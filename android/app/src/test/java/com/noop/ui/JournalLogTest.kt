package com.noop.ui

import com.noop.data.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure journal merge helper (JournalLog.kt) Insights uses to read imported and
 * previously logged answers together. Question strings are opaque exact-match keys.
 */
class JournalLogTest {

    private fun e(dev: String, day: String, q: String, yes: Boolean) =
        JournalEntry(deviceId = dev, day = day, question = q, answeredYes = yes)

    @Test
    fun nativeWinsOnCollision() {
        val imported = listOf(e("my-whoop", "2026-06-09", "Did you drink any alcohol?", false))
        val native = listOf(e("noop-journal", "2026-06-09", "Did you drink any alcohol?", true))
        val merged = mergeJournalEntries(imported, native)
        assertEquals(1, merged.size)
        assertEquals(true, merged[0].answeredYes)
        assertEquals("noop-journal", merged[0].deviceId)
    }

    @Test
    fun disjointKeysUnionAndSort() {
        val imported = listOf(e("my-whoop", "2026-06-09", "B?", true))
        val native = listOf(
            e("noop-journal", "2026-06-10", "A?", false),
            e("noop-journal", "2026-06-09", "A?", true),
        )
        val merged = mergeJournalEntries(imported, native)
        assertEquals(3, merged.size)
        // Sorted day ASC then question ASC — matches the DAO/Swift read order.
        assertEquals(listOf("A?", "B?", "A?"), merged.map { it.question })
        assertEquals(listOf("2026-06-09", "2026-06-09", "2026-06-10"), merged.map { it.day })
    }

    @Test
    fun coverageCaptionNamesFirstAndLastDay() {
        val entries = listOf(
            e("my-whoop", "2026-03-12", "A?", true),
            e("noop-journal", "2025-01-03", "B?", false),
            e("my-whoop", "2025-11-30", "A?", false),
        )
        assertEquals(
            "Based on journal answers from 3 Jan 2025 to 12 Mar 2026.",
            journalCoverageCaption(entries),
        )
    }

    @Test
    fun coverageCaptionSingleDay() {
        val entries = listOf(e("my-whoop", "2026-03-12", "A?", true), e("my-whoop", "2026-03-12", "B?", false))
        assertEquals("Based on journal answers from 12 Mar 2026.", journalCoverageCaption(entries))
    }

    @Test
    fun coverageCaptionNullWhenEmptyOrUnparseable() {
        assertEquals(null, journalCoverageCaption(emptyList()))
        assertEquals(null, journalCoverageCaption(listOf(e("my-whoop", "not-a-day", "A?", true))))
    }
}
