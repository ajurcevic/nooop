package com.noop.ui

import com.noop.ui.StrapStatusPill.Battery
import com.noop.ui.StrapStatusPill.ChipText
import com.noop.ui.StrapStatusPill.Level
import com.noop.ui.StrapStatusPill.Status
import com.noop.ui.StrapStatusPill.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins [StrapStatusPill.resolve] and [StrapStatusPill.chipText], the decisions behind the Today header battery chip. */
class StrapStatusPillTest {

    private val charge72 = HeaderBatteryDisplay.State.Charge(72.4, isRing = false)

    @Test fun `linked strap with a reading shows charge and sync age`() {
        val m = StrapStatusPill.resolve(charge72, SyncChipState.Synced("3m"), canReconnect = false, scanning = false)
        assertEquals(StrapStatusPill.Model(Battery.Charge(72, false, Level.Ok), Status.Synced("3m"), Tap.OpenDevices), m)
    }

    @Test fun `charge bands match the old ring thresholds`() {
        fun level(pct: Double) = (StrapStatusPill.resolve(
            HeaderBatteryDisplay.State.Charge(pct, isRing = false), SyncChipState.Hidden, false, false,
        )!!.battery as Battery.Charge).level
        assertEquals(Level.Critical, level(14.9))
        assertEquals(Level.Low, level(15.0))
        assertEquals(Level.Low, level(34.9))
        assertEquals(Level.Ok, level(35.0))
    }

    @Test fun `syncing reads as catching up`() {
        val m = StrapStatusPill.resolve(charge72, SyncChipState.Syncing(10, 80), false, false)
        assertEquals(Status.CatchingUp, m!!.status)
    }

    @Test fun `offline with a scan action offers reconnect and the tap reconnects`() {
        val m = StrapStatusPill.resolve(
            HeaderBatteryDisplay.State.Offline, SyncChipState.Synced("2h"), canReconnect = true, scanning = false,
        )
        assertEquals(StrapStatusPill.Model(Battery.Offline, Status.TapToReconnect, Tap.Reconnect), m)
    }

    @Test fun `offline while scanning says looking and does not start a second scan`() {
        val m = StrapStatusPill.resolve(
            HeaderBatteryDisplay.State.Offline, SyncChipState.Synced("2h"), canReconnect = true, scanning = true,
        )
        assertEquals(StrapStatusPill.Model(Battery.Offline, Status.Looking, Tap.OpenDevices), m)
    }

    @Test fun `offline with no scan action falls back to sync state`() {
        val m = StrapStatusPill.resolve(
            HeaderBatteryDisplay.State.Offline, SyncChipState.Synced("2h"), canReconnect = false, scanning = false,
        )
        assertEquals(StrapStatusPill.Model(Battery.Offline, Status.Synced("2h"), Tap.OpenDevices), m)
    }

    @Test fun `nothing to say hides the pill`() {
        assertNull(StrapStatusPill.resolve(HeaderBatteryDisplay.State.NotActiveDevice, SyncChipState.Hidden, true, false))
    }

    @Test fun `no device but a sync state still shows the sync half`() {
        val m = StrapStatusPill.resolve(HeaderBatteryDisplay.State.NotActiveDevice, SyncChipState.ExperimentalLive, false, false)
        assertEquals(StrapStatusPill.Model(Battery.None, Status.Live, Tap.OpenDevices), m)
    }

    @Test fun `pending keeps the device name without a number`() {
        val m = StrapStatusPill.resolve(HeaderBatteryDisplay.State.Pending, SyncChipState.Hidden, false, false)
        assertEquals(StrapStatusPill.Model(Battery.Pending, Status.None, Tap.OpenDevices), m)
    }

    private fun chip(
        battery: HeaderBatteryDisplay.State,
        sync: SyncChipState,
        canReconnect: Boolean = false,
        scanning: Boolean = false,
    ): StrapStatusPill.Model = StrapStatusPill.resolve(battery, sync, canReconnect, scanning)!!

    @Test fun `a synced strap shows only its charge`() {
        assertEquals(ChipText.Percent(72), StrapStatusPill.chipText(chip(charge72, SyncChipState.Synced("3m"))))
    }

    @Test fun `a running offload says syncing in place of the charge`() {
        assertEquals(ChipText.Syncing, StrapStatusPill.chipText(chip(charge72, SyncChipState.Syncing(10, 80))))
    }

    @Test fun `offline with a scan action says not connected and the tap still reconnects`() {
        val m = chip(HeaderBatteryDisplay.State.Offline, SyncChipState.Synced("2h"), canReconnect = true)
        assertEquals(ChipText.NotConnected, StrapStatusPill.chipText(m))
        assertEquals(Tap.Reconnect, m.tap)
    }

    @Test fun `offline while scanning says looking`() {
        val m = chip(HeaderBatteryDisplay.State.Offline, SyncChipState.Synced("2h"), canReconnect = true, scanning = true)
        assertEquals(ChipText.Looking, StrapStatusPill.chipText(m))
    }

    @Test fun `offline with no scan action still says not connected rather than a sync age`() {
        val m = chip(HeaderBatteryDisplay.State.Offline, SyncChipState.Synced("2h"))
        assertEquals(ChipText.NotConnected, StrapStatusPill.chipText(m))
    }

    @Test fun `pending is a glyph with no words`() {
        val m = chip(HeaderBatteryDisplay.State.Pending, SyncChipState.Hidden)
        assertEquals(ChipText.None, StrapStatusPill.chipText(m))
        assertTrue(StrapStatusPill.showsChip(m))
    }

    @Test fun `a sync state with no device and nothing to say draws no chip`() {
        val m = chip(HeaderBatteryDisplay.State.NotActiveDevice, SyncChipState.ExperimentalLive)
        assertEquals(ChipText.None, StrapStatusPill.chipText(m))
        assertFalse(StrapStatusPill.showsChip(m))
    }

    @Test fun `experimental live with a charge still shows the charge`() {
        assertEquals(ChipText.Percent(72), StrapStatusPill.chipText(chip(charge72, SyncChipState.ExperimentalLive)))
    }

    @Test fun `only syncing, not connected and looking need attention`() {
        assertTrue(StrapStatusPill.needsAttention(chip(charge72, SyncChipState.Syncing(10, 80))))
        assertTrue(StrapStatusPill.needsAttention(chip(HeaderBatteryDisplay.State.Offline, SyncChipState.Hidden, canReconnect = true)))
        assertFalse(StrapStatusPill.needsAttention(chip(charge72, SyncChipState.Synced("3m"))))
        assertFalse(StrapStatusPill.needsAttention(chip(HeaderBatteryDisplay.State.Pending, SyncChipState.Hidden)))
    }
}
