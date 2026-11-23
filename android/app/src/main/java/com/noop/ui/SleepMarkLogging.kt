package com.noop.ui

import android.content.Context
import android.widget.Toast
import com.noop.analytics.SleepMark
import com.noop.analytics.SleepMarkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Logs a "going to bed" / "woke up" mark (#461): one line on the shareable strap log, one row in the
 * `sleep_mark` metric series (written off the main thread), and a Toast confirming the time. LOGGING ONLY:
 * a mark never changes the detected sleep or the night's boundaries, and nothing is sent to the strap.
 * The quick-actions sheet and the Sleep marks card hosted on Today both call this, so a mark is recorded
 * the same way wherever it is tapped. Mirrors macOS SleepView.sleepMarkCard's handler.
 */
internal fun logSleepMark(vm: AppViewModel, scope: CoroutineScope, context: Context, type: SleepMarkType) {
    val mark = SleepMark.now(type)
    // The shareable strap log is the human-readable surface in a debug export.
    vm.ble.externalLog(mark.logLine())
    scope.launch {
        runCatching {
            vm.repo.upsertMetricSeries(listOf(mark.metricPoint("my-whoop")))
        }
    }
    Toast.makeText(context, mark.confirmation(), Toast.LENGTH_SHORT).show()
}
