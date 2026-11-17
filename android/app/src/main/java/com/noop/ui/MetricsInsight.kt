package com.noop.ui

import androidx.annotation.StringRes
import com.noop.R
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// MARK: - Metrics baselines (Zoop "Calm" redesign)
//
// The Metrics tab sets every number against the owner's own recent past: a 30-day baseline (the mean of the
// 30 days before the number's day), a normal range (the middle 80% of those days) and the 7-day average the
// Home tab already compares with. The maths and the choice of each plain-English line live here as pure
// functions, so JVM tests pin them; MetricsCategoryCards.kt only draws what these return.

/** Calendar days behind a Metrics baseline and its normal range. */
internal const val METRICS_BASELINE_DAYS = 30

/** Fewest days in the window before a normal range is drawn: a percentile of a handful of days says little. */
internal const val METRICS_RANGE_MIN_DAYS = 7

/** Lower percentile that bounds the normal range, which spans the middle 80% of the owner's days. */
internal const val METRICS_RANGE_LOW = 0.10

/** Upper percentile that bounds the normal range. */
internal const val METRICS_RANGE_HIGH = 0.90

/** Skin temperature reads as steady within this many degrees Celsius of its reference, either way. */
internal const val SKIN_TEMP_STEADY_C = 0.5

/** Effort reads as typical within this share of its baseline. Effort swings far more than the vitals do. */
internal const val EFFORT_TYPICAL_TOLERANCE = 0.10

/** Charge at or above which the Recovery line says the owner recovered well (the Synthesis "Strong" band). */
internal const val RECOVERY_WELL = 70.0

/** Charge at or above which the Recovery line calls recovery steady; below it, low. */
internal const val RECOVERY_STEADY = 50.0

/** How a metric's arrow is coloured: toward its healthy direction, steadiness, or not at all. */
internal enum class TrendRule {
    /** Up is better (HRV, Charge, Rest, sleep duration, blood oxygen). */
    HIGHER_IS_BETTER,

    /** Down is better (resting heart rate, breathing rate). */
    LOWER_IS_BETTER,

    /** No healthy direction, so the arrow stays grey (Effort, steps, calories, weight). */
    NEUTRAL,

    /** Close to the reference is best and either direction is a change (skin temperature). */
    STEADY_IS_BEST,
}

/** The four groups the Metrics tab sorts its numbers into, always in this order. */
internal enum class MetricCategory { RECOVERY, SLEEP, TRAINING, BODY }

/** The group a Key Metric belongs to. */
internal fun metricCategory(metric: KeyMetric): MetricCategory = when (metric) {
    KeyMetric.CHARGE, KeyMetric.HRV, KeyMetric.RESTING_HR, KeyMetric.RESPIRATORY -> MetricCategory.RECOVERY
    KeyMetric.REST, KeyMetric.ASLEEP -> MetricCategory.SLEEP
    KeyMetric.EFFORT, KeyMetric.STEPS, KeyMetric.CALORIES -> MetricCategory.TRAINING
    KeyMetric.WEIGHT, KeyMetric.SKIN_TEMP, KeyMetric.BLOOD_OXYGEN -> MetricCategory.BODY
}

/** One number set against the owner's own recent past. A field stays null when there is too little history. */
internal data class MetricInsight(
    /** Mean of the [METRICS_BASELINE_DAYS] days before the number's day, once [HOME_AVERAGE_MIN_DAYS] carry one. */
    val baseline: Double?,
    /** Bottom of the normal range: the 10th percentile of the same days, once [METRICS_RANGE_MIN_DAYS] carry one. */
    val rangeLow: Double?,
    /** Top of the normal range: the 90th percentile of the same days. */
    val rangeHigh: Double?,
    /** Mean of the 7 days before, the figure the Home tab compares with, so the two tabs agree. */
    val average7: Double?,
    /** The window's days and the number's own day, oldest first, as (days since the window start, value). */
    val points: List<Pair<Int, Double>>,
) {
    companion object {
        val EMPTY = MetricInsight(null, null, null, null, emptyList())
    }
}

/**
 * [history] set against the [windowDays] days before [dayKey]. The day itself is left out of the baseline and
 * the range, so a number is compared with its own past rather than with an average it is part of; it is kept
 * in [MetricInsight.points], where the chart ends on it. [dayValue] is the number the tile shows for the day,
 * when that is not the stored one (today's live Effort, or steps counted since sleep onset), so the chart
 * ends on the same number the tile displays.
 */
internal fun metricInsight(
    history: Map<String, Double>,
    dayKey: String?,
    dayValue: Double? = null,
    windowDays: Int = METRICS_BASELINE_DAYS,
): MetricInsight {
    val key = dayKey ?: return MetricInsight.EMPTY
    val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: return MetricInsight.EMPTY
    val start = day.minusDays(windowDays.toLong())
    val startKey = start.toString()
    val prior = history.filterKeys { it >= startKey && it < key }
    val sortedPrior = prior.values.sorted()
    val hasRange = sortedPrior.size >= METRICS_RANGE_MIN_DAYS
    val own = dayValue ?: history[key]
    val points = prior.entries
        .sortedBy { it.key }
        .mapNotNull { entry ->
            runCatching { LocalDate.parse(entry.key) }.getOrNull()
                ?.let { ChronoUnit.DAYS.between(start, it).toInt() to entry.value }
        } + listOfNotNull(own?.let { windowDays to it })
    return MetricInsight(
        baseline = trailingAverage(history, key, windowDays),
        rangeLow = if (hasRange) percentile(sortedPrior, METRICS_RANGE_LOW) else null,
        rangeHigh = if (hasRange) percentile(sortedPrior, METRICS_RANGE_HIGH) else null,
        average7 = trailingAverage(history, key, HOME_AVERAGE_WINDOW_DAYS),
        points = points,
    )
}

/** The [fraction] percentile of an ascending, non-empty list, interpolating between neighbours. */
internal fun percentile(sorted: List<Double>, fraction: Double): Double {
    require(sorted.isNotEmpty()) { "percentile of an empty list" }
    val position = fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)
    val lower = floor(position).toInt()
    val upper = ceil(position).toInt()
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
}

/**
 * The colour a number's arrow takes against its reference, using the Home vitals rule for the metrics with a
 * healthy direction. Null leaves the arrow grey: a neutral metric, or a side of the comparison missing.
 */
internal fun comparisonTone(value: Double?, reference: Double?, rule: TrendRule): HomeTone? {
    if (value == null || reference == null) return null
    return when (rule) {
        TrendRule.NEUTRAL -> null
        TrendRule.HIGHER_IS_BETTER -> vitalTrendTone(value, reference, higherIsBetter = true)
        TrendRule.LOWER_IS_BETTER -> vitalTrendTone(value, reference, higherIsBetter = false)
        TrendRule.STEADY_IS_BEST ->
            if (abs(value - reference) <= SKIN_TEMP_STEADY_C) HomeTone.FAIR else HomeTone.POOR
    }
}

/** Which side of its reference a number sits on, for the plain sentences. */
internal enum class BaselineSide { ABOVE, CLOSE, BELOW }

/**
 * [value] against [reference]. Close means within [tolerance] of the reference (as a share of it), the same
 * band the arrow colours use; skin temperature uses a fixed [SKIN_TEMP_STEADY_C] instead.
 */
internal fun baselineSide(
    value: Double,
    reference: Double,
    rule: TrendRule,
    tolerance: Double = HOME_TREND_TOLERANCE,
): BaselineSide {
    val difference = value - reference
    val band = if (rule == TrendRule.STEADY_IS_BEST) SKIN_TEMP_STEADY_C else abs(reference) * tolerance
    return when {
        abs(difference) <= band -> BaselineSide.CLOSE
        difference > 0 -> BaselineSide.ABOVE
        else -> BaselineSide.BELOW
    }
}

/** Where a number sits against the owner's normal range. */
internal enum class RangePosition { BELOW, INSIDE, ABOVE }

/** [value] against the normal range [low]..[high], both ends counting as inside. */
internal fun rangePosition(value: Double, low: Double, high: Double): RangePosition = when {
    value < low -> RangePosition.BELOW
    value > high -> RangePosition.ABOVE
    else -> RangePosition.INSIDE
}

/** The expanded tile's first sentence: which side of the baseline the number sits on. */
@StringRes
internal fun baselineSentence(side: BaselineSide): Int = when (side) {
    BaselineSide.ABOVE -> R.string.metrics_sentence_above_baseline
    BaselineSide.CLOSE -> R.string.metrics_sentence_close_to_baseline
    BaselineSide.BELOW -> R.string.metrics_sentence_below_baseline
}

/** The expanded tile's second sentence: where the number sits against the normal range. */
@StringRes
internal fun rangeSentence(position: RangePosition): Int = when (position) {
    RangePosition.BELOW -> R.string.metrics_sentence_below_range
    RangePosition.INSIDE -> R.string.metrics_sentence_inside_range
    RangePosition.ABOVE -> R.string.metrics_sentence_above_range
}

/** The Recovery card's first sentence, from the Charge band. Null when there is no Charge to read. */
@StringRes
internal fun recoveryChargeLine(charge: Double?): Int? = when {
    charge == null -> null
    charge >= RECOVERY_WELL -> R.string.metrics_recovery_well
    charge >= RECOVERY_STEADY -> R.string.metrics_recovery_steady
    else -> R.string.metrics_recovery_low
}

/**
 * The Recovery card's second sentence, from how HRV and resting heart rate sit against their baselines. HRV
 * leads; resting heart rate joins it when the two agree and speaks alone when HRV says nothing. Null when
 * neither has a baseline yet.
 */
@StringRes
internal fun recoveryVitalsLine(hrv: HomeTone?, restingHr: HomeTone?): Int? = when {
    hrv == HomeTone.GOOD && restingHr == HomeTone.GOOD -> R.string.metrics_recovery_hrv_up_rhr_down
    hrv == HomeTone.POOR && restingHr == HomeTone.POOR -> R.string.metrics_recovery_hrv_down_rhr_up
    hrv == HomeTone.GOOD -> R.string.metrics_recovery_hrv_up
    hrv == HomeTone.POOR -> R.string.metrics_recovery_hrv_down
    restingHr == HomeTone.GOOD -> R.string.metrics_recovery_rhr_down
    restingHr == HomeTone.POOR -> R.string.metrics_recovery_rhr_up
    hrv == HomeTone.FAIR -> R.string.metrics_recovery_hrv_close
    restingHr == HomeTone.FAIR -> R.string.metrics_recovery_rhr_close
    else -> null
}

/** The Sleep card's first sentence, from the Rest band. Null when there is no Rest score. */
@StringRes
internal fun sleepRestLine(rest: Double?): Int? = rest?.let {
    when (restTone(it)) {
        HomeTone.GOOD -> R.string.metrics_sleep_good
        HomeTone.FAIR -> R.string.metrics_sleep_fair
        HomeTone.POOR -> R.string.metrics_sleep_poor
    }
}

/**
 * The Sleep card's duration sentence, from the Asleep arrow's own tone so the words and the colour agree:
 * more than usual, about usual, less than usual, or just the duration while there is no baseline.
 */
@StringRes
internal fun sleepDurationLine(tone: HomeTone?): Int = when (tone) {
    HomeTone.GOOD -> R.string.metrics_sleep_more
    HomeTone.FAIR -> R.string.metrics_sleep_usual
    HomeTone.POOR -> R.string.metrics_sleep_less
    null -> R.string.metrics_sleep_plain
}

/** The Training card's line, from Effort against its baseline; today's reads "so far". */
@StringRes
internal fun trainingLine(effort: Double?, baseline: Double?, isToday: Boolean): Int {
    if (effort == null) return R.string.metrics_training_empty
    if (baseline == null) return R.string.metrics_training_building
    return when (baselineSide(effort, baseline, TrendRule.NEUTRAL, EFFORT_TYPICAL_TOLERANCE)) {
        BaselineSide.BELOW ->
            if (isToday) R.string.metrics_training_lighter_today else R.string.metrics_training_lighter
        BaselineSide.CLOSE ->
            if (isToday) R.string.metrics_training_typical_today else R.string.metrics_training_typical
        BaselineSide.ABOVE ->
            if (isToday) R.string.metrics_training_bigger_today else R.string.metrics_training_bigger
    }
}

/**
 * The Body card's line: where skin temperature sits against its normal when it has one to compare with,
 * otherwise a plain note that the card holds the latest readings, or what fills it.
 */
@StringRes
internal fun bodyLine(skinTemp: BaselineSide?, hasAnyValue: Boolean): Int = when {
    skinTemp == BaselineSide.ABOVE -> R.string.metrics_body_skin_up
    skinTemp == BaselineSide.BELOW -> R.string.metrics_body_skin_down
    skinTemp == BaselineSide.CLOSE -> R.string.metrics_body_skin_close
    hasAnyValue -> R.string.metrics_body_latest
    else -> R.string.metrics_body_empty
}

/** A number and the day it was measured, so its baseline is the 30 days before ITS day, not the screen's. */
internal data class DayReading(val value: Double, val day: String)

/** The first of [rows] that carries [pick]: today's row first, then the carried rows in the order given. */
internal fun firstReading(vararg rows: DailyMetric?, pick: (DailyMetric) -> Double?): DayReading? {
    for (row in rows) {
        if (row == null) continue
        val value = pick(row) ?: continue
        return DayReading(value, row.day)
    }
    return null
}

/**
 * The rows a category card lays its tiles out in: pairs on a two-column grid, with a wide tile (Effort) or the
 * tile the owner opened taking a full row of its own. A tile left without a partner keeps half the width.
 */
internal fun <T> tileRows(tiles: List<T>, fullWidth: (T) -> Boolean): List<List<T>> {
    val rows = mutableListOf<List<T>>()
    val pending = mutableListOf<T>()
    for (tile in tiles) {
        if (fullWidth(tile)) {
            if (pending.isNotEmpty()) {
                rows.add(pending.toList())
                pending.clear()
            }
            rows.add(listOf(tile))
        } else {
            pending.add(tile)
            if (pending.size == 2) {
                rows.add(pending.toList())
                pending.clear()
            }
        }
    }
    if (pending.isNotEmpty()) rows.add(pending.toList())
    return rows
}

