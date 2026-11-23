package com.noop.ui

import com.noop.alarm.SmartAlarmStore
import kotlin.math.roundToInt

/** Minutes allowed for falling asleep between getting into bed and the first minute of sleep. */
internal const val BEDTIME_SLEEP_LATENCY_MIN = 15

/**
 * The minute of day to be in bed by so that [needMin] of sleep, after [latencyMin] to fall asleep, ends at
 * [wakeMinutes]. Wraps across midnight (a 06:30 wake with an 8 h need is 22:15). Pure, so the arithmetic is
 * testable without a clock or Compose.
 */
internal fun suggestedBedtimeMinutes(
    wakeMinutes: Int,
    needMin: Double,
    latencyMin: Int = BEDTIME_SLEEP_LATENCY_MIN,
): Int = Math.floorMod(wakeMinutes - needMin.roundToInt() - latencyMin, SmartAlarmStore.MINUTES_PER_DAY)

/** How far ahead a bedtime can lie and still be tonight's. A weekday-only alarm seen on a Friday evening
 *  points at Sunday night's bedtime, well beyond this. */
internal const val BEDTIME_TONIGHT_MAX_AHEAD_MIN = 18 * 60

/** How long after a bedtime it still reads as tonight's: long enough for a late night, short of the small
 *  hours of a night already being slept. */
internal const val BEDTIME_TONIGHT_MAX_BEHIND_MIN = 3 * 60

/**
 * Whether the bedtime for the wake window that opens at [wakeStartMs] can honestly be called "tonight" at
 * [nowMs]. The bedtime is [needMin] of sleep plus [latencyMin] to fall asleep before that wake, the same
 * arithmetic as [suggestedBedtimeMinutes]; it counts as tonight's when it lies at most
 * [BEDTIME_TONIGHT_MAX_AHEAD_MIN] ahead and at most [BEDTIME_TONIGHT_MAX_BEHIND_MIN] behind. The next alarm
 * can be days away, and between bedtime and the alarm the computed bedtime belongs to the night already
 * under way; in both cases there is no bedtime to name for tonight. Pure, so it is testable without a clock.
 */
internal fun bedtimeIsTonight(
    nowMs: Long,
    wakeStartMs: Long,
    needMin: Double,
    latencyMin: Int = BEDTIME_SLEEP_LATENCY_MIN,
): Boolean {
    val bedMs = wakeStartMs - (needMin.roundToInt() + latencyMin) * 60_000L
    val aheadMs = bedMs - nowMs
    return aheadMs <= BEDTIME_TONIGHT_MAX_AHEAD_MIN * 60_000L &&
        aheadMs >= -BEDTIME_TONIGHT_MAX_BEHIND_MIN * 60_000L
}
