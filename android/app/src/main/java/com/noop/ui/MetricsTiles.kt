package com.noop.ui

import androidx.compose.ui.graphics.Color
import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.ScoreConfidence
import com.noop.analytics.SkinTempDisplay
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Metrics tiles (Zoop "Calm" redesign)
//
// Resolves every Key Metric to the tile the Metrics tab draws: the number, the day it was measured, its
// history and the 30-day insight drawn from that history. The carry rules are the ones the Key Metrics grid
// used (the day's own row first, then the per-field carries), so the redesign changes how a number looks,
// never which number it is. TodayScreen gathers the inputs; MetricsCategoryCards.kt draws the result.

/** Each metric's history as day key to value, built once per data refresh instead of per recomposition. */
internal class MetricHistories(days: List<DailyMetric>) {
    val charge = days.seriesOf { it.recovery }
    val effort = days.seriesOf { it.strain }
    val asleep = days.seriesOf { it.totalSleepMin }
    val hrv = days.seriesOf { it.avgHrv }
    val restingHr = days.seriesOf { it.restingHr?.toDouble() }
    val respiratory = days.seriesOf { it.respRateBpm }
    val bloodOxygen = days.seriesOf { it.spo2Pct }
    val steps = days.seriesOf { it.steps?.toDouble() }

    /** Absolute wrist temperatures, including the imports that store one in the deviation column. */
    val skinAbsolute = days.seriesOf { row ->
        row.skinTempC ?: row.skinTempDevC?.takeIf { SkinTempDisplay.kind(it) == SkinTempDisplay.Kind.ABSOLUTE }
    }

    /** Deviations from the nightly skin-temperature baseline, never an absolute stored in the same column. */
    val skinDeviation = days.seriesOf { row ->
        row.skinTempDevC?.takeIf { SkinTempDisplay.kind(it) == SkinTempDisplay.Kind.DEVIATION }
    }
}

/** What the Steps tile reads: the day's imported and estimated counts, their histories, and its captions. */
internal data class StepsInputs(
    val importedForDay: Int?,
    val estimatedForDay: Int?,
    val importedByDay: Map<String, Double>,
    val estimatedByDay: Map<String, Double>,
    /** The calibration status shown under an estimate. */
    val estimateCaption: String,
    /** What unblocks a blank tile on a strap that estimates steps; null when nothing would. */
    val calibrationPrompt: String?,
)

/** The newest measured body weight, every measured weight by day (kilograms), and the profile fallback. */
internal data class WeightInputs(
    val latestKg: Double?,
    val history: Map<String, Double>,
    val profileKg: Double,
)

/**
 * Every Key Metric's tile for [dayKey], enabled or not; the category cards draw only the enabled ones. [day]
 * is the row on screen; the carry rows are the screen's per-field fallbacks, which only exist on today.
 */
internal fun buildMetricTiles(
    histories: MetricHistories,
    days: List<DailyMetric>,
    day: DailyMetric?,
    dayKey: String,
    isToday: Boolean,
    recoveryCarryDay: DailyMetric?,
    vitalsCarryDay: DailyMetric?,
    hrvCarryDay: DailyMetric?,
    restingHrCarryDay: DailyMetric?,
    respCarryDay: DailyMetric?,
    spo2CarryDay: DailyMetric?,
    skinTempCarryDay: DailyMetric?,
    recoveryCalibration: Int?,
    lastScoredCharge: LastCharge?,
    effortForDay: Double?,
    effortScale: EffortScale,
    restScore: Double?,
    restHistory: Map<String, Double>,
    restPendingSync: Boolean,
    spo2CandidateByDay: Map<String, Double>,
    steps: StepsInputs,
    weight: WeightInputs,
    unitSystem: UnitSystem,
    caloriesByDay: Map<String, Double>,
    skinTempPreferred: SkinTempDisplay.Kind,
    fahrenheit: Boolean,
    onOpenMetric: (String) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenStepsCalibration: () -> Unit,
    /** How sure the day's own Charge is; a "building" score says it is still learning. Null hides the note. */
    chargeConfidence: ScoreConfidence? = null,
    /** Days whose calories are Zoop's heart-rate estimate rather than a phone-imported figure. */
    caloriesEstimatedDays: Set<String> = emptySet(),
): Map<KeyMetric, MetricTileUi> {
    // A number takes its comparison colour when its metric has a healthy direction (the approved Calm mock:
    // HRV green, breathing amber), and the primary text colour when it has none. Charge, Effort and Rest
    // pass their own score colours, the same ones Home uses.
    fun make(
        metric: KeyMetric,
        reading: DayReading?,
        history: Map<String, Double>,
        unit: String,
        lineColor: Color,
        rule: TrendRule,
        format: (Double) -> String,
        formatDelta: (Double) -> String = format,
        tolerance: Double = HOME_TREND_TOLERANCE,
        value: String? = reading?.let { format(it.value) },
        caption: String? = null,
        referenceIsNormal: Boolean = false,
        labelSuffix: String? = null,
        valueColor: Color? = null,
        onOpen: (() -> Unit)? = null,
        estimatedDays: Set<String> = emptySet(),
    ): MetricTileUi {
        val date = reading?.let { runCatching { LocalDate.parse(it.day) }.getOrNull() }
        val insight = reading?.let { metricInsight(history, it.day, dayValue = it.value) } ?: MetricInsight.EMPTY
        val reference = if (referenceIsNormal) 0.0 else insight.baseline
        val tone = comparisonTone(reading?.value, reference, rule)
        return MetricTileUi(
            id = metric.raw,
            label = uiString(metric.titleRes),
            value = value,
            unit = unit,
            valueColor = valueColor ?: tone?.let { homeToneColor(it) } ?: Palette.textPrimary,
            lineColor = lineColor,
            raw = reading?.value,
            insight = insight,
            rule = rule,
            tolerance = tolerance,
            format = format,
            formatDelta = formatDelta,
            caption = caption,
            referenceIsNormal = referenceIsNormal,
            labelSuffix = labelSuffix,
            axisStart = date?.let { shortDate(it, METRICS_BASELINE_DAYS) },
            axisEnd = date?.let {
                if (isToday && reading.day == dayKey) uiString(R.string.today_day_today) else shortDate(it, 0)
            },
            onOpen = onOpen,
            estimatedDays = estimatedDays,
        )
    }

    // Charge: the day's own score, else the calibration count, else the last scored night labelled as such.
    val ownCharge = day?.let { row -> row.recovery?.let { DayReading(it, row.day) } }
    val carriedCharge = if (ownCharge == null && recoveryCalibration == null) {
        lastScoredCharge?.let { charge -> recoveryCarryDay?.let { DayReading(charge.value, it.day) } }
    } else {
        null
    }
    val chargeReading = ownCharge ?: carriedCharge

    // Effort: the screen's one resolved figure (live-preferring today), so the tile matches Home's ring.
    val effortReading = (effortForDay ?: day?.strain)?.let { DayReading(it, dayKey) }
    val effortFormat: (Double) -> String = { value ->
        if (effortScale == EffortScale.WHOOP) {
            UnitFormatter.effortDisplay(value, effortScale)
        } else {
            UnitFormatter.effortValue(value, effortScale).roundToInt().toString()
        }
    }

    // Rest: the day's score, or the latest night the screen fell back to. Asleep reads that same night.
    val restDay = when {
        restScore == null -> null
        restHistory[dayKey] != null -> dayKey
        else -> restHistory.keys.maxOrNull()
    }
    val restReading = restScore?.let { score -> restDay?.let { DayReading(score, it) } }
    val restRow = restDay?.let { key -> if (key == day?.day) day else days.lastOrNull { it.day == key } }
    val asleepReading = firstReading(restRow ?: day) { it.totalSleepMin }

    // Blood oxygen: a calibrated reading first, the strap estimate only when there is none, and the history
    // the tile charts follows the source of the number it shows.
    val calibratedSpo2 = firstReading(day, recoveryCarryDay, spo2CarryDay) { it.spo2Pct }
    val candidateKey = day?.day
    val candidateToday = candidateKey?.let { spo2CandidateByDay[it] }
    val spo2OnCandidate = spo2UsingCandidate(calibratedSpo2?.value, candidateToday)
    val spo2Reading = calibratedSpo2
        ?: candidateToday?.let { value -> DayReading(value, candidateKey) }

    // Steps: the counted figure, then imported, then the estimate (#107/#150), with the history merged in
    // the same order so the chart and the baseline describe the same kind of number as the tile.
    val realSteps = day?.steps ?: steps.importedForDay
    val shownSteps = realSteps ?: steps.estimatedForDay
    val stepsReading = shownSteps?.let { DayReading(it.toDouble(), dayKey) }
    val stepsHistory = (histories.steps.keys + steps.importedByDay.keys + steps.estimatedByDay.keys)
        .associateWith { key -> histories.steps[key] ?: steps.importedByDay[key] ?: steps.estimatedByDay[key] ?: 0.0 }
    // The days the steps chart plots an estimate for: no counted or imported figure, by the same precedence as
    // stepsHistory above, plus the day on screen when its number is the estimate.
    val stepsEstimatedDays = steps.estimatedByDay.keys
        .filterTo(HashSet()) { it !in histories.steps && it !in steps.importedByDay }
        .apply { if (realSteps == null && steps.estimatedForDay != null) add(dayKey) else remove(dayKey) }
    val stepsOpenCalibration = stepsTileShouldOpenCalibration(
        realSteps = realSteps,
        estimatedSteps = steps.estimatedForDay,
        calibrationPrompt = steps.calibrationPrompt,
    )

    // Weight: the newest measured weight against the readings before it; the profile weight has no history.
    val weightDay = weight.history.keys.maxOrNull()
    val weightReading = weight.latestKg?.let { kg -> weightDay?.let { DayReading(kg, it) } }
    val weightFormat: (Double) -> String = { kg ->
        oneDecimal(if (unitSystem == UnitSystem.IMPERIAL) UnitFormatter.kgToPounds(kg) else kg)
    }

    // Skin temperature: an import can store an absolute temperature in the deviation column, so the value
    // decides the scale. A deviation is already relative to the owner's baseline and compares with zero.
    val skinRow = listOfNotNull(day, recoveryCarryDay, skinTempCarryDay)
        .firstOrNull { it.skinTempC != null || it.skinTempDevC != null }
    val skinLead = skinRow?.let { SkinTempDisplay.leadReading(it.skinTempC, it.skinTempDevC, skinTempPreferred) }
    val skinKind = skinLead?.let { SkinTempDisplay.kind(it.value) } ?: SkinTempDisplay.Kind.ABSOLUTE
    val skinIsDeviation = skinKind == SkinTempDisplay.Kind.DEVIATION
    val skinReading = skinRow?.let { row -> skinLead?.let { DayReading(it.value, row.day) } }

    val calories = caloriesByDay[dayKey]?.let { DayReading(it, dayKey) }

    return mapOf(
        KeyMetric.CHARGE to make(
            KeyMetric.CHARGE,
            chargeReading,
            histories.charge,
            unit = if (chargeReading != null) "%" else "",
            valueColor = chargeReading?.let { Palette.recoveryColor(it.value) },
            lineColor = Palette.chargeColor,
            rule = TrendRule.HIGHER_IS_BETTER,
            format = ::wholeNumber,
            value = chargeReading?.let { wholeNumber(it.value) }
                ?: recoveryCalibration?.let { "$it/${Baselines.minNightsSeed}" },
            caption = when {
                chargeReading == null && recoveryCalibration != null ->
                    uiString(R.string.score_state_title_calibrating)
                carriedCharge != null -> lastScoredCharge?.caption?.takeIf { it.isNotEmpty() }
                else -> chargeEstimateNote(ownCharge != null, chargeConfidence)?.let { uiString(it.labelRes) }
            },
            onOpen = { onOpenMetric("recovery") },
        ),
        KeyMetric.EFFORT to make(
            KeyMetric.EFFORT,
            effortReading,
            histories.effort,
            // "/ 21" or "/ 100" beside the number: Effort reads out of its scale, never as a percentage (#492).
            unit = "/ ${UnitFormatter.effortScaleMax(effortScale)}",
            valueColor = effortReading?.let { Palette.strainColor(it.value) },
            lineColor = Palette.effortColor,
            rule = TrendRule.NEUTRAL,
            format = effortFormat,
            tolerance = EFFORT_TYPICAL_TOLERANCE,
            labelSuffix = if (isToday && effortReading != null) uiString(R.string.metrics_effort_so_far) else null,
            onOpen = { onOpenMetric(HERO_EFFORT_METRIC_KEY) },
        ),
        KeyMetric.REST to make(
            KeyMetric.REST,
            restReading,
            restHistory,
            unit = "%",
            valueColor = restReading?.let { homeToneColor(restTone(it.value)) },
            lineColor = Palette.restBright,
            rule = TrendRule.HIGHER_IS_BETTER,
            format = ::wholeNumber,
            // #1164/#2012: a provisional Rest is said to be provisional rather than withheld.
            caption = if (restPendingSync) {
                uiString(
                    R.string.l10n_today_screen_pending_sync_complete_5940c589,
                    uiString(R.string.l10n_today_screen_strap_history_still_offloading_80140264),
                )
            } else {
                null
            },
            onOpen = { onOpenMetric(HERO_REST_METRIC_KEY) },
        ),
        KeyMetric.ASLEEP to make(
            KeyMetric.ASLEEP,
            asleepReading,
            histories.asleep,
            unit = "",
            lineColor = Palette.sleepLight,
            rule = TrendRule.HIGHER_IS_BETTER,
            format = ::durationText,
            // A duration reads in the primary colour; its chip still says whether it was more or less.
            valueColor = Palette.textPrimary,
            onOpen = onOpenSleep,
        ),
        KeyMetric.HRV to make(
            KeyMetric.HRV,
            firstReading(day, vitalsCarryDay, hrvCarryDay) { it.avgHrv },
            histories.hrv,
            unit = DashboardCard.HRV.unit,
            lineColor = Palette.metricPurple,
            rule = TrendRule.HIGHER_IS_BETTER,
            format = ::wholeNumber,
            onOpen = { onOpenMetric("hrv") },
        ),
        KeyMetric.RESTING_HR to make(
            KeyMetric.RESTING_HR,
            firstReading(day, vitalsCarryDay, restingHrCarryDay) { it.restingHr?.toDouble() },
            histories.restingHr,
            unit = DashboardCard.RESTING_HR.unit,
            lineColor = Palette.metricRose,
            rule = TrendRule.LOWER_IS_BETTER,
            format = ::wholeNumber,
            onOpen = { onOpenMetric("rhr") },
        ),
        KeyMetric.RESPIRATORY to make(
            KeyMetric.RESPIRATORY,
            firstReading(day, vitalsCarryDay, respCarryDay) { it.respRateBpm },
            histories.respiratory,
            unit = DashboardCard.RESPIRATORY.unit,
            lineColor = Palette.metricCyan,
            rule = TrendRule.LOWER_IS_BETTER,
            format = ::oneDecimal,
            onOpen = { onOpenMetric("resp") },
        ),
        KeyMetric.BLOOD_OXYGEN to make(
            KeyMetric.BLOOD_OXYGEN,
            spo2Reading,
            if (spo2OnCandidate) spo2CandidateByDay else histories.bloodOxygen,
            unit = "%",
            lineColor = Palette.metricCyan,
            rule = TrendRule.HIGHER_IS_BETTER,
            format = ::wholeNumber,
            // Say whose number this is when it is the strap's own estimate.
            caption = if (spo2OnCandidate) uiString(R.string.spo2_strap_estimate_caption) else null,
            onOpen = { onOpenMetric("spo2") },
        ),
        KeyMetric.STEPS to make(
            KeyMetric.STEPS,
            stepsReading,
            stepsHistory,
            unit = "",
            lineColor = Palette.metricCyan,
            rule = TrendRule.NEUTRAL,
            format = ::groupedNumber,
            // A counted figure needs no caption; an estimate names its calibration (#760/#792); a blank tile
            // on a strap that estimates says what would unblock it (#1491).
            caption = when {
                realSteps != null -> null
                steps.estimatedForDay != null -> steps.estimateCaption
                else -> steps.calibrationPrompt
            },
            onOpen = if (stepsOpenCalibration) onOpenStepsCalibration else ({ onOpenMetric("steps_est") }),
            estimatedDays = stepsEstimatedDays,
        ),
        KeyMetric.WEIGHT to make(
            KeyMetric.WEIGHT,
            weightReading,
            weight.history,
            unit = UnitFormatter.massUnit(unitSystem),
            lineColor = Palette.accent,
            rule = TrendRule.NEUTRAL,
            format = weightFormat,
            // With no measured weight the profile weight shows, labelled, with nothing to compare.
            value = weightReading?.let { weightFormat(it.value) } ?: weightFormat(weight.profileKg),
            caption = if (weightReading == null) uiString(WeightCaption.FROM_PROFILE.labelRes) else null,
        ),
        KeyMetric.CALORIES to make(
            KeyMetric.CALORIES,
            calories,
            caloriesByDay,
            unit = DashboardCard.CALORIES.unit,
            lineColor = Palette.metricAmber,
            rule = TrendRule.NEUTRAL,
            format = ::groupedNumber,
            caption = caloriesEstimateNote(dayKey, calories != null, caloriesEstimatedDays)?.let { uiString(it.labelRes) },
            estimatedDays = caloriesEstimatedDays,
            onOpen = { onOpenMetric("active_kcal") },
        ),
        KeyMetric.SKIN_TEMP to make(
            KeyMetric.SKIN_TEMP,
            skinReading,
            if (skinIsDeviation) histories.skinDeviation else histories.skinAbsolute,
            unit = SkinTempDisplay.unitSymbol(skinKind, fahrenheit),
            lineColor = Palette.metricAmber,
            rule = TrendRule.STEADY_IS_BEST,
            format = { SkinTempDisplay.numberString(it, skinKind, fahrenheit) },
            formatDelta = { temperatureDifference(it, fahrenheit) },
            referenceIsNormal = skinIsDeviation,
            onOpen = { onOpenMetric("skin") },
        ),
    )
}

/**
 * The short note a Metrics tile carries when its number is a guess rather than a measurement, so an
 * estimated figure never reads as solidly as a measured one.
 */
internal enum class EstimateNote(@androidx.annotation.StringRes val labelRes: Int) {
    /** The score is computed, but its personal baseline is still thin. */
    STILL_LEARNING(R.string.metrics_estimate_still_learning),
    /** Worked out from heart rate, weight and age with a published formula, not measured. */
    FROM_HEART_RATE(R.string.metrics_estimate_from_heart_rate),
}

/** The Charge tile's note: only the day's OWN score, and only while its baseline is still building. */
internal fun chargeEstimateNote(hasOwnScore: Boolean, confidence: ScoreConfidence?): EstimateNote? =
    if (hasOwnScore && confidence == ScoreConfidence.BUILDING) EstimateNote.STILL_LEARNING else null

/** The Calories tile's note: only when the day shows a figure and that figure is Zoop's own estimate. */
internal fun caloriesEstimateNote(dayKey: String, hasValue: Boolean, estimatedDays: Set<String>): EstimateNote? =
    if (hasValue && dayKey in estimatedDays) EstimateNote.FROM_HEART_RATE else null

/** A whole number, rounded: Charge, Rest, HRV, resting heart rate, blood oxygen. */
private fun wholeNumber(value: Double): String = value.roundToInt().toString()

/** One decimal in the reader's locale: breathing rate and weight. */
private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

/** A whole number with the locale's grouping, such as 12,345 steps. */
private fun groupedNumber(value: Double): String = String.format(Locale.getDefault(), "%,d", value.roundToInt())

/** The size of a temperature difference in the reader's unit: a Fahrenheit difference scales without the offset. */
private fun temperatureDifference(celsius: Double, fahrenheit: Boolean): String =
    oneDecimal(if (fahrenheit) celsius * 9.0 / 5.0 else celsius)
