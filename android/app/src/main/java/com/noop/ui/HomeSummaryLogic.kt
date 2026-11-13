package com.noop.ui

import com.noop.data.DailyMetric
import java.time.LocalDate
import java.util.Locale
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
