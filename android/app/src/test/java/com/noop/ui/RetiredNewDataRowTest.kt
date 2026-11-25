package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "New data added" inbox row was removed. Rows older builds already posted are dropped when the
 * inbox loads, through [UpdateStore.isRetiredNewDataRow]. This pins that the rule matches only those
 * rows and leaves every other inbox entry in place.
 */
class RetiredNewDataRowTest {

    private fun item(kind: UpdateKind, deepLink: String? = null) =
        UpdateItem(kind = kind, title = "t", message = "m", deepLink = deepLink)

    @Test
    fun anOldNewDataRowIsDropped() {
        val old = UpdateItem(
            kind = UpdateKind.READING,
            title = "New data added",
            message = "1 new day of history is ready in Trends.",
            deepLink = "trends",
        )
        assertTrue(UpdateStore.isRetiredNewDataRow(old))
    }

    @Test
    fun otherInboxRowsAreKept() {
        assertFalse(UpdateStore.isRetiredNewDataRow(item(UpdateKind.WHATS_NEW, UpdateStore.WHATS_NEW_DEEP_LINK)))
        assertFalse(UpdateStore.isRetiredNewDataRow(item(UpdateKind.WHATS_NEW)))
        assertFalse(UpdateStore.isRetiredNewDataRow(item(UpdateKind.NEW_VERSION)))
        assertFalse(UpdateStore.isRetiredNewDataRow(item(UpdateKind.STRAP_ALERT)))
        assertFalse(UpdateStore.isRetiredNewDataRow(item(UpdateKind.DISMISSED_CARD)))
        assertFalse(UpdateStore.isRetiredNewDataRow(item(UpdateKind.READING)))
    }
}
