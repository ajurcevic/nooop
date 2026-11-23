package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.BatteryEstimator
import com.noop.analytics.DayCycleMode
import com.noop.analytics.HydrationGoal
import com.noop.analytics.HydrationStore
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.protocol.Whoop5RR
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.ingest.HealthConnectImporter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun DisplayText.localized(): String = when (this) {
    is DisplayText.Resource -> uiString(id, *args.toTypedArray())
    is DisplayText.Dynamic -> value
}

@Composable
internal fun List<DisplayText>.localizedSourceList(): String {
    val labels = ArrayList<String>(size)
    for (label in this) labels += label.localized()
    return labels.joinToString(uiString(R.string.today_source_joiner))
}

/**
 * Control Center, the home dashboard. A recovery ring + plain-English synthesis
 * hero, an illness banner when the watch fires, and a tile grid of the day's key
 * metrics, each tile carrying a 14-day sparkline. Ports the macOS TodayView
 * composition (Strand/Screens/TodayView.swift) with the same locked components.
 *
 * Sparkline series are built off the view model's `recentDays` (oldest → newest,
 * all from the my-whoop source). Missing current-day values render as explicit
 * "No Data" states instead of raw dashes, so old imports do not look like today.
 */

/** Stable Today info-card ids (the dismissed-flag suffix + the inbox `restorePayload`). Match the
 *  iOS card ids so an export/import round-trips. */
private const val CARD_SCORES_BUILDING = "scoresBuilding"
private const val CARD_NEW_HERE = "newHere"
// #827: the "Building your baseline, N more nights" calibrating note is dismissible-into-the-inbox like
// the other Today info-cards, so a returning user who has read it once isn't nagged with it every day
// through the multi-night calibration window. Same id on both platforms so it round-trips an export/import.
private const val CARD_CALIBRATING = "calibratingBaseline"
// The "Latest sleep · <date>" / "Last night · <date>" carry-over note (ScoreState.CarriedLastNight). iOS
// has nothing in this slot, so on Android it's dismissible-into-the-inbox like the other Today info-cards:
// a small × tucks it into Updates (restorable), so it never sits permanently between the header and the
// hero throwing off the compact liquid look. Local-only id (iOS has no twin), matching the dismiss plumbing.
private const val CARD_CARRIED_SLEEP = "carriedSleep"

/** #860 item 1: process-lifetime guard for the launch snap-to-today. `selectedDayOffset` is rememberSaveable
 *  so a tab-away keeps the user's chosen day (#614/#739). The same persistence, however, rides the
 *  saved-instance-state bundle across a system-initiated process kill + restore (common after an app UPDATE),
 *  so a user who was browsing an OLD day when the process died - or a calibrating user the now-retired
 *  #605/#739 auto-land would have snapped to an old day - reopened the app pinned to that day instead of
 *  today. A top-level var = one value per LAUNCH (reset only on a genuine fresh process), so we run the pure
 *  `launchDayOffset` policy exactly once per launch (forcing today) and leave in-session tab-away/restore
 *  behaviour untouched. iOS parity: TodayView's selectedDayOffset is plain @State, which is never persisted
 *  and so already re-inits to 0 on every fresh launch, reaching the same offset through the same helper. */
private var todayDidSnapToTodayThisLaunch = false

// MARK: - Liquid hero tokens (the liquid Today restyle)
//
// The hero card the score rings float on, ported from the iOS LiquidTodayView. `heroFill` is a
// translucent near-black (mock rgba(13,14,20,.80)) so it floats over the day-of-sky; the rings + white
// count-up numbers read crisp on it. Radius 26 + a white@0.11 hairline give the frosted-glass edge.
private val LIQUID_HERO_RADIUS: Dp = 26.dp

// The Vitality ring purple (#9b7bff) — no exact Palette token in this theme, so a fixed brand literal
// matching the iOS liquid Today's `liquidPurple` (Color(.sRGB, red:0x9b, green:0x7b, blue:0xff)). Used by
// the mini "Your cards" ring so Vitality reads the same purple as iOS.
internal val LIQUID_PURPLE: Color = Color(red = 0x9b / 255f, green = 0x7b / 255f, blue = 0xff / 255f, alpha = 1f)

/**
 * The minimal, stable slice of the BLE [com.noop.ble.LiveState] the Today top-level body reads. Pulled out
 * so a per-second heart-rate tick, which the body does not display numerically, produces an EQUAL value
 * and skips recomposing the whole dashboard (the redesign's scroll-jank fix). `hrStreaming` collapses the
 * ticking bpm to "is a live stream present" (the only thing the recording light needs); all other fields
 * change at most every few seconds. A plain data class so [androidx.compose.runtime.derivedStateOf] can
 * structurally-compare successive snapshots and emit only on a real change.
 */
private data class TodayLiveSnapshot(
    val connected: Boolean,
    val bonded: Boolean,
    val hrStreaming: Boolean,
    val lastSyncAt: Long?,
    val backfilling: Boolean,
    val syncChunksThisSession: Int,
    /** Mirrors `LiveState.historyReady` — whether this strap can actually hand over history. Flips at
     *  most once per connection, so it costs the snapshot nothing. */
    val historyReady: Boolean,
    val historySyncExperimental: Boolean,
    /** Whether a scan is running, so the header chip can say so and refuse a second tap while it is
     *  (#2169). Flips twice per scan rather than per tick, so it costs this snapshot nothing. */
    val scanning: Boolean,
    /** #689/#815: the connect-time GET_DATA_RANGE backlog sample, when known. Set once per connection,
     *  so it adds no per-tick churn to this snapshot. */
    val pagesBehindAtConnect: Int?,
    val batteryPct: Double?,
    /** True once a WHOOP 5/MG strap has been seen this session, picks the 5/MG rated-life fallback for the
     *  battery runtime estimate (#713). Changes at most once per connection, so it doesn't reintroduce the
     *  per-tick churn the snapshot exists to avoid. */
    val whoop5: Boolean,
    /** Charging hides the runtime estimate (no "X left" while topping up). Rare flips, snapshot-safe. */
    val charging: Boolean?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun TodayScreen(
    viewModel: AppViewModel,
    onQuickActions: () -> Unit = {},
    updateStore: UpdateStore? = null,
    onOpenUpdates: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHydration: () -> Unit = {},
    // #706/#684: the "Your cards" dashboard rows are tappable on iOS but only Hydration navigated on Android.
    // These push each card's detail (Stress card -> Stress; Sleep -> Sleep), matching the iOS pinnedCardRow
    // destinations. Defaulted to no-ops so the call site stays compiling; AppRoot binds them to nav.navigate(...)
    // like onOpenHydration.
    onOpenStress: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
    // Every metric/vital card (HRV, Resting HR, Respiratory, SpO₂, Skin Temp, Fitness age, Vitality, Steps,
    // Calories) opens ITS OWN focused detail trend, not the shared Health hub (2026-07-03: cards were
    // wrongly dumping into the Health monitor). Mirrors the iOS liquidCard `metricDetail(key)`. Takes the
    // vital_detail key; defaults to the Health screen so an unbound caller keeps the old behaviour.
    onOpenMetric: (String) -> Unit = { onOpenHealth() },
    // A blank WHOOP 4.0 Steps tile can open calibration directly (#1515). AppRoot binds this to the same
    // nested destination Settings uses; other Steps states continue through [onOpenMetric].
    onOpenStepsCalibration: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    // Optional Coupled view card (task #43): a tap-through to the WHOOP-style day screen. Defaulted to a
    // no-op so the call site stays compiling; AppRoot binds it to nav.navigate(CoupledView).
    onOpenCoupled: () -> Unit = {},
    // The liquid header battery ring taps through to Devices (iOS parity: the battery ring → router.openDevices()).
    // Defaulted to fall back to Settings so the call site stays compiling; AppRoot binds it to the Devices route.
    onOpenDevices: () -> Unit = onOpenSettings,
    // HOME is the bottom-bar Today tab: live heart rate, the three scores and the three recovery vitals,
    // sized to fit one screen. METRICS is the Metrics tab: every other Today section, still arrangeable.
    mode: TodayMode = TodayMode.HOME,
) {
    val homeMode = mode == TodayMode.HOME
    val today by viewModel.today.collectAsStateWithLifecycle()
    val alert by viewModel.healthAlert.collectAsStateWithLifecycle()
    val days by viewModel.recentDays.collectAsStateWithLifecycle()
    // #2525: the Charge baselines the engine's rule resolves, the one source for every Charge readout below
    // the headline (calibration count, "What shaped it", confidence tier).
    val chargeBaselines by viewModel.chargeBaselines.collectAsStateWithLifecycle()
    val activeDayCycle by viewModel.activeDayCycle.collectAsStateWithLifecycle()
    val spo2CandidateByDay by viewModel.spo2CandidateByDay.collectAsStateWithLifecycle()
    // #2208: `connected` alone never said WHOSE charge liveSnap.batteryPct is. It goes true the moment any
    // source streams, and the strap's percentage is never cleared, so under an active ring both halves of
    // the old gate passed and Today drew the strap's charge. Same seam the Devices list already uses.
    val activeIsWhoop by viewModel.activeIsWhoop.collectAsStateWithLifecycle()
    val v5Signals by viewModel.v5Signals.collectAsStateWithLifecycle()
    val cycleEnabled by viewModel.cycleTrackingEnabled.collectAsStateWithLifecycle()
    val cycleHidden by viewModel.cycleAwarenessHidden.collectAsStateWithLifecycle()
    val periodStarts by viewModel.periodStarts.collectAsStateWithLifecycle()
    var showCycleTracker by remember { mutableStateOf(false) }
    val live by viewModel.live.collectAsStateWithLifecycle()
    // The in-flight manual workout (single source of truth, survives an app kill via rehydration), so the
    // indicator card auto-appears/clears off this alone. Null↔non-null + the start drive the card; the
    // per-second clock ticks inside the card's own LaunchedEffect, never recomposing the Today body.
    // PERF (#scroll-jank): the BLE live state ticks the heart rate roughly once a second. Reading the raw
    // `live` object directly in this top-level body would recompose the ENTIRE Today tree (rings, cards,
    // scene-positioning) on every bpm change, visible as scroll stutter on real devices. The body only
    // needs a handful of stable, slow-changing fields, and the live HR matters here only as "is a stream
    // present" (null↔non-null), never the bpm number. Funnel those through a `derivedStateOf` snapshot so a
    // 72→73 bpm tick produces an EQUAL snapshot and the body is NOT recomposed; it only recomposes when
    // connection / sync / battery / streaming-presence actually change. The live bpm number is rendered
    // elsewhere (HeartRateTrendCard), which scopes its own collection. Appearance-preserving.
    // #2169: the ONE gate every scan entry point goes through. `connect()` called directly silently
    // does nothing on Android 12+ when the Bluetooth permission was denied or revoked (#1), so Today's
    // two new affordances use the same wrapper Settings' Re-scan and Live's Connect already use.
    var scanTapAt by remember { mutableStateOf(0L) }
    // Armed from INSIDE the granted callback, not from the tap. `rememberRequestScan` runs this either
    // straight away, when the permission is already held, or after the system dialog closes; timing the
    // reply from the tap instead would start a clock while that dialog was still open and then answer a
    // press that was proceeding perfectly well, with whatever stale note happened to be lying around.
    // A permanently denied permission still lands here, the launcher's callback firing either way, so
    // the case most worth explaining is the one this keeps.
    val requestScan = rememberRequestScan {
        scanTapAt = System.currentTimeMillis()
        viewModel.connect()
    }
    // #2169: Today can start a connect but had nowhere to report one that never starts. Every early
    // exit from a user-initiated connect writes an explanatory note and leaves `scanning` false, so
    // "Bluetooth is off. Turn it on, then tap Connect." and the Nearby-devices permission line were
    // being set and thrown away: Live and Onboarding render `statusNote`, Today does not. A tap that
    // cannot proceed therefore changed nothing on screen at all, which reads as a dead control.
    //
    // Shown only after a tap from HERE, and only when the tap did not get as far as scanning, so this
    // stays a reply to a press rather than Today quietly becoming a connection-status surface, which
    // is a larger question (#2179).
    var scanHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scanTapAt) {
        if (scanTapAt == 0L) return@LaunchedEffect
        scanHint = null
        // Long enough for the adapter checks and the scan start to settle, short enough to still read
        // as the answer to the press. The dialog is already behind us by here.
        delay(400)
        if (!live.scanning && !live.connected) scanHint = live.statusNote
        delay(4_500)
        scanHint = null
    }
    val liveSnap by remember {
        derivedStateOf {
            val s = live
            TodayLiveSnapshot(
                connected = s.connected,
                bonded = s.bonded,
                hrStreaming = s.heartRate != null,
                lastSyncAt = s.lastSyncAt,
                backfilling = s.backfilling,
                syncChunksThisSession = s.syncChunksThisSession,
                historyReady = s.historyReady,
                historySyncExperimental = s.historySyncExperimental,
                scanning = s.scanning,
                pagesBehindAtConnect = s.pagesBehindAtConnect,
                batteryPct = s.batteryPct,
                whoop5 = s.whoop5Detected,
                charging = s.charging,
            )
        }
    }
    // #849: seed from the ViewModel cache so a re-mount (tab-return / post-import) restores the last footer
    // immediately instead of flashing empty while the heavy reload is (now) skipped for unchanged data.
    var footer by remember { mutableStateOf(viewModel.todayFooterCache ?: TodayFooterState()) }
    // rememberSaveable (not plain remember): the bottom-tab NavHost (AppRoot) navigates with
    // saveState/restoreState, which only restores rememberSaveable-backed state. With plain remember a
    // tab-away wiped the chosen day back to 0, so on return the dashboard "shifted" off the day the user was
    // looking at (#614 follow-up). Persisting it across the save/restore keeps the chosen day put. The
    // launch snap-to-today is a separate process-lifetime flag (todayDidSnapToTodayThisLaunch below).
    var selectedDayOffset by rememberSaveable { mutableIntStateOf(0) }
    // #860 item 1: on a GENUINE fresh process (not a tab-away/recomposition), force the selected day back to
    // today via the pure `launchDayOffset` policy. rememberSaveable restores selectedDayOffset from the
    // saved-instance-state bundle, which the system reuses across a process kill + restore (the after-an-update
    // case in the report); without this, a user who was viewing an old day when the process died - OR a
    // calibrating user the retired auto-land would have snapped to an old day - reopened the app stranded
    // there. The top-level guard is false exactly once per launch, so `launchDayOffset(isFreshLaunch = true)`
    // forces today a single time and never fights the in-session tab-away day-memory (#614/#739) afterwards.
    // Done in composition (not a LaunchedEffect) so the stale restored day never paints for a frame. iOS uses
    // plain @State (re-inits to 0 every launch) and reaches the same offset through the same helper.
    if (!todayDidSnapToTodayThisLaunch) {
        todayDidSnapToTodayThisLaunch = true
        val landed = launchDayOffset(
            isFreshLaunch = true,
            savedOffset = selectedDayOffset,
            hasTodayData = today != null,
            latestDataDayBack = selectedDayOffset,
        )
        if (selectedDayOffset != landed) selectedDayOffset = landed
    }
    // Anchor offset-0 to the LOGICAL day (rolls at 04:00 local), so between midnight and 4am "Today"
    // still resolves to the prior calendar day's banked row instead of an empty new-calendar-day row
    // that blanks the dashboard (#144). Past offsets count back from this anchor. Presentation-only.
    val todayDate = logicalDayNow()
    // #860 item 1: the launch auto-land (#605/#739 "snap to the most recent data day when today is empty")
    // is RETIRED. It fired on a fresh process when today had no row yet, and for a calibrating user whose
    // newest data was a few days back it stranded them on that old day, overriding the snap-to-today above.
    // A fresh launch now lands on today via `launchDayOffset` (the inline guard above), and in-session day
    // memory (#739/#614) is preserved because nothing rewrites `selectedDayOffset` after launch. iOS parity
    // in TodayView (which retired the same block).
    val selectedDay = remember(selectedDayOffset, todayDate) { todayDate.minusDays(selectedDayOffset.toLong()) }
    // The key the day-scoped read-outs (Rest score, HR window, sleep band) key on. At offset 0 it
    // follows the resolver's `today?.day` so it tracks the row actually surfaced, including the non-UTC
    // pre-04:00 case (#304) where Today is the LOCAL-calendar-day row, not the logical-day one. Falls
    // back to the logical key when no row is banked yet. Past offsets use the logical key directly.
    val selectedDayKey = remember(selectedDay, today, selectedDayOffset) {
        if (selectedDayOffset == 0) today?.day ?: selectedDay.toString() else selectedDay.toString()
    }
    val historicalMetric = remember(days, selectedDayKey) { days.lastOrNull { it.day == selectedDayKey } }
    val displayMetric = remember(today, historicalMetric, selectedDayOffset) {
        if (selectedDayOffset == 0) today ?: historicalMetric else historicalMetric
    }
    val dayCycleMode = NoopPrefs.dayCycleMode(LocalContext.current)
    // Steps alone use the physiological sleep-onset cycle. Every other field stays on the dashboard's
    // existing logical-day row; past-day navigation continues to show the persisted historical cycle row.
    val stepResolvedDisplayMetric = remember(displayMetric, activeDayCycle, selectedDayOffset, dayCycleMode) {
        val cycleSteps = activeDayCycle?.steps
        if (selectedDayOffset == 0 && dayCycleMode == DayCycleMode.SLEEP_ONSET && cycleSteps != null) {
            displayMetric?.copy(steps = cycleSteps)
        } else {
            displayMetric
        }
    }
    // Keep the explicit calendar date visible alongside Today/Yesterday so the logical-day remap stays
    // honest, between midnight and 04:00 "Today" still points at the prior calendar date, and showing
    // that date makes it obvious which day's row is on screen (#144).
    val dayLabel = run {
        // Date the label by the row ACTUALLY on screen, not the raw logical date. `selectedDayKey` already
        // follows the resolver's `today?.day` at offset 0, so when the resolver surfaces yesterday's
        // complete row (today not scored yet) the date now reads that row's day, instead of stamping
        // "Today · <today>" over yesterday's values, which disagreed with the Intelligence History row for
        // the same data (#434). iOS/Mac already label by the shown row's day; this brings Android to parity.
        val keyDate = runCatching { LocalDate.parse(selectedDayKey) }.getOrNull() ?: selectedDay
        val date = keyDate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        when (selectedDayOffset) {
            0 -> uiString(R.string.today_day_with_date, date)
            1 -> uiString(R.string.today_yesterday_with_date, date)
            else -> date
        }
    }
    // Display-only unit system + the SI profile weight, read once like every other Settings-backed
    // preference (SharedPreferences isn't reactive, a Settings write triggers recomposition).
    val context = LocalContext.current
    val unitSystem = UnitPrefs.system(context)
    // Effort display scale (#268), drives the Effort tile's value + caption. Display-only.
    val effortScale = UnitPrefs.effortScale(context)
    val profileWeightKg = remember { ProfileStore.from(context).weightKg }
    // Body profile for the live Effort computation below, age/sex/HR-max-override drive the same
    // StrainScorer call the daily pass uses. Read once like every other Settings-backed value. (#402)
    val profileStore = remember { ProfileStore.from(context) }

    // Editable Key-Metrics layout (#251), an ordered list of the enabled tiles, persisted display-only.
    // SharedPreferences isn't reactive, so it's mirrored into local state and re-read when the editor saves.
    var showMetricsEditor by remember { mutableStateOf(false) }
    var enabledKeyMetrics by remember { mutableStateOf(KeyMetricPrefs.enabled(context)) }
    // The Metrics tile opened in place (its KeyMetric raw key), one at a time; null when all are closed.
    // Saveable, so a tab-away and back keeps it open like the selected day.
    var expandedMetric by rememberSaveable { mutableStateOf<String?>(null) }
    // #today-layout: the user-ordered below-hero section list + its editor dialog flag. Read once (prefs
    // aren't reactive) and re-read on the editor's save, exactly like enabledKeyMetrics above.
    var showLayoutEditor by remember { mutableStateOf(false) }
    var sectionOrder by remember { mutableStateOf(TodayLayoutPrefs.order(context)) }
    var hiddenSections by remember { mutableStateOf(TodayLayoutPrefs.hidden(context)) }
    // #today-layout (hold-to-drag): the hoisted list state (the drag math needs layoutInfo + scrollBy) and
    // the live drag state. The frame loop below runs ONLY while a section is lifted: each frame it retries
    // the swap (so a card held still at a viewport edge keeps reordering as the list scrolls under it —
    // onDrag alone only fires while the finger moves) and applies the edge auto-scroll velocity that
    // TodayReorderableSection's onDrag computed.
    val todayListState = rememberLazyListState()
    val sectionDrag = remember { TodaySectionDragState() }
    val sectionDragActive = sectionDrag.key != null
    LaunchedEffect(sectionDragActive) {
        // Auto-scroll is TIME-based (px/second × real frame delta), not per-frame: a per-frame step runs
        // twice as fast on a 120 Hz panel and reads as jarring — the first on-device feedback. dt is
        // clamped so a dropped/backgrounded frame can't produce one giant jump.
        var lastFrameNanos = 0L
        while (sectionDrag.key != null) {
            val frameNanos = withFrameNanos { it }
            val dtSec = if (lastFrameNanos == 0L) 0f
            else ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrameNanos = frameNanos
            swapTargetForDraggedSection(todayListState, sectionDrag, sectionOrder)?.let { (dragged, target) ->
                // Freeze the scroll anchor across the reorder. LazyColumn re-anchors the viewport to the
                // FIRST VISIBLE item's key — when a swap involves that item (usual while dragging near the
                // top of the screen), the whole content leaps by the two cards' height difference in a
                // single frame (the on-device "not smooth with other cards" report). Re-pinning the same
                // positional index+offset around the move keeps the viewport still; a swap far below the
                // anchor re-pins to the identical spot (visual no-op).
                val anchorIndex = todayListState.firstVisibleItemIndex
                val anchorOffset = todayListState.firstVisibleItemScrollOffset
                sectionOrder = sectionOrder.movedTodaySection(dragged, target)
                todayListState.scrollToItem(anchorIndex, anchorOffset)
            }
            if (sectionDrag.autoScrollPxPerSecond != 0f && dtSec > 0f) {
                todayListState.scrollBy(sectionDrag.autoScrollPxPerSecond * dtSec)
            }
        }
    }

    // "Your cards" customisable dashboard (WHOOP "My Dashboard"), a persisted, reorderable selection of
    // metric cards. Empty/unset shows the sensible default set (Stress / Fitness age / Vitality + HRV +
    // Resting HR). The "CUSTOMISE" link on the section header opens a local sheet (no new nav destination).
    // Persistence is display-only, these cards read the SAME values the rest of Today already loads.
    // SharedPreferences isn't reactive, so it's mirrored into local state and re-read when the editor saves.
    var showDashboardEditor by remember { mutableStateOf(false) }
    var enabledDashboardCards by remember { mutableStateOf(DashboardCardPrefs.enabled(context)) }
    // #today-hosted-cards: the Trends/Sleep cards hosted in Today (empty/opt-in). Mirrored into local
    // state like the dashboard selection and re-read when the hosted-cards editor saves.
    var showHostedEditor by remember { mutableStateOf(false) }
    var enabledHostedCards by remember { mutableStateOf(HostedCardPrefs.enabled(context)) }

    // The pinned "Your cards" values (Stress / Fitness age / Vitality), surfaced on Today so the buried
    // Explore features sit on the home screen (#582). The same merged resolvedSeries reads their detail
    // screens use; null simply renders a dash on that card. Mirror the iOS Today lane's stressToday /
    // fitnessAgeToday / vitalityToday loads (last resolved value over all history). Loaded off the main
    // thread; re-read as the data grows.
    // #849: seed from the ViewModel cache so a re-mount restores the pinned-card numbers instead of flashing
    // dashes while the heavy history-wide read is (now) skipped for unchanged data.
    var stressToday by remember { mutableStateOf(viewModel.todayStressCache) }
    var fitnessAgeToday by remember { mutableStateOf(viewModel.todayFitnessAgeCache) }
    var vo2maxToday by remember { mutableStateOf(viewModel.todayVo2maxCache) }
    var vitalityToday by remember { mutableStateOf(viewModel.todayVitalityCache) }
    LaunchedEffect(days) {
        // #849 re-mount guard: skip the whole-history scan when `days` is content-identical to the last load
        // (data class hashCode is a stable structural signature). The marker + cached values live on the
        // long-lived ViewModel, so a tab-return / post-import re-mount restores the numbers without re-reading.
        val sig = days.hashCode()
        if (viewModel.todayCardsLoadedSig == sig) return@LaunchedEffect
        // Read each pinned card from the SAME source its own detail screen reads, the proven path that
        // already shows real numbers there (and the resolution iOS's exploreSeries uses). Stress is derived
        // from the imported strap data (StressScreen reads "my-whoop"); Fitness age + Vitality are
        // NOOP-COMPUTED weekly scores the IntelligenceEngine writes under "<activeStrapId>-noop". Read them
        // through the computed UNION (active strap's sibling + canonical "my-whoop-noop"), the same helper
        // HealthScreen uses — a hardcoded "my-whoop-noop" misses a live-BLE strap's "whoop-<mac>-noop" (#349).
        // Take the latest value (series are day-ascending), null → the card shows a dash, never a fabricated number.
        // #753: build the SAME StressModel the detail screen (StressScreen) shows and take `model.score`,
        // rather than the stress series' last banked row. StressModel.build prefers today's stored stress row
        // but otherwise DERIVES today's score from the live `days` RHR/HRV baseline; the old `.lastOrNull()`
        // read returned the latest *banked* day, so on a day with no stored stress row the pinned card sat on
        // yesterday's number (e.g. "2") while the detail page moved on. Reading the stored series the same way
        // StressScreen does (day → value, clamped 0–3) and feeding the same `days` ties the two together; both
        // recompute off `days`, so the pinned card stays in sync. null (no usable signal) keeps the honest
        // "Calibrating" placeholder, matching StressScreen's empty state.
        // OFF the main thread. This reads the stress series over ALL history and then builds a model
        // that is O(n) across it, and a LaunchedEffect body runs on the UI thread, so on an install with
        // a long history it was holding frames on the home screen. The three reads below it are indexed
        // single-row lookups and are left as they are.
        stressToday = runCatching {
            withContext(Dispatchers.Default) {
                val stored = viewModel.repo.metricSeries("my-whoop", "stress", "0000-01-01", "9999-12-31")
                    .associate { it.day to it.value.coerceIn(0.0, 3.0) }
                StressModel.build(days, stored)?.score
            }
        }.getOrNull()
        fitnessAgeToday = runCatching {
            viewModel.repo.latestMetricComputedUnion(viewModel.activeStrapId, "fitness_age")?.value
        }.getOrNull()
        vo2maxToday = runCatching {
            viewModel.repo.latestMetricComputedUnion(viewModel.activeStrapId, "vo2max_est")?.value
        }.getOrNull()
        vitalityToday = runCatching {
            viewModel.repo.latestMetricComputedUnion(viewModel.activeStrapId, "vitality")?.value
        }.getOrNull()
        // Cache the computed triple + signature so a later re-mount with unchanged data restores them and
        // short-circuits the history-wide read above.
        viewModel.todayStressCache = stressToday
        viewModel.todayFitnessAgeCache = fitnessAgeToday
        viewModel.todayVo2maxCache = vo2maxToday
        viewModel.todayVitalityCache = vitalityToday
        viewModel.todayCardsLoadedSig = sig
    }

    // #713, strap battery runtime estimate ("~X left") for the Data-sources battery row. The battery lane
    // banks a SoC time series; here we read it and run the SHARED BatteryEstimator (the iOS twin computes the
    // same value off LiveState.batteryEstimate). Rated-life fallback is chosen by strap generation: WHOOP 5/MG
    // gets the ~12-day figure, WHOOP 4.0 the ~4.5-day one. Recomputed when the banked series grows (a new
    // reading lands ~every 8 min), when the link comes/goes, or when the strap generation resolves. Charging
    // hides it (no "X left" while topping up); a too-short discharge run returns null and the badge shows just
    // the %. Display rule: hours < 48 -> "~Nh left", else "~N days left"; null hides the estimate.
    var batteryEstimateText by rememberTodayCached<String?>(viewModel.todayUiCache, "today.batteryEstimateText", selectedDayKey, null)
    LaunchedEffect(liveSnap.connected, liveSnap.batteryPct, liveSnap.whoop5, liveSnap.charging) {
        batteryEstimateText = if (!liveSnap.connected || liveSnap.charging == true) {
            null
        } else {
            runCatching {
                val now = System.currentTimeMillis() / 1000
                // A wide window: SoC readings are sparse (~8 min apart), so a few days back is plenty for the
                // estimator to find the trailing discharge run and still cheap to load.
                val from = now - 14L * 86_400
                // #1304/#512: a 2nd active strap banks its SoC under "whoop-<uuid>", so a "my-whoop" read
                // returned empty and the runtime estimate went blank. Read the active strap's own battery.
                val samples = viewModel.repo.batterySamples(viewModel.activeStrapId, from, now, limit = 2_000)
                    .mapNotNull { s -> s.soc?.let { s.ts to it } }
                val rated = if (liveSnap.whoop5) BatteryEstimator.ratedLifeHoursWhoop5
                            else BatteryEstimator.ratedLifeHoursWhoop4
                // Battery test mode (Test Centre #713): emit the discharge-run / fitted-slope / gate ANALYSIS
                // trace, not only the per-reading "bank soc=" line. This LaunchedEffect re-runs on a natural
                // throttle (battery% / connection / charging changes), never a tight loop, and reuses the
                // samples + rated just loaded, so there is no extra Room read. estimateTrace returns the SAME
                // Estimate the badge shows, so no displayed number changes. Gated zero-cost when the mode is off
                // (one SharedPreferences bool read) and routed to the .battery-tagged strap log via externalLog.
                if (com.noop.testcentre.TestCentre.from(context)
                        .active(com.noop.testcentre.TestDomain.BATTERY)) {
                    for (line in BatteryEstimator.estimateTrace(samples, rated).second) {
                        viewModel.ble.externalLog(line, com.noop.testcentre.TestDomain.BATTERY)
                    }
                }
                BatteryEstimator.estimate(samples, rated)?.let { est ->
                    val hours = est.hoursRemaining
                    if (!hours.isFinite() || hours <= 0.0) null
                    else if (hours < 48) context.resources.getQuantityString(
                        R.plurals.today_strap_hours_left, hours.roundToInt(), hours.roundToInt(),
                    )
                    else {
                        val daysLeft = (hours / 24).roundToInt()
                        context.resources.getQuantityString(
                            R.plurals.today_strap_days_left, daysLeft, daysLeft,
                        )
                    }
                }
            }.getOrNull()
        }
    }

    // #616: ONE calorie definition across the card, the Key-Metrics tile and the detail — resolve per day,
    // IMPORTED-FIRST (the phone's Apple/Health-Connect activeKcal, the figure these surfaces already showed),
    // falling back to NOOP's on-device HR estimate (activeKcalEst) only for days the phone didn't cover.
    // Keyed by day; `caloriesByDay` feeds the SELECTED-day value the dashboard card + Key-Metrics tile both
    // read — day-scoped like every other card, and like steps.
    var caloriesByDay by rememberTodayCached<Map<String, Double>>(viewModel.todayUiCache, "today.caloriesByDay", selectedDayKey, emptyMap())
    // The days whose figure above is the on-device HR estimate (no phone import that day), so the tile
    // can say so. Resolved in the same pass as caloriesByDay, from the same two maps.
    var caloriesEstimatedDays by rememberTodayCached<Set<String>>(viewModel.todayUiCache, "today.caloriesEstimatedDays", selectedDayKey, emptySet())
    LaunchedEffect(days) {
        caloriesByDay = runCatching {
            val onDevice = viewModel.repo.resolvedSeries("active_kcal", "my-whoop", "0000-00-00", "9999-99-99",
                strapDeviceId = viewModel.activeStrapId).points.associate { it.day to it.value }
            val imported = LinkedHashMap<String, Double>()
            for (r in viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31") +
                viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31")) {
                r.activeKcal?.takeIf { it > 0 }?.let { imported.putIfAbsent(r.day, it) }
            }
            caloriesEstimatedDays = onDevice.keys - imported.keys
            (onDevice.keys + imported.keys)
                .mapNotNull { day -> (imported[day] ?: onDevice[day])?.let { day to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    // HYDRATION (opt-in, default OFF), the Today "Hydration" card + its detail are hidden unless the user
    // turns Hydration tracking on in Settings. When on, the card reads today's logged total (ml, from the
    // local-only HydrationStore series) against the pure HydrationGoal (sex baseline + today's Effort bump).
    // Both are loaded off the main thread and re-read as the day's data grows; SharedPreferences isn't
    // reactive, so the toggle is read once into local state.
    val hydrationEnabled = remember { NoopPrefs.hydrationTracking(context) }
    // Day-cycle scene backdrop (#698). Default ON. When off, Today drops the SceneScreenBackground and
    // the scaffold paints the plain dark surface canvas instead. SharedPreferences isn't reactive, so
    // this is read once into local state (mirrors iOS @AppStorage in TodayView).
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    // "Sky behind cards" (opt-in, default OFF): extend the day-cycle sky behind the WHOLE scroll so the
    // Card-transparency slider reveals it under every card (no effect when the scene is off). Read once.
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }
    var hydrationTotalMl by rememberTodayCached(viewModel.todayUiCache, "today.hydrationTotalMl", selectedDayKey, 0.0)
    // #989: `days` only changes on a data refresh, which a hydration write never causes, so the card sat
    // stale after logging a drink until an unrelated sync landed. Keying on the store's mutationSeq too
    // re-reads the one metric row the moment a drink is logged / edited / deleted. Mirrors the iOS
    // Repository.hydrationSeq trigger.
    val hydrationSeq by HydrationStore.mutationSeq.collectAsStateWithLifecycle()
    LaunchedEffect(days, hydrationEnabled, hydrationSeq) {
        hydrationTotalMl = if (hydrationEnabled) {
            runCatching { HydrationStore.total(viewModel.repo) }.getOrDefault(0.0)
        } else 0.0
    }
    // The day's Effort/strain (0..100) drives the goal's effort bump. Prefer the live in-progress Effort
    // for today (floored at the stored value, mirroring the Effort gauge) so the goal reflects a hard day
    // as it accrues; null leaves the bump at 0. Computed below where liveTodayStrain is in scope.
    val hydrationGoalMl = remember(displayMetric, profileStore) {
        if (!hydrationEnabled) 0 else HydrationGoal.dailyGoalMl(profileStore.sex, displayMetric?.strain)
    }

    // "How your scores work" guide, opened from the per-score ⓘ affordances and the one-time
    // first-run card. `guideSection` carries which score to deep-link to (null = open at the top);
    // `showGuide` gates the presenting Dialog. The first-run card's seen-state lives in
    // ScoringGuidePrefs and is read once (SharedPreferences isn't reactive), then driven locally.
    var showGuide by remember { mutableStateOf(false) }
    var guideSection by remember { mutableStateOf<ScoreSection?>(null) }
    val openGuide: (ScoreSection?) -> Unit = { section ->
        guideSection = section
        showGuide = true
    }
    // A1 (#514/#706): the Charge breakdown sheet, opened by tapping the hero Charge ring. Hosts the
    // existing RecoveryDriversSection (gated to the calibration countdown when the night can't score) plus
    // the folded Readiness card (S4). Not persisted, so it reopens closed. Mirrors iOS showChargeBreakdown.
    var showChargeBreakdown by remember { mutableStateOf(false) }
    // #1694: the Latest-Workouts tile that was tapped. Held HERE, not inside the section: the Today
    // sections are LazyColumn items, and a disposed item would take an open sheet down with it.
    var selectedWorkoutRow by remember { mutableStateOf<WorkoutRow?>(null) }
    // S5: the Data Sources footer collapses to a single "Synced from: ..." line. It defaults collapsed and
    // is NOT persisted, so the screen reopens compact. Mirrors iOS.
    var sourcesExpanded by remember { mutableStateOf(false) }

    // Per-card "dismissed into the inbox" flags for the two Today info-cards. A small × on each card
    // sets these (and posts a `.dismissedCard` update); "Restore to Today" in the inbox flips them back
    // via the shared TodayCardDismissal key. Read once (SharedPreferences isn't reactive), driven locally.
    var scoresBuildingDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_SCORES_BUILDING))
    }
    var newHereDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_NEW_HERE))
    }
    // #827: the calibrating note's own dismissed flag, read once from the same shared store.
    var calibratingDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_CALIBRATING))
    }
    // The carried "Latest sleep · <date>" note's dismissed flag (iOS has no such card; on Android it's
    // dismissible so it doesn't sit permanently above the hero and break the compact look). Read once.
    var carriedSleepDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_CARRIED_SLEEP))
    }
    // Dismiss a Today info-card INTO the inbox: persist its flag, hide it, and post a restorable
    // `.dismissedCard` update carrying the card id. Mirrors the iOS `dismissTodayCard`.
    val dismissTodayCard: (String, String, String) -> Unit = { id, title, message ->
        TodayCardDismissal.setDismissed(context, id, true)
        when (id) {
            CARD_SCORES_BUILDING -> scoresBuildingDismissed = true
            CARD_NEW_HERE -> newHereDismissed = true
            CARD_CALIBRATING -> calibratingDismissed = true
            CARD_CARRIED_SLEEP -> carriedSleepDismissed = true
        }
        updateStore?.post(
            UpdateItem(
                kind = UpdateKind.DISMISSED_CARD,
                title = title,
                message = message,
                restorePayload = id,
            ),
        )
    }
    // Honour a "Restore to Today" tap from the inbox: flip the matching dismissed flag back so the card
    // reappears (the inbox also cleared the shared pref directly, but this re-reads it into local state
    // for an already-mounted Today). Cleared once handled. Mirrors the iOS restoreRequest observer.
    val restoreSignal = updateStore?.restoreRequest
    LaunchedEffect(restoreSignal) {
        if (updateStore != null && restoreSignal != null) {
            when (restoreSignal) {
                CARD_SCORES_BUILDING -> scoresBuildingDismissed = false
                CARD_NEW_HERE -> newHereDismissed = false
                CARD_CALIBRATING -> calibratingDismissed = false
                CARD_CARRIED_SLEEP -> carriedSleepDismissed = false
            }
            updateStore.restoreRequest = null
        }
    }

    // Announce NEW history to the inbox only when the NEWEST day-key (max yyyy-MM-dd) moves strictly
    // forward, not on a count change (#521). A background recompute rebuilds the window via
    // delete-then-reinsert, so the count momentarily dips and recovers while the newest key is unchanged
    //, keying off the count mistook that churn for new history and re-posted "New data added" on a
    // loop. The baseline is PERSISTED in SharedPreferences (not `remember`), so a relaunch over the same
    // history never re-announces. Empty baseline = first sight → record silently, never announce
    // historical data. The "added" count is the distinct days strictly above the old watermark, real,
    // never fabricated. Deep-links to Trends. Mirrors the Swift `announceNewDaysIfNeeded`.
    LaunchedEffect(days, updateStore) {
        val store = updateStore ?: return@LaunchedEffect
        val newestKey = days.maxOfOrNull { it.day } ?: return@LaunchedEffect   // no history yet
        val previousKey = NewDataWatermark.lastAnnouncedKey(context)
        NewDataWatermark.setLastAnnouncedKey(context, newestKey)
        if (previousKey.isEmpty()) return@LaunchedEffect            // first sight → silent baseline
        if (newestKey <= previousKey) return@LaunchedEffect         // recompute churn, not new history
        val added = days.map { it.day }.toSet().count { it > previousKey }
        if (added <= 0) return@LaunchedEffect
        store.post(
            UpdateItem(
                kind = UpdateKind.READING,
                title = uiString(R.string.l10n_today_screen_new_data_added_e59345b4),
                message = context.resources.getQuantityString(
                    R.plurals.today_new_history_ready, added, added,
                ),
                deepLink = "trends",
            ),
        )
    }

    // The newest Apple Health / Health Connect body weight, loaded off the main thread. Null until the
    // load runs or when neither source carries a weight, the Weight tile then falls back to the profile.
    // With "Use weight from Health Connect" ON it reads Health Connect only, the source the profile holds.
    var weightKg by rememberTodayCached<Double?>(viewModel.todayUiCache, "today.weightKg", selectedDayKey, null)
    // Every measured weight by day from the same rows, the history the Metrics Weight tile compares with.
    var weightHistory by rememberTodayCached<Map<String, Double>>(viewModel.todayUiCache, "today.weightHistory", selectedDayKey, emptyMap())
    val weightFromHealthConnect = ProfileStore.from(context).useHealthConnectWeight
    LaunchedEffect(days, weightFromHealthConnect) {
        val apple = viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31")
        val healthConnect = viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31")
        weightKg = latestWeightKg(apple, healthConnect, healthConnectOnly = weightFromHealthConnect)
        weightHistory = weightHistoryKg(apple, healthConnect, healthConnectOnly = weightFromHealthConnect)
    }

    // Steps for the selected day from imported Apple Health / Health Connect data, the Today Steps
    // tile's fallback when the strap itself didn't bank an on-device count. A WHOOP 4.0 DOES count
    // steps (in the official WHOOP app), but NOOP can't yet read them off the strap over Bluetooth, so
    // on a 4.0 the tile shows your imported steps instead of "No Data". Reloads as the day selector
    // moves. On-device WHOOP 5/MG steps still take precedence. (#150)
    var importedStepsForDay by rememberTodayCached<Int?>(viewModel.todayUiCache, "today.importedStepsForDay", selectedDayKey, null)
    // The same imported totals for every day, the history the Metrics Steps tile compares with.
    var importedStepsByDay by rememberTodayCached<Map<String, Double>>(viewModel.todayUiCache, "today.importedStepsByDay", selectedDayKey, emptyMap())
    var stepsAverage30 by rememberTodayCached<Pair<Double?, Int>>(viewModel.todayUiCache, "today.stepsAverage30", selectedDayKey, null to 0)
    LaunchedEffect(days, selectedDayKey, enabledDashboardCards, importedStepsForDay) {
        stepsAverage30 = if (DashboardCard.STEPS_AVERAGE_30 in enabledDashboardCards) {
            val readings = buildSeriesVitalDetail(viewModel, "steps_est")?.readings.orEmpty()
            rollingStepsAverage(readings, java.time.LocalDate.parse(selectedDayKey))
        } else null to 0
    }
    LaunchedEffect(days, selectedDayKey) {
        // Today's steps keep moving after the manual one-shot HC import, so the stored row goes
        // stale within minutes, top it up with ONE live StepsRecord read before the stored-row
        // read below. Best-effort: any HC hiccup just falls through to whatever is stored. (#150)
        if (selectedDayOffset == 0) {
            try {
                HealthConnectImporter.refreshTodaySteps(context, viewModel.repo)
            } catch (_: Exception) { /* best-effort */ }
        }
        val apple = viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31")
        val healthConnect = viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31")
        importedStepsForDay = stepsForDay(apple, healthConnect, selectedDayKey)
        importedStepsByDay = importedStepsHistory(apple, healthConnect)
    }

    // On-device steps ESTIMATE for the selected day (key "steps_est", computed "-noop" source). The
    // Steps tile prefers a REAL step count (strap @57 counter / imported Health Connect); only when a
    // day has NEITHER does it fall back to this estimate, shown with an "est." caption so it's never read
    // as a measured count. resolvedSeries reads the computed source for the my-whoop key, exactly like
    // the Explore "steps_est" metric. Null until loaded / no estimate for the day. (#150)
    var stepsEstForDay by rememberTodayCached<Int?>(viewModel.todayUiCache, "today.stepsEstForDay", selectedDayKey, null)
    // Every day's estimate, kept for the Metrics Steps tile's history on days with no counted steps.
    var stepsEstByDay by rememberTodayCached<Map<String, Double>>(viewModel.todayUiCache, "today.stepsEstByDay", selectedDayKey, emptyMap())
    LaunchedEffect(days, selectedDayKey) {
        val byDay = runCatching {
            viewModel.repo.resolvedSeries("steps_est", "my-whoop", "0000-00-00", "9999-99-99",
                strapDeviceId = viewModel.activeStrapId)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
        stepsEstByDay = byDay
        stepsEstForDay = byDay[selectedDayKey]?.let { Math.round(it).toInt() }
    }

    // The selected day's representative activity class for the Steps tile icon (#316 / @63). Reads the day's
    // step samples (now carrying `activityClass` after the v13 column) over the local-day window and takes the
    // LAST non-null class as "what the wrist was doing most recently today" (0=still, 1=walk, 2=run). null when
    // the day has no classed sample (a 4.0 strap, a pre-v13 row, or every record's @63 byte was invalid), then
    // the tile shows NO icon. Mirrors the iOS Today step-activity read exactly. Best-effort: a read hiccup just
    // drops the optional icon.
    var stepActivityClassForDay by rememberTodayCached<Int?>(viewModel.todayUiCache, "today.stepActivityClassForDay", selectedDayKey, null)
    LaunchedEffect(days, selectedDay, today) {
        val zone = ZoneId.systemDefault()
        val start = selectedDay.atStartOfDay(zone).toEpochSecond()
        val nextStart = selectedDay.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val now = System.currentTimeMillis() / 1000
        val end = if (selectedDayOffset == 0) now else (nextStart - 1)
        // #908 family: read the active strap ∪ canonical "my-whoop" union, NOT a hardcoded "my-whoop". A strap
        // re-added through the device manager banks its live step samples (which carry the @63 class) under its
        // own fresh id, so a pinned "my-whoop" read dropped the tile icon for a re-added strap. Single-WHOOP ⇒
        // one id ⇒ byte-identical read. Mirrors the iOS Repository.stepActivityClassLatest union.
        stepActivityClassForDay = runCatching {
            viewModel.repo.stepActivityClassLatestUnion(viewModel.activeStrapId, start, end)
        }.getOrNull()
    }

    // The Rest SCORE (0–100) for the selected day, IntelligenceEngine's Rest composite, written to the
    // `sleep_performance` metric series. The Key-Metrics "Rest" tile shows THIS, with hours-in-bed kept
    // as the caption; the tile previously showed hours where the score belonged (#248). resolvedSeries
    // merges imported + computed sleep_performance (imported-wins), so an importer sees the export's
    // figure and a Bluetooth-only user sees the on-device composite. Null until loaded / no night yet.
    var restScoreForDay by rememberTodayCached<Double?>(viewModel.todayUiCache, "today.restScoreForDay", selectedDayKey, null)
    // The same resolved Rest series keyed by day, kept so Home can compare the day's Rest with its own
    // trailing 7-day average without a second read.
    var restHistory by rememberTodayCached<Map<String, Double>>(viewModel.todayUiCache, "today.restHistory", selectedDayKey, emptyMap())
    LaunchedEffect(days, selectedDayKey, selectedDayOffset) {
        val byDay = runCatching {
            viewModel.repo.resolvedSeries("sleep_performance", "my-whoop", "0000-00-00", "9999-99-99",
                strapDeviceId = viewModel.activeStrapId)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
        restHistory = byDay
        // #977: the tail-fallback (latest scored night) is now freshness-gated. A live 5.0 whose sleep never
        // scores used to pin Rest to the weeks-old series tail forever while Charge advanced; if that tail is
        // stale, fall through to null so the Rest ring shows its needs-a-tracked-night state instead of a
        // frozen number. `selectedDayKey` is today's key at offset 0, so it anchors the freshness check.
        val latest = byDay.entries.maxByOrNull { it.key }
        restScoreForDay = freshRestScore(
            todayValue = byDay[selectedDayKey], lastDay = latest?.key, lastValue = latest?.value,
            isTodaySelected = selectedDayOffset == 0, today = selectedDayKey)
    }

    // Provenance (COMPONENT 4): the REAL per-metric merge winner for the selected day's three hero scores,
    // keyed by metric key ("recovery" / "strain" / "sleep_performance"); each value is the RAW source id the resolver
    // returned (e.g. "my-whoop", "my-whoop-noop", "apple-health"). resolvedSeries applies the SAME
    // imported-WHOOP > NOOP-computed > Apple-Health precedence the dashboard merge uses field-by-field
    // (WhoopRepository.mergeDaily), then computed rows resolve through durable input provenance so the
    // card-level badge names the sensor/import providers rather than where NOOP ran the math.
    var providerByMetric by rememberTodayCached<Map<String, ScoreInputProvider>>(viewModel.todayUiCache, "today.providerByMetric", selectedDayKey, emptyMap())
    LaunchedEffect(days, selectedDayKey, viewModel.activeStrapId) {
        val resolved = mutableMapOf<String, ScoreInputProvider>()
        for (key in listOf("recovery", "strain", "sleep_performance")) {
            val win = runCatching {
                viewModel.repo.resolvedSeries(key, "my-whoop", selectedDayKey, selectedDayKey,
                    strapDeviceId = viewModel.activeStrapId)
                    .points.lastOrNull { it.day == selectedDayKey }
            }.getOrNull()
            if (win != null) {
                viewModel.scoreInputProvider(win.source, win.day, key)?.let {
                    resolved[key] = it
                }
            }
        }
        providerByMetric = resolved
    }

    // LIVE in-progress Effort for TODAY (#402), mirrors the iOS TodayView live-Effort fix. The stored
    // `day?.strain` lags: early in the day it shows yesterday's completed Effort (or a stale 0.0) until the
    // heavy daily pass re-scores. So for offset 0 only, integrate today's raw HR over the SAME window the
    // HR trend uses (the logical day's local-midnight → now) through StrainScorer with the SAME params the
    // daily pass persists (Tanaka HR-max from age, or the manual override, the day's resting HR else the
    // default, profile sex), and prefer it on the Effort gauge. StrainScorer returns null below
    // `minReadings`, so before there's enough HR the gauge falls back to the stored value and never shows a
    // fabricated number. Any past day → null (the gauge uses the stored strain). Keyed on the same inputs
    // as the day-scoped loads so it reloads as the selector moves and as a sync/import grows the HR window.
    var liveTodayStrain by rememberTodayCached<Double?>(viewModel.todayUiCache, "today.liveTodayStrain", selectedDayKey, null)
    LaunchedEffect(days, selectedDayKey, selectedDayOffset, activeDayCycle, dayCycleMode) {
        liveTodayStrain = if (selectedDayOffset == 0) {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis() / 1000
            val start = activeDayCycleStart(
                mode = dayCycleMode,
                confirmedOrSyntheticOnset = activeDayCycle?.onsetTs,
                calendarStart = selectedDay.atStartOfDay(zone).toEpochSecond(),
            )
            // #908: read the active strap ∪ canonical "my-whoop" union, NOT a hardcoded "my-whoop". A strap
            // re-added through the device manager banks its live HR under its own fresh id, so a pinned
            // "my-whoop" read returned nothing and Effort integrated to 0 off an empty series. Single-WHOOP
            // install resolves to "my-whoop" ⇒ one id ⇒ byte-identical read.
            val todayHr = runCatching { viewModel.repo.hrSamplesUnion(viewModel.activeStrapId, start, now) }
                .getOrDefault(emptyList())
            // effMaxHR resolution matches AnalyticsEngine: manual HR-max override first, else Tanaka from age.
            val effMaxHR = profileStore.hrMaxOverride.takeIf { it > 0 }?.toDouble()
                ?: if (profileStore.age > 0) StrainScorer.tanakaHRmax(profileStore.age.toDouble()) else null
            val restingHr = displayMetric?.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR
            StrainScorer.strain(
                hr = todayHr,
                maxHR = effMaxHR,
                restingHR = restingHr,
                method = NoopPrefs.effortMethod(context),
                sex = profileStore.sex,
            )
        } else {
            null
        }
    }

    // #1001: Effort resolved ONCE for the whole screen, so the Key Metrics tile and the HR chart's edge
    // badge show what the hero ring shows. Both used to read `displayMetric.strain` straight off the daily
    // row, which only refreshes when the heavy daily pass runs — so an active morning read 2.3 on the ring
    // and 0.5 in the other two. The ring resolves the same way from the same rule (see ScoreHeroRow).
    val effortForDay = StrainScorer.effectiveEffort(
        live = if (selectedDayOffset == 0) liveTodayStrain else null,
        stored = displayMetric?.strain,
    )

    // Recovery cold-start: recovery is null until the HRV baseline crosses the seed gate
    // (Baselines.minNightsSeed valid nights). Show honest "calibrating, N of 4 nights" progress
    // instead of a bare "No Data" so a new BLE-only user knows scores are coming, not broken. (PR #85)
    val recoveryCalibration: Int? = if (selectedDayOffset == 0) {
        // Thread the persisted "Recalibrate HRV baseline" epoch (0 = none) so N folds the SAME
        // epoch-aware history the recovery engine folds — otherwise a post-recalibration user's pre-epoch
        // nights inflate the count past the seed gate and the score side wrongly reads NeedsStrap (Bug B).
        val hrvEpoch = NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble()
        recoveryCalibrationNights(
            chargeBaselines?.hrvHistory?.values.orEmpty(), chargeBaselines?.hrvHistory?.dayKeys.orEmpty(),
            displayMetric?.recovery != null, hrvEpoch,
        )
    } else {
        null
    }

    // The most recent fully-SCORED recovery day to carry over on TODAY while tonight's recovery hasn't
    // been scored yet (#543). Right after the logical-day rollover the new day has no recovery (the new
    // night isn't scored until you wear it tonight), so a baseline-established user, past calibration, so
    // recoveryCalibration is null, saw the WHOLE recovery side blank ("No Data" Charge AND blank HRV /
    // resting-HR / respiratory / SpO₂ tiles + Synthesis + Contributors) while live HR kept ticking, which
    // reads as broken. This is the ONE prior row every recovery-derived read-out carries over from, the
    // way WHOOP keeps showing last recovery until the new one lands, it NEVER fabricates a number for the
    // new day, each carried read shows the REAL prior value labelled as prior, and any metric the prior
    // row genuinely lacks still falls through to "No Data". Non-null only when: it's today, today has no
    // recovery, and we're not mid-calibration (calibration owns its own copy). days is oldest→newest;
    // exclude the (still-null) today key so we never echo "today". Mirrors iOS lastScoredRecoveryDay.
    // #547 carry-over upper bound: the LATER of the logical "today" (rolls at 04:00) and the local
    // calendar day. Using the later key means a legitimate just-after-midnight carry-over of yesterday's
    // logical day is NOT dropped, while any FUTURE-dated row (a bad strap clock) still sorts past it and
    // is excluded. ISO date strings compare chronologically.
    val carryOverTodayKey = remember(todayDate) {
        maxOf(todayDate.toString(), java.time.LocalDate.now().toString())
    }
    val lastScoredRecoveryDay: DailyMetric? = remember(days, selectedDayKey, recoveryCalibration, selectedDayOffset, displayMetric, carryOverTodayKey) {
        lastScoredRecoveryDay(
            days = days,
            selectedDayKey = selectedDayKey,
            isToday = selectedDayOffset == 0,
            todayScored = displayMetric?.recovery != null,
            isCalibrating = recoveryCalibration != null,
            today = carryOverTodayKey,
        )
    }
    // The freshest STRICTLY-PRIOR night carrying a real overnight VITAL (HRV / resting-HR / respiratory),
    // recovery-INDEPENDENT (#543 follow-up). HRV/RHR/resp exist without a recovery score, so a post-update
    // re-analysis that nulls last night's recovery while preserving its avgHrv/restingHr must NOT fall back
    // to an OLDER recovery-scored day for the vitals (that's the tile-vs-card mismatch: the per-field tiles
    // already keep last night's real value; the whole-row card was discarding it). The vitals read PER-FIELD
    // today-first with THIS carry as the fallback, kept separate from lastScoredRecoveryDay (Charge ring /
    // Synthesis / Contributors / Readiness stay recovery-gated). Future-clock-safe: the upper bound is the
    // LATER of the resolved today row's own key and carryOverTodayKey, mirroring lastScoredRecoveryDay's
    // #547 guard. Non-null only on today (offset 0). Mirrors iOS Repository.lastVitalsDay.
    val lastVitalsDay: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastVitalsRow(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    // PER-FIELD SpO₂ / skin-temp carries, the twin of lastVitalsDay for the two fields its predicate does
    // NOT check. The on-device engine writes spo2Pct = null (only raw spo2Red/spo2Ir), so every computed
    // "-noop" row lacks a percentage; only imported rows carry one. A whole-row carry (lastScoredRecoveryDay
    // or lastVitalsDay) therefore lands on a row with null spo2Pct/skinTempDevC and the Blood Oxygen /
    // Skin Temp cards read "No Data" even though an imported row holds a real reading. Resolving the two
    // fields independently (last strictly-prior row with the field non-null) mirrors iOS
    // TodayView.lastSpo2Day / lastSkinTempDay. Same #547 future-clock bound; non-null only on today.
    val lastSpo2Day: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastSpo2Row(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    // #1844: the carry must find a night with EITHER number now that these surfaces lead with the
    // absolute — a calibrating night has a real temperature and no deviation yet, and lastSkinTempRow
    // (deviation-only) would skip straight past it. Both Today consumers below want that row.
    val lastSkinTempDay: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastSkinTempReadingRow(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    // #1842: per-field HRV / resting-HR carries, the twins of the SpO₂ and skin-temp rows above. Same
    // shape, same future-clock bound; see lastHrvRow for why the shared lastVitalsRow is not enough.
    val lastHrvDay: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastHrvRow(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    val lastRestingHrDay: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastRestingHrRow(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    // PER-FIELD respiratory carry (#1331): the freshest strictly-prior row that actually HAS a breaths/min,
    // since lastVitalsDay can land on a night with HRV/RHR but no respiratory. Twin of lastSpo2Day.
    val lastRespDay: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastRespRow(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    // Carry-over Charge for TODAY, the prior scored row's recovery + its "Last night · <date>" caption.
    // Derived from lastScoredRecoveryDay so Charge and every other recovery tile carry the SAME prior day.
    val carriedChargeCaption = lastScoredRecoveryDay?.let { carriedCaption(it.day, carryOverTodayKey).localized() }
    val lastScoredCharge: LastCharge? = remember(lastScoredRecoveryDay, carriedChargeCaption) {
        lastScoredRecoveryDay?.let { prior ->
            prior.recovery?.let { LastCharge(it, carriedChargeCaption.orEmpty()) }
        }
    }
    var carriedRecoveryProvider by rememberTodayCached<ScoreInputProvider?>(viewModel.todayUiCache, "today.carriedRecoveryProvider", selectedDayKey, null)
    LaunchedEffect(lastScoredRecoveryDay?.day, viewModel.activeStrapId) {
        val carriedDay = lastScoredRecoveryDay?.day
        carriedRecoveryProvider = if (carriedDay == null) {
            null
        } else {
            runCatching {
                viewModel.repo.resolvedSeries("recovery", "my-whoop", carriedDay, carriedDay,
                    strapDeviceId = viewModel.activeStrapId)
                    .points.lastOrNull { it.day == carriedDay }
                    ?.let { viewModel.scoreInputProvider(it.source, it.day, "recovery") }
            }.getOrNull()
        }
    }

    // #1505: whether this strap's R-R is read under the WHOOP 5 single-transport unit policy, plus the
    // two local days that bound the era it could not score: the first day it banked ANY beat, and the
    // first day it banked a scorable one (null when it has banked none). Three cheap reads (a registry
    // row, two indexed MINs), keyed on `days` so a sync that lands labelled beats moves the scorable day
    // instead of leaving a stale one telling a wearer last night could not be scored.
    // A read that throws leaves the flag off, which HIDES the note rather than showing a guessed one.
    var whoop5StrictRr by rememberTodayCached(viewModel.todayUiCache, "today.whoop5StrictRr", selectedDayKey, false)
    var firstRecordedRrDay by rememberTodayCached<String?>(viewModel.todayUiCache, "today.firstRecordedRrDay", selectedDayKey, null)
    var firstScorableRrDay by rememberTodayCached<String?>(viewModel.todayUiCache, "today.firstScorableRrDay", selectedDayKey, null)
    LaunchedEffect(days) {
        runCatching {
            // The ACTIVE strap, the id every other read on this screen threads. A re-pair leaves history
            // under the canonical alias, and the repository's own policy resolves that; asking about the
            // alias here would answer for whichever strap it inherited from.
            val owner = viewModel.activeStrapId
            // The plain local calendar date, which is the convention every banked `day` key on this
            // screen uses (`selectedDay.toString()`), NOT the 04:00 logical-day remap: these compare
            // against row keys, and remapping them would shift the boundary by a few hours.
            fun dayKey(ts: Long?) =
                ts?.let { LocalDate.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault()).toString() }
            whoop5StrictRr = viewModel.repo.isWhoop5RrSource(owner)
            firstRecordedRrDay = dayKey(viewModel.repo.firstRecordedRrTs(owner))
            firstScorableRrDay = dayKey(viewModel.repo.firstScorableWhoop5RrTs(owner))
        }.onFailure {
            whoop5StrictRr = false
            firstRecordedRrDay = null
            firstScorableRrDay = null
        }
    }

    // #1505: whether the SELECTED day's empty Charge is explained by the unit policy having no scorable
    // beats for that night. The judgement is the pure `Whoop5RR.legacyUnscorableNight`, shared byte for
    // byte with iOS; everything it reads is already on the row or loaded above.
    val chargeLegacyRrGap = remember(displayMetric, whoop5StrictRr, firstRecordedRrDay, firstScorableRrDay) {
        val row = displayMetric
        row != null && row.recovery == null && Whoop5RR.legacyUnscorableNight(
            strictWhoop5 = whoop5StrictRr,
            day = row.day,
            firstRecordedDay = firstRecordedRrDay,
            firstScorableDay = firstScorableRrDay,
            avgHrv = row.avgHrv,
            totalSleepMin = row.totalSleepMin,
        )
    }

    // Explainability (COMPONENT 2): the honest state of the score side for TODAY, scored / calibrating /
    // carried-last-night / needs-strap. One state, never a bare blank, and never a fabricated number. Only
    // computed for today (offset 0); a past day shows its own row, not a "needs the strap" prompt.
    val scoreState: ScoreState = remember(displayMetric, recoveryCalibration, lastScoredRecoveryDay, selectedDayOffset, carryOverTodayKey) {
        if (selectedDayOffset == 0) {
            scoreStateForToday(
                todayRecovery = displayMetric?.recovery,
                calibratingNights = recoveryCalibration,
                carriedDay = lastScoredRecoveryDay,
                today = carryOverTodayKey,
            )
        } else {
            ScoreState.Scored(displayMetric?.recovery ?: 0.0)
        }
    }

    // One honest card-level badge, matching LiquidTodayView: identical winners collapse to one label;
    // mixed winners show at most two sources in Charge / Effort / Rest order so the pill stays compact.
    val heroSourceParts = remember(providerByMetric, carriedRecoveryProvider, displayMetric?.recovery, lastScoredCharge) {
        scoreHeroSourceLabel(
            providerByMetric = providerByMetric,
            carriedRecoveryProvider = carriedRecoveryProvider,
            usesCarriedRecovery = displayMetric?.recovery == null && lastScoredCharge != null,
        )
    }
    val heroSourceLabel = heroSourceParts.takeIf { it.isNotEmpty() }?.localizedSourceList()

    // The Metrics tab's tiles, every Key Metric set against its own 30-day baseline (MetricsTiles.kt). Home
    // draws none of them, so it skips the work. The day series are rebuilt only when the data changes.
    val metricHistories = remember(days) { MetricHistories(days) }
    val stepsEstimateCaptionText = stepsEstimateCaption(profileStore)
    val stepsCalibrationPromptText = stepsCalibrationPrompt(context, profileStore)
    val metricTiles: Map<KeyMetric, MetricTileUi> = if (homeMode) {
        emptyMap()
    } else {
        buildMetricTiles(
            histories = metricHistories,
            days = days,
            day = stepResolvedDisplayMetric,
            dayKey = selectedDayKey,
            isToday = selectedDayOffset == 0,
            recoveryCarryDay = lastScoredRecoveryDay,
            vitalsCarryDay = lastVitalsDay,
            hrvCarryDay = lastHrvDay,
            restingHrCarryDay = lastRestingHrDay,
            respCarryDay = lastRespDay,
            spo2CarryDay = lastSpo2Day,
            skinTempCarryDay = lastSkinTempDay,
            recoveryCalibration = recoveryCalibration,
            lastScoredCharge = lastScoredCharge,
            effortForDay = effortForDay,
            effortScale = effortScale,
            restScore = restScoreForDay,
            restHistory = restHistory,
            // #1164: today's Rest is provisional while the strap has banked records not yet offloaded.
            restPendingSync = restPendingSync(
                restScore = restScoreForDay,
                backfilling = live.backfilling,
                historyPendingSync = live.historyPendingSync,
                isTodaySelected = selectedDayOffset == 0,
            ),
            spo2CandidateByDay = spo2CandidateByDay,
            steps = StepsInputs(
                importedForDay = importedStepsForDay,
                estimatedForDay = stepsEstForDay,
                importedByDay = importedStepsByDay,
                estimatedByDay = stepsEstByDay,
                estimateCaption = stepsEstimateCaptionText,
                calibrationPrompt = stepsCalibrationPromptText,
            ),
            weight = WeightInputs(latestKg = weightKg, history = weightHistory, profileKg = profileWeightKg),
            unitSystem = unitSystem,
            caloriesByDay = caloriesByDay,
            skinTempPreferred = UnitPrefs.skinTempPreferred(context),
            fahrenheit = UnitPrefs.temperature(context) == TemperatureUnit.FAHRENHEIT,
            onOpenMetric = onOpenMetric,
            onOpenSleep = onOpenSleep,
            onOpenStepsCalibration = onOpenStepsCalibration,
            chargeConfidence = chargeConfidenceTier(chargeBaselines, stepResolvedDisplayMetric),
            caloriesEstimatedDays = caloriesEstimatedDays,
        )
    }

    LaunchedEffect(days) {
        // #849: this footer pass is the heavy one. It derives HR per imported workout from raw strap samples
        // (fillWorkoutHrFromStrap = potentially hundreds of raw-HR reads) and counts every workout / Apple /
        // Health-Connect row across ALL history. A bare Today re-mount (tab-away + return, or an Apple-Health
        // import that recreates the screen) re-fires this LaunchedEffect with the screen's `remember` state
        // reset, so it re-ran the full pass for byte-identical data every time: the lag users see returning
        // to Today after an import. The signature is `days` (a `data class` list, so its structural
        // hashCode is a stable content signature) PLUS the 14-day cross-source workout union: `days`
        // alone missed workouts imported without touching the Whoop day summaries (e.g. a Health
        // Connect session recorded today), so the "Last Workouts" feed stayed stale until the next
        // Whoop cycle bumped `days`. The union is a cheap windowed SELECT; only the heavy strap-HR
        // derivation and all-history counts below are skipped on a signature match. The marker lives
        // on the long-lived ViewModel, so it survives the re-mount that reset the screen state. A real
        // data change bumps the signature and re-runs, so no real update is dropped.
        val now = System.currentTimeMillis() / 1000
        val recentCutoff = LocalDate.now()
            .minusDays(13)
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
        val recentUnion = viewModel.repo.workoutsAllSources(viewModel.deviceId, recentCutoff, now)
            .sortedByDescending { it.startTs }
        val sig = 31 * days.hashCode() + recentUnion.hashCode()
        if (viewModel.todayFooterLoadedSig == sig) return@LaunchedEffect
        // Union of the active strap id + legacy "my-whoop" (#814), NOT the literal id alone: after a
        // re-pair the fresh recordings live under "whoop-<id>", and a pinned read undercounted them
        // in the Whoop pill exactly like the feed dropped them from "Latest Workouts".
        val whoopWorkouts = viewModel.repo.workoutsUnion(viewModel.deviceId, 0L, now)
        // Apple Health and Health Connect are separate sources (since #34), keep them separate in the
        // provenance footer too, so Health Connect data isn't mislabelled under the "Apple Health" pill
        // (issue #53). The recent-workouts list below still unions all sources for a combined feed.
        val appleWorkouts = viewModel.repo.workouts("apple-health", 0L, now)
        val hcWorkouts = viewModel.repo.workouts("health-connect", 0L, now)
        val appleDaysCount = viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31").size
        val hcDaysCount = viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31").size
        footer = TodayFooterState(
            // fillWorkoutHrFromStrap: imported sessions carry no HR, derive it from strap samples (#77).
            // #510: strap-native rows now read HR under their OWN recording strap (inside the fill), so a 2nd
            // WHOOP's workouts reconcile Avg HR + Effort from their own trace; imported rows keep the default.
            // #1601: the ACTIVE strap id, not the "my-whoop" default — see the AppViewModel call site.
            // Left defaulted, an imported row on a non-canonical install reads only the canonical id here
            // while every other surface reads active ∪ canonical, so its Avg HR stays blank against a
            // populated graph.
            recentWorkouts = viewModel.repo.fillWorkoutHrFromStrap(
                recentUnion,
                strapDeviceId = viewModel.deviceId,
                effortMethod = NoopPrefs.effortMethod(context),
            ),
            whoopDays = days.size,
            whoopWorkouts = whoopWorkouts.size,
            appleDays = appleDaysCount,
            appleWorkouts = appleWorkouts.size,
            hcDays = hcDaysCount,
            hcWorkouts = hcWorkouts.size,
        )
        // Cache the result + record the signature so a later re-mount with unchanged data restores the footer
        // and short-circuits the heavy reload above.
        viewModel.todayFooterCache = footer
        viewModel.todayFooterLoadedSig = sig
    }

    // #817 - horizontal swipe to change day, alongside the header chevrons. `detectHorizontalDragGestures`
    // only claims HORIZONTAL drags, so the LazyColumn keeps its vertical scroll; we accumulate the drag and
    // resolve the day ONCE on lift via the pure `dayNavSwipeTarget` (rightward = older, leftward = newer,
    // clamped at today). The threshold is density-scaled from DAY_NAV_SWIPE_THRESHOLD_DP so a small wobble
    // during a scroll doesn't flip the day. Mirrors the iOS day-nav swipe lane.
    val swipeThresholdPx = with(LocalDensity.current) { DAY_NAV_SWIPE_THRESHOLD_DP.dp.toPx() }
    val daySwipeModifier = Modifier.pointerInput(Unit) {
        var accumulatedX = 0f
        detectHorizontalDragGestures(
            onDragStart = { accumulatedX = 0f },
            onDragEnd = {
                selectedDayOffset = dayNavSwipeTarget(selectedDayOffset, accumulatedX, swipeThresholdPx)
            },
            onHorizontalDrag = { _, dragAmount -> accumulatedX += dragAmount },
        )
    }
    val canPullToSync =
        todayPullToSyncEnabled(liveSnap.connected, liveSnap.bonded, liveSnap.backfilling, liveSnap.historyReady)
    // material3 1.2.1's rememberPullToRefreshState CAPTURES the `enabled` lambda ONCE (rememberSaveable,
    // no rememberUpdatedState), so `{ canPullToSync }` would freeze the plain Boolean from the FIRST
    // composition — and Today usually first composes before the strap has (re)connected, leaving the
    // gesture permanently disabled for the session. Read the stable `liveSnap` State live inside the lambda
    // instead, so each gesture check sees the current connected/bonded/backfilling. (syncNow is triple-gated
    // anyway; this just makes the gesture actually enable once the strap is ready.)
    val pullToSyncState = rememberPullToRefreshState(
        enabled = {
            todayPullToSyncEnabled(
                liveSnap.connected, liveSnap.bonded, liveSnap.backfilling, liveSnap.historyReady,
            )
        },
    )
    LaunchedEffect(pullToSyncState.isRefreshing, canPullToSync) {
        if (pullToSyncState.isRefreshing) {
            if (canPullToSync) viewModel.syncNow()
            // Historical offloads can run for a while; the existing sync chip/note owns ongoing progress.
            pullToSyncState.endRefresh()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullToSyncState.nestedScrollConnection),
    ) {
    LazyScreenScaffold(
        modifier = daySwipeModifier,
        // title = null suppresses the big scaffold header (the nullable-title path); the compact
        // WHOOP-style top bar below replaces it, mirroring the iOS Today screen (todayTopBar).
        title = null,
        // Tighten the top inset now the big title is gone (Compose forbids negative padding, so this
        // expresses iOS's `.padding(top: -16)` as a smaller scaffold top padding).
        topPadding = 12.dp,
        // FIX 6 (compactness): the liquid Today matches the iOS Today's tight `VStack(spacing: 12)` section
        // rhythm rather than the app-wide 20dp row gap, so the whole screen reads as compact/slick as iOS.
        // Scoped to this scaffold — no other screen's rhythm changes.
        rowSpacing = 12.dp,
        // #today-layout (hold-to-drag): the hoisted list state the section drag reads (layoutInfo/scrollBy).
        listState = todayListState,
        // LIQUID SKY BACKDROP (the pilot pattern — LiquidScreenSky.kt): the time-of-day liquid sky sits
        // behind the WHOLE top region, the liquid header + wordmark AND the hero rings, full-bleed (full-width, up
        // behind the status bar via the scaffold's topBackground plumbing), top-aligned, settling into the
        // flat canvas over its lower half so the cards float OVER it on the theme surface. This is the
        // Android equivalent of the iOS `ScreenScaffold(topBackground: liquidScaffoldSky())`: it replaces
        // the classic day-cycle SceneScreenBackground with the liquid day-of-sky (LiquidSkyStatic — no
        // per-frame cost on this scroll-heavy screen). The other liquid screens drop in the SAME
        // LiquidScreenSky() slot verbatim.
        // #698, gated on the "Day-cycle background" setting (default ON). Off passes null, so the scaffold
        // paints the plain dark surface canvas instead, mirroring iOS's `showDayCycleBackground ? ... : nil`.
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        // Sky-behind-cards fills the viewport so the transparent cards reveal the sky the whole way down.
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {
        item {
        // LIQUID Today header (iOS LiquidTodayView.scene parity), a full structural rebuild to mirror the
        // iOS liquid Today element-for-element (NOT the old numeric-date + recording-light + bell header):
        //   LEFT  — a tappable title block: the big rounded-bold day title ("Today" / "Yesterday" / the
        //           weekday) over a human date line ("Friday, 3 July"). Tap opens the day picker.
        //   RIGHT — exactly the iOS four controls, in order: a filled HEART (→ Support), the PROFILE
        //           AVATAR (→ Settings), a "+" ADD button (→ quick actions), and the strap BATTERY RING.
        // The recording-status light and the notifications BELL are GONE from the header (iOS has neither);
        // the Updates inbox is relocated into the "+" quick-actions sheet (AppRoot), so the feature stays one
        // tap away without sitting in the Today header. Staggered in as the first section (index 0).
        val dayTitle = when (selectedDayOffset) {
            0 -> uiString(R.string.today_day_today)
            1 -> uiString(R.string.today_day_yesterday)
            else -> {
                val keyDate = runCatching { LocalDate.parse(selectedDayKey) }.getOrNull() ?: selectedDay
                keyDate.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            }
        }
        // Human date line under the title — "Friday, 3 July" (weekday + day + month), NOT a numeric date.
        // Dated by the row ACTUALLY on screen (selectedDayKey follows the resolver at offset 0), matching
        // the iOS `dateLine` (EEEE, d MMMM). Mirrors iOS's date-under-title block.
        val humanDate = run {
            val keyDate = runCatching { LocalDate.parse(selectedDayKey) }.getOrNull() ?: selectedDay
            keyDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
        }
        // #486: header + wordmark + Arrange fold into ONE compact top cluster. Previously the decorative
        // "N O O P" wordmark and the pinned "Arrange" affordance were each their own full-width list item,
        // so the scaffold's 12dp rowSpacing left two near-empty sky bands stacked under the header ("empty
        // space below NOOP"). Grouping them here removes those section gaps: the wordmark hangs 2dp under
        // the title, and Arrange rides the SAME row as the wordmark (wordmark dead-centre, Arrange trailing)
        // instead of claiming its own band.
        Column(
            modifier = Modifier.fillMaxWidth().staggeredAppear(0),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LiquidTodayHeader(
                dayTitle = dayTitle,
                humanDate = humanDate,
                selectedDay = selectedDay,
                battery = HeaderBatteryDisplay.resolve(
                    activeIsWhoop = activeIsWhoop, connected = liveSnap.connected,
                    strapPct = liveSnap.batteryPct, ringPct = null,
                ),
                backfilling = liveSnap.backfilling,
                syncChunksThisSession = liveSnap.syncChunksThisSession,
                lastSyncAt = liveSnap.lastSyncAt,
                historySyncExperimental = liveSnap.historySyncExperimental,
                pagesBehindAtConnect = liveSnap.pagesBehindAtConnect,
                scanning = liveSnap.scanning,
                // One rule for both affordances: they exist while the strap is away. Connected, the
                // chip goes back to reporting sync and nothing else, rather than offering a connect to
                // something already connected.
                onRescan = if (liveSnap.connected) null else requestScan,
                onPickDay = { offset -> selectedDayOffset = offset },
                onQuickActions = onQuickActions,
                onOpenSettings = onOpenSettings,
                onOpenDevices = onOpenDevices,
            )
            // WORDMARK (iOS LiquidWordmark parity): a subtle centred "N O O P" @ ~50% opacity, with a
            // tap easter egg. Still shares its row with the customization control, as #486 intended,
            // but in a slot of its own rather than underneath it.
            //
            // #2110: the wordmark and the control used to share this row as a BOX (#486), wordmark centred
            // and the control aligned CenterEnd. A Box stacks its children, so nothing reserved space or
            // shortened the wordmark — the two simply overlapped once the control grew wide enough to reach
            // the centre. #2010 grew it (icon + label + padding + a frosted pill) and the trailing "P"
            // disappeared underneath it, worse per locale since the label runs 3 chars in zh to 13 in fr
            // against the 9 it was eyeballed at.
            //
            // A ROW with equal fixed gutters instead of a Box: the leading Spacer mirrors the trailing
            // control exactly, so the wordmark stays optically centred while owning the space BETWEEN them.
            // Overlap is now unexpressible at any label width, in any locale, because the two occupy
            // different slots rather than the same one.
            // Home drops the wordmark and the Arrange control, so the row only appears there while the
            // strap is away and the reconnect control has something to offer.
            if (!homeMode || !liveSnap.connected) Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // #2169: the balancing spacer becomes the control, but ONLY while there is something to
                // connect. A permanently visible control has to explain itself; one that appears exactly
                // when the strap is away says what it is for by being there, and cannot be read as a
                // reload button on a screen that never reloads. Connected, it goes back to being a
                // spacer, so the wordmark is centred by the same control-sized gutter either way and
                // nothing moves as the strap comes and goes.
                //
                // Scanning counts as needed: a scan running means not yet connected, and hiding the
                // control mid-attempt would take away the only in-progress signal the header has.
                // The SLOT is permanent and only the control inside it fades, rather than swapping the
                // two. A 4.0 that is dropping and auto-reconnecting flips `connected` repeatedly, and an
                // instant swap turns that into a blinking icon beside the wordmark; a fade reads as a
                // pulse instead. Keeping the gutter itself always present also means the wordmark cannot
                // shift even mid-transition, which an if/else between a control and a spacer allows.
                val scanAffordance by animateFloatAsState(
                    targetValue = if (liveSnap.connected) 0f else 1f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                )
                Box(
                    modifier = Modifier.size(HeaderClusterControl),
                    contentAlignment = Alignment.Center,
                ) {
                    if (scanAffordance > 0.01f) {
                        Box(modifier = Modifier.graphicsLayer { alpha = scanAffordance }) {
                            RescanDisc(scanning = liveSnap.scanning, onClick = requestScan)
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (!homeMode) LiquidWordmark()
                }
                if (homeMode) {
                    Spacer(modifier = Modifier.size(HeaderClusterControl))
                } else {
                    CustomizeDisc(onClick = { showLayoutEditor = true })
                }
            }
            // The reply to a tap that went nowhere. Wording comes from the BLE layer, the same text
            // Live and Onboarding show, so this adds no copy of its own.
            scanHint?.let { hint ->
                Text(
                    hint,
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        }

        // Design Reset (iOS parity): the "New here?" first-run card is off the Today dashboard for the
        // clean look, the scoring guide stays reachable from the i on each score and in Settings.

        // When there is no daily score yet (today's recovery is null / no history), the Metrics tab
        // explains the empty tiles below (syncing, calibrating, carried night). The first-week line that
        // used to sit here now follows this block, so Home shows it too.
        if (!homeMode && displayMetric?.recovery == null) {
            item {
            // While the strap is mid-offload, say so, empty tiles read as final otherwise (#77).
            if (liveSnap.backfilling) SyncingHistoryNote(chunks = liveSnap.syncChunksThisSession)
            // Explained score state (COMPONENT 2): when there's no own number to show, say WHY and WHAT to
            // do. "Calibrating" (N more nights, no fake number), "Last night · <date>" (#802 carry-over)
            // or "Needs the strap" (no data overnight). The carried Charge now draws a dimmed filled ring on
            // the hero with NO in-ring caption, so its "Last night ..." note renders BELOW the rings here,
            // matching iOS explainedScoreNote. Today only; never a fabricated value.
            //
            // #1505: a night whose beats predate transport labelling has a known, specific cause, so it
            // is named instead of leaving a bare blank. Checked BEFORE the generic states and on EVERY
            // day, not just today: unlike an ordinary missing-data gap the cause is known, so a past day
            // gets the same honest explanation, and that is where this is almost always read.
            if (chargeLegacyRrGap) {
                ChargeLegacyRrGapNote()
            // #827: NeedsStrap ALWAYS shows (a today-blocking state, not a recurring nag), except behind
            // the #1505 note above, which names the SAME blank's actual cause rather than restating it.
            } else if (selectedDayOffset == 0 && scoreState is ScoreState.NeedsStrap) {
                ScoreStateNote(scoreState)
            }
            // The carried "Latest sleep · <date>" / "Last night · <date>" note. iOS has NOTHING in this slot,
            // and the maintainer flagged it as breaking the compact liquid look sitting permanently above the
            // hero. So on Android it's dismissible-into-the-inbox (restorable) like the calibrating note: a
            // small × tucks it into Updates so it isn't a fixed fixture between the header and the hero.
            if (selectedDayOffset == 0 && scoreState is ScoreState.CarriedLastNight && !carriedSleepDismissed) {
                // Resolved HERE, not in the onClick below: dismissTodayCard runs from a non-composable
                // lambda, and what it stores is what the Updates inbox later shows. Passing the raw
                // `scoreState.title`/`.detail` filed the dismissed card in English while the card itself
                // rendered localized — the same strings reaching a second sink. (#612)
                val carriedTitle = scoreStateTitle(scoreState)
                val carriedDetail = scoreStateDetail(scoreState)
                Box(modifier = Modifier.fillMaxWidth()) {
                    ScoreStateNote(scoreState)
                    if (updateStore != null) {
                        TodayCardDismissButton(
                            modifier = Modifier.align(Alignment.TopEnd),
                            onClick = {
                                dismissTodayCard(
                                    CARD_CARRIED_SLEEP,
                                    carriedTitle,
                                    carriedDetail,
                                )
                            },
                        )
                    }
                }
            }
            // #827: the dismissible calibrating note. Hidden once dismissed into the inbox; a "Restore to
            // Today" tap there flips calibratingDismissed back via the shared restore path above.
            if (selectedDayOffset == 0 && scoreState is ScoreState.Calibrating && !calibratingDismissed) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    ScoreStateNote(
                        scoreState,
                        restartCause = ScoreState.calibrationRestartCause(
                            ScoreState.recalibrationDay(
                                NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L),
                            ),
                        )?.localized(),
                    )
                    if (updateStore != null) {
                        TodayCardDismissButton(
                            modifier = Modifier.align(Alignment.TopEnd),
                            onClick = {
                                dismissTodayCard(
                                    CARD_CALIBRATING,
                                    context.getString(R.string.today_update_building_baseline_title),
                                    context.getString(R.string.today_update_building_baseline_message),
                                )
                            },
                        )
                    }
                }
            }
            }
        }

        // The first-week line: one short sentence while there is no daily score yet. Today it shows on
        // both tabs (Home leads with it, above the heart card) until dismissed; a past day keeps it on
        // Metrics only. Both read the one scoresBuildingDismissed flag through this one gate, so the two
        // tabs cannot disagree, and the × files one restorable Updates entry. Only today's line has a ×
        // (a past day's line isn't dismissed).
        if (displayMetric?.recovery == null &&
            ((selectedDayOffset == 0 && !scoresBuildingDismissed) || (!homeMode && selectedDayOffset != 0))
        ) {
            item {
                TodayPendingLine(
                    text = uiString(R.string.today_scores_start_line),
                    onDismiss = if (selectedDayOffset == 0 && updateStore != null) {
                        {
                            dismissTodayCard(
                                CARD_SCORES_BUILDING,
                                context.getString(R.string.today_update_scores_building_title),
                                context.getString(R.string.today_update_scores_building_message),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        // The alert belongs to the newest banked night. A past-day view or a day
        // rollover without a new row must not keep showing yesterday's warning.
        if (selectedDayOffset == 0 && alert != null && today != null &&
            days.lastOrNull()?.day == resolveTodayRow(days, todayDate.toString(), LocalDate.now().toString())?.day
        ) item { IllnessBanner(alert!!) }

        // HOME: live heart rate, then one summary card with the three scores and the three recovery
        // vitals. Nothing else, so the tab fits one phone screen; everything else lives on the Metrics tab.
        if (homeMode) {
            item {
                HeartRateTrendCard(viewModel, days, selectedDay, todayDate, displayMetric,
                    effortScale, effortForDay, dayCycleMode, compact = true)
            }
            item {
                HomeSummaryCard(
                    day = displayMetric,
                    vitalsDay = lastVitalsDay,
                    days = days,
                    selectedDayKey = selectedDayKey,
                    effortForDay = effortForDay,
                    effortScale = effortScale,
                    restScore = restScoreForDay,
                    restHistory = restHistory,
                    chargeConfidence = chargeConfidenceTier(chargeBaselines, displayMetric),
                    onChargeTap = { showChargeBreakdown = true },
                    onOpenMetric = onOpenMetric,
                )
            }
        }

        // #486: the "Arrange" affordance moved UP into the header/wordmark cluster (see above) so it no
        // longer sits alone in its own full-width band here. It stays pinned; only its position changed.

        // #today-layout: EVERY Today section — including the Charge/Effort/Rest hero and the Start-session
        // entry — renders in the user's saved order (TodayLayoutPrefs); only the top bar + this Arrange
        // affordance stay pinned. Each section is ONE keyed item wrapped in [TodayReorderableSection]:
        // LONG-PRESS anywhere on a section and drag — it lifts (haptic), follows the finger, swaps
        // neighbours as it crosses their centres (the screen-level frame loop also auto-scrolls at the
        // viewport edges and keeps swapping while it does), and the order persists on drop. The stagger
        // index follows the section's live position.
        // HOME renders none of the arrangeable sections; METRICS renders all of them except the two
        // whose content now lives on Home (the score hero and the heart-rate chart).
        sectionOrder.filterNot {
            homeMode || it in hiddenSections || it in TodaySection.homeOwned
        }.forEach { section ->
            // Entrance stagger keyed on the section's FIXED default position, not its live position: the
            // stagger only matters on first appearance (staggeredAppear latches), and a live-position
            // stagger changes every moved section's content lambda on every mid-drag swap — recomposing
            // the heavy sections (Key Metrics grid, HR chart) while the finger is down (drag jank).
            val stagger = TodaySection.defaultOrder.indexOf(section) + 1
            // A gated-off section (Start session outside today / beta-off; Your Cards outside today or
            // empty) emits NO item at all: an always-present zero-height item would double the 12dp row
            // gap around its slot — visible on the DEFAULT layout, where Start session sits right under
            // the hero and the beta flag is off for most users. The section keeps its place in the saved
            // order; its item simply reappears when eligible.
            val visibleDashboardCards = enabledDashboardCards.filter {
                    it != DashboardCard.HYDRATION || hydrationEnabled
            }
            val sectionVisible = when (section) {
                TodaySection.YOUR_CARDS ->
                    selectedDayOffset == 0 && visibleDashboardCards.isNotEmpty()
                TodaySection.MENSTRUAL_CYCLE ->
                    selectedDayOffset == 0 && !cycleHidden &&
                        (cycleOptInApplies(profileStore.sex) || cycleEnabled || periodStarts.isNotEmpty())
                TodaySection.ADDED_CARDS ->
                    selectedDayOffset == 0 && enabledHostedCards.isNotEmpty()
                else -> true
            }
            if (!sectionVisible) return@forEach
            item(key = TODAY_SECTION_KEY_PREFIX + section.raw) {
                TodayReorderableSection(
                    section = section,
                    listState = todayListState,
                    drag = sectionDrag,
                    onDrop = { TodayLayoutPrefs.setOrder(context, sectionOrder) },
                ) {
                    when (section) {
                        // HERO, three equal Charge / Effort / Rest GlowRings in the compact pinned-dark
                        // card used by LiquidTodayView. Effort prefers today's live in-progress strain and
                        // falls back to the stored value (#402); the floating badge names the real score
                        // sources. The honest "why is Effort 0?" caption (#482/#480) travels WITH the hero
                        // (folded into this section) so wherever the rings sit, their explanation follows.
                        TodaySection.HERO -> Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // The liquid hero CARD: a translucent near-black that floats over the day-of-sky
                            // so the rings + white count-up numbers stay crisp. A rounded 26 corner + a
                            // faint white hairline give it the frosted-glass edge of the iOS liquid heroCard
                            // (heroFill = rgba(13,14,20,.80), stroke white@0.11).
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Palette.heroFill.copy(alpha = Palette.heroFill.alpha * CardAppearance.opacity),
                                        RoundedCornerShape(LIQUID_HERO_RADIUS),
                                    )
                                    .border(1.dp, Palette.heroBorder.copy(alpha = Palette.heroBorder.alpha * CardAppearance.opacity), RoundedCornerShape(LIQUID_HERO_RADIUS))
                                    .staggeredAppear(stagger),
                            ) {
                                ScoreHeroRow(
                                    day = displayMetric,
                                    restScore = restScoreForDay,
                                    recoveryCalibration = recoveryCalibration,
                                    lastScoredCharge = lastScoredCharge,
                                    effortScale = effortScale,
                                    liveTodayStrain = if (selectedDayOffset == 0) liveTodayStrain else null,
                                    heroSourceLabel = heroSourceLabel,
                                    onScoreInfo = openGuide,
                                    onChargeTap = { showChargeBreakdown = true },
                                    // #1164: today's Rest is provisional while the strap has banked records
                                    // not yet offloaded — show "Pending sync" instead of a number that moves.
                                    restPendingSync = restPendingSync(
                                        restScore = restScoreForDay,
                                        backfilling = live.backfilling,
                                        historyPendingSync = live.historyPendingSync,
                                        isTodaySelected = selectedDayOffset == 0,
                                    ),
                                    onOpenMetric = onOpenMetric,
                                )
                            }
                            // Honest "why is Effort 0?" caption — only when today's Effort is a real
                            // near-zero (HR present but never crossed the cardio zone). Effort accrues over
                            // a day and must never visibly drop: floor the in-progress value at the day's
                            // already-earned strain (#489/#506).
                            // #1001: the shared resolution, not a fourth hand-rolled copy of it. Stays null
                            // for a navigated past day so the caption is a TODAY-only explanation.
                            val todayEffort = if (selectedDayOffset == 0) effortForDay else null
                            if (todayEffort != null && todayEffort < 1.0) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        Icons.Filled.Info,
                                        contentDescription = null,
                                        tint = Palette.effortColor,
                                        modifier = Modifier.size(Metrics.iconSmall),
                                    )
                                    Text(
                                        uiString(R.string.l10n_today_screen_no_cardio_load_yet_effort_builds_e952006c),
                                        style = NoopType.footnote,
                                        color = Palette.textTertiary,
                                    )
                                }
                            }
                        }
                        // The plain-English read-out: the Synthesis word and line, the readiness pill, and
                        // the chips that drove it. Carries the last scored day's read at the rollover (#543).
                        TodaySection.SYNTHESIS -> Box(modifier = Modifier.fillMaxWidth().staggeredAppear(stagger)) {
                            SynthesisHeroCard(
                                day = displayMetric,
                                recoveryCalibration = recoveryCalibration,
                                carriedDay = lastScoredRecoveryDay,
                                days = days,
                                drivers = metricDrivers(metricTiles),
                                onOpenReadiness = { showChargeBreakdown = true },
                            )
                        }
                        // METRICS: the one line that explains every arrow, then the Recovery, Sleep, Training
                        // and Body cards (each enabled metric in its group, a tap opening it in place), then
                        // the Customize button that picks the metrics (#251). One keyed item for the drag.
                        TodaySection.KEY_METRICS -> Column(
                            modifier = Modifier.fillMaxWidth().staggeredAppear(stagger),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MetricsArrowCaption()
                            MetricCategoryCards(
                                tiles = metricTiles,
                                enabled = enabledKeyMetrics,
                                isToday = selectedDayOffset == 0,
                                expandedId = expandedMetric,
                                onToggle = { id -> expandedMetric = if (expandedMetric == id) null else id },
                            )
                            MetricsCustomizeButton(onClick = { showMetricsEditor = true })
                        }
                        // #991: TodayWorkoutsSection emits header + card as two siblings; spaced Column.
                        TodaySection.WORKOUTS -> Column(
                            modifier = Modifier.fillMaxWidth().staggeredAppear(stagger),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TodayWorkoutsSection(footer.recentWorkouts, onSelect = { selectedWorkoutRow = it })
                        }
                        // HEART RATE, the live HR thread / trend card. #991: header + card in a Column.
                        TodaySection.HEART_RATE -> Column(
                            modifier = Modifier.fillMaxWidth().staggeredAppear(stagger),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HeartRateTrendCard(viewModel, days, selectedDay, todayDate, displayMetric,
                                effortScale, effortForDay, dayCycleMode)
                        }
                        // The three hero vitals, HRV / Resting HR / Respiratory. Carried day (#543).
                        TodaySection.RECOVERY_VITALS -> Box(modifier = Modifier.fillMaxWidth().staggeredAppear(stagger)) {
                            HeroMetricRows(day = displayMetric, carriedDay = lastScoredRecoveryDay,
                                           vitalsDay = lastVitalsDay, onOpenMetric = onOpenMetric)
                        }
                        // YOUR CARDS, the user-customisable dashboard (WHOOP "My Dashboard"). Hydration is
                        // hidden when its tracking is OFF (the editor still offers it, so the choice
                        // persists). Per-field carried-day fallbacks (#543) stop rollover "No Data" blanks.
                        // The today/non-empty gate lives at the loop level (sectionVisible) so a gated-off
                        // section emits no item; visibleDashboardCards is the loop-level filtered list.
                        TodaySection.YOUR_CARDS -> YourCardsSection(
                            cards = visibleDashboardCards,
                            stepsAverage30 = stepsAverage30,
                            day = stepResolvedDisplayMetric,
                            carriedDay = lastScoredRecoveryDay,
                            vitalsDay = lastVitalsDay,
                            spo2Day = lastSpo2Day,
                            skinTempDay = lastSkinTempDay,
                            hrvDay = lastHrvDay,
                            rhrDay = lastRestingHrDay,
                            respDay = lastRespDay,
                            stress = stressToday,
                            fitnessAge = fitnessAgeToday,
                            vo2max = vo2maxToday,
                            vitality = vitalityToday,
                            importedStepsForDay = importedStepsForDay,
                            estimatedStepsForDay = stepsEstForDay,
                            caloriesForDay = caloriesByDay[selectedDayKey],
                            hydrationTotalMl = hydrationTotalMl,
                            hydrationGoalMl = hydrationGoalMl,
                            onOpenHydration = onOpenHydration,
                            onOpenStress = onOpenStress,
                            onOpenMetric = onOpenMetric,
                            onOpenSleep = onOpenSleep,
                            onOpenCoupled = onOpenCoupled,
                            onCustomise = { showDashboardEditor = true },
                            spo2CandidateByDay = spo2CandidateByDay,
                        )
                        TodaySection.MENSTRUAL_CYCLE -> MenstrualCycleHomeCard(
                            enabled = cycleEnabled,
                            result = v5Signals?.cycle,
                            starts = periodStarts,
                            onSetUp = {
                                viewModel.setCycleTrackingEnabled(true)
                                showCycleTracker = true
                            },
                            onOpen = { showCycleTracker = true },
                            onLogToday = { viewModel.logPeriodStart() },
                        )
                        // #today-hosted-cards: the Trends/Sleep cards the user pulled into Today, in
                        // arranged order. Each is the SAME card its home tab renders (a mirror).
                        TodaySection.ADDED_CARDS -> HostedCardsSection(
                            effortScale = effortScale,
                            onOpenStress = onOpenStress,
                            onOpenSleep = onOpenSleep,
                            onOpenMetric = onOpenMetric,
                            cards = enabledHostedCards,
                            days = days,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
        // Auto-detect workouts (MVP, opt-in, default OFF), a NON-DESTRUCTIVE "looks like a workout?"
        // card that suggests logging a detected sustained-elevated-HR bout. Renders nothing when the
        // toggle is off or there's nothing to suggest. Save → a manual "Workout" row; × → dismissed forever.
        if (!homeMode && selectedDayOffset == 0) {
            item { AutoWorkoutNudgeCard(viewModel = viewModel, days = days) }
        }
        // Strap battery only while the link is up AND a real reading exists, a stale % from a
        // dropped connection must not present as live (#159).
        if (!homeMode) item {
            TodaySourcesSection(
                footer,
                // NOT routed through LiveConsoleReadout.batteryPercent, which substitutes the RING's charge
                // for a non-WHOOP active device. That is right for a readout that names the active device,
                // and wrong here: this value lands in the SourceRow badged `today_source_whoop`, and it
                // also feeds that row's `present` flag. Substituting would put the ring's number under a
                // WHOOP label and assert a WHOOP source that is not there, which is worse than the stale
                // reading being fixed. Nothing is the honest answer for a strap that is not active.
                strapBatteryPct = if (liveSnap.connected && activeIsWhoop)
                    liveSnap.batteryPct?.roundToInt() else null,
                // The runtime estimate is banked from strap SoC samples, so it is the strap's alone.
                strapBatteryEstimate = if (liveSnap.connected && activeIsWhoop) batteryEstimateText else null,
                expanded = sourcesExpanded,
                onToggle = { sourcesExpanded = !sourcesExpanded },
            )
        }
    }
        // Material3's PullToRefreshContainer draws its indicator circle even at rest (progress 0, not
        // refreshing), so an idle Today showed a permanent grey dot at the top (#582). Compose it only
        // while a pull is in progress or a sync is running — the pull GESTURE flows through the Box's
        // nestedScroll above, not this container, so gating the visual can't disable pull-to-sync.
        if (pullToSyncState.progress > 0f || pullToSyncState.isRefreshing) {
            PullToRefreshContainer(
                state = pullToSyncState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (showCycleTracker) {
        v5Signals?.cycle?.let { cycle ->
            CycleTrackerDialog(
                result = cycle,
                starts = periodStarts,
                onLog = viewModel::logPeriodStart,
                onDelete = viewModel::deletePeriodStart,
                onDeleteAll = viewModel::deleteAllPeriodStarts,
                onDismiss = { showCycleTracker = false },
            )
        }
    }

    // Scoring guide sheet, full-screen Dialog, mirroring Settings' What's-new presentation. Opened
    // by the per-score ⓘ (deep-linked via guideSection) and the first-run card (guideSection = null).
    if (showGuide) {
        Dialog(
            onDismissRequest = { showGuide = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                ScoringGuideScreen(
                    onClose = { showGuide = false },
                    initialSection = guideSection,
                )
            }
        }
    }

    // A1/S4: the Charge breakdown sheet, opened by tapping the hero Charge ring. A full-screen Dialog
    // (mirroring the scoring guide's presentation) hosting the existing What-shaped-it breakdown, the
    // Contributors bars and the Readiness card, built only when shown (#819 lazy). A calibrating night
    // (empty drivers) falls through to the existing countdown inside RecoveryDriversSection's own gate.
    if (showChargeBreakdown) {
        Dialog(
            onDismissRequest = { showChargeBreakdown = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ChargeBreakdownSheet(
                days = days,
                chargeBaselines = chargeBaselines,
                displayDay = displayMetric,
                carriedDay = lastScoredRecoveryDay,
                showReadiness = selectedDayOffset == 0,
                onClose = { showChargeBreakdown = false },
                // "How Charge is calculated" → close the breakdown and open the scoring guide at the Charge
                // section, the same target the per-ring ⓘ buttons use. Mirrors the iOS NavigationLink to
                // ScoringGuideView(initialSection: .charge) whose onClose dismisses the breakdown.
                onHowCalculated = {
                    showChargeBreakdown = false
                    openGuide(ScoreSection.CHARGE)
                },
                onOpenTrend = {
                    showChargeBreakdown = false
                    onOpenMetric(HERO_CHARGE_METRIC_KEY)
                },
            )
        }
    }

    // #1694: the tapped Latest-Workouts session, in the same read-only sheet the Workouts list opens.
    selectedWorkoutRow?.let { row ->
        WorkoutDetailSheet(vm = viewModel, row = row, onDismiss = { selectedWorkoutRow = null })
    }

    // Key-Metrics layout editor (#251), a Today-local dialog (no new nav destination). Saves the layout
    // and re-reads it into local state so the grid updates immediately and survives relaunch.
    if (showMetricsEditor) {
        KeyMetricsEditorDialog(
            initial = enabledKeyMetrics,
            onDismiss = { showMetricsEditor = false },
            onSave = { metrics ->
                KeyMetricPrefs.setEnabled(context, metrics)
                enabledKeyMetrics = metrics
                // A tile the owner just hid cannot stay open.
                if (metrics.none { it.raw == expandedMetric }) expandedMetric = null
                showMetricsEditor = false
            },
        )
    }

    // "Your cards" uses the same shown/hidden editor pattern as Today and Key Metrics. Saves the
    // selection and re-reads it into local state so the dashboard updates immediately and survives relaunch.
    if (showDashboardEditor) {
        DashboardCardsEditorDialog(
            initial = enabledDashboardCards,
            onDismiss = { showDashboardEditor = false },
            onSave = { cards ->
                DashboardCardPrefs.setEnabled(context, cards)
                enabledDashboardCards = cards
                showDashboardEditor = false
            },
        )
    }

    // #today-hosted-cards: the add/remove/reorder editor for the Trends/Sleep cards hosted in Today. Saves
    // the selection and re-reads it into local state so the Added-cards section reflects it immediately.
    if (showHostedEditor) {
        HostedCardsEditorDialog(
            initial = enabledHostedCards,
            onDismiss = { showHostedEditor = false },
            onSave = { cards ->
                HostedCardPrefs.setEnabled(context, cards)
                enabledHostedCards = cards
                showHostedEditor = false
            },
        )
    }

    // #today-layout: the section-order editor (reorder the below-hero sections). Saves the order and
    // re-reads it into local state so Today re-lays-out immediately and survives relaunch. Its "Edit added
    // cards" button hands off to the hosted-cards editor above (#today-hosted-cards).
    if (showLayoutEditor) {
        TodayLayoutEditorDialog(
            // The Home-owned sections never render on Metrics, so the editor does not offer them.
            initialOrder = sectionOrder.filterNot { it in TodaySection.homeOwned },
            initialHidden = hiddenSections.filterNot { it in TodaySection.homeOwned },
            onDismiss = { showLayoutEditor = false },
            onEditAdded = {
                showLayoutEditor = false
                showHostedEditor = true
            },
            onSave = { order, hidden ->
                TodayLayoutPrefs.setOrder(context, order)
                TodayLayoutPrefs.setHidden(context, hidden)
                sectionOrder = order
                hiddenSections = hidden
                showLayoutEditor = false
            },
        )
    }
}
