package com.noop.widget

import com.noop.ui.DarkTokens
import com.noop.ui.LightTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the widgets to the app's Calm tokens: the card, text and score colours must be the ones Home paints
 * with, so a widget and the Home screen cannot show one score in two colours.
 */
class WidgetColorsTest {

    private val dark = WidgetColors.of(true)
    private val light = WidgetColors.of(false)

    @Test
    fun surfaceAndTextComeFromTheActiveTokenSet() {
        assertEquals(DarkTokens.surfaceRaised, dark.surface)
        assertEquals(DarkTokens.textPrimary, dark.textPrimary)
        assertEquals(DarkTokens.textSecondary, dark.textSecondary)
        assertEquals(LightTokens.surfaceRaised, light.surface)
        assertNotEquals(dark.surface, light.surface)
    }

    @Test
    fun restUsesTheHomeGoodFairPoorCuts() {
        assertEquals(DarkTokens.statusPositive, dark.rest(70))
        assertEquals(DarkTokens.statusWarning, dark.rest(69))
        assertEquals(DarkTokens.statusWarning, dark.rest(50))
        assertEquals(DarkTokens.statusCritical, dark.rest(49))
    }

    @Test
    fun chargeAndEffortSampleTheirRampEnds() {
        assertEquals(DarkTokens.recovery000, dark.charge(0))
        assertEquals(DarkTokens.recovery100, dark.charge(100))
        assertEquals(DarkTokens.strain000, dark.effort(0))
        assertEquals(DarkTokens.strain100, dark.effort(100))
        // Out-of-range input clamps to the ramp ends rather than extrapolating.
        assertEquals(DarkTokens.recovery100, dark.charge(140))
    }

    @Test
    fun heartAndStressMatchTheirScreens() {
        assertEquals(DarkTokens.zone5, dark.heart)
        assertEquals(DarkTokens.accent, dark.stressCalm)
        assertEquals(DarkTokens.statusPositive, dark.stressSteady)
        assertEquals(DarkTokens.statusWarning, dark.stressTense)
    }
}
