package com.noop.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Metrics tab's one-line readiness card (SynthesisHeroCard). Compose wiring has no logic function to
 * assert against, so this reads the sources, the idiom [TodayWorkoutTapTest] already uses.
 *
 * Two things are pinned. Readiness is today's read, so the line takes the same today-only gate as the
 * breakdown sheet's Readiness card: without it a past day shows today's word over that day's numbers, and
 * "See your full readiness" opens a sheet that has no Readiness card. And a calibration fault the owner can
 * act on (#612) stays on the card, not behind the info button with the colour legend.
 */
class MetricsReadinessLineTest {

    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(userDir, File(userDir, ".."), File(userDir, "../.."))
        return candidates.firstOrNull { File(it, "android/app/src/main/java").isDirectory }
            ?: error("could not locate the repo root from ${userDir.absolutePath}")
    }

    /** The source with `//` comments removed, so prose naming a symbol is not mistaken for the code. */
    private fun code(name: String): String =
        File(repoRoot(), "android/app/src/main/java/com/noop/ui/$name").readText()
            .lines().joinToString("\n") { it.substringBefore("//") }

    /** The argument list of the first call to [callee]: the text between its `(` and the matching `)`. */
    private fun callArguments(source: String, callee: String): String {
        val open = source.indexOf("$callee(")
        assertTrue("$callee( not found", open >= 0)
        var depth = 0
        var quoted = false
        var index = open + callee.length
        while (index < source.length) {
            val char = source[index]
            if (char == '"' && source[index - 1] != '\\') quoted = !quoted
            if (!quoted) {
                if (char == '(') depth++
                if (char == ')') {
                    depth--
                    if (depth == 0) return source.substring(open + callee.length + 1, index)
                }
            }
            index++
        }
        throw AssertionError("the call to $callee is not closed")
    }

    @Test
    fun theLineAndTheBreakdownSheetShareOneTodayOnlyReadinessGate() {
        val screen = code("TodayScreen.kt")
        val gate = "showReadiness = selectedDayOffset == 0"
        assertTrue(
            "the readiness line must be today-only, or a past day shows today's word",
            callArguments(screen, "SynthesisHeroCard").contains(gate),
        )
        assertTrue(
            "the breakdown sheet's Readiness card must keep the same gate as the line that opens it",
            callArguments(screen, "ChargeBreakdownSheet").contains(gate),
        )
    }

    @Test
    fun aCalibrationFaultStaysOnTheCardNotBehindTheInfoButton() {
        val hero = code("TodayHero.kt")
        val start = hero.indexOf("internal fun SynthesisHeroCard(")
        assertTrue("SynthesisHeroCard not found", start >= 0)
        val body = hero.substring(start, hero.indexOf("\n}\n", start))
        assertTrue(
            "the #612 no-new-nights notice must still be built",
            body.contains("R.string.l10n_today_screen_no_new_nights_from_your_strap_for_stale_days_8863bcfe"),
        )
        val notice = body.indexOf("calibrationNotice?.let")
        val dialog = body.indexOf("AlertDialog(")
        assertTrue("the notice must be drawn", notice >= 0)
        assertTrue("the info dialog must exist", dialog >= 0)
        assertTrue("the notice must sit on the card, before the info dialog", notice < dialog)
    }
}
