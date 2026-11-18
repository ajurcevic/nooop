package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the "Bed by" arithmetic: wake time minus personal need minus time to fall asleep, across midnight. */
class BedtimeSuggestionTest {

    @Test
    fun eightHoursBeforeAnEarlyMorningWakeLandsTheEveningBefore() {
        // 06:30 wake, 8 h need, 15 min to fall asleep → 22:15.
        assertEquals(22 * 60 + 15, suggestedBedtimeMinutes(6 * 60 + 30, 480.0))
    }

    @Test
    fun aLateWakeStaysOnTheSameDay() {
        // 13:00 wake (a night-shift sleeper), 8 h 30 min need → 04:15.
        assertEquals(4 * 60 + 15, suggestedBedtimeMinutes(13 * 60, 510.0))
    }

    @Test
    fun theNeedIsRoundedToTheNearestMinute() {
        // 07:00 wake, 8 h 0.6 min need rounds to 481 min → 22:44.
        assertEquals(22 * 60 + 44, suggestedBedtimeMinutes(7 * 60, 480.6))
    }

    @Test
    fun latencyCanBeZero() {
        assertEquals(23 * 60, suggestedBedtimeMinutes(7 * 60, 480.0, latencyMin = 0))
    }
}
