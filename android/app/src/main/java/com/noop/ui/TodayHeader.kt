package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.app.DatePickerDialog
import android.view.HapticFeedbackConstants
import com.noop.R
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar

// Today tab: header (title, battery chip, sync line), wordmark, header discs and the scoring-guide affordances.
// Split out of TodayScreen.kt; behaviour unchanged.

/**
 * A small top-trailing × for a Today info-card that has no built-in dismiss control (the shared
 * [DataPendingNote]). Matches the "New here?" card's × styling. Dismisses the card into the inbox.
 */
@Composable
internal fun TodayCardDismissButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(Metrics.iconButton)
            .semantics { contentDescription = uiString(R.string.l10n_today_screen_dismiss_to_updates_2c7915b8) },
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Scan and connect, on the LEADING edge of the wordmark row (#2169).
 *
 * That slot cost nothing to take. The row was a spacer, the wordmark centred in what was left, and the
 * Customize disc; the spacer existed only to balance the disc so the wordmark sat centred. A control
 * there replaces it, and the wordmark stays where it was.
 *
 * Deliberately NOT in the right-hand cluster, which is where this would otherwise belong. #2110
 * measured that: a fifth control there leaves the 28sp day title about 117dp, and "Yesterday" needs
 * about 139dp, so it would ellipsize a title that fits today.
 *
 * Greys out and stops taking taps while a scan is running, which is the same rule the Settings and Live
 * buttons follow. No spinner: the header battery chip is already the thing that reports state.
 */
@Composable
internal fun RescanDisc(scanning: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(HeaderClusterControl)
            .liquidPress(interaction)
            .clip(CircleShape)
            // The same inset disc its siblings use: part of the header, not a call to action.
            .background(Palette.surfaceInset.copy(alpha = if (scanning) 0.5f else 1f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !scanning,
                onClick = onClick,
            )
            .semantics { contentDescription = uiString(R.string.l10n_today_screen_scan_and_connect_40157030) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            // Bluetooth, not Refresh. Settings can use a circular arrow because the word "Re-scan" is
            // sitting next to it; here the glyph is the whole affordance, and a circular arrow on a
            // screen full of numbers reads as "reload my data" rather than "look for my strap". This is
            // also the icon Live's Scan & Connect button and the onboarding connect steps already use,
            // so the same action now looks the same everywhere it appears.
            if (scanning) Icons.Filled.BluetoothSearching else Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = Palette.textPrimary.copy(alpha = if (scanning) 0.45f else 1f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Customize Today (#2110): section order and visibility, on the trailing edge of the wordmark row.
 *
 *  Icon-only at the shared header-control size. The glyph and the accessibility-label-instead-of-text
 *  treatment are iOS's (`slider.horizontal.3` in a `nativeLiquidGlassHeaderButton`), but the PLACEMENT
 *  deliberately is not: iOS keeps this in the header control cluster, and Android cannot afford it
 *  there. See the cluster comment in `LiquidTodayHeader` — Android's cluster already carries a sync
 *  chip iOS has no equivalent of, and lacks iOS's measured title fade, so a control added there is
 *  width taken straight from the day title.
 *
 *  Icon-only is what answers BOTH reports: it still reads as a control (#2008 found the old tertiary
 *  text unreadable as one) while no longer out-shouting the hero rings (#2110), and dropping the
 *  visible label retires the locale-width problem, since the word ran 3 characters in zh to 13 in fr.
 *
 *  No new string: the existing "Customize Today" title is the accessibility label, so this ships in
 *  every locale that already had it. */
@Composable
internal fun CustomizeDisc(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            // Level with the avatar and the + (shared size), so the cluster reads as one row.
            .size(HeaderClusterControl)
            .liquidPress(interaction)
            .clip(CircleShape)
            // The same inset disc the + uses, rather than the frosted card surface #2010 reached for:
            // the point is to look like its siblings, not like a call to action.
            .background(Palette.surfaceInset)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = uiString(R.string.today_customize_title) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = null,
            tint = Palette.textPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The accent quick-action "+" in the Today header's top-right. Moved off the bottom bar (now four clean
 * tabs) to balance the header and open the existing quick-action sheet. A small CONTAINED accent disc,  * the accented primary among an otherwise-neutral icon set, ~36dp, no float and no glow: a flat reset-blue
 * accent fill with a hairline rim, the "+" glyph in crisp white. Mirrors the iOS quick-action + (a glyph on
 * Circle().fill(StrandPalette.accent)).
 */
@Composable
private fun QuickActionDisc(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            // Sit level with the avatar in the liquid header cluster (shared size).
            .size(HeaderClusterControl)
            .liquidPress(interaction)
            .clip(CircleShape)
            // Calm: an inset disc (the chip and input tone) with a crisp primary glyph, like the rest of
            // the header cluster.
            .background(Palette.surfaceInset)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = uiString(R.string.l10n_today_screen_quick_actions_e47e8042) },
        contentAlignment = Alignment.Center,
    ) {
        // A bolt rather than "+": the menu holds shortcuts (Live HR, Breathe, Updates), not "add" actions.
        Icon(
            Icons.Filled.Bolt,
            contentDescription = null,
            tint = Palette.textPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

// MARK: - Scoring-guide affordances (ⓘ + first-run card)

/**
 * The small ⓘ that opens the scoring guide. Used on the Charge ring and the Effort / Rest tiles.
 * [section] only tunes the accessibility label; the deep-link target is carried by [onClick]'s
 * call site. Icon-only, so it always carries a content description. [compact] shrinks the hit-target
 * for the tile headers (where a full 36dp button would crowd the fixed-height tile).
 */
@Composable
internal fun ScoreInfoButton(
    section: ScoreSection?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val label = section?.let { uiString(R.string.today_how_score_named, uiString(it.labelRes)) }
        ?: uiString(R.string.today_how_score_generic)
    val button = if (compact) 24.dp else Metrics.iconButton
    val glyph = if (compact) 16.dp else Metrics.iconSmall
    IconButton(onClick = onClick, modifier = modifier.size(button)) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = label,
            tint = Palette.textTertiary,
            modifier = Modifier.size(glyph),
        )
    }
}

/**
 * One-time "New here?" card pointing first-run users at the scoring guide. A NoopCard in the Today
 * flow, never a dialog, with a primary "See how it works" action and a ✕ dismiss; both set the
 * seen-flag at the call site so the card never returns. Copy verbatim from the approved source.
 */
@Composable
private fun ScoringGuideIntroCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = Palette.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(uiString(R.string.l10n_today_screen_new_here_b81f2e17), style = NoopType.headline, color = Palette.textPrimary)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(Metrics.iconButton)
                        .semantics { contentDescription = uiString(R.string.l10n_today_screen_dismiss_70afe9ef) },
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(Metrics.iconSmall),
                    )
                }
            }
            Text(
                uiString(R.string.l10n_today_screen_see_how_charge_effort_and_rest_80243197),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpen) {
                    Text(uiString(R.string.l10n_today_screen_see_how_it_works_f7b38f07), style = NoopType.captionNumber, color = Palette.accent)
                }
            }
        }
    }
}

// MARK: - Today header
//
// LEFT: a tappable title block, the big day title over a human date line ("Friday, 3 July"); tap opens the
// day picker. RIGHT: the battery chip (charge as a glyph and a percentage, words only when the strap needs
// attention; tap opens Devices or reconnects), then two round controls, quick actions (bolt, opens the
// quick-actions sheet that also holds the Updates inbox) and the profile avatar (Settings). BELOW: only
// while history offloads, the thin catch-up line.

/** The diameter of the round Today-header controls (quick actions and the profile avatar), so they sit
 *  level. Single source of truth so a size tweak keeps both in lockstep. */
internal val HeaderClusterControl = 40.dp

@Composable
internal fun LiquidTodayHeader(
    dayTitle: String,
    humanDate: String,
    selectedDay: LocalDate,
    /** What the battery chip shows for the ACTIVE device, resolved by [HeaderBatteryDisplay]: the strap's
     *  charge (or its offline / no-reading-yet glyph) under an active strap, the ring's own charge under an
     *  active ring, and nothing at all when the active device is neither. (#2208) */
    battery: HeaderBatteryDisplay.State,
    // #245: sync state, shown in the battery chip in the header's control row.
    backfilling: Boolean = false,
    syncChunksThisSession: Int = 0,
    lastSyncAt: Long? = null,
    historySyncExperimental: Boolean = false,
    // #689/#815: the connect-time backlog sample, when known.
    pagesBehindAtConnect: Int? = null,
    // #2169: whether a scan is already running, and how to ask for one.
    scanning: Boolean = false,
    onRescan: (() -> Unit)? = null,
    onPickDay: (Int) -> Unit,
    onQuickActions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        val context = LocalContext.current
        DisposableEffect(selectedDay) {
            val cal = Calendar.getInstance().apply {
                set(selectedDay.year, selectedDay.monthValue - 1, selectedDay.dayOfMonth)
            }
            // Anchor the offset to the LOGICAL day (matches selectedDayOffset's anchor) so a picked date
            // resolves to the same row the header is labelling, never drifting against LocalDate.now().
            val anchor = logicalDayNow()
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    val picked = LocalDate.of(year, month + 1, day)
                    val offset = ChronoUnit.DAYS.between(picked, anchor).toInt().coerceAtLeast(0)
                    onPickDay(offset)
                    showPicker = false
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).apply {
                datePicker.maxDate = System.currentTimeMillis()
                setOnDismissListener { showPicker = false }
            }
            dialog.show()
            onDispose { runCatching { dialog.dismiss() } }
        }
    }

    // The header row, then (only while strap history offloads) the thin catch-up line across its full
    // width. The line sits under the row instead of inside the trailing cluster, so syncing costs the
    // day title no width at all.
    Column(modifier = modifier.fillMaxWidth()) {
        // The battery chip: the active device's charge, plus a word when the sync state needs a look. Tap
        // opens Devices, or starts a scan while the strap is offline.
        val pill = StrapStatusPill.resolve(
            battery = battery,
            sync = SyncChipState.resolve(
                backfilling = backfilling,
                chunks = syncChunksThisSession,
                lastSyncAtSec = lastSyncAt,
                historySyncExperimental = historySyncExperimental,
                nowSec = System.currentTimeMillis() / 1000L,
                pagesBehind = pagesBehindAtConnect,
            ),
            canReconnect = onRescan != null,
            scanning = scanning,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // LEFT: the tappable title block — big rounded-bold day title over the human date line. Taps open the
            // day picker; a horizontal swipe across the dashboard still changes the day. weight(1f) so the title
            // claims the leading room and never pushes the trailing control cluster. Mirrors iOS's title Button.
            Column(
                modifier = Modifier
                    .weight(1f)
                    // #492: NO rounded clip here. With indication = null there's no ripple to shape, and the
                    // rounded corners were clipping the title/date text's bottom-left (the "W" of "Wednesday").
                    // iOS's title Button uses a plain contentShape(Rectangle()) with no clip — mirror that.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = uiString(R.string.today_action_change_day),
                        onClick = { showPicker = true },
                    )
                    .semantics { contentDescription = uiString(R.string.l10n_today_screen_daytitle_humandate_tap_to_pick_a_7e12ce96, dayTitle, humanDate) },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    dayTitle,
                    // Calm page title, 30/700 in the primary text colour on the flat canvas.
                    style = NoopType.title1,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    humanDate,
                    style = NoopType.caption,
                    color = Palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // RIGHT: the battery chip, quick actions and the profile, 8dp apart. Kept small on purpose: the
            // title Column beside this is weight(1f) with maxLines=1 + Ellipsis, so every control added
            // here is width taken from the day title (#2110). The chip is a glyph and a percentage in the
            // usual case, and spells out words only when the strap needs attention.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pill != null && StrapStatusPill.showsChip(pill)) {
                    BatteryChip(
                        model = pill,
                        onClick = when (pill.tap) {
                            StrapStatusPill.Tap.Reconnect -> onRescan ?: onOpenDevices
                            StrapStatusPill.Tap.OpenDevices -> onOpenDevices
                        },
                    )
                }
                QuickActionDisc(onClick = onQuickActions)
                // (a) Profile avatar (the photo set in Settings, or the NOOP loop mark) → Settings. Mirrors iOS.
                Box(
                    modifier = Modifier
                        .size(HeaderClusterControl)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenSettings,
                        )
                        .semantics { contentDescription = uiString(R.string.l10n_today_screen_profile_and_settings_9b3d12f2) },
                    contentAlignment = Alignment.Center,
                ) {
                    ProfileAvatar(size = HeaderClusterControl)
                }
            }
        }
        if (backfilling) {
            SyncProgressLine(
                chunks = syncChunksThisSession,
                pagesBehind = pagesBehindAtConnect?.takeIf { it > 0 },
                modifier = Modifier.padding(top = Metrics.space8),
            )
        }
    }
}

/** The Today header's catch-up indicator: a thin line across the full header width with a soft
 *  segment gliding along it while strap history offloads. It replaces the old syncing pill, whose
 *  backlog label ("80 pages to catch up") took width from the day title. The offload has no reliable
 *  total, so the line is indeterminate and never a percent (see AppViewModel). The counts it used to
 *  print stay available to TalkBack through the content description. Posed still, it shows a fixed
 *  segment so screenshots stay stable. */
@Composable
private fun SyncProgressLine(chunks: Int, pagesBehind: Int?, modifier: Modifier = Modifier) {
    // Both counts are inflected, so "1 chunk" and "1 page" read correctly. Android <plurals> inflects
    // one quantity per resource, so each count resolves separately and a template sentence joins them;
    // the joiner sits inside the template, never on a resource edge, because aapt2 strips edge
    // whitespace (#2041).
    val chunksText = uiPlural(R.plurals.sync_chip_chunks_count, chunks, chunks)
    val pagesText = pagesBehind?.let { uiPlural(R.plurals.sync_chip_pages_behind_count, it, it) }
    val desc = if (pagesText != null) {
        uiString(R.string.l10n_today_screen_sync_chip_syncing_pages_desc_ad763b39, chunksText, pagesText)
    } else {
        uiString(R.string.l10n_today_screen_sync_chip_syncing_desc_92daf60c, chunksText)
    }
    val still = rememberPoseStill()
    val phase = if (still) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition()
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )
        p
    }
    val track = Palette.surfaceInset
    val glow = Palette.accent
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(SyncLineThickness)
            .clearAndSetSemantics { contentDescription = desc },
    ) {
        val y = size.height / 2f
        val stroke = size.height
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
        // The segment enters from the left edge and leaves past the right one, clipped to the track.
        val segment = size.width * 0.3f
        val head = phase * (size.width + segment)
        val start = (head - segment).coerceIn(0f, size.width)
        val end = head.coerceIn(0f, size.width)
        if (end > start) {
            drawLine(glow, Offset(start, y), Offset(end, y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** Thickness of the header catch-up line: a hairline that reads as status, not as a control. */
private val SyncLineThickness = 3.dp

/** The Today header's battery chip, in the control row beside quick actions: a small battery glyph and
 *  the charge ("64%"), or a short word when the state needs attention ("Syncing", "Not connected",
 *  "Looking…"), all decided by [StrapStatusPill]. The full state, sync age included, goes to TalkBack. */
@Composable
private fun BatteryChip(model: StrapStatusPill.Model, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val battery = model.battery
    val levelColor = when (battery) {
        is StrapStatusPill.Battery.Charge -> when (battery.level) {
            StrapStatusPill.Level.Critical -> Palette.statusCritical
            StrapStatusPill.Level.Low -> Palette.statusWarning
            StrapStatusPill.Level.Ok -> Palette.chargeColor
        }
        else -> Palette.textTertiary
    }
    // TalkBack reads the whole state ("Strap 64%, synced 3m ago"), which the chip itself no longer spells out.
    val lead: String? = when (battery) {
        StrapStatusPill.Battery.None -> null
        StrapStatusPill.Battery.Offline -> uiString(R.string.strap_pill_offline)
        StrapStatusPill.Battery.Pending -> uiString(R.string.strap_pill_strap)
        is StrapStatusPill.Battery.Charge -> uiString(
            if (battery.isRing) R.string.strap_pill_ring_pct else R.string.strap_pill_strap_pct, battery.pct,
        )
    }
    val status: String? = when (val st = model.status) {
        StrapStatusPill.Status.None -> null
        StrapStatusPill.Status.CatchingUp -> uiString(R.string.strap_pill_catching_up)
        is StrapStatusPill.Status.Synced -> uiString(R.string.strap_pill_synced_ago, st.agoText)
        StrapStatusPill.Status.Live -> uiString(R.string.strap_pill_live)
        StrapStatusPill.Status.Looking -> uiString(R.string.strap_pill_looking)
        StrapStatusPill.Status.TapToReconnect -> uiString(R.string.strap_pill_tap_to_reconnect)
    }
    val desc = when {
        lead != null && status != null -> uiString(R.string.strap_chip_desc, lead, status)
        else -> lead ?: status ?: ""
    }
    val clickLabel = uiString(
        if (model.tap == StrapStatusPill.Tap.Reconnect) R.string.l10n_today_screen_scan_and_connect_40157030
        else R.string.strap_pill_open_devices,
    )
    val chipText = StrapStatusPill.chipText(model)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
        modifier = modifier
            .heightIn(min = BatteryChipHeight)
            .clip(RoundedCornerShape(50))
            .background(Palette.surfaceInset)
            .clickable(onClickLabel = clickLabel, onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = Metrics.space8),
    ) {
        if (battery != StrapStatusPill.Battery.None) {
            BatteryGlyph(
                fraction = (battery as? StrapStatusPill.Battery.Charge)?.pct?.div(100f),
                color = levelColor,
            )
        }
        when (chipText) {
            is StrapStatusPill.ChipText.Percent -> Text(
                uiString(R.string.strap_chip_pct, chipText.pct),
                style = NoopType.captionNumber,
                color = Palette.textPrimary,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {},
            )
            StrapStatusPill.ChipText.Syncing,
            StrapStatusPill.ChipText.NotConnected,
            StrapStatusPill.ChipText.Looking -> Text(
                uiString(
                    when (chipText) {
                        StrapStatusPill.ChipText.Syncing -> R.string.strap_chip_syncing
                        StrapStatusPill.ChipText.NotConnected -> R.string.strap_chip_not_connected
                        else -> R.string.strap_chip_looking
                    },
                ),
                style = NoopType.caption,
                color = Palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {},
            )
            StrapStatusPill.ChipText.None -> Unit
        }
    }
}

/** A small horizontal battery: an outline, a nub, and a fill for [fraction] (null draws the outline
 *  only, for an offline or not-yet-read strap). A drawn glyph rather than an icon set, so the fill tracks
 *  the real charge instead of snapping to a few bars. */
@Composable
private fun BatteryGlyph(fraction: Float?, color: Color) {
    Canvas(modifier = Modifier.size(width = 18.dp, height = 10.dp).clearAndSetSemantics { }) {
        val stroke = 1.5.dp.toPx()
        val nub = 2.dp.toPx()
        val bodyW = size.width - nub
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyW - stroke, size.height - stroke),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyW, size.height * 0.3f),
            size = Size(nub, size.height * 0.4f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        if (fraction != null) {
            val inset = stroke + 1.dp.toPx()
            val fullW = bodyW - inset * 2
            // At least a sliver, so a near-flat battery still shows something.
            val w = fullW * fraction.coerceIn(0.08f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(w, size.height - inset * 2),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/** Minimum height of the battery chip: a comfortable tap target that still reads as a caption. */
private val BatteryChipHeight = 32.dp


// MARK: - Zoop wordmark (centred, with a tap easter egg)
//
// The Loop wordmark ("zoop" with the two o's as one mint infinity loop) that sits between the header and
// the first card on the Metrics tab, about 22dp tall and dead centre. A tap plays one of several random
// one-shot animations — wiggle / shake / flip / spin / bounce / jelly squash.

@Composable
internal fun LiquidWordmark() {
    val reduced = rememberReduceMotion()
    var rot by remember { mutableStateOf(0f) }        // z-rotation (wiggle / spin)
    var scaleX by remember { mutableStateOf(1f) }     // horizontal scale (jelly squash)
    var scaleY by remember { mutableStateOf(1f) }     // vertical scale (bounce / jelly)
    var dx by remember { mutableStateOf(0f) }         // horizontal offset (shake)
    var egg by remember { mutableIntStateOf(0) }      // which egg to play (drives the LaunchedEffect)

    val view = LocalView.current
    val animRot by animateFloatAsState(rot, tween(durationMillis = if (reduced) 0 else 520), label = uiString(R.string.l10n_today_screen_wordmark_rot_21de874b))
    val animScaleX by animateFloatAsState(scaleX, tween(durationMillis = if (reduced) 0 else 380), label = uiString(R.string.l10n_today_screen_wordmark_sx_68fa7b60))
    val animScaleY by animateFloatAsState(scaleY, tween(durationMillis = if (reduced) 0 else 380), label = uiString(R.string.l10n_today_screen_wordmark_sy_7b8bd743))
    val animDx by animateFloatAsState(dx, tween(durationMillis = if (reduced) 0 else 420), label = uiString(R.string.l10n_today_screen_wordmark_dx_d428284f))

    // On each tap, kick a value to an extreme then settle it back so the animateFloatAsState eases through
    // to rest — a natural wobble without hand-authored keyframes. Six variants, chosen at random per tap.
    LaunchedEffect(egg) {
        if (egg == 0) return@LaunchedEffect
        when ((0..5).random()) {
            0 -> { rot = -12f; kotlinx.coroutines.delay(90); rot = 0f }            // wiggle
            1 -> { dx = -12f; kotlinx.coroutines.delay(90); dx = 0f }              // shake
            2 -> { rot += 360f }                                                    // spin
            3 -> { scaleX = 1.28f; scaleY = 1.28f; kotlinx.coroutines.delay(90); scaleX = 1f; scaleY = 1f } // bounce
            4 -> { scaleX = 1.35f; scaleY = 0.7f; kotlinx.coroutines.delay(90); scaleX = 1f; scaleY = 1f }  // jelly
            else -> { rot += 360f }                                                 // flip (spin twin)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                egg += 1
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            .graphicsLayer {
                rotationZ = animRot
                this.scaleX = animScaleX
                this.scaleY = animScaleY
                translationX = animDx
            }
            .clearAndSetSemantics {}, // decorative wordmark — invisible to TalkBack
        contentAlignment = Alignment.Center,
    ) {
        ZoopWordmark(height = 22.dp)
    }
}

/// Vital-detail keys the hero rings open (#1995).
///
/// These are the keys `VitalDetailScreen`'s `when` accepts, NOT the metricSeries keys. Rest is the
/// difference that bites: its detail key is "rest" while the series underneath it is
/// "sleep_performance", which is what iOS routes on. An unknown key falls through that `when` to a null
/// model and renders an empty screen, so the pairing is pinned by `HeroRingMetricKeyTest`.
internal const val HERO_CHARGE_METRIC_KEY = "recovery"
internal const val HERO_EFFORT_METRIC_KEY = "strain"
internal const val HERO_REST_METRIC_KEY = "rest"
