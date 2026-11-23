package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for the screen-header rule: a `ScreenScaffold` / `LazyScreenScaffold` subtitle shows
 * live state the user needs (a date, a status), never a static tagline under the title.
 *
 * Every scaffold call in `com.noop` (ui + push) is scanned. A call that passes `subtitle =` must use
 * one of the reviewed live/status expressions in [allowedSubtitles]; anything else (a string literal,
 * a tagline string resource) fails the build so the taglines do not creep back one screen at a time.
 */
class ScreenTaglineAuditTest {

    private val allowedSubtitles = setOf(
        // Coupled view: today's date, the only date on that screen.
        "subtitleToday()",
        // Self-hosted push: the default-off Experimental status marker (upstream #1314 export boundary).
        "stringResource(R.string.push_subtitle)",
    )

    private fun noopSourceDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val found = listOf(
            File(userDir, "src/main/java/com/noop"),
            File(userDir, "app/src/main/java/com/noop"),
            File(userDir, "android/app/src/main/java/com/noop"),
        ).firstOrNull { it.isDirectory }
        assertNotNull(
            "com/noop not found from user.dir=$userDir; a source guard must fail closed",
            found,
        )
        return found!!
    }

    /** Returns the text between the `(` at [open] and its matching `)`, skipping string contents. */
    private fun argumentsAt(source: String, open: Int): String {
        var depth = 0
        var quoted = false
        var index = open
        while (index < source.length) {
            val char = source[index]
            if (char == '"' && source[index - 1] != '\\') quoted = !quoted
            if (!quoted) {
                if (char == '(') depth++
                if (char == ')') {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
            index++
        }
        return source.substring(open + 1)
    }

    /** Reads the expression after `subtitle =` up to the next top-level comma. */
    private fun expressionAt(args: String, start: Int): String {
        var depth = 0
        var quoted = false
        var index = start
        while (index < args.length) {
            val char = args[index]
            if (char == '"' && args[index - 1] != '\\') quoted = !quoted
            if (!quoted) {
                if (char == '(' || char == '{') depth++
                if (char == ')' || char == '}') depth--
                if (char == ',' && depth == 0) break
            }
            index++
        }
        return args.substring(start, index).trim()
    }

    @Test
    fun `screen scaffolds carry no static tagline subtitle`() {
        val callPattern = Regex("""(?<![A-Za-z0-9_])(Lazy)?ScreenScaffold\(""")
        val subtitlePattern = Regex("""\bsubtitle\s*=\s*""")
        var calls = 0
        val offenders = mutableListOf<String>()
        noopSourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            callPattern.findAll(source).forEach { match ->
                val lineStart = source.lastIndexOf('\n', match.range.first) + 1
                val prefix = source.substring(lineStart, match.range.first)
                // Skip the declarations themselves and mentions inside comments.
                if (prefix.contains("fun ") || prefix.contains("//") || prefix.trimStart().startsWith("*")) {
                    return@forEach
                }
                calls++
                val args = argumentsAt(source, match.range.last)
                subtitlePattern.findAll(args).forEach { sub ->
                    val expression = expressionAt(args, sub.range.last + 1)
                    if (expression !in allowedSubtitles) {
                        offenders += "${file.name}: subtitle = $expression"
                    }
                }
            }
        }
        assertTrue("expected to find the app's screen scaffold calls, found $calls", calls >= 25)
        assertEquals(
            "Screen subtitles must show live state, not a static tagline:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }
}
