package com.noop.ui

import com.noop.alarm.SmartAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The Alarms countdown ("in 17 hours 47 minutes", with the date beside it), plus the phone scheduler's
 * deadline resolver that the Sleep tab's bedtime line still reads.
 *
 * The invariant: the readout resolves through the SAME pure function the strap slot is armed from, takes
 * the per-day times, and is not shown at all when nothing will fire. A countdown is a promise.
 */
class AlarmCountdownTest {

    /**
     * An override day counts down to ITS deadline, not the default one.
     *
     * This is the behaviour behind the display: a day whose wake was moved has a different deadline, and a
     * countdown resolved from the default would be wrong on exactly the days someone took the trouble to
     * change. `nextDeadline` is pure but for the clock, so it pins without an AlarmManager.
     */
    @Test fun anOverrideDayCountsDownToItsOwnDeadline() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        // Wednesday 2026-09-16, 09:00 UTC. Saturday only, base 06:30 + 30m window = 07:00,
        // but Saturday's wake is overridden to 20:00, so its deadline is 20:30.
        cal.set(2026, Calendar.SEPTEMBER, 16, 9, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = SmartAlarmScheduler.nextDeadline(
            now = cal,
            weekdays = setOf(Calendar.SATURDAY),
            windowMinutes = 30,
            targetForDay = { dow -> if (dow == Calendar.SATURDAY) 20 * 60 else 6 * 60 + 30 },
        )
        assertNotNull("a reachable Saturday must resolve", next)
        assertEquals(Calendar.SATURDAY, next!!.get(Calendar.DAY_OF_WEEK))
        assertEquals(20, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, next.get(Calendar.MINUTE))
    }

    /** Without an override the deadline is the default wake plus the window, which is what the card promises. */
    @Test fun anUntouchedDayUsesTheDefaultWakePlusTheWindow() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2026, Calendar.SEPTEMBER, 16, 9, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = SmartAlarmScheduler.nextDeadline(
            now = cal,
            weekdays = emptySet(),
            windowMinutes = 30,
            targetForDay = { 6 * 60 + 30 },
        )
        assertNotNull(next)
        assertEquals(7, next!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
    }

    /**
     * The Alarms countdown must come from the resolver the strap slot is armed from, take the per-day
     * times, and show nothing while the alarm is off.
     *
     * Source-asserted because a Composable reading a live clock has no unit seam, the same reason
     * `ChargingAndReleaseTest` reads source.
     */
    @Test fun theCountdownResolvesThroughTheStrapResolverAndIsGatedOnTheAlarmBeingOn() {
        val src = screenSource()
        val start = src.indexOf("private fun alarmCountdown(")
        if (start < 0) throw AssertionError("alarmCountdown not found in SmartAlarmScreen.kt")
        val body = src.substring(start, src.indexOf("\n}", start))
        assertTrue(
            "the countdown must use the same resolver reconcileStrapAlarm arms from:\n$body",
            body.contains("nextSmartAlarmEpochSec("),
        )
        assertTrue(
            "the per-day times must reach it, or a moved day counts to the wrong time",
            body.contains("dayOverrides = overrides"),
        )
        assertTrue("it must be gated on the alarm being on", src.contains("armed = enabled"))
        assertTrue(
            "an alarm that is off must produce no readout rather than a wrong one",
            body.contains("if (!armed) return null"),
        )
    }

    /**
     * The countdown has to tick. Computed once at composition it would freeze at whatever it read when the
     * screen opened, which is the single failure mode a countdown has.
     */
    @Test fun theCountdownIsDrivenByAClockRatherThanComposedOnce() {
        val src = screenSource()
        assertTrue(
            "the card needs a minute ticker feeding the countdown",
            src.contains("produceState(initialValue = System.currentTimeMillis())"),
        )
        assertTrue("the tick interval must be a minute", src.contains("delay(60_000L)"))
        assertTrue("and the countdown must read that tick", src.contains("nowMs = nowMs,"))
    }

    private fun screenSource(): String {
        var root = java.io.File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(4) {
            val f = java.io.File(root, "android/app/src/main/java/com/noop/ui/SmartAlarmScreen.kt")
            if (f.isFile) return f.readText()
            root = root.parentFile ?: root
        }
        throw IllegalStateException("SmartAlarmScreen.kt not found from ${System.getProperty("user.dir")}")
    }
}
