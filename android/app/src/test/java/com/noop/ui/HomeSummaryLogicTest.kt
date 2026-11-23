package com.noop.ui

import com.noop.data.DailyMetric
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSummaryLogicTest {

    @Test
    fun averageExcludesTheDayItselfAndDaysOutsideTheWindow() {
        val values = mapOf(
            "2026-11-01" to 100.0, // eight days back, outside the window
            "2026-11-03" to 40.0,
            "2026-11-05" to 50.0,
            "2026-11-08" to 60.0,
            "2026-11-09" to 999.0, // the day itself
        )
        assertEquals(50.0, trailingAverage(values, "2026-11-09")!!, 1e-9)
    }

    @Test
    fun averageNeedsThreeDays() {
        val values = mapOf("2026-11-07" to 40.0, "2026-11-08" to 60.0)
        assertNull(trailingAverage(values, "2026-11-09"))
    }

    @Test
    fun averageOfAnUnparseableDayIsNull() {
        assertNull(trailingAverage(mapOf("2026-11-08" to 1.0), "today"))
    }

    @Test
    fun seriesSkipsEmptyFields() {
        val rows = listOf(
            DailyMetric(deviceId = "my-whoop", day = "2026-11-07", recovery = 55.0),
            DailyMetric(deviceId = "my-whoop", day = "2026-11-08"),
        )
        assertEquals(mapOf("2026-11-07" to 55.0), rows.seriesOf { it.recovery })
    }

    @Test
    fun signedDeltaWholeNumbers() {
        assertEquals("+4", signedDelta(3.6))
        assertEquals("-2", signedDelta(-2.4))
        assertEquals("0", signedDelta(0.3))
        assertEquals("0", signedDelta(-0.4))
    }

    @Test
    fun signedDeltaOneDecimal() {
        assertEquals("+0.6", signedDelta(0.58, decimals = 1, locale = Locale.UK))
        assertEquals("-1.2", signedDelta(-1.24, decimals = 1, locale = Locale.UK))
        assertEquals("0.0", signedDelta(-0.04, decimals = 1, locale = Locale.UK))
        assertEquals("+0,6", signedDelta(0.58, decimals = 1, locale = Locale.GERMANY))
    }
}

class HomeToneTest {

    @Test
    fun restBandsSplitAtFiftyAndSeventy() {
        assertEquals(HomeTone.GOOD, restTone(70.0))
        assertEquals(HomeTone.FAIR, restTone(69.9))
        assertEquals(HomeTone.FAIR, restTone(50.0))
        assertEquals(HomeTone.POOR, restTone(49.9))
    }

    @Test
    fun higherHrvIsGoodAndLowerIsPoor() {
        assertEquals(HomeTone.GOOD, vitalTrendTone(60.0, 50.0, higherIsBetter = true))
        assertEquals(HomeTone.POOR, vitalTrendTone(40.0, 50.0, higherIsBetter = true))
    }

    @Test
    fun lowerRestingHrIsGoodAndHigherIsPoor() {
        assertEquals(HomeTone.GOOD, vitalTrendTone(52.0, 58.0, higherIsBetter = false))
        assertEquals(HomeTone.POOR, vitalTrendTone(64.0, 58.0, higherIsBetter = false))
    }

    @Test
    fun withinFivePercentReadsAsFairEitherWay() {
        assertEquals(HomeTone.FAIR, vitalTrendTone(52.4, 50.0, higherIsBetter = true))
        assertEquals(HomeTone.FAIR, vitalTrendTone(47.6, 50.0, higherIsBetter = false))
        assertEquals(HomeTone.GOOD, vitalTrendTone(47.0, 50.0, higherIsBetter = false))
    }

    @Test
    fun missingSidesOrZeroAverageGiveNoTone() {
        assertNull(vitalTrendTone(null, 50.0, higherIsBetter = true))
        assertNull(vitalTrendTone(50.0, null, higherIsBetter = true))
        assertNull(vitalTrendTone(50.0, 0.0, higherIsBetter = true))
    }

    @Test
    fun homeChipToneIsGreenForBetterAmberForWorseAndNeutralOtherwise() {
        assertEquals(HomeTone.GOOD, homeChipTone(HomeTone.GOOD))
        assertEquals(HomeTone.FAIR, homeChipTone(HomeTone.POOR))
        assertNull(homeChipTone(HomeTone.FAIR))
        assertNull(homeChipTone(null))
    }
}
