package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Android More-page navigation contract.
 *
 * More is three plain cards of rows ([moreCards]), with no group headers and nothing to collapse. It is a
 * real NavHost destination, so each row must push its destination onto the current stack. Treating a row
 * like a bottom-tab switch pops More back to Today before opening the destination, which makes Android
 * Back skip More entirely.
 *
 * The screens More no longer lists keep a door: a link inside their host screen (Compare on Explore,
 * Backup & Sync on Data Sources, Alarms on Automations, Vital Signs on Health) or a row in Settings. The
 * last test walks every [Destination] and names any that nothing opens, so dropping a link cannot strand a
 * screen without failing here. The source audits follow the source-locating pattern used by
 * [ResolvedSeriesCallSiteAuditTest].
 */
class MoreNavigationContractTest {

    /** The destinations of every More row, in page order. */
    private val moreDests: List<Destination> get() = moreCards.flatten().map { it.dest }

    /** The screens that left More for a row in Settings. */
    private val settingsRows = listOf(
        Destination.Notifications, Destination.PowerSaving, Destination.Rhythm, Destination.Intelligence,
        Destination.NoopLimitations, Destination.CoupledView, Destination.TestCentre,
    )

    /** Locate a ui/ source file from Gradle, IDE, or repository-root test working directories. */
    private fun uiSource(name: String): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(userDir, "src/main/java/com/noop/ui/$name"),
            File(userDir, "app/src/main/java/com/noop/ui/$name"),
            File(userDir, "android/app/src/main/java/com/noop/ui/$name"),
        ).firstOrNull { it.isFile }
    }

    /** A ui/ source file with every whitespace run collapsed to one space; skips the test if it is absent. */
    private fun normalisedSource(name: String): String {
        val file = uiSource(name)
        assumeTrue("$name not reachable from ${System.getProperty("user.dir")}", file != null)
        return file!!.readText().replace(Regex("\\s+"), " ")
    }

    /** Whether [source] holds a `NavLink(Destination.<dest>` row for exactly that destination. */
    private fun hasLinkRow(source: String, dest: Destination): Boolean =
        Regex("""NavLink\(Destination\.${dest.name}[,)]""").containsMatchIn(source)

    @Test
    fun moreRowsPushWhileBottomTabsRemainTopLevel() {
        val source = normalisedSource("AppRoot.kt")

        assertTrue(
            "More destinations must be pushed so Back returns to More",
            source.contains("MoreScreen(onNavigate = { nav.navigate(it) })"),
        )
        assertFalse(
            "More destinations must not clear the stack through navigateTopLevel",
            source.contains("MoreScreen(onNavigate = { nav.navigateTopLevel(it) })"),
        )
        assertTrue(
            "Every More card must keep using the shared navigation callback",
            source.contains("moreCards.forEach { card -> NavLinkCard(card, onOpen = { onNavigate(it.route) }) }"),
        )
        assertTrue(
            "Bottom-tab selections must retain top-level state save/restore",
            source.contains("if (dest.route != currentRoute) nav.navigateTopLevel(dest.route)"),
        )
    }

    /** Three plain cards, eleven rows, in the order the design shows them. */
    @Test
    fun moreIsThreeCardsOfElevenRows() {
        assertEquals(
            listOf(
                listOf(
                    Destination.Live, Destination.Workouts, Destination.Health, Destination.Stress,
                    Destination.Breathe,
                ),
                listOf(Destination.Insights, Destination.Explore),
                listOf(Destination.Devices, Destination.DataSources, Destination.Automations, Destination.Settings),
            ),
            moreCards.map { card -> card.map { it.dest } },
        )
        assertEquals(11, moreDests.size)
        assertEquals("a destination is listed twice", moreDests.size, moreDests.distinct().size)
    }

    /** None of the screens that moved to a host screen's link or a Settings row is listed on More again. */
    @Test
    fun theMovedScreensAreNotOnMore() {
        val moved = settingsRows + listOf(
            Destination.VitalSigns, Destination.Compare, Destination.BackupSync, Destination.SmartAlarm,
        )
        val listedAgain = moved.filter { it in moreDests }
        assertTrue("these left More but are listed on it again: $listedAgain", listedAgain.isEmpty())
    }

    /** The four relabelled rows use their own strings; every other row shows the screen's own title. */
    @Test
    fun relabelledRowsUseTheirOwnStrings() {
        val ownLabel = mapOf(
            Destination.Live to R.string.more_row_live_heart_rate,
            Destination.Explore to R.string.more_row_explore_compare,
            Destination.DataSources to R.string.more_row_import_backup,
            Destination.Automations to R.string.more_row_alarms_automations,
        )
        for (link in moreCards.flatten()) {
            assertEquals("${link.dest} row label", ownLabel[link.dest] ?: link.dest.titleRes, link.labelRes)
        }
    }

    /** Each screen that left More opens from its host screen's link, pushed, or from a Settings row. */
    @Test
    fun theMovedScreensOpenFromTheirHostScreenOrSettings() {
        val root = normalisedSource("AppRoot.kt")
        for (wiring in listOf(
            "TrendsExploreScreen(viewModel, onOpenCompare = { nav.navigate(Destination.Compare.route) })",
            "AutomationsScreen(viewModel, onOpenSmartAlarm = { nav.navigate(Destination.SmartAlarm.route) })",
            "DataSourcesScreen(viewModel, onOpenBackupSync = { nav.navigate(Destination.BackupSync.route) })",
            "onOpenVitalSigns = { nav.navigate(Destination.VitalSigns.route) }",
            "onOpenRoute = { nav.navigate(it) }",
        )) {
            assertTrue("AppRoot.kt lost the wiring $wiring", root.contains(wiring))
        }

        val hostLinks = mapOf(
            "TrendsExploreScreen.kt" to listOf(
                "NavLinkCard(listOf(NavLink(Destination.Compare, R.string.explore_compare_row)), " +
                    "onOpen = { onOpenCompare() })",
            ),
            "DataSourcesScreen.kt" to listOf(
                // Disabled during an import, which leaving the screen would cancel part-way.
                "NavLinkCard( listOf(NavLink(Destination.BackupSync, R.string.data_sources_backup_row)), " +
                    "enabled = !busy, onOpen = { onOpenBackupSync() }, )",
            ),
            "AutomationsScreen.kt" to listOf(
                "NavLinkCard(listOf(NavLink(Destination.SmartAlarm, R.string.automations_smart_alarm_row)), " +
                    "onOpen = { onOpenSmartAlarm() })",
            ),
            "HealthScreen.kt" to listOf(
                "NavLink(Destination.VitalSigns, R.string.health_vital_signs_row)",
                "NavLinkCard(vitalSignsLink, onOpen = { onOpenVitalSigns() })",
            ),
        )
        for ((file, needles) in hostLinks) {
            val source = normalisedSource(file)
            for (needle in needles) assertTrue("$file lost its link: $needle", source.contains(needle))
        }

        val settings = normalisedSource("SettingsScreen.kt")
        assertTrue(
            "the Settings rows must open their destination through onOpenRoute",
            settings.contains("onOpen = { onOpenRoute(it.route) }"),
        )
        for (dest in settingsRows) {
            assertTrue("Settings lost its ${dest.name} row", hasLinkRow(settings, dest))
        }
        assertEquals(
            "Strap limitations keeps its own label in Settings",
            1,
            Regex("""NavLink\(Destination\.NoopLimitations, R\.string\.settings_row_strap_limitations\)""")
                .findAll(settings).count(),
        )
    }

    /**
     * Every destination is reachable: the start destination, a bottom-bar tab, a More row, a push or tab
     * switch wired in AppRoot, or a Settings row. Fails naming every destination nothing opens.
     */
    @Test
    fun everyDestinationIsReachable() {
        val root = normalisedSource("AppRoot.kt")
        val settings = normalisedSource("SettingsScreen.kt")
        val barDests = (barLeadingTabs + barTrailingTabs).map { it.dest }
        val unreachable = Destination.entries.filterNot { dest ->
            dest == Destination.Today ||
                dest == Destination.More ||
                dest in barDests ||
                dest in moreDests ||
                Regex("""navigate(TopLevel)?\(Destination\.${dest.name}\.route\)""").containsMatchIn(root) ||
                hasLinkRow(settings, dest) ||
                (dest == Destination.VitalSignsDetail && root.contains("nav.navigate(\"vital_detail/\$"))
        }
        assertTrue("nothing opens these destinations: $unreachable", unreachable.isEmpty())
        // Today is the NavHost's start destination and the bar appends its own More slot, which is why
        // both are exempt above.
        assertTrue(root.contains("startDestination = Destination.Today.route"))
        assertTrue(root.contains("onClick = { onTabSelected(Destination.More) }"))
    }
}
