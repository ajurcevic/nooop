package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.ChargeBaselines
import com.noop.analytics.ChargeDriver
import com.noop.analytics.ChargeDriverLabel
import com.noop.analytics.ChargeDriverUnit
import com.noop.analytics.RecoveryDrivers
import com.noop.analytics.ChargeDriverVerdict
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import java.util.Locale
import kotlin.math.roundToInt

// Today tab: the Charge breakdown sheet, its drivers and contributors, and score-state notes.
// Split out of TodayScreen.kt; behaviour unchanged.

/**
 * A1/S4: the Charge breakdown sheet opened by tapping the hero Charge ring. A full-screen surface with a
 * titled top bar (Close) and a scrollable body hosting the existing What-shaped-it breakdown, the
 * Contributors bars and (S4) the folded Readiness card. Built only when shown (the caller gates on
 * showChargeBreakdown), so the heavy rows materialise on tap (#819). Nothing is recomputed here, it reuses
 * the existing sections, which read the SAME carried/today row the ring shows. Mirrors iOS chargeBreakdownSheet.
 * `internal` (not private) so the Coupled view's hero ring (task #43) opens THIS same sheet, one breakdown,
 * never a duplicate.
 */
@Composable
internal fun ChargeBreakdownSheet(
    days: List<DailyMetric>,
    chargeBaselines: ChargeBaselines.Resolved?,
    displayDay: DailyMetric?,
    carriedDay: DailyMetric?,
    showReadiness: Boolean,
    onClose: () -> Unit,
    onHowCalculated: () -> Unit,
    // #1995: the Charge ring opens THIS sheet rather than the trend, because the sheet's other entry
    // (the Synthesis card) sits in a section the user can hide, so routing the ring away could orphan it.
    // The trend is therefore reachable from inside, which keeps every hero ring leading to its own
    // score's detail while Charge's stays the richer one.
    //
    // Null renders NO row, which is why CoupledScreen passes nothing: that screen has no metric
    // navigation at all, so a link there would be a dead one. A host that cannot go somewhere should
    // not offer to.
    onOpenTrend: (() -> Unit)? = null,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.screenPadding, vertical = Metrics.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    uiString(R.string.l10n_today_screen_what_shaped_your_charge_9f53a2b3),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = uiString(R.string.l10n_today_screen_close_bbfa773e), tint = Palette.textSecondary)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Metrics.screenPadding)
                    .padding(bottom = Metrics.sectionGap),
                verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
            ) {
                // The breakdown self-gates: a calibrating night (empty drivers) renders nothing here, the
                // Contributors + Readiness below still give an honest read, never a blank sheet.
                RecoveryDriversSection(chargeBaselines = chargeBaselines, displayDay = displayDay, carriedDay = carriedDay)
                RecoveryContributorsSection(day = displayDay, carriedDay = carriedDay)
                // S4: the SEPARATE Readiness block now lives here behind the Charge-ring tap (today-only,
                // matching the old inline gate). The Metrics tab's readiness line keeps the one-word read
                // (Primed / Balanced / Strained / Run down) behind the same today-only gate.
                if (showReadiness) ReadinessSection(days, carriedDay = carriedDay)
                onOpenTrend?.let { openTrend ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                onClickLabel = uiString(R.string.l10n_today_screen_see_the_charge_trend_9dcbcff7),
                                onClick = openTrend,
                            )
                            .background(Palette.surfaceInset)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = null,
                            tint = DomainTheme.Charge.color,
                            modifier = Modifier.size(14.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                uiString(R.string.l10n_today_screen_see_the_charge_trend_9dcbcff7),
                                style = NoopType.subhead,
                                color = Palette.textPrimary,
                            )
                            Text(
                                uiString(R.string.l10n_today_screen_every_day_s_score_over_time_a00ea7c7),
                                style = NoopType.caption,
                                color = Palette.textTertiary,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Palette.textTertiary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                // Everything above is what shaped YOUR Charge today; this opens the general METHOD behind the
                // score, so the two are clearly separated, not conflated. Opens the scoring guide at the
                // Charge section, the same target the per-ring ⓘ buttons use. Mirrors the iOS chargeBreakdown
                // "How Charge is calculated" NavigationLink to ScoringGuideView(initialSection: .charge).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            onClickLabel = uiString(R.string.today_action_how_charge),
                            onClick = onHowCalculated,
                        )
                        .background(Palette.surfaceInset)
                        .padding(14.dp)
                        .semantics {
                            contentDescription = uiString(R.string.l10n_today_screen_how_charge_is_calculated_the_method_3ea7548b)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Functions,
                        contentDescription = null,
                        tint = DomainTheme.Charge.color,
                        modifier = Modifier.size(14.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            uiString(R.string.l10n_today_screen_how_charge_is_calculated_142d5e8e),
                            style = NoopType.subhead,
                            color = Palette.textPrimary,
                        )
                        Text(
                            uiString(R.string.l10n_today_screen_the_method_behind_the_score_not_5bc68508),
                            style = NoopType.caption,
                            color = Palette.textTertiary,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

// MARK: - "What shaped it" the engine-computed Charge driver breakdown
//
// The SHARED-CONTRACT driver rows under the Charge ring: one row per REAL term the recovery scorer used,
// each carrying its signed point contribution (deltaPoints), the night's value, the personal baseline it
// was scored against, and a short plain-English verdict. Computed by RecoveryDrivers.chargeDrivers from
// the SAME inputs the Charge ring reads, so a row can never describe a term the score did not use; a
// missing input yields NO row (never a faked zero). The confidence dot + tier tag SURFACE the existing
// ScoreConfidence.forCharge: they are read, not recomputed. Hidden entirely when the day can't score
// (cold-start / no drivers). Byte-aligned with the iOS "What shaped it" section. No em-dashes.

@Composable
internal fun RecoveryDriversSection(
    chargeBaselines: ChargeBaselines.Resolved?,
    displayDay: DailyMetric?,
    carriedDay: DailyMetric? = null,
) {
    // Read the row the Charge ring itself reads: today's own when scored, else the carried last-scored
    // day (#543) so the breakdown matches the carried ring instead of vanishing at the rollover. The
    // baselines already carry both recalibration epochs (#2315), applied where they were resolved (#2525).
    val readDay = carriedDay ?: displayDay
    val drivers = remember(chargeBaselines, readDay) { recoveryChargeDrivers(chargeBaselines, readDay) }
    if (drivers.isEmpty()) return

    val tier = remember(chargeBaselines, readDay) { chargeConfidenceTier(chargeBaselines, readDay) }
    val overline = carriedDay?.let { uiString(R.string.today_charge_carried, carriedCaption(it.day).localized()) }
        ?: uiString(R.string.trends_charge)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Header row: section title + the SURFACED confidence pill (dot + tier tag) on the right.
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader(uiString(R.string.today_what_shaped_it), overline = overline, trailing = uiString(R.string.today_vs_your_baseline))
            }
            ChargeConfidencePill(tier)
        }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                drivers.forEach { DriverRow(it) }
                Text(
                    uiString(R.string.today_charge_driver_guidance),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** The SURFACED Charge confidence pill (dot + tier tag). Reads the existing ScoreConfidence, never
 *  recomputes it. SOLID = gold/accent, BUILDING = the blue warning tone, CALIBRATING = neutral slate. */
@Composable
private fun ChargeConfidencePill(tier: ScoreConfidence) {
    val (label, tone) = when (tier) {
        ScoreConfidence.SOLID -> uiString(R.string.today_confidence_solid) to StrandTone.Accent
        ScoreConfidence.BUILDING -> uiString(R.string.today_confidence_building) to StrandTone.Warning
        ScoreConfidence.CALIBRATING -> uiString(R.string.today_confidence_calibrating) to StrandTone.Neutral
    }
    StatePill(title = label, tone = tone)
}

/** One "What shaped it" driver row: an up/down delta chip (signed points, green up / red down),
 *  the label + verdict, and the value over its baseline. Mirrors the iOS driver row layout. */
@Composable
private fun DriverRow(driver: ChargeDriver) {
    val positive = driver.deltaPoints >= 0
    // A zero delta reads neutral (no green/red), not a misleading "good".
    val tone = when {
        driver.deltaPoints > 0 -> Palette.statusPositive
        driver.deltaPoints < 0 -> Palette.statusCritical
        else -> Palette.textTertiary
    }
    val signed = if (driver.deltaPoints > 0) "+${driver.deltaPoints}" else "${driver.deltaPoints}"
    val label = when (driver.label) {
        ChargeDriverLabel.HEART_RATE_VARIABILITY -> uiString(R.string.today_driver_hrv)
        ChargeDriverLabel.RESTING_HEART_RATE -> uiString(R.string.today_driver_resting_hr)
        ChargeDriverLabel.SLEEP_QUALITY -> uiString(R.string.today_driver_sleep_quality)
        ChargeDriverLabel.RESPIRATORY_RATE -> uiString(R.string.today_driver_respiratory)
        ChargeDriverLabel.SKIN_TEMPERATURE -> uiString(R.string.today_driver_skin_temperature)
    }
    val valueText = when (driver.unit) {
        ChargeDriverUnit.MILLISECONDS -> uiString(R.string.today_driver_value_ms, driver.value.roundToInt())
        ChargeDriverUnit.BEATS_PER_MINUTE -> uiString(R.string.today_driver_value_bpm, driver.value.roundToInt())
        ChargeDriverUnit.PERCENT -> uiString(R.string.today_driver_value_percent, driver.value.roundToInt())
        ChargeDriverUnit.BREATHS_PER_MINUTE -> uiString(
            R.string.today_driver_value_br_min,
            String.format(Locale.getDefault(), "%.1f", RecoveryDrivers.displayRounded(driver.value, 1)),
        )
        ChargeDriverUnit.CELSIUS_DEVIATION -> uiString(
            R.string.today_driver_value_temp_deviation,
            String.format(Locale.getDefault(), "%+.1f", driver.value),
        )
    }
    val baselineText = driver.baseline?.let { baseline -> when (driver.unit) {
        ChargeDriverUnit.MILLISECONDS -> uiString(R.string.today_driver_baseline_ms, baseline.roundToInt())
        ChargeDriverUnit.BEATS_PER_MINUTE -> uiString(R.string.today_driver_baseline_bpm, baseline.roundToInt())
        ChargeDriverUnit.BREATHS_PER_MINUTE -> uiString(
            R.string.today_driver_baseline_br_min,
            String.format(Locale.getDefault(), "%.1f", RecoveryDrivers.displayRounded(baseline, 1)),
        )
        ChargeDriverUnit.PERCENT, ChargeDriverUnit.CELSIUS_DEVIATION -> ""
    } } ?: ""
    val verdict = when (driver.verdict) {
        ChargeDriverVerdict.ABOVE_BASELINE_SUPPORTING -> uiString(R.string.today_driver_above_supporting)
        ChargeDriverVerdict.BELOW_BASELINE_SUPPORTING -> uiString(R.string.today_driver_below_supporting)
        ChargeDriverVerdict.ABOVE_BASELINE_LIMITING -> uiString(R.string.today_driver_above_limiting)
        ChargeDriverVerdict.BELOW_BASELINE_LIMITING -> uiString(R.string.today_driver_below_limiting)
        ChargeDriverVerdict.SLIGHTLY_ABOVE_BASELINE_SUPPORTING ->
            uiString(R.string.today_driver_slightly_above_supporting)
        ChargeDriverVerdict.SLIGHTLY_BELOW_BASELINE_SUPPORTING ->
            uiString(R.string.today_driver_slightly_below_supporting)
        ChargeDriverVerdict.SLIGHTLY_ABOVE_BASELINE_LIMITING ->
            uiString(R.string.today_driver_slightly_above_limiting)
        ChargeDriverVerdict.SLIGHTLY_BELOW_BASELINE_LIMITING ->
            uiString(R.string.today_driver_slightly_below_limiting)
        ChargeDriverVerdict.ABOVE_BASELINE_TOO_SMALL -> uiString(R.string.today_driver_above_too_small)
        ChargeDriverVerdict.BELOW_BASELINE_TOO_SMALL -> uiString(R.string.today_driver_below_too_small)
        ChargeDriverVerdict.AT_BASELINE -> uiString(R.string.today_driver_at_baseline)
        ChargeDriverVerdict.HRV_SATURATION_LIMITING -> uiString(R.string.today_driver_hrv_saturation)
        ChargeDriverVerdict.STRONG_NIGHT_SUPPORTING -> uiString(R.string.today_driver_strong_night)
        ChargeDriverVerdict.BELOW_GOOD_NIGHT_LIMITING -> uiString(R.string.today_driver_below_good_night)
        ChargeDriverVerdict.TYPICAL_NIGHT -> uiString(R.string.today_driver_typical_night)
        ChargeDriverVerdict.NEAR_BASELINE -> uiString(R.string.today_driver_near_baseline)
        ChargeDriverVerdict.WARMER_THAN_BASELINE_LIMITING -> uiString(R.string.today_driver_warmer_limiting)
        ChargeDriverVerdict.COOLER_THAN_BASELINE_LIMITING -> uiString(R.string.today_driver_cooler_limiting)
    }
    val driverA11y = uiString(R.string.today_driver_a11y, label, valueText, baselineText, signed, verdict)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics { contentDescription = driverA11y },
    ) {
        // Signed-point delta chip with a direction glyph.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(Metrics.cornerPill))
                .background(tone.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (driver.deltaPoints != 0) {
                Icon(
                    if (positive) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(uiString(R.string.l10n_today_screen_signed_pts_5ea85678, signed), style = NoopType.captionNumber, color = tone)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = NoopType.headline, color = Palette.textPrimary)
            Text(verdict, style = NoopType.footnote, color = Palette.textSecondary)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(valueText, style = NoopType.captionNumber, color = Palette.textPrimary)
            Text(baselineText, style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

// MARK: - Recovery contributors (README screen #5), labelled progress bars
//
// "CONTRIBUTORS", what drove today's Charge, each as a labelled progress bar in the shared stage/zone
// bar style (inset track, round-capped metric-hue fill, right-aligned read-out). Design-Reset tokens
// (iOS RecoveryContributorsSection parity): HRV reads teal (metricCyan), Resting HR the recovery/Charge
// world (chargeColor), Sleep and Respiratory the blue sleep world. Each bar's fraction is a
// presentation-only normalisation of the day's value to a typical adult span, no scoring/logic change.
// Suppressed entirely until at least one contributor has a value.

@Composable
private fun RecoveryContributorsSection(day: DailyMetric?, carriedDay: DailyMetric? = null) {
    // The row the contributors read from: today's own when it carries recovery, else the carried last
    // scored day (#543) so the bars don't all read "No Data" at the rollover while live HR ticks. The
    // overline stamps "Last night · <date>" when carrying so the prior read isn't passed off as today's.
    val cd = carriedDay ?: day
    val hrv = cd?.avgHrv
    val rhr = cd?.restingHr?.toDouble()
    val sleepMin = cd?.totalSleepMin
    val resp = cd?.respRateBpm
    if (hrv == null && rhr == null && sleepMin == null && resp == null) return

    val overline = carriedDay?.let { uiString(R.string.today_recovery_carried, carriedCaption(it.day).localized()) }
        ?: uiString(R.string.l10n_today_screen_recovery_ea924f72)
    SectionHeader(uiString(R.string.today_contributors), overline = overline, trailing = uiString(R.string.today_what_drove_charge))
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            // HRV, higher is better; map a typical 20–120 ms span. Teal (its biometric hue; iOS metricCyan).
            ContributorBar(
                label = uiString(R.string.today_metric_hrv),
                readout = hrv?.let { "${it.roundToInt()} ms" } ?: NO_DATA,
                fraction = hrv?.let { ((it - 20.0) / 100.0) },
                color = Palette.metricCyan,
            )
            // Resting HR, lower is better, so invert a typical 40–80 bpm span. Charge/recovery world (iOS
            // chargeColor, the recovery contributor reads on the WHOOP-green Charge world, not gold).
            ContributorBar(
                label = uiString(R.string.l10n_today_screen_resting_hr_26677094),
                readout = rhr?.let { "${it.roundToInt()} bpm" } ?: NO_DATA,
                fraction = rhr?.let { 1.0 - ((it - 40.0) / 40.0) },
                color = Palette.chargeColor,
            )
            // Sleep, hours in bed against an 8h target. Blue (sleep world).
            ContributorBar(
                label = uiString(R.string.l10n_today_screen_sleep_3cac34e6),
                readout = sleepMin?.let { sleepValue(cd) } ?: NO_DATA,
                fraction = sleepMin?.let { (it / 60.0) / 8.0 },
                color = Palette.sleepLight,
            )
            // Respiratory, stability around a typical 12–20 rpm span. Deep blue (sleep world).
            ContributorBar(
                label = uiString(R.string.l10n_today_screen_respiratory_1cd8c175),
                readout = resp?.let { String.format(Locale.getDefault(), "%.1f rpm", it) } ?: NO_DATA,
                fraction = resp?.let { 1.0 - ((it - 12.0) / 8.0) },
                color = Palette.sleepDeep,
            )
            Text(
                uiString(R.string.l10n_today_screen_baselines_learned_on_device_over_14_359f6812),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** One labelled contributor bar: a label + right-aligned read-out over a liquid TUBE filled to [fraction].
 *  These ARE genuine single-value progress bars (each signal against a typical adult span), so the liquid
 *  finish reads well here (matching how the iOS liquid Today draws its single-value goal/strain bars as
 *  tubes). Static (not per-frame) — they sit in the tapped-open Charge breakdown, not a live surface, so
 *  `animated = false` keeps the sheet cheap. A null fraction renders an empty tube. */
@Composable
private fun ContributorBar(label: String, readout: String, fraction: Double?, color: Color) {
    val fillFrac = fraction?.coerceIn(0.0, 1.0) ?: 0.0
    val displayReadout = localizedMetricValue(readout)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(label, modifier = Modifier.weight(1f))
            Text(displayReadout, style = NoopType.captionNumber, color = Palette.textPrimary)
        }
        LiquidTube(
            frac = fillFrac,
            tint = color,
            height = Metrics.progressHeight,
            animated = false,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = uiString(R.string.l10n_today_screen_label_readout_3f166607, label, displayReadout) },
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════════════════════════════
// Explainability layer, COMPONENTS 2, 3, 4 (spec: 2026-06-20-sleep-guidance-explainability.md)
//
// "No bare number without a STATE, a REASON, and a NEXT STEP." Every uncertain or derived read-out on
// Today gets a clear state, a plain-English reason and a next step, and we NEVER fabricate a number:
// calibrating / needs-strap show NO value, carried values are always stamped with their date, and the
// provenance badge reflects the REAL per-day merge winner. The score-state copy here is VERBATIM and
// must match the Swift Today lane word-for-word. No em-dashes anywhere.
// ════════════════════════════════════════════════════════════════════════════════════════════════════

// ── COMPONENT 2, explained score states ─────────────────────────────────────────────────────────────

/** The score-state title, LOCALIZED:
 *  [ScoreState.title] is the English parity contract asserted verbatim against Swift in
 *  `TodayExplainabilityTest`, so the resource lookup lives here rather than in the pure mapper. */
@Composable
internal fun scoreStateTitle(state: ScoreState): String = when (state) {
    is ScoreState.Scored -> ""
    is ScoreState.Calibrating -> uiString(state.titleRes!!)
    is ScoreState.CarriedLastNight ->
        uiString(state.titleRes!!, state.dateLabel)
    ScoreState.NeedsStrap -> uiString(state.titleRes!!)
}

/** The score-state detail line, LOCALIZED.
 *
 *  The calibrating countdown becomes a real `<plurals>`. Kotlin picked the noun with
 *  `if (nightsRemaining == 1) "night" else "nights"`, which bakes in English's TWO categories — the
 *  exact hand-rolling [uiPlural]'s doc warns about. `getQuantityString` applies the LOCALE's own rules
 *  instead. (Swift splits the same sentence into two catalogue keys, so it shares the assumption; both
 *  values here come from those keys. The one-forms carry `%1$d` where the Swift copy spelled the number
 *  out — pt-PT said "mais uma noite" — because `i18n_audit` requires every quantity form of a plural to
 *  share its siblings' placeholders, the check that catches a `%1$d` dropped from just one form.) */
@Composable
internal fun scoreStateDetail(state: ScoreState): String = when (state) {
    is ScoreState.Scored -> ""
    is ScoreState.Calibrating ->
        uiPlural(R.plurals.score_state_detail_calibrating, state.nightsRemaining, state.nightsRemaining)
    is ScoreState.CarriedLastNight ->
        if (state.stale) uiString(R.string.score_state_detail_carried_stale)
        else uiString(R.string.score_state_detail_carried_fresh)
    ScoreState.NeedsStrap -> uiString(R.string.score_state_detail_needs_strap)
}

/** The honest score-state note shown in the Today flow when there is no own number to render, the
 *  state title + one what-to-do line, no fabricated value. [ScoreState.Scored] renders nothing (the
 *  tiles carry the real number). The whole card is the spec's "never a bare blank". Mirrors the iOS
 *  ScoreStateNote. */
@Composable
internal fun ScoreStateNote(state: ScoreState, restartCause: String? = null) {
    if (state is ScoreState.Scored) return
    val icon = when (state) {
        is ScoreState.Calibrating -> Icons.Filled.Tune
        is ScoreState.CarriedLastNight -> Icons.Filled.History
        ScoreState.NeedsStrap -> Icons.Filled.Warning
        is ScoreState.Scored -> Icons.Filled.Info
    }
    val tint = when (state) {
        ScoreState.NeedsStrap -> Palette.statusWarning
        else -> Palette.textTertiary
    }
    // Resolved before the Row: `semantics { }` is not a composable scope, so uiString/uiPlural cannot be
    // called inside it, and reading them once keeps the card and its a11y label on one string.
    val title = scoreStateTitle(state)
    val detail = scoreStateDetail(state)
    val noteA11y = uiString(R.string.l10n_today_screen_state_title_state_detail_f5380609, title, detail)
    NoopCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = noteA11y },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(Metrics.iconSmall),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
                Text(detail, style = NoopType.subhead, color = Palette.textSecondary)
                // #731: when the countdown restarted because the user tapped "Recalibrate
                // baseline", say so - otherwise the natural response to a fresh countdown is to
                // tap it again, resetting it once more. null (no line) if never recalibrated.
                restartCause?.let {
                    Text(it, style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
        }
    }
}

/** #1505: the note shown instead of a bare blank when the selected night's beats predate WHOOP 5
 *  transport labelling and so cannot be scored. Same card shape as [ScoreStateNote] it sits beside.
 *  Twin of the iOS `chargeLegacyRRGapNote`. */
@Composable
internal fun ChargeLegacyRrGapNote() {
    val title = uiString(R.string.charge_legacy_rr_gap_title)
    val detail = uiString(R.string.charge_legacy_rr_gap_detail)
    // Resolved before the Row: `semantics { }` is not a composable scope, so uiString cannot be called
    // inside it, and reading them once keeps the card and its a11y label on one string.
    val noteA11y = uiString(R.string.l10n_today_screen_state_title_state_detail_f5380609, title, detail)
    NoopCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = noteA11y },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.MonitorHeart,
                contentDescription = null,
                tint = Palette.chargeColor,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(Metrics.iconSmall),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
                Text(detail, style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}

// ── COMPONENT 4, provenance badge ───────────────────────────────────────────────────────────────────

// NOTE: the blanket day-level `TodayProvenanceBadge` was removed. Today provenance now resolves the real
// per-metric field-by-field winners, deduplicates them, and renders one card-level SourceBadge aligned to
// the Rest ring (see heroSourceLabel + ScoreHeroRow). The pure `dayOwnerSource` /
// `provenanceBadgeLabel` By-Day mappers are kept (Intelligence/Trends + tests still use that vocabulary).

/**
 * The skin-temp reading a Today surface should LEAD with (#1844): the first of today / the recovery carry
 * / the per-field carry that holds EITHER number, resolved to absolute-or-deviation by
 * [com.noop.analytics.SkinTempDisplay.leadReading].
 *
 * Both numbers are read off the SAME row, so an absolute is never paired with another night's deviation.
 * Replaces `resolveSkinTempDevC`, which had no caller left once both Today surfaces moved onto
 * this one; its carry-order tests were repinned here rather than dropped.
 */
internal fun resolveSkinTempReading(
    d: DailyMetric?,
    carriedDay: DailyMetric?,
    skinTempCarryDay: DailyMetric?,
    prefer: com.noop.analytics.SkinTempDisplay.Kind = com.noop.analytics.SkinTempDisplay.Kind.ABSOLUTE,
): com.noop.analytics.SkinTempDisplay.Reading? =
    listOfNotNull(d, carriedDay, skinTempCarryDay)
        .firstOrNull { it.skinTempC != null || it.skinTempDevC != null }
        ?.let { com.noop.analytics.SkinTempDisplay.leadReading(it.skinTempC, it.skinTempDevC, prefer) }

/** True only for the actionable #1515 state: the tile has no measured/imported count, no estimate, and
 *  carries the WHOOP 4.0 calibration prompt. Real or estimated Steps always retain their trend drill-in;
 *  a blank 5/MG or already-calibrated quiet day has no prompt and retains it too. */
internal fun stepsTileShouldOpenCalibration(
    realSteps: Int?,
    estimatedSteps: Int?,
    calibrationPrompt: String?,
): Boolean = realSteps == null && estimatedSteps == null && calibrationPrompt != null

// Workouts across every recorded + imported source over [from, to]. Recorded sessions live under
// the ACTIVE strap id — "whoop-<id>" after a re-pair — unioned with the canonical legacy "my-whoop"
// via [WhoopRepository.workoutsUnion] (#814): this read was pinned to the literal "my-whoop", which
// stranded a re-paired strap's fresh recordings, so the "Latest Workouts" feed and the HR-graph
// glyphs silently dropped the newest sessions while the Workouts screen (already on the union, #28)
// still showed them. Apple Health and Health Connect imports are stored under their own device ids
// (since #34/#53). Both the "Last Workouts" feed and the HR-graph sport glyphs need the SAME union,
// or Health-Connect-imported sessions get no glyph on the Today trend, so they share this one seam.
// Deduped here (not per-consumer) with the Workouts screen's #687 semantics: a live strap recording
// and its thin Health Connect import collapse to the richer row, so neither the feed shows a
// duplicate card nor the HR trend a doubled sport glyph. dropDetectedShadows/filterDismissed are
// deliberately absent: `detected` rows live under `<deviceId>-noop`, which this union never queries.
internal suspend fun WhoopRepository.workoutsAllSources(
    activeDeviceId: String,
    from: Long,
    to: Long,
): List<WorkoutRow> =
    WorkoutEditing.dedupCrossSource(
        workoutsUnion(activeDeviceId, from, to) +
            workouts("apple-health", from, to) +
            workouts("health-connect", from, to)
    )
