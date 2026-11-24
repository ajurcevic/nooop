package com.noop.ui

import androidx.annotation.StringRes
import com.noop.R
import com.noop.analytics.TrainingLoadEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// MARK: - Trends periods (Zoop "Calm" redesign)
//
// The Trends tab reads each metric over the selected period (the last 30, 90, 180 or 365 days, or all
// history): the period's average, its low and peak, a normal band (the middle 80% of its days) and the
// change against the same length of time just before it. The maths, the axis labels and the choice of each
// section's plain-English line live here as pure functions, so JVM tests pin them; TrendsCalmCards.kt only
// draws what these return.

/** Fewest days in a period before its normal band is drawn, the same floor the Metrics range uses. */
internal const val TRENDS_BAND_MIN_DAYS = METRICS_RANGE_MIN_DAYS

/** Share of the previous period's days that must carry a value before the arrow compares with it. */
internal const val TRENDS_PREVIOUS_MIN_SHARE = 0.2

/** Calendar days in the rolling average the Charge chart plots. */
internal const val TRENDS_ROLLING_DAYS = 7

/** Skin temperature reads as steady while its period average moves less than this many degrees Celsius. */
internal const val TRENDS_SKIN_STEADY_C = 0.2

/** Weight reads as steady while its period average moves less than this many kilograms. */
internal const val TRENDS_WEIGHT_STEADY_KG = 0.5

/** Share of chronic load inside which fitness reads as holding, and fatigue as close to fitness. */
internal const val TRENDS_LOAD_TOLERANCE = 0.10

/** Days back the fitness trend looks when it says whether fitness is building or easing. */
internal const val TRENDS_FITNESS_LOOKBACK_DAYS = 28

/** One metric over a Trends period, with the period before it for the arrow. */
internal data class TrendPeriodStats(
    /** The period's days and values, oldest first. */
    val points: List<Pair<String, Double>>,
    val average: Double?,
    /** The previous period's average, null for all history or when too few of its days carry a value. */
    val previousAverage: Double?,
    val low: Double?,
    val peak: Double?,
    /** Bottom of the normal band: the 10th percentile of the period's days. */
    val bandLow: Double?,
    /** Top of the normal band: the 90th percentile. */
    val bandHigh: Double?,
) {
    /** The period average less the previous one, null without both. */
    val delta: Double? get() = if (average != null && previousAverage != null) average - previousAverage else null

    /** The normal band as low to high, null until the period has enough days for one. */
    val band: Pair<Double, Double>? get() = if (bandLow != null && bandHigh != null) bandLow to bandHigh else null

    /** [points] as epoch days, for the charts. */
    val epochDays: List<Pair<Long, Double>> by lazy { epochPoints(points) }

    companion object {
        val EMPTY = TrendPeriodStats(emptyList(), null, null, null, null, null, null)
    }
}

/**
 * [series] over the [days] calendar days ending on [endDay], and the same span just before it. A null [days]
 * takes every day up to [endDay] and has no previous period, so it shows no arrows.
 */
internal fun trendPeriodStats(series: Map<String, Double>, endDay: LocalDate, days: Int?): TrendPeriodStats {
    val endKey = endDay.toString()
    val startKey = days?.let { endDay.minusDays((it - 1).toLong()).toString() }
    val points = series.entries
        .filter { (day, _) -> day <= endKey && (startKey == null || day >= startKey) }
        .sortedBy { it.key }
        .map { it.key to it.value }
    if (points.isEmpty()) return TrendPeriodStats.EMPTY
    val values = points.map { it.second }
    val sorted = values.sorted()
    val hasBand = sorted.size >= TRENDS_BAND_MIN_DAYS
    val previous = days?.let { span ->
        val previousEnd = endDay.minusDays(span.toLong()).toString()
        val previousStart = endDay.minusDays((2 * span - 1).toLong()).toString()
        series.filterKeys { it in previousStart..previousEnd }.values
            .takeIf { it.size >= maxOf(HOME_AVERAGE_MIN_DAYS, (span * TRENDS_PREVIOUS_MIN_SHARE).toInt()) }
            ?.average()
    }
    return TrendPeriodStats(
        points = points,
        average = values.average(),
        previousAverage = previous,
        low = sorted.first(),
        peak = sorted.last(),
        bandLow = if (hasBand) percentile(sorted, METRICS_RANGE_LOW) else null,
        bandHigh = if (hasBand) percentile(sorted, METRICS_RANGE_HIGH) else null,
    )
}

/**
 * Each day's mean over the [window] calendar days ending on it, for every day in [series] that carries a
 * value. Days before a period start still count toward its first averages, so the line does not jump there.
 */
internal fun rollingAverage(series: Map<String, Double>, window: Int = TRENDS_ROLLING_DAYS): Map<String, Double> {
    val dated = series.entries
        .mapNotNull { (day, value) -> parseDay(day)?.let { Triple(day, it.toEpochDay(), value) } }
        .sortedBy { it.second }
    val result = LinkedHashMap<String, Double>(dated.size)
    var start = 0
    var sum = 0.0
    for (end in dated.indices) {
        sum += dated[end].third
        while (dated[start].second <= dated[end].second - window) {
            sum -= dated[start].third
            start += 1
        }
        result[dated[end].first] = sum / (end - start + 1)
    }
    return result
}

/** Longest range, in days, whose Rest bars are weekly; longer ranges and all history bar by month. */
internal const val TRENDS_WEEKLY_BARS_MAX_DAYS = 180

/** The calendar span one Rest bar covers. */
internal enum class BarPeriod { WEEK, MONTH }

/** Weekly bars up to six months, monthly beyond, so the bars never get thinner than the mock's. */
internal fun barPeriodFor(days: Int?): BarPeriod =
    if (days != null && days <= TRENDS_WEEKLY_BARS_MAX_DAYS) BarPeriod.WEEK else BarPeriod.MONTH

/** One week's or month's mean, for the Rest bars. [start] and [end] are the period's first and last day. */
internal data class PeriodBar(val start: LocalDate, val end: LocalDate, val average: Double)

/**
 * The means of [points] per Monday-to-Sunday week or per calendar month, oldest first. A period counts as
 * soon as one of its days has a value.
 */
internal fun periodBars(points: List<Pair<String, Double>>, period: BarPeriod): List<PeriodBar> =
    points
        .mapNotNull { (day, value) -> parseDay(day)?.let { it to value } }
        .groupBy({ (date, _) -> barStart(date, period) }, { it.second })
        .map { (start, values) -> PeriodBar(start, barEnd(start, period), values.average()) }
        .sortedBy { it.start }

private fun barStart(date: LocalDate, period: BarPeriod): LocalDate = when (period) {
    BarPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    BarPeriod.MONTH -> date.withDayOfMonth(1)
}

private fun barEnd(start: LocalDate, period: BarPeriod): LocalDate = when (period) {
    BarPeriod.WEEK -> start.plusDays(6)
    BarPeriod.MONTH -> start.with(TemporalAdjusters.lastDayOfMonth())
}

/** [points] as epoch days and values, oldest first, leaving out any key that is not an ISO date. */
internal fun epochPoints(points: List<Pair<String, Double>>): List<Pair<Long, Double>> =
    points.mapNotNull { (day, value) -> parseDay(day)?.let { it.toEpochDay() to value } }.sortedBy { it.first }

/** A run of calendar days drawn as one bar: its first and last epoch day and the mean of its values. */
internal data class DayBucket(val firstDay: Long, val lastDay: Long, val average: Double)

/**
 * [points] (epoch day to value) grouped into runs of [daysPerBucket] calendar days counted from [startDay],
 * each with its mean, so a long range draws one bar per run instead of bars thinner than a pixel. One day per
 * bucket keeps every day as its own bar. Points before [startDay] are left out.
 */
internal fun dayBuckets(points: List<Pair<Long, Double>>, startDay: Long, daysPerBucket: Int): List<DayBucket> {
    val size = daysPerBucket.coerceAtLeast(1)
    return points
        .filter { it.first >= startDay }
        .groupBy({ (it.first - startDay) / size }, { it.second })
        .map { (index, values) ->
            val first = startDay + index * size
            DayBucket(first, first + size - 1, values.average())
        }
        .sortedBy { it.firstDay }
}

/** Calendar days per bar so that [spanDays] days fit in at most [maxBars] bars. */
internal fun daysPerBar(spanDays: Long, maxBars: Int): Int {
    val bars = maxBars.coerceAtLeast(1)
    return ((spanDays + bars - 1) / bars).toInt().coerceAtLeast(1)
}

/**
 * The first day a Trends chart covers: [days] back from [endDay], or the [earliest] reading for all history.
 * All history with no reading before [endDay] falls back to the last 30 days, so the axis never collapses.
 */
internal fun trendsAxisStart(endDay: LocalDate, days: Int?, earliest: LocalDate?): LocalDate = when {
    days != null -> endDay.minusDays((days - 1).toLong())
    earliest != null && earliest.isBefore(endDay) -> earliest
    else -> endDay.minusDays(29)
}

/**
 * The Charge chart's lowest gridline: the multiple of 20 below the lowest point, with a little room under it,
 * never below zero. The chart always tops out at 100, Charge's ceiling.
 */
internal fun chargeAxisFloor(lowest: Double): Double = (floor((lowest - 5.0) / 20.0) * 20.0).coerceIn(0.0, 80.0)

/** One Monday-to-Sunday week of a metric, for the This-week card. */
internal data class WeekSummary(
    /** Monday to Sunday, null on a day without a value. */
    val daily: List<Double?>,
    val average: Double?,
    /** The week before's average, once [HOME_AVERAGE_MIN_DAYS] of its days carry a value. */
    val previousAverage: Double?,
) {
    val delta: Double? get() = if (average != null && previousAverage != null) average - previousAverage else null
}

/** [series] for the week starting on [monday], set against the week before it. */
internal fun weekSummary(series: Map<String, Double>, monday: LocalDate): WeekSummary {
    val daily = (0L..6L).map { series[monday.plusDays(it).toString()] }
    val previous = (1L..7L).mapNotNull { series[monday.minusDays(it).toString()] }
    return WeekSummary(
        daily = daily,
        average = daily.filterNotNull().takeIf { it.isNotEmpty() }?.average(),
        previousAverage = previous.takeIf { it.size >= HOME_AVERAGE_MIN_DAYS }?.average(),
    )
}

/** "11–17 Nov", or "28 Oct – 3 Nov" across a month, for the week starting on [monday]. */
internal fun trendsWeekRangeLabel(monday: LocalDate, locale: Locale = Locale.getDefault()): String {
    val sunday = monday.plusDays(6)
    val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    return if (monday.month == sunday.month) {
        "${monday.format(DateTimeFormatter.ofPattern("d", locale))}–${sunday.format(dayMonth)}"
    } else {
        "${monday.format(dayMonth)} – ${sunday.format(dayMonth)}"
    }
}

/** A label under a chart and where it sits along the axis, 0 at the start and 1 at the end. */
internal data class AxisTick(val fraction: Float, val label: String)

/**
 * Date labels for an axis from [start] to [end]: the start, middle and end day for a short span, the first
 * of each month for up to about a year, and the first of each year beyond that, thinned to at most
 * [maxTicks] so they never crowd.
 */
internal fun dateTicks(start: LocalDate, end: LocalDate, maxTicks: Int = 5, locale: Locale = Locale.getDefault()): List<AxisTick> {
    val span = ChronoUnit.DAYS.between(start, end)
    if (span <= 0) return listOf(AxisTick(0.5f, start.format(DateTimeFormatter.ofPattern("d MMM", locale))))
    fun fraction(day: LocalDate): Float = ChronoUnit.DAYS.between(start, day).toFloat() / span
    if (span < 60) {
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        val middle = start.plusDays(span / 2)
        return listOf(start, middle, end).map { AxisTick(fraction(it), it.format(dayMonth)) }
    }
    val yearly = span > 400
    val format = DateTimeFormatter.ofPattern(if (yearly) "yyyy" else "MMM", locale)
    val marks = generateSequence(
        if (yearly) start.withDayOfYear(1).plusYears(1) else start.withDayOfMonth(1).plusMonths(1),
    ) { if (yearly) it.plusYears(1) else it.plusMonths(1) }
        .takeWhile { !it.isAfter(end) }
        .toList()
    val step = (marks.size + maxTicks - 1) / maxTicks
    return marks.filterIndexed { index, _ -> index % step.coerceAtLeast(1) == 0 }
        .map { AxisTick(fraction(it), it.format(format)) }
}

/** The month (or, under two months, the day) a chart starts and ends on, for the small cards' two labels. */
internal fun spanLabels(start: LocalDate, end: LocalDate, locale: Locale = Locale.getDefault()): Pair<String, String> {
    val short = ChronoUnit.DAYS.between(start, end) < 60
    val format = DateTimeFormatter.ofPattern(if (short) "d MMM" else "MMM", locale)
    return start.format(format) to end.format(format)
}

/** The calendar span a Trends chart covers, as epoch days, with its date labels. */
internal data class TrendAxis(
    val startDay: Long,
    val endDay: Long,
    val ticks: List<AxisTick>,
    /** The month (or day) the small cards print under their first point. */
    val startLabel: String,
    /** The month (or day) the small cards print under their last point. */
    val endLabel: String,
) {
    /** Where [day] sits along the axis, 0 at the start and 1 at the end. */
    fun fraction(day: Long): Float =
        if (endDay > startDay) (day - startDay).toFloat() / (endDay - startDay) else 0.5f
}

/** The axis from [start] to [end] with its date ticks and its two end labels. */
internal fun trendAxis(start: LocalDate, end: LocalDate, locale: Locale = Locale.getDefault()): TrendAxis {
    val (first, last) = spanLabels(start, end, locale)
    return TrendAxis(start.toEpochDay(), end.toEpochDay(), dateTicks(start, end, locale = locale), first, last)
}

/** Index of the day in [days] (oldest first) nearest to [target], or -1 when there are none. */
internal fun nearestIndex(days: List<Long>, target: Double): Int {
    if (days.isEmpty()) return -1
    var low = 0
    var high = days.lastIndex
    while (low < high) {
        val mid = (low + high) / 2
        if (days[mid] < target) low = mid + 1 else high = mid
    }
    return if (low > 0 && abs(days[low - 1] - target) <= abs(days[low] - target)) low - 1 else low
}

/**
 * [days] (oldest first) split into runs whose neighbours are at most [maxGap] days apart, as index ranges, so a
 * line breaks across a long gap instead of drawing a straight bridge over weeks without a reading.
 */
internal fun contiguousRuns(days: List<Long>, maxGap: Int): List<IntRange> {
    if (days.isEmpty()) return emptyList()
    val runs = ArrayList<IntRange>()
    var start = 0
    for (index in 1 until days.size) {
        if (days[index] - days[index - 1] > maxGap) {
            runs.add(start until index)
            start = index
        }
    }
    runs.add(start..days.lastIndex)
    return runs
}

/** The Charge chart's line: each day's [TRENDS_ROLLING_DAYS]-day mean from [start] to [end], as epoch days. */
internal fun rollingLine(series: Map<String, Double>, start: LocalDate, end: LocalDate): List<Pair<Long, Double>> {
    val startKey = start.toString()
    val endKey = end.toString()
    return epochPoints(rollingAverage(series).filterKeys { it in startKey..endKey }.toList())
}

/** Every series the Trends tab reads, by day key. */
internal data class TrendsSeries(
    val charge: Map<String, Double>,
    val hrv: Map<String, Double>,
    val restingHr: Map<String, Double>,
    val effort: Map<String, Double>,
    val rest: Map<String, Double>,
    val weight: Map<String, Double>,
    val skinDeviation: Map<String, Double>,
    val skinAbsolute: Map<String, Double>,
    /** Weekly VO₂max estimates, already cut to the current estimation method by [vo2MaxCurrentMethodRun]. */
    val vo2max: Map<String, Double> = emptyMap(),
)

/** Every metric over one Trends period, worked out once per range or data change. */
internal class TrendsPeriodModel(
    /** The period's length in days, null for all history. */
    val days: Int?,
    val axis: TrendAxis,
    val charge: TrendPeriodStats,
    /** Charge's 7-day average across the period, for the line. */
    val chargeLine: List<Pair<Long, Double>>,
    val hrv: TrendPeriodStats,
    val restingHr: TrendPeriodStats,
    val effort: TrendPeriodStats,
    val rest: TrendPeriodStats,
    val weight: TrendPeriodStats,
    val skin: TrendPeriodStats,
    val vo2max: TrendPeriodStats,
    /** Whether [skin] holds deviations from the nightly baseline rather than absolute temperatures. */
    val skinIsDeviation: Boolean,
)

/**
 * [series] over the [days] days ending on [endDay], or all history when null, on one shared axis. Skin
 * temperature uses the owner's preferred scale ([preferDeviation]) when the period has readings on both, and
 * whichever scale has readings otherwise.
 */
internal fun trendsPeriodModel(series: TrendsSeries, days: Int?, endDay: LocalDate, preferDeviation: Boolean): TrendsPeriodModel {
    val charge = trendPeriodStats(series.charge, endDay, days)
    val hrv = trendPeriodStats(series.hrv, endDay, days)
    val restingHr = trendPeriodStats(series.restingHr, endDay, days)
    val effort = trendPeriodStats(series.effort, endDay, days)
    val rest = trendPeriodStats(series.rest, endDay, days)
    val weight = trendPeriodStats(series.weight, endDay, days)
    val deviation = trendPeriodStats(series.skinDeviation, endDay, days)
    val absolute = trendPeriodStats(series.skinAbsolute, endDay, days)
    val vo2max = trendPeriodStats(series.vo2max, endDay, days)
    val skinIsDeviation = when {
        deviation.points.isEmpty() -> false
        absolute.points.isEmpty() -> true
        else -> preferDeviation
    }
    val earliest = listOf(charge, hrv, restingHr, effort, rest, weight, deviation, absolute, vo2max)
        .mapNotNull { it.points.firstOrNull()?.first }
        .minOrNull()
        ?.let(::parseDay)
    val start = trendsAxisStart(endDay, days, earliest)
    return TrendsPeriodModel(
        days = days,
        axis = trendAxis(start, endDay),
        charge = charge,
        chargeLine = rollingLine(series.charge, start, endDay),
        hrv = hrv,
        restingHr = restingHr,
        effort = effort,
        rest = rest,
        weight = weight,
        skin = if (skinIsDeviation) deviation else absolute,
        vo2max = vo2max,
        skinIsDeviation = skinIsDeviation,
    )
}

/** The VO₂max weeks Trends plots: the newest run that shares one estimation method, and whether older weeks were cut. */
internal data class Vo2MaxTrendRun(
    val points: Map<String, Double>,
    val droppedEarlier: Boolean,
)

/**
 * The newest unbroken run of VO₂max [readings] (day, value, estimator id) that share the latest reading's
 * estimator. The two estimators (Nes with a waist, Uth without) can sit several ml/kg/min apart for the same
 * person, so a line joining them would show a jump that is a method change, not a fitness change. A missing
 * id is its own group: an unrecorded method is never assumed to match a known one.
 */
internal fun vo2MaxCurrentMethodRun(readings: List<Triple<String, Double, String?>>): Vo2MaxTrendRun {
    val sorted = readings.sortedBy { it.first }
    val current = sorted.lastOrNull() ?: return Vo2MaxTrendRun(emptyMap(), droppedEarlier = false)
    val run = sorted.takeLastWhile { it.third == current.third }
    return Vo2MaxTrendRun(
        points = run.associateTo(LinkedHashMap()) { it.first to it.second },
        droppedEarlier = run.size < sorted.size,
    )
}

/** Which way a period average moved against the period before. */
internal enum class TrendDirection { UP, DOWN, STEADY }

/**
 * [delta] against [reference]: steady inside [tolerance] of the reference (as a share of it), or inside a
 * fixed [steadyBand] when one is given (skin temperature, whose reference sits near zero). Null without both.
 */
internal fun trendDirection(
    delta: Double?,
    reference: Double?,
    tolerance: Double = HOME_TREND_TOLERANCE,
    steadyBand: Double? = null,
): TrendDirection? {
    if (delta == null || reference == null) return null
    val band = steadyBand ?: (abs(reference) * tolerance)
    return when {
        abs(delta) <= band -> TrendDirection.STEADY
        delta > 0 -> TrendDirection.UP
        else -> TrendDirection.DOWN
    }
}

/** What a section sentence needs filled in: the change and the period's length, the average, or nothing. */
internal enum class SentenceArgs { CHANGE, LEVEL, NONE }

/** A section sentence: its resource and which arguments it takes. */
internal data class TrendSentence(@StringRes val res: Int, val args: SentenceArgs)

private fun change(@StringRes res: Int) = TrendSentence(res, SentenceArgs.CHANGE)
private fun level(@StringRes res: Int) = TrendSentence(res, SentenceArgs.LEVEL)
private fun plain(@StringRes res: Int) = TrendSentence(res, SentenceArgs.NONE)

/**
 * The Recovery card's line, from Charge's move and HRV's. HRV is named only when it moved the same way, so
 * the sentence never credits a signal that went the other way. Null [charge] means no previous period.
 */
internal fun recoveryTrendSentence(charge: TrendDirection?, hrv: TrendDirection?, hasValues: Boolean): TrendSentence =
    when {
        !hasValues -> plain(R.string.trends_recovery_empty)
        charge == null -> level(R.string.trends_recovery_average)
        charge == TrendDirection.UP && hrv == TrendDirection.UP -> change(R.string.trends_recovery_up_hrv)
        charge == TrendDirection.UP -> change(R.string.trends_recovery_up)
        charge == TrendDirection.DOWN && hrv == TrendDirection.DOWN -> change(R.string.trends_recovery_down_hrv)
        charge == TrendDirection.DOWN -> change(R.string.trends_recovery_down)
        else -> level(R.string.trends_recovery_steady)
    }

/** The Sleep card's line, from Rest's move. */
internal fun sleepTrendSentence(rest: TrendDirection?, hasValues: Boolean): TrendSentence = when {
    !hasValues -> plain(R.string.trends_sleep_empty)
    rest == null -> level(R.string.trends_sleep_average)
    rest == TrendDirection.UP -> change(R.string.trends_sleep_up)
    rest == TrendDirection.DOWN -> change(R.string.trends_sleep_down)
    else -> level(R.string.trends_sleep_steady)
}

/** The Training card's fallback line, from Effort's move, while there is too little history for training load. */
internal fun effortTrendSentence(effort: TrendDirection?, hasValues: Boolean): TrendSentence = when {
    !hasValues -> plain(R.string.trends_training_empty)
    effort == null -> level(R.string.trends_training_average)
    effort == TrendDirection.UP -> change(R.string.trends_training_up)
    effort == TrendDirection.DOWN -> change(R.string.trends_training_down)
    else -> level(R.string.trends_training_steady)
}

/** The Body card's weight sentence, null without a weight in the period. */
internal fun weightTrendSentence(weight: TrendDirection?, hasValues: Boolean): TrendSentence? = when {
    !hasValues -> null
    weight == null -> level(R.string.trends_body_weight_average)
    weight == TrendDirection.UP -> change(R.string.trends_body_weight_up)
    weight == TrendDirection.DOWN -> change(R.string.trends_body_weight_down)
    else -> level(R.string.trends_body_weight_steady)
}

/** The Body card's skin temperature sentence, null without a reading in the period. */
internal fun skinTrendSentence(skin: TrendDirection?, hasValues: Boolean): TrendSentence? = when {
    !hasValues -> null
    skin == null -> level(R.string.trends_body_skin_average)
    skin == TrendDirection.UP -> change(R.string.trends_body_skin_up)
    skin == TrendDirection.DOWN -> change(R.string.trends_body_skin_down)
    else -> plain(R.string.trends_body_skin_steady)
}

/** How fresh the owner is: fatigue (acute load) against fitness (chronic load). */
internal enum class TrainingForm { FRESH, BALANCED, LOADED }

/** Form from the latest training-load point: balanced while fatigue is within a tenth of fitness either way. */
internal fun trainingForm(point: TrainingLoadEngine.Point): TrainingForm {
    val band = abs(point.chronicLoad) * TRENDS_LOAD_TOLERANCE
    return when {
        point.balance > band -> TrainingForm.FRESH
        point.balance < -band -> TrainingForm.LOADED
        else -> TrainingForm.BALANCED
    }
}

/**
 * Whether fitness (chronic load) is building, holding or easing: the latest point against the one
 * [TRENDS_FITNESS_LOOKBACK_DAYS] points back, or the first point on a shorter history.
 */
internal fun fitnessDirection(points: List<TrainingLoadEngine.Point>): TrendDirection? {
    if (points.size < 2) return null
    val latest = points.last().chronicLoad
    val earlier = points[(points.size - 1 - TRENDS_FITNESS_LOOKBACK_DAYS).coerceAtLeast(0)].chronicLoad
    return trendDirection(latest - earlier, earlier, tolerance = TRENDS_LOAD_TOLERANCE)
}

/** The Training card's two sentences once training load is available: where fitness is heading, then form. */
internal fun trainingLoadSentences(fitness: TrendDirection?, form: TrainingForm): List<Int> = listOfNotNull(
    when (fitness) {
        TrendDirection.UP -> R.string.trends_training_fitness_building
        TrendDirection.DOWN -> R.string.trends_training_fitness_easing
        TrendDirection.STEADY -> R.string.trends_training_fitness_holding
        null -> null
    },
    when (form) {
        TrainingForm.FRESH -> R.string.trends_training_form_fresh
        TrainingForm.BALANCED -> R.string.trends_training_form_balanced
        TrainingForm.LOADED -> R.string.trends_training_form_loaded
    },
)

/** The word after "Form" on the training-load chip. */
@StringRes
internal fun trainingFormWord(form: TrainingForm): Int = when (form) {
    TrainingForm.FRESH -> R.string.trends_form_fresh
    TrainingForm.BALANCED -> R.string.trends_form_balanced
    TrainingForm.LOADED -> R.string.trends_form_loaded
}

/**
 * Gridline values for a chart over [min]..[max], every [step] from the first multiple of it at or below
 * [min] up to the first at or above [max], so the lines frame the data on round numbers.
 */
internal fun gridValues(min: Double, max: Double, step: Double): List<Double> {
    require(step > 0) { "grid step must be positive" }
    val first = floor(min / step) * step
    val last = ceil(max / step) * step
    val count = ((last - first) / step).toInt().coerceAtMost(12)
    return (0..count).map { first + it * step }
}

/** An ISO "yyyy-MM-dd" day key as a date, null when it does not parse. */
private fun parseDay(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()
