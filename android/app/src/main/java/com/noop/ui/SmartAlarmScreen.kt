package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ble.PuffinExperiment

/**
 * Alarms: one strap alarm with a time for each day of the week, and an optional second buzz 5 minutes
 * after the first.
 *
 * The strap's own firmware alarm does the buzzing ([com.noop.ble.WhoopBleClient.armStrapAlarm]), armed
 * through the single reconciler in [AppViewModel]. The schedule is the strap alarm's existing model, a
 * default time plus weekdays plus per-day overrides; this page shows it as seven rows. A day's time is
 * its override, else the default, and picking the default again clears the override.
 *
 * The phone wake window, the "Buzz WHOOP" companion and the wind-down reminder used to live here. They
 * were switched off once on upgrade ([AppViewModel] `migrateToStrapOnlyAlarms`).
 */
@Composable
fun SmartAlarmScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val enabled by vm.smartAlarmEnabled.collectAsStateWithLifecycle()
    val defaultMinutes by vm.smartAlarmMinutes.collectAsStateWithLifecycle()
    val weekdays by vm.smartAlarmWeekdays.collectAsStateWithLifecycle()
    val overrides by vm.smartAlarmDayOverrides.collectAsStateWithLifecycle()
    val rebuzz by vm.alarmRebuzzEnabled.collectAsStateWithLifecycle()
    val live = vm.live.collectAsStateWithLifecycle().value
    // On a 5/MG the firmware alarm only arms with Protocol probes on (WhoopBleClient.armStrapAlarm).
    val probesOn = PuffinExperiment.from(context).isEnabled

    LazyScreenScaffold(title = uiString(R.string.l10n_smart_alarm_screen_alarms_131dd3d6)) {
        item {
            // Ticks once a minute so the countdown cannot freeze at whatever it read when the page opened.
            val nowMs by produceState(initialValue = System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    kotlinx.coroutines.delay(60_000L)
                }
            }
            NextAlarmCard(
                next = alarmCountdown(
                    nowMs = nowMs,
                    armed = enabled,
                    defaultMinutes = defaultMinutes,
                    weekdays = weekdays,
                    overrides = overrides,
                ),
            )
        }

        item {
            NoopCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SwitchRow(label = "Strap alarm", checked = enabled, onChange = { vm.setSmartAlarmEnabled(it) })
                    if (enabled) {
                        for (dow in SMART_ALARM_WEEKDAY_ORDER) {
                            val dayOn = smartAlarmWeekdayIsSelected(dow, weekdays)
                            val dayMinutes = overrides[dow] ?: defaultMinutes
                            DayRow(
                                name = smartAlarmWeekdayName(dow),
                                minutes = dayMinutes,
                                on = dayOn,
                                onTime = { picked ->
                                    vm.setSmartAlarmDayOverride(dow, picked.takeIf { it != defaultMinutes })
                                },
                                onToggle = { want ->
                                    val onCount = SMART_ALARM_WEEKDAY_ORDER.count { smartAlarmWeekdayIsSelected(it, weekdays) }
                                    // An empty set means every day, so switching off the last day turns the
                                    // alarm off instead of silently turning every day back on.
                                    if (!want && onCount == 1) {
                                        vm.setSmartAlarmEnabled(false)
                                    } else if (want != dayOn) {
                                        vm.setSmartAlarmWeekdays(toggledSmartAlarmWeekday(dow, weekdays))
                                    }
                                },
                            )
                        }
                        AlarmDivider()
                        SwitchRow(label = "Buzz again after 5 min", checked = rebuzz, onChange = { vm.setAlarmRebuzzEnabled(it) })
                        val note = when {
                            live.whoop5Detected && !probesOn -> "Turn on Protocol probes in Test Centre so your strap can be armed."
                            !live.bonded -> "Saved. It reaches your strap next time it connects."
                            else -> null
                        }
                        note?.let {
                            Text(
                                it,
                                style = NoopType.footnote,
                                color = if (live.whoop5Detected && !probesOn) Palette.statusWarning else Palette.textTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextAlarmCard(next: Pair<String, String>?) {
    NoopCard(padding = 20.dp, tint = if (next != null) Palette.accent else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Alarm, contentDescription = null, tint = Palette.accent)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Overline("Next alarm")
                if (next != null) {
                    Text(next.first, style = NoopType.number(28f), color = Palette.textPrimary)
                    Text(next.second, style = NoopType.footnote, color = Palette.textSecondary)
                } else {
                    Text(uiString(R.string.l10n_smart_alarm_screen_off_e3de5ab0), style = NoopType.title2, color = Palette.textSecondary)
                }
            }
        }
    }
}

/**
 * The next alarm as ("06:30", "Tue 26 Nov · in 9 hours 12 minutes"), or null when nothing will fire.
 *
 * Resolved through [nextSmartAlarmEpochSec], the same pure function the strap slot is armed from, with
 * the per-day overrides threaded in, so the readout cannot drift from what the strap holds.
 */
@Composable
private fun alarmCountdown(
    nowMs: Long,
    armed: Boolean,
    defaultMinutes: Int,
    weekdays: Set<Int>,
    overrides: Map<Int, Int>,
): Pair<String, String>? {
    if (!armed) return null
    val nextSec = nextSmartAlarmEpochSec(
        minuteOfDay = defaultMinutes,
        weekdays = weekdays,
        nowMs = nowMs,
        dayOverrides = overrides,
    ) ?: return null
    val nextMs = nextSec * 1000L
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(nextMs))
    val date = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(nextMs))

    val totalMinutes = ((nextMs - nowMs) / 60_000L).toInt()
    if (totalMinutes < 1) {
        return time to "$date · ${uiString(R.string.l10n_smart_alarm_screen_alarm_in_less_than_a_minute_9821dce0)}"
    }
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    val parts = buildList {
        if (days > 0) add(pluralStringResource(R.plurals.alarm_countdown_days, days, days))
        if (hours > 0) add(pluralStringResource(R.plurals.alarm_countdown_hours, hours, hours))
        if (minutes > 0) add(pluralStringResource(R.plurals.alarm_countdown_minutes, minutes, minutes))
    }
    return time to "$date · in ${parts.joinToString(" ")}"
}

@Composable
private fun DayRow(
    name: String,
    minutes: Int,
    on: Boolean,
    onTime: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = NoopType.body,
            color = if (on) Palette.textPrimary else Palette.textTertiary,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        Spacer(Modifier.weight(1f))
        if (on) {
            TimeChip(minutes = minutes, accessibilityLabel = "$name alarm time", onPicked = onTime)
            Spacer(Modifier.width(12.dp))
        }
        AlarmSwitch(checked = on, onChange = onToggle)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        AlarmSwitch(checked = checked, onChange = onChange)
    }
}

@Composable
private fun AlarmSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.surfaceBase,
            checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textSecondary,
            uncheckedTrackColor = Palette.surfaceInset,
            uncheckedBorderColor = Palette.hairline,
        ),
    )
}

@Composable
private fun AlarmDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.hairline),
    )
}
