package com.noop.ui

import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import com.noop.R
import com.noop.analytics.ReadinessEngine
import com.noop.data.DailyMetric
import com.noop.data.WorkoutRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

// Today tab: shared derived text and formatting helpers.
// Split out of TodayScreen.kt; behaviour unchanged.

// MARK: - Derived text (ported from TodayView.swift)

@Composable
internal fun synthesisWord(score: Double?): String {
    if (score == null) return uiString(R.string.today_no_data)
    // #1405: these are CHARGE/recovery-level words, a different axis from the ReadinessEngine training
    // verdict (Run down / Strained / Balanced / Primed). They must NOT share a word, or the Synthesis
    // card ("Steady") and the Charge-breakdown Readiness card ("Primed") read as the same thing
    // contradicting itself. So the [70,88) band is "Strong" (which also matches this card's own "Charge is
    // strong" detail copy), leaving "Primed" exclusively to the readiness engine. Keep parity with Swift.
    return when {
        score < 25 -> uiString(R.string.today_synthesis_depleted)
        score < 50 -> uiString(R.string.today_synthesis_low)
        score < 70 -> uiString(R.string.today_synthesis_steady)
        score < 88 -> uiString(R.string.today_synthesis_strong)
        else -> uiString(R.string.today_synthesis_peak)
    }
}

@Composable
internal fun synthesisDetail(d: DailyMetric?): String {
    val rec = d?.recovery
        ?: return uiString(R.string.today_synthesis_no_metrics)
    val res = when {
        rec < 50 && d.totalSleepMin == null -> R.string.today_synthesis_charge_low
        rec < 50 && d.totalSleepMin!! / 60.0 >= 7 -> R.string.today_synthesis_charge_low_sleep_consistent
        rec < 50 -> R.string.today_synthesis_charge_low_sleep_short
        rec < 70 && d.totalSleepMin == null -> R.string.today_synthesis_charge_steady
        rec < 70 && d.totalSleepMin!! / 60.0 >= 7 -> R.string.today_synthesis_charge_steady_sleep_consistent
        rec < 70 -> R.string.today_synthesis_charge_steady_sleep_short
        d.totalSleepMin == null -> R.string.today_synthesis_charge_strong
        d.totalSleepMin / 60.0 >= 7 -> R.string.today_synthesis_charge_strong_sleep_consistent
        else -> R.string.today_synthesis_charge_strong_sleep_short
    }
    return uiString(res)
}

internal fun sleepValue(d: DailyMetric?): String {
    val m = d?.totalSleepMin ?: return NO_DATA
    val total = m.roundToInt()
    return "${total / 60}h ${total % 60}m"
}

internal const val NO_DATA = "noop:no-data"

/** The dashboard-card placeholder for a baseline-relative metric (Stress) that is still seeding its window,  *  an honest "building your baseline" state rather than a bare dash (#706/#684). Rendered dimmed like NO_DATA. */
internal const val STRESS_CALIBRATING = "noop:calibrating"

@Composable
internal fun localizedMetricValue(value: String): String = when (value) {
    NO_DATA -> uiString(R.string.today_no_data)
    STRESS_CALIBRATING -> uiString(R.string.today_calibrating)
    else -> value
}

private val workoutDateFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).withZone(ZoneId.systemDefault())
private val workoutTimeFmt: DateTimeFormatter =
    // Respect the device's 12-/24-hour locale (#337): "7:10 AM" where 12-hour is preferred, "19:10"
    // where 24-hour is, instead of forcing 24-hour on everyone.
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())

@Composable
internal fun countDetail(days: Int?, workouts: Int?): String {
    if (days == null || workouts == null) return uiString(R.string.today_source_counting)
    return uiString(
        R.string.today_source_count_joiner,
        uiPlural(R.plurals.today_source_days, days, grouped(days)),
        uiPlural(R.plurals.today_source_workouts, workouts, grouped(workouts)),
    )
}

/** Same bands as the Settings Strap battery pill, so the % reads the same colour everywhere (#159). */
internal fun batteryPillTone(pct: Int): StrandTone = when {
    pct <= 15 -> StrandTone.Critical
    pct <= 30 -> StrandTone.Warning
    else -> StrandTone.Positive
}

internal fun workoutDuration(row: WorkoutRow): String {
    val seconds = row.durationS ?: (row.endTs - row.startTs).coerceAtLeast(0L).toDouble()
    if (seconds <= 0.0) return NO_DATA
    val totalMinutes = (seconds / 60.0).roundToInt()
    return if (totalMinutes >= 60) {
        "${totalMinutes / 60}h ${totalMinutes % 60}m"
    } else {
        "${totalMinutes}m"
    }
}

/** "d MMM · HH:mm–HH:mm" (#157); start-only when the end isn't after the start (zero/unknown span). */
internal fun workoutCaption(row: WorkoutRow): String {
    val date = workoutDateFmt.format(Instant.ofEpochSecond(row.startTs))
    val start = workoutTimeFmt.format(Instant.ofEpochSecond(row.startTs))
    return if (row.endTs > row.startTs) {
        "$date · $start - ${workoutTimeFmt.format(Instant.ofEpochSecond(row.endTs))}"
    } else {
        "$date · $start"
    }
}

private fun grouped(value: Int): String =
    String.format(Locale.getDefault(), "%,d", value)
