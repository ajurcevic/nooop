package com.noop.data

import com.noop.protocol.RrSourceChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1071 — `rrInterval` mixed two optical channels, so every Oura beat was stored twice.
 *
 * The ring measures the SAME heartbeats on more than one tag (0x80 green all night, 0x6E only while an
 * SpO2 measurement runs) and both decoded to an R-R row, so the table held roughly two complete copies
 * of a night: 2.06x the beats the measured HR curve allows over one 488-min window. That leaves the
 * MEAN correct — resting HR was never wrong — and destroys everything built on successive differences:
 * RMSSD, and a ~200 ms nocturnal SDNN where a healthy adult asleep is 40-100 ms.
 *
 * The fix is deliberately NOT a de-duplication: both rows are real measurements, so the channel is
 * LABELLED at decode, both rows are STORED, and the scoring read takes one. This is the Kotlin half of
 * the parity contract — the Swift twin is `RrSourceChannelTests` in WhoopStoreTests, which also
 * exercises the read end-to-end against a real SQLite. There is no SQLite driver on this unit-test
 * classpath (see [DeepCaptureMigrationTest]), so the read filter is guarded here by auditing the DAO
 * query text, the way [HrFrontierQueryShapeTest] does.
 *
 * The Oura decoders that labelled these channels are gone from this WHOOP-only build. The storage half
 * stays because existing databases and backups may still carry labelled rows.
 */
class RrSourceChannelTest {

    /**
     * The raw codes are a DURABLE storage format (`rrInterval.srcChannel`, and the `.noopbak` that
     * carries it between platforms), so renumbering a case would silently relabel stored history.
     * Pinned here rather than trusted to declaration order. Twin of the Swift assertion.
     */
    @Test
    fun channelCodesAreTheDurableStorageValues() {
        assertEquals(1, RrSourceChannel.GREEN_QUALITY.code)
        assertEquals(2, RrSourceChannel.SPO2_IBI.code)
        assertEquals(3, RrSourceChannel.IBI_AMPLITUDE.code)
        assertEquals(4, RrSourceChannel.IBI_BARE.code)
        assertEquals(RrSourceChannel.SPO2_IBI, RrSourceChannel.fromCode(2))
        assertNull("an unknown code is unknown, not a default", RrSourceChannel.fromCode(99))
        assertNull(RrSourceChannel.fromCode(null))
    }

    // MARK: - The insert path

    @Test
    fun assignRrSeqCarriesTheChannelOntoTheRowWithoutTouchingTheKey() {
        val ts = 1_780_916_150L
        val out = assignRrSeq(
            "ring",
            listOf(
                RrRow(ts, 848, RrSourceChannel.GREEN_QUALITY),
                RrRow(ts, 848, RrSourceChannel.SPO2_IBI),
                RrRow(ts, 856, null),
            ),
        )
        assertEquals(listOf(1, 2, null), out.map { it.srcChannel })
        // seq/ord are unaffected: the two equal beats still get distinct seq (so both are storable),
        // and all three keep their emission order.
        assertEquals(listOf(0, 1, 0), out.map { it.seq })
        assertEquals(listOf(0, 1, 2), out.map { it.ord })
    }

    @Test
    fun entityDefaultsSrcChannelToNull_soWhoopAndLegacyRowsStayHonest() {
        // A WHOOP strap has ONE beat source: there is no channel to name, and NULL says exactly that
        // rather than asserting a channel nobody measured.
        assertNull(RrInterval(deviceId = "d", ts = 1L, rrMs = 800).srcChannel)
        assertNull(RrRow(1L, 800).srcChannel)
    }

    // MARK: - The migration

    @Test
    fun migrationIsAdditiveNullableAndNotAKeyChange() {
        val sql = WhoopDatabase.RR_SRC_CHANNEL_MIGRATION_SQL
        assertEquals(1, sql.size)
        assertEquals("ALTER TABLE `rrInterval` ADD COLUMN `srcChannel` INTEGER", sql[0])
        val upper = sql[0].uppercase()
        assertTrue(upper.startsWith("ALTER TABLE"))
        assertTrue("must be additive", upper.contains("ADD COLUMN"))
        assertFalse("srcChannel must be nullable", upper.contains("NOT NULL"))
        assertFalse("no default — absent means no channel to name", upper.contains("DEFAULT"))
        for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ", "PRIMARY KEY")) {
            assertFalse("migration must not contain $banned", upper.contains(banned))
        }
        assertEquals(25, WhoopDatabase.MIGRATION_25_26.startVersion)
        assertEquals(26, WhoopDatabase.MIGRATION_25_26.endVersion)
    }

    // MARK: - The read filter

    /**
     * The read is the half that actually fixes the numbers, and it is SQL — so it is audited as source.
     * The behavioural end of it is the Swift twin, which CI runs against a real SQLite.
     */
    @Test
    fun rrIntervalsReadsOneChannelAndKeepsNullRows() {
        val q = rrIntervalsQuery().replace(Regex("\\s+"), " ")

        assertTrue(
            "the scoring read must exclude the SpO2 channel: $q",
            q.contains("srcChannel <> ${RrSourceChannel.SPO2_IBI.code}"),
        )
        // NULL must survive the filter. `srcChannel <> 2` alone is NULL (not true) for a NULL row, so
        // SQLite would drop every WHOOP night and every pre-v26 row — the one way this predicate can
        // quietly delete real data.
        assertTrue(
            "NULL rows (WHOOP, pre-v26) must be kept explicitly: $q",
            q.contains("srcChannel IS NULL OR"),
        )
        // The amplitude channel is deliberately NOT excluded: on a ring where 0x60 is the only beat
        // source, excluding it would leave nothing to score.
        assertFalse(
            "only the demonstrated duplicate is excluded: $q",
            q.contains("srcChannel <> ${RrSourceChannel.IBI_AMPLITUDE.code}"),
        )
        // #823's emission order still leads the sort — the filter must not have displaced it.
        assertTrue("emission order must still lead: $q", q.contains("ORDER BY ts ASC, ord ASC"))
    }

    /** The `@Query` body attached to `rrIntervals`, string fragments joined, comments excluded. */
    private fun rrIntervalsQuery(): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val rel = "src/main/java/com/noop/data/WhoopDao.kt"
        val found = listOf(File(userDir, rel), File(userDir, "app/$rel"), File(userDir, "android/app/$rel"))
            .firstOrNull { it.isFile }
        assertNotNull(
            "WhoopDao.kt not found from user.dir=$userDir — this guard cannot run, and a skip here " +
                "reads as a pass. Add this host's working dir to the list rather than letting it slide.",
            found,
        )
        val src = found!!.readText()
        val decl = src.indexOf("suspend fun rrIntervals(")
        assertTrue("rrIntervals not found — was it renamed?", decl > 0)
        val annotationStart = src.lastIndexOf("@Query(", decl)
        assertTrue("no @Query above rrIntervals", annotationStart > 0)
        val block = src.substring(annotationStart, decl)
        if (block.contains("@Query(RR_INTERVALS_SQL)")) return RR_INTERVALS_SQL
        return Regex("\"([^\"]*)\"").findAll(block).joinToString("") { it.groupValues[1] }
    }
}
