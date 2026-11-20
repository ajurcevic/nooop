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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.analytics.ReadinessEngine
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.data.WorkoutRow
import java.util.Locale
import kotlin.math.roundToInt

// Today tab: footer sections (workouts, sources), readiness, spark tiles and the illness banner.
// Split out of TodayScreen.kt; behaviour unchanged.

// MARK: - Today footer sections

@Composable
internal fun TodayWorkoutsSection(workouts: List<WorkoutRow>, onSelect: (WorkoutRow) -> Unit) {
    // Single column, newest first: the 2x2 grid truncated durations on narrow phones and read as
    // unrelated stat tiles rather than a chronological feed. Full-width tiles have room for the
    // kcal chip, so the #332 compactDelta workaround is no longer needed here.
    val feed = lastWorkoutsFeed(workouts)
    if (feed.isEmpty()) return

    // "Latest Workouts", not "Last": "Last" read as "final". Mirrored on iOS (TodayView). Lives in
    // strings.xml so the header is a resource like the nav labels.
    SectionHeader(stringResource(R.string.today_latest_workouts), overline = uiString(R.string.today_workouts_activity), trailing = uiString(R.string.today_workouts_14_days))
    // #1694: the feed was read-only, so the only way to a session's detail was More > Workouts. The
    // tap opens the SAME read-only WorkoutDetailSheet the Workouts list uses — nothing there can edit
    // or delete, so a tap from Today carries no risk that list does not already carry.
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        feed.forEach { workout ->
            val interaction = remember(workout.startTs, workout.source) { MutableInteractionSource() }
            StatTile(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidPress(interaction)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = uiString(R.string.today_action_show_workout),
                        onClick = { onSelect(workout) },
                    ),
                label = WorkoutEditing.displaySport(workout.sport),
                value = localizedMetricValue(workoutDuration(workout)),
                caption = workoutCaption(workout),
                accent = workout.strain?.let { Palette.effortTint(it / StrainScorer.maxStrain) } ?: Palette.textPrimary,
                delta = workout.energyKcal?.let { "${it.roundToInt()} kcal" },
                deltaColor = Palette.metricAmber,
            )
        }
    }
}

@Composable
internal fun TodaySourcesSection(
    footer: TodayFooterState,
    strapBatteryPct: Int? = null,
    strapBatteryEstimate: String? = null,
    // S5: collapse to a single "Synced from: ..." summary line by default; tapping expands the full
    // per-source rows + strap battery inline. Nothing is removed, only folded behind a tap.
    expanded: Boolean = true,
    onToggle: () -> Unit = {},
) {
    SectionHeader(uiString(R.string.today_data_sources), overline = uiString(R.string.today_provenance))
    Spacer(Modifier.height(Metrics.gap))
    val whoopPresent = (footer.whoopDays ?: 0) > 0 || strapBatteryPct != null
    val applePresent = (footer.appleDays ?: 0) > 0 || (footer.appleWorkouts ?: 0) > 0
    val hcPresent = (footer.hcDays ?: 0) > 0 || (footer.hcWorkouts ?: 0) > 0
    if (!expanded) {
        // Collapsed: one tappable "Synced from: ..." line. Each source is named for what it is —
        // Health Connect must NOT fold under "Apple Watch" (issue #176: Health-Connect-only users
        // saw "Synced from: Apple Watch"); the expanded card lists every source by name too.
        val collapsedInteraction = remember { MutableInteractionSource() }
        NoopCard(
            modifier = Modifier
                .fillMaxWidth()
                .liquidPress(collapsedInteraction)
                .clickable(
                    interactionSource = collapsedInteraction,
                    indication = null,
                    onClickLabel = uiString(R.string.today_action_show_sources),
                    onClick = onToggle,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    syncedFromSummary(hasWhoop = whoopPresent, hasApple = applePresent, hasHealthConnect = hcPresent, hasXiaomi = false)
                        .let { names ->
                            if (names.isEmpty()) uiString(R.string.today_no_sources_yet)
                            else uiString(R.string.today_synced_from, names.localizedSourceList())
                        },
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Palette.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        return
    }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A header row to collapse it back, an obvious "less" cue on the expanded card.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = uiString(R.string.today_action_hide_sources), onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(uiString(R.string.l10n_today_screen_synced_from_2aa7258b), style = NoopType.overline, color = Palette.textTertiary, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Palette.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
            SourceRow(
                badge = uiString(R.string.today_source_whoop),
                tint = Palette.accent,
                // A live battery reading means the strap IS connected, even before the first banked
                // night, don't contradict it with "Not connected" (#159).
                present = whoopPresent,
                detail = countDetail(footer.whoopDays, footer.whoopWorkouts),
                batteryPct = strapBatteryPct,
                batteryEstimate = strapBatteryEstimate,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Palette.hairline),
            )
            SourceRow(
                badge = uiString(R.string.today_source_apple_health),
                tint = Palette.metricCyan,
                present = applePresent,
                detail = countDetail(footer.appleDays, footer.appleWorkouts),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Palette.hairline),
            )
            SourceRow(
                badge = uiString(R.string.today_source_health_connect),
                tint = Palette.metricPurple,
                present = hcPresent,
                detail = countDetail(footer.hcDays, footer.hcWorkouts),
            )
        }
    }
}

@Composable
internal fun SourceRow(
    badge: String,
    tint: Color,
    present: Boolean,
    detail: String,
    batteryPct: Int? = null,
    batteryEstimate: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SourceBadge(badge, tint = if (present) tint else Palette.textTertiary)
        // Compact strap-battery readout beside the source badge, same pill + tone bands as the
        // Settings Strap section; absent entirely when there's no live reading (#159).
        batteryPct?.let { pct ->
            Spacer(Modifier.width(8.dp))
            StatePill(title = uiString(R.string.l10n_today_screen_pct_ee63e247, pct), tone = batteryPillTone(pct), showsDot = false)
            // The "~X left" runtime estimate sits beside the %, dimmer, only when we have a trusted one (#713).
            batteryEstimate?.let { est ->
                Spacer(Modifier.width(6.dp))
                Text(
                    text = est,
                    style = NoopType.captionNumber,
                    color = Palette.textTertiary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (present) detail else uiString(R.string.today_not_connected),
            style = NoopType.captionNumber,
            color = if (present) Palette.textSecondary else Palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Readiness card (ported from TodayView.swift readinessSection)
//
// On-device training-readiness synthesis. Calls the analytics ReadinessEngine over the
// view model's day history and renders the macOS card: a colored level dot + headline,
// an optional acute:chronic "load X.XX" read-out, the plain-English summary, then one
// row per driving signal (a small flag-colored dot + label + detail). The whole card is
// suppressed until there is enough history (level == INSUFFICIENT), matching macOS.

@Composable
private fun readinessEvidenceText(evidence: ReadinessEngine.Evidence): String {
    fun number(value: Double, decimals: Int): String = if (decimals == 0) {
        Math.round(value).toString()
    } else {
        String.format(Locale.getDefault(), "%.${decimals}f", value)
    }
    return when (evidence) {
        is ReadinessEngine.Evidence.MetricVsBaseline -> {
            val unit = when (evidence.unit) {
                ReadinessEngine.MetricUnit.MS -> uiString(R.string.today_unit_ms)
                ReadinessEngine.MetricUnit.BPM -> uiString(R.string.today_unit_bpm)
                ReadinessEngine.MetricUnit.RPM -> uiString(R.string.today_unit_rpm)
            }
            uiString(
                R.string.today_readiness_evidence_metric,
                number(evidence.value, evidence.decimals), number(evidence.baseline, evidence.decimals), unit,
            )
        }
        is ReadinessEngine.Evidence.Monotony ->
            uiString(R.string.today_readiness_evidence_monotony, number(evidence.value, 1))
        is ReadinessEngine.Evidence.TrainingLoad ->
            uiString(
                R.string.today_readiness_evidence_load,
                number(evidence.acute, 1), number(evidence.chronic, 1),
            )
    }
}

@Composable
private fun readinessCopy(copy: ReadinessEngine.Copy): String = uiString(
    when (copy) {
        ReadinessEngine.Copy.TODAY_READINESS_BALANCED -> R.string.today_readiness_balanced
        ReadinessEngine.Copy.TODAY_READINESS_BALANCED_SUMMARY -> R.string.today_readiness_balanced_summary
        ReadinessEngine.Copy.TODAY_READINESS_HRV_BAD -> R.string.today_readiness_hrv_bad
        ReadinessEngine.Copy.TODAY_READINESS_HRV_GOOD -> R.string.today_readiness_hrv_good
        ReadinessEngine.Copy.TODAY_READINESS_HRV_WATCH -> R.string.today_readiness_hrv_watch
        ReadinessEngine.Copy.TODAY_READINESS_LOAD_BUILDING_FAST -> R.string.today_readiness_load_building_fast
        ReadinessEngine.Copy.TODAY_READINESS_LOAD_RAMPING_DOWN -> R.string.today_readiness_load_ramping_down
        ReadinessEngine.Copy.TODAY_READINESS_LOAD_SPIKING -> R.string.today_readiness_load_spiking
        ReadinessEngine.Copy.TODAY_READINESS_LOAD_SWEET_SPOT -> R.string.today_readiness_load_sweet_spot
        ReadinessEngine.Copy.TODAY_READINESS_MONOTONY_WATCH -> R.string.today_readiness_monotony_watch
        ReadinessEngine.Copy.TODAY_READINESS_MORE_NIGHTS -> R.string.today_readiness_more_nights
        ReadinessEngine.Copy.TODAY_READINESS_NORMAL_RANGE -> R.string.today_readiness_normal_range
        ReadinessEngine.Copy.TODAY_READINESS_PRIMED -> R.string.today_readiness_primed
        ReadinessEngine.Copy.TODAY_READINESS_PRIMED_SUMMARY -> R.string.today_readiness_primed_summary
        ReadinessEngine.Copy.TODAY_READINESS_RESP_BAD -> R.string.today_readiness_resp_bad
        ReadinessEngine.Copy.TODAY_READINESS_RESP_WATCH -> R.string.today_readiness_resp_watch
        ReadinessEngine.Copy.TODAY_READINESS_RHR_BAD -> R.string.today_readiness_rhr_bad
        ReadinessEngine.Copy.TODAY_READINESS_RHR_GOOD -> R.string.today_readiness_rhr_good
        ReadinessEngine.Copy.TODAY_READINESS_RHR_WATCH -> R.string.today_readiness_rhr_watch
        ReadinessEngine.Copy.TODAY_READINESS_RUN_DOWN -> R.string.today_readiness_run_down
        ReadinessEngine.Copy.TODAY_READINESS_RUN_DOWN_SUMMARY -> R.string.today_readiness_run_down_summary
        ReadinessEngine.Copy.TODAY_READINESS_SIGNAL_HRV -> R.string.today_readiness_signal_hrv
        ReadinessEngine.Copy.TODAY_READINESS_SIGNAL_RESPIRATORY -> R.string.today_readiness_signal_respiratory
        ReadinessEngine.Copy.TODAY_READINESS_SIGNAL_RESTING_HR -> R.string.today_readiness_signal_resting_hr
        ReadinessEngine.Copy.TODAY_READINESS_SIGNAL_TRAINING_LOAD -> R.string.today_readiness_signal_training_load
        ReadinessEngine.Copy.TODAY_READINESS_SIGNAL_TRAINING_VARIETY -> R.string.today_readiness_signal_training_variety
        ReadinessEngine.Copy.TODAY_READINESS_STRAINED -> R.string.today_readiness_strained
        ReadinessEngine.Copy.TODAY_READINESS_STRAINED_SUMMARY -> R.string.today_readiness_strained_summary
        ReadinessEngine.Copy.TODAY_READINESS_TITLE -> R.string.today_readiness_title
        ReadinessEngine.Copy.TODAY_READINESS_WEAR_FOR_NIGHTS -> R.string.today_readiness_wear_for_nights
    },
)

@Composable
private fun readinessSignalDetail(signal: ReadinessEngine.Signal): String {
    val load = signal.evidence as? ReadinessEngine.Evidence.TrainingLoad
    return if (load != null && load.chronic > 0.0) {
        uiString(
            when (signal.detailRes) {
                ReadinessEngine.Copy.TODAY_READINESS_LOAD_RAMPING_DOWN -> R.string.today_readiness_load_ramping_down
                ReadinessEngine.Copy.TODAY_READINESS_LOAD_SWEET_SPOT -> R.string.today_readiness_load_sweet_spot
                ReadinessEngine.Copy.TODAY_READINESS_LOAD_BUILDING_FAST -> R.string.today_readiness_load_building_fast
                ReadinessEngine.Copy.TODAY_READINESS_LOAD_SPIKING -> R.string.today_readiness_load_spiking
                else -> error("Training-load evidence requires a training-load detail")
            },
            String.format(Locale.getDefault(), "%.2f", load.acute / load.chronic),
        )
    } else {
        readinessCopy(signal.detailRes)
    }
}

@Composable
internal fun ReadinessSection(days: List<DailyMetric>, carriedDay: DailyMetric? = null) {
    // Logical day (rolls at 04:00 local), so readiness keeps reading the evening's row in the small
    // hours instead of an empty new-calendar-day row (#144). Mirrors the Today-row resolution.
    //
    // Carry-over (#543): Readiness anchors on the day whose row carries today's vitals. Right after the
    // rollover today has no scored row, so `evaluate` would read INSUFFICIENT and the whole card would
    // VANISH while live HR ticks, the same blank the carried Charge/Synthesis avoid. So when carrying,
    // anchor on the last scored day's key instead, and stamp the overline "Last night · <date>". Honest:
    // it's the real prior read; today's own readiness wins the instant tonight is scored.
    val anchorKey = carriedDay?.day ?: logicalDayKeyNow()
    val readiness = remember(days, anchorKey) { ReadinessEngine.evaluate(days, today = anchorKey) }
    if (readiness.level == ReadinessEngine.Level.INSUFFICIENT) return

    val overline = carriedDay?.let { carriedCaption(it.day).localized() }
        ?: uiString(R.string.today_should_you_push)
    SectionHeader(uiString(R.string.today_readiness_title), overline = overline)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Headline row: level dot + headline, then the ACWR load read-out.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(readinessColor(readiness.level)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    readinessCopy(readiness.headlineRes),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                readiness.acwr?.let { acwr ->
                    Text(
                        uiString(R.string.today_load_ratio, String.format(Locale.getDefault(), "%.2f", acwr)),
                        style = NoopType.captionNumber,
                        color = Palette.textTertiary,
                    )
                }
            }

            // #1405: mark this card as a DIFFERENT axis from the home Synthesis word (which bands the
            // Charge %). Stated at the point the two get compared, so "Primed" here vs "Steady" there
            // doesn't read as one value contradicting itself. Raw string, matching this card's copy.
            Text(
                uiString(R.string.today_training_read_explanation),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )

            // Plain-English summary.
            Text(
                readinessCopy(readiness.summaryRes),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )

            // Per-signal rows: flag dot + fixed-width label + detail.
            if (readiness.signals.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Palette.hairline),
                )
                readiness.signals.forEach { signal ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(flagColor(signal.flag)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            readinessCopy(signal.labelRes),
                            style = NoopType.caption,
                            color = Palette.textSecondary,
                            modifier = Modifier.width(104.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                readinessSignalDetail(signal),
                                style = NoopType.caption,
                                color = Palette.textTertiary,
                            )
                            // The numbers behind the read (e.g. "48 vs 55 ms"), as a small mono caption,                             // mirrors the macOS readiness card and the "load X.XX" numeric readout above.
                            signal.evidence?.let { evidence ->
                                Text(
                                    readinessEvidenceText(evidence),
                                    style = NoopType.captionNumber,
                                    color = Palette.textTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Level → color, mirroring TodayView.readinessColor. */
internal fun readinessColor(level: ReadinessEngine.Level): Color = when (level) {
    ReadinessEngine.Level.PRIMED -> Palette.accent
    ReadinessEngine.Level.BALANCED -> Palette.statusPositive
    ReadinessEngine.Level.STRAINED -> Palette.statusWarning
    ReadinessEngine.Level.RUNDOWN -> Palette.metricRose
    ReadinessEngine.Level.INSUFFICIENT -> Palette.textTertiary
}

/** Flag → color, mirroring TodayView.flagColor. */
private fun flagColor(flag: ReadinessEngine.Flag): Color = when (flag) {
    ReadinessEngine.Flag.GOOD -> Palette.accent
    ReadinessEngine.Flag.NEUTRAL -> Palette.textTertiary
    ReadinessEngine.Flag.WATCH -> Palette.statusWarning
    ReadinessEngine.Flag.BAD -> Palette.metricRose
}

/**
 * #316 / @63, map a step-sample activity class (0=still, 1=walk, 2=run) to the still/walk/run icon + an
 * accessibility label. Mirrors the iOS Steps-tile glyph set (figure.stand / figure.walk / figure.run) and
 * semantics exactly (cross-platform parity). Returns (null, "") for any other code so an unmapped value
 * shows nothing rather than a wrong glyph.
 */
private fun stepActivityIconFor(activityClass: Int): Pair<ImageVector?, Int?> = when (activityClass) {
    0 -> Icons.Filled.Accessibility to R.string.today_steps_activity_still
    1 -> Icons.AutoMirrored.Filled.DirectionsWalk to R.string.today_steps_activity_walking
    2 -> Icons.AutoMirrored.Filled.DirectionsRun to R.string.today_steps_activity_running
    else -> null to null
}

// MARK: - SparkStatTile
//
// A fixed-height metric tile: overline label, big value + caption, and a 14-day
// Sparkline anchored along the bottom edge. Mirrors the macOS StatTile-with-sparkline
// while reusing the locked surfaces/typography (NoopCard, Overline, NoopType). Built
// here rather than mutating the shared StatTile so other screens keep the plain tile.

@Composable
private fun SparkStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color = Palette.textPrimary,
    spark: List<Double> = emptyList(),
    sparkColor: Color = Palette.accent,
    onInfo: (() -> Unit)? = null,
    badge: String? = null,
    // #316 / @63, an optional activity-class code (0=still, 1=walk, 2=run) rendered as a small still/walk/run
    // glyph in the label row, tinted with the tile accent. null = no icon. Used by the Steps tile.
    trailingIcon: Int? = null,
) {
    val displayValue = localizedMetricValue(value)
    NoopCard(modifier = modifier.height(Metrics.tileHeight), padding = Metrics.space14) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Label row carries the overline, an optional low-confidence [badge] (H9, e.g. "Estimated"
            // stages), an optional activity glyph ([trailingIcon], #316), and, for the three headline scores
            // only, a trailing ⓘ that opens the scoring guide at this score. Other tiles render as before.
            if (onInfo != null || badge != null || trailingIcon != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Overline(label)
                    if (badge != null) {
                        Spacer(Modifier.width(Metrics.space6))
                        // A tertiary-tinted pill, honest "this is estimated, not measured" signal, the same
                        // muted treatment as a provenance badge so it informs without alarming. (H9)
                        SourceBadge(badge, tint = Palette.textTertiary)
                    }
                    Spacer(Modifier.weight(1f))
                    // #316, still/walk/run glyph, before the optional ⓘ. Self-hides for an unknown code.
                    if (trailingIcon != null) {
                        val (vector, descRes) = stepActivityIconFor(trailingIcon)
                        if (vector != null && descRes != null) {
                            Icon(
                                vector,
                                contentDescription = uiString(descRes),
                                tint = accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (onInfo != null) ScoreInfoButton(section = null, onClick = onInfo, compact = true)
                }
            } else {
                Overline(label)
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Shrink-to-fit (down to 0.6×) so a value never ellipsizes to "100%"→"10…" /
                    // "15.5"→"15…" next to the inline sparkline, matching the Swift tile's
                    // minimumScaleFactor (#332). fillMaxWidth() is load-bearing: AutoSizeValue only
                    // shrinks when its Text is given a hard width to overflow against, without it the
                    // single-line Text takes its intrinsic width, `didOverflowWidth` never trips, and
                    // the value silently truncates at full size. The plain StatTile worked because it
                    // passes weight(1f) (a hard width); this column-child needs fillMaxWidth instead.
                    AutoSizeValue(
                        displayValue,
                        style = NoopType.tileValueLarge,
                        color = accent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (caption != null) {
                        Text(
                            caption,
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = Metrics.space2),
                        )
                    }
                }
                if (spark.size >= 2) {
                    // Sparkline forces fillMaxWidth + a fixed height internally, so we
                    // bound it in a sized Box to keep it a compact inline trend.
                    SparkTailBox(wide = true) {
                        Sparkline(values = spark, color = sparkColor)
                    }
                }
            }
        }
    }
}

// MARK: - Illness banner (ported from HealthAlertBanner.swift)

@Composable
internal fun IllnessBanner(message: String) {
    // Frosted Bevel warning card (amber tint), matches the Swift HealthAlertBanner.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .frostedCardSurface(tint = Palette.statusWarning, cornerRadius = Metrics.cardRadius)
            .padding(Metrics.space14),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.statusWarning.copy(alpha = StrandAlpha.warningFill)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.statusWarning)
        }
        Text(message, style = NoopType.subhead, color = Palette.textPrimary)
    }
}
