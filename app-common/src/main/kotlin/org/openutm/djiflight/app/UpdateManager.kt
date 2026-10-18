package org.openutm.djiflight.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openutm.djiflight.core.update.Release
import org.openutm.djiflight.core.update.Updates
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sideloaded updates from the lab's release channel: a static latest.json next to the APKs
 * (GitHub Releases or the lab's web host). The APK is installed only if its SHA-256 matches, and
 * Android itself refuses an APK not signed with the installed app's key.
 */
class UpdateManager(private val context: Context, private val versionCode: Int) {
    suspend fun check(channelUrl: String): Release? = withContext(Dispatchers.IO) {
        val channel = Updates.parse(get(channelUrl).toString(Charsets.UTF_8))
        Updates.available(channel, context.packageName, versionCode, Build.VERSION.SDK_INT)
    }

    suspend fun download(channelUrl: String, release: Release, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "${release.applicationId}-${release.versionName}.apk")
        val conn = URL(Updates.resolveUrl(channelUrl, release.url)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            check(conn.responseCode == 200) { context.getString(R.string.update_http, conn.responseCode) }
            val total = contentLength(conn)
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!Updates.verify(target, release)) {
            target.delete()
            error(context.getString(R.string.update_sha_mismatch))
        }
        target
    }

    /** Opens the system installer, or first the "install unknown apps" permission page. */
    fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            check(conn.responseCode == 200) { context.getString(R.string.update_http_for, conn.responseCode, url) }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
