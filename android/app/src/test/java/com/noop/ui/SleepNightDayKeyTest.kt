package com.noop.ui

import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Pins which [DailyMetric.day] row a browsed night reads its figures from ([nightDayKey], through
 * [selectNight]). Rows are keyed by the LOCAL day the night wakes on (#277). East of UTC an early wake is
 * still the previous day in UTC (Perth before 08:00), and west of UTC a late wake is already the next one,
 * so trying the UTC day first landed a night on its neighbour's row and the Last night card showed that
 * neighbour's Rest and sleep debt. Fixtures are built in an explicit zone so they do not depend on the
 * default zone the code under test reads.
 */
class SleepNightDayKeyTest {

    private val saved: TimeZone = TimeZone.getDefault()

    @Before fun perth() { TimeZone.setDefault(TimeZone.getTimeZone(PERTH)) }

    @After fun restore() { TimeZone.setDefault(saved) }

    /** Unix seconds for a wall-clock time in [zone]. */
    private fun at(day: String, time: String, zone: String): Long =
        LocalDateTime.parse("${day}T$time").atZone(ZoneId.of(zone)).toEpochSecond()

    /** An eight-hour sleep ending at [wake] on [wakeDay] in [zone]. */
    private fun sleep(wakeDay: String, wake: String = "06:30:00", zone: String = PERTH): SleepSession {
        val end = at(wakeDay, wake, zone)
        return SleepSession(
            deviceId = "my-whoop",
            startTs = end - 8 * 3_600,
            endTs = end,
            stagesJSON = """{"awake":30,"light":250,"deep":80,"rem":90}""",
        )
    }

    private fun row(day: String, stages: Boolean = true) = DailyMetric(
        deviceId = "my-whoop", day = day,
        totalSleepMin = if (stages) 420.0 else null,
        deepMin = if (stages) 80.0 else null,
        remMin = if (stages) 90.0 else null,
        lightMin = if (stages) 250.0 else null,
    )

    /** Two nights waking at 06:30 Perth time (22:30 UTC the day before), newest first, one stop per
     *  local wake day as the Sleep screen groups them. */
    private fun perthNights(): List<List<SleepSession>> =
        listOf(listOf(sleep("2026-11-20")), listOf(sleep("2026-11-19")))

    @Test
    fun earlyWakeEastOfUtc_readsItsOwnLocalRow() {
        val days = listOf(row("2026-11-19"), row("2026-11-20"))
        assertEquals("2026-11-20", selectNight(perthNights(), days, 0)!!.dayKey)
        assertEquals("2026-11-19", selectNight(perthNights(), days, 1)!!.dayKey)
    }

    @Test
    fun unscoredNewestNight_neverBorrowsThePreviousNightsRow() {
        // Last night's row holds no stages yet, or is not written at all; the night before is scored.
        val noStages = listOf(row("2026-11-19"), row("2026-11-20", stages = false))
        assertEquals("2026-11-20", selectNight(perthNights(), noStages, 0)!!.dayKey)
        assertEquals("2026-11-20", selectNight(perthNights(), listOf(row("2026-11-19")), 0)!!.dayKey)
    }

    @Test
    fun legacyUtcKeyedRow_isStillFoundWhenNoOtherNightWakesOnThatDay() {
        // A row written before 2.11.1 under the UTC wake day, and no night waking on that local day.
        val onlyNight = listOf(listOf(sleep("2026-11-20")))
        assertEquals("2026-11-19", selectNight(onlyNight, listOf(row("2026-11-19")), 0)!!.dayKey)
    }

    @Test
    fun noRowAtAll_isTheLocalWakeDay() {
        assertEquals("2026-11-20", selectNight(perthNights(), emptyList(), 0)!!.dayKey)
    }

    @Test
    fun lateWakeWestOfUtc_readsItsOwnLocalRow() {
        TimeZone.setDefault(TimeZone.getTimeZone(LOS_ANGELES))
        // Day sleeps ending at 17:30 Pacific time, which is 01:30 UTC the next day.
        val nights = listOf(
            listOf(sleep("2026-11-20", "17:30:00", LOS_ANGELES)),
            listOf(sleep("2026-11-19", "17:30:00", LOS_ANGELES)),
        )
        val days = listOf(row("2026-11-19"), row("2026-11-20"))
        assertEquals("2026-11-20", selectNight(nights, days, 0)!!.dayKey)
        assertEquals("2026-11-19", selectNight(nights, days, 1)!!.dayKey)
    }

    @Test
    fun utcZone_isUnchanged() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val night = sleep("2026-11-20", zone = "UTC")
        assertEquals("2026-11-20", nightDayKey(night, listOf(listOf(night)), listOf(row("2026-11-20"))))
        assertEquals("2026-11-20", nightDayKey(night, listOf(listOf(night)), emptyList()))
    }

    private companion object {
        const val PERTH = "Australia/Perth"
        const val LOS_ANGELES = "America/Los_Angeles"
    }
}
