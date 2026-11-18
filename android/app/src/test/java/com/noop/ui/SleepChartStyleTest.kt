package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins how stored sleep-chart preferences resolve after the Garmin-coloured fill was removed. */
class SleepChartStyleTest {

    @Test
    fun retiredGarminFillFallsBackToTheFilledChart() {
        assertEquals(SleepChartStyle.FILLED, SleepChartStyle.fromRaw("garminFilled"))
    }

    @Test
    fun remainingStylesRoundTripThroughTheirRawValue() {
        for (style in SleepChartStyle.entries) {
            assertEquals(style, SleepChartStyle.fromRaw(style.raw))
        }
    }

    @Test
    fun unsetOrUnknownResolvesToClassic() {
        assertEquals(SleepChartStyle.CLASSIC, SleepChartStyle.fromRaw(null))
        assertEquals(SleepChartStyle.CLASSIC, SleepChartStyle.fromRaw("nope"))
    }

    @Test
    fun settingsOffersThreeStyles() {
        assertEquals(
            listOf(SleepChartStyle.CLASSIC, SleepChartStyle.FILLED, SleepChartStyle.RIBBON),
            SleepChartStyle.entries.toList(),
        )
    }
}
