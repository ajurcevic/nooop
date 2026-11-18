package com.noop.ui

/**
 * What the Today header's single strap pill says: the active device's charge and its sync state, joined
 * into one line ("Strap 11% · synced 3m ago"). It replaces the separate battery ring and sync chip, which
 * together took most of the header's width from the day title.
 *
 * Pure, so the truth table pins with no strap and no BLE. The composable maps the result to strings.
 * The battery half reuses [HeaderBatteryDisplay] and the sync half reuses [SyncChipState], so the pill
 * cannot disagree with either decision.
 */
object StrapStatusPill {

    /** The leading half: whose charge, and how much. */
    sealed class Battery {
        /** Nothing to say about a device (no active strap, and no ring reading). The pill drops this half. */
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
        /** Offline, and tapping the pill starts a scan. */
        data object TapToReconnect : Status()
    }

    /** What a tap does. */
    enum class Tap { OpenDevices, Reconnect }

    data class Model(val battery: Battery, val status: Status, val tap: Tap)

    /**
     * Null when the pill has nothing to say (no device to describe and no sync state), so the header
     * draws nothing rather than an empty capsule.
     *
     * Offline wins the trailing half: a "synced 3m ago" beside "Strap offline" answers the wrong question,
     * and the reconnect hint is the useful thing to show. [canReconnect] is whether the caller has a scan
     * action to offer; without one the pill falls back to the sync state and opens Devices.
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
}
