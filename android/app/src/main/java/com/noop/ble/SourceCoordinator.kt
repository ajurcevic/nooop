package com.noop.ble

import android.content.Context
import android.util.Log
import com.noop.data.DeviceRegistry
import com.noop.data.PairedDeviceRow
import com.noop.data.WhoopRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the WHOOP connection pointed at the ACTIVE registry device, driven by [DeviceRegistry]'s active id.
 *
 * This fork supports WHOOP straps only (WHOOP 5.0 / MG first, WHOOP 4.0 still works). The coordinator is a
 * deliberate NO-OP whenever the active device is the same WHOOP it already targets, which is the default
 * state for a single-strap install. It acts only on a WHOOP -> WHOOP switch (multi-WHOOP, MW-2/MW-3): drop
 * the current link, re-point the preferred address + sample deviceId, and reconnect. A non-WHOOP registry
 * row (left over from an older build that supported other brands) is refused rather than started: the
 * switch reports failure and the WHOOP stays the live source.
 *
 * It never imports or references [WhoopBleClient] internals: the WHOOP start/stop are injected closures from
 * the composition root, so the coordinator stays decoupled from the BLE client.
 */
class SourceCoordinator(
    /** Android [Context] for the strap source's own scanner/GATT. Non-null in production (set at the
     *  composition root); nullable ONLY so the registry-driven paths (e.g. identity adoption) are
     *  exercisable on the plain JVM without Android — required at the single strap-path use site. */
    private val context: Context?,
    private val registry: DeviceRegistry,
    /** The store the strap source persists into. Non-null in production; nullable for the same JVM-test
     *  reason as [context] — required at the single strap-path use site, untouched by the WHOOP/adoption
     *  paths. */
    private val repository: WhoopRepository?,
    /** Push a strap's live HR/R-R into whatever the UI observes (e.g. `ble::publishExternalLiveHr`). */
    private val liveSink: (hr: Int, rr: List<Int>) -> Unit,
    /** Re-trigger WHOOP's EXISTING scan/connect entry point (e.g. `AppViewModel.connect`). */
    private val startWhoop: () -> Unit,
    /** Pause WHOOP via its EXISTING teardown (e.g. `AppViewModel.disconnect` → `ble.disconnect`). */
    private val stopWhoop: () -> Unit,
    /**
     * Pin the WHOOP connection to ONE strap by its persisted `peripheralId` (the MAC address), or null to
     * clear the pin back to "connect to the first WHOOP found". Wraps [WhoopBleClient.preferredAddress].
     * Called only on a WHOOP transition; nil on the legacy "my-whoop" path (unchanged). Default no-op keeps
     * the existing `SourceCoordinator(...)` call sites compiling unchanged. (MW-2/MW-3)
     */
    private val setWhoopPreferredAddress: (String?) -> Unit = {},
    /**
     * Re-point which device id live WHOOP samples store under. Wraps [WhoopBleClient.setActiveDeviceId].
     * Called ONLY when the active WHOOP is NOT the seeded "my-whoop" — the single-WHOOP path never invokes
     * it, so the id stays "my-whoop". Default no-op keeps existing call sites compiling unchanged. (MW-3)
     */
    private val setWhoopActiveDeviceId: (String) -> Unit = {},
    /** Background scope for the suspend registry reads + persist. SupervisorJob keeps one failure from
     *  cancelling the others; IO keeps DB work off the main thread. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Diagnostic sink for the multi-WHOOP identity-adoption "different strap connected" notice — the
     *  Android analogue of Swift's `live.append(log:)`. Defaults to logcat; tests inject a capturing
     *  closure to assert the wording. Inert on the single-WHOOP path (the message only fires on a
     *  registered-but-mismatched strap). */
    private val log: (String) -> Unit = { Log.i("SourceCoordinator", it) },
    /** Diagnostic sink wired at the composition root to [WhoopBleClient.externalLog], so a refused device
     *  switch lands in the SAME in-app strap log the user exports (issue #421). Default no-op keeps plain
     *  JVM call sites compiling. */
    private val straplog: (String) -> Unit = {},
    /** Retained for call-site compatibility; nothing feeds it now that only WHOOP straps are supported. */
    @Suppress("unused") private val batterySink: (Int) -> Unit = {},
    /** Initial registry id for process composition. Nullable keeps plain JVM harnesses honest until their
     * first successful reconcile; production injects its already-resolved startup id. */
    initialActiveDeviceId: String? = null,
) {

    /** The single success-only active-id publication for UI and internal identity-adoption consumers. */
    private val _activeDeviceId = MutableStateFlow(initialActiveDeviceId)
    val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()

    /** The WHOOP registry id we last pointed the connection at, so a WHOOP→WHOOP switch is detected and a
     *  repeat activation of the SAME WHOOP is a no-op. null until the first WHOOP activation. (MW-3) */
    private var activeWhoopId: String? = null
    /** The last active id we reconciled, so a repeated [onActiveDeviceChanged] for the same id is a no-op
     *  (mirrors Swift's `removeDuplicates()` on the published active id). */
    private var lastSeenId: String? = null
    /** The address of the strap the WHOOP link is CURRENTLY connected to, learned from
     *  [connectedPeripheralChanged]. Lets a WHOOP→WHOOP make-active adopt IN PLACE when the newly-activated
     *  row is the same physical strap (#74 keep): a stop/start churn there would drop the live link and
     *  reconnect through the scan path. Cleared on disconnect (null address). */
    private var connectedWhoopAddress: String? = null

    /** Serializes [reconcile] so two device switches — or [start] racing [onActiveDeviceChanged] — can't
     *  interleave on the multi-threaded [scope] (Dispatchers.IO is a pool) and leak a half-torn-down live
     *  source. The Swift twin gets this free from `@MainActor`; on Android we serialize the state machine
     *  explicitly. The persist launches stay OUTSIDE this lock (they touch no coordinator state). (ryanbr, #1031) */
    private val reconcileLock = Mutex()

    /**
     * Reconcile once against the CURRENT active id (launch). For a single-WHOOP install this resolves to
     * the WHOOP and is a pure no-op, so the existing WHOOP startup is untouched.
     */
    fun start() {
        scope.launch {
            val id = registry.activeDeviceId() ?: WhoopBleClient.DEFAULT_DEVICE_ID
            reconcileActiveDevice(id)
        }
    }

    /**
     * Called by the app after a registry mutation that can change the active device (Devices-screen
     * setActive). Resolves the device for [id] and reconciles which live source runs. Idempotent: a
     * repeated call for the same id is dropped (the `removeDuplicates()` equivalent).
     */
    fun onActiveDeviceChanged(id: String) {
        scope.launch { reconcileActiveDevice(id) }
    }

    /**
     * Await the serialized source switch and report its completed outcome. Callers which publish active
     * source state must use this API so they never advertise an id whose activation later failed.
     * Re-selecting the already reconciled id is a successful, idempotent completion.
     */
    suspend fun reconcileActiveDevice(id: String): Boolean =
        reconcileLock.withLock {
            reconcile(id).also { success ->
                if (success) _activeDeviceId.value = id
            }
        }

    /** Stamp the row belonging to the strap that just DISCONNECTED, resolved by the identity it adopted
     *  rather than by whatever is active now. On a multi-WHOOP install those are not the same row: make
     *  another strap active while this one is live, and stamping "the active row" would record a sighting of
     *  a strap that was never connected — the same mis-mapping the peripheralId guard below exists to
     *  prevent. An address no row has adopted stamps nothing.
     *
     *  Matched the way the connect path below matches — `ignoreCase` — and NOT through
     *  [DeviceRegistry.deviceForPeripheralId], whose `WHERE peripheralId = ?` is case-SENSITIVE in SQLite.
     *  A stored address differing only in case is the same strap to the guard below, so resolving it more
     *  strictly here would adopt on connect and then silently stamp nothing on disconnect — the same
     *  quiet staleness this whole change exists to remove. The Swift twin keeps its exact lookup because
     *  its connect path compares exactly too; each side matches ITS OWN adopt rule. (#1527) */
    private suspend fun touchLastSeenForStrap(address: String) {
        val row = registry.all().firstOrNull { it.peripheralId.equals(address, ignoreCase = true) } ?: return
        registry.touchLastSeen(row.id)
    }

    /**
     * The BLE engine connected to a WHOOP strap at [address] (null on disconnect). Persist that stable
     * identity onto the CURRENTLY ACTIVE device when it's a WHOOP and hasn't adopted one yet — so the
     * legacy "my-whoop" learns its strap's address on first connect, and a freshly-paired WHOOP confirms
     * its identity. Faithful twin of macOS `SourceCoordinator.connectedPeripheralChanged(to:)` (the sink
     * fed by `BLEManager.connectedPeripheralUUID`), INCLUDING the different-strap guard.
     *
     * Guards (so this never corrupts the registry):
     *   • null address (a disconnect/never-connected republish) → ignore.
     *   • the active device is NOT a WHOOP (a generic strap is active) → ignore; this connection isn't ours.
     *   • the active WHOOP already has a DIFFERENT non-null peripheralId → a different strap connected; LOG
     *     it and do NOT clobber the stored identity (would mis-map another strap's samples onto this row).
     *   • it already matches → nothing to write.
     */
    fun connectedPeripheralChanged(address: String?) {
        // Track the live strap's address for the WHOOP->WHOOP adopt-in-place skip (#74). A null address is a
        // disconnect/never-connected republish: clear it so a later make-active can't wrongly match a stale
        // link, then fall through to the existing ignore.
        val previouslyConnectedTo = connectedWhoopAddress
        connectedWhoopAddress = address
        if (address == null) {
            // Disconnect edge. The strap was in hand right up to this instant, so stamp it NOW rather than
            // leaving the connect-time value: after a ten-hour overnight session that would have the card
            // reading "Last seen 10 h ago" the moment the link dropped. Only when we were actually
            // connected — a null republish with no prior link is not a sighting. (#1527)
            if (previouslyConnectedTo != null) {
                scope.launch { runCatching { touchLastSeenForStrap(previouslyConnectedTo) } }
            }
            return
        }
        scope.launch {
            val activeId = registry.activeDeviceId() ?: return@launch
            val devices = registry.all()
            val row = devices.firstOrNull { it.id == activeId }
            if (!isWhoop(activeId, devices) || row == null) return@launch

            val existing = row.peripheralId
            when {
                existing == null -> {
                    // First connect for this WHOOP row → adopt the strap's stable identity (its address).
                    registry.setPeripheralId(activeId, address)
                    registry.touchLastSeen(activeId)
                }
                existing.equals(address, ignoreCase = true) -> {
                    // Already adopted this exact strap → only the sighting is new. (#1527)
                    registry.touchLastSeen(activeId)
                }
                else ->
                    // A DIFFERENT strap connected under this WHOOP row. Never silently overwrite — that would
                    // mis-map another physical strap's samples onto this device. Log and leave the stored id.
                    log(
                        "Multi-WHOOP: active device $activeId is registered to strap $existing but " +
                            "$address connected — not overwriting.",
                    )
            }
        }
    }

    private suspend fun reconcile(id: String): Boolean {
        if (id == lastSeenId) return true
        lastSeenId = id
        // CONTAIN every device-switch failure here. reconcile is the single entry point for both
        // start() (launch, against the PERSISTED active id) and onActiveDeviceChanged(), and it runs
        // inside a bare `scope.launch {}` — a SupervisorJob does NOT stop an uncaught throw from
        // killing the process, so before this guard a throw while activating a generic strap crashed
        // the app, and because the active id is persisted it crash-LOOPED on every launch (#421
        // regression: making a Polar H10 active bricked the app). A strap switch must never crash the
        // app. We log the exception into the EXPORTABLE strap log too, so the next shared log reveals
        // the exact underlying throw, and reset lastSeenId so the user can retry after switching away.
        return try {
            val devices = registry.all()
            if (isWhoop(id, devices)) {
                switchToWhoop(id, devices)
                true
            } else {
                // A non-WHOOP row can only come from an older build that supported other brands. There is
                // no driver for it any more, so refuse the switch and keep the WHOOP as the live source.
                lastSeenId = null
                straplog("Device '$id' is not a WHOOP strap; only WHOOP straps are supported. " +
                    "Staying on the current WHOOP.")
                false
            }
        } catch (t: Throwable) {
            lastSeenId = null
            log("SourceCoordinator: device switch to '$id' failed: ${t.javaClass.simpleName}: ${t.message}")
            straplog("Activating this device failed (${t.javaClass.simpleName}: ${t.message}) - " +
                "staying on the previous source. Please share this log so we can fix it.")
            false
        }
    }

    /**
     * Active device is a WHOOP ([id]). Churn-guarded sub-cases:
     *   • Already streaming this exact WHOOP → pure no-op (the dormant default; the single-WHOOP launch
     *     lands here and touches nothing but the initial preferred-address).
     *   • The SAME physical strap under another row → adopt in place, no reconnect (#74).
     *   • A DIFFERENT WHOOP → drop the current link, re-point (preferred address + deviceId), reconnect.
     */
    private fun switchToWhoop(id: String, devices: List<PairedDeviceRow>) {
        // Already streaming this exact WHOOP → nothing to do.
        if (activeWhoopId == id) return

        val peripheralId = devices.firstOrNull { it.id == id }?.peripheralId

        when {
            activeWhoopId == null -> {
                // First WHOOP activation of the session (the normal launch path). Set the targeting so the
                // existing WHOOP flow — kicked off elsewhere on launch — uses it. For the seeded "my-whoop"
                // (peripheralId null, id "my-whoop") this is setWhoopPreferredAddress(null) and NO
                // setActiveDeviceId / NO scan / NO disconnect: byte-for-byte today's behaviour.
                pointWhoop(id, peripheralId)
            }
            peripheralId != null && peripheralId.equals(connectedWhoopAddress, ignoreCase = true) -> {
                // WHOOP → the SAME physical strap (make-active on the row we're already connected to, e.g. the
                // pick-same-strap Add flow): adopt IN PLACE. A stop/start churn here would drop the #74-kept
                // live link and force a scan reconnect (wrong-family default + OS-bond status=133). Just
                // re-point the targeting so samples land under this id; the connection is untouched.
                pointWhoop(id, peripheralId)
            }
            else -> {
                // WHOOP → a DIFFERENT WHOOP: drop the current link, re-point, and reconnect.
                stopWhoop()
                pointWhoop(id, peripheralId)
                startWhoop()
            }
        }
    }

    /**
     * Apply the WHOOP targeting for the now-active WHOOP [id]. Always sets the preferred address (null for
     * the legacy "my-whoop" → connect to any WHOOP, unchanged). Re-points the sample deviceId ONLY for a
     * non-legacy WHOOP — the seeded "my-whoop" keeps the bootstrap-set id, so the single-WHOOP path never
     * calls setActiveDeviceId. Records [activeWhoopId] for future change detection. Mirrors macOS
     * `pointWhoop`.
     */
    private fun pointWhoop(id: String, peripheralId: String?) {
        setWhoopPreferredAddress(peripheralId)
        if (id != WhoopBleClient.DEFAULT_DEVICE_ID) {
            setWhoopActiveDeviceId(id)
        }
        activeWhoopId = id
    }

    companion object {
        /**
         * Classify a device id as WHOOP vs a generic strap. WHOOP if the id is the canonical "my-whoop",
         * the registry row's `brand` is "WHOOP" (case-insensitive), OR the id is unknown — unknown ids
         * default to WHOOP so the coordinator stays dormant rather than ever stealing the WHOOP's BLE.
         * Mirrors Swift `SourceCoordinator.isWhoop`.
         */
        fun isWhoop(id: String, devices: List<PairedDeviceRow>): Boolean {
            if (id == WhoopBleClient.DEFAULT_DEVICE_ID) return true
            val device = devices.firstOrNull { it.id == id } ?: return true
            return isWhoop(device)
        }

        /**
         * A device is WHOOP when its id is "my-whoop" or its brand is "WHOOP" (the seeded row's brand).
         *
         * #1881 gave the BLE client the same question to answer, so the rule now lives in ONE place
         * ([SourceIdentity]) and this delegates. Two spellings of "is this a WHOOP" that could disagree is
         * precisely how a strap's samples end up filed under a ring.
         */
        fun isWhoop(device: PairedDeviceRow): Boolean = SourceIdentity.isWhoop(device)
    }
}
