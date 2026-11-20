package com.noop.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.noop.ui.DarkTokens
import com.noop.ui.HomeTone
import com.noop.ui.LightTokens
import com.noop.ui.Palette
import com.noop.ui.PaletteTokens
import com.noop.ui.restTone

/**
 * Whether a widget should draw dark, resolved the way every widget in this package resolves it.
 *
 * This lived as four byte-identical copies, one per widget. It is extracted because a FIFTH reader
 * arrived that must not disagree with them: [RenderedGate] decides whether a push is worth sending, and
 * the appearance is an input the widgets read at composition rather than one the snapshot carries. A
 * gate that resolved the theme even slightly differently would decline a push the widgets needed, and
 * the widget would sit in the wrong colours until something unrelated moved.
 *
 * The in-app override wins; "system" falls back to the configuration's night mode. Defaults to dark on
 * any failure, matching what the four copies did.
 */
internal object WidgetTheme {

    fun isDark(context: Context): Boolean = runCatching {
        when (context.getSharedPreferences("noop_prefs", Context.MODE_PRIVATE)
            .getString("theme.appearance", "system")) {
            "light" -> false
            "dark" -> true
            else -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }.getOrDefault(true)
}

/**
 * The widget colours, resolved from the app's own Calm token sets ([DarkTokens] / [LightTokens]) rather
 * than copied as hex literals.
 *
 * Glance composes outside the app theme, so a widget cannot read [Palette]'s active scheme; it picks the
 * token set itself from [WidgetTheme.isDark]. Reading the same token objects the app paints with is what
 * keeps a widget and the Home screen from showing one score in two colours, and lets a future palette
 * change reach the widgets without a second edit here.
 *
 * Each score is coloured the way Home colours it: Charge on the recovery ramp, Effort on the strain ramp,
 * Rest on the good / fair / poor status colours ([restTone]).
 */
internal class WidgetColors private constructor(private val t: PaletteTokens) {

    /** The card colour: the raised surface Calm cards use, not the page background. */
    val surface: Color get() = t.surfaceRaised
    val textPrimary: Color get() = t.textPrimary
    val textSecondary: Color get() = t.textSecondary

    /** Heart-rate accent: HR zone 5, the colour a heart reads in this app. */
    val heart: Color get() = t.zone5

    /** The Stress screen's three-stop ramp (calm, steady, tense), so a widget hour matches the screen. */
    val stressCalm: Color get() = t.accent
    val stressSteady: Color get() = t.statusPositive
    val stressTense: Color get() = t.statusWarning

    /** Charge on the recovery ramp at a 0..100 score, as Home draws it. */
    fun charge(score: Int): Color = Palette.sample(
        listOf(
            0.00f to t.recovery000, 0.30f to t.recovery030, 0.55f to t.recovery055,
            0.78f to t.recovery078, 1.00f to t.recovery100,
        ),
        score / 100f,
    )

    /** Effort on the strain ramp at a 0..100 value, as Home draws it. */
    fun effort(value: Int): Color = Palette.sample(
        listOf(0.00f to t.strain000, 0.33f to t.strain033, 0.66f to t.strain066, 1.00f to t.strain100),
        value / 100f,
    )

    /** Rest on the status colours, through the same good / fair / poor cuts Home uses. */
    fun rest(score: Int): Color = when (restTone(score.toDouble())) {
        HomeTone.GOOD -> t.statusPositive
        HomeTone.FAIR -> t.statusWarning
        HomeTone.POOR -> t.statusCritical
    }

    companion object {
        private val dark = WidgetColors(DarkTokens)
        private val light = WidgetColors(LightTokens)

        fun of(dark: Boolean): WidgetColors = if (dark) this.dark else light
    }
}

/** Corner radius of every widget card, matching the Calm card radius in the app. */
internal val WIDGET_CORNER_RADIUS = 24.dp
