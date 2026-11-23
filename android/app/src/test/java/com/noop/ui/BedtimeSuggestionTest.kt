package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "Bed by" arithmetic: wake time minus personal need minus time to fall asleep, across midnight;
 * and when that bedtime may be called tonight's, which is the only time the Sleep hero names it.
 */
class BedtimeSuggestionTest {

    private val minute = 60_000L
    private val hour = 60 * minute

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

    // bedtimeIsTonight only compares instants, so "now" is pinned to 0 and every case is an offset from it.
    // An 8 h need plus the default 15 min to fall asleep puts the bedtime 8 h 15 min before the wake window.

    @Test
    fun anAfternoonLookAtTomorrowMorningsAlarmIsTonight() {
        // 14:00, wake window opens 07:00 tomorrow (17 h away): bed by 22:45, 8 h 45 min from now.
        assertTrue(bedtimeIsTonight(0L, 17 * hour, 480.0))
    }

    @Test
    fun anAlarmDaysAwayIsNotTonight() {
        // Friday 18:00 with a weekday-only alarm: the next window is Monday 07:00, so its bedtime is Sunday's.
        assertFalse(bedtimeIsTonight(0L, (2 * 24 + 13) * hour, 480.0))
    }

    @Test
    fun theSmallHoursOfANightUnderWayAreNotTonight() {
        // 02:00 with the window at 07:00 the same morning: that bedtime (22:45) was 3 h 15 min ago.
        assertFalse(bedtimeIsTonight(0L, 5 * hour, 480.0))
    }

    @Test
    fun aBedtimeJustMissedStillReadsAsTonight() {
        // 23:30 with the window at 07:00: the 22:45 bedtime is 45 min behind, inside the late-night allowance.
        assertTrue(bedtimeIsTonight(0L, 7 * hour + 30 * minute, 480.0))
    }

    @Test
    fun bothEdgesOfTheTonightWindowAreInclusive() {
        val lead = (480 + BEDTIME_SLEEP_LATENCY_MIN) * minute // wake window start minus bedtime
        val ahead = BEDTIME_TONIGHT_MAX_AHEAD_MIN * minute
        val behind = BEDTIME_TONIGHT_MAX_BEHIND_MIN * minute
        assertTrue(bedtimeIsTonight(0L, ahead + lead, 480.0))
        assertFalse(bedtimeIsTonight(0L, ahead + lead + minute, 480.0))
        assertTrue(bedtimeIsTonight(0L, -behind + lead, 480.0))
        assertFalse(bedtimeIsTonight(0L, -behind + lead - minute, 480.0))
    }
}
