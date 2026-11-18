package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsRestOutcomeTest {

    private val days = listOf(
        DailyMetric(deviceId = "my-whoop", day = "2026-11-16", efficiency = 91.0, recovery = 70.0),
        DailyMetric(deviceId = "my-whoop", day = "2026-11-17", efficiency = 88.0, recovery = 55.0),
    )

    @Test
    fun restUsesTheRestScoreNotSleepEfficiency() {
        val rest = mapOf("2026-11-16" to 64.0, "2026-11-17" to 79.0)
        assertEquals(rest, insightsOutcomeDays(InsightsOutcome.Sleep, days, rest))
    }

    @Test
    fun aNightWithNoRestScoreIsLeftOut() {
        val rest = mapOf("2026-11-17" to 79.0, "2026-11-01" to 50.0)
        assertEquals(mapOf("2026-11-17" to 79.0), insightsOutcomeDays(InsightsOutcome.Sleep, days, rest))
    }

    @Test
    fun otherOutcomesStillReadTheDailyRows() {
        assertEquals(
            mapOf("2026-11-16" to 70.0, "2026-11-17" to 55.0),
            insightsOutcomeDays(InsightsOutcome.Recovery, days, emptyMap()),
        )
    }
}
