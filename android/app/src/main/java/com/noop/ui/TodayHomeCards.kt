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

// Today tab: the Home heart, score and vitals cards.
// Split out of TodayScreen.kt; behaviour unchanged.

// MARK: - Home tab cards
//
// The one-screen Home: a heart-rate card led by the live bpm, then the three daily scores in thin score
// rings and the three recovery vitals as plain numbers, each set against its own trailing 7-day average.
// Neutral surfaces; colour sits on the numbers and rings only: the live bpm takes its heart-rate zone
// colour, Charge and Effort their own score ramps, Rest a good/fair/poor band, and each vital whether it
// beat its 7-day average. The live bpm and the three score numbers carry a faint glow in their colour.

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

/** Charge, Effort and Rest for the selected day, each against its own trailing 7-day average. */
@Composable
internal fun HomeScoreCard(
    day: DailyMetric?,
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
    // A Charge whose HRV baseline is still thin says so in place of its comparison line, the same rule the
    // Metrics tile's note follows (chargeEstimateNote), so the two surfaces flag the same days.
    val chargeLearning = chargeEstimateNote(charge != null, chargeConfidence) == EstimateNote.STILL_LEARNING
    val chargeAvg = remember(days, selectedDayKey) { trailingAverage(days.seriesOf { it.recovery }, selectedDayKey) }
    val effortAvg = remember(days, selectedDayKey) { trailingAverage(days.seriesOf { it.strain }, selectedDayKey) }
    val restAvg = remember(restHistory, selectedDayKey) { trailingAverage(restHistory, selectedDayKey) }
    val effortDecimals = if (effortScale == EffortScale.WHOOP) 1 else 0
    NoopCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            HomeScoreRing(
                label = uiString(R.string.today_metric_charge),
                value = charge?.roundToInt()?.toString(),
                // Charge and Rest are 0-100 scores; Effort is stored on the 0-100 scale whatever the
                // display scale, so all three rings fill by value / 100.
                fraction = ((charge ?: 0.0) / 100.0).toFloat(),
                comparison = if (chargeLearning) {
                    uiString(R.string.home_still_learning)
                } else {
                    homeComparison(charge, chargeAvg, decimals = 0)
                },
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
                comparison = homeComparison(
                    effortForDay?.let { UnitFormatter.effortValue(it, effortScale) },
                    effortAvg?.let { UnitFormatter.effortValue(it, effortScale) },
                    decimals = effortDecimals,
                ),
                valueColor = effortForDay?.let { Palette.strainColor(it) },
                onClick = { onOpenMetric(HERO_EFFORT_METRIC_KEY) },
                modifier = Modifier.weight(1f),
            )
            HomeScoreRing(
                label = uiString(R.string.today_metric_rest),
                value = restScore?.roundToInt()?.toString(),
                fraction = ((restScore ?: 0.0) / 100.0).toFloat(),
                comparison = homeComparison(restScore, restAvg, decimals = 0),
                valueColor = restScore?.let { homeToneColor(restTone(it)) },
                onClick = { onOpenMetric(HERO_REST_METRIC_KEY) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One daily score on Home: its label over a thin score ring with the number inside, then the comparison
 * line, all centred. The ring fills by [fraction] (0..1) in [valueColor]; with no value it shows only the
 * empty track and a dash.
 */
@Composable
private fun HomeScoreRing(
    label: String,
    value: String?,
    fraction: Float,
    comparison: String,
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
        Text(
            comparison,
            style = NoopType.caption,
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** HRV, resting heart rate and breathing rate from last night, each against its trailing 7-day average. */
@Composable
internal fun HomeVitalsCard(
    day: DailyMetric?,
    vitalsDay: DailyMetric?,
    days: List<DailyMetric>,
    selectedDayKey: String,
    onOpenMetric: (String) -> Unit,
) {
    // Per field, today first, the same carry rule as the Recovery vitals section on Metrics.
    val hrv = day?.avgHrv ?: vitalsDay?.avgHrv
    val rhr = (day?.restingHr ?: vitalsDay?.restingHr)?.toDouble()
    val resp = day?.respRateBpm ?: vitalsDay?.respRateBpm
    val hrvAvg = remember(days, selectedDayKey) { trailingAverage(days.seriesOf { it.avgHrv }, selectedDayKey) }
    val rhrAvg = remember(days, selectedDayKey) {
        trailingAverage(days.seriesOf { it.restingHr?.toDouble() }, selectedDayKey)
    }
    val respAvg = remember(days, selectedDayKey) { trailingAverage(days.seriesOf { it.respRateBpm }, selectedDayKey) }
    fun open(card: DashboardCard): (() -> Unit)? = dashboardCardMetricKey(card)?.let { key -> { onOpenMetric(key) } }
    NoopCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        ) {
            HomeStat(
                label = uiString(R.string.today_card_hrv),
                value = hrv?.roundToInt()?.toString(),
                unit = DashboardCard.HRV.unit,
                comparison = homeComparison(hrv, hrvAvg, decimals = 0),
                valueColor = vitalTrendTone(hrv, hrvAvg, higherIsBetter = true)?.let { homeToneColor(it) },
                onClick = open(DashboardCard.HRV),
                compact = true,
                modifier = Modifier.weight(1f),
            )
            HomeStat(
                label = uiString(R.string.today_card_resting_hr),
                value = rhr?.roundToInt()?.toString(),
                unit = DashboardCard.RESTING_HR.unit,
                comparison = homeComparison(rhr, rhrAvg, decimals = 0),
                valueColor = vitalTrendTone(rhr, rhrAvg, higherIsBetter = false)?.let { homeToneColor(it) },
                onClick = open(DashboardCard.RESTING_HR),
                compact = true,
                modifier = Modifier.weight(1f),
            )
            HomeStat(
                label = uiString(R.string.today_card_respiratory),
                value = resp?.let { String.format(Locale.getDefault(), "%.1f", it) },
                unit = DashboardCard.RESPIRATORY.unit,
                comparison = homeComparison(resp, respAvg, decimals = 1),
                valueColor = vitalTrendTone(resp, respAvg, higherIsBetter = false)?.let { homeToneColor(it) },
                onClick = open(DashboardCard.RESPIRATORY),
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The status colour for a Home tone: positive, warning or critical. Shared with the Metrics tiles. */
internal fun homeToneColor(tone: HomeTone): Color = when (tone) {
    HomeTone.GOOD -> Palette.statusPositive
    HomeTone.FAIR -> Palette.statusWarning
    HomeTone.POOR -> Palette.statusCritical
}

/** "+4 vs 7-day avg", or the no-average line when either side of the comparison is missing. */
@Composable
private fun homeComparison(value: Double?, average: Double?, decimals: Int): String =
    if (value != null && average != null) {
        uiString(R.string.home_vs_7day_avg, signedDelta(value - average, decimals))
    } else {
        uiString(R.string.home_no_7day_avg)
    }

/**
 * One figure in a Home card: label, number with an optional unit, and its comparison line. [valueColor]
 * tints the number; null keeps it neutral.
 */
@Composable
private fun HomeStat(
    label: String,
    value: String?,
    comparison: String,
    onClick: (() -> Unit)?,
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
    unit: String? = null,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Metrics.space8))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = Metrics.space4),
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Text(label, style = NoopType.subhead, color = Palette.textSecondary, maxLines = 1)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space4),
        ) {
            Text(
                value ?: HOME_EMPTY_VALUE,
                style = NoopType.number(if (compact) 24f else 30f),
                color = if (value != null) valueColor ?: Palette.textPrimary else Palette.textTertiary,
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
        Text(comparison, style = NoopType.caption, color = Palette.textTertiary, maxLines = 2)
    }
}
