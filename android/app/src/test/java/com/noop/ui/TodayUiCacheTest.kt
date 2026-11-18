package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the day rule behind the Today tab-switch cache: a stored value comes back only for the day it
 * was loaded for, so returning to Today shows the last-known reading without ever painting another
 * day's number.
 */
class TodayUiCacheTest {

    @Test
    fun returnsTheValueForTheSameDay() {
        val cache = TodayUiCache()
        cache.put("today.restScoreForDay", "2026-11-18", 82.0)
        assertTrue(cache.has("today.restScoreForDay", "2026-11-18"))
        assertEquals(82.0, cache.get("today.restScoreForDay", "2026-11-18"))
    }

    @Test
    fun neverHandsBackAnotherDaysValue() {
        val cache = TodayUiCache()
        cache.put("hrCard.buckets", "2026-11-17", listOf(1, 2, 3))
        assertFalse(cache.has("hrCard.buckets", "2026-11-18"))
        assertNull(cache.get("hrCard.buckets", "2026-11-18"))
    }

    @Test
    fun aStoredNullStillCountsAsLoaded() {
        val cache = TodayUiCache()
        cache.put("today.weightKg", "2026-11-18", null)
        assertTrue(cache.has("today.weightKg", "2026-11-18"))
        assertNull(cache.get("today.weightKg", "2026-11-18"))
    }

    @Test
    fun aNewDayReplacesTheOldEntry() {
        val cache = TodayUiCache()
        cache.put("today.restScoreForDay", "2026-11-17", 70.0)
        cache.put("today.restScoreForDay", "2026-11-18", 88.0)
        assertFalse(cache.has("today.restScoreForDay", "2026-11-17"))
        assertEquals(88.0, cache.get("today.restScoreForDay", "2026-11-18"))
    }

    @Test
    fun keysAreIndependent() {
        val cache = TodayUiCache()
        cache.put("today.a", "2026-11-18", 1)
        assertFalse(cache.has("today.b", "2026-11-18"))
        cache.clear()
        assertFalse(cache.has("today.a", "2026-11-18"))
    }
}
