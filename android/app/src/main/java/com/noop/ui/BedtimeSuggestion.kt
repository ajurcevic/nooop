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
