package com.noop.ui

import com.noop.analytics.ScoreConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins when a Metrics tile says its number is an estimate, and when it stays quiet. */
class MetricsEstimateNoteTest {

    @Test
    fun chargeSaysStillLearningOnlyWhileBuilding() {
        assertEquals(EstimateNote.STILL_LEARNING, chargeEstimateNote(true, ScoreConfidence.BUILDING))
        assertNull(chargeEstimateNote(true, ScoreConfidence.SOLID))
        assertNull(chargeEstimateNote(true, null))
        // Calibrating has its own caption; a carried or missing score carries none of this.
        assertNull(chargeEstimateNote(true, ScoreConfidence.CALIBRATING))
        assertNull(chargeEstimateNote(false, ScoreConfidence.BUILDING))
    }

    @Test
    fun caloriesSaysEstimatedOnlyForEstimatedDaysWithAFigure() {
        val estimated = setOf("2026-11-17")
        assertEquals(EstimateNote.FROM_HEART_RATE, caloriesEstimateNote("2026-11-17", true, estimated))
        // A phone-imported day is not Zoop's estimate.
        assertNull(caloriesEstimateNote("2026-11-18", true, estimated))
        // No figure on screen, nothing to qualify.
        assertNull(caloriesEstimateNote("2026-11-17", false, estimated))
    }
}
