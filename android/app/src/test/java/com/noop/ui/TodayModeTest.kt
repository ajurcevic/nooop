package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home draws the score hero and the heart-rate chart itself; Metrics must offer every other section. */
class TodayModeTest {

    @Test
    fun homeOwnsOnlyTheHeroAndTheHeartRateChart() {
        assertEquals(setOf(TodaySection.HERO, TodaySection.HEART_RATE), TodaySection.homeOwned)
    }

    @Test
    fun everyOtherSectionStaysAvailableOnMetrics() {
        val onMetrics = TodaySection.defaultOrder.filterNot { it in TodaySection.homeOwned }
        assertEquals(TodaySection.entries.size - TodaySection.homeOwned.size, onMetrics.size)
        assertTrue(TodaySection.ADDED_CARDS in onMetrics)
    }

    /** Saving the Metrics editor drops the Home-owned sections; decoding must put them back. */
    @Test
    fun anOrderSavedWithoutHomeSectionsStillDecodesToEverySection() {
        val saved = TodayLayoutPrefs.encode(TodaySection.defaultOrder.filterNot { it in TodaySection.homeOwned })
        assertEquals(TodaySection.entries.toSet(), TodayLayoutPrefs.decodeOrder(saved).toSet())
    }
}
