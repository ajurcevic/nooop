package com.noop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the version comparison that drives "Check for updates". The headline case is the string-compare
 * trap: "1.40" must be NEWER than "1.39" (lexicographically it isn't), and "1.9" must be OLDER than
 * "1.10". Also covers the demo flavour's "-demo" suffix and a leading "v".
 */
class UpdateCheckTest {

    @Test
    fun newer() {
        assertTrue(UpdateCheck.isNewer("1.40", "1.39"))   // the trap: "1.40" < "1.39" as strings
        assertTrue(UpdateCheck.isNewer("1.10", "1.9"))    // and "1.10" < "1.9" as strings
        assertTrue(UpdateCheck.isNewer("2.0", "1.39"))
        assertTrue(UpdateCheck.isNewer("1.39.1", "1.39")) // extra patch segment
        assertTrue(UpdateCheck.isNewer("v1.40", "1.39"))  // tolerant of the tag's "v"
    }

    @Test
    fun notNewer() {
        assertFalse(UpdateCheck.isNewer("1.39", "1.39"))      // equal
        assertFalse(UpdateCheck.isNewer("1.38", "1.39"))      // older
        assertFalse(UpdateCheck.isNewer("1.9", "1.10"))
        assertFalse(UpdateCheck.isNewer("1.39-demo", "1.39")) // demo flavour vs the same release
        assertFalse(UpdateCheck.isNewer("garbage", "1.39"))   // unparseable → not newer (no false alarm)
    }

    @Test
    fun picksTheReleaseApk() {
        val assets = listOf(
            UpdateCheck.Asset("checksums.txt", "https://github.com/ajurcevic/nooop/releases/download/v12.2.2/checksums.txt"),
            UpdateCheck.Asset("NOOP-android-v12.2.2.apk", "https://github.com/ajurcevic/nooop/releases/download/v12.2.2/NOOP-android-v12.2.2.apk"),
        )
        assertEquals(
            "https://github.com/ajurcevic/nooop/releases/download/v12.2.2/NOOP-android-v12.2.2.apk",
            UpdateCheck.pickApkUrl(assets),
        )
    }

    @Test
    fun noApkMeansNoDownload() {
        assertNull(UpdateCheck.pickApkUrl(emptyList()))   // release created, APK not uploaded yet
        assertNull(UpdateCheck.pickApkUrl(listOf(UpdateCheck.Asset("notes.md", "https://example.com/notes.md"))))
        // A cleartext URL is never offered as a download.
        assertNull(UpdateCheck.pickApkUrl(listOf(UpdateCheck.Asset("NOOP.apk", "http://example.com/NOOP.apk"))))
    }

    @Test
    fun apkFileNameIgnoresHostileVersionText() {
        assertEquals("NOOP-12.2.3.apk", ApkUpdate.fileNameFor("12.2.3"))
        assertEquals("NOOP-12.2.3-staging.apk", ApkUpdate.fileNameFor("12.2.3-staging"))
        assertEquals("NOOP-....x.apk", ApkUpdate.fileNameFor("../../x")) // no path separators survive
        assertEquals("NOOP-latest.apk", ApkUpdate.fileNameFor("/"))
    }
}
