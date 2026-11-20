package com.noop.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.noop.R

// Today tab: the layout, card and key-metric editors, plus section drag-reorder.
// Split out of TodayScreen.kt; behaviour unchanged.

// MARK: - Shared Shown / Hidden editor rows

@Composable
private fun VisibilityItemLabel(title: String, subtitle: String?, color: Color, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Text(title, style = NoopType.body, color = color)
        if (subtitle != null) {
            Text(subtitle, style = NoopType.caption, color = Palette.textSecondary)
        }
    }
}

/**
 * The common editor body used by Today sections, Key Metrics and Your Cards. Items are never deleted:
 * remove moves one from Shown to Hidden, add restores it at the end of Shown, and the arrow controls use
 * the same ordering behavior in every editor. SnapshotStateList callers recompose on these mutations.
 */
@Composable
internal fun <T> EditableVisibilityRows(
    shown: MutableList<T>,
    hidden: MutableList<T>,
    itemTitle: (T) -> String,
    modifier: Modifier = Modifier,
    // Whether the Shown list may go EMPTY. Default false — the last shown item can't be hidden, so
    // surfaces needing >=1 item can't be emptied. The hosted-cards editor (#today-hosted-cards) passes
    // true (opt-in: un-hosting the last card is valid).
    allowEmpty: Boolean = false,
    // Optional grouping key for the Hidden ("Available") list. When set (the hosted-cards editor passes the
    // card's origin, e.g. "Sleep" / "Trends"), the Available items render under one sub-header per group so
    // a user browses by origin. null (Today sections, Key Metrics, Your Cards) keeps the flat list. The
    // Shown list stays flat — it is the user's own cross-origin order. Twin of the Swift EditableLayoutList.
    hiddenGroup: ((T) -> String)? = null,
    itemSubtitle: @Composable (T) -> String? = { null },
) {
    val minShown = if (allowEmpty) 0 else 1
    Column(
        modifier = modifier
            .heightIn(max = Metrics.editorListMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.today_customize_shown), style = NoopType.overline, color = Palette.textTertiary)
        shown.forEachIndexed { index, item ->
            val title = itemTitle(item)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.space6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VisibilityItemLabel(title, itemSubtitle(item), Palette.textPrimary, Modifier.weight(1f))
                IconButton(
                    onClick = {
                        if (index > 0) shown.add(index - 1, shown.removeAt(index))
                    },
                    enabled = index > 0,
                    modifier = Modifier.size(Metrics.iconButton),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.today_customize_move_up, title),
                        tint = if (index > 0) Palette.textSecondary else Palette.textTertiary,
                        modifier = Modifier.size(Metrics.iconSmall),
                    )
                }
                IconButton(
                    onClick = {
                        if (index < shown.lastIndex) shown.add(index + 1, shown.removeAt(index))
                    },
                    enabled = index < shown.lastIndex,
                    modifier = Modifier.size(Metrics.iconButton),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.today_customize_move_down, title),
                        tint = if (index < shown.lastIndex) Palette.textSecondary else Palette.textTertiary,
                        modifier = Modifier.size(Metrics.iconSmall),
                    )
                }
                IconButton(
                    onClick = {
                        if (shown.size > minShown) hidden.add(shown.removeAt(index))
                    },
                    enabled = shown.size > minShown,
                    modifier = Modifier.size(Metrics.iconButton),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.today_customize_hide, title),
                        tint = if (shown.size > minShown) Palette.textSecondary else Palette.textTertiary,
                        modifier = Modifier.size(Metrics.iconSmall),
                    )
                }
            }
            if (index < shown.lastIndex) {
                HorizontalDivider(color = Palette.hairline, thickness = Metrics.divider)
            }
        }

        Spacer(Modifier.height(Metrics.space16))
        Text(stringResource(R.string.today_customize_hidden), style = NoopType.overline, color = Palette.textTertiary)
        if (hidden.isEmpty()) {
            Text(
                stringResource(R.string.today_customize_nothing_hidden),
                style = NoopType.body,
                color = Palette.textTertiary,
                modifier = Modifier.padding(vertical = Metrics.space12),
            )
        } else if (hiddenGroup != null) {
            // Grouped Available list: one sub-header per origin ("Sleep", "Trends"). Remove by identity
            // (items are unique) so the move is index-free across the regrouped display.
            val buckets = LinkedHashMap<String, MutableList<T>>()
            hidden.forEach { item -> buckets.getOrPut(hiddenGroup(item)) { ArrayList() }.add(item) }
            buckets.keys.toList().forEachIndexed { gi, groupName ->
                Text(
                    groupName,
                    style = NoopType.overline,
                    color = Palette.textTertiary,
                    modifier = Modifier.padding(top = if (gi == 0) Metrics.space4 else Metrics.space12),
                )
                val itemsIn = buckets.getValue(groupName)
                itemsIn.forEachIndexed { ii, item ->
                    val title = itemTitle(item)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.space6),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VisibilityItemLabel(title, itemSubtitle(item), Palette.textTertiary, Modifier.weight(1f))
                        IconButton(
                            onClick = { hidden.remove(item); shown.add(item) },
                            modifier = Modifier.size(Metrics.iconButton),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.today_customize_show, title),
                                tint = Palette.accent,
                                modifier = Modifier.size(Metrics.iconSmall),
                            )
                        }
                    }
                    if (ii < itemsIn.lastIndex) {
                        HorizontalDivider(color = Palette.hairline, thickness = Metrics.divider)
                    }
                }
            }
        } else {
            hidden.forEachIndexed { index, item ->
                val title = itemTitle(item)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.space6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VisibilityItemLabel(title, itemSubtitle(item), Palette.textTertiary, Modifier.weight(1f))
                    IconButton(
                        onClick = { shown.add(hidden.removeAt(index)) },
                        modifier = Modifier.size(Metrics.iconButton),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.today_customize_show, title),
                            tint = Palette.accent,
                            modifier = Modifier.size(Metrics.iconSmall),
                        )
                    }
                }
                if (index < hidden.lastIndex) {
                    HorizontalDivider(color = Palette.hairline, thickness = Metrics.divider)
                }
            }
        }
    }
}

// MARK: - "Your cards" dashboard editor (WHOOP "My Dashboard" ✎)
//
// A Today-local dialog for choosing which dashboard cards show and in what order. Display-only: it edits the
// persisted selection, never any stored metric. Shown cards retain their saved order; Hidden contains the
// canonical remainder, and restoring a card appends it to Shown. At least one card must stay enabled.

@Composable
internal fun DashboardCardsEditorDialog(
    initial: List<DashboardCard>,
    onDismiss: () -> Unit,
    onSave: (List<DashboardCard>) -> Unit,
) {
    val titles = DashboardCard.entries.associateWith { uiString(it.titleRes) }
    val shown = remember { mutableStateListOf<DashboardCard>().apply { addAll(initial) } }
    val hidden = remember {
        mutableStateListOf<DashboardCard>().apply {
            addAll(DashboardCard.hiddenOptions(initial))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Palette.surfaceOverlay,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(uiString(R.string.l10n_today_screen_my_dashboard_a7a19e72), style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.today_dashboard_editor_description),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }

                EditableVisibilityRows(
                    shown = shown,
                    hidden = hidden,
                    itemTitle = { titles.getValue(it) },
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            shown.clear()
                            shown.addAll(DashboardCard.defaultSelection)
                            hidden.clear()
                            hidden.addAll(DashboardCard.hiddenOptions(shown))
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Palette.textSecondary),
                    ) { Text(uiString(R.string.l10n_today_screen_reset_44c57abd), style = NoopType.body) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSave(shown.toList()) },
                        enabled = shown.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.accent,
                            contentColor = Palette.surfaceBase,
                        ),
                    ) { Text(uiString(R.string.l10n_today_screen_done_e9b450d1), style = NoopType.captionNumber) }
                }
            }
        }
    }
}

/**
 * #today-hosted-cards: the add/remove/reorder editor for the Trends/Sleep cards hosted in Today. Clone of
 * [DashboardCardsEditorDialog] over [HostedCard], with ONE difference — Done is always enabled, because an
 * EMPTY hosted set is valid (the feature is opt-in; hosting nothing simply hides the Added-cards section).
 * Reuses the shared [EditableVisibilityRows]. Twin of the iOS `HostedCardsCustomizationPage`.
 */
@Composable
internal fun HostedCardsEditorDialog(
    initial: List<HostedCard>,
    onDismiss: () -> Unit,
    onSave: (List<HostedCard>) -> Unit,
) {
    val shown = remember { mutableStateListOf<HostedCard>().apply { addAll(initial) } }
    val hidden = remember {
        mutableStateListOf<HostedCard>().apply {
            addAll(HostedCard.canonicalOrder.filter { it !in initial })
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Palette.surfaceOverlay, shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.today_hosted_editor_title), style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        stringResource(R.string.today_hosted_editor_desc),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }

                // Resolve the localized title/origin per card up front (composable-only calls can't run
                // inside the plain itemTitle/hiddenGroup lambdas). The enum's raw title/origin stay the
                // English source of truth; the UI shows the translated text.
                val titleFor = HostedCard.entries.associateWith { it.localizedTitle() }
                val originFor = HostedCard.entries.associateWith { it.localizedOrigin() }
                EditableVisibilityRows(
                    shown = shown,
                    hidden = hidden,
                    itemTitle = { titleFor.getValue(it) },
                    allowEmpty = true,   // hosting is opt-in: un-hosting the last card is valid
                    hiddenGroup = { originFor.getValue(it) },   // group the Available list by origin tab ("Sleep", "Trends")
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            shown.clear()
                            shown.addAll(HostedCard.defaultSelection)
                            hidden.clear()
                            hidden.addAll(HostedCard.canonicalOrder.filter { it !in shown })
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Palette.textSecondary),
                    ) { Text(uiString(R.string.l10n_today_screen_reset_44c57abd), style = NoopType.body) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSave(shown.toList()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.accent,
                            contentColor = Palette.surfaceBase,
                        ),
                    ) { Text(uiString(R.string.l10n_today_screen_done_e9b450d1), style = NoopType.captionNumber) }
                }
            }
        }
    }
}

// #today-layout (hold-to-drag): LazyColumn key prefix for the reorderable section items, so the drag can
// tell a section item from the pinned rows around it.
internal const val TODAY_SECTION_KEY_PREFIX = "todaySection:"

/**
 * Live drag state for the Today hold-to-drag section reorder (#today-layout). One instance per screen.
 * `key`/`distance` are snapshot state (they drive the lifted card's translation each frame); the rest are
 * plain fields written by the gesture and read on the same (main) thread.
 */
internal class TodaySectionDragState {
    /** LazyColumn key of the section being dragged; null when idle. */
    var key by mutableStateOf<String?>(null)

    /** Accumulated finger travel since pickup (px). */
    var distance by mutableFloatStateOf(0f)

    /** The dragged item's viewport offset at pickup (px) — with [distance], the finger-anchored position. */
    var pickedUpAt = 0f

    /** Edge auto-scroll velocity (px/SECOND — the frame loop scales by real frame time, so the speed is
     *  identical on 60/90/120 Hz displays), set by onDrag from edge proximity; 0 outside the edge zones. */
    var autoScrollPxPerSecond = 0f
}

/** This order with [section] moved to [target]'s position (the classic list move). */
internal fun List<TodaySection>.movedTodaySection(section: TodaySection, target: TodaySection): List<TodaySection> {
    val from = indexOf(section)
    val to = indexOf(target)
    if (from == -1 || to == -1 || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * The (dragged, target) pair to swap right now, or null. The lifted card's finger-anchored middle
 * (`pickedUpAt + distance + size/2`, viewport space) must sit over another section item AND have crossed
 * that item's CENTRE in the direction of travel — the centre gate stops a tall card over a short one from
 * ping-ponging (an immediate swap-back would require crossing back over the centre).
 *
 * The direction is derived from [order] (the section list, the source of truth), NOT from layout offsets:
 * after a swap the state updates immediately but layoutInfo lags one frame, and an offset-derived direction
 * on that stale frame re-derives the SAME swap and undoes it (a visible oscillation). Order-derived
 * direction flips with the swap, so the stale re-check fails the centre gate and the move sticks; a
 * genuine user reversal still passes once the finger crosses back over the centre. Pure read; the caller
 * applies the move.
 */
internal fun swapTargetForDraggedSection(
    listState: LazyListState,
    drag: TodaySectionDragState,
    order: List<TodaySection>,
): Pair<TodaySection, TodaySection>? {
    val key = drag.key ?: return null
    val info = listState.layoutInfo
    val current = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
    val middle = drag.pickedUpAt + drag.distance + current.size / 2f
    val target = info.visibleItemsInfo.firstOrNull { item ->
        item.key != key && (item.key as? String)?.startsWith(TODAY_SECTION_KEY_PREFIX) == true &&
            middle >= item.offset && middle <= item.offset + item.size
    } ?: return null
    val dragged = TodaySection.fromRaw(key.removePrefix(TODAY_SECTION_KEY_PREFIX)) ?: return null
    val tgt = TodaySection.fromRaw((target.key as String).removePrefix(TODAY_SECTION_KEY_PREFIX)) ?: return null
    val targetCentre = target.offset + target.size / 2f
    val movingDown = order.indexOf(tgt) > order.indexOf(dragged)
    if (movingDown && middle < targetCentre) return null
    if (!movingDown && middle > targetCentre) return null
    return dragged to tgt
}

/**
 * #today-layout (hold-to-drag): the per-section drag wrapper. LONG-PRESS anywhere on the section lifts it
 * (haptic; the card raises + follows the finger via graphicsLayer, translation computed against the item's
 * CURRENT layout offset so a mid-drag reorder or auto-scroll can't teleport it). onDrag only accumulates
 * finger travel + the edge auto-scroll velocity — the screen-level frame loop owns the swap + scroll, one
 * code path whether the finger is moving or parked at an edge. Taps/scrolls pass through untouched (the
 * detector waits for a long press), so every card keeps its own tap behaviour. No reorder library.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LazyItemScope.TodayReorderableSection(
    section: TodaySection,
    listState: LazyListState,
    drag: TodaySectionDragState,
    onDrop: () -> Unit,
    content: @Composable () -> Unit,
) {
    val key = TODAY_SECTION_KEY_PREFIX + section.raw
    val isDragging = drag.key == key
    val haptics = LocalHapticFeedback.current
    // Drop SETTLE: on release the lifted card is usually mid-air between slots; killing the translation
    // outright snapped it into place (part of the on-device "not smooth" report). Instead the residual
    // offset animates to 0 so the card glides into its slot. `settling` keeps the lifted chrome (zIndex)
    // during the glide; a new pickup cancels it.
    val settleScope = rememberCoroutineScope()
    val settle = remember { Animatable(0f) }
    var settling by remember { mutableStateOf(false) }
    fun releaseWithSettle() {
        val current = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        val residual = if (current != null) drag.pickedUpAt + drag.distance - current.offset else 0f
        onDrop()
        drag.key = null
        drag.distance = 0f
        drag.autoScrollPxPerSecond = 0f
        if (residual != 0f) {
            settling = true
            settleScope.launch {
                settle.snapTo(residual)
                settle.animateTo(0f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
                settling = false
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging || settling) 1f else 0f)
            .then(
                if (isDragging || settling) {
                    Modifier.graphicsLayer {
                        translationY = if (isDragging) {
                            // Finger-anchored viewport position minus wherever layout currently placed it.
                            val current = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                            if (current != null) drag.pickedUpAt + drag.distance - current.offset else 0f
                        } else {
                            settle.value
                        }
                        shadowElevation = if (isDragging) 12f else 6f
                        scaleX = 1.01f
                        scaleY = 1.01f
                    }
                } else {
                    // Non-dragged sections animate to their new slot as the lifted card crosses them — a
                    // calm, deterministic ease (the default placement spring read abrupt when a tall card
                    // displaced a short one; first on-device feedback).
                    Modifier.animateItemPlacement(tween(durationMillis = 260, easing = FastOutSlowInEasing))
                },
            )
            .pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        settling = false
                        drag.key = key
                        drag.distance = 0f
                        drag.pickedUpAt = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == key }?.offset?.toFloat() ?: 0f
                        drag.autoScrollPxPerSecond = 0f
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = { releaseWithSettle() },
                    onDragCancel = {
                        // The list already reordered live; persist what the user sees rather than
                        // silently reverting on a system-cancelled gesture.
                        releaseWithSettle()
                    },
                    onDrag = onDrag@{ change, amount ->
                        change.consume()
                        drag.distance += amount.y
                        val info = listState.layoutInfo
                        val current = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@onDrag
                        // Edge auto-scroll velocity (px/SECOND — the frame loop scales by real frame time)
                        // from the lifted card's proximity to the viewport edges; ramps linearly across the
                        // zone with an eased-in feel via the squared fraction, so entering the zone starts
                        // gently instead of at speed.
                        val zone = 112.dp.toPx()
                        val maxV = 620.dp.toPx()
                        val top = drag.pickedUpAt + drag.distance
                        val bottom = top + current.size
                        drag.autoScrollPxPerSecond = when {
                            bottom > info.viewportEndOffset - zone -> {
                                val f = ((bottom - (info.viewportEndOffset - zone)) / zone).coerceAtMost(1f)
                                maxV * f * f
                            }
                            top < info.viewportStartOffset + zone -> {
                                val f = (((info.viewportStartOffset + zone) - top) / zone).coerceAtMost(1f)
                                -maxV * f * f
                            }
                            else -> 0f
                        }
                    },
                )
            },
    ) { content() }
}

/**
 * Today section editor. Uses the same Shown / Hidden rows as the Key Metrics and Your Cards editors.
 * Hiding is reversible and persists separately from the full order registry.
 */
@Composable
internal fun TodayLayoutEditorDialog(
    initialOrder: List<TodaySection>,
    initialHidden: List<TodaySection>,
    onDismiss: () -> Unit,
    onEditAdded: () -> Unit,
    onSave: (List<TodaySection>, List<TodaySection>) -> Unit,
) {
    val hiddenSet = remember(initialHidden) { initialHidden.toSet() }
    val shown = remember {
        mutableStateListOf<TodaySection>().apply { addAll(initialOrder.filterNot { it in hiddenSet }) }
    }
    val hidden = remember {
        mutableStateListOf<TodaySection>().apply { addAll(initialOrder.filter { it in hiddenSet }) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Palette.surfaceOverlay, shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.today_customize_title), style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        stringResource(R.string.today_customize_description),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }

                EditableVisibilityRows(
                    shown = shown,
                    hidden = hidden,
                    itemTitle = { uiString(it.titleRes) },
                )

                // #today-hosted-cards: hand-off to the editor that chooses WHICH Trends/Sleep cards the
                // "Added Cards" section hosts (the section row above only reorders/hides the section).
                TextButton(
                    onClick = onEditAdded,
                    colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Metrics.iconSmall))
                    Spacer(Modifier.width(Metrics.space6))
                    Text(stringResource(R.string.today_customize_edit_added_cards), style = NoopType.body)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            shown.clear()
                            shown.addAll(TodaySection.defaultOrder.filterNot { it in TodaySection.homeOwned })
                            hidden.clear()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Palette.textSecondary),
                    ) { Text(uiString(R.string.l10n_today_screen_reset_44c57abd), style = NoopType.body) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSave(shown.toList() + hidden.toList(), hidden.toList()) },
                        enabled = shown.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.accent,
                            contentColor = Palette.surfaceBase,
                        ),
                    ) { Text(uiString(R.string.l10n_today_screen_done_e9b450d1), style = NoopType.captionNumber) }
                }
            }
        }
    }
}

// MARK: - Key-Metrics layout editor (#251)
//
// A Today-local dialog (no new nav destination, another lane owns the nav graph) for choosing which
// Key-Metric tiles show on the Control Center and in what order. Display-only: it edits the persisted
// `today.keyMetrics` layout, never any stored metric. Uses the shared Shown / Hidden editor rows.

@Composable
internal fun KeyMetricsEditorDialog(
    initial: List<KeyMetric>,
    onDismiss: () -> Unit,
    onSave: (List<KeyMetric>) -> Unit,
) {
    val titles = KeyMetric.entries.associateWith { uiString(it.titleRes) }
    val shown = remember { mutableStateListOf<KeyMetric>().apply { addAll(initial) } }
    // Every metric not shown is offered, the default ones first: Skin Temp and Asleep sit outside a saved
    // layout until the owner adds them here, so listing only the default order would hide them for good.
    val hidden = remember {
        mutableStateListOf<KeyMetric>().apply {
            addAll((KeyMetric.defaultOrder + KeyMetric.entries).distinct().filter { it !in initial })
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Palette.surfaceOverlay,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(uiString(R.string.l10n_today_screen_edit_key_metrics_f95e61a4), style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.l10n_today_screen_choose_which_tiles_show_on_your_a4e3acfb),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }

                HorizontalDivider(color = Palette.hairline, thickness = 1.dp)

                EditableVisibilityRows(
                    shown = shown,
                    hidden = hidden,
                    itemTitle = { titles.getValue(it) },
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            shown.clear()
                            shown.addAll(KeyMetric.defaultOrder)
                            hidden.clear()
                            hidden.addAll(KeyMetric.entries.filter { it !in KeyMetric.defaultOrder })
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Palette.textSecondary),
                    ) { Text(uiString(R.string.l10n_today_screen_reset_44c57abd), style = NoopType.body) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSave(shown.toList()) },
                        enabled = shown.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.accent,
                            contentColor = Palette.surfaceBase,
                        ),
                    ) { Text(uiString(R.string.l10n_today_screen_done_e9b450d1), style = NoopType.captionNumber) }
                }
            }
        }
    }
}
