package com.noop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sleep tab's one-hero, one-title layout: one pinned "Last night" hero, every card under a single title,
 * no standing hints, and the sleep marks moved from a card to the quick-actions sheet.
 *
 * Source tripwires, because each of these is a Composable layout choice with no unit seam: an overline or a
 * standing caption compiles and renders the same in any test that can run without a device. Each assertion
 * names the one shape it forbids, so a later card can still add a conditional warning note.
 */
class SleepRethinkSourceTest {

    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(userDir, File(userDir, ".."), File(userDir, "../.."))
        return candidates.firstOrNull { File(it, "android/app/src/main/java").isDirectory }
            ?: error("could not locate the repo root from ${userDir.absolutePath}")
    }

    private fun ui(name: String): String =
        File(repoRoot(), "android/app/src/main/java/com/noop/ui/$name").readText()

    /** The argument text of every `SectionHeader(...)` call in [src], parentheses balanced. */
    private fun sectionHeaderCalls(src: String): List<String> {
        val calls = mutableListOf<String>()
        Regex("""(?<![A-Za-z0-9_])SectionHeader\(""").findAll(src).forEach { match ->
            if (src.substring(0, match.range.first).endsWith("fun ")) return@forEach
            var depth = 1
            var i = match.range.last + 1
            while (i < src.length && depth > 0) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            calls += src.substring(match.range.last + 1, i - 1)
        }
        return calls
    }

    /** The body of the function declared by [signature], up to the closing brace at column 0. */
    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val end = src.indexOf("\n}", start)
        assertTrue("unterminated $signature", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `every Sleep card header is one title with no overline or trailing note`() {
        for (file in listOf("SleepScreen.kt", "SleepMetricCardsUi.kt")) {
            val calls = sectionHeaderCalls(ui(file))
            assertTrue("$file should still title its cards with SectionHeader", calls.isNotEmpty())
            for (args in calls) {
                assertFalse(
                    "$file: SectionHeader($args) carries a second label",
                    Regex("""\b(overline|trailing)\s*=""").containsMatchIn(args),
                )
            }
        }
        val cards = ui("SleepMetricCardsUi.kt")
        assertFalse("an inline \"Sleep\" overline is back", cards.contains("Overline(\"Sleep\")"))
        assertFalse("an inline \"Schedule\" overline is back", cards.contains("Overline(\"Schedule\")"))
    }

    @Test
    fun `the stages card carries no standing hint or caveat line`() {
        val screen = ui("SleepScreen.kt")
        assertFalse("the tap hint is back", screen.contains("Tap a stage to highlight"))
        assertFalse(
            "the estimated-stages caveat is an always-on line again",
            Regex("""Text\(\s*uiString\(R\.string\.sleep_stages_estimated_note\)""").containsMatchIn(screen),
        )
        // The caveat is not gone, it moved behind the card's info icon.
        assertTrue(screen.contains("uiString(R.string.sleep_stages_estimated_note)"))
        assertTrue(screen.contains("info = stagesInfo"))
    }

    @Test
    fun `the main-sleep reason is not rendered`() {
        val footer = body(ui("SleepScreen.kt"), "private fun MainSleepFooter(")
        assertFalse(footer.contains("mainSleepReasonText"))
    }

    @Test
    fun `sleep marks are logged from the quick-actions sheet and not a Sleep tab card`() {
        val screen = ui("SleepScreen.kt")
        assertTrue(screen.contains("SleepSection.SLEEP_MARKS -> Unit"))
        // The card itself still exists for the hosted Today slot; the Sleep tab only declares it.
        assertEquals(
            "SleepScreen draws a Sleep marks card",
            Regex("""fun SleepMarkCard\(""").findAll(screen).count(),
            Regex("""(?<![A-Za-z0-9_])SleepMarkCard\(""").findAll(screen).count(),
        )
        val root = ui("AppRoot.kt")
        assertTrue(root.contains("logSleepMark(viewModel, markScope, context, mark.type)"))
        assertTrue(root.contains("SleepMarkType.BEDTIME"))
        assertTrue(root.contains("SleepMarkType.WAKE"))
    }
}
