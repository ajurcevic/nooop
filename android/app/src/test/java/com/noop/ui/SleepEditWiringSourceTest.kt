package com.noop.ui

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Source tripwires for how the Sleep screen wires its edit and delete controls. Each is a Composable choice
 * with no unit seam: the Naps card's rows edit and delete through the nap-row callbacks, the Last night
 * card's confirms are keyed to the night they were opened for, and only a removal from the Last night card
 * scrolls the Undo strip into view. The pure halves are pinned in [SleepEditTargetsTest].
 */
class SleepEditWiringSourceTest {

    @Test
    fun napsCardRowsEditAndDeleteThroughTheNapRowCallbacks() {
        val screen = uiSource("SleepScreen.kt")
        assertTrue(screen.contains("onUpdateTimes = onUpdateNapTimes, onDeleteSession = onDeleteNapSession,"))
        assertTrue(screen.contains("{ s, start, end -> applySleepTimes(s, start, end, true) }"))
        assertTrue(screen.contains("val onDeleteNapSession: (SleepSession) -> Unit = { s -> applySleepDelete(s, true) }"))
        // A nap row's window must overlap its nap before anything is planned.
        assertTrue(screen.contains("if (!fromNapRow || napRowWindowApplies(s, safeStart, safeEnd))"))
    }

    @Test
    fun lastNightConfirmsAreKeyedToTheirNight() {
        val header = uiSource("SleepNightNavUi.kt")
        assertTrue(header.contains("var showDeleteConfirm by remember(session?.deviceId, session?.startTs)"))
        assertTrue(header.contains("var pendingDisjointTimes by remember(session?.deviceId, session?.startTs)"))
    }

    @Test
    fun onlyALastNightRemovalScrollsTheUndoStripIntoView() {
        val screen = uiSource("SleepScreen.kt")
        assertTrue(screen.contains("if (undo.fromLastNightCard && sleepListState.layoutInfo.visibleItemsInfo"))
        assertTrue(screen.contains("SleepUndoState(listOf(s), fromEdit = false, fromLastNightCard = !fromNapRow)"))
        // The Last night card keeps its own callbacks, which pass fromNapRow = false.
        assertTrue(screen.contains("onUpdateTimes = onUpdateSleepTimes, onDeleteSession = onDeleteSleepSession,"))
        assertTrue(screen.contains("val onDeleteSleepSession: (SleepSession) -> Unit = { s -> applySleepDelete(s, false) }"))
    }

    /** A file in the ui package with every whitespace run collapsed to one space; skips the test if absent. */
    private fun uiSource(name: String): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val file = listOf(
            File(userDir, "src/main/java/com/noop/ui/$name"),
            File(userDir, "app/src/main/java/com/noop/ui/$name"),
            File(userDir, "android/app/src/main/java/com/noop/ui/$name"),
        ).firstOrNull { it.isFile }
        assumeTrue("$name not reachable from $userDir", file != null)
        return file!!.readText().replace(Regex("\\s+"), " ")
    }
}
