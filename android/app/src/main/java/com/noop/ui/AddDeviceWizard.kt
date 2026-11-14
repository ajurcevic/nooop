package com.noop.ui

import com.noop.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.data.WhoopLiveCapabilities
import kotlinx.coroutines.launch

// MARK: - Add a device - guided wizard (MW-4)
//
// This fork targets the WHOOP 5.0 / MG strap (WHOOP 4.0 stays supported). The wizard asks which WHOOP
// family first, gives family-specific prep guidance, then runs the WHOOP present-scan
// ([AppViewModel.presentWhoopScan]) targeted at that family. It lists nearby straps from
// [AppViewModel.discoveredWhoops] (a present-only mode that never auto-connects).
//
// Registration goes through [AppViewModel.registerDevice] -> DeviceRegistry; the SourceCoordinator reacts
// to the active-device change and pins the WHOOP connection. The wizard never touches the BLE client
// directly - only the AppViewModel pass-throughs. Renders cleanly with nothing nearby (the type picker,
// every prep step, and the searching/empty pick state all need no hardware). US English throughout.

/** Which WHOOP family the user is adding. Drives the prep copy AND which family the scan targets. */
private enum class DeviceType {
    Whoop5MG, Whoop4;

    val isWhoop: Boolean get() = true
    val whoopModel: WhoopModel
        get() = when (this) {
            Whoop4 -> WhoopModel.WHOOP4
            Whoop5MG -> WhoopModel.WHOOP5_MG
        }

    val title: String
        get() = when (this) {
            Whoop5MG -> "WHOOP 5.0 / MG"
            Whoop4 -> "WHOOP 4.0"
        }
}

private enum class WizardStep { Type, Prep, Pick, Confirm }

@Composable
fun AddDeviceWizard(
    viewModel: AppViewModel,
    onClose: () -> Unit,
    /** Kept for call-site compatibility; the WHOOP-only wizard has no file-import lane of its own. */
    @Suppress("UNUSED_PARAMETER") onUseFileImport: () -> Unit = onClose,
) {
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(WizardStep.Type) }
    var type by remember { mutableStateOf<DeviceType?>(null) }

    var pickedWhoop by remember { mutableStateOf<WhoopBleClient.DiscoveredWhoop?>(null) }

    var nameDraft by remember { mutableStateOf("") }
    var askMakeActive by remember { mutableStateOf(false) }

    fun startScan(t: DeviceType) {
        viewModel.presentWhoopScan(t.whoopModel)
    }

    fun stopAllScans() {
        viewModel.stopWhoopScan()
    }

    // Belt-and-braces: stop the scan whenever the wizard leaves composition.
    DisposableEffect(Unit) { onDispose { stopAllScans() } }

    fun goBack() {
        when (step) {
            WizardStep.Type -> Unit
            WizardStep.Prep -> step = WizardStep.Type
            WizardStep.Pick -> { stopAllScans(); step = WizardStep.Prep }
            WizardStep.Confirm -> {
                // Re-enter the pick step and restart its scan so the user can choose a different strap.
                type?.let { startScan(it) }
                pickedWhoop = null
                step = WizardStep.Pick
            }
        }
    }

    val confirmAdvertisedName = pickedWhoop?.name?.takeIf { it.isNotBlank() } ?: (type?.title ?: "Device")
    val confirmName = nameDraft.trim().ifEmpty { confirmAdvertisedName }
    val confirmBrand = "WHOOP"
    val confirmRssi = pickedWhoop?.rssi ?: -70

    fun finishAdd(makeActive: Boolean) {
        stopAllScans()
        val now = System.currentTimeMillis() / 1000
        val pw = pickedWhoop
        val t = type
        if (pw == null || t == null) { onClose(); return }
        // Honest live capability set (no calibrated SpO2 % - import-only; #548); id namespaced by
        // address; model "4.0" / "5.0 MG". Steps only on 5.0/MG.
        val modelLabel = if (t.whoopModel == WhoopModel.WHOOP4) "4.0" else "5.0 MG"
        val device = PairedDeviceRow(
            id = "whoop-${pw.address}",
            brand = "WHOOP",
            model = modelLabel,
            nickname = confirmName,
            peripheralId = pw.address,
            sourceKind = SourceKind.liveBLE.name,
            capabilities = WhoopLiveCapabilities.encoded(modelLabel),
            status = DeviceStatus.paired.name,
            addedAt = now,
            lastSeenAt = now,
        )
        scope.launch { viewModel.registerDevice(device, makeActive = makeActive) }
        onClose()
    }

    AlertDialog(
        onDismissRequest = { stopAllScans(); onClose() },
        containerColor = Palette.surfaceOverlay,
        title = {
            val hTitle = headerTitle(step, type)
            val hSub = headerSubtitle(step)
            // Back is offered on every step except the very first (the type list).
            val showBack = step != WizardStep.Type
            Row(verticalAlignment = Alignment.Top) {
                if (showBack) {
                    IconButton(onClick = { goBack() }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = uiString(R.string.l10n_add_device_wizard_back_b52b36b7),
                            tint = Palette.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(hTitle, style = NoopType.title2, color = Palette.textPrimary)
                    hSub?.let {
                        Text(it, style = NoopType.caption, color = Palette.textTertiary)
                    }
                }
                IconButton(onClick = { stopAllScans(); onClose() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = uiString(R.string.l10n_add_device_wizard_close_bbfa773e), tint = Palette.textTertiary, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            // Scrollable so no step is ever cut off under large font scaling or on short displays (#897).
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (step) {
                    WizardStep.Type -> TypeStep(onPick = { t ->
                        type = t; nameDraft = ""
                        step = WizardStep.Prep
                    })
                    WizardStep.Prep -> type?.let { t ->
                        PrepStep(t, onScan = { startScan(t); step = WizardStep.Pick })
                    }
                    WizardStep.Pick -> type?.let { t ->
                        WhoopPickStep(
                            viewModel = viewModel,
                            onSelect = { strap ->
                                pickedWhoop = strap
                                nameDraft = strap.name?.takeIf { it.isNotBlank() } ?: t.title
                                viewModel.stopWhoopScan()
                                step = WizardStep.Confirm
                            },
                            onRescan = { viewModel.presentWhoopScan(t.whoopModel) },
                        )
                    }
                    WizardStep.Confirm -> ConfirmStep(
                        advertisedName = confirmAdvertisedName,
                        brand = confirmBrand,
                        rssi = confirmRssi,
                        name = nameDraft,
                        onName = { nameDraft = it },
                        onAdd = { askMakeActive = true },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )

    // After adding, offer to make the new device active.
    if (askMakeActive) {
        AlertDialog(
            onDismissRequest = { askMakeActive = false; finishAdd(makeActive = false) },
            containerColor = Palette.surfaceOverlay,
            title = { Text(uiString(R.string.l10n_add_device_wizard_make_this_your_active_device_b425485c), style = NoopType.title2, color = Palette.textPrimary) },
            text = {
                Text(
                    uiString(R.string.l10n_add_device_wizard_make_confirmname_your_active_device_now_90ceb73e, confirmName),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { askMakeActive = false; finishAdd(makeActive = true) }) {
                    Text(uiString(R.string.l10n_add_device_wizard_make_active_75690bb8), style = NoopType.body, color = Palette.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { askMakeActive = false; finishAdd(makeActive = false) }) {
                    Text(uiString(R.string.l10n_add_device_wizard_not_now_e4571490), style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
    }
}

private fun headerTitle(step: WizardStep, type: DeviceType?): String = when (step) {
    WizardStep.Type -> "Add a device"
    WizardStep.Prep -> type?.title ?: "Add a device"
    WizardStep.Pick -> "Pick your device"
    WizardStep.Confirm -> "Name & confirm"
}

private fun headerSubtitle(step: WizardStep): String? = when (step) {
    WizardStep.Type -> "What are you adding?"
    WizardStep.Prep -> "Get it ready, then scan."
    WizardStep.Pick -> "Tap the one that's yours."
    WizardStep.Confirm -> null
}

// MARK: - Step 1 - type picker

@Composable
private fun TypeStep(onPick: (DeviceType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TypeRow(Icons.Filled.Watch, DeviceType.Whoop5MG.title, "Newer WHOOP band with live data and history sync") {
            onPick(DeviceType.Whoop5MG)
        }
        TypeRow(Icons.Filled.Watch, DeviceType.Whoop4.title, "Previous-generation WHOOP band") {
            onPick(DeviceType.Whoop4)
        }
        WhoopFirstNote()
    }
}

@Composable
private fun TypeRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .frostedCardSurface(cornerRadius = 14.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = uiString(R.string.l10n_add_device_wizard_title_subtitle_8d9004e8, title, subtitle) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = NoopType.headline, color = Palette.textPrimary)
            Text(subtitle, style = NoopType.caption, color = Palette.textTertiary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun WhoopFirstNote() {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
        Text(
            uiString(R.string.l10n_add_device_wizard_whoop_is_noop_s_primary_fully_ac6fa33b),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
    }
}

/**
 * The one-phone pairing warning shown before pairing a WHOOP strap. A WHOOP band bonds to a single
 * device/app at a time, so connecting it to NOOP means it won't stream to the official WHOOP app at the
 * same time (and vice versa). Honest + reversible: re-pairing in the other app hands the strap back. No
 * em-dashes. Mirrors the iOS one-phone warning card so all platforms say the same thing.
 */
@Composable
private fun OnePhoneWarningCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.statusWarning.copy(alpha = 0.10f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.PhonelinkErase,
            contentDescription = null,
            tint = Palette.statusWarning,
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                uiString(R.string.l10n_add_device_wizard_one_phone_at_a_time_cff4ff44),
                style = NoopType.headline,
                color = Palette.statusWarning,
            )
            Text(
                uiString(R.string.l10n_add_device_wizard_a_whoop_strap_bonds_to_a_d39bdf4a),
                style = NoopType.footnote,
                color = Palette.statusWarning,
            )
        }
    }
}

// MARK: - Step 2 - type-specific prep + guidance

@Composable
private fun PrepStep(type: DeviceType, onScan: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Watch,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(28.dp),
            )
            Text(type.title, style = NoopType.title2, color = Palette.textPrimary)
        }

        if (type == DeviceType.Whoop5MG) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.statusWarning.copy(alpha = 0.10f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Filled.Science, contentDescription = null, tint = Palette.statusWarning, modifier = Modifier.size(18.dp))
                Text(
                    uiString(R.string.l10n_add_device_wizard_whoop_5_0_mg_support_is_e452e686),
                    style = NoopType.footnote,
                    color = Palette.statusWarning,
                )
            }
        }

        // A WHOOP strap bonds to ONE phone/app at a time. Make the trade-off explicit BEFORE pairing so it
        // isn't a surprise, with the honest reassurance that it is reversible. Mirrors the iOS one-phone
        // pairing warning card. Shown for both WHOOP models (the constraint is the strap's, not the app's).
        if (type.isWhoop) {
            OnePhoneWarningCard()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .frostedCardSurface(cornerRadius = 14.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            prepInstructions(type).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text("•", style = NoopType.body, color = Palette.accent)
                    Text(line, style = NoopType.body, color = Palette.textSecondary)
                }
            }
        }

        TextButton(
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.accent)
                .semantics { contentDescription = uiString(R.string.l10n_add_device_wizard_scan_for_type_title_be2c98fb, type.title) },
        ) {
            Text(uiString(R.string.l10n_add_device_wizard_scan_28cba55d), style = NoopType.headline, color = Palette.goldDeepText)
        }
    }
}

/** Type-specific "get it ready" guidance - the point of the branching wizard. US English copy. */
private fun prepInstructions(type: DeviceType): List<String> = when (type) {
    DeviceType.Whoop4 -> listOf(
        "Put your WHOOP 4.0 on your wrist and make sure it's awake.",
        "Make sure it's NOT connected to the official WHOOP app right now.",
        "Zoop will look for it nearby.",
    )
    DeviceType.Whoop5MG -> listOf(
        "WHOOP 5.0 / MG bonds to one device at a time, so unpair it from the official WHOOP app first.",
        "Put the band into pairing mode, on your wrist and awake.",
        "Zoop will look for it nearby.",
    )
}

// MARK: - Step 3 - pick from the live scan

@Composable
private fun WhoopPickStep(
    viewModel: AppViewModel,
    onSelect: (WhoopBleClient.DiscoveredWhoop) -> Unit,
    onRescan: () -> Unit,
) {
    val found by viewModel.discoveredWhoops.collectAsStateWithLifecycle()
    PickList(searching = true, isEmpty = found.isEmpty(), onRescan = onRescan) {
        found.sortedByDescending { it.rssi }.forEach { strap ->
            DiscoveredRow(
                name = strap.name?.takeIf { it.isNotBlank() } ?: "WHOOP",
                subtitle = "WHOOP",
                rssi = strap.rssi,
                onTap = { onSelect(strap) },
            )
        }
    }
}

/** Shared pick-step shell: a searching status bar + a Rescan button, then either the searching card
 *  (while [isEmpty]) or the caller's discovered [rows]. Mirrors the iOS pick step's ScanStatusBar +
 *  SearchingCard. */
@Composable
private fun PickList(
    searching: Boolean,
    isEmpty: Boolean,
    onRescan: () -> Unit,
    rows: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(
                if (searching) "Searching…" else "Idle",
                tone = if (searching) StrandTone.Accent else StrandTone.Neutral,
                pulsing = searching,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRescan) {
                Text(uiString(R.string.l10n_add_device_wizard_rescan_84661f6a), style = NoopType.subhead, color = Palette.accent)
            }
        }
        if (isEmpty) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .frostedCardSurface(cornerRadius = 14.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(22.dp))
                Text(uiString(R.string.l10n_add_device_wizard_searching_1a6a5ba8), style = NoopType.body, color = Palette.textPrimary)
                Text(
                    uiString(R.string.l10n_add_device_wizard_make_sure_it_s_awake_and_8c40e59f),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) { rows() }
        }
    }
}

@Composable
private fun DiscoveredRow(name: String, subtitle: String, rssi: Int, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .frostedCardSurface(cornerRadius = 12.dp)
            .clickable(onClick = onTap)
            .semantics { contentDescription = uiString(R.string.l10n_add_device_wizard_name_signal_signalbars_level_rssi_of_6aa514f6, name, SignalBars.level(rssi)) }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalBars(rssi)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = NoopType.body, color = Palette.textPrimary)
            Text(subtitle, style = NoopType.caption, color = Palette.textTertiary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

// MARK: - Step 4 - name + confirm

@Composable
private fun ConfirmStep(
    advertisedName: String,
    brand: String,
    rssi: Int,
    name: String,
    onName: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .frostedCardSurface(cornerRadius = 12.dp)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalBars(rssi)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(advertisedName, style = NoopType.headline, color = Palette.textPrimary)
                Text(brand, style = NoopType.caption, color = Palette.textTertiary)
            }
        }

        Overline("Name")
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            placeholder = { Text(uiString(R.string.l10n_add_device_wizard_device_name_79d7a157), style = NoopType.body, color = Palette.textTertiary) },
            colors = wizardFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = uiString(R.string.l10n_add_device_wizard_device_name_79d7a157) },
        )

        TextButton(
            onClick = onAdd,
            enabled = name.trim().isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (name.trim().isNotEmpty()) Palette.accent else Palette.surfaceInset),
        ) {
            Text(
                uiString(R.string.l10n_add_device_wizard_add_61cc55aa),
                style = NoopType.headline,
                color = if (name.trim().isNotEmpty()) Palette.goldDeepText else Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun wizardFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    focusedContainerColor = Palette.surfaceInset,
    unfocusedContainerColor = Palette.surfaceInset,
)
