package com.noop.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noop.NoopApplication

/** What brought the process up without a screen: a phone restart or an update of this app. */
internal enum class StrapRestartTrigger(val logLabel: String) {
    PHONE_RESTART("phone restart"),
    APP_UPDATE("app update"),
}

/** Map a broadcast action to the restart it reports, or null for anything this receiver ignores. */
internal fun strapRestartTrigger(action: String?): StrapRestartTrigger? = when (action) {
    Intent.ACTION_BOOT_COMPLETED,
    "android.intent.action.QUICKBOOT_POWERON",
    "com.htc.intent.action.QUICKBOOT_POWERON" -> StrapRestartTrigger.PHONE_RESTART
    Intent.ACTION_MY_PACKAGE_REPLACED -> StrapRestartTrigger.APP_UPDATE
    else -> null
}

/**
 * Resumes the saved strap's background link after a phone restart or an app update, so data keeps
 * syncing without the user opening the app first.
 *
 * Both events end the old process and its foreground service, and before this receiver the link only
 * came back on the next app launch. It runs the same [NoopApplication.resumeSavedStrapConnection] rule
 * the launch path uses: nothing happens when "Stay connected in the background" is off or no strap is
 * saved. BOOT_COMPLETED and MY_PACKAGE_REPLACED are both on Android's list of broadcasts allowed to
 * start a foreground service from the background.
 */
class StrapRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val trigger = strapRestartTrigger(intent?.action) ?: return
        val app = context.applicationContext as? NoopApplication ?: return
        runCatching {
            if (app.resumeSavedStrapConnection()) {
                app.ble.externalLog("Background reconnect requested after ${trigger.logLabel}")
            }
        }
    }
}
