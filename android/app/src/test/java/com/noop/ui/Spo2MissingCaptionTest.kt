package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The Blood O₂ tile must distinguish "the sensor recorded nothing" from "the sensor recorded, but the
 * calibrated % comes from an import".
 *
 * A WHOOP 4.0 banks raw red/IR ADC counts and NOT a percentage — the schema says so at the field
 * (`raw ADC; SpO2 % computed server-side`) — so `spo2Pct` is import-only by design and the raw tile shows
 * the counts instead. When both tiles render together the old wording contradicted itself: an empty
 * "No SpO₂ import or Health value" sitting beside a Raw SpO₂ tile displaying a live number, which reads
 * as a broken strap rather than a missing import. That is a real user report, not a hypothetical.
 *
 * `vitalsFor` resolves strings through `NoopApplication`, so it cannot run in a JVM unit test at all;
 * [spo2MissingCaptionRes] exists to make the BRANCH testable without the resource lookup.
 */
class Spo2MissingCaptionTest {

    @Test
    fun rawCountsPresentGetsTheImportWording() {
        assertEquals(
            R.string.l10n_health_screen_raw_counts_only_needs_an_import_d0e33552,
            spo2MissingCaptionRes(hasRawSpo2 = true),
        )
    }

    @Test
    fun nothingDecodedKeepsTheOriginalWording() {
        assertEquals(
            R.string.l10n_health_screen_no_spo_import_or_health_value_408f8c55,
            spo2MissingCaptionRes(hasRawSpo2 = false),
        )
    }

    /** The whole point is that the two states read differently. */
    @Test
    fun theTwoEmptyStatesAreNotTheSameString() {
        assertNotEquals(spo2MissingCaptionRes(true), spo2MissingCaptionRes(false))
    }
}
