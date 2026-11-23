package com.noop.ui

import kotlin.math.roundToInt

// MARK: - Waist stepper (optional VO₂max input)

/** A typical adult waist (cm) used as the first value when stepping up from "unset" (0), so the field
 *  jumps to a sensible starting point rather than 1 cm. ~34" — the rough population midpoint. */
private const val WAIST_SEED_CM = 86.0

/** Step the waist by one centimetre, seeding [WAIST_SEED_CM] when starting from unset (0). Stepping
 *  down from the seed cannot go below the seed (it never silently re-enters the "unset" sentinel). */
internal fun waistCmStep(current: Double, up: Boolean): Double {
    if (current <= 0.0) return if (up) WAIST_SEED_CM else 0.0
    return (current + if (up) 1.0 else -1.0).coerceAtLeast(WAIST_SEED_CM - 30.0)
}

/** Step the waist by one inch (entry unit in imperial; stored as cm), seeding [WAIST_SEED_CM] from
 *  unset. Snaps to whole inches so the up/down sequence is symmetric, mirroring the Height field. */
internal fun waistInchesStep(current: Double, up: Boolean): Double {
    if (current <= 0.0) return if (up) WAIST_SEED_CM else 0.0
    val inches = UnitFormatter.cmToInches(current).roundToInt()
    val nextInches = (inches + if (up) 1 else -1)
    val nextCm = nextInches * UnitFormatter.CENTIMETERS_PER_INCH
    return nextCm.coerceAtLeast(WAIST_SEED_CM - 30.0)
}

// MARK: - Strap status helpers (mirror SettingsView's computed properties)

/**
 * The Strap pill on Settings.
 *
 * [encryptedBond], not [bonded], is what "Bonded" means. `bonded` is also set by the 5/MG live-HR
 * shortcut (#69), where HR streams over the open profile with no encrypted pairing at all — so keying the
 * green "Bonded" state off it told a strap with no pairing that it had one. The Live screen has drawn this
 * distinction since #69; Settings and Data Sources were missed, and #1635 hello suppression turns that
 * state from a brief moment on the way to bonding into where a 5/MG now permanently sits.
 *
 * It is not cosmetic. The encrypted bond gates buzz, alarms, double-tap and history sync, and Settings
 * already says so a few rows further down ("Needs the full encrypted bond…"). A green "Bonded · streaming"
 * above that contradicts it on the same screen.
 */
internal fun strapStatusTitle(encryptedBond: Boolean, bonded: Boolean, connected: Boolean): String = when {
    encryptedBond && connected -> "Bonded · streaming"
    encryptedBond -> "Bonded · idle"
    bonded && connected -> "Live HR (not fully paired)"
    connected -> "Connected"
    // No `bonded`-only idle arm: without an encrypted bond there was never a pairing to be idle from,
    // and labelling that "Paired" would be the same overclaim this change exists to remove. The 5/MG
    // shortcut's `bonded` is cleared on disconnect anyway, so the honest answer here is Disconnected.
    else -> "Disconnected"
}

/** Positive ONLY for a real encrypted bond on a live link — see [strapStatusTitle]. A live-HR-only link
 *  is a warning, not a success: it works, but the pairing-gated features do not. */
internal fun strapTone(encryptedBond: Boolean, bonded: Boolean, connected: Boolean): StrandTone = when {
    encryptedBond && connected -> StrandTone.Positive
    connected || bonded -> StrandTone.Warning
    else -> StrandTone.Critical
}

// `internal` (not private) so the unit test in the same package can assert the scanning branch.
internal fun strapStatusDetail(
    encryptedBond: Boolean,
    bonded: Boolean,
    connected: Boolean,
    scanning: Boolean,
): String = when {
    scanning -> "Searching for your WHOOP… make sure it's charged, on your wrist, and the official WHOOP app isn't connected to it."
    encryptedBond && connected -> "Your strap is paired and sending data. Open Live for a real-time heart rate."
    bonded && connected -> "Live heart rate is streaming, but your strap is not fully paired. The encrypted pairing is what carries motion, skin temperature, SpO₂ and respiratory rate — without it, sleep is staged from heart rate alone. Buzz, alarms and history sync need it too."
    connected -> "Connected. Finishing the secure pairing handshake…"
    bonded -> "Previously paired but not currently connected. Re-scan to reconnect."
    else -> "No strap connected. Put your WHOOP nearby and tap Re-scan to pair."
}

internal fun batteryTone(pct: Double): StrandTone = when {
    pct <= 15 -> StrandTone.Critical
    pct <= 30 -> StrandTone.Warning
    else -> StrandTone.Positive
}

// MARK: - Sex options

internal data class SexOption(val tag: String, val label: String)

internal val SEX_OPTIONS = listOf(
    SexOption("male", "Male"),
    SexOption("female", "Female"),
    SexOption("nonbinary", "Non-binary"),
)

// MARK: - Settings pages

/**
 * The pages the Settings landing list opens. A new Settings screen starts on the list. The open page is
 * saveable screen state, never persisted: it comes back only with the rest of the screen's saved state
 * (Back from a screen pushed off the page, or a tab switch that restores Settings), so no stored token can
 * strand a user on a page that was renamed or removed.
 */
internal enum class SettingsPage {
    PROFILE,
    LOOK,
    STRAP,
    HEALTH,
    DATA,
    ABOUT,
    ADVANCED,
}

/** The everyday pages, in landing-list order. [SettingsPage.ADVANCED] sits apart in its own card below. */
internal val SETTINGS_LANDING_PAGES: List<SettingsPage> = listOf(
    SettingsPage.PROFILE,
    SettingsPage.LOOK,
    SettingsPage.STRAP,
    SettingsPage.HEALTH,
    SettingsPage.DATA,
    SettingsPage.ABOUT,
)

/**
 * The strap battery the landing row may show: the active strap's charge from its current link, resolved
 * through [HeaderBatteryDisplay.resolve], the gate the Today header's battery chip reads, or null. The
 * strap's `batteryPct` is never cleared on disconnect, so an ungated read would show the last link's
 * charge (or, after a switch, another strap's) as if it were current. A ring's charge is not the strap's,
 * so it is never shown here.
 */
internal fun strapLandingBatteryPct(activeIsWhoop: Boolean, connected: Boolean, batteryPct: Double?): Double? =
    (HeaderBatteryDisplay.resolve(activeIsWhoop, connected, batteryPct, ringPct = null) as? HeaderBatteryDisplay.State.Charge)
        ?.takeIf { !it.isRing }
        ?.pct

/**
 * The strap row's summary on the landing list: the active strap's name and its battery, for example
 * "WHOOP 4.0, 64%". [batteryPct] is the gated reading from [strapLandingBatteryPct], rounded the same way
 * as the battery pill on the Strap page. Either half is left out when unknown, and with neither the row
 * shows no summary rather than a placeholder.
 */
internal fun strapLandingSummary(name: String?, batteryPct: Double?): String? {
    val label = name?.trim()?.takeIf { it.isNotEmpty() }
    val pct = batteryPct?.roundToInt()
    return when {
        label != null && pct != null -> "$label, $pct%"
        label != null -> label
        pct != null -> "$pct%"
        else -> null
    }
}
