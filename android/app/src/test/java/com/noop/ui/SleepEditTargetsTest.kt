package com.noop.ui

import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which sessions a sleep time edit rewrites ([sleepEditTargets]): an edit from the Last night card
 * applies to the main night's whole bridged group (#1492), and an edit from a Naps card row to that nap
 * alone. Planning a nap's window against the main night's group either dropped the edit (no overlap) or
 * clipped the main night to the nap's times.
 */
class SleepEditTargetsTest {

    private fun block(start: Long, end: Long) = SleepSession(deviceId = "my-whoop", startTs = start, endTs = end)

    private val firstHalf = block(1_000L, 10_000L)
    private val main = block(11_000L, 30_000L)
    private val nap = block(50_000L, 52_000L)

    private fun night(
        group: List<SleepSession>,
        anchor: SleepSession = main,
        naps: List<SleepSession> = listOf(nap),
    ) = HeroNight(
        session = anchor,
        dayKey = "2026-11-20",
        realSegments = null,
        clockLabel = "",
        napBlocks = naps,
        heroGroup = group,
    )

    @Test
    fun theLastNightCardEditsTheWholeGroup() {
        val night = night(listOf(firstHalf, main))
        assertEquals(listOf(firstHalf, main), sleepEditTargets(night, main, fromNapRow = false))
        assertEquals(listOf(firstHalf, main), sleepEditTargets(night, firstHalf, fromNapRow = false))
        val editedCopy = main.copy(userEdited = true, startTsAdjusted = 12_000L)
        assertEquals(listOf(firstHalf, main), sleepEditTargets(night, editedCopy, fromNapRow = false))
    }

    @Test
    fun aNapRowEditsItsNapAlone() {
        assertEquals(listOf(nap), sleepEditTargets(night(listOf(firstHalf, main)), nap, fromNapRow = true))
    }

    /**
     * The single-block anchor can fall outside the bridged group, and it is then also a Naps card row. The
     * session cannot say which card it was edited from, so the caller does: the row edits it alone, the
     * Last night card edits the group the card shows.
     */
    @Test
    fun anAnchorOutsideTheGroupFollowsTheCardItWasEditedFrom() {
        val anchor = block(40_000L, 46_000L)
        val night = night(listOf(firstHalf, main), anchor = anchor, naps = listOf(anchor, nap))
        assertEquals(listOf(anchor), sleepEditTargets(night, anchor, fromNapRow = true))
        assertEquals(listOf(firstHalf, main), sleepEditTargets(night, anchor, fromNapRow = false))
    }

    @Test
    fun withoutAGroupTheEditedSessionStandsAlone() {
        assertEquals(listOf(main), sleepEditTargets(night(emptyList()), main, fromNapRow = false))
        assertEquals(listOf(main), sleepEditTargets(null, main, fromNapRow = false))
        assertEquals(listOf(nap), sleepEditTargets(null, nap, fromNapRow = true))
    }

    /**
     * A Naps card row only adjusts its nap ([napRowWindowApplies]). The row has no "Move this sleep?" confirm,
     * and its time-only pickers can put a window on the wrong day: a start moved back past midnight keeps the
     * nap's own date, so the end rolls to the next day. A window clear of the nap is refused, not applied.
     */
    @Test
    fun aNapRowWindowMustOverlapItsNap() {
        val min = 60L
        val t = 1_780_000_000L
        val afterMidnight = block(t, t + 150 * min)   // read as 00:30 to 03:00
        assertTrue(napRowWindowApplies(afterMidnight, t - 45 * min, t + 150 * min))
        assertTrue(napRowWindowApplies(afterMidnight, t + 30 * min, t + 60 * min))
        // The picker's accident: 23:45 on the nap's own day, then 03:00 the day after.
        assertFalse(napRowWindowApplies(afterMidnight, t + 1_395 * min, t + 1_590 * min))
        // Touching the nap's end is not overlapping it.
        assertFalse(napRowWindowApplies(afterMidnight, t + 150 * min, t + 240 * min))
        // An edited nap keeps its recorded span: a window back over the detected onset still applies.
        val trimmed = afterMidnight.copy(userEdited = true, startTsAdjusted = t + 60 * min)
        assertTrue(napRowWindowApplies(trimmed, t, t + 30 * min))
    }
}
