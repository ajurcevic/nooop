package com.noop.ui

import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which sessions a sleep time edit rewrites ([sleepEditTargets]): the main night as its whole bridged
 * group (#1492), and a nap from the Naps card on its own. Planning a nap's window against the main night's
 * group either dropped the edit (no overlap) or clipped the main night to the nap's times.
 */
class SleepEditTargetsTest {

    private fun block(start: Long, end: Long) = SleepSession(deviceId = "my-whoop", startTs = start, endTs = end)

    private val firstHalf = block(1_000L, 10_000L)
    private val main = block(11_000L, 30_000L)
    private val nap = block(50_000L, 52_000L)

    private fun night(group: List<SleepSession>) = HeroNight(
        session = main,
        dayKey = "2026-11-20",
        realSegments = null,
        clockLabel = "",
        napBlocks = listOf(nap),
        heroGroup = group,
    )

    @Test
    fun theMainNightIsEditedAsItsWholeGroup() {
        val night = night(listOf(firstHalf, main))
        assertEquals(listOf(firstHalf, main), sleepEditTargets(night, main))
        assertEquals(listOf(firstHalf, main), sleepEditTargets(night, firstHalf))
        // Matched by the immutable detected key, so an already-edited copy of the anchor still counts.
        val editedCopy = main.copy(userEdited = true, startTsAdjusted = 12_000L)
        assertEquals(listOf(firstHalf, main), sleepEditTargets(night, editedCopy))
    }

    @Test
    fun aNapIsEditedAlone() {
        assertEquals(listOf(nap), sleepEditTargets(night(listOf(firstHalf, main)), nap))
    }

    @Test
    fun withoutAGroupTheEditedSessionStandsAlone() {
        assertEquals(listOf(main), sleepEditTargets(night(emptyList()), main))
        assertEquals(listOf(nap), sleepEditTargets(null, nap))
    }
}
