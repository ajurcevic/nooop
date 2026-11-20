package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.DailyMetric
import com.noop.data.WorkoutRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.noop.analytics.BehaviorEffect
import com.noop.analytics.EffectRanker

// MARK: - Insights
//
// The "interrogate what affects what" screen, ported from the macOS InsightsView.
// Two halves:
//
//  1. BEHAVIOUR EFFECTS, split logged journal answers (the days each behaviour was
//     logged "yes" vs the days it was logged "no" — a day it was not logged at all
//     is in neither group) and compare a chosen outcome metric (Charge / HRV /
//     Rest / RHR) between the two groups. Ranked by effect size (Cohen's d), with
//     significant effects first. Each card carries a plain-English sentence, the
//     with/without means, group counts, a significance pill, and the magnitude word.
//     Tint is sign-aware: a behaviour that moves the outcome the "good" way (respecting
//     higherIsBetter) reads positive/green, the "bad" way reads critical/red.
//
//  2. METRIC RELATIONSHIPS, a curated set of Pearson correlations between daily series
//     (HRV ↔ charge, rest ↔ charge, RHR ↔ charge, charge → next-day charge),
//     each rendered as a one-line insight with r and a plain-English reading.
//
// Data note vs macOS: the Swift app computes these via the StrandAnalytics package
// (BehaviorInsights / CorrelationEngine) over a metricSeries store. On Android the
// analytics package isn't ported, and the guaranteed outcome source is the cached
// DailyMetric rows (vm.recentDays). So the outcome series here are read straight off
// those rows (recovery / avgHrv / restingHr; Rest is the resolved Rest score) and the simple, honest
// math (group means + Cohen's d, Pearson r) is computed inline below. No fabricated
// values: a behaviour or relationship only appears when there is real overlapping data.

// MARK: - Outcome (segmented selection)

/** One interrogable outcome metric: how to read it off a DailyMetric, its label,
 *  units, and whether higher is the "good" direction (drives sign-aware tint). */
private enum class Outcome(
    val label: String,
    val outcomeName: String,
    val higherIsBetter: Boolean,
    /** The Bevel colour world the outcome belongs to, drives the card wash so the
     *  Behaviour Effects section sits in one world (Charge→green, HRV/Rest→indigo,
     *  RHR→Stress teal), mirroring the Swift Outcome.domain. */
    val domain: DomainTheme,
    val pick: (DailyMetric) -> Double?,
    val format: (Double) -> String,
) {
    Recovery(
        label = uiString(R.string.l10n_insights_screen_charge_d4e1aee4), outcomeName = "Charge", higherIsBetter = true, domain = DomainTheme.Charge,
        pick = { it.recovery }, format = { "${it.roundToInt()}%" },
    ),
    Hrv(
        label = "HRV", outcomeName = "HRV", higherIsBetter = true, domain = DomainTheme.Rest,
        pick = { it.avgHrv }, format = { "${it.roundToInt()} ms" },
    ),
    Sleep(
        label = uiString(R.string.l10n_insights_screen_rest_b79e5f48), outcomeName = "Rest", higherIsBetter = true, domain = DomainTheme.Rest,
        // The Rest SCORE is not a DailyMetric column; buildModel reads it from the resolved
        // `sleep_performance` series (AppViewModel.restScoreByDay), the number Home and Sleep show.
        pick = { null }, format = { "${it.roundToInt()}%" },
    ),
    Rhr(
        label = uiString(R.string.l10n_insights_screen_rhr_04edf9b3), outcomeName = "Resting HR", higherIsBetter = false, domain = DomainTheme.Stress,
        pick = { it.restingHr?.toDouble() }, format = { "${it.roundToInt()} bpm" },
    ),
}

// MARK: - Computed shapes (plain data; behaviour effects come from the analytics package)

/** A curated metric relationship plus its computed Pearson correlation. */
private data class Relationship(
    val id: String,
    val title: String,
    val blurb: String,
    val r: Double,
    val n: Int,
) {
    /** Crude significance flag for |r| with n pairs (rough p < 0.05 threshold). */
    val significant: Boolean get() = n >= 4 && abs(r) >= significanceThreshold(n)
}

/** The fully-computed insight inputs for the current data, recomputed off recentDays. */
private data class InsightModel(
    /** behaviour question → set of days it was answered "yes". */
    val behaviours: Map<String, Set<String>>,
    /** Per behaviour, the days it was logged NO — the only legitimate control group. A day with no
     *  journal row for the question is in neither map and takes part in no comparison. */
    val controls: Map<String, Set<String>>,
    /** day → value, per outcome. */
    val outcomeByDay: Map<Outcome, Map<String, Double>>,
    /** ordered (day, value) per outcome for correlations. */
    val seriesByOutcome: Map<Outcome, List<Pair<String, Double>>>,
    /**
     * #322: numeric journal item (question) → [day: value]. A numeric series is the same
     * Map<String, Double> shape EffectRanker.rank's `outcomeByDay` takes, so a numeric journal item
     * ("caffeine mg", "alcohol units") is a first-class series the ranker can consume like any metric
     * outcome (dose-response lands in the v5 hub). Empty for a yes/no-only journal.
     */
    val numericJournalSeries: Map<String, Map<String, Double>> = emptyMap(),
)

// MARK: - Screen

/**
 * Insights, behaviour effects + metric relationships over cached history.
 *
 * Loads the journal (all days) and the per-day outcome series from `vm.recentDays`,
 * then presents the ranked behaviour effects for the selected outcome and the curated
 * Pearson relationships. Empty/sparse states explain what's missing rather than faking
 * numbers, matching the macOS data-display contract.
 */
@Composable
fun InsightsScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()

    // Journal answers (all history, read-only): imported "my-whoop" rows UNIONED with the native
    // "noop-journal" rows logged before in-app journaling was removed (native wins per (day, question)).
    var behaviours by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var controls by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    // #322: numeric journal item (question) -> [day: value]. A numeric journal series is a daily series
    // the effect ranker consumes exactly like a metric series (EffectRanker.effect already takes a
    // Map<String, Double> outcome), so "caffeine mg" / "alcohol units" can rank as a numeric outcome.
    var numericJournalSeries by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }
    var journalLoaded by remember { mutableStateOf(false) }
    // Journaling no longer adds answers, so the card says which dates its effects come from.
    var journalCoverage by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val imported = vm.repo.journal("my-whoop", "0000-01-01", "9999-12-31")
        val native = vm.repo.journal(JOURNAL_DEVICE_ID, "0000-01-01", "9999-12-31")
        val entries = mergeJournalEntries(imported, native)
        val byBehaviour = mutableMapOf<String, MutableSet<String>>()
        val controlsByBehaviour = mutableMapOf<String, MutableSet<String>>()
        // #322: a numeric log writes answeredYes=true too, so a numeric item lands in the with/without
        // split here unchanged; its per-day value is captured separately for a numeric series the effect
        // ranker can consume like any metric outcome (dose-response lands in the v5 hub). Additive.
        val numericByBehaviour = mutableMapOf<String, MutableMap<String, Double>>()
        for (e in entries) {
            val bucket = if (e.answeredYes) byBehaviour else controlsByBehaviour
            bucket.getOrPut(e.question) { mutableSetOf() }.add(e.day)
            e.numericValue?.let { v -> numericByBehaviour.getOrPut(e.question) { mutableMapOf() }[e.day] = v }
        }
        behaviours = byBehaviour.mapValues { it.value.toSet() }
        controls = controlsByBehaviour.mapValues { it.value.toSet() }
        numericJournalSeries = numericByBehaviour.mapValues { it.value.toMap() }
        journalCoverage = journalCoverageCaption(entries)
        journalLoaded = true
    }

    // Selected outcome metric for the behaviour-effects half.
    var outcome by remember { mutableStateOf(Outcome.Recovery) }


    // Build outcome day-maps + ordered series off the cached daily metrics. Cheap and
    // recomputed only when `days` changes (not on every recomposition).
    var restScoreByDay by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(days) { restScoreByDay = vm.restScoreByDay() }
    val model = remember(days, restScoreByDay, behaviours, controls, numericJournalSeries) {
        buildModel(days, restScoreByDay, behaviours, controls, numericJournalSeries)
    }

    // Ranked behaviour effects for the current outcome (recomputed when outcome/data change).
    val ranked = remember(model, outcome) { rankEffects(model, outcome) }
    // Curated relationships (independent of the selected outcome).
    val relationships = remember(model) { computeRelationships(model) }

    // --- Activity Cost (#439): the engine is pure + unit-tested; shape its inputs HERE. ---
    // Load the sessions (ALL sources, dismissed-filtered) the same way the Workouts screen does, then
    // build [sport: Set<localDayKey>] + [localDayKey: Charge] and rank the per-sport recovery cost.
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadWorkouts() }
    val activityCosts = remember(workouts, days) { computeActivityCosts(workouts, days) }

    // PERF (#707): migrate to the lazy scaffold so only on-screen sections compose + get
    // accessibility-walked. Each eager child becomes its own `item { }`, INCLUDING the standalone
    // `Spacer(sectionGap - 20)` separators, which are real Column children that already sat inside the
    // eager `spacedBy(20.dp)`; a LazyColumn with the same `spacedBy(20.dp)` flanks each item identically,
    // so the rendered spacing is byte-for-byte unchanged. Conditional sections use `if (cond) { item {} }`
    // (never `item { if (cond) }`) so a hidden section emits NO item, an unconditional empty item would
    // otherwise insert a 0-height row that the 20dp arrangement flanks, shifting layout. The one
    // composable-only block (`run { … remember(snapshot) … }`) moves inside its `item { }` (which is
    // @Composable). Order is preserved exactly.
    // LIQUID SKY BACKDROP (the pilot pattern — LiquidScreenSky.kt): the static time-of-day liquid sky
    // settles into the theme canvas behind the header + the first cards (and bleeds full-width up behind the
    // status bar via the scaffold's topBackground plumbing), top-aligned, so the analysis cards float OVER
    // the sky on the flat surface below. The Android equivalent of the iOS
    // `ScreenScaffold(topBackground: liquidScaffoldSky())`; reuses the shared LiquidScreenSky() slot verbatim.
    // Insights has no day-cycle gate of its own, so the sky is always drawn (matching the liquid explorer).
    // Day-cycle sky + sky-behind-cards: the SAME two Appearance gates every other screen honours.
    // (This screen previously drew the sky unconditionally - it now matches Today/Trends/Sleep,
    // including turning OFF with the day-cycle setting.) Read once; SharedPreferences isn't reactive.
    val skyCtx = androidx.compose.ui.platform.LocalContext.current
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(skyCtx) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(skyCtx) }
    LazyScreenScaffold(
        title = uiString(R.string.l10n_insights_screen_insights_b4510362),
        subtitle = "Interrogate what affects what.",
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        // Sky-behind-cards fills the viewport so the transparent cards reveal the sky the whole way
        // down (Today / Trends / Sleep / metric-detail parity - same two prefs, same two behaviours).
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {

        // --- Behaviour effects -------------------------------------------------
        // (Always emits one of three branches → one unconditional item, never empty.)
        item {
        if (!journalLoaded) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_insights_screen_reading_your_journal_and_outcomes_4a59af69),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else if (behaviours.isEmpty()) {
            // No journal yet, explain, without dead-ending on a paid export.
            DataPendingNote(
                title = uiString(R.string.l10n_insights_screen_insights_read_your_journal_and_outcomes_6ec8aaf9),
                body = "Importing a WHOOP export (which includes its journal) lets Zoop rank how " +
                    "each behaviour moves your recovery, HRV and sleep.",
            )
        } else {
            BehaviourSection(
                outcome = outcome,
                onOutcome = { outcome = it },
                ranked = ranked,
                coverage = journalCoverage,
            )
        }
        }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Activity Cost (what each activity costs your recovery) ------------
        item { ActivityCostSection(activityCosts) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Metric relationships ---------------------------------------------
        item { RelationshipsSection(relationships) }
    }
}

// MARK: - Activity Cost section (#439)
//
// "What each activity costs your recovery": one ranked NoopCard per sport that cleared the engine's
// minSessions gate, each carrying next-morning Charge vs rest baseline, days-to-baseline, the sample
// count + confidence pill, and the engine's plain-English sentence. Sign-aware tint: a positive cost
// (recovery dipped) reads warm/critical, a recovery-POSITIVE delta reads green. Mirrors the Swift
// InsightsView.activityCostSection exactly.

/**
 * Shape the [ActivityCostEngine] inputs from the loaded sessions + cached daily metrics, then rank.
 * [workouts] → [sport: Set<localDayKey>] (displaySport collapses detected/"Activity" into one bucket
 * and de-camelCases WHOOP names; manual/imported labels pass through), keyed by the LOCAL calendar
 * day the session STARTED via the SAME AnalyticsEngine.dayString path [DailyMetric.day] uses, so the
 * engine's D+1 next-morning alignment is honest. [days] → [localDayKey: Charge] off DailyMetric.recovery.
 */
internal fun computeActivityCosts(
    workouts: List<WorkoutRow>,
    days: List<DailyMetric>,
): List<com.noop.analytics.ActivityCost> {
    // Single "now" offset for every session, the SAME tz-offset basis IntelligenceEngine.kt uses to
    // key DailyMetric.day (getOffset(now)/1000 applied across the run), via the SAME
    // AnalyticsEngine.dayString(ts, offsetSec) path, so the engine's D+1 next-morning lookups align
    // byte-for-byte with the recovery keys (and match the Swift TimeZone.current.secondsFromGMT path).
    val offsetSec = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1_000L
    val activityDaysBySport = HashMap<String, MutableSet<String>>()
    for (w in workouts) {
        val sport = WorkoutEditing.displaySport(w.sport)
        if (sport.isEmpty()) continue
        val day = com.noop.analytics.AnalyticsEngine.dayString(w.startTs, offsetSec)
        activityDaysBySport.getOrPut(sport) { mutableSetOf() }.add(day)
    }
    val recoveryByDay = HashMap<String, Double>()
    for (d in days) {
        d.recovery?.let { recoveryByDay[d.day] = it }
    }
    return com.noop.analytics.ActivityCostEngine.evaluate(
        activityDaysBySport = activityDaysBySport.mapValues { it.value.toSet() },
        recoveryByDay = recoveryByDay,
    )
}

@Composable
private fun ActivityCostSection(costs: List<com.noop.analytics.ActivityCost>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader("Activity Cost", overline = "What each activity costs your recovery")
        if (costs.isEmpty()) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_insights_screen_tag_a_few_sessions_of_the_97927401),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            // Fade + rise the ranked cost cards in sequence (mirrors iOS .staggeredAppear(index:)).
            costs.forEachIndexed { i, cost ->
                Box(modifier = Modifier.staggeredAppear(i)) { ActivityCostCard(cost) }
            }
        }
    }
}

@Composable
private fun ActivityCostCard(cost: com.noop.analytics.ActivityCost) {
    // Sign-aware accent: a POSITIVE delta means the next morning sat BELOW baseline (it cost you) →
    // warm/critical; a negative delta means you woke higher → green. Near-zero reads neutral gold so
    // "barely moves" doesn't shout either way.
    val costing = cost.delta >= com.noop.analytics.ActivityCostEngine.barelyMovesPoints
    val lifting = cost.delta <= -com.noop.analytics.ActivityCostEngine.barelyMovesPoints
    val accent: Color = when {
        costing -> Palette.statusCritical
        lifting -> Palette.statusPositive
        else -> Palette.chargeColor
    }
    val solid = cost.confidence == com.noop.analytics.ScoreConfidence.SOLID
    val pointsLabel = (if (cost.delta >= 0) "−" else "+") + abs(cost.delta).roundToInt()

    NoopCard(tint = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    sportIcon(cost.sport),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    cost.sport,
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatePill(
                    if (solid) "SOLID" else "BUILDING",
                    tone = if (solid) StrandTone.Positive else StrandTone.Accent,
                    showsDot = false,
                )
            }
            Text(cost.sentence(), style = NoopType.subhead, color = Palette.textSecondary)
            // 2×2 StatTile grid so tile heights stay uniform on phone widths (matches SummarySection).
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_insights_screen_next_morning_61d1ea83),
                    value = "${cost.meanNextMorning.roundToInt()}",
                    caption = "Charge · $pointsLabel pts",
                    accent = accent,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_insights_screen_rest_baseline_b3ac52a5),
                    value = "${cost.baselineMean.roundToInt()}",
                    caption = "untouched days",
                    accent = Palette.textPrimary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_insights_screen_bounce_back_be2d66a4),
                    value = cost.daysToBaseline?.let { "${it}d" } ?: "—",
                    caption = if (cost.daysToBaseline != null) "to baseline" else "not within 7d",
                    accent = Palette.chargeColor,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_insights_screen_sessions_e11e37a9),
                    value = "${cost.n}",
                    caption = if (solid) "solid" else "building",
                    accent = Palette.textPrimary,
                )
            }
        }
    }
}

// MARK: - Behaviour effects section

@Composable
private fun BehaviourSection(
    outcome: Outcome,
    onOutcome: (Outcome) -> Unit,
    ranked: List<BehaviorEffect>,
    coverage: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader(
                    "Behaviour Effects",
                    overline = "What moves your ${outcome.outcomeName.lowercase(Locale.US)}",
                )
            }
            SegmentedPillControl(
                items = Outcome.entries.toList(),
                selection = outcome,
                label = { it.label },
                onSelect = onOutcome,
            )
        }

        if (coverage != null) {
            Text(coverage, style = NoopType.caption, color = Palette.textTertiary)
        }

        if (ranked.isEmpty()) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_insights_screen_not_enough_overlap_between_your_journal_0ebdd7a2, outcome.outcomeName.lowercase(Locale.US)),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            // Fade + rise the ranked cards in sequence (mirrors iOS .staggeredAppear(index:)).
            ranked.forEachIndexed { i, e ->
                Box(modifier = Modifier.staggeredAppear(i)) { EffectCard(e, outcome) }
            }
        }
    }
}

/** One behaviour-effect card: sentence + with/without StatTiles + significance pill. */
@Composable
private fun EffectCard(e: BehaviorEffect, outcome: Outcome) {
    // Sign-aware tint: did this behaviour move the outcome the GOOD way?
    val movedGood: Boolean? = when {
        e.delta == 0.0 -> null
        else -> (e.delta > 0) == outcome.higherIsBetter
    }
    val tone: StrandTone = when (movedGood) {
        null -> StrandTone.Neutral
        true -> StrandTone.Positive
        false -> if (e.significant) StrandTone.Critical else StrandTone.Warning
    }
    val tintColor = tone.color
    val arrow = if (e.delta > 0) "↑" else if (e.delta < 0) "↓" else "→"
    val deltaText = "$arrow ${String.format(Locale.US, "%.1f", abs(e.delta))}"
    val sentence = effectSentence(e, outcome)

    // The card wash reads as the OUTCOME's colour world (so the whole Behaviour Effects
    // section sits in one world), while the dot / StatTile accents stay sign-aware.
    NoopCard(tint = outcome.domain.color) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {

            // Header: behaviour name (tinted dot) + significance pill.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .drawBehind { drawCircle(tintColor) },
                    )
                    Text(
                        e.behavior,
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatePill(
                    if (e.significant) "SIGNIFICANT" else "EXPLORATORY",
                    tone = if (e.significant) StrandTone.Positive else StrandTone.Neutral,
                    showsDot = false,
                )
            }

            // Plain-English sentence.
            Text(sentence, style = NoopType.body, color = Palette.textSecondary)

            // With / without means as uniform StatTiles.
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_insights_screen_with_564f8c6e),
                    value = outcome.format(e.meanWith),
                    caption = "n = ${e.nWith}",
                    accent = tintColor,
                    delta = deltaText,
                    deltaColor = tintColor,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_insights_screen_without_cb735356),
                    value = outcome.format(e.meanWithout),
                    caption = "n = ${e.nWithout}",
                    accent = Palette.textPrimary,
                )
            }

            HorizontalDivider(color = Palette.hairline)

            // Effect-size footer: Cohen's d + magnitude word.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Overline("Effect size", modifier = Modifier.weight(1f))
                Text(
                    String.format(Locale.US, "d = %.2f", e.cohensD),
                    style = NoopType.captionNumber,
                    color = tintColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    effectMagnitudeWord(e.cohensD),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

// MARK: - Metric relationships section

@Composable
private fun RelationshipsSection(rels: List<Relationship>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader("Metric Relationships", overline = "Pearson r")

        if (rels.isEmpty()) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_insights_screen_not_enough_overlapping_history_to_correlate_a552dbd4),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            // Every curated relationship terminates in Charge, so the card sits in the
            // Charge (green) colour world via a faint wash.
            NoopCard(tint = DomainTheme.Charge.color) {
                Column {
                    rels.forEachIndexed { idx, rel ->
                        RelationshipRow(rel)
                        if (idx < rels.size - 1) {
                            HorizontalDivider(color = Palette.hairline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationshipRow(rel: Relationship) {
    val strength = correlationColor(rel.r)
    val sentence = relationshipSentence(rel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp)
            .clearAndSetSemantics { contentDescription = sentence },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                rel.title,
                style = NoopType.headline,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                String.format(Locale.US, "r = %+.2f", rel.r),
                style = NoopType.number(16f),
                color = strength,
            )
            StatePill(
                if (rel.significant) "p < 0.05" else "n.s.",
                tone = if (rel.significant) StrandTone.Accent else StrandTone.Neutral,
                showsDot = false,
            )
        }

        // r bar, centred zero, fills left (negative) / right (positive) by |r|.
        RBar(r = rel.r, color = strength)

        Text(sentence, style = NoopType.subhead, color = Palette.textSecondary)
        Text(rel.blurb, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/**
 * A centred correlation bar: a faint inset track with a centre tick at zero, and a
 * coloured fill that grows left (negative r) or right (positive r) proportional to |r|.
 * Mirrors the macOS RBar (minus the desktop hover tooltip, the exact r value is already
 * printed beside the title, so the bar is never an unexplained coloured shape on phone).
 */
@Composable
private fun RBar(r: Double, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .drawBehind {
                val half = size.width / 2f
                val mag = (abs(r).coerceAtMost(1.0)).toFloat() * half
                // Inset track.
                drawLine(
                    color = Palette.surfaceInset,
                    start = Offset(size.height / 2f, size.height / 2f),
                    end = Offset(size.width - size.height / 2f, size.height / 2f),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                // Centre tick.
                drawLine(
                    color = Palette.hairlineStrong,
                    start = Offset(half, 0f),
                    end = Offset(half, size.height),
                    strokeWidth = 1f,
                )
                // Value fill from centre outward.
                if (mag > 0f) {
                    val start = if (r >= 0) Offset(half, size.height / 2f)
                    else Offset(half - mag, size.height / 2f)
                    val end = if (r >= 0) Offset(half + mag, size.height / 2f)
                    else Offset(half, size.height / 2f)
                    drawLine(
                        color = color,
                        start = start,
                        end = end,
                        strokeWidth = size.height,
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}

// MARK: - Model building + math (simple, honest, no external analytics)

/** Build the per-outcome day maps + ordered series from cached daily metrics. */
private fun buildModel(
    days: List<DailyMetric>,
    restScoreByDay: Map<String, Double>,
    behaviours: Map<String, Set<String>>,
    controls: Map<String, Set<String>>,
    numericJournalSeries: Map<String, Map<String, Double>> = emptyMap(),
): InsightModel {
    val outcomeByDay = mutableMapOf<Outcome, Map<String, Double>>()
    val seriesByOutcome = mutableMapOf<Outcome, List<Pair<String, Double>>>()
    for (o in Outcome.entries) {
        // Oldest → newest; one value per day (DailyMetric PK is (deviceId, day)).
        val series = days.mapNotNull { d ->
            val v = if (o == Outcome.Sleep) restScoreByDay[d.day] else o.pick(d)
            v?.let { d.day to it }
        }
        seriesByOutcome[o] = series
        outcomeByDay[o] = series.toMap()
    }
    return InsightModel(behaviours, controls, outcomeByDay, seriesByOutcome, numericJournalSeries)
}

/** Rank behaviour effects for one outcome by |Cohen's d|, significant first. */
private fun rankEffects(model: InsightModel, outcome: Outcome): List<BehaviorEffect> {
    val outcomeDays = model.outcomeByDay[outcome] ?: emptyMap()
    if (outcomeDays.isEmpty()) return emptyList()
    // Through the shared engine, not a local copy. This screen used to carry its own with/without split,
    // its own pooled-SD Cohen's d and a "crude significance" (|d| >= 0.5 with >= 3 a side) where iOS's
    // same screen ran a Welch p — so identical journals could flag different behaviours on the two
    // platforms. EffectRanker.rankNoLag is the byte-identical twin of Swift's BehaviorInsights.rank,
    // which is what iOS calls here.
    return EffectRanker.rankNoLag(model.behaviours, model.controls, outcomeDays, outcome.outcomeName)
}

/** The curated metric relationships, computed via Pearson r over aligned day pairs. */
private fun computeRelationships(model: InsightModel): List<Relationship> {
    fun series(o: Outcome) = model.seriesByOutcome[o] ?: emptyList()
    val out = mutableListOf<Relationship>()

    pearsonAligned(series(Outcome.Hrv), series(Outcome.Recovery))?.let { (r, n) ->
        out.add(
            Relationship(
                "hrv-rec", "HRV ↔ Charge",
                "Heart-rate variability as the engine behind your charge score.", r, n,
            ),
        )
    }
    pearsonAligned(series(Outcome.Sleep), series(Outcome.Recovery))?.let { (r, n) ->
        out.add(
            Relationship(
                "sleep-rec", "Rest ↔ Charge",
                "How closely a good night tracks next-morning charge.", r, n,
            ),
        )
    }
    pearsonAligned(series(Outcome.Rhr), series(Outcome.Recovery))?.let { (r, n) ->
        out.add(
            Relationship(
                "rhr-rec", "Resting HR ↔ Charge",
                "A lower resting heart rate usually means a higher charge.", r, n,
            ),
        )
    }
    pearsonLagged(series(Outcome.Recovery), lagDays = 1)?.let { (r, n) ->
        out.add(
            Relationship(
                "rec-lag", "Charge → Next-day charge",
                "How much one day's charge carries into the next.", r, n,
            ),
        )
    }

    return out
}

// MARK: - Statistics (pooled-SD Cohen's d, Pearson r)

/** Pearson r over two (day,value) series aligned on shared days. Returns (r, n) or
 *  null if fewer than 3 overlapping pairs or no variance. */
private fun pearsonAligned(
    xs: List<Pair<String, Double>>,
    ys: List<Pair<String, Double>>,
): Pair<Double, Int>? {
    val ym = ys.toMap()
    val pairs = xs.mapNotNull { (day, x) -> ym[day]?.let { x to it } }
    return pearson(pairs)
}

/** Pearson r of a series against itself shifted forward by [lagDays] days.
 *  Uses index offset on the ordered series (days are oldest → newest). */
private fun pearsonLagged(series: List<Pair<String, Double>>, lagDays: Int): Pair<Double, Int>? {
    if (series.size <= lagDays) return null
    val pairs = (0 until series.size - lagDays).map { i ->
        series[i].second to series[i + lagDays].second
    }
    return pearson(pairs)
}

/** Pearson correlation of paired samples. Null with <3 pairs or no variance. */
private fun pearson(pairs: List<Pair<Double, Double>>): Pair<Double, Int>? {
    val n = pairs.size
    if (n < 3) return null
    val mx = pairs.sumOf { it.first } / n
    val my = pairs.sumOf { it.second } / n
    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    for ((x, y) in pairs) {
        val dx = x - mx
        val dy = y - my
        sxy += dx * dy
        sxx += dx * dx
        syy += dy * dy
    }
    val denom = sqrt(sxx * syy)
    if (denom <= 0.0 || !denom.isFinite()) return null
    return (sxy / denom).coerceIn(-1.0, 1.0) to n
}

/** Rough |r| threshold for "p < 0.05" at n pairs (critical r for a two-tailed test,
 *  approximated by 2 / sqrt(n), a standard rule-of-thumb). Honest, not exact. */
private fun significanceThreshold(n: Int): Double =
    if (n < 4) 1.1 else (2.0 / sqrt(n.toDouble())).coerceAtMost(1.0)

// MARK: - Text + colour helpers

private fun effectSentence(e: BehaviorEffect, outcome: Outcome): String {
    val dir = when {
        e.delta > 0 -> "higher"
        e.delta < 0 -> "lower"
        else -> "no different"
    }
    val name = outcome.outcomeName.lowercase(Locale.US)
    if (e.delta == 0.0) {
        return "On days you logged ${e.behavior.lowercase(Locale.US)}, your $name was no different."
    }
    val withStr = outcome.format(e.meanWith)
    val withoutStr = outcome.format(e.meanWithout)
    return "On days you logged ${e.behavior.lowercase(Locale.US)}, your $name averaged " +
        "$withStr, $dir than the $withoutStr on days you didn't."
}

private fun effectMagnitudeWord(d: Double): String {
    val m = abs(d)
    return when {
        m < 0.2 -> "negligible"
        m < 0.5 -> "small"
        m < 0.8 -> "moderate"
        else -> "large"
    }
}

private fun strengthWord(r: Double): String {
    val m = abs(r)
    return when {
        m < 0.1 -> "No"
        m < 0.3 -> "A weak"
        m < 0.5 -> "A moderate"
        m < 0.7 -> "A strong"
        else -> "A very strong"
    }
}

private fun relationshipSentence(rel: Relationship): String {
    val dir = if (rel.r > 0) "positive" else if (rel.r < 0) "negative" else "flat"
    return "${strengthWord(rel.r)} $dir relationship " +
        "(r = ${String.format(Locale.US, "%.2f", rel.r)}, n = ${rel.n})."
}

/** Tint a correlation by strength, keyed on the recovery gradient so strong positive
 *  reads mint and strong negative reads red. Maps r∈[-1,1] → 0…1 of the scale. */
private fun correlationColor(r: Double): Color =
    Palette.sample(Palette.recoveryStops, ((r + 1.0) / 2.0).toFloat())
