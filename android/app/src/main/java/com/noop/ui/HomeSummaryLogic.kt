package com.noop.ui

import com.noop.data.DailyMetric
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Calendar days the Home tab averages over when it compares a day with its own recent past. */
internal const val HOME_AVERAGE_WINDOW_DAYS = 7

/** Fewest days inside the window that must carry a value before an average is shown at all. */
internal const val HOME_AVERAGE_MIN_DAYS = 3

/**
 * Mean of [values] over the [windowDays] calendar days before [dayKey]. The day itself is excluded, so
 * a day is compared with its own past rather than with an average it is part of. Null when fewer than
 * [HOME_AVERAGE_MIN_DAYS] of those days carry a value, or when [dayKey] is not an ISO date.
 */
internal fun trailingAverage(
    values: Map<String, Double>,
    dayKey: String,
    windowDays: Int = HOME_AVERAGE_WINDOW_DAYS,
): Double? {
    val day = runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: return null
    val start = day.minusDays(windowDays.toLong()).toString()
    val window = values.filterKeys { it >= start && it < dayKey }.values
    return if (window.size >= HOME_AVERAGE_MIN_DAYS) window.average() else null
}

/** Day key to value for one [DailyMetric] field, skipping days where that field is empty. */
internal fun List<DailyMetric>.seriesOf(field: (DailyMetric) -> Double?): Map<String, Double> =
    mapNotNull { row -> field(row)?.let { row.day to it } }.toMap()

/**
 * A signed difference for display: "+4", "-2" and "0" with no decimals, or "+0.6" and "-1.2" with one.
 * A difference that rounds to zero reads as a plain zero, never "+0" or "-0".
 */
internal fun signedDelta(delta: Double, decimals: Int = 0, locale: Locale = Locale.getDefault()): String {
    if (decimals <= 0) {
        val r = delta.roundToInt()
        return if (r > 0) "+$r" else r.toString()
    }
    val rounded = String.format(locale, "%.${decimals}f", delta)
    val isZero = Math.round(delta * Math.pow(10.0, decimals.toDouble())) == 0L
    return when {
        isZero -> String.format(locale, "%.${decimals}f", 0.0)
        delta > 0 -> "+$rounded"
        else -> rounded
    }
}

/** How a Home figure reads at a glance, mapped to the status colours (positive, warning, critical). */
internal enum class HomeTone { GOOD, FAIR, POOR }

/** Share of the 7-day average inside which a vital counts as unchanged rather than better or worse. */
internal const val HOME_TREND_TOLERANCE = 0.05

/** Rest score at or above which Rest reads as good. */
internal const val HOME_REST_GOOD = 70.0

/** Rest score at or above which Rest reads as fair; below it, poor. */
internal const val HOME_REST_FAIR = 50.0

/** Rest band: good from [HOME_REST_GOOD], fair from [HOME_REST_FAIR], poor below. */
internal fun restTone(score: Double): HomeTone = when {
    score >= HOME_REST_GOOD -> HomeTone.GOOD
    score >= HOME_REST_FAIR -> HomeTone.FAIR
    else -> HomeTone.POOR
}

/**
 * A vital set against its own trailing average. Within [tolerance] of the average (as a share of it) the
 * day reads as fair; otherwise good when it moved in the healthy direction ([higherIsBetter] for HRV,
 * lower for resting heart rate and breathing rate) and poor when it did not. Null when either side is
 * missing or the average is not positive, so the figure keeps its neutral colour.
 */
internal fun vitalTrendTone(
    value: Double?,
    average: Double?,
    higherIsBetter: Boolean,
    tolerance: Double = HOME_TREND_TOLERANCE,
): HomeTone? {
    if (value == null || average == null || average <= 0.0) return null
    val change = (value - average) / average
    if (abs(change) <= tolerance) return HomeTone.FAIR
    val improved = if (higherIsBetter) change > 0 else change < 0
    return if (improved) HomeTone.GOOD else HomeTone.POOR
}
