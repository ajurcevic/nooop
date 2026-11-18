package com.noop.ui

import com.noop.R
import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.noop.data.DataBackup
import com.noop.data.ImportSummary
import com.noop.ble.PuffinExperiment
import com.noop.ingest.HealthConnectImporter
import com.noop.ingest.HealthConnectWriter
import com.noop.ingest.WhoopCsvImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data Sources — ports the macOS DataSourcesView (Strand/Screens/DataSourcesView.swift)
 * onto the locked Android component system (ScreenScaffold / NoopCard / StatePill /
 * Overline / NoopType / Palette).
 *
 * The macOS screen is built around "bring your history in once, then it's yours": three
 * source cards (WHOOP Export, Apple Health, Live BLE) plus on-device file import. On
 * Android the on-device store is a single Room/SQLite file, and the real, working
 * migration path is whole-store export/import via [DataBackup] (a SAF document the user
 * picks). So this screen keeps the macOS structure but maps each card to what Android
 * actually has:
 *
 *   - WHOOP data    — live counts of the cached "my-whoop" history, plus a working import
 *                     of a WHOOP .zip/.csv export (app.whoop.com → Data Management) via
 *                     [com.noop.ingest.WhoopCsvImporter].
 *   - Health Connect— native Android import (steps/HR/HRV/sleep/SpO₂/weight/workouts) via
 *                     [com.noop.ingest.HealthConnectImporter], gated on runtime permission.
 *   - WHOOP Strap   — the live BLE bond/stream status, straight from the LiveState flow.
 *   - Backup        — Export / Import the whole on-device database through [DataBackup],
 *                     wired to ActivityResult document launchers.
 */
@Composable
fun DataSourcesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by vm.live.collectAsStateWithLifecycle()
    val puffinExperiment = remember { PuffinExperiment.from(context) }
    var strapHrBroadcast by remember { mutableStateOf(puffinExperiment.broadcastHr) }
    val hrBroadcast by vm.hrBroadcast.collectAsStateWithLifecycle()
    val hrBroadcastAdvertising by vm.hrBroadcastAdvertising.collectAsStateWithLifecycle()
    val hrBroadcastSubscribers by vm.hrBroadcastSubscribers.collectAsStateWithLifecycle()
    val hrBroadcastStatus by vm.hrBroadcastStatus.collectAsStateWithLifecycle()
    val hcAutoSync by vm.hcAutoSync.collectAsStateWithLifecycle()
    val hcSyncHours by vm.hcSyncHours.collectAsStateWithLifecycle()
    val hcLastSync by vm.hcLastSync.collectAsStateWithLifecycle()
    val hcWriteback by vm.hcWriteback.collectAsStateWithLifecycle()
    val hcWbStatus by vm.hcWritebackStatus.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences(PuffinExperiment.PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == PuffinExperiment.KEY_BROADCAST_HR) {
                strapHrBroadcast = puffinExperiment.broadcastHr
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var hcReadCategories by remember {
        mutableStateOf(HealthConnectImporter.selectedCategories(context))
    }
    // A background (BLE-path) writeback updates prefs, not the VM's flow — re-read on entry so the
    // status line reflects the latest attempt whenever this screen is opened (#660).
    LaunchedEffect(Unit) { vm.refreshHcWritebackStatus() }

    // Cached-store counts, loaded once from the repo (newest data is fine to recount).
    var whoopDays by remember { mutableStateOf<Int?>(null) }
    var whoopWorkouts by remember { mutableStateOf<Int?>(null) }
    var whoopHasHr by remember { mutableStateOf(false) }
    // Health Connect has its OWN source ("health-connect"), so its card counts only its own data (issue #34).
    var hcDays by remember { mutableStateOf<Int?>(null) }
    var hcWorkouts by remember { mutableStateOf<Int?>(null) }

    // Count-badge refresh, shared by the initial load below and every importer's post-run refresh.
    // PERF: scalar SQL COUNTs (and one LIMIT-1 existence probe), NOT materialized row lists — the old
    // shape loaded every row of every source's history just to call `.size` on it, ~14 full-range
    // reads per screen visit. Workout counts are now exact (the row read was capped at DEFAULT_LIMIT).
    suspend fun refreshCounts() {
        val nowS = System.currentTimeMillis() / 1000
        // #1304/#512: count across the active-strap UNION (active ∪ canonical), so a 2nd strap's data
        // under "whoop-<uuid>" is included instead of silently under-reported. `daysMerged` is Android's
        // merged-history counterpart to Swift `Repository.days`
        // (mergeActivityFileSteps(mergeDaily(imported, computed))), so this
        // matches the iOS badge count, including a strap-only user's computed-only ("-noop") days that an
        // imported-only count would miss. workoutsUnion mirrors Swift's dataVolumeSnapshot workout union.
        whoopDays = vm.repo.daysMerged(vm.activeStrapId).size
        whoopWorkouts = vm.repo.workoutsUnion(vm.activeStrapId, 0L, nowS).size
        whoopHasHr = vm.repo.latestHrSampleTsUnion(vm.activeStrapId) != null
        hcDays = vm.repo.appleDailyCount("health-connect", "0000-01-01", "9999-12-31")
        hcWorkouts = vm.repo.workoutsCount("health-connect", 0L, nowS)
    }

    LaunchedEffect(Unit) { refreshCounts() }

    // Busy flag shared by every importer's Export/Import buttons.
    var busy by remember { mutableStateOf(false) }

    // Run an importer off the main thread, refresh the counts, then toast the result.
    fun runImport(block: suspend () -> ImportSummary) {
        busy = true
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { ImportSummary.failure("Import", it.message ?: "failed") }
            }
            // Mirror the import into the SAME exported strap log the WHOOP path uses (issue #421 parity),
            // so a tester's file import is captured in a shared debug bundle. On success: brand label +
            // per-table COUNTS only (e.g. "dailyMetric=120, sleepSession=88"). On a zero-row/failed import:
            // the brand label + the human reason from the summary. Never a file name, a path, or any health
            // value. Prefixed "Import: " so it's distinguishable from WHOOP / generic-HR lines. The Swift
            // twin logs the same in DataSourcesView's import handlers.
            if (summary.totalRows > 0) {
                val countsText = summary.counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
                vm.ble.externalLog("Import ${summary.source}: $countsText")
            } else {
                vm.ble.externalLog("Import ${summary.source} failed: ${summary.message}")
            }
            // Import & Data Ingest test mode (Test Centre): emit the parser / per-stage / day-delta trace,
            // tagged IMPORT, iff the mode is on. Gated zero-cost when off (one SharedPreferences bool read).
            // The numbers are the SAME per-table counts the summary carries (Room upserts are fire-and-forget,
            // so the persisted count equals the mapped count at this seam); emission changes nothing saved. No
            // file name, path, or health value is in any line. Twin of the macOS DataSourcesView handlers.
            emitImportTrace(context, vm, summary)
            refreshCounts()
            busy = false
            Toast.makeText(context, summary.message, Toast.LENGTH_LONG).show()
        }
    }

    // SAF pickers — the importers auto-detect zip vs csv/xml from the file's content.
    val whoopImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { WhoopCsvImporter.importZip(context, uri, vm.repo) } }

    // Health Connect permission request → import once granted.
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        val selectedPermissions = HealthConnectImporter.permissionsFor(hcReadCategories)
        if (granted.any { it in selectedPermissions }) {
            runImport { HealthConnectImporter.import(context, vm.repo, ProfileStore.from(context).heightCm) }
        } else {
            Toast.makeText(context, "Health Connect access not granted.", Toast.LENGTH_LONG).show()
        }
    }

    val healthConnectAvailable = remember {
        HealthConnectImporter.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    // "Broadcast heart rate": flip the toggle on only AFTER the BLUETOOTH_ADVERTISE (+ CONNECT) runtime
    // permission is granted on Android 12+ — otherwise advertising silently no-ops. On grant (or pre-12,
    // where it's install-time) the VM starts the HR peripheral.
    val requestAdvertise = rememberRequestAdvertise(onGranted = { vm.setHrBroadcast(true) })

    // Import directly if permissions already granted, otherwise request them first.
    fun startHealthConnect() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            // #645: a user who predates the selector has nothing stored. Recover their real scope from
            // what Android already grants BEFORE the checkboxes are read back, or a first visit would
            // show Recovery-only and saving it would lock in the narrowing.
            HealthConnectImporter.migrateSelectionFromGrants(context, granted)
            hcReadCategories = HealthConnectImporter.selectedCategories(context)
            val selectedPermissions = HealthConnectImporter.permissionsFor(hcReadCategories)
            // `any` (not `all`) is deliberate — partial grants are supported (#150). But that alone
            // would never ASK about a permission added in an update, so a newly-read type would come
            // back empty forever (#949). Route through the request once when the set has grown.
            if (granted.any { it in selectedPermissions } &&
                !HealthConnectImporter.hasUnaskedPermissions(context, hcReadCategories)
            ) {
                runImport { HealthConnectImporter.import(context, vm.repo, ProfileStore.from(context).heightCm) }
            } else {
                HealthConnectImporter.markPermissionsAsked(context, hcReadCategories)
                hcPermissionLauncher.launch(selectedPermissions)
            }
        }
    }

    // Writeback (computed metrics → Health Connect): WRITE permissions, requested only when the
    // user opts in. Denial flips the toggle back off so the UI never claims it's writing.
    val hcWritePermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        // #1525: this prompt already included VO2 max, so whatever the answer was, do not ask again
        // through the targeted one-shot below.
        NoopPrefs.setHcVo2MaxAsked(context, true)
        if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) {
            vm.writebackHealthConnectNow()
        } else {
            vm.setHcWriteback(false)
            Toast.makeText(context, "Health Connect write access not granted.", Toast.LENGTH_LONG).show()
        }
    }

    // Write immediately if the write permissions are already granted, otherwise request them first.
    /**
     * #1525: the one-shot ask for the VO2 max write permission, for installs that were set up before it
     * existed. The outcome is deliberately ignored — VO2 max records are written in their own attempt, so
     * a decline costs that metric and nothing else, and blocking the writeback on it would be the very
     * regression keeping VO2 max out of the gate avoids. Either way we mark it asked, so a "no" is
     * respected instead of re-prompted on every sync.
     */
    val hcVo2MaxPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { _ ->
        NoopPrefs.setHcVo2MaxAsked(context, true)
        vm.writebackHealthConnectNow()
    }

    fun startWriteback() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            // Gate on vitals AND exercise perms so a user who enabled writeback before exercise
            // writeback shipped (vitals-only grant) still gets re-prompted for WRITE_EXERCISE/
            // WRITE_DISTANCE — otherwise their workouts silently never reach Health Connect (#412).
            // #1525: VO2 max is ASKED FOR below but deliberately absent from this gate. Adding it here
            // would mean every existing user, who granted the older set, fails `containsAll` and gets
            // re-prompted — and blocks their whole writeback until they accept. Out of the gate, a decline
            // costs only VO2 max, whose records are written in their own attempt.
            if (granted.containsAll(HealthConnectWriter.PERMISSIONS + HealthConnectWriter.EXERCISE_PERMISSIONS)) {
                // #1525: this branch is where an existing install lands — it granted everything that
                // existed at the time, so it never reaches the launcher below and would never be offered
                // VO2 max. Ask exactly once, then write regardless of the answer.
                if (!granted.containsAll(HealthConnectWriter.VO2MAX_PERMISSIONS) &&
                    !NoopPrefs.hcVo2MaxAsked(context)
                ) {
                    hcVo2MaxPermissionLauncher.launch(HealthConnectWriter.VO2MAX_PERMISSIONS)
                } else {
                    vm.writebackHealthConnectNow()
                }
            } else {
                // Request vitals + exercise-session write perms together so GPS workouts can write
                // back too (the launcher-result handler stays keyed on the vital PERMISSIONS, so
                // exercise writeback is opt-in + non-fatal if the user declines it). v1.71 / #412.
                hcWritePermissionLauncher.launch(
                    HealthConnectWriter.PERMISSIONS + HealthConnectWriter.EXERCISE_PERMISSIONS +
                        HealthConnectWriter.VO2MAX_PERMISSIONS,
                )
            }
        }
    }

    // PERF (#707): lazy scaffold — each SourceCard is an unconditional top-level child, so each becomes one
    // `item { }` in the same order. There are no standalone Spacers (the eager column relied on
    // `spacedBy(20.dp)`, which the LazyColumn reproduces), so spacing is byte-identical. Only the on-screen
    // cards now compose + get accessibility-walked on scroll — this list of source cards is long. The
    // confirm dialogs below the scaffold are untouched.
    LazyScreenScaffold(
        title = uiString(R.string.l10n_data_sources_screen_data_sources_5e43d6bb),
        subtitle = "Everything stays on this phone. Bring your history in once, then it's yours.",
    ) {
        // --- WHOOP data (cached history) ---
        item {
        SourceCard(
            title = uiString(R.string.l10n_data_sources_screen_whoop_history_db101974),
            icon = Icons.Filled.MonitorHeart,
            subtitle = "Recovery, strain, sleep and workouts, stored locally. Import a full " +
                "WHOOP data export (.zip) from app.whoop.com → Data Management and it " +
                "backfills your whole history in about a minute. Working now on Android.",
        ) {
            StatePill(
                title = if (whoopHasHr) "Streaming locally" else "No samples yet",
                tone = if (whoopHasHr) StrandTone.Positive else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = whoopDays?.let { "$it days" } ?: "—",
                secondary = whoopWorkouts?.let { "$it workouts stored" } ?: "Counting…",
            )
            BackupButton(
                label = uiString(R.string.l10n_data_sources_screen_import_whoop_export_zip_16f4176b),
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { whoopImportLauncher.launch(arrayOf("*/*")) }
        }
        }


        // --- Health Connect (native Android health data) ---
        item {
        SourceCard(
            title = uiString(R.string.l10n_data_sources_screen_health_connect_be6bca3e),
            icon = Icons.Filled.MonitorHeart,
            subtitle = "Pull steps, heart rate, HRV, sleep, SpO₂, weight and workouts straight from " +
                "Android's Health Connect. No file needed. On-device; it never overwrites richer " +
                "WHOOP data, and writes nothing unless you opt in to sharing back below.",
        ) {
            val hasHc = (hcDays ?: 0) > 0 || (hcWorkouts ?: 0) > 0
            if (hasHc) {
                StatePill(title = uiString(R.string.l10n_data_sources_screen_imported_434eb26f), tone = StrandTone.Accent, showsDot = true)
                CountLine(
                    primary = hcDays?.let { "$it days" } ?: "—",
                    secondary = hcWorkouts?.let { "$it workouts" } ?: "Counting…",
                )
            }
            if (healthConnectAvailable) {
                HealthConnectCategorySelector(
                    selected = hcReadCategories,
                    onSelectionChange = { categories ->
                        hcReadCategories = categories
                        HealthConnectImporter.setSelectedCategories(context, categories)
                    },
                )
                BackupButton(
                    label = uiString(R.string.l10n_data_sources_screen_import_from_health_connect_35d55e21),
                    icon = Icons.Filled.FileUpload,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { startHealthConnect() }

                // Auto-sync: pull new Health Connect data when you open NOOP, if it's been longer than
                // the chosen interval — no manual taps. On-open only (no background worker): it avoids a
                // sensitive background-health permission and is reliable, and opening the app is enough.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(uiString(R.string.l10n_data_sources_screen_auto_sync_periodically_5f3041e8), style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            uiString(R.string.l10n_data_sources_screen_re_pull_new_health_connect_data_3e9c3914),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = hcAutoSync,
                        onCheckedChange = { on ->
                            vm.setHcAutoSync(on)
                            // Ensure permissions (and an immediate first sync) when turning it on.
                            if (on) startHealthConnect()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = uiString(R.string.l10n_data_sources_screen_auto_sync_health_connect_periodically_6f3f4d92)
                        },
                    )
                }
                if (hcAutoSync) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(uiString(R.string.l10n_data_sources_screen_every_3560d90b), style = NoopType.footnote, color = Palette.textSecondary)
                        SegmentedPillControl(
                            items = listOf(6, 12, 24),
                            selection = hcSyncHours,
                            label = { "${it}h" },
                            onSelect = { vm.setHcSyncHours(it) },
                        )
                    }
                    Text(
                        uiString(
                            R.string.l10n_data_sources_screen_last_sync_b793ffab,
                            if (hcLastSync == 0L) "not yet"
                            else DateUtils.getRelativeTimeSpanString(hcLastSync).toString(),
                        ),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }

                // Writeback: the inverse direction. Opt-in, default OFF, computed metrics only.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(uiString(R.string.l10n_data_sources_screen_share_back_to_health_connect_1d578f4a), style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            uiString(R.string.l10n_data_sources_screen_write_the_metrics_noop_computes_from_439940c2),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = hcWriteback,
                        onCheckedChange = { on ->
                            vm.setHcWriteback(on)
                            // Ensure write permissions (and an immediate first write) when turning on.
                            if (on) startWriteback()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = uiString(R.string.l10n_data_sources_screen_share_computed_metrics_back_to_health_c11f5d70)
                        },
                    )
                }
                // #660: surface the last writeback OUTCOME so a silently-failing share (revoked
                // permission, provider error) is visible instead of a healthy-looking toggle. Only
                // while enabled and after at least one attempt (empty status = never tried).
                if (hcWriteback && hcWbStatus.code.isNotEmpty()) {
                    when (hcWbStatus.code) {
                        NoopPrefs.HC_WB_PERMISSION_DENIED -> Text(
                            uiString(R.string.l10n_data_sources_screen_sharing_paused_health_connect_permission_was_e8950315),
                            style = NoopType.footnote,
                            color = Palette.accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { startWriteback() },   // tap re-requests the WRITE permissions
                        )
                        NoopPrefs.HC_WB_REMOTE_ERROR -> Text(
                            uiString(R.string.l10n_data_sources_screen_last_share_didn_t_finish_noop_0d6c27f0),
                            style = NoopType.footnote,
                            color = Palette.accent,
                        )
                        else -> Text(   // HC_WB_OK
                            uiString(R.string.l10n_data_sources_screen_last_shared_6bf8389c, DateUtils.getRelativeTimeSpanString(hcWbStatus.atMs).toString()),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                }
            } else {
                RoadmapNote("Health Connect isn't set up on this device. Install it from Google Play, then return here to import.")
            }
        }
        }

        // --- Broadcast heart rate (NOOP as a standard BLE HR peripheral) ---
        item {
        SourceCard(
            title = uiString(R.string.l10n_data_sources_screen_broadcast_hr_from_this_phone_10e5605c),
            icon = Icons.Filled.MonitorHeart,
            tint = DomainTheme.Effort.color,
            subtitle = "Re-share your live strap heart rate over Bluetooth as a standard heart-rate " +
                "sensor, so a gym treadmill, bike, Zwift, Peloton or any fitness app nearby can read " +
                "it. Works on any WHOOP (4.0 or 5.0/MG) because your phone does the broadcasting. " +
                "If your strap or watch already broadcasts heart rate directly to another device, " +
                "you can leave this off. Local Bluetooth only. Nothing leaves your phone. Off by default.",
        ) {
            if (hrBroadcast) {
                val (label, tone) =
                    if (hrBroadcastAdvertising) "Broadcasting" to StrandTone.Positive
                    else "Starting…" to StrandTone.Warning
                StatePill(title = label, tone = tone, showsDot = true, pulsing = !hrBroadcastAdvertising)
                CountLine(
                    primary = if (hrBroadcastAdvertising) "Standard HR sensor (0x180D)" else "—",
                    secondary = when {
                        hrBroadcastSubscribers > 0 ->
                            "$hrBroadcastSubscribers ${if (hrBroadcastSubscribers == 1) "device" else "devices"} reading"
                        live.heartRate != null -> "Sharing ${live.heartRate} bpm · waiting for a device"
                        else -> "No live heart rate yet · open Live to pair your strap"
                    },
                )
            } else {
                // Parity with the Swift card, which shows an explicit "Off" pill when the toggle is off
                // (DataSourcesView.broadcastHrCard: StatePill("Off", tone: .neutral, showsDot: false)).
                StatePill(title = uiString(R.string.l10n_data_sources_screen_off_e3de5ab0), tone = StrandTone.Neutral, showsDot = false)
            }
            hrBroadcastStatus?.let { note ->
                Text(note, style = NoopType.footnote, color = Palette.statusWarning)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(uiString(R.string.l10n_data_sources_screen_broadcast_hr_from_this_phone_10e5605c), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.l10n_data_sources_screen_acts_as_a_standard_bluetooth_heart_f8d13439),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = hrBroadcast,
                    onCheckedChange = { on ->
                        // Turning ON requests BLUETOOTH_ADVERTISE first (the VM flips on once granted);
                        // turning OFF stops the peripheral immediately.
                        if (on) requestAdvertise() else vm.setHrBroadcast(false)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = uiString(R.string.l10n_data_sources_screen_broadcast_heart_rate_as_a_bluetooth_6a44fdb4)
                    },
                )
            }

            // #573: leaving broadcast on keeps the radio advertising continuously, which drains the
            // battery faster — make that visible and persistent so it isn't left on by accident. Mirrors
            // the Swift broadcast-HR warning (SettingsView).
            if (hrBroadcast) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.SettingsInputAntenna,
                        contentDescription = null,
                        tint = Palette.statusWarning,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        uiString(R.string.l10n_data_sources_screen_broadcast_hr_is_on_your_strap_0ad5368a),
                        style = NoopType.caption,
                        color = Palette.statusWarning,
                    )
                }
            }
        }
        }

        // --- Live WHOOP strap over BLE ---
        item {
        SourceCard(
            title = uiString(R.string.l10n_data_sources_screen_whoop_strap_live_ble_217f7df6),
            icon = Icons.Filled.Bluetooth,
            subtitle = uiString(R.string.data_sources_whoop_live_subtitle),
        ) {
            val (label, tone) = when {
                // encryptedBond, not bonded — see strapStatusTitle. A 5/MG streaming over the open
                // profile has bonded == true with no pairing at all, and after #1635 hello suppression it
                // stays there for good rather than passing through.
                //
                // "Live HR" reads a little wide here: bonded means REACHABLE, not currently streaming. It
                // survives the stream stopping (take the strap off mid-link and it stays true), and the
                // unbonded offload probe is a second way in — it sets bonded on a strap that answered a
                // command without ever having sent an HR reading. Both are the same state: reachable over
                // the open profile, never encrypted.
                live.encryptedBond -> "Bonded, streaming." to StrandTone.Positive
                live.bonded -> "Live HR (not fully paired)" to StrandTone.Warning
                live.connected -> "Connected, pairing…" to StrandTone.Warning
                else -> "Not connected. Open Live to pair." to StrandTone.Critical
            }
            StatePill(title = label, tone = tone, showsDot = true, pulsing = live.connected && !live.bonded)
            val strapBroadcastTitle = uiString(R.string.raw_diag_broadcast_hr)
            val strapBroadcastDescription = uiString(R.string.data_sources_band_broadcast_description)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strapBroadcastTitle, style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        strapBroadcastDescription,
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = strapHrBroadcast,
                    onCheckedChange = { enabled ->
                        strapHrBroadcast = enabled
                        puffinExperiment.broadcastHr = enabled
                        vm.ble.setBroadcastHr(enabled)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = strapBroadcastTitle
                    },
                )
            }
        }
        }

        // Keep the final control clear of the app and system navigation bars on shorter phones.
        item { Spacer(Modifier.height(96.dp)) }

    }

// MARK: - Source card (mirrors the macOS private `card(...)` builder)

@Composable
private fun SourceCard(
    title: String,
    icon: ImageVector,
    subtitle: String,
    tint: Color = Palette.accent,
    content: @Composable () -> Unit,
) {
    // A frosted, domain-tinted card: a tinted source glyph chip + title, the explainer line, then
    // the source's status pill + connect/import action(s). Replaces the old flat surface.
    NoopCard(padding = 18.dp, tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
            }
            Text(subtitle, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

// MARK: - "N days · N workouts stored" footnote line (mirrors the macOS counts line)

@Composable
private fun CountLine(primary: String, secondary: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(primary, style = NoopType.captionNumber, color = Palette.textSecondary)
        Text("  ·  ", style = NoopType.footnote, color = Palette.textTertiary)
        Text(secondary, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun RoadmapNote(text: String) {
    Text(text, style = NoopType.footnote, color = Palette.textTertiary)
}

// MARK: - Backup action button (accent fill)

@Composable
private fun BackupButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Palette.accent,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val ink = if (enabled) tint else tint.copy(alpha = Palette.disabledOpacity)
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, ink.copy(alpha = 0.4f), shape)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = NoopType.headline, color = ink)
    }
}

/**
 * Emit the Import & Data Ingest test-mode trace for a finished import, tagged TestDomain.IMPORT, iff the
 * mode is on. Shared by the Data Sources + Onboarding import flows (both call runImport). Gated zero-cost
 * when off: one SharedPreferences bool read before any line is built. The lines are byte-aligned with the
 * macOS ImportTrace shapes (parser / per-stage / reject / day-delta), built from the ImportSummary the
 * importer already returned. Never a file name, a path, or any health value.
 *
 * HONESTY (the whole point of this mode, tied to the #601/#749/#754 "didn't save" cluster): unlike the
 * Swift store, which returns the summed SQLite changes from each upsert, Room's @Upsert reports no
 * store-write count at this layer. So Android does NOT claim "(all written)" / "(all days persisted)" - it
 * emits rowsIn / daysMapped with rowsOut / daysPersisted marked UNVERIFIED. A line never asserts a save it
 * cannot confirm. REJECTED counts (e.g. skippedSpans - scrubbed/damaged spans, the OPPOSITE of written)
 * are routed through the reject line, never a stage line, matching AppleHealthImport.swift.
 */
internal fun emitImportTrace(
    context: android.content.Context,
    vm: AppViewModel,
    summary: com.noop.data.ImportSummary,
) {
    if (!com.noop.testcentre.TestCentre.from(context).active(com.noop.testcentre.TestDomain.IMPORT)) return
    if (summary.totalRows <= 0) return   // a failed/empty import already logged its reason above
    val kind = com.noop.analytics.ImportTrace.kindWire(summary.source)
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.parserVersionLine(kind, importerVersion = 1),
        com.noop.testcentre.TestDomain.IMPORT,
    )
    // Reject keys are NOT writes: they are rows/spans the import dropped (the opposite of "written"), so
    // they must never become a stage line. skippedSpans is the only one an Android importer emits today.
    val skippedSpans = summary.counts["skippedSpans"] ?: 0
    for ((rawKey, count) in summary.counts) {
        if (rawKey == "skippedSpans") continue   // routed through the reject line below, not as a stage
        val category = com.noop.analytics.ImportTrace.categoryWire(summary.source, rawKey)
        // rowsOut is UNVERIFIED on Android (Room reports no store-write count); never claim "(all written)".
        vm.ble.externalLog(
            com.noop.analytics.ImportTrace.stageLineUnverified(category, rowsIn = count),
            com.noop.testcentre.TestDomain.IMPORT,
        )
    }
    // #1617: which metric COLUMNS the file actually carried. rowsOut stays unverified here because Room
    // reports no write count, but this is known at PARSE time and so is exact on both platforms — and it
    // separates "the store never got it" from "the export never had it", which the stage lines alone
    // cannot. Emitted only when the importer produced daily rows.
    // Emitted generically here for any summary carrying coverage, while the Swift twin emits it inside
    // WhoopImporter — so a NEW importer that starts filling columnCoverage would produce this line on
    // Android and silently not on Swift. Give it the Swift twin at the same time.
    if (summary.columnCoverage.isNotEmpty()) {
        vm.ble.externalLog(
            com.noop.analytics.ImportTrace.columnCoverageLine(
                // The literal, not categoryWire: that function maps RAW TABLE KEYS to wire categories
                // ("dailyMetric" -> "cycles"), so handing it a category already in wire form only works
                // through its `else -> rawKey` fallthrough, and would break the day anyone adds a
                // "cycles" branch. The Swift twin passes the same literal.
                stage = "cycles",
                rows = summary.columnCoverageRows,
                counts = summary.columnCoverage,
            ),
            com.noop.testcentre.TestDomain.IMPORT,
        )
    }
    // The reject line mirrors AppleHealthImport.swift: the app map drops nothing further here, so
    // droppedRows = 0; skippedSpans carries the tolerant-import scrubbed-span count (0 on non-Apple).
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.rejectLine(droppedRows = 0, skippedSpans = skippedSpans),
        com.noop.testcentre.TestDomain.IMPORT,
    )
    // Day delta: pick the source's day-keyed table (Apple -> appleDaily, WHOOP/others -> dailyMetric) so a
    // real Apple import reports the right day count, and label the stage with the Swift category vocabulary.
    val dayKey = if (summary.counts.containsKey("appleDaily")) "appleDaily" else "dailyMetric"
    val days = summary.counts[dayKey] ?: summary.counts["days"] ?: 0
    val dayCategory = com.noop.analytics.ImportTrace.categoryWire(summary.source, dayKey)
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.dayDeltaLineUnverified(dayCategory, daysMapped = days),
        com.noop.testcentre.TestDomain.IMPORT,
    )
}
