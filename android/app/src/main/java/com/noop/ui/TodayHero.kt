package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.ReadinessEngine
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Today tab: the Charge / Effort / Rest score hero and the vitals rows under it.
// Split out of TodayScreen.kt; behaviour unchanged.

// MARK: - Score hero row, three Charge / Effort / Rest score rings
//
// The ring-style Today hero: three equal daily-score GlowRings in Charge / Effort / Rest order, with a
// tappable label beneath each one and one card-level provenance badge aligned to the Rest ring's trailing
// edge. Same GlowRing primitive the Intelligence forecast card uses, with its spring arc + count-up anim.

@Composable
internal fun ScoreHeroRow(
    day: DailyMetric?,
    restScore: Double?,
    recoveryCalibration: Int?,
    lastScoredCharge: LastCharge? = null,
    effortScale: EffortScale,
    liveTodayStrain: Double? = null,
    // One card-level provenance label derived from the three REAL per-metric merge winners upstream.
    heroSourceLabel: String? = null,
    onScoreInfo: (ScoreSection) -> Unit,
    // A1 (#514/#706): tapping the Charge ring opens the breakdown sheet. A small chevron cue overlays the
    // ring's bottom edge INSIDE the ring frame, so it adds no stacked height (the #762 self-sizing parity).
    onChargeTap: (() -> Unit)? = null,
    // #1164: pending-sync state for today's Rest (strap has banked records not yet offloaded). When true
    // the Rest ring shows "Pending sync" instead of a provisional number that will change.
    restPendingSync: Boolean = false,
    // #1995: tapping the Effort or Rest ring opens that score's own vital detail, the same trend the
    // metric cards below already push, so the ring and its card can never lead to different screens.
    // Charge keeps `onChargeTap` (its breakdown sheet), which is richer than a trend and has no twin on
    // the iOS liquid Today. Defaulted to a no-op so the ring stays inert where a host does not bind it.
    onOpenMetric: ((String) -> Unit)? = null,
) {
    val recovery = day?.recovery
    // Prefer the live in-progress Effort for today, but never BELOW the day's already-earned strain
    // (#489/#506: a live under-read replaced today's real Effort with 0). The effective value drives the
    // gauge number AND the has-data / "No Data" branch, so the ring only reads "No Data" when neither
    // exists. Mirrors the iOS live-Effort gauge. (#402)
    // #1001: the shared rule, so this ring and the Key Metrics tile / chart badge cannot disagree.
    val strain = StrainScorer.effectiveEffort(live = liveTodayStrain, stored = day?.strain)
    // Effort honours the 0–100 / WHOOP-0–21 toggle (#313). The stored strain is on NOOP's 0–100 Effort
    // axis; render it on the user's selected scale so the arc and centre number match the app's Effort.
    val effortOutOf = if (effortScale == EffortScale.WHOOP) 21.0 else 100.0
    val effortVal = strain?.let { UnitFormatter.effortValue(it, effortScale) } ?: 0.0

    // Vessel path only: the vessels run LIVE (per-frame slosh + tilt) once the row has any real score to
    // show; a wholly empty/calibrating hero poses them static so a brand-new user's launch churn isn't
    // fighting live canvases (the Android equivalent of the iOS `dataLoaded` gate on HeroScoreCell).
    // LiquidVessel + the count-up both honour Reduce Motion internally, so this is purely a "don't animate
    // an empty hero" cost gate. A carried Charge counts as data (its dimmed vessel should slosh like the
    // Rest one). The ring rendering ignores it: GlowRing springs once to its target and then sits still,
    // so there is no per-frame cost to gate.
    val heroVesselsAnimated =
        recovery != null || strain != null || restScore != null || lastScoredCharge != null

    Box(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        // iOS parity: the hero rings float DIRECTLY on the SCREEN-level day-cycle scene (the scaffold's
        // topBackground), not on any per-hero atmosphere or the old scenic indigo gradient, matching
        // TodayView, which moved the scene to a screen-level SceneScreenBackground and dropped the
        // per-hero scene/ScenicHeroBackground.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.gap, vertical = Metrics.space16),
        ) {
            // iOS parity (TodayView.scoreHeroRow): three EQUAL rings in CHARGE · EFFORT · REST order, no
            // enlarged centre, filling the width as one balanced row. Ring stroke 0.10 (WHOOP weight).
            val ringGap = 14.dp
            // #1502: ONE width for all three columns. Charge and Effort used to size to their own label
            // rows while Rest alone was pinned to `ring` (its box anchors the source badge), so the three
            // columns came out different widths — read as "the rings aren't the same size" — and Rest's
            // label had the least room of the three despite REST being the shortest word, which is why it
            // was the one ellipsising to "R…". Three of these plus two gaps is exactly maxWidth.
            val col = (maxWidth - ringGap * 2) / 3
            // The 90.dp floor can exceed `col` once the hero is under ~298.dp wide — a small phone, a
            // split-screen pane, a foldable's cover display. Unbounded, that overflowed the column; bounded,
            // the ring simply shrinks with its column instead of spilling out of it.
            val ring = ((maxWidth - ringGap * 2) / 3.1f).coerceIn(90.dp, 112.dp).coerceAtMost(col)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ringGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
            ) {
                // CHARGE, recovery 0–100, as a GlowRing with the value counting up in the centre. Honest
                // empty / calibrating overlay; badges its recovery winner.
                val effortRingTap = onOpenMetric?.let { open -> { open(HERO_EFFORT_METRIC_KEY) } }
                val restRingTap = onOpenMetric?.let { open -> { open(HERO_REST_METRIC_KEY) } }
                HeroRingColumn(
                    modifier = Modifier.width(col),
                    domain = DomainTheme.Charge,
                    onInfo = { onScoreInfo(ScoreSection.CHARGE) },
                    onRingTap = onChargeTap,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // #802: when today has no Charge yet but a prior night's value is carried, draw a
                        // DIMMED (0.8 opacity) REAL ring filled to the carried value, matching the Rest
                        // ring, rather than a bare number on an empty ring (which read as broken). Same
                        // diameter so the self-sizing hero row is untouched; the dim + the carried "Last
                        // night · <date>" caption mark it as carried, not today's fresh score. Mirrors iOS.
                        val carried = if (recovery == null && recoveryCalibration == null) lastScoredCharge else null
                        if (carried != null) {
                            HeroScoreVessel(
                                modifier = Modifier.alpha(0.8f),
                                fraction = carried.value / 100.0,
                                value = carried.value,
                                tint = Palette.recoveryColor(carried.value),
                                diameter = ring,
                                showsValue = true,
                                animated = heroVesselsAnimated,
                                onTap = onChargeTap,
                            )
                        } else {
                            HeroScoreVessel(
                                fraction = (recovery ?: 0.0) / 100.0,
                                value = recovery ?: 0.0,
                                tint = Palette.recoveryColor(recovery ?: 0.0),
                                diameter = ring,
                                showsValue = recovery != null,
                                animated = heroVesselsAnimated,
                                onTap = onChargeTap,
                            )
                            // Empty ring + calibrating / no-data overlay (the carried case is above).
                            if (recovery == null) RingEmptyOverlay(recoveryCalibration, diameter = ring)
                        }
                        // No in-ring tap cue: the single tap affordance is the CHARGE-label chevron below
                        // the ring (HeroRingColumn), matching iOS where the in-ring cue was removed.
                    }
                }
                // EFFORT, strain on the gauge, on the user's selected scale, as a GlowRing.
                HeroRingColumn(
                    modifier = Modifier.width(col),
                    domain = DomainTheme.Effort,
                    onInfo = { onScoreInfo(ScoreSection.EFFORT) },
                    onRingTap = effortRingTap,
                    ringTapLabel = uiString(
                        R.string.today_action_open_detail,
                        uiString(R.string.today_metric_effort),
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Show the optimal strain range as a gray segment in the Effort ring.
                        // The range is derived from recovery (Charge) and shows where the current effort
                        // should ideally fall. Returned as a 0..1 fraction (independent of the user's selected scale).
                        val targetFraction = optimalFractionRange(recovery)
                        HeroScoreVessel(
                            fraction = if (effortOutOf > 0) effortVal / effortOutOf else 0.0,
                            value = effortVal,
                            tint = Palette.effortTint((strain ?: 0.0) / 100.0),
                            diameter = ring,
                            showsValue = strain != null,
                            format = { if (effortScale == EffortScale.WHOOP) String.format(Locale.getDefault(), "%.1f", it) else it.toInt().toString() },
                            animated = heroVesselsAnimated,
                            onTap = effortRingTap,
                            targetRange = targetFraction,
                        )
                        if (strain == null) RingNoData(diameter = ring)
                    }
                }
                // REST, sleep composite 0–100. Its fixed-width box also anchors the card-level source badge:
                // the badge may grow leftward, but its trailing edge always matches the Rest ring.
                Box(modifier = Modifier.width(col)) {
                    HeroRingColumn(
                        modifier = Modifier.width(col),
                        domain = DomainTheme.Rest,
                        onInfo = { onScoreInfo(ScoreSection.REST) },
                        onRingTap = restRingTap,
                        ringTapLabel = uiString(
                            R.string.today_action_open_detail,
                            uiString(R.string.today_metric_rest),
                        ),
                        // #1164/#2012: the provisional state is now said BESIDE the number instead of
                        // replacing it. See [HeroRingColumn]'s caption for why.
                        caption = if (restPendingSync) {
                            uiString(R.string.l10n_today_screen_pending_sync_cbe01f9e)
                        } else {
                            null
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            HeroScoreVessel(
                                fraction = (restScore ?: 0.0) / 100.0,
                                value = restScore ?: 0.0,
                                tint = Palette.recoveryColor(restScore ?: 0.0),
                                diameter = ring,
                                showsValue = restScore != null,
                                animated = heroVesselsAnimated,
                                onTap = restRingTap,
                            )
                            if (restScore == null) {
                                // #898: an aggregate-import user (a daily HRV/RHR import, no in-bed session) gets a
                                // Charge from WatchRecovery but NO sleep_performance, so Rest used to read a bare
                                // "No Data" next to a lit Charge , reading as broken. When a Charge IS present for the
                                // day but Rest is absent, say WHY honestly ("Needs a tracked night") instead. We do
                                // NOT fabricate a Rest number , an aggregate genuinely has no scored night. A day with
                                // no Charge either (truly empty) keeps the plain "No Data". Mirrors iOS restRing.
                                if (recovery != null) RingNeedsTrackedNight() else RingNoData(diameter = ring)
                            }
                        }
                    }
                    if (heroSourceLabel != null) {
                        SourceBadge(
                            text = heroSourceLabel,
                            // #1160: the hero is theme-aware now, so its badge uses the flip-able text token.
                            tint = Palette.textSecondary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                // Measure the full label even when it is wider than the Rest ring, then
                                // let it overflow left while preserving the ring-aligned trailing edge.
                                .wrapContentWidth(unbounded = true, align = Alignment.End)
                                // #1502: the box is now the shared column width rather than the ring's,
                                // so inset by the slack to keep the badge's trailing edge on the RING —
                                // the alignment this anchor exists for.
                                .padding(end = ((col - ring) / 2).coerceAtLeast(0.dp))
                                // Match iOS: centre the pill on the card border, aligned with the Rest ring.
                                .offset(y = -(Metrics.space16 + Metrics.sourceBadgeHeight / 2))
                                .semantics { contentDescription = uiString(R.string.l10n_today_screen_source_herosourcelabel_d3363687, heroSourceLabel) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One hero ring column: the ring, with a tappable UPPERCASE domain label + chevron beneath it (the
 * WHOOP affordance) that opens the matching scoring-guide section. Provenance belongs to the whole hero
 * card and is rendered once by [ScoreHeroRow], so this column only owns score content and navigation.
 */
@Composable
private fun HeroRingColumn(
    domain: DomainTheme,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    // A1: when non-null the ring is tappable. Charge opens the breakdown sheet; Effort and Rest open
    // their metric detail (#1995). The chevron cue is overlaid by the caller INSIDE the ring box so it
    // adds no stacked height (#762 self-sizing parity).
    //
    // This wires the tap for the ring's SURROUND and for TalkBack (which activates the semantics node
    // directly). A touch that lands on the ring itself never reaches here: see the onTap the callers
    // hand to HeroScoreVessel.
    onRingTap: (() -> Unit)? = null,
    // Charge's ring opens a breakdown, so "see what shaped" is honest there. Effort and Rest open a
    // trend detail instead, so they override it rather than announce a breakdown they do not show.
    ringTapLabel: String? = null,
    // #2012: an optional one-line note under the domain label — currently Rest's "Pending sync".
    //
    // It lives HERE, under the label, rather than over the ring, for two reasons. It cannot cover the
    // score, which is what made the old overlay hide a number the user had every right to see. And it is
    // laid out at the COLUMN's width rather than the ring's, so it has room to render: the overlay was
    // measured against the circle and ellipsised its own explanation mid-word ("strap history still o...")
    // while spilling past the ring's edge.
    caption: String? = null,
    ring: @Composable () -> Unit,
) {
    val domainLabel = uiString(
        when (domain) {
            DomainTheme.Charge -> R.string.today_metric_charge
            DomainTheme.Effort -> R.string.today_metric_effort
            DomainTheme.Rest -> R.string.today_metric_rest
            DomainTheme.Stress -> R.string.today_card_stress
        },
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onRingTap != null) {
            // liquidPress on the tappable Charge ring so it settles inward on press. Same interactionSource
            // on the clickable + press. No CircleShape clip — the GlowRing is already round, and the clip
            // cut the outer bloom off at the arc edge (visible as a hard ring boundary on the hero row).
            val ringInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .liquidPress(ringInteraction)
                    .clickable(
                        interactionSource = ringInteraction,
                        indication = null,
                        onClickLabel = ringTapLabel
                            ?: uiString(R.string.today_action_see_what_shaped, domainLabel),
                        onClick = onRingTap,
                    ),
            ) { ring() }
        } else {
            ring()
        }
        // #937 wanted the WORD centred on the ring's axis, not the word-plus-chevron block, and got there
        // by balancing the row with an invisible LEADING twin of the chevron. That worked, but it spent a
        // second 14.dp slot plus its gap on nothing — and inside Rest's ring-width box that left the label
        // barely 50.dp on a compact screen, so "REST" ellipsised to "R…" at a larger font scale (#1502).
        //
        // Centring in a Box gets the same axis for free: the column is symmetric, so a centred word sits on
        // the ring's centre by construction, and the chevron floats at the trailing edge instead of paying
        // for a counterweight. The word reserves 16.dp each side so it can never run under the chevron
        // (which needs 14.dp), keeping the layout honest at any label length, and the whole box is the tap
        // target. RTL still mirrors: Box alignments and the AutoMirrored icon both flip.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .clickable { onInfo() }
                .padding(vertical = Metrics.space2),
            contentAlignment = Alignment.Center,
        ) {
            // Keep the word centred on the ring independently of the trailing chevron. Giving the label
            // the complete fixed score-column width prevents longer translations (for example ERHOLUNG)
            // from pushing the third column right or being clipped at the screen edge.
            AutoSizeValue(
                text = domainLabel.uppercase(),
                style = NoopType.overline,
                color = Palette.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    // The 16.dp each side the note above specifies (#2421). It had drifted to 2.dp leading
                    // against 18.dp trailing, reserving the chevron on one side only, so `TextAlign.Center`
                    // centred the word in a box 16.dp narrower on the right and put it 8.dp LEFT of the ring
                    // above it, on every hero label. A reporter saw that before anyone reading this file did.
                    //
                    // Symmetric at 16 rather than 18 because the width matters: it is the margin
                    // `AutoSizeValue` shrinks into before it ellipsises, and #1502 was a German "ERHOLUNG"
                    // cut to "R…". 16 clears the 14.dp chevron by 2.
                    .padding(horizontal = Metrics.space16),
                minScale = 0.7f,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = uiString(R.string.l10n_today_screen_how_domain_label_is_calculated_8897768c, domainLabel),
                tint = Palette.textSecondary.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(Metrics.space14),
            )
        }
        if (caption != null) {
            // A plain wrapping Text, NOT AutoSizeValue: that one is maxLines = 1 with softWrap off and an
            // ellipsis, so a longer translation would shrink to its floor and then cut itself off
            // mid-word. Truncating its own explanation is precisely what the old in-ring overlay did and
            // what moving the caption out here was meant to stop, so it must not come back through the
            // component. Two lines at the column's width holds every locale we ship.
            Text(
                text = caption,
                style = NoopType.footnote,
                color = Palette.textTertiary,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.space2),
            )
        }
    }
}

/**
 * One hero score, drawn as whichever gauge the Today preference selects.
 *
 * DEFAULT, and what #2311 shipped: a [GlowRing] — the ring-style gauge shared with the Intelligence
 * forecast card, the same crisp-arc + count-up primitive the rest of the app already uses: a
 * 10%-of-diameter stroke, spring arc from 12 o'clock, and a tween count-up number at `diameter * 0.36`
 * Bold (the [glowRingCenterTextStyle] house numeral).
 *
 * OFF: the [LiquidVessel] the ring replaced, filling to [fraction] in the domain [tint] with a
 * [CountUpText] rolling the number up over it (white, tabular, a soft shadow so it reads on the vessel),
 * matching the iOS `HeroScoreCell`. The number is hit-transparent (clearAndSetSemantics, no clickable)
 * so a tap falls THROUGH to the vessel, which owns its own tap to splash + haptic.
 *
 * Both renderings take the SAME bindings — same fraction, same value, same value-sampled tint — so
 * whichever one is on agrees with every other surface that shows the metric. The caller overlays the
 * calibrating / No-Data / Needs-a-Tracked-Night text when [showsValue] is false, for either gauge.
 *
 * The preference is read on entry via [remember], the convention every other Today-screen display toggle
 * follows (day-cycle background, sky behind cards, hydration). Settings is its own NavHost destination, so
 * Today leaves composition while it is open and reads the pref again on the way back: a flip is live the
 * next time Today is opened, which is the first moment it could be seen anyway.
 */
@Composable
private fun HeroScoreVessel(
    fraction: Double,
    value: Double,
    tint: Color,
    diameter: Dp,
    modifier: Modifier = Modifier,
    showsValue: Boolean = true,
    format: (Double) -> String = { it.roundToInt().toString() },
    targetRange: ClosedFloatingPointRange<Float>? = null,
    // Both of these are VESSEL-only; the ring branch ignores them.
    //
    // Whether the vessel sloshes live. The caller passes its empty-hero cost gate, so a brand-new user's
    // launch churn isn't fighting live canvases. GlowRing needs no equivalent.
    animated: Boolean = true,
    // Its animated render owns a clickable that consumes the touch, so a
    // clickable parent never sees a tap landing on it (#1995). The ring attaches none, which is why
    // #2311 could move the handler up to HeroRingColumn.onRingTap and drop this. With the vessel
    // selectable again the forwarding has to come back, for that rendering only.
    onTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val ringGauges = remember { NoopPrefs.todayRingGauges(context) }
    if (ringGauges) {
        GlowRing(
            fraction = fraction.coerceIn(0.0, 1.0).toFloat(),
            value = value,
            color = tint,
            diameter = diameter,
            lineWidth = diameter * 0.10f,
            modifier = modifier,
            showsLabel = showsValue,
            format = format,
            targetRange = targetRange,
        )
    } else Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        LiquidVessel(
            value = fraction.coerceIn(0.0, 1.0),
            tint = tint,
            animated = animated,
            modifier = Modifier.size(diameter),
            onTap = onTap,
        )
        if (showsValue) {
            // Count-up number over the vessel — white, tabular, a soft shadow for legibility, hit-transparent
            // so the tap reaches the vessel (splash). Size ≈ diameter × 0.27 (iOS 96→26 ratio), capped.
            val numberSp = (diameter.value * 0.27f).coerceIn(20f, 30f)
            CountUpText(
                value = value,
                format = format,
                // #2346: the weight follows the Appearance preference. BOLD is the shipped look and the
                // default, so nothing moves unless someone asks; SOFT falls back to `NoopType.number`'s
                // own default, the weight every other number in the app already uses. The SIZE is not a
                // preference: it is pinned to the iOS 96-to-26 ratio a few lines up, and moving one
                // platform alone would break that.
                style = NoopType.number(
                    numberSp,
                    weight = if (AppearancePrefs.gaugeNumerals == GaugeNumeralStyle.SOFT) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Bold
                    },
                )
                    .copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 1f), blurRadius = 6f)),
                color = Color.White,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * The plain-English Synthesis card at the top of the Metrics tab (Zoop "Calm"): the overline and the one-word
 * readiness pill, the Charge-level word in the brand mint, the one-line read, and chips for what drove it
 * (HRV, resting heart rate and last night's sleep against their 30-day baselines). Always open: the read is
 * short enough that folding it only hid it.
 */
@Composable
internal fun SynthesisHeroCard(
    day: DailyMetric?,
    recoveryCalibration: Int?,
    carriedDay: DailyMetric? = null,
    // The day history for the one-word readiness read, the driver chips, and the tap that opens the Charge
    // breakdown (where the full Readiness card lives). Defaults keep previews compiling.
    days: List<DailyMetric> = emptyList(),
    drivers: List<MetricDriver> = emptyList(),
    onOpenReadiness: () -> Unit = {},
) {
    // The row the synthesis reads from: today's own when it carries recovery, else the carried-over last
    // scored day (#543) so the card mirrors the carried Charge instead of blanking to "No Data". When
    // carrying, the detail line gets a "Last night · <date>" provenance so the prior read isn't passed off
    // as today's. Today's own read wins the instant tonight is scored.
    val readDay = carriedDay ?: day
    val recovery = readDay?.recovery
    // S4 (#205): the one-word readiness read (Push / Maintain / Rest), hidden without enough history. #1405:
    // the Synthesis words below are Charge-level and never share a word with it.
    val readinessLevel = remember(days) {
        if (days.isEmpty()) ReadinessEngine.Level.INSUFFICIENT
        else ReadinessEngine.evaluate(days, today = logicalDayKeyNow()).level
    }
    val status = if (recoveryCalibration != null) uiString(R.string.score_state_title_calibrating)
    else synthesisWord(recovery)
    val detail = if (recoveryCalibration != null) {
        // #612: if the baseline aged out silently — connected, but no new night for > staleDays — say WHY
        // it's calibrating instead of only "learning your baseline". `stale` is always > staleDays (14).
        val stale = Baselines.nightsSinceNewestValidNight(days.map { it.day }, days.map { it.avgHrv }, logicalDayKeyNow())
        if (stale != null && stale > Baselines.staleDays) {
            uiString(R.string.l10n_today_screen_no_new_nights_from_your_strap_for_stale_days_8863bcfe, stale)
        } else {
            // Comma (not the old em-dash) to match the Swift canonical synthesis copy VERBATIM
            // (TodayView "Learning your baseline, N of M nights."). #612 above covers a TOTAL drought; the
            // common shape is nights arriving with most of them empty, so name the missing nights to give
            // a wearer something to act on instead of a counter that looks stuck. Swift twin:
            // TodayView.calibrationDetail.
            val cov = Baselines.recentHrvCoverage(
                days.map { it.day }, days.map { it.avgHrv }, logicalDayKeyNow(),
            )
            val base = uiString(
                R.string.today_synthesis_learning_baseline, recoveryCalibration, Baselines.minNightsSeed,
            )
            if (cov.missing > 0 && cov.observed > 0) {
                base + " " + uiString(R.string.today_synthesis_nights_without_hrv, cov.missing, cov.observed)
            } else {
                base
            }
        }
    } else if (carriedDay != null) {
        // Carried prior-day read, summarise that day + stamp it so it isn't passed off as today's.
        uiString(
            R.string.today_synthesis_carried_detail,
            synthesisDetail(carriedDay), carriedCaption(carriedDay.day).localized(),
        )
    } else {
        synthesisDetail(day)
    }
    // The word takes the brand mint only when it is a real read; calibrating and no-data stay neutral.
    val statusColor = if (recoveryCalibration == null && recovery != null) Palette.accent else Palette.textPrimary
    NoopCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                Text(
                    uiString(R.string.today_section_synthesis),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                readinessWord(readinessLevel)?.let { wordRes ->
                    ReadinessHeroPill(word = uiString(wordRes), level = readinessLevel, onTap = onOpenReadiness)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                Text(status, style = NoopType.title1, color = statusColor)
                Text(detail, style = NoopType.body, color = Palette.textSecondary)
            }
            if (drivers.isNotEmpty()) MetricDriverChips(drivers)
        }
    }
}

/**
 * S4 (#205): the one-word readiness pill on the hero (Push / Maintain / Rest). A small tinted capsule
 * matching the score-pill chrome, coloured by the readiness level; tapping opens the Charge breakdown sheet
 * where the full Readiness card lives. Mirrors the iOS readinessHeroPill.
 */
@Composable
private fun ReadinessHeroPill(word: String, level: ReadinessEngine.Level, onTap: () -> Unit) {
    val tone = readinessColor(level)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.12f))
            .border(1.dp, tone.copy(alpha = 0.32f), RoundedCornerShape(50))
            .clickable(onClickLabel = uiString(R.string.today_action_full_readiness), onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = uiString(R.string.l10n_today_screen_readiness_word_749b3330, word) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(word.uppercase(), style = NoopType.overline, color = tone)
    }
}

/** Honest overlay shown over the Charge ring when today's recovery is null: either the calibrating count
 *  or No data. The carried last-scored Charge case is NOT handled here anymore: it's intercepted earlier
 *  and drawn as a dimmed FILLED ring in the carried branch (matching iOS chargeRing), so this overlay only
 *  covers the calibrating and no-data cases. Mirrors iOS TodayView.ringEmptyOverlay. */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun RingEmptyOverlay(
    calibratingNights: Int?,
    diameter: Dp,
) {
    if (calibratingNights != null) {
        // AutoSizeValue shrink-to-fit + the centring Column, same as RingNeedsTrackedNight (#1168): a longer
        // localized "N of M nights" would otherwise fill the narrow ring and left-align/clip like the Rest tile.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AutoSizeValue(
                uiString(R.string.l10n_today_screen_calibrating_37c2c9bd),
                style = NoopType.headline,
                color = Palette.textTertiary,
                minScale = 0.7f,
            )
            AutoSizeValue(
                uiString(R.string.l10n_today_screen_calibratingnights_of_baselines_minnightsseed_3b76e55c, calibratingNights, Baselines.minNightsSeed),
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )
        }
    } else {
        RingNoData(diameter = diameter)
    }
}

@Composable
private fun RingNoData(diameter: Dp) {
    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Text(
            text = uiString(R.string.today_no_data),
            style = NoopType.headline,
            color = Palette.textTertiary,
            modifier = Modifier.width(diameter * 0.78f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
    }
}

/** #898: the Rest ring's overlay when a Charge exists for the day but there's no scored sleep (the
 *  aggregate-import case , a daily HRV/RHR import carries no in-bed session). Says WHY Rest is blank
 *  instead of a bare "No Data", without fabricating a number. Mirrors iOS restRing's needs-a-night branch. */
@Composable
private fun RingNeedsTrackedNight() {
    // AutoSizeValue (not plain Text): the subtext "needs a tracked night" is wider than the narrow ring,
    // so a maxLines=1 Text filled the width and left-aligned + clipped the tail (#1168). Shrink-to-fit and
    // let the centering Column place the content-sized line — the Android mirror of iOS ringNeedsTrackedNight's
    // .minimumScaleFactor + centred VStack.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AutoSizeValue(
            uiString(R.string.l10n_today_screen_calibrating_37c2c9bd),
            style = NoopType.headline,
            color = Palette.textTertiary,
            minScale = 0.7f,
        )
        AutoSizeValue(
            uiString(R.string.l10n_today_screen_needs_a_tracked_night_ccfd532a),
            style = NoopType.footnote,
            color = Palette.textSecondary,
        )
    }
}

/** #1164: the Rest ring's overlay when today's score is provisional because the strap still has banked
 *  records not yet offloaded. Shows "Pending sync" instead of a number that will change once the full
 *  night lands and `analyzeRecent` re-scores it. Mirrors iOS ringPendingSync. */
// MARK: - Hero vitals metric rows, HRV / Resting HR / Respiratory, re-homed below the ring hero
//
// The WHOOP-style redesign (#23) dropped the big gold RecoveryRing hero that used to carry these; the
// three vitals now read directly below the three-ring hero + Synthesis card. [HeroMetricRows] is the
// README "Metric row" card; the SOLID/CALIBRATING pill + Synthesis insight moved into [SynthesisHeroCard].

/**
 * Whether the Recovery Vitals card should explain its blanks with the HR-only note (#1801).
 *
 * Reads the flag from the SAME row the shown vitals came from. [carriedFromVitals] already decides that
 * for the date caption, so reusing it keeps the note and the numbers describing ONE night;
 * `day?.sleepHrOnly ?: vitalsDay?.sleepHrOnly` would instead fall through whenever today's flag is merely
 * unknown — every row scored before v36 — and caption today's blanks with a different night's staging.
 *
 * Gated on something actually being blank so a night that kept its vitals stays quiet, and on `== true`
 * so the tri-state reads correctly: null means "not known", which earns no claim either way.
 */
internal fun showsHrOnlyNote(
    day: DailyMetric?,
    vitalsDay: DailyMetric?,
    carriedFromVitals: Boolean,
    hrv: Double?,
    rhr: Int?,
): Boolean {
    val source = if (carriedFromVitals) vitalsDay else day
    return source?.sleepHrOnly == true && (hrv == null || rhr == null)
}

/** The three hero vitals as README metric rows, HRV (teal) · Resting HR (rose) · Respiratory (blue).
 *  Reads PER-FIELD today-first with a recovery-INDEPENDENT vitals carry ([vitalsDay]) as the fallback
 *  (#543 follow-up), so a night whose recovery was nulled post-update still shows its OWN preserved HRV /
 *  RHR / respiratory rather than an older recovery-scored day's numbers (or "No Data"). This aligns the
 *  card to the Key-Metrics tiles, which already read per-field. Each row still falls through to "No Data"
 *  for a vital neither today nor the carry supplies. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun HeroMetricRows(
    day: DailyMetric?,
    carriedDay: DailyMetric? = null,
    vitalsDay: DailyMetric? = null,
    // #706/#684: the same `vital_detail/<key>` trends the HRV / Resting HR / Respiratory dashboard cards
    // open. These three rows show the SAME metrics and had no way through, so the summary card was the one
    // place on Today where a metric was a dead end. Keys come from `dashboardCardMetricKey`, so the two
    // surfaces cannot drift onto different destinations for the same vital.
    onOpenMetric: (String) -> Unit = {},
) {
    // Per-field, today-first: today's own value wins; the vitals carry only fills a field today lacks.
    val hrv = day?.avgHrv ?: vitalsDay?.avgHrv
    val rhr = day?.restingHr ?: vitalsDay?.restingHr
    val resp = day?.respRateBpm ?: vitalsDay?.respRateBpm
    // The caption reflects the row the shown vitals actually came from: if today supplied ANY of them the
    // values are today's own, so don't stamp them as a prior "Last night · <date>"; only when EVERY shown
    // vital is carried do we stamp the carry's date (relabelled "Latest sleep · <date>" when weeks-old).
    val carriedFromVitals = day?.avgHrv == null && day?.restingHr == null && day?.respRateBpm == null &&
        (hrv != null || rhr != null || resp != null) && vitalsDay != null
    // #1801: why the two blanks below are blank. Read from the SAME row the shown vitals came from —
    // `carriedFromVitals` already decides that for the date caption, so reusing it keeps the note and the
    // values describing one night. `day?.sleepHrOnly ?: vitalsDay?.sleepHrOnly` would instead fall through
    // on a pre-v36 row whose flag is merely unknown, and explain a different night's numbers.
    // Gated on something actually being blank, so a night that recovered its vitals stays quiet.
    val hrOnlyNight = showsHrOnlyNote(day, vitalsDay, carriedFromVitals, hrv, rhr)
    // iOS `recoveryVitalsSection`: a frosted card with a "RECOVERY VITALS" header + a "last night · <date>"
    // on the right, then three `vitalRow`s (26dp mini LIQUID RING + label + value). NoopCard supplies the
    // same neutral surfaceRaised + hairline as iOS's frosted card. Inner spacing 12, matching iOS.
    NoopCard(padding = Metrics.space16) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline(uiString(R.string.today_section_recovery_vitals), modifier = Modifier.weight(1f))
                // iOS `lastNightLine` — today's own "Last night · <date>" unless the shown vitals are a carry.
                Text(
                    if (carriedFromVitals) carriedCaption(vitalsDay!!.day).localized() else heroVitalsLastNightLine(),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }
            HeroVitalRow(
                label = uiString(R.string.l10n_today_screen_heart_rate_variability_a137586d),
                value = hrv?.let { "${it.roundToInt()} ms" } ?: NO_DATA,
                tint = Palette.metricCyan,
                fraction = hrv?.let { (it / 120.0).coerceIn(0.0, 1.0) },
                metricKey = dashboardCardMetricKey(DashboardCard.HRV),
                onOpenMetric = onOpenMetric,
            )
            HeroVitalRow(
                label = uiString(R.string.l10n_today_screen_resting_heart_rate_348928d6),
                value = rhr?.let { "$it bpm" } ?: NO_DATA,
                tint = Palette.metricRose,
                fraction = rhr?.let { (it / 100.0).coerceIn(0.0, 1.0) },
                metricKey = dashboardCardMetricKey(DashboardCard.RESTING_HR),
                onOpenMetric = onOpenMetric,
            )
            HeroVitalRow(
                label = uiString(R.string.l10n_today_screen_breaths_per_minute_2b197c54),
                value = resp?.let { String.format(Locale.getDefault(), "%.1f rpm", it) } ?: NO_DATA,
                tint = Palette.accent,
                fraction = resp?.let { (it / 24.0).coerceIn(0.0, 1.0) },
                metricKey = dashboardCardMetricKey(DashboardCard.RESPIRATORY),
                onOpenMetric = onOpenMetric,
            )
            if (hrOnlyNight) {
                Text(
                    uiString(R.string.l10n_today_screen_this_night_was_staged_from_heart_rate_4d0f1f9b),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** iOS `lastNightLine` — "Last night · <date>" where <date> is yesterday in "d MMM" form. */
@Composable
private fun heroVitalsLastNightLine(): String {
    val d = LocalDate.now().minusDays(1)
    return uiString(R.string.today_last_night_date, d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())))
}

/** One iOS `vitalRow`: a 26dp mini liquid RING filled to [fraction] in [tint], the label (subhead,
 *  secondary), a spacer, and the value (number 15, primary). Replaces the old flat-Material-icon row. */
@Composable
private fun HeroVitalRow(
    label: String,
    value: String,
    tint: Color,
    fraction: Double?,
    // The metric-detail key this row opens, from `dashboardCardMetricKey` so the row and its dashboard-card
    // twin cannot drift onto different trends for the same vital. NULL means the row simply does not
    // navigate: it loses the tap AND the chevron together, which is the honest degradation. An earlier
    // draft asserted the key was present, which would have turned a missing destination into a crash on
    // tap - a worse outcome than a row that quietly does not move.
    metricKey: String? = null,
    onOpenMetric: (String) -> Unit = {},
) {
    val onClick: (() -> Unit)? = metricKey?.let { key -> { onOpenMetric(key) } }
    val hasValue = value != NO_DATA
    val displayValue = localizedMetricValue(value)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .semantics { contentDescription = uiString(R.string.l10n_today_screen_label_value_b781d590, label, displayValue) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        // Ring or vessel per the Today gauge preference; both are static here.
        val gaugeCtx = LocalContext.current
        if (remember { NoopPrefs.todayRingGauges(gaugeCtx) }) {
            GlowRing(
                fraction = (fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
                value = 0.0,
                color = tint,
                diameter = 26.dp,
                lineWidth = 2.6.dp,
                showsLabel = false,
            )
        } else {
            LiquidVessel(
                value = fraction,
                tint = tint,
                animated = false,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(label, style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        Text(
            displayValue,
            style = NoopType.number(15f),
            color = if (hasValue) Palette.textPrimary else Palette.textTertiary,
        )
        // Only when the row actually goes somewhere. `dashboardCardDestination` states the rule this
        // follows - every card resolves to a destination, so the chevron is always honest (#706/#684) -
        // and the inverse is what was wrong here: three rows that go somewhere and did not say so.
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
    }
}
