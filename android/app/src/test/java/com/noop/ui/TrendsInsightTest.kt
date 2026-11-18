package com.noop.ui

import com.noop.R
import com.noop.analytics.TrainingLoadEngine
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Trends tab's maths and plain-English lines (TrendsInsight.kt): period averages against the period
 * before, the normal band, the rolling Charge line, the bar and bucket grouping, the date axis, the direction
 * each arrow takes, and the sentence and form each section picks. Pure functions, so every rule is pinned here
 * without a phone or a strap.
 */
class TrendsInsightTest {

    private val end: LocalDate = LocalDate.of(2026, 11, 30)

    private fun epoch(day: String): Long = LocalDate.parse(day).toEpochDay()

    private fun point(chronic: Double, acute: Double = chronic, day: String = "2026-11-30") =
        TrainingLoadEngine.Point(day = day, load = 0.0, chronicLoad = chronic, acuteLoad = acute, balance = chronic - acute)

    private val noSeries = TrendsSeries(
        charge = emptyMap(),
        hrv = emptyMap(),
        restingHr = emptyMap(),
        effort = emptyMap(),
        rest = emptyMap(),
        weight = emptyMap(),
        skinDeviation = emptyMap(),
        skinAbsolute = emptyMap(),
    )

    // MARK: - trendPeriodStats

    /** 21 to 30 November valued 1 to 10, the ten days before at 2, and a day after the period that must not count. */
    private val tenDayPeriod: Map<String, Double> =
        (21..30).associate { "2026-11-%02d".format(it) to (it - 20).toDouble() } +
            (11..20).associate { "2026-11-%02d".format(it) to 2.0 } +
            mapOf("2026-11-10" to 50.0, "2026-12-01" to 99.0)

    @Test
    fun periodAverageAndLowAndPeakCoverOnlyThePeriod() {
        val stats = trendPeriodStats(tenDayPeriod, end, 10)
        assertEquals(10, stats.points.size)
        assertEquals("2026-11-21", stats.points.first().first)
        assertEquals("2026-11-30", stats.points.last().first)
        assertEquals(5.5, stats.average!!, 1e-9)
        assertEquals(1.0, stats.low!!, 1e-9)
        assertEquals(10.0, stats.peak!!, 1e-9)
    }

    @Test
    fun previousAverageIsTheSameSpanJustBefore() {
        val stats = trendPeriodStats(tenDayPeriod, end, 10)
        // 11 to 20 November only: the 50 on 10 November sits outside the previous span.
        assertEquals(2.0, stats.previousAverage!!, 1e-9)
        assertEquals(3.5, stats.delta!!, 1e-9)
    }

    @Test
    fun normalBandIsTheTenthToNinetiethPercentileOfThePeriod() {
        val stats = trendPeriodStats(tenDayPeriod, end, 10)
        assertEquals(1.9, stats.bandLow!!, 1e-9)
        assertEquals(9.1, stats.bandHigh!!, 1e-9)
        assertEquals(stats.bandLow!! to stats.bandHigh!!, stats.band)
    }

    @Test
    fun noBandUntilThePeriodHasSevenDays() {
        val six = (25..30).associate { "2026-11-%02d".format(it) to it.toDouble() }
        val stats = trendPeriodStats(six, end, 30)
        assertEquals(6, stats.points.size)
        assertNull(stats.bandLow)
        assertNull(stats.band)
        assertEquals(7, TRENDS_BAND_MIN_DAYS)
    }

    @Test
    fun sparsePreviousPeriodGivesNoArrow() {
        // Thirty days need six of the previous thirty to carry a value; five is too few.
        val series = (1..30).associate { "2026-11-%02d".format(it) to 60.0 } +
            (10..14).associate { "2026-10-%02d".format(it) to 40.0 }
        val stats = trendPeriodStats(series, end, 30)
        assertNull(stats.previousAverage)
        assertNull(stats.delta)
        val enough = series + ("2026-10-15" to 40.0)
        assertEquals(40.0, trendPeriodStats(enough, end, 30).previousAverage!!, 1e-9)
    }

    @Test
    fun allHistoryTakesEveryDayAndHasNoPreviousPeriod() {
        val series = mapOf("2024-01-01" to 10.0, "2026-11-30" to 30.0, "2026-12-01" to 99.0)
        val stats = trendPeriodStats(series, end, null)
        assertEquals(2, stats.points.size)
        assertEquals(20.0, stats.average!!, 1e-9)
        assertNull(stats.previousAverage)
    }

    @Test
    fun emptyPeriodIsEmpty() {
        val stats = trendPeriodStats(mapOf("2026-01-01" to 10.0), end, 30)
        assertEquals(TrendPeriodStats.EMPTY, stats)
        assertNull(stats.average)
        assertTrue(stats.epochDays.isEmpty())
    }

    @Test
    fun epochDaysFollowThePoints() {
        val stats = trendPeriodStats(mapOf("2026-11-29" to 1.0, "2026-11-30" to 2.0), end, 30)
        assertEquals(listOf(epoch("2026-11-29") to 1.0, epoch("2026-11-30") to 2.0), stats.epochDays)
    }

    // MARK: - rollingAverage and rollingLine

    @Test
    fun rollingAverageMeansTheSevenCalendarDaysEndingOnEachDay() {
        val series = mapOf(
            "2026-11-01" to 10.0,
            "2026-11-02" to 20.0,
            "2026-11-03" to 30.0,
            "2026-11-10" to 40.0,
            "not a day" to 99.0,
        )
        val rolling = rollingAverage(series)
        assertEquals(4, rolling.size)
        assertEquals(10.0, rolling.getValue("2026-11-01"), 1e-9)
        assertEquals(15.0, rolling.getValue("2026-11-02"), 1e-9)
        assertEquals(20.0, rolling.getValue("2026-11-03"), 1e-9)
        // 4 to 10 November holds only the 40: the first three days have left the window.
        assertEquals(40.0, rolling.getValue("2026-11-10"), 1e-9)
    }

    @Test
    fun rollingLineCountsDaysBeforeThePeriodStart() {
        val series = (1..10).associate { "2026-11-%02d".format(it) to it * 10.0 }
        val line = rollingLine(series, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 10))
        assertEquals(
            listOf(epoch("2026-11-08") to 50.0, epoch("2026-11-09") to 60.0, epoch("2026-11-10") to 70.0),
            line,
        )
    }

    // MARK: - Bars and buckets

    @Test
    fun restBarsAreWeeklyUpToSixMonths() {
        assertEquals(BarPeriod.WEEK, barPeriodFor(30))
        assertEquals(BarPeriod.WEEK, barPeriodFor(90))
        assertEquals(BarPeriod.WEEK, barPeriodFor(180))
        assertEquals(BarPeriod.MONTH, barPeriodFor(365))
        assertEquals(BarPeriod.MONTH, barPeriodFor(null))
    }

    @Test
    fun weeklyBarsRunMondayToSunday() {
        val points = listOf("2026-11-16" to 60.0, "2026-11-18" to 80.0, "2026-11-23" to 50.0)
        val bars = periodBars(points, BarPeriod.WEEK)
        assertEquals(
            listOf(
                PeriodBar(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22), 70.0),
                PeriodBar(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 29), 50.0),
            ),
            bars,
        )
    }

    @Test
    fun monthlyBarsRunFirstToLastDay() {
        val points = listOf("2026-11-30" to 80.0, "2026-10-31" to 40.0, "2026-11-01" to 60.0)
        val bars = periodBars(points, BarPeriod.MONTH)
        assertEquals(
            listOf(
                PeriodBar(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 40.0),
                PeriodBar(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 70.0),
            ),
            bars,
        )
    }

    @Test
    fun epochPointsSortAndSkipKeysThatAreNotDays() {
        val points = listOf("2026-11-02" to 2.0, "bad" to 9.0, "2026-11-01" to 1.0)
        assertEquals(listOf(epoch("2026-11-01") to 1.0, epoch("2026-11-02") to 2.0), epochPoints(points))
    }

    @Test
    fun dayBucketsMeanEachRunOfDaysFromTheStart() {
        val start = epoch("2026-11-01")
        val points = listOf(start - 1 to 99.0, start to 10.0, start + 1 to 20.0, start + 2 to 30.0, start + 5 to 60.0)
        assertEquals(
            listOf(
                DayBucket(start, start + 1, 15.0),
                DayBucket(start + 2, start + 3, 30.0),
                DayBucket(start + 4, start + 5, 60.0),
            ),
            dayBuckets(points, start, 2),
        )
    }

    @Test
    fun oneDayBucketsKeepEveryDay() {
        val start = epoch("2026-11-01")
        val points = listOf(start to 10.0, start + 1 to 20.0)
        val expected = listOf(DayBucket(start, start, 10.0), DayBucket(start + 1, start + 1, 20.0))
        assertEquals(expected, dayBuckets(points, start, 1))
        assertEquals(expected, dayBuckets(points, start, 0))
    }

    @Test
    fun daysPerBarFitsTheSpanIntoTheBars() {
        assertEquals(1, daysPerBar(30, 100))
        assertEquals(4, daysPerBar(365, 100))
        assertEquals(1, daysPerBar(0, 10))
        assertEquals(100, daysPerBar(100, 0))
    }

    // MARK: - Axis

    @Test
    fun axisStartsAtThePeriodOrTheEarliestReading() {
        assertEquals(LocalDate.of(2026, 11, 1), trendsAxisStart(end, 30, null))
        assertEquals(LocalDate.of(2025, 1, 5), trendsAxisStart(end, null, LocalDate.of(2025, 1, 5)))
        assertEquals(LocalDate.of(2026, 11, 1), trendsAxisStart(end, null, null))
        assertEquals(LocalDate.of(2026, 11, 1), trendsAxisStart(end, null, end))
    }

    @Test
    fun chargeFloorIsTheMultipleOfTwentyBelowTheLowestPoint() {
        assertEquals(40.0, chargeAxisFloor(63.0), 1e-9)
        assertEquals(20.0, chargeAxisFloor(41.0), 1e-9)
        assertEquals(0.0, chargeAxisFloor(5.0), 1e-9)
        assertEquals(0.0, chargeAxisFloor(2.0), 1e-9)
        assertEquals(80.0, chargeAxisFloor(99.0), 1e-9)
        assertEquals(80.0, chargeAxisFloor(100.0), 1e-9)
    }

    @Test
    fun shortSpansLabelTheStartMiddleAndEndDay() {
        val ticks = dateTicks(LocalDate.of(2026, 11, 1), end, locale = Locale.ENGLISH)
        assertEquals(listOf("1 Nov", "15 Nov", "30 Nov"), ticks.map { it.label })
        assertEquals(0f, ticks[0].fraction, 1e-6f)
        assertEquals(14f / 29f, ticks[1].fraction, 1e-6f)
        assertEquals(1f, ticks[2].fraction, 1e-6f)
    }

    @Test
    fun monthSpansLabelTheFirstOfEachMonth() {
        val ticks = dateTicks(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), locale = Locale.ENGLISH)
        assertEquals(listOf("Feb", "Mar", "Apr", "May", "Jun"), ticks.map { it.label })
        assertTrue(ticks.zipWithNext().all { (a, b) -> a.fraction < b.fraction })
    }

    @Test
    fun aYearOfMonthsIsThinnedToFiveTicksAtMost() {
        val ticks = dateTicks(LocalDate.of(2025, 11, 30), end, locale = Locale.ENGLISH)
        assertEquals(4, ticks.size)
        assertEquals("Dec", ticks[0].label)
        assertEquals("Mar", ticks[1].label)
    }

    @Test
    fun longSpansLabelEachYear() {
        val ticks = dateTicks(LocalDate.of(2022, 3, 1), end, locale = Locale.ENGLISH)
        assertEquals(listOf("2023", "2024", "2025", "2026"), ticks.map { it.label })
    }

    @Test
    fun aOneDaySpanHasOneCentredTick() {
        val ticks = dateTicks(end, end, locale = Locale.ENGLISH)
        assertEquals(listOf(AxisTick(0.5f, "30 Nov")), ticks)
    }

    @Test
    fun spanLabelsUseDaysUnderTwoMonthsAndMonthsBeyond() {
        assertEquals("1 Nov" to "30 Nov", spanLabels(LocalDate.of(2026, 11, 1), end, Locale.ENGLISH))
        assertEquals("Jan" to "Nov", spanLabels(LocalDate.of(2026, 1, 1), end, Locale.ENGLISH))
    }

    @Test
    fun axisFractionRunsFromZeroAtTheStartToOneAtTheEnd() {
        val axis = trendAxis(LocalDate.of(2026, 11, 1), end, Locale.ENGLISH)
        assertEquals(0f, axis.fraction(epoch("2026-11-01")), 1e-6f)
        assertEquals(14f / 29f, axis.fraction(epoch("2026-11-15")), 1e-6f)
        assertEquals(1f, axis.fraction(epoch("2026-11-30")), 1e-6f)
        assertEquals("1 Nov", axis.startLabel)
        assertEquals("30 Nov", axis.endLabel)
        assertEquals(0.5f, trendAxis(end, end, Locale.ENGLISH).fraction(epoch("2026-11-30")), 1e-6f)
    }

    // MARK: - This week

    @Test
    fun weekSummaryRunsMondayToSundayAgainstTheWeekBefore() {
        val series = mapOf(
            "2026-11-16" to 60.0,
            "2026-11-18" to 80.0,
            "2026-11-09" to 50.0,
            "2026-11-10" to 50.0,
            "2026-11-11" to 50.0,
            "2026-11-23" to 99.0,
        )
        val week = weekSummary(series, LocalDate.of(2026, 11, 16))
        assertEquals(listOf(60.0, null, 80.0, null, null, null, null), week.daily)
        assertEquals(70.0, week.average!!, 1e-9)
        assertEquals(50.0, week.previousAverage!!, 1e-9)
        assertEquals(20.0, week.delta!!, 1e-9)
    }

    @Test
    fun weekBeforeNeedsThreeDaysForAnArrow() {
        val series = mapOf("2026-11-16" to 60.0, "2026-11-09" to 50.0, "2026-11-10" to 50.0)
        val week = weekSummary(series, LocalDate.of(2026, 11, 16))
        assertNull(week.previousAverage)
        assertNull(week.delta)
        assertNull(weekSummary(emptyMap(), LocalDate.of(2026, 11, 16)).average)
    }

    @Test
    fun weekLabelNamesTheMonthOnceInsideAMonth() {
        assertEquals("16–22 Nov", trendsWeekRangeLabel(LocalDate.of(2026, 11, 16), Locale.ENGLISH))
        assertEquals("26 Oct – 1 Nov", trendsWeekRangeLabel(LocalDate.of(2026, 10, 26), Locale.ENGLISH))
    }

    // MARK: - Line helpers

    @Test
    fun nearestIndexFindsTheClosestDay() {
        val days = listOf(10L, 20L, 30L)
        assertEquals(1, nearestIndex(days, 24.0))
        assertEquals(2, nearestIndex(days, 26.0))
        assertEquals(1, nearestIndex(days, 25.0))
        assertEquals(0, nearestIndex(days, 5.0))
        assertEquals(2, nearestIndex(days, 100.0))
        assertEquals(-1, nearestIndex(emptyList(), 5.0))
    }

    @Test
    fun contiguousRunsBreakAtLongGaps() {
        assertEquals(listOf(0..2, 3..4, 5..5), contiguousRuns(listOf(1L, 2L, 3L, 20L, 21L, 40L), 7))
        assertEquals(listOf(0..1), contiguousRuns(listOf(1L, 8L), 7))
        assertEquals(listOf(0..0), contiguousRuns(listOf(5L), 7))
        assertTrue(contiguousRuns(emptyList(), 7).isEmpty())
    }

    // MARK: - trendDirection

    @Test
    fun directionIsSteadyInsideTheToleranceOfTheReference() {
        assertEquals(TrendDirection.STEADY, trendDirection(5.0, 100.0))
        assertEquals(TrendDirection.UP, trendDirection(6.0, 100.0))
        assertEquals(TrendDirection.DOWN, trendDirection(-6.0, 100.0))
        assertEquals(TrendDirection.STEADY, trendDirection(8.0, 100.0, tolerance = 0.10))
    }

    @Test
    fun aFixedSteadyBandReplacesTheShare() {
        assertEquals(TrendDirection.STEADY, trendDirection(0.15, 0.0, steadyBand = TRENDS_SKIN_STEADY_C))
        assertEquals(TrendDirection.UP, trendDirection(0.3, 0.0, steadyBand = TRENDS_SKIN_STEADY_C))
        assertEquals(TrendDirection.DOWN, trendDirection(-0.6, 80.0, steadyBand = TRENDS_WEIGHT_STEADY_KG))
        assertEquals(TrendDirection.UP, trendDirection(0.15, 0.0))
    }

    @Test
    fun noDirectionWithoutBothSides() {
        assertNull(trendDirection(null, 100.0))
        assertNull(trendDirection(5.0, null))
    }

    // MARK: - Section sentences

    @Test
    fun recoveryNamesHrvOnlyWhenItMovedTheSameWay() {
        val up = TrendDirection.UP
        val down = TrendDirection.DOWN
        val steady = TrendDirection.STEADY
        assertEquals(TrendSentence(R.string.trends_recovery_empty, SentenceArgs.NONE), recoveryTrendSentence(null, null, false))
        assertEquals(TrendSentence(R.string.trends_recovery_average, SentenceArgs.LEVEL), recoveryTrendSentence(null, up, true))
        assertEquals(TrendSentence(R.string.trends_recovery_up_hrv, SentenceArgs.CHANGE), recoveryTrendSentence(up, up, true))
        assertEquals(TrendSentence(R.string.trends_recovery_up, SentenceArgs.CHANGE), recoveryTrendSentence(up, down, true))
        assertEquals(TrendSentence(R.string.trends_recovery_up, SentenceArgs.CHANGE), recoveryTrendSentence(up, null, true))
        assertEquals(TrendSentence(R.string.trends_recovery_down_hrv, SentenceArgs.CHANGE), recoveryTrendSentence(down, down, true))
        assertEquals(TrendSentence(R.string.trends_recovery_down, SentenceArgs.CHANGE), recoveryTrendSentence(down, steady, true))
        assertEquals(TrendSentence(R.string.trends_recovery_steady, SentenceArgs.LEVEL), recoveryTrendSentence(steady, up, true))
    }

    @Test
    fun sleepSentenceFollowsRest() {
        assertEquals(TrendSentence(R.string.trends_sleep_empty, SentenceArgs.NONE), sleepTrendSentence(null, false))
        assertEquals(TrendSentence(R.string.trends_sleep_average, SentenceArgs.LEVEL), sleepTrendSentence(null, true))
        assertEquals(TrendSentence(R.string.trends_sleep_up, SentenceArgs.CHANGE), sleepTrendSentence(TrendDirection.UP, true))
        assertEquals(TrendSentence(R.string.trends_sleep_down, SentenceArgs.CHANGE), sleepTrendSentence(TrendDirection.DOWN, true))
        assertEquals(TrendSentence(R.string.trends_sleep_steady, SentenceArgs.LEVEL), sleepTrendSentence(TrendDirection.STEADY, true))
    }

    @Test
    fun effortSentenceFollowsEffort() {
        assertEquals(TrendSentence(R.string.trends_training_empty, SentenceArgs.NONE), effortTrendSentence(null, false))
        assertEquals(TrendSentence(R.string.trends_training_average, SentenceArgs.LEVEL), effortTrendSentence(null, true))
        assertEquals(TrendSentence(R.string.trends_training_up, SentenceArgs.CHANGE), effortTrendSentence(TrendDirection.UP, true))
        assertEquals(TrendSentence(R.string.trends_training_down, SentenceArgs.CHANGE), effortTrendSentence(TrendDirection.DOWN, true))
        assertEquals(TrendSentence(R.string.trends_training_steady, SentenceArgs.LEVEL), effortTrendSentence(TrendDirection.STEADY, true))
    }

    @Test
    fun bodySentencesAreLeftOutWithoutReadings() {
        assertNull(weightTrendSentence(TrendDirection.UP, false))
        assertNull(skinTrendSentence(TrendDirection.UP, false))
        assertEquals(TrendSentence(R.string.trends_body_weight_average, SentenceArgs.LEVEL), weightTrendSentence(null, true))
        assertEquals(TrendSentence(R.string.trends_body_weight_up, SentenceArgs.CHANGE), weightTrendSentence(TrendDirection.UP, true))
        assertEquals(TrendSentence(R.string.trends_body_weight_down, SentenceArgs.CHANGE), weightTrendSentence(TrendDirection.DOWN, true))
        assertEquals(TrendSentence(R.string.trends_body_weight_steady, SentenceArgs.LEVEL), weightTrendSentence(TrendDirection.STEADY, true))
        assertEquals(TrendSentence(R.string.trends_body_skin_average, SentenceArgs.LEVEL), skinTrendSentence(null, true))
        assertEquals(TrendSentence(R.string.trends_body_skin_up, SentenceArgs.CHANGE), skinTrendSentence(TrendDirection.UP, true))
        assertEquals(TrendSentence(R.string.trends_body_skin_down, SentenceArgs.CHANGE), skinTrendSentence(TrendDirection.DOWN, true))
        assertEquals(TrendSentence(R.string.trends_body_skin_steady, SentenceArgs.NONE), skinTrendSentence(TrendDirection.STEADY, true))
    }

    // MARK: - Training load

    @Test
    fun formIsBalancedWithinATenthOfFitness() {
        assertEquals(TrainingForm.FRESH, trainingForm(point(chronic = 10.0, acute = 8.0)))
        assertEquals(TrainingForm.LOADED, trainingForm(point(chronic = 10.0, acute = 12.0)))
        assertEquals(TrainingForm.BALANCED, trainingForm(point(chronic = 10.0, acute = 9.5)))
        assertEquals(TrainingForm.BALANCED, trainingForm(point(chronic = 10.0, acute = 9.0)))
    }

    @Test
    fun fitnessDirectionLooksFourWeeksBack() {
        fun history(latest: Double): List<TrainingLoadEngine.Point> =
            List(30) { index -> point(chronic = if (index == 29) latest else if (index <= 1) 10.0 else 50.0) }
        // 30 points: the latest is compared with the one 28 points back (index 1), not the busier days between.
        assertEquals(TrendDirection.UP, fitnessDirection(history(12.0)))
        assertEquals(TrendDirection.STEADY, fitnessDirection(history(10.5)))
        assertEquals(TrendDirection.DOWN, fitnessDirection(history(8.0)))
    }

    @Test
    fun shortHistoryComparesWithTheFirstPoint() {
        val points = listOf(point(chronic = 10.0), point(chronic = 30.0), point(chronic = 12.0))
        assertEquals(TrendDirection.UP, fitnessDirection(points))
        assertNull(fitnessDirection(listOf(point(chronic = 10.0))))
    }

    @Test
    fun trainingSentencesNameFitnessThenForm() {
        assertEquals(
            listOf(R.string.trends_training_fitness_building, R.string.trends_training_form_fresh),
            trainingLoadSentences(TrendDirection.UP, TrainingForm.FRESH),
        )
        assertEquals(
            listOf(R.string.trends_training_fitness_holding, R.string.trends_training_form_balanced),
            trainingLoadSentences(TrendDirection.STEADY, TrainingForm.BALANCED),
        )
        assertEquals(
            listOf(R.string.trends_training_fitness_easing, R.string.trends_training_form_loaded),
            trainingLoadSentences(TrendDirection.DOWN, TrainingForm.LOADED),
        )
        assertEquals(listOf(R.string.trends_training_form_loaded), trainingLoadSentences(null, TrainingForm.LOADED))
    }

    @Test
    fun formWordsMatchTheForm() {
        assertEquals(R.string.trends_form_fresh, trainingFormWord(TrainingForm.FRESH))
        assertEquals(R.string.trends_form_balanced, trainingFormWord(TrainingForm.BALANCED))
        assertEquals(R.string.trends_form_loaded, trainingFormWord(TrainingForm.LOADED))
    }

    // MARK: - gridValues

    @Test
    fun gridLinesFrameTheDataOnRoundNumbers() {
        assertEquals(listOf(40.0, 60.0, 80.0, 100.0), gridValues(40.0, 100.0, 20.0))
        assertEquals(listOf(0.0, 7.0, 14.0, 21.0), gridValues(0.0, 21.0, 7.0))
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0), gridValues(3.0, 17.0, 5.0))
        assertEquals(13, gridValues(0.0, 1000.0, 1.0).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gridStepMustBePositive() {
        gridValues(0.0, 10.0, 0.0)
    }

    // MARK: - trendsPeriodModel

    @Test
    fun skinUsesThePreferredScaleWhenBothHaveReadings() {
        val series = noSeries.copy(
            skinDeviation = mapOf("2026-11-30" to 0.3),
            skinAbsolute = mapOf("2026-11-30" to 33.8),
        )
        val deviation = trendsPeriodModel(series, 30, end, preferDeviation = true)
        assertTrue(deviation.skinIsDeviation)
        assertEquals(0.3, deviation.skin.average!!, 1e-9)
        val absolute = trendsPeriodModel(series, 30, end, preferDeviation = false)
        assertFalse(absolute.skinIsDeviation)
        assertEquals(33.8, absolute.skin.average!!, 1e-9)
    }

    @Test
    fun skinFallsBackToWhicheverScaleHasReadings() {
        val onlyAbsolute = noSeries.copy(skinAbsolute = mapOf("2026-11-30" to 33.8))
        assertFalse(trendsPeriodModel(onlyAbsolute, 30, end, preferDeviation = true).skinIsDeviation)
        val onlyDeviation = noSeries.copy(skinDeviation = mapOf("2026-11-30" to -0.2))
        assertTrue(trendsPeriodModel(onlyDeviation, 30, end, preferDeviation = false).skinIsDeviation)
    }

    @Test
    fun periodModelSharesOneAxisOverThePeriod() {
        val series = noSeries.copy(charge = (1..30).associate { "2026-11-%02d".format(it) to 60.0 })
        val model = trendsPeriodModel(series, 30, end, preferDeviation = false)
        assertEquals(30, model.days)
        assertEquals(epoch("2026-11-01"), model.axis.startDay)
        assertEquals(epoch("2026-11-30"), model.axis.endDay)
        assertEquals(30, model.chargeLine.size)
        assertEquals(60.0, model.charge.average!!, 1e-9)
        assertNull(model.weight.average)
    }

    @Test
    fun allHistoryStartsAtTheEarliestReadingInAnySeries() {
        val series = noSeries.copy(
            charge = mapOf("2026-01-01" to 60.0, "2026-11-30" to 70.0),
            weight = mapOf("2025-06-01" to 80.0),
        )
        val model = trendsPeriodModel(series, null, end, preferDeviation = false)
        assertNull(model.days)
        assertEquals(epoch("2025-06-01"), model.axis.startDay)
        assertNull(model.charge.previousAverage)
    }
}
