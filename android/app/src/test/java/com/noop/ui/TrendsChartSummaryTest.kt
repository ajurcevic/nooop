package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the numbers a Trends chart reads to TalkBack: latest, lowest and highest. */
class TrendsChartSummaryTest {

    @Test
    fun latestIsTheLastValueNotTheHighest() {
        assertEquals(Triple(61.0, 40.0, 85.0), trendSeriesSummary(listOf(50.0, 85.0, 40.0, 61.0)))
    }

    @Test
    fun nonFiniteValuesAreIgnored() {
        assertEquals(Triple(70.0, 60.0, 70.0), trendSeriesSummary(listOf(60.0, Double.NaN, 70.0, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun nothingToDescribeIsNull() {
        assertNull(trendSeriesSummary(emptyList()))
        assertNull(trendSeriesSummary(listOf(Double.NaN)))
    }
}
