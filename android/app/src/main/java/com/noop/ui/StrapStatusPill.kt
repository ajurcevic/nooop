package com.noop.ui

/**
 * What the Today header's battery chip says: the active device's charge and its sync state. The chip sits
 * in the header's control row as a battery glyph and a percentage; words appear only when the state needs
 * attention (syncing, not connected, looking for the strap). It replaces the separate battery ring and
 * sync chip, which together took most of the header's width from the day title.
 *
 * Pure, so the truth table pins with no strap and no BLE. The composable maps the result to strings.
 * The battery half reuses [HeaderBatteryDisplay] and the sync half reuses [SyncChipState], so the chip
 * cannot disagree with either decision. [resolve] decides the full state (it still feeds TalkBack), and
 * [chipText] decides the few words the chip shows.
 */
object StrapStatusPill {

    /** The leading half: whose charge, and how much. */
    sealed class Battery {
        /** Nothing to say about a device (no active strap, and no ring reading). The chip drops this half. */
        data object None : Battery()
        /** The strap is active but not linked. No stale percentage. */
        data object Offline : Battery()
        /** Linked, no reading yet: the device name with no number. */
        data object Pending : Battery()
        data class Charge(val pct: Int, val isRing: Boolean, val level: Level) : Battery()
    }

    /** Charge bands, matching the thresholds the old battery ring used. */
    enum class Level { Ok, Low, Critical }

    /** The trailing half. */
    sealed class Status {
        data object None : Status()
        data object CatchingUp : Status()
        data class Synced(val agoText: String) : Status()
        data object Live : Status()
        /** Offline, a scan is running. */
        data object Looking : Status()
        /** Offline, and tapping the chip starts a scan. */
        data object TapToReconnect : Status()
    }

    /** What a tap does. */
    enum class Tap { OpenDevices, Reconnect }

    data class Model(val battery: Battery, val status: Status, val tap: Tap)

    /**
     * Null when there is nothing to say (no device to describe and no sync state), so the header
     * draws nothing rather than an empty capsule.
     *
     * Offline wins the trailing half: a "synced 3m ago" beside "Strap offline" answers the wrong question,
     * and the reconnect hint is the useful thing to show. [canReconnect] is whether the caller has a scan
     * action to offer; without one the chip falls back to the sync state and opens Devices.
     */
    fun resolve(
        battery: HeaderBatteryDisplay.State,
        sync: SyncChipState,
        canReconnect: Boolean,
        scanning: Boolean,
    ): Model? {
        val lead = when (battery) {
            HeaderBatteryDisplay.State.NotActiveDevice -> Battery.None
            HeaderBatteryDisplay.State.Offline -> Battery.Offline
            HeaderBatteryDisplay.State.Pending -> Battery.Pending
            is HeaderBatteryDisplay.State.Charge -> {
                val pct = battery.pct.coerceIn(0.0, 100.0)
                val level = when {
                    pct < 15 -> Level.Critical
                    pct < 35 -> Level.Low
                    else -> Level.Ok
                }
                Battery.Charge(Math.round(pct).toInt(), battery.isRing, level)
            }
        }
        val offline = lead == Battery.Offline
        val status = when {
            offline && scanning -> Status.Looking
            offline && canReconnect -> Status.TapToReconnect
            else -> when (sync) {
                is SyncChipState.Syncing -> Status.CatchingUp
                is SyncChipState.Synced -> Status.Synced(sync.agoText)
                SyncChipState.ExperimentalLive -> Status.Live
                SyncChipState.Hidden -> Status.None
            }
        }
        if (lead == Battery.None && status == Status.None) return null
        val tap = if (offline && canReconnect && !scanning) Tap.Reconnect else Tap.OpenDevices
        return Model(lead, status, tap)
    }

    /** The visible text of the header battery chip. Only [Percent] is a number; the rest are words. */
    sealed class ChipText {
        /** Glyph only (a pending reading, or a sync state that needs no words). */
        data object None : ChipText()
        data class Percent(val pct: Int) : ChipText()
        data object Syncing : ChipText()
        data object NotConnected : ChipText()
        data object Looking : ChipText()
    }

    /**
     * What the chip spells out for [model]. Looking wins (a scan is running), then an offline strap reads
     * as not connected whatever its sync age, then a running offload reads as syncing, and otherwise the
     * chip shows the charge. A settled "synced 3m ago" or "live" needs no words, so it is left to TalkBack.
     */
    fun chipText(model: Model): ChipText = when {
        model.status == Status.Looking -> ChipText.Looking
        model.battery == Battery.Offline -> ChipText.NotConnected
        model.status == Status.CatchingUp -> ChipText.Syncing
        model.battery is Battery.Charge -> ChipText.Percent(model.battery.pct)
        else -> ChipText.None
    }

    /** True when the chip shows words because the state needs a look (syncing, not connected, looking). */
    fun needsAttention(model: Model): Boolean = when (chipText(model)) {
        ChipText.Syncing, ChipText.NotConnected, ChipText.Looking -> true
        ChipText.None, is ChipText.Percent -> false
    }

    /** False when the chip would be an empty capsule: no device glyph and no words. */
    fun showsChip(model: Model): Boolean = model.battery != Battery.None || chipText(model) != ChipText.None
}
