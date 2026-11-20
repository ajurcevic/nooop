package com.noop.ui

import com.noop.data.JournalEntry

// MARK: - Journal helpers (read-only: the app no longer logs journal answers)

/** Answers logged in-app were written under this dedicated source id, NEVER under "my-whoop": journal's
 *  PK is (deviceId, day, question) and has no source column, so a CSV re-import would silently
 *  overwrite in-app answers. Insights still reads these historical rows. */
const val JOURNAL_DEVICE_ID = "noop-journal"

/** Union of imported + native entries; on a (day, question) collision the NATIVE row wins (it was the
 *  user's most recent explicit answer). */
internal fun mergeJournalEntries(
    imported: List<JournalEntry>,
    native: List<JournalEntry>,
): List<JournalEntry> {
    val byKey = LinkedHashMap<Pair<String, String>, JournalEntry>()
    for (e in imported) byKey[e.day to e.question] = e
    for (e in native) byKey[e.day to e.question] = e
    return byKey.values.sortedWith(compareBy({ it.day }, { it.question }))
}

/** Insights caption naming the dates the (no longer growing) journal covers, so a reader can tell the
 *  behaviour effects come from past answers. Days are the stored `yyyy-MM-dd` keys; null when there are
 *  no entries or a day key does not parse. */
internal fun journalCoverageCaption(entries: List<JournalEntry>): String? {
    if (entries.isEmpty()) return null
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US)
    return try {
        val days = entries.map { java.time.LocalDate.parse(it.day) }
        val first = days.min().format(fmt)
        val last = days.max().format(fmt)
        if (first == last) "Based on journal answers from $last."
        else "Based on journal answers from $first to $last."
    } catch (_: java.time.format.DateTimeParseException) {
        null
    }
}
