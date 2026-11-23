package com.noop.ui

import androidx.compose.ui.graphics.Color
import com.noop.R
import com.noop.data.AppleDaily
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Metrics tab's maths and plain-English lines (MetricsInsight.kt): the 30-day baseline, the normal range,
 * the chart points, the arrow colours and the sentences behind them. Pure functions, so every rule is
 * pinned here without a phone or a strap.
 */
class MetricsInsightTest {

    /** Ten days, 1 to 10 November 2026, valued 10, 20, ... 100. */
    private val tenDays: Map<String, Double> = (1..10).associate { "2026-11-%02d".format(it) to it * 10.0 }

    // MARK: - metricInsight

    @Test
    fun baselineIsTheMeanOfTheDaysBeforeAndLeavesTheDayItselfOut() {
        val insight = metricInsight(tenDays, "2026-11-10")
        assertEquals(50.0, insight.baseline!!, 1e-9)
    }

    @Test
    fun sevenDayAverageMatchesTheHomeComparison() {
        val insight = metricInsight(tenDays, "2026-11-10")
        assertEquals(60.0, insight.average7!!, 1e-9)
        assertEquals(trailingAverage(tenDays, "2026-11-10"), insight.average7)
    }

    @Test
    fun normalRangeIsTheMiddleEightyPercentOfThePriorDays() {
        val insight = metricInsight(tenDays, "2026-11-10")
        assertEquals(18.0, insight.rangeLow!!, 1e-9)
        assertEquals(82.0, insight.rangeHigh!!, 1e-9)
    }

    @Test
    fun pointsSitAtTheirCalendarOffsetAndEndOnTheDay() {
        val points = metricInsight(tenDays, "2026-11-10").points
        assertEquals(10, points.size)
        assertEquals(21 to 10.0, points.first())
        assertEquals(METRICS_BASELINE_DAYS to 100.0, points.last())
    }

    @Test
    fun theDisplayedValueEndsTheChartButNeverJoinsTheBaseline() {
        val insight = metricInsight(tenDays, "2026-11-10", dayValue = 120.0)
        assertEquals(METRICS_BASELINE_DAYS to 120.0, insight.points.last())
        assertEquals(50.0, insight.baseline!!, 1e-9)
    }

    @Test
    fun aDayWithNoValueOfItsOwnStillChartsItsPast() {
        val points = metricInsight(tenDays, "2026-11-11").points
        assertEquals(10, points.size)
        assertEquals(29 to 100.0, points.last())
    }

    @Test
    fun daysOlderThanTheWindowAreIgnored() {
        val history = tenDays + ("2026-09-01" to 1_000.0)
        assertEquals(50.0, metricInsight(history, "2026-11-10").baseline!!, 1e-9)
    }

    @Test
    fun aFewDaysGiveABaselineButNoRange() {
        val five = (1..6).associate { "2026-11-%02d".format(it) to 50.0 }
        val insight = metricInsight(five, "2026-11-06")
        assertEquals(50.0, insight.baseline!!, 1e-9)
        assertNull(insight.rangeLow)
        assertNull(insight.rangeHigh)
    }

    @Test
    fun tooFewDaysGiveNothingToCompareWith() {
        val two = mapOf("2026-11-01" to 50.0, "2026-11-02" to 60.0, "2026-11-03" to 70.0)
        val insight = metricInsight(two, "2026-11-03")
        assertNull(insight.baseline)
        assertNull(insight.rangeLow)
        assertNull(insight.average7)
    }

    @Test
    fun aMissingOrUnreadableDayIsEmpty() {
        assertEquals(MetricInsight.EMPTY, metricInsight(tenDays, null))
        assertEquals(MetricInsight.EMPTY, metricInsight(tenDays, "not a day"))
    }

    // MARK: - percentile

    @Test
    fun percentileInterpolatesBetweenNeighbours() {
        val sorted = listOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(2.5, percentile(sorted, 0.5), 1e-9)
        assertEquals(1.0, percentile(sorted, 0.0), 1e-9)
        assertEquals(4.0, percentile(sorted, 1.0), 1e-9)
        assertEquals(7.0, percentile(listOf(7.0), 0.9), 1e-9)
    }

    // MARK: - Arrow colours and sides

    @Test
    fun aMetricWithAHealthyDirectionColoursItsArrow() {
        assertEquals(HomeTone.GOOD, comparisonTone(110.0, 100.0, TrendRule.HIGHER_IS_BETTER))
        assertEquals(HomeTone.POOR, comparisonTone(90.0, 100.0, TrendRule.HIGHER_IS_BETTER))
        assertEquals(HomeTone.FAIR, comparisonTone(103.0, 100.0, TrendRule.HIGHER_IS_BETTER))
        assertEquals(HomeTone.GOOD, comparisonTone(90.0, 100.0, TrendRule.LOWER_IS_BETTER))
        assertEquals(HomeTone.POOR, comparisonTone(110.0, 100.0, TrendRule.LOWER_IS_BETTER))
    }

    @Test
    fun aNeutralMetricOrAMissingSideLeavesTheArrowGrey() {
        assertNull(comparisonTone(150.0, 100.0, TrendRule.NEUTRAL))
        assertNull(comparisonTone(null, 100.0, TrendRule.HIGHER_IS_BETTER))
        assertNull(comparisonTone(100.0, null, TrendRule.HIGHER_IS_BETTER))
    }

    @Test
    fun skinTemperatureIsAmberWhenSteadyAndOrangeWhenItMovesEitherWay() {
        assertEquals(HomeTone.FAIR, comparisonTone(0.3, 0.0, TrendRule.STEADY_IS_BEST))
        assertEquals(HomeTone.FAIR, comparisonTone(0.5, 0.0, TrendRule.STEADY_IS_BEST))
        assertEquals(HomeTone.POOR, comparisonTone(0.8, 0.0, TrendRule.STEADY_IS_BEST))
        assertEquals(HomeTone.POOR, comparisonTone(-0.6, 0.0, TrendRule.STEADY_IS_BEST))
    }

    @Test
    fun closeMeansInsideTheSameBandTheArrowUses() {
        assertEquals(BaselineSide.CLOSE, baselineSide(104.0, 100.0, TrendRule.HIGHER_IS_BETTER))
        assertEquals(BaselineSide.ABOVE, baselineSide(106.0, 100.0, TrendRule.HIGHER_IS_BETTER))
        assertEquals(BaselineSide.BELOW, baselineSide(94.0, 100.0, TrendRule.LOWER_IS_BETTER))
        // Effort swings more, so it reads as typical within a wider band.
        assertEquals(BaselineSide.CLOSE, baselineSide(108.0, 100.0, TrendRule.NEUTRAL, EFFORT_TYPICAL_TOLERANCE))
        assertEquals(BaselineSide.ABOVE, baselineSide(111.0, 100.0, TrendRule.NEUTRAL, EFFORT_TYPICAL_TOLERANCE))
        // Skin temperature uses a fixed band in degrees, whatever its reference.
        assertEquals(BaselineSide.CLOSE, baselineSide(0.4, 0.0, TrendRule.STEADY_IS_BEST))
        assertEquals(BaselineSide.ABOVE, baselineSide(33.9, 33.2, TrendRule.STEADY_IS_BEST))
        assertEquals(BaselineSide.BELOW, baselineSide(-0.6, 0.0, TrendRule.STEADY_IS_BEST))
    }

    @Test
    fun bothEndsOfTheNormalRangeCountAsInside() {
        assertEquals(RangePosition.BELOW, rangePosition(9.0, 10.0, 20.0))
        assertEquals(RangePosition.INSIDE, rangePosition(10.0, 10.0, 20.0))
        assertEquals(RangePosition.INSIDE, rangePosition(20.0, 10.0, 20.0))
        assertEquals(RangePosition.ABOVE, rangePosition(21.0, 10.0, 20.0))
    }

    @Test
    fun theOpenedTileSentencesFollowTheSideAndTheRange() {
        assertEquals(R.string.metrics_sentence_above_baseline, baselineSentence(BaselineSide.ABOVE))
        assertEquals(R.string.metrics_sentence_close_to_baseline, baselineSentence(BaselineSide.CLOSE))
        assertEquals(R.string.metrics_sentence_below_baseline, baselineSentence(BaselineSide.BELOW))
        assertEquals(R.string.metrics_sentence_inside_range, rangeSentence(RangePosition.INSIDE))
        assertEquals(R.string.metrics_sentence_above_range, rangeSentence(RangePosition.ABOVE))
        assertEquals(R.string.metrics_sentence_below_range, rangeSentence(RangePosition.BELOW))
    }

    // MARK: - Card lines

    @Test
    fun recoveryLineFollowsTheChargeBands() {
        assertNull(recoveryChargeLine(null))
        assertEquals(R.string.metrics_recovery_well, recoveryChargeLine(70.0))
        assertEquals(R.string.metrics_recovery_steady, recoveryChargeLine(69.9))
        assertEquals(R.string.metrics_recovery_steady, recoveryChargeLine(50.0))
        assertEquals(R.string.metrics_recovery_low, recoveryChargeLine(49.0))
    }

    @Test
    fun hrvLeadsTheVitalsLineAndRestingHeartRateJoinsWhenTheyAgree() {
        assertEquals(R.string.metrics_recovery_hrv_up_rhr_down, recoveryVitalsLine(HomeTone.GOOD, HomeTone.GOOD))
        assertEquals(R.string.metrics_recovery_hrv_down_rhr_up, recoveryVitalsLine(HomeTone.POOR, HomeTone.POOR))
        assertEquals(R.string.metrics_recovery_hrv_up, recoveryVitalsLine(HomeTone.GOOD, HomeTone.POOR))
        assertEquals(R.string.metrics_recovery_hrv_down, recoveryVitalsLine(HomeTone.POOR, HomeTone.GOOD))
        assertEquals(R.string.metrics_recovery_rhr_down, recoveryVitalsLine(HomeTone.FAIR, HomeTone.GOOD))
        assertEquals(R.string.metrics_recovery_rhr_up, recoveryVitalsLine(null, HomeTone.POOR))
        assertEquals(R.string.metrics_recovery_hrv_close, recoveryVitalsLine(HomeTone.FAIR, HomeTone.FAIR))
        assertEquals(R.string.metrics_recovery_rhr_close, recoveryVitalsLine(null, HomeTone.FAIR))
        assertNull(recoveryVitalsLine(null, null))
    }

    @Test
    fun sleepLinesFollowTheRestBandAndTheAsleepArrow() {
        assertNull(sleepRestLine(null))
        assertEquals(R.string.metrics_sleep_good, sleepRestLine(80.0))
        assertEquals(R.string.metrics_sleep_fair, sleepRestLine(60.0))
        assertEquals(R.string.metrics_sleep_poor, sleepRestLine(40.0))
        assertEquals(R.string.metrics_sleep_more, sleepDurationLine(HomeTone.GOOD))
        assertEquals(R.string.metrics_sleep_usual, sleepDurationLine(HomeTone.FAIR))
        assertEquals(R.string.metrics_sleep_less, sleepDurationLine(HomeTone.POOR))
        assertEquals(R.string.metrics_sleep_plain, sleepDurationLine(null))
    }

    @Test
    fun trainingLineComparesEffortAndSaysSoFarOnlyForToday() {
        assertEquals(R.string.metrics_training_empty, trainingLine(null, 100.0, isToday = true))
        assertEquals(R.string.metrics_training_building, trainingLine(40.0, null, isToday = true))
        assertEquals(R.string.metrics_training_lighter_today, trainingLine(50.0, 100.0, isToday = true))
        assertEquals(R.string.metrics_training_typical_today, trainingLine(105.0, 100.0, isToday = true))
        assertEquals(R.string.metrics_training_bigger_today, trainingLine(150.0, 100.0, isToday = true))
        assertEquals(R.string.metrics_training_lighter, trainingLine(50.0, 100.0, isToday = false))
        assertEquals(R.string.metrics_training_typical, trainingLine(95.0, 100.0, isToday = false))
        assertEquals(R.string.metrics_training_bigger, trainingLine(150.0, 100.0, isToday = false))
    }

    @Test
    fun bodyLineSpeaksAboutSkinTemperatureFirst() {
        assertEquals(R.string.metrics_body_skin_up, bodyLine(BaselineSide.ABOVE, hasAnyValue = true))
        assertEquals(R.string.metrics_body_skin_down, bodyLine(BaselineSide.BELOW, hasAnyValue = true))
        assertEquals(R.string.metrics_body_skin_close, bodyLine(BaselineSide.CLOSE, hasAnyValue = true))
        assertEquals(R.string.metrics_body_latest, bodyLine(null, hasAnyValue = true))
        assertEquals(R.string.metrics_body_empty, bodyLine(null, hasAnyValue = false))
    }

    // MARK: - Readings and layout

    private fun row(day: String, hrv: Double? = null) = DailyMetric(deviceId = "my-whoop-noop", day = day, avgHrv = hrv)

    @Test
    fun firstReadingKeepsTheDayOfTheRowItCameFrom() {
        val reading = firstReading(null, row("2026-11-10"), row("2026-11-08", hrv = 55.0)) { it.avgHrv }
        assertEquals(DayReading(55.0, "2026-11-08"), reading)
        assertEquals(DayReading(61.0, "2026-11-10"), firstReading(row("2026-11-10", 61.0), row("2026-11-08", 55.0)) { it.avgHrv })
        assertNull(firstReading(null, row("2026-11-10")) { it.avgHrv })
    }

    @Test
    fun everyMetricBelongsToACategory() {
        assertEquals(MetricCategory.SLEEP, metricCategory(KeyMetric.ASLEEP))
        assertEquals(MetricCategory.SLEEP, metricCategory(KeyMetric.REST))
        assertEquals(MetricCategory.TRAINING, metricCategory(KeyMetric.EFFORT))
        assertEquals(MetricCategory.BODY, metricCategory(KeyMetric.SKIN_TEMP))
        assertEquals(MetricCategory.RECOVERY, metricCategory(KeyMetric.RESPIRATORY))
        // No category card can be left without a metric to show.
        assertEquals(MetricCategory.entries.toSet(), KeyMetric.entries.map { metricCategory(it) }.toSet())
    }

    @Test
    fun asleepIsAStableTokenThatFollowsRestByDefault() {
        assertEquals(KeyMetric.ASLEEP, KeyMetric.fromRaw("asleep"))
        val order = KeyMetric.defaultOrder
        assertEquals(order.indexOf(KeyMetric.REST) + 1, order.indexOf(KeyMetric.ASLEEP))
        assertTrue(KeyMetric.ASLEEP in KeyMetricPrefs.decodeEnabled(""))
        // A saved layout is kept as saved; Asleep joins it only from the editor.
        assertFalse(KeyMetric.ASLEEP in KeyMetricPrefs.decodeEnabled("charge,effort,rest"))
    }

    // MARK: - Tiles

    private fun tile(
        raw: Double?,
        baseline: Double?,
        rule: TrendRule = TrendRule.HIGHER_IS_BETTER,
        referenceIsNormal: Boolean = false,
        label: String = "HRV",
    ) = MetricTileUi(
        id = "hrv",
        label = label,
        value = raw?.toString(),
        unit = "ms",
        valueColor = Color.White,
        lineColor = Color.White,
        raw = raw,
        insight = MetricInsight(baseline, null, null, null, emptyList()),
        rule = rule,
        format = { it.toString() },
        referenceIsNormal = referenceIsNormal,
    )

    @Test
    fun aDeviationComparesWithNormalRatherThanItsBaseline() {
        val skin = tile(raw = 0.7, baseline = 0.6, rule = TrendRule.STEADY_IS_BEST, referenceIsNormal = true)
        assertEquals(0.0, skin.reference!!, 0.0)
        assertEquals(HomeTone.POOR, skin.tone)
        assertEquals(BaselineSide.ABOVE, skin.side)
    }

    @Test
    fun aTileWithoutANumberHasNothingToOpenOrCompare() {
        val empty = tile(raw = null, baseline = 60.0)
        assertFalse(empty.canExpand)
        assertNull(empty.tone)
        assertNull(empty.side)
    }

    // MARK: - Imported histories

    private fun apple(day: String, steps: Int? = null, weightKg: Double? = null) =
        AppleDaily(deviceId = "apple-health", day = day, steps = steps, weightKg = weightKg)

    private fun healthConnect(day: String, steps: Int? = null, weightKg: Double? = null) =
        AppleDaily(deviceId = "health-connect", day = day, steps = steps, weightKg = weightKg)

    @Test
    fun importedStepsKeepTheLargerTotalForADay() {
        val history = importedStepsHistory(
            listOf(apple("2026-11-01", steps = 8_000), apple("2026-11-02")),
            listOf(healthConnect("2026-11-01", steps = 9_500), healthConnect("2026-11-03", steps = 4_000)),
        )
        assertEquals(mapOf("2026-11-01" to 9_500.0, "2026-11-03" to 4_000.0), history)
    }

    @Test
    fun weightHistoryCanReadHealthConnectAlone() {
        val apple = listOf(apple("2026-11-01", weightKg = 80.0))
        val healthConnect = listOf(healthConnect("2026-11-02", weightKg = 79.5))
        assertEquals(mapOf("2026-11-01" to 80.0, "2026-11-02" to 79.5), weightHistoryKg(apple, healthConnect))
        assertEquals(mapOf("2026-11-02" to 79.5), weightHistoryKg(apple, healthConnect, healthConnectOnly = true))
    }
}
