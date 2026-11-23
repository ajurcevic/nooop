package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Settings landing list: every page is reachable from it exactly once, the strap row's summary
 * reports only what it knows, Settings opens on the list rather than a page restored from storage, and the
 * strap experiments live on Advanced so the Strap page keeps to everyday controls. The source audits
 * follow the source-locating pattern used by [MoreNavigationContractTest].
 */
class SettingsLandingTest {

    @Test
    fun everyPageIsOnTheListOnce_withAdvancedApart() {
        assertEquals(SettingsPage.entries.toSet() - SettingsPage.ADVANCED, SETTINGS_LANDING_PAGES.toSet())
        assertEquals(SETTINGS_LANDING_PAGES.size, SETTINGS_LANDING_PAGES.toSet().size)
        assertEquals(SettingsPage.PROFILE, SETTINGS_LANDING_PAGES.first())
    }

    @Test
    fun strapSummary_namesTheStrapAndRoundsTheBatteryLikeTheStrapPage() {
        assertEquals("WHOOP 4.0, 64%", strapLandingSummary("WHOOP 4.0", 63.6))
        assertEquals("WHOOP 4.0, 63%", strapLandingSummary("WHOOP 4.0", 63.4))
        assertEquals("WHOOP 4.0", strapLandingSummary("WHOOP 4.0", null))
        assertEquals("WHOOP 4.0", strapLandingSummary("  WHOOP 4.0 ", null))
        assertEquals("50%", strapLandingSummary(null, 50.0))
        assertEquals("50%", strapLandingSummary("   ", 50.0))
    }

    /** The row shows a charge only from a live link to the active strap, never a stale or a ring's reading. */
    @Test
    fun strapBattery_onlyFromALiveLinkToTheActiveStrap() {
        assertEquals(64.0, settingsStrapBatteryPct(activeIsWhoop = true, connected = true, batteryPct = 64.0)!!, 0.0)
        assertNull(settingsStrapBatteryPct(activeIsWhoop = true, connected = false, batteryPct = 64.0))
        assertNull(settingsStrapBatteryPct(activeIsWhoop = false, connected = true, batteryPct = 64.0))
        assertNull(settingsStrapBatteryPct(activeIsWhoop = true, connected = true, batteryPct = null))
        // Disconnected: the row keeps the strap's name and drops the old reading.
        assertEquals("WHOOP 4.0", strapLandingSummary("WHOOP 4.0", settingsStrapBatteryPct(true, false, 64.0)))
    }

    /** The landing row and the Strap page's pill read one gated battery value, so they cannot disagree. */
    @Test
    fun strapBattery_oneGateForTheRowAndThePill() {
        val source = settingsSource()
        assertTrue(source.contains("val strapBatteryPct = settingsStrapBatteryPct(activeIsWhoop, live.connected, live.batteryPct)"))
        assertTrue(source.contains("strapSummary = strapLandingSummary(activeDeviceName, strapBatteryPct)"))
        val strapStart = source.indexOf("if (settingsPage == SettingsPage.STRAP) {")
        val strapEnd = source.indexOf("if (settingsPage == SettingsPage.HEALTH) {", strapStart)
        assertTrue("page blocks out of order", strapStart >= 0 && strapEnd > strapStart)
        assertTrue(source.substring(strapStart, strapEnd).contains("strapBatteryPct?.let { pct ->"))
        assertFalse("an ungated battery read is back", source.contains("live.batteryPct?.let"))
    }

    @Test
    fun strapSummary_isAbsentRatherThanInvented() {
        assertNull(strapLandingSummary(null, null))
        assertNull(strapLandingSummary("", null))
    }

    @Test
    fun strapSummary_neverJoinsWithAMiddleDot() {
        val summaries = listOfNotNull(
            strapLandingSummary("WHOOP 4.0", 80.0),
            strapLandingSummary("WHOOP 4.0", null),
            strapLandingSummary(null, 80.0),
        )
        for (summary in summaries) assertFalse(summary, summary.contains("·"))
    }

    /** The open page is saveable screen state that starts on the list, and Back on a page returns there. */
    @Test
    fun settingsOpensOnTheList() {
        val source = settingsSource()
        assertTrue(source.contains("var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }"))
        assertTrue(source.contains("BackHandler(enabled = settingsPage != null) { settingsPage = null }"))
        // Nothing restores a page from storage any more, so no stored value can open Settings mid-page.
        assertFalse(source.contains("noop.settingsTab"))
        assertFalse(source.contains("SettingsTabPrefs"))
    }

    /** The strap experiments and the strap log moved to Advanced; the Strap page shows everyday controls. */
    @Test
    fun strapExperimentsLiveOnAdvanced() {
        val source = settingsSource()
        val strapStart = source.indexOf("if (settingsPage == SettingsPage.STRAP) {")
        val strapEnd = source.indexOf("if (settingsPage == SettingsPage.HEALTH) {", strapStart)
        val advanced = source.indexOf("if (settingsPage == SettingsPage.ADVANCED) {")
        assertTrue("page blocks out of order", strapStart >= 0 && strapEnd > strapStart && advanced > strapEnd)
        val strapPage = source.substring(strapStart, strapEnd)
        for (needle in listOf("R.string.fast_history_sync)", "R.string.fast_link_phy)", "share_strap_log_for_bug_reports")) {
            assertFalse("$needle is still on the Strap page", strapPage.contains(needle))
            assertTrue("$needle is missing from Advanced", source.indexOf(needle, advanced) > advanced)
        }
    }

    /** SettingsScreen.kt with every whitespace run collapsed to one space; skips the test if it is absent. */
    private fun settingsSource(): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val file = listOf(
            File(userDir, "src/main/java/com/noop/ui/SettingsScreen.kt"),
            File(userDir, "app/src/main/java/com/noop/ui/SettingsScreen.kt"),
            File(userDir, "android/app/src/main/java/com/noop/ui/SettingsScreen.kt"),
        ).firstOrNull { it.isFile }
        assumeTrue("SettingsScreen.kt not reachable from $userDir", file != null)
        return file!!.readText().replace(Regex("\\s+"), " ")
    }
}
