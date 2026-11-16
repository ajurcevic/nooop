package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The deferred restore swap: a verified backup is parked beside the live database and renamed into place
 * at the next cold start, before Room opens. Driven on real files, since renames and sidecars are the
 * whole behaviour.
 */
class PendingRestoreSwapTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun db(): File = File(tmp.root, "noop_whoop.db")
    private fun side(f: File, suffix: String) = File(f.path + suffix)
    private fun pending(dbFile: File) = DataBackup.pendingRestoreFile(dbFile)
    private fun previous(dbFile: File) = File(dbFile.path + DataBackup.RESTORE_PREVIOUS_SUFFIX)

    /** Nothing parked: nothing moves, and the live store and its WAL are left exactly as they were. */
    @Test
    fun `no parked restore leaves the live database alone`() {
        val dbFile = db().apply { writeText("live") }
        side(dbFile, "-wal").writeText("live-wal")
        assertEquals(DataBackup.PendingSwap.NONE, DataBackup.swapInPendingRestore(dbFile))
        assertEquals("live", dbFile.readText())
        assertEquals("live-wal", side(dbFile, "-wal").readText())
    }

    /** The parked bytes become the live file, and the old store's sidecars cannot pair with them. */
    @Test
    fun `a parked restore replaces the live database and drops its sidecars`() {
        val dbFile = db().apply { writeText("live") }
        side(dbFile, "-wal").writeText("live-wal")
        side(dbFile, "-shm").writeText("live-shm")
        side(dbFile, "-journal").writeText("live-journal")
        pending(dbFile).writeText("backup")

        assertEquals(DataBackup.PendingSwap.APPLIED, DataBackup.swapInPendingRestore(dbFile))
        assertEquals("backup", dbFile.readText())
        assertFalse(pending(dbFile).exists())
        for (s in listOf("-wal", "-shm", "-journal")) {
            assertFalse("stale $s must not sit beside the restored file", side(dbFile, s).exists())
        }
        assertFalse("snapshot removed after success", previous(dbFile).exists())
    }

    /** A fresh install may have no live file yet. */
    @Test
    fun `a parked restore with no live database is applied`() {
        val dbFile = db()
        pending(dbFile).writeText("backup")
        assertEquals(DataBackup.PendingSwap.APPLIED, DataBackup.swapInPendingRestore(dbFile))
        assertEquals("backup", dbFile.readText())
    }

    /**
     * Killed after the live file moved aside but before the parked one moved in: the next start must
     * finish the swap and must not delete the snapshot first (it is the only copy of the old store).
     */
    @Test
    fun `an interrupted swap is finished without touching the snapshot first`() {
        val dbFile = db()
        previous(dbFile).writeText("old")
        pending(dbFile).writeText("backup")
        assertEquals(DataBackup.PendingSwap.APPLIED, DataBackup.swapInPendingRestore(dbFile))
        assertEquals("backup", dbFile.readText())
    }

    /** An older build's roll-back copy may be the only good copy of someone's data; it is never touched. */
    @Test
    fun `a legacy import-bak file is never deleted`() {
        val dbFile = db().apply { writeText("live") }
        val legacy = File(dbFile.path + ".import-bak").apply { writeText("precious") }
        pending(dbFile).writeText("backup")
        assertEquals(DataBackup.PendingSwap.APPLIED, DataBackup.swapInPendingRestore(dbFile))
        assertTrue(legacy.exists())
        assertEquals("precious", legacy.readText())
    }
}
