package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Pins how a tap on a Metrics card's 30-day chart picks a point and reads it out. */
class MetricsChartPickTest {

    private val points = listOf(0 to 50.0, 10 to 55.0, 20 to 60.0, 30 to 58.0)

    @Test
    fun picksTheNearestPointByCalendarPosition() {
        // 300 px wide over 30 days: 10 px per day.
        assertEquals(0, nearestPointIndex(points, 30, 300f, 0f))
        assertEquals(1, nearestPointIndex(points, 30, 300f, 140f))   // day 14 is nearer day 10
        assertEquals(2, nearestPointIndex(points, 30, 300f, 160f))   // day 16 is nearer day 20
        assertEquals(3, nearestPointIndex(points, 30, 300f, 300f))
    }

    @Test
    fun aGapInDaysDoesNotSqueezeThePick() {
        val sparse = listOf(0 to 1.0, 29 to 2.0, 30 to 3.0)
        // A tap a third of the way across is day 10, still nearest the first point.
        assertEquals(0, nearestPointIndex(sparse, 30, 300f, 100f))
    }

    @Test
    fun emptyOrZeroWidthFallsBackToTheFirstIndex() {
        assertEquals(0, nearestPointIndex(emptyList(), 30, 300f, 50f))
        assertEquals(0, nearestPointIndex(points, 30, 0f, 50f))
    }

    @Test
    fun labelNamesTheDateAndValue() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.UK)
        try {
            val label = pickedPointLabel(LocalDate.of(2026, 10, 19), 10 to 54.0, { it.toInt().toString() }, "ms")
            assertEquals("29 Oct · 54 ms", label)
            // A unitless value has no trailing space; no window start drops the date.
            assertEquals("54", pickedPointLabel(null, 10 to 54.0, { it.toInt().toString() }, ""))
        } finally {
            Locale.setDefault(default)
        }
    }
}
