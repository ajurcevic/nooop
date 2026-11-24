package com.noop.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.analytics.StepsEstimateEngine
import com.noop.ble.WhoopModel
import com.noop.data.DailyMetric
import com.noop.widget.StressPoint
import com.noop.widget.StressWidgetProducer
import com.noop.widget.WidgetSnapshotStore
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Today tab: the hosted cards and the "Your cards" dashboard rows.
// Split out of TodayScreen.kt; behaviour unchanged.

// MARK: - "Your cards" dashboard (WHOOP "My Dashboard"), iOS yourCardsSection parity
//
// A persisted, reorderable selection of metric cards surfaced on Today as flat WHOOP metric ROWS. The
// section header carries the "Your cards" overline + a right-aligned BLUE "CUSTOMISE" text action; each row
// is a leading tinted icon tile + UPPERCASE tracked label over a grey baseline caption on the left, and the
// big white value + small unit + chevron on the right. A card with no value yet renders a dash rather than
// vanishing. Mirrors iOS TodayView.yourCardsSection / pinnedCardRow / dashboardValue / dashboardTint.

/** Shared Today section edit affordance. The 48dp box keeps the whole control easy to tap while its
 *  visible content stays pinned to the overline instead of centring against a two-line header. */
@Composable
private fun TodayEditAction(
    contentDescription: String,
    onClick: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = Metrics.space12),
    ) {
        Row(
            modifier = Modifier.align(contentAlignment),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Metrics.space4))
            Text(
                uiString(R.string.l10n_today_screen_edit_5301648d).uppercase(Locale.getDefault()),
                style = NoopType.overline.copy(letterSpacing = 0.4.sp),
                color = Palette.accent,
            )
        }
    }
}

/**
 * #today-hosted-cards render: the Trends/Sleep cards hosted in Today, in arranged order. Each case renders
 * the SAME card its home tab uses (a mirror, not a copy) so the two never diverge. P0 hosts only Sleep
 * marks; its logging callback mirrors the Sleep tab's exactly (external log + metric-series upsert +
 * confirmation toast). Renders nothing when [cards] is empty. Twin of the iOS `hostedCardsSection`.
 */
@Composable
internal fun HostedCardsSection(
    cards: List<HostedCard>,
    days: List<DailyMetric>,
    viewModel: AppViewModel,
    // Passed in rather than read here: Today already resolved it once for its own tiles, and reading
    // SharedPreferences per hosted card per recomposition would put a synchronous file read on the
    // composition path of a screen that recomposes often.
    effortScale: EffortScale,
    onOpenStress: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenMetric: (String) -> Unit,
) {
    if (cards.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // #today-hosted-cards: the SleepModel-backed hosted sleep cards (Stages vs typical today; more to
    // follow). Build the shared model ONCE, and only when at least one such card is actually hosted, so a
    // Today hosting none pays no cost. Same inputs + builder as the Sleep tab (buildHostedSleepModel), so
    // hosted numbers match the Sleep tab. Reused by every model-backed card. Twin of the iOS hostedSleepModel.
    val modelBackedHosted = setOf(HostedCard.STAGES_VS_TYPICAL, HostedCard.NIGHT_DETAIL, HostedCard.SLEEP_DEBT, HostedCard.STAGES, HostedCard.HOURS_VS_NEEDED, HostedCard.CONSISTENCY)
    val needsSleepModel = cards.any { it in modelBackedHosted }
    var hostedSleepModel by remember { mutableStateOf<SleepModel?>(null) }
    LaunchedEffect(needsSleepModel, days, viewModel.activeStrapId) {
        // OFF the main thread: a LaunchedEffect body runs on it, and this walks the night's stages.
        hostedSleepModel = if (needsSleepModel) {
            withContext(Dispatchers.Default) {
                buildHostedSleepModel(viewModel.repo, viewModel.activeStrapId, days)
            }
        } else {
            null
        }
    }
    // Today's stress curve, loaded only when the card is actually hosted — the same "hosting none pays
    // nothing" rule the sleep model above follows. Routed through the SAME gated producer the stress
    // widget publishes from, so an unchanged day costs one indexed COUNT. The foreground card passes
    // the user's selected lens; background widget callers deliberately keep the cheaper default lens.
    val needsStressCurve = cards.contains(HostedCard.STRESS_TODAY)
    var stressCurve by remember { mutableStateOf<List<StressPoint>>(emptyList()) }
    var stressActivityMaskedHours by remember { mutableStateOf(0) }
    // SEEDED from the curve already on disk, so an app update does not show "Calibrating" for a day it
    // has already scored. `stressCurve` starts empty on a cold process, and the card reads an empty
    // curve as an unscored day, which is honest for a genuinely unscored one and wrong the moment the
    // app is merely restarted. The first compute usually fills it, but not always in time: the strap id
    // it needs comes from the source coordinator and is blank for a moment after launch, and a blank id
    // makes the producer answer null, which by contract means "say nothing" and leaves the card empty
    // until the next pass.
    //
    // The widget snapshot is today's default-lens curve. It is a valid cold-start seed only while the
    // personal lens is OFF; using it while opted in recreates #2430 until the foreground pass finishes,
    // or indefinitely if no active strap id arrives. Read once, off the main thread, and only while
    // nothing better has arrived, so a compute that already landed is never overwritten by a stale copy.
    LaunchedEffect(Unit) {
        if (stressCurve.isNotEmpty()) return@LaunchedEffect
        if (NoopPrefs.stressPersonalBaseline(context)) return@LaunchedEffect
        val banked = withContext(Dispatchers.IO) {
            runCatching { WidgetSnapshotStore.load(context).stressSeries }.getOrDefault(emptyList())
        }
        if (banked.isNotEmpty() && stressCurve.isEmpty()) stressCurve = banked
    }
    // #2144: this used to score ONCE, when these keys last moved, and none of them tracks incoming
    // heart rate — `days` is the daily rows, not the intraday samples the curve is built from. So a
    // card left open held whatever it scored then, while the Stress screen scores when you open it,
    // and the two drifted apart by however long sat between the two triggers. A reporter saw 1pm here
    // against 2pm there at twenty past four. This asks again on the producer's shared cadence; the
    // foreground lens is passed below so Today's curve also matches Stress detail when personal mode is on.
    val stressLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(needsStressCurve, days, viewModel.activeStrapId, stressLifecycleOwner) {
        if (!needsStressCurve) {
            stressCurve = emptyList()
            stressActivityMaskedHours = 0
            return@LaunchedEffect
        }
        // Gated on STARTED, the same reason HealthScreen's live-HR tick is: a LaunchedEffect is tied to
        // the composition and not to the lifecycle, so an ungated loop here would go on scoring a day of
        // heart-rate rows every fifteen minutes for as long as the composition survived in the
        // background. The service is already doing that work on this exact cadence while backgrounded,
        // which is the point of it, so the card doing it too would be a second pass that nothing is on
        // screen to read. Resuming re-enters the block and scores immediately, so coming back to the app
        // shows a current curve rather than waiting out an interval.
        stressLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // A null is "nothing to say about stress right now" (no device to read), which the
                // producer documents as keep-what-you-had rather than "today scored nothing". Holding
                // the last curve matters more here than for a single pass: blanking the card on one bad
                // tick would be a visible flicker on a screen that is sitting open.
                StressWidgetProducer.todayCurve(
                    viewModel.repo,
                    viewModel.activeStrapId,
                    personalBaseline = NoopPrefs.stressPersonalBaseline(context),
                )
                    ?.let {
                        stressCurve = it.points
                        stressActivityMaskedHours = it.activityMaskedHours
                    }
                delay(StressWidgetProducer.RESCORE_INTERVAL_MS)
            }
        }
    }
    // Turning the card's own destination into the callback that reaches it. The mapping itself lives
    // on `HostedCard` so a test can assert it; this is only the wiring, which cannot be tested and does
    // not need to be.
    fun opener(card: HostedCard): (() -> Unit)? = when (val d = card.destination) {
        HostedDestination.None -> null
        HostedDestination.Sleep -> onOpenSleep
        HostedDestination.Stress -> onOpenStress
        is HostedDestination.Metric -> ({ onOpenMetric(d.key) })
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
        cards.forEach { card ->
            val open = opener(card)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Clipped BEFORE the click. `NoopCard` clips itself, but the ripple draws on this
                    // wrapper, so without matching the shape here it would wash square corners over a
                    // rounded card. `Metrics.cardRadius` is the right figure: the 26dp liquid-hero surface
                    // `ChartCard` can wear is hero-only, and none of these opt into it.
                    //
                    // This used to read "because every hosted card renders through NoopCard", and that was
                    // the bug (#2109). Six of them open with a bare section header instead, and the clip
                    // was cutting its first glyph. The shape is asked for per card now.
                    .clip(
                        // #2109: shaped from the card's own opening, not assumed. A card that FILLS this
                        // slot wants the full radius. A card that opens with a bare section header does
                        // not: its first pixel is heading text at the top-left, where an 18dp corner cuts
                        // hardest at y=0, and the radius clipped the overline's first glyph ("LAST NIGHT"
                        // read as "AST NIGHT"). Square the TOP corners for those and keep the bottom
                        // rounded, because the slot's bottom edge IS the inner card's bottom edge and a
                        // square ripple there would wash its corners, which is the defect the clip was
                        // added for in the first place.
                        if (card.leadsWithSectionHeader) {
                            RoundedCornerShape(
                                topStart = 0.dp, topEnd = 0.dp,
                                bottomStart = Metrics.cardRadius, bottomEnd = Metrics.cardRadius,
                            )
                        } else {
                            RoundedCornerShape(Metrics.cardRadius)
                        }
                    )
                    .then(if (open != null) Modifier.clickable(onClick = open) else Modifier),
            ) {
            when (card) {
                HostedCard.STRESS_TODAY -> StressTodayCard(stressCurve, stressActivityMaskedHours)
                // The Trends-origin trends. `resolveMetric` walks the `days` already in hand, so these
                // need no model build and no gate, unlike the sleep and stress cards above.
                HostedCard.TREND_HRV, HostedCard.TREND_RESTING_HR, HostedCard.TREND_EFFORT ->
                    TrendHostCard(card, days, effortScale)
                HostedCard.SLEEP_MARKS -> SleepMarkCard(
                    onMark = { type -> logSleepMark(viewModel, scope, context, type) },
                )
                // #today-hosted-cards P1: the hours-asleep trend, built pure from `days` (no nap/debt) so
                // it matches the Sleep tab's hours chart. The card composable lives in SleepScreen.kt
                // (internal) where its ChartCard/BarChart siblings are in-file.
                HostedCard.ASLEEP_DURATION -> {
                    val (hours, dates) = sleepDurationTrend(days)
                    AsleepDurationHostCard(hours = hours, dates = dates)
                }
                // #today-hosted-cards: last night's stages vs the wearer's personal per-stage means, rendered
                // from the SAME shared SleepModel the Sleep tab uses (mirror, not copy). Until the async build
                // lands — or on a device with no stage data — the model is null and the slot renders nothing
                // this frame, matching how the Sleep tab guards the section on a null model.
                HostedCard.STAGES_VS_TYPICAL -> hostedSleepModel?.let { StagesVsTypicalHostCard(it) }
                // #today-hosted-cards: the Night-detail metric grid, rendered from the SAME shared SleepModel
                // the Sleep tab uses (mirror, not copy). Null until the async build lands / no stage data —
                // the slot renders nothing this frame, matching the Sleep tab's null-model guard.
                HostedCard.NIGHT_DETAIL -> hostedSleepModel?.let { NightDetailHostCard(it) }
                // #today-hosted-cards: the Sleep-debt ledger, rendered from the SAME shared SleepModel the
                // Sleep tab uses (mirror, not copy). Null until the async build lands / no stage data — the
                // slot renders nothing this frame, matching the Sleep tab's null-model guard.
                HostedCard.SLEEP_DEBT -> hostedSleepModel?.let { SleepDebtLedgerHostCard(it) }
                // #today-hosted-cards: the READ-ONLY latest-night stage chart + breakdown, rendered from the
                // SAME shared SleepModel the Sleep tab uses (mirror, not copy) — none of the hero's nav/edit/
                // nap interaction. Null until the async build lands / no stage data — the slot renders nothing
                // this frame, matching the Sleep tab's null-model guard.
                HostedCard.STAGES -> hostedSleepModel?.let { StagesHostCard(it) }
                // #today-hosted-cards: the single hours-vs-need % metric, rendered from the SAME shared
                // SleepModel the Sleep tab uses (mirror, not copy) via the existing standalone
                // HoursVsNeededCard. Null until the async build lands / no stage data — the slot renders
                // nothing this frame, matching the Sleep tab's null-model guard.
                HostedCard.HOURS_VS_NEEDED -> hostedSleepModel?.let { HoursVsNeededCard(it) }
                // #today-hosted-cards: the single sleep-consistency % metric, rendered from the SAME shared
                // SleepModel the Sleep tab uses (mirror, not copy) via the simple standalone
                // ConsistencyHostCard. Null until the async build lands / no stage data — the slot renders
                // nothing this frame, matching the Sleep tab's null-model guard.
                HostedCard.CONSISTENCY -> hostedSleepModel?.let { ConsistencyHostCard(it) }
            }
            }
        }
    }
}

@Composable
internal fun YourCardsSection(
    cards: List<DashboardCard>,
    stepsAverage30: Pair<Double?, Int>,
    day: DailyMetric?,
    carriedDay: DailyMetric?,
    vitalsDay: DailyMetric?,
    spo2Day: DailyMetric?,
    skinTempDay: DailyMetric?,
    hrvDay: DailyMetric?,
    rhrDay: DailyMetric?,
    respDay: DailyMetric?,
    stress: Double?,
    fitnessAge: Double?,
    vo2max: Double?,
    vitality: Double?,
    importedStepsForDay: Int?,
    estimatedStepsForDay: Int?,
    caloriesForDay: Double?,
    hydrationTotalMl: Double,
    hydrationGoalMl: Int,
    onOpenHydration: () -> Unit,
    onOpenStress: () -> Unit,
    onOpenMetric: (String) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenCoupled: () -> Unit,
    onCustomise: () -> Unit,
    spo2CandidateByDay: Map<String, Double> = emptyMap(),
) {
    // #1331 parity: honor the °C/°F preference on the Skin Temp card, the way Health / Compare (and the
    // Swift twin) do. The classic dashboard hardcoded Celsius here alone, so a °F user saw °C on this
    // screen only.
    val fahrenheit = UnitPrefs.temperature(LocalContext.current) == TemperatureUnit.FAHRENHEIT
    Box(modifier = Modifier.fillMaxWidth().staggeredAppear(2)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header: "YOUR CARDS" overline + a right-aligned blue EDIT action (the WHOOP ✎ affordance).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline(uiString(R.string.today_section_your_cards), modifier = Modifier.weight(1f))
                TodayEditAction(
                    onClick = onCustomise,
                    contentDescription = uiString(R.string.l10n_today_screen_customise_your_cards_2428d761),
                )
            }
            cards.forEach { card ->
                DashboardCardRow(
                    card = card,
                    value = dashboardCardValue(
                        card = card,
                        stepsAverage30 = stepsAverage30.first,
                        day = day,
                        carriedDay = carriedDay,
                        vitalsDay = vitalsDay,
                        spo2Day = spo2Day,
                        skinTempDay = skinTempDay,
                        hrvDay = hrvDay,
                        rhrDay = rhrDay,
                        respDay = respDay,
                        fahrenheit = fahrenheit,
                        // #1846: the user's lead-with choice. Read at the call site, not defaulted inside —
                        // a defaulted parameter no caller passes is a setting that silently does nothing.
                        skinTempPreferred = com.noop.ui.UnitPrefs.skinTempPreferred(LocalContext.current),
                        stress = stress,
                        fitnessAge = fitnessAge,
                        vo2max = vo2max,
                        vitality = vitality,
                        importedStepsForDay = importedStepsForDay,
                        estimatedStepsForDay = estimatedStepsForDay,
                        caloriesForDay = caloriesForDay,
                        hydrationTotalMl = hydrationTotalMl,
                        hydrationGoalMl = hydrationGoalMl,
                        spo2CandidateByDay = spo2CandidateByDay,
                    ),
                    // The mini liquid ring's fill — the SAME per-card fraction iOS `liquidCard` uses.
                    fraction = dashboardCardFraction(
                        card = card,
                        stepsAverage30 = stepsAverage30.first,
                        day = day,
                        carriedDay = carriedDay,
                        vitalsDay = vitalsDay,
                        respDay = respDay,
                        stress = stress,
                        fitnessAge = fitnessAge,
                        vo2max = vo2max,
                        vitality = vitality,
                        importedStepsForDay = importedStepsForDay,
                        estimatedStepsForDay = estimatedStepsForDay,
                    ),
                    tint = dashboardCardTint(card),
                    // #110: label the sleep row with its source + night (this section renders at offset 0
                    // only, so it IS last night), so a WHOOP-imported figure is never silently shown as
                    // "last night" with no provenance. iOS TodayView.sleepSourceSubtitle twin.
                    subtitleOverride = if (card == DashboardCard.STEPS_AVERAGE_30)
                        uiString(R.string.steps_average_coverage, stepsAverage30.second)
                    else sleepSourceSubtitle(card, day),
                    // #706/#684: every card now opens its OWN detail, matching iOS. The Stress card -> Stress;
                    // the overnight vitals (HRV / Resting HR / Respiratory / SpO₂ / Skin Temp) + Fitness age /
                    // Vitality / Steps / Calories -> each metric's focused trend (vital_detail/<key>, the iOS
                    // metricDetail twin); Sleep -> Sleep; Hydration -> Hydration. Whole row is the button.
                    onClick = dashboardCardDestination(
                        card = card,
                        onOpenStress = onOpenStress,
                        onOpenMetric = onOpenMetric,
                        onOpenSleep = onOpenSleep,
                        onOpenHydration = onOpenHydration,
                        onOpenCoupled = onOpenCoupled,
                    ),
                )
            }
        }
    }
}

/** #110: the sleep row's value is `totalSleepMin` — WHOOP's imported TST, which can legitimately differ
 *  from the Sleep tab's on-device re-staged night (WHOOP CSV + Apple Health both imported). Label the row
 *  with its source (the SAME `daySourceBadge` winner the Sleep tab's `MainSleepFooter` uses) + "last
 *  night" — `YourCardsSection` renders at offset 0 only, so the row IS last night — so a WHOOP figure is
 *  never silently shown as "last night" with no provenance. null → the card keeps its static subtitle
 *  (not the sleep card, or no banked sleep). Twin of iOS `TodayView.sleepSourceSubtitle`; the source
 *  mechanism differs per platform (Android keys on the day's session source, iOS on `importedSleep`),
 *  exactly as the two Sleep-tab badges already do, so the label — not the wiring — is what stays in parity. */
@Composable
private fun sleepSourceSubtitle(card: DashboardCard, day: DailyMetric?): String? {
    if (card != DashboardCard.SLEEP) return null
    val d = day ?: return null
    if (d.totalSleepMin == null) return null
    val source = daySourceBadge(d.deviceId).first
    return uiString(R.string.today_sleep_source_last_night, source)
}

/** The `vital_detail/<key>` key a metric/vital card opens, or null when the card has its OWN dedicated
 *  screen (Stress / Sleep / Hydration / Coupled) rather than a metric-detail trend. Mirrors the iOS
 *  `liquidCard` switch, where every metric/vital card opens `metricDetail(key)` (its own focused trend),
 *  NOT the shared Health hub (2026-07-03). Keys are the Android VitalDetailScreen keys. */
internal fun dashboardCardMetricKey(card: DashboardCard): String? = when (card) {
    DashboardCard.HRV -> "hrv"
    DashboardCard.RESTING_HR -> "rhr"
    DashboardCard.RESPIRATORY -> "resp"
    DashboardCard.BLOOD_OXYGEN -> "spo2"
    DashboardCard.SKIN_TEMP -> "skin"
    DashboardCard.FITNESS_AGE -> "fitness_age"
    DashboardCard.VO2MAX -> "vo2max_est"
    DashboardCard.VITALITY -> "vitality"
    DashboardCard.STEPS, DashboardCard.STEPS_AVERAGE_30 -> "steps_est"
    DashboardCard.CALORIES -> "active_kcal"
    // These carry their own full screen, not a per-metric trend.
    DashboardCard.STRESS, DashboardCard.SLEEP, DashboardCard.HYDRATION, DashboardCard.COUPLED -> null
}

/** The destination callback a dashboard card opens when tapped. Mirrors the iOS dashboardCardRow switch:
 *  Stress -> Stress; Sleep -> Sleep; Hydration -> Hydration; Coupled -> the WHOOP-style day screen; every
 *  metric/vital card -> its OWN focused trend (`vital_detail/<key>` via [onOpenMetric]), matching the iOS
 *  `metricDetail(key)`. Every card resolves to a destination, so the chevron is always honest (#706/#684). */
internal fun dashboardCardDestination(
    card: DashboardCard,
    onOpenStress: () -> Unit,
    onOpenMetric: (String) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenHydration: () -> Unit,
    onOpenCoupled: () -> Unit,
): () -> Unit = when (card) {
    DashboardCard.STRESS -> onOpenStress
    DashboardCard.SLEEP -> onOpenSleep
    DashboardCard.HYDRATION -> onOpenHydration
    // The Coupled view card (#43) taps through to the full WHOOP-style day screen.
    DashboardCard.COUPLED -> onOpenCoupled
    // Every overnight vital + Fitness age / Vitality / Steps / Calories opens its own metric-detail trend.
    else -> {
        val key = dashboardCardMetricKey(card)
        if (key != null) ({ onOpenMetric(key) }) else ({})
    }
}

/** A dashboard card's WHOOP-token tint (icon + accent). Score cards take their domain colour; vitals take
 *  their biometric hue; everything else the blue accent. No gold (WHOOP), tokens only. Mirrors iOS
 *  dashboardTint. This drives the mini liquid ring's tint on each row, so it follows the iOS `liquidCard`
 *  per-card tints exactly: Stress=accent, Fitness age=charge-green, Vitality=liquid-purple, HRV=cyan,
 *  Resting HR=rose, Respiratory=accent, Steps=cyan, Sleep=rest, Coupled=charge. */
private fun dashboardCardTint(card: DashboardCard): Color = when (card) {
    // iOS `liquidCard`: stress → StrandPalette.accent (blue), not the Effort orange.
    DashboardCard.STRESS -> Palette.accent
    DashboardCard.FITNESS_AGE -> Palette.chargeColor
    DashboardCard.VO2MAX -> Palette.chargeColor
    // iOS vitality → liquidPurple (#9b7bff).
    DashboardCard.VITALITY -> LIQUID_PURPLE
    // iOS hrv → metricCyan (this theme's metricPurple is a blue, cyan reads as the iOS HRV teal).
    DashboardCard.HRV -> Palette.metricCyan
    DashboardCard.RESTING_HR -> Palette.metricRose
    DashboardCard.RESPIRATORY -> Palette.accent
    DashboardCard.BLOOD_OXYGEN -> Palette.metricCyan
    DashboardCard.SKIN_TEMP -> Palette.metricAmber
    DashboardCard.SLEEP -> Palette.restColor
    DashboardCard.STEPS, DashboardCard.STEPS_AVERAGE_30 -> Palette.metricCyan
    DashboardCard.CALORIES -> Palette.metricAmber
    DashboardCard.HYDRATION -> Palette.metricCyan
    DashboardCard.COUPLED -> Palette.chargeColor
}

/** The VO₂max a full mini-ring stands for (ml/kg/min). */
internal const val VO2MAX_RING_CEILING = 70.0

/**
 * A dashboard card's mini-ring fill fraction (0..1), or null for an empty (no-reading) ring. Mirrors the
 * iOS `liquidCard` `frac:` argument exactly, per card:
 *   Stress = stress/3 · Fitness age = 0.5 (fixed) · VO₂max = vo2max/70 · Vitality = vitality/100 · HRV = avgHrv/120 ·
 *   Resting HR = restingHr/100 · Respiratory = respRate/24 · Steps = steps/10000 · Sleep = totalSleepMin/480 ·
 *   Coupled = 0.6 (fixed) · Blood oxygen / Skin temp / Calories / Hydration = null (empty, not half-full).
 * The three overnight vitals (HRV / Resting HR / Respiratory) read PER-FIELD today-first with the
 * recovery-INDEPENDENT [vitalsDay] carry, matching the row VALUE, so the ring fill and the number agree
 * (and a recovery-nulled night keeps its OWN preserved vitals). Sleep keeps the recovery-gated
 * `carriedDay ?: day` carry.
 */
private fun dashboardCardFraction(
    card: DashboardCard,
    stepsAverage30: Double?,
    day: DailyMetric?,
    carriedDay: DailyMetric?,
    vitalsDay: DailyMetric?,
    respDay: DailyMetric?,
    stress: Double?,
    fitnessAge: Double?,
    vo2max: Double?,
    vitality: Double?,
    importedStepsForDay: Int?,
    estimatedStepsForDay: Int?,
): Double? {
    fun over(v: Double?, ceiling: Double): Double? = v?.let { (it / ceiling).coerceIn(0.0, 1.0) }
    val vd = carriedDay ?: day
    return when (card) {
        DashboardCard.STRESS -> over(stress, 3.0)
        DashboardCard.FITNESS_AGE -> if (fitnessAge != null) 0.5 else null
        // Filled against a fixed 70 ml/kg/min ceiling (the top of the range these estimators produce), so a
        // fitter reading fills more of the ring. It used to be a flat half, which said nothing.
        DashboardCard.VO2MAX -> over(vo2max, VO2MAX_RING_CEILING)
        DashboardCard.VITALITY -> over(vitality, 100.0)
        DashboardCard.HRV -> over(day?.avgHrv ?: vitalsDay?.avgHrv, 120.0)
        DashboardCard.RESTING_HR -> over((day?.restingHr ?: vitalsDay?.restingHr)?.toDouble(), 100.0)
        // PER-FIELD carry: today → the STALENESS-BOUNDED prior night (`respDay` = lastRespRow). The
        // unbounded `vitalsDay?.respRateBpm` is dropped on purpose — it picks the newest row with ANY
        // vital regardless of age and printed one CSV import's 15.6 as today's rate for a fortnight
        // (#1331). Byte-twin of the Swift `lastRespDay` card.
        DashboardCard.RESPIRATORY -> over(day?.respRateBpm ?: respDay?.respRateBpm, 24.0)
        DashboardCard.STEPS -> {
            val steps = (day?.steps ?: importedStepsForDay ?: estimatedStepsForDay)?.toDouble()
            over(steps, 10000.0)
        }
        DashboardCard.SLEEP -> over(vd?.totalSleepMin, 480.0)
        DashboardCard.STEPS_AVERAGE_30 -> over(stepsAverage30, 10000.0)
        DashboardCard.COUPLED -> 0.6
        // Not wired to a real read yet — an EMPTY ring (not half-full) so it doesn't imply a reading.
        DashboardCard.BLOOD_OXYGEN, DashboardCard.SKIN_TEMP, DashboardCard.CALORIES,
        DashboardCard.HYDRATION -> null
    }
}

/**
 * Resolve a dashboard card's CURRENT display value from the values Today already loads, with its unit
 * suffix appended. Returns a dash when the value isn't available yet, never a fabricated number. Reuses
 * the SAME reads the rest of Today uses (displayMetric vitals, the pinned Stress / Fitness age / Vitality,
 * steps, calories, sleep duration). Mirrors iOS dashboardValue.
 *
 * The three overnight vitals (HRV / Resting HR / Respiratory) read PER-FIELD today-first with the
 * recovery-INDEPENDENT [vitalsDay] carry (#543 follow-up), so a night whose recovery was nulled post-update
 * still shows its OWN preserved value rather than an older recovery-scored day's (the tile-vs-card fix).
 * SpO₂ / Skin Temp / Sleep keep the recovery-gated `carriedDay ?: day` carry. Steps / Calories stay on
 * today's own row (they accrue through the day, never a carry). Stress / Fitness age / Vitality come from
 * their own resolved loads.
 */
private fun dashboardCardValue(
    card: DashboardCard,
    stepsAverage30: Double?,
    day: DailyMetric?,
    carriedDay: DailyMetric?,
    vitalsDay: DailyMetric?,
    spo2Day: DailyMetric?,
    skinTempDay: DailyMetric?,
    hrvDay: DailyMetric?,
    rhrDay: DailyMetric?,
    respDay: DailyMetric?,
    stress: Double?,
    fitnessAge: Double?,
    vo2max: Double?,
    vitality: Double?,
    importedStepsForDay: Int?,
    estimatedStepsForDay: Int?,
    caloriesForDay: Double?,
    hydrationTotalMl: Double,
    hydrationGoalMl: Int,
    spo2CandidateByDay: Map<String, Double> = emptyMap(),
    fahrenheit: Boolean = false,
    skinTempPreferred: com.noop.analytics.SkinTempDisplay.Kind =
        com.noop.analytics.SkinTempDisplay.Kind.ABSOLUTE,
): String {
    fun withUnit(s: String): String =
        if (s == NO_DATA) NO_DATA else if (card.unit.isEmpty()) s else "$s ${card.unit}"

    // SpO₂ / Skin Temp / Sleep carry over from the last scored night; today's accruing totals do not.
    val vd = carriedDay ?: day

    return when (card) {
        // #1842: PER-FIELD carry, the fourth of these. `lastVitalsRow`'s predicate is an OR across
        // HRV / resting-HR / respiratory, so it can select a row that has respRateBpm and a NULL avgHrv —
        // and the card then reads null and prints "No data" while the tile, carrying a different row,
        // shows a value. That is the same failure `lastSpo2Row` and `lastSkinTempRow` were split out to
        // fix ("can select a row whose spo2Pct is null … while an OLDER row has a real reading"); HRV and
        // resting HR were the two left on the shared predicate.
        //
        // Deliberately NOT the recovery-scored carry: the comment on `lastVitalsDay` is explicit that
        // vitals must not fall back to an older recovery-scored day.
        DashboardCard.HRV ->
            withUnit((day?.avgHrv ?: hrvDay?.avgHrv)?.let { it.roundToInt().toString() } ?: NO_DATA)
        DashboardCard.RESTING_HR ->
            withUnit((day?.restingHr ?: rhrDay?.restingHr)?.toString() ?: NO_DATA)
        DashboardCard.RESPIRATORY ->
            // PER-FIELD carry: today → the STALENESS-BOUNDED `respDay` (lastRespRow). The unbounded
            // `vitalsDay?.respRateBpm` is dropped on purpose (see the gauge site + Swift `lastRespDay`):
            // it had no age bound and showed a fortnight-old imported 15.6 as today's rate (#1331). A gap
            // past the window now reads NO_DATA — the truthful answer when nobody measured.
            withUnit((day?.respRateBpm ?: respDay?.respRateBpm)?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: NO_DATA)
        DashboardCard.BLOOD_OXYGEN ->
            // PER-FIELD carry: the whole-row carries (vd) land on rows whose spo2Pct is null (the engine
            // writes spo2Pct = null on computed rows), so fall through to the last row that HAS one.
            // #103/queue-11a: when no calibrated spo2Pct exists, fall back to the spo2_candidate strap
            // estimate (WHOOP @82 or Oura ceiling@100, from metricSeries) when the experimental display
            // toggle is ON. Labelled "estimate" in the Health vitals screen; here it just fills the card
            // so it's not blank.
            (vd?.spo2Pct ?: spo2Day?.spo2Pct)?.let { String.format(Locale.getDefault(), "%.0f%%", it) }
                ?: (vd?.day ?: day?.day)?.let { spo2CandidateByDay[it] }?.let { String.format(Locale.getDefault(), "%.0f%%", it) }
                ?: NO_DATA
            DashboardCard.SKIN_TEMP -> {
                // #1844: LEAD WITH THE ABSOLUTE when the night measured one, the rule the Health tile has
                // used since #1665 — a deviation with no anchor cannot be read ("+0.9" is a fever or a warm
                // bedroom). Both numbers come off the SAME row so the scale shown is that night's own.
                // #622 still applies to the fallback: a deviation keeps its Δ unit so "−0.1 °C" is never
                // read as a wrist temperature.
                // Same resolver as the Key Metrics tile, so the two can never disagree — the claim the iOS
                // twin already made in a comment and that now actually holds on both platforms. It also puts
                // today's own reading FIRST: `vd` is `carriedDay ?: day`, so a live carry hid a temperature
                // measured today, which `todaysOwnReadingWinsOverEitherCarry` has asserted all along.
                resolveSkinTempReading(day, carriedDay, skinTempDay, skinTempPreferred)
                    ?.let { com.noop.analytics.SkinTempDisplay.formatReading(it, fahrenheit = fahrenheit) }
                    ?: NO_DATA
            }
        DashboardCard.SLEEP -> sleepValue(vd)
        DashboardCard.STEPS -> {
            val real = day?.steps?.let { intStringGrouped(it.toDouble()) }
                ?: importedStepsForDay?.let { intStringGrouped(it.toDouble()) }
            val est = estimatedStepsForDay?.let { intStringGrouped(it.toDouble()) }
            real ?: est ?: NO_DATA
        }
        DashboardCard.STEPS_AVERAGE_30 -> stepsAverage30?.let { intStringGrouped(it) } ?: NO_DATA
        DashboardCard.CALORIES ->
            withUnit(caloriesForDay?.let { intStringGrouped(it) } ?: NO_DATA)
        DashboardCard.STRESS ->
            // #706/#684: Stress is baseline-relative, so until the strap has banked enough worn nights to
            // seed the 30-day RHR/HRV baseline StressScreen reads, the front card has no number to show. The
            // old `?: NO_DATA` rendered a bare dash that read like a broken card; show the honest calibrating
            // state instead, matching the owner's reply on #706 and the StressScreen empty/calibrating copy.
            stress?.let { it.roundToInt().toString() } ?: STRESS_CALIBRATING
        DashboardCard.FITNESS_AGE ->
            // Carries the bound symbol the Health hero uses (#2173), so a floored reading does not read
            // as an exact one here and as a bounded one there.
            withUnit(fitnessAge?.let { "${fitnessAgeBoundSymbol(it)}${it.roundToInt()}" } ?: NO_DATA)
        DashboardCard.VO2MAX ->
            vo2max?.let { it.roundToInt().toString() } ?: NO_DATA
        DashboardCard.VITALITY ->
            vitality?.let { it.roundToInt().toString() } ?: NO_DATA
        DashboardCard.HYDRATION ->
            // "<total> / <goal> L" in litres to 1 dp, e.g. "1.2 / 3.2 L". Always shows a value (a fresh
            // day reads "0.0 / 3.2 L"), since the goal is always derivable from the profile.
            String.format(
                Locale.getDefault(), "%.1f / %.1f L",
                hydrationTotalMl / 1000.0, hydrationGoalMl / 1000.0,
            )
        DashboardCard.COUPLED ->
            // A tap-through row with no metric value of its own, the row shows just the chevron. An empty
            // string (not NO_DATA) renders no number and leaves it un-dimmed. Mirrors iOS dashboardValue.
            ""
    }
}

/**
 * One WHOOP "My Dashboard" metric row: a thin-line tinted icon tile, an UPPERCASE tracked label over a grey
 * baseline caption, the big white value + small unit, and a chevron, on the flat frosted card surface (no
 * glow), tokens only. Mirrors iOS pinnedCardRow. The whole row is the tap target: when [onClick] is set it
 * pushes that card's detail (the chevron is the hint), matching iOS (#706/#684).
 */
@Composable
private fun DashboardCardRow(
    card: DashboardCard,
    value: String,
    fraction: Double?,
    tint: Color,
    // #110: a per-card dynamic subtitle (currently the sleep row's source + night); null keeps the
    // card's static description.
    subtitleOverride: String? = null,
    onClick: (() -> Unit)? = null,
) {
    // A real number renders white; a placeholder (No Data, or the Stress calibrating state) renders dimmed.
    val hasValue = value != NO_DATA && value != STRESS_CALIBRATING
    val displayValue = localizedMetricValue(value)
    // iOS `cardLink` corner is 20 (a touch rounder than the app-wide 18dp card), with the SAME neutral
    // surfaceRaised fill + plain hairline the frosted neutral surface already draws.
    val rowShape = RoundedCornerShape(20.dp)
    // liquidPress: the tappable card settles inward on press (the iOS LiquidPressStyle feel). The SAME
    // interactionSource feeds the clickable and the press modifier, so it responds to the actual touch.
    // It is applied OUTSIDE the frosted surface so the whole card (surface + content) scales/dims as one.
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.liquidPress(interaction) else it }
            .clip(rowShape)
            .frostedCardSurface(cornerRadius = 20.dp)
            .let {
                if (onClick != null) {
                    it.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else it
            }
            // iOS row padding: 14h / 11v (tighter than the old 13/11 icon-box row).
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics { contentDescription = uiString(R.string.l10n_today_screen_card_title_value_e4bb76b3, uiString(card.titleRes), displayValue) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A 30dp mini GlowRing filled to this card's fraction, tinted its domain colour — the
        // "small ring per icon" iOS shows. Static (GlowRing starts empty, springs to target once).
        // Ring or vessel per the Today gauge preference. Static either way: the many small gauges
        // cost nothing per frame, which is what the vessel's animated=false bought here too.
        val cardGaugeCtx = LocalContext.current
        if (remember { NoopPrefs.todayRingGauges(cardGaugeCtx) }) {
            GlowRing(
                fraction = (fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
                value = 0.0,
                color = tint,
                diameter = 30.dp,
                lineWidth = 3.dp,
                showsLabel = false,
            )
        } else {
            LiquidVessel(
                value = fraction,
                tint = tint,
                animated = false,
                modifier = Modifier.size(30.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // iOS: overline 11 / +1.0 tracking, textPrimary.
            Text(
                uiString(card.titleRes).uppercase(),
                style = NoopType.overline.copy(fontSize = 11.sp, letterSpacing = 1.0.sp),
                color = Palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitleOverride ?: uiString(card.subtitleRes),
                style = NoopType.caption,
                color = Palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // iOS value = number(17), textPrimary.
        Text(
            displayValue,
            style = NoopType.number(17f),
            color = if (hasValue) Palette.textPrimary else Palette.textTertiary,
            maxLines = 1,
        )
        // iOS chevron = 12.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** #1491: the blank-Steps-tile line for a strap that ESTIMATES steps, or null when the prompt does not
 *  apply. A WHOOP 4.0 sends no step count, so NOOP fits an estimate from motion against a phone-counted
 *  reference and withholds it until it has one — and until #1491 the tile just went blank, with no
 *  explanation and no route to the sheet where a coefficient can be set by hand.
 *
 *  Keyed on the persisted model NAME rather than [DeviceFamily.forRegistryModel]: that resolver maps
 *  REGISTRY labels ("4.0", "WHOOP 4.0") and answers WHOOP5 for everything else, including the enum names
 *  this preference actually stores — so routing through it would classify every 4.0 as a 5 and this would
 *  silently never appear. A null preference (no model ever selected) also yields null, so a user with no
 *  strap is never told to calibrate one. Mirrors the iOS `stepsPipelineActive` gate.
 *
 *  The wording is the engine's own [StepsEstimateEngine.CalibrationStatus.headline], which already says
 *  "connect your phone's step count" when NO day has both motion and a phone total, rather than a
 *  countdown that would never advance. */
@Composable
internal fun stepsCalibrationPrompt(context: Context, profileStore: ProfileStore): String? {
    val model = context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE)
        .getString("noop.selectedWhoopModel", null) ?: return null
    if (model != WhoopModel.WHOOP4.name) return null
    // Already calibrated? Then a blank day is just a quiet one — the estimate exists, this day simply did
    // not move enough to earn a number, and there is nothing for the user to go and do. Saying otherwise
    // would render "Need 0 more days where your phone also counted steps", because the headline's
    // countdown is max(0, need - have) and a calibrated user is already past `need`.
    // All three, because the first two are ENGINE outputs — written on the next analyze pass — while
    // [ProfileStore.stepsManualCoefficient] is what the user just typed. Checking only the outputs told
    // someone who had hand-set a coefficient to go connect a phone step source, for up to a scoring cycle
    // afterwards. That is the same shape as the bug this whole prompt exists to fix (#1491): gating on
    // evidence the app produces later, rather than on what the user has already done.
    if (profileStore.stepsCalibrationCoefficient > 0.0 ||
        profileStore.stepsCalibrationManual ||
        profileStore.stepsManualCoefficient > 0.0
    ) {
        return null
    }
    // #1816: when the strap has banked NO motion, the phone-step-days countdown is the wrong message.
    // A step estimate is `motion * coefficient`, so with the motion half missing neither the estimate
    // nor the fit moves however many days the phone counts — and the countdown that names only the
    // phone half sent a field reporter to enter Apple Health steps by hand expecting calibration to
    // start, which it cannot. Say "No motion synced yet" instead — the same wording the calibration
    // sheet's no-motion banner uses, so the two surfaces agree.
    if (!profileStore.stepsHasBankedMotion) {
        return uiString(R.string.today_steps_headline_no_motion)
    }
    val headline = StepsEstimateEngine.CalibrationStatus.NeedsMoreDays(
        have = profileStore.stepsCalibrationSampleDays,
        need = StepsEstimateEngine.MIN_CALIBRATION_DAYS,
    ).headline
    return when (headline) {
        StepsEstimateEngine.CalibrationStatus.Headline.Manual -> uiString(R.string.today_steps_headline_manual)
        is StepsEstimateEngine.CalibrationStatus.Headline.Calibrated ->
            uiString(R.string.today_steps_headline_calibrated, headline.sampleDays)
        StepsEstimateEngine.CalibrationStatus.Headline.ConnectPhoneSteps ->
            uiString(R.string.today_steps_headline_connect_phone)
        is StepsEstimateEngine.CalibrationStatus.Headline.NeedMoreDays ->
            uiString(R.string.today_steps_headline_more_days, headline.remaining)
    }
}

/** #760/#792: the caption under an ESTIMATED Steps tile: "est. · <status detail>", where the detail is the
 *  engine's own STATUS line (manual k, or k=… from N days + confidence tier) built from the SAME persisted
 *  calibration the estimate used. So a WHOOP 4.0 user can see WHY the number reads as it does (and why it may
 *  look frozen at low confidence) right where they notice the "est." flag. Falls back to a bare "est." when no
 *  coefficient is recorded yet. Mirrors iOS `stepsEstimateCaption`. */
@Composable
internal fun stepsEstimateCaption(profileStore: ProfileStore): String {
    if (profileStore.stepsCalibrationCoefficient <= 0.0) return uiString(R.string.today_steps_estimated_short)
    val status: StepsEstimateEngine.CalibrationStatus = if (profileStore.stepsCalibrationManual) {
        StepsEstimateEngine.CalibrationStatus.Manual(
            coefficient = profileStore.stepsCalibrationCoefficient,
            sampleDays = profileStore.stepsCalibrationSampleDays,
        )
    } else {
        StepsEstimateEngine.CalibrationStatus.Calibrated(
            coefficient = profileStore.stepsCalibrationCoefficient,
            sampleDays = profileStore.stepsCalibrationSampleDays,
            confidence = profileStore.stepsCalibrationConfidence,
        )
    }
    return uiString(R.string.today_steps_estimated_detail, stepsCalibrationDetail(status.detail))
}

@Composable
private fun stepsCalibrationDetail(detail: StepsEstimateEngine.CalibrationStatus.Detail): String = when (detail) {
    is StepsEstimateEngine.CalibrationStatus.Detail.Manual ->
        uiString(R.string.today_steps_manual_coefficient, String.format(Locale.getDefault(), "%.1f", detail.coefficient))
    is StepsEstimateEngine.CalibrationStatus.Detail.Calibrated -> {
        val confidence = when (detail.confidenceTier) {
            StepsEstimateEngine.ConfidenceTier.LOW -> uiString(R.string.today_steps_confidence_low)
            StepsEstimateEngine.ConfidenceTier.MEDIUM -> uiString(R.string.today_steps_confidence_medium)
            StepsEstimateEngine.ConfidenceTier.HIGH -> uiString(R.string.today_steps_confidence_high)
        }
        uiString(
            R.string.today_steps_calibrated_detail,
            String.format(Locale.getDefault(), "%.1f", detail.coefficient), detail.sampleDays, confidence,
        )
    }
    is StepsEstimateEngine.CalibrationStatus.Detail.Calibrating ->
        uiString(R.string.today_steps_calibrating_detail, detail.have, detail.need)
}

/** Group-separated integer display from a Double (e.g. 12 345 steps), matching the Apple Health tiles, so
 *  the dashboard rows format steps and calories the same way everywhere on this screen. */
private fun intStringGrouped(v: Double): String {
    val n = v.roundToInt()
    return if (kotlin.math.abs(n) >= 1000) String.format(Locale.getDefault(), "%,d", n) else "$n"
}
