package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.noop.R
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import java.util.Locale
import kotlin.math.roundToInt

// Today tab: the Home heart card, the one summary card (scores and vitals) and the first-week line.
// Split out of TodayScreen.kt.

// MARK: - Home tab cards
//
// The one-screen Home: a heart-rate card led by the live bpm, then one summary card: the three daily
// scores in thin score rings, each with a small change chip against its own trailing 7-day average, and
// under a hairline the three recovery vitals as a quiet row of plain numbers.
// Neutral surfaces; colour sits on the numbers, rings and chips only: the live bpm takes its heart-rate
// zone colour, Charge and Effort their own score ramps, Rest a good/fair/poor band. The live bpm and the
// three score numbers carry a faint glow in their colour.

/** Placeholder for a number Home has no value for. */
private const val HOME_EMPTY_VALUE = "—"

/**
 * Home's heart-rate card: the live bpm (or, with no strap streaming, the latest 5-minute average) over
 * the day's heart-rate line. [note] replaces the chart when the day has too little heart rate to draw.
 */
@Composable
internal fun HomeHeartCard(
    liveBpm: Int?,
    latestAvg: Int?,
    note: String?,
    chart: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current
    val zoneSet = remember { ProfileStore.from(context.applicationContext).hrZoneSet }
    val zone = liveBpm?.let { zoneSet.zoneNumber(it.toDouble()) } ?: 0
    val shown = liveBpm ?: latestAvg
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                Text(
                    uiString(R.string.today_section_heart_rate),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (liveBpm != null) {
                    LivePulseDot()
                    Text(uiString(R.string.home_hr_live), style = NoopType.caption, color = Palette.accent)
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                // Only a streaming bpm inside a zone is tinted (and glows); the stored average stays neutral.
                val bpmColor = when {
                    shown == null -> Palette.textTertiary
                    liveBpm != null && zone >= 1 -> Palette.hrZoneColor(zone)
                    else -> Palette.textPrimary
                }
                Text(
                    shown?.toString() ?: HOME_EMPTY_VALUE,
                    style = NoopType.number(48f).withCalmGlow(
                        if (liveBpm != null && zone >= 1) bpmColor else null,
                    ),
                    color = bpmColor,
                )
                Text(
                    uiString(R.string.today_unit_bpm),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    modifier = Modifier.padding(bottom = Metrics.space8),
                )
                Spacer(modifier = Modifier.weight(1f))
                // With nothing streaming the number is the latest stored average, so the label says so;
                // with no number at all there is nothing to label.
                val status = when {
                    liveBpm != null && zone >= 1 -> uiString(R.string.home_hr_zone, zone)
                    liveBpm != null -> uiString(R.string.home_hr_below_zones)
                    shown != null -> uiString(R.string.home_hr_not_streaming)
                    else -> null
                }
                if (status != null) {
                    Text(
                        status,
                        style = NoopType.caption,
                        color = if (liveBpm != null && zone >= 1) Palette.hrZoneColor(zone) else Palette.textTertiary,
                        modifier = Modifier.padding(bottom = Metrics.space10),
                    )
                }
            }
            chart?.invoke()
            note?.let { Text(it, style = NoopType.footnote, color = Palette.textTertiary) }
        }
    }
}

/** The live signature: a small rose dot that breathes while the strap streams, posed still (#909). */
@Composable
private fun LivePulseDot() {
    val still = rememberPoseStill()
    val pulse = if (still) {
        1f
    } else {
        val transition = rememberInfiniteTransition()
        val a by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        )
        a
    }
    Box(
        modifier = Modifier
            .size(Metrics.space8)
            .graphicsLayer { alpha = pulse }
            .background(Palette.metricRose, CircleShape),
    )
}

/**
 * Home's one summary card: Charge, Effort and Rest as score rings, each with a small change chip against
 * its own trailing 7-day average (one "vs last 7 days" caption names the comparison for all three), then,
 * under a hairline, last night's HRV, resting heart rate and breathing rate as a quiet row of plain
 * numbers with no comparison.
 */
@Composable
internal fun HomeSummaryCard(
    day: DailyMetric?,
    vitalsDay: DailyMetric?,
    days: List<DailyMetric>,
    selectedDayKey: String,
    effortForDay: Double?,
    effortScale: EffortScale,
    restScore: Double?,
    restHistory: Map<String, Double>,
    chargeConfidence: ScoreConfidence?,
    onChargeTap: () -> Unit,
    onOpenMetric: (String) -> Unit,
) {
    val charge = day?.recovery
    // A Charge whose HRV baseline is still thin says so in place of its change chip, the same rule the
    // Metrics tile's note follows (chargeEstimateNote), so the two surfaces flag the same days.
    val chargeLearning = chargeEstimateNote(charge != null, chargeConfidence) == EstimateNote.STILL_LEARNING
    val chargeAvg = remember(days, selectedDayKey) { trailingAverage(days.seriesOf { it.recovery }, selectedDayKey) }
    val effortAvg = remember(days, selectedDayKey) { trailingAverage(days.seriesOf { it.strain }, selectedDayKey) }
    val restAvg = remember(restHistory, selectedDayKey) { trailingAverage(restHistory, selectedDayKey) }
    val effortDecimals = if (effortScale == EffortScale.WHOOP) 1 else 0
    val wholeNumber: (Double) -> String = { it.roundToInt().toString() }
    val oneDecimal: (Double) -> String = { String.format(Locale.getDefault(), "%.1f", it) }
    val chargeChange = scoreChange(charge, chargeAvg, chargeLearning, wholeNumber)
    val restChange = scoreChange(restScore, restAvg, learning = false, formatDelta = wholeNumber)
    // Effort has no healthy direction (more or less effort is neither good nor bad), so its chip stays
    // neutral whichever way it moved.
    val effortChange = when {
        effortForDay != null && effortAvg != null -> HomeChange.Chip(
            delta = UnitFormatter.effortValue(effortForDay, effortScale) -
                UnitFormatter.effortValue(effortAvg, effortScale),
            tone = null,
            formatDelta = if (effortDecimals == 0) wholeNumber else oneDecimal,
        )
        effortForDay != null -> HomeChange.Learning
        else -> HomeChange.None
    }
    val anyChip = chargeChange is HomeChange.Chip || effortChange is HomeChange.Chip ||
        restChange is HomeChange.Chip

    // Per field, today first, the same carry rule as the Recovery vitals section on Metrics.
    val hrv = day?.avgHrv ?: vitalsDay?.avgHrv
    val rhr = (day?.restingHr ?: vitalsDay?.restingHr)?.toDouble()
    val resp = day?.respRateBpm ?: vitalsDay?.respRateBpm
    fun open(card: DashboardCard): (() -> Unit)? = dashboardCardMetricKey(card)?.let { key -> { onOpenMetric(key) } }

    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            if (anyChip) {
                Text(
                    uiString(R.string.home_vs_last_7_days),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                HomeScoreRing(
                    label = uiString(R.string.today_metric_charge),
                    value = charge?.roundToInt()?.toString(),
                    // Charge and Rest are 0-100 scores; Effort is stored on the 0-100 scale whatever the
                    // display scale, so all three rings fill by value / 100.
                    fraction = ((charge ?: 0.0) / 100.0).toFloat(),
                    change = chargeChange,
                    valueColor = charge?.let { Palette.recoveryColor(it) },
                    onClick = onChargeTap,
                    modifier = Modifier.weight(1f),
                )
                HomeScoreRing(
                    label = uiString(R.string.today_metric_effort),
                    value = effortForDay?.let {
                        if (effortDecimals == 0) UnitFormatter.effortValue(it, effortScale).roundToInt().toString()
                        else UnitFormatter.effortDisplay(it, effortScale)
                    },
                    fraction = ((effortForDay ?: 0.0) / 100.0).toFloat(),
                    change = effortChange,
                    valueColor = effortForDay?.let { Palette.strainColor(it) },
                    onClick = { onOpenMetric(HERO_EFFORT_METRIC_KEY) },
                    modifier = Modifier.weight(1f),
                )
                HomeScoreRing(
                    label = uiString(R.string.today_metric_rest),
                    value = restScore?.roundToInt()?.toString(),
                    fraction = ((restScore ?: 0.0) / 100.0).toFloat(),
                    change = restChange,
                    valueColor = restScore?.let { homeToneColor(restTone(it)) },
                    onClick = { onOpenMetric(HERO_REST_METRIC_KEY) },
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = Palette.hairline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                HomeVital(
                    label = uiString(R.string.today_card_hrv),
                    value = hrv?.roundToInt()?.toString(),
                    unit = DashboardCard.HRV.unit,
                    onClick = open(DashboardCard.HRV),
                    modifier = Modifier.weight(1f),
                )
                HomeVital(
                    label = uiString(R.string.today_card_resting_hr),
                    value = rhr?.roundToInt()?.toString(),
                    unit = DashboardCard.RESTING_HR.unit,
                    onClick = open(DashboardCard.RESTING_HR),
                    modifier = Modifier.weight(1f),
                )
                HomeVital(
                    label = uiString(R.string.today_card_respiratory),
                    value = resp?.let { String.format(Locale.getDefault(), "%.1f", it) },
                    unit = DashboardCard.RESPIRATORY.unit,
                    onClick = open(DashboardCard.RESPIRATORY),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The change chip for Charge or Rest: the day against its trailing average, green when higher and amber
 * when lower (neutral inside the tolerance). "Still learning" when the score exists but there is no
 * average yet, or when [learning] says the score itself is still an estimate; nothing with no score.
 */
private fun scoreChange(
    value: Double?,
    average: Double?,
    learning: Boolean,
    formatDelta: (Double) -> String,
): HomeChange = when {
    learning -> HomeChange.Learning
    value != null && average != null -> HomeChange.Chip(
        delta = value - average,
        tone = homeChipTone(vitalTrendTone(value, average, higherIsBetter = true)),
        formatDelta = formatDelta,
    )
    value != null -> HomeChange.Learning
    else -> HomeChange.None
}

/** What sits under a Home score ring: a change chip, the still-learning caption, or nothing. */
private sealed class HomeChange {
    data class Chip(val delta: Double, val tone: HomeTone?, val formatDelta: (Double) -> String) : HomeChange()
    data object Learning : HomeChange()
    data object None : HomeChange()
}

/**
 * One daily score on Home: its label over a thin score ring with the number inside, then its change chip
 * (or the still-learning caption), all centred. The ring fills by [fraction] (0..1) in [valueColor]; with
 * no value it shows only the empty track and a dash.
 */
@Composable
private fun HomeScoreRing(
    label: String,
    value: String?,
    fraction: Float,
    change: HomeChange,
    valueColor: Color?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val numberColor = if (value != null) valueColor ?: Palette.textPrimary else Palette.textTertiary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Metrics.space12))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = Metrics.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        Text(label, style = NoopType.subhead, color = Palette.textSecondary, maxLines = 1)
        Box(modifier = Modifier.size(Metrics.scoreRing), contentAlignment = Alignment.Center) {
            GlowRing(
                fraction = if (value != null) fraction.coerceIn(0f, 1f) else 0f,
                value = 0.0,
                color = valueColor ?: Palette.textTertiary,
                diameter = Metrics.scoreRing,
                lineWidth = Metrics.scoreRingStroke,
                showsLabel = false,
            )
            Text(
                value ?: HOME_EMPTY_VALUE,
                style = NoopType.number(22f).withCalmGlow(if (value != null) valueColor else null),
                color = numberColor,
                maxLines = 1,
            )
        }
        when (change) {
            is HomeChange.Chip -> ComparisonChip(
                delta = change.delta,
                tone = change.tone,
                formatDelta = change.formatDelta,
            )
            HomeChange.Learning -> Text(
                uiString(R.string.home_still_learning),
                style = NoopType.caption,
                color = Palette.textTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            HomeChange.None -> Unit
        }
    }
}

/**
 * One vital in the summary card's quiet row: label, then the number with its unit. No comparison and no
 * tone colour; the vital's detail screen carries the trend.
 */
@Composable
private fun HomeVital(
    label: String,
    value: String?,
    unit: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Metrics.space8))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = Metrics.space4),
        verticalArrangement = Arrangement.spacedBy(Metrics.space2),
    ) {
        Text(label, style = NoopType.caption, color = Palette.textSecondary, maxLines = 1)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space4),
        ) {
            Text(
                value ?: HOME_EMPTY_VALUE,
                style = NoopType.number(18f),
                color = if (value != null) Palette.textPrimary else Palette.textTertiary,
                maxLines = 1,
            )
            if (unit != null && value != null) {
                Text(
                    unit,
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                    modifier = Modifier.padding(bottom = Metrics.space2),
                )
            }
        }
    }
}

/**
 * The first-week line on Today: one short sentence where the scores will be, with an optional × that
 * files it into the Updates inbox (restorable from there).
 */
@Composable
internal fun TodayPendingLine(text: String, onDismiss: (() -> Unit)?) {
    NoopCard(padding = Metrics.space12) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        ) {
            Text(
                text,
                style = NoopType.subhead,
                color = Palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) TodayCardDismissButton(onClick = onDismiss)
        }
    }
}

/** The status colour for a Home tone: positive, warning or critical. Shared with the Metrics tiles. */
internal fun homeToneColor(tone: HomeTone): Color = when (tone) {
    HomeTone.GOOD -> Palette.statusPositive
    HomeTone.FAIR -> Palette.statusWarning
    HomeTone.POOR -> Palette.statusCritical
}
