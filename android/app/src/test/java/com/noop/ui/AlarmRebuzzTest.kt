package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "buzz again after 5 min" fire sequence ([AlarmRebuzz]), without the BLE stack. */
class AlarmRebuzzTest {

    private val t0 = 1_800_000_000L // the first buzz, epoch seconds

    @Test fun aFirstBuzzSchedulesTheSecondFiveMinutesLater() {
        assertEquals(t0 + 300, AlarmRebuzz.pendingAfterFire(nowSec = t0, pendingSec = 0L, enabled = true))
    }

    @Test fun offSchedulesNothingAndDropsAnythingPending() {
        assertEquals(0L, AlarmRebuzz.pendingAfterFire(nowSec = t0, pendingSec = 0L, enabled = false))
        assertEquals(0L, AlarmRebuzz.pendingAfterFire(nowSec = t0, pendingSec = t0 + 300, enabled = false))
    }

    /** A repeated report of the first buzz must not push the second one later. */
    @Test fun aDuplicateReportOfTheFirstBuzzKeepsThePendingOne() {
        assertEquals(t0 + 300, AlarmRebuzz.pendingAfterFire(nowSec = t0 + 20, pendingSec = t0 + 300, enabled = true))
    }

    /** The second buzz clears the pending one and never chains a third. */
    @Test fun theSecondBuzzClearsAndDoesNotChain() {
        assertEquals(0L, AlarmRebuzz.pendingAfterFire(nowSec = t0 + 300, pendingSec = t0 + 300, enabled = true))
        assertEquals(0L, AlarmRebuzz.pendingAfterFire(nowSec = t0 + 240, pendingSec = t0 + 300, enabled = true))
        assertEquals(0L, AlarmRebuzz.pendingAfterFire(nowSec = t0 + 600, pendingSec = t0 + 300, enabled = true))
    }

    /** A second buzz missed yesterday (phone away) must not swallow today's first buzz. */
    @Test fun aStalePendingOneFromAnEarlierMorningCountsAsAFreshFirstBuzz() {
        val tomorrow = t0 + 24 * 3600
        assertEquals(tomorrow + 300, AlarmRebuzz.pendingAfterFire(nowSec = tomorrow, pendingSec = t0 + 300, enabled = true))
    }

    @Test fun theSlotHoldsThePendingSecondBuzzWhileItIsAhead() {
        val nextMorning = t0 + 24 * 3600
        assertEquals(t0 + 300, AlarmRebuzz.strapTarget(scheduledSec = nextMorning, pendingSec = t0 + 300, nowSec = t0 + 10))
        assertEquals(t0 + 300, AlarmRebuzz.strapTarget(scheduledSec = null, pendingSec = t0 + 300, nowSec = t0 + 10))
    }

    @Test fun onceThePendingOneHasPassedTheSlotGoesBackToTheSchedule() {
        val nextMorning = t0 + 24 * 3600
        assertEquals(nextMorning, AlarmRebuzz.strapTarget(scheduledSec = nextMorning, pendingSec = t0 + 300, nowSec = t0 + 400))
        assertEquals(nextMorning, AlarmRebuzz.strapTarget(scheduledSec = nextMorning, pendingSec = 0L, nowSec = t0))
        assertNull(AlarmRebuzz.strapTarget(scheduledSec = null, pendingSec = 0L, nowSec = t0))
    }
}
