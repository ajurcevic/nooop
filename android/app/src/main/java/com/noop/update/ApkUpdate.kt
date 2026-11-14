package com.noop.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK found by [UpdateCheck] and hands it to Android's package installer.
 *
 * Runs only from the Settings button, never in the background. Android always shows its own install
 * confirmation for a sideloaded APK, so nothing is installed without the user confirming it there; the
 * first time, Android also asks the user to allow NOOP to install apps ("Install unknown apps").
 *
 * The APK goes to `cacheDir/updates/`, which `res/xml/file_paths.xml` shares through the app's
 * FileProvider. Only one APK is kept: each download clears the folder first.
 *
 * Android refuses the install if the APK is signed with a different key or has a lower versionCode than
 * the installed app; the installer reports that itself.
 */
object ApkUpdate {

    private const val DIR = "updates"

    /** Upper bound on an APK download, so a wrong asset cannot fill the cache. The release APK is far
     *  below this. */
    private const val MAX_BYTES = 300L * 1024L * 1024L

    sealed interface Download {
        data class Done(val file: File) : Download
        object Failed : Download
    }

    /** The file name the download is saved under, derived from the version only so a crafted asset
     *  name cannot steer the path. Pure + unit-tested. */
    fun fileNameFor(version: String): String {
        val safe = version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.ifEmpty { "latest" }
        return "NOOP-$safe.apk"
    }

    /**
     * Download [apkUrl] into the cache, reporting progress as a 0..1 fraction (or null while the size is
     * unknown). Never throws: any failure (offline, non-200, too large, cut off) resolves to
     * [Download.Failed] and leaves no partial file behind.
     */
    suspend fun download(
        context: Context,
        apkUrl: String,
        version: String,
        onProgress: (Float?) -> Unit = {},
    ): Download = withContext(Dispatchers.IO) {
        if (!apkUrl.startsWith("https://")) return@withContext Download.Failed
        val dir = File(context.cacheDir, DIR)
        val target = File(dir, fileNameFor(version))
        val part = File(dir, target.name + ".part")
        runCatching {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            // GitHub answers with a redirect to its download host; HttpURLConnection follows
            // https -> https redirects on its own.
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                if (conn.responseCode != 200) return@runCatching Download.Failed
                val total = conn.contentLengthLong.takeIf { it > 0 }
                if (total != null && total > MAX_BYTES) return@runCatching Download.Failed
                var read = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastReported = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > MAX_BYTES) return@runCatching Download.Failed
                            out.write(buf, 0, n)
                            val pct = if (total != null) ((read * 100) / total).toInt() else -1
                            if (pct != lastReported) {
                                lastReported = pct
                                withContext(Dispatchers.Main) {
                                    onProgress(if (total != null) read.toFloat() / total else null)
                                }
                            }
                        }
                    }
                }
                if (read == 0L || (total != null && read != total)) return@runCatching Download.Failed
                if (!part.renameTo(target)) return@runCatching Download.Failed
                Download.Done(target)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(Download.Failed).also { if (it !is Download.Done) part.delete() }
    }

    /** False when Android still needs the user to allow NOOP to install apps (per-app switch). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Open the system screen where the user allows NOOP to install apps. Returns false if no such
     *  screen exists on this device. */
    fun openInstallPermissionSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Hand [file] to Android's package installer, which shows its own confirmation. Returns false if
     *  no installer handled the intent. */
    fun install(context: Context, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
