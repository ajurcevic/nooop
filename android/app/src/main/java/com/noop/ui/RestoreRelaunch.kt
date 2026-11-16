package com.noop.ui

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Restart the app process after a successful restore.
 *
 * A restore parks the verified backup beside the live database, and `DataBackup.applyPendingRestore`
 * moves it into place at the next cold start. Until then the process keeps writing to the OLD store, and
 * strap history acked into it would be trimmed from the strap and then replaced by the backup. So the
 * restart is part of the restore, not advice: it is done here rather than left to a toast (which is how
 * #57 happened), and a foreground BLE service would otherwise keep the process alive through a swipe-away.
 *
 * NonCancellable because callers run in a screen's scope, which is cancelled the moment the user
 * navigates away.
 */
suspend fun relaunchAfterRestore(context: Context) {
    withContext(NonCancellable) {
        delay(800) // let the toast render before the process ends
        val ctx = context.applicationContext
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { ctx.startActivity(it) }
        Runtime.getRuntime().exit(0)
    }
}
