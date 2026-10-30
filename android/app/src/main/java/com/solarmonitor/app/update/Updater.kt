package com.solarmonitor.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.solarmonitor.app.MainActivity
import com.solarmonitor.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One Android build published as a GitHub release: tag "android-v1.5.8" with one .apk asset. */
data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String)

sealed interface UpdState {
    data object Idle : UpdState
    data object Checking : UpdState
    data object UpToDate : UpdState
    data class Available(val rel: Release) : UpdState
    data class Downloading(val rel: Release, val progress: Float) : UpdState
    data class Installing(val rel: Release) : UpdState
    data class Failed(val msg: String, val rel: Release?) : UpdState
}

/**
 * In-app updates until the app is on the Play Store: new versions are GitHub releases of the (public) repo.
 * The APK is streamed straight into Android's installer, so no file is left in Downloads.
 * After the first update the app is its own installer, and on Android 12+ later updates can go through without
 * the "Install?" screen.
 */
class Updater(private val context: Context) {
    companion object {
        const val REPO = "sharjeelmazhar/solar-monitor"
        const val TAG_PREFIX = "android-v"
        private const val CH_UPDATE = "update"
        private const val NOTIFY_ID = 40

        /** "1.5.10" > "1.5.9"; anything after the numbers ("-beta") is ignored. */
        fun newer(a: String, b: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val x = parts(a); val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        /** The newest Android release in a GitHub /releases answer that is newer than [current], or null. */
        fun pick(json: String, current: String): Release? {
            val arr = JSONArray(json)
            var best: Release? = null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val tag = r.optString("tag_name")
                if (!tag.startsWith(TAG_PREFIX) || r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val v = tag.removePrefix(TAG_PREFIX)
                val assets = r.optJSONArray("assets") ?: continue
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
                if (!newer(v, current) || (best != null && !newer(v, best.version))) continue
                best = Release(v, r.optString("body").trim(), apk.optString("browser_download_url"), apk.optLong("size"),
                    apk.optString("digest").removePrefix("sha256:").takeIf { apk.optString("digest").startsWith("sha256:") } ?: "")
            }
            return best
        }
    }

    private val _state = MutableStateFlow<UpdState>(UpdState.Idle)
    val state: StateFlow<UpdState> = _state.asStateFlow()
    private val sp = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    val current: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_UPDATE, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When a new version of the app is ready" })
    }

    /** Asks GitHub for a newer version. Quiet = a failed check leaves the state alone (used on app start). */
    suspend fun check(quiet: Boolean = false): Release? {
        if (_state.value is UpdState.Downloading || _state.value is UpdState.Installing) return null
        if (!quiet) _state.value = UpdState.Checking
        lastCheck = System.currentTimeMillis()
        return try {
            val json = withContext(Dispatchers.IO) { getText("https://api.github.com/repos/$REPO/releases?per_page=20") }
            val rel = pick(json, current)
            _state.value = if (rel != null) UpdState.Available(rel) else UpdState.UpToDate
            rel
        } catch (e: Exception) {
            if (!quiet) _state.value = UpdState.Failed("Couldn't check for updates. Is the phone online?", null)
            else if (_state.value is UpdState.Checking) _state.value = UpdState.Idle
            null
        }
    }

    /** "Later" on the pop-up: don't pop up for this version again today (the System tab still shows it). */
    fun snooze(rel: Release) = sp.edit().putString("snoozeV", rel.version).putLong("snoozeT", System.currentTimeMillis()).apply()
    fun snoozed(rel: Release) = sp.getString("snoozeV", null) == rel.version && System.currentTimeMillis() - sp.getLong("snoozeT", 0) < 24 * 3600_000L

    /** Background check from the monitor service: a notification instead of a pop-up, once per version. */
    suspend fun checkAndNotify() {
        val rel = check(quiet = true) ?: return
        if (sp.getString("notifiedV", null) == rel.version) return
        sp.edit().putString("notifiedV", rel.version).apply()
        val open = PendingIntent.getActivity(context, NOTIFY_ID, Intent(context, MainActivity::class.java).putExtra("update", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, NotificationCompat.Builder(context, CH_UPDATE)
                .setSmallIcon(R.drawable.ic_stat_solar).setContentTitle("Update ready: v${rel.version}")
                .setContentText("Tap to install the new version of Solar Monitor").setContentIntent(open).setAutoCancel(true).build())
        }
    }

    /** Set while the user is in Android's "Install unknown apps" screen; the install starts when they come back. */
    var waitingForPermission: Release? = null
    var lastCheck = 0L

    /** False until the user allows "Install unknown apps" for this app (asked once, on the first update). */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    /** Downloads the APK straight into an install session and hands it to Android. */
    suspend fun install(rel: Release) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (rel.size > 0) setSize(rel.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        var id = -1
        try {
            _state.value = UpdState.Downloading(rel, 0f)
            id = pi.createSession(params)
            withContext(Dispatchers.IO) {
                pi.openSession(id).use { s ->
                    val c = open(rel.apkUrl, 30_000)
                    try {
                        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                        val total = rel.size.takeIf { it > 0 } ?: c.contentLengthLong
                        val md = MessageDigest.getInstance("SHA-256")
                        s.openWrite("app.apk", 0, total.takeIf { it > 0 } ?: -1).use { out ->
                            c.inputStream.use { inp ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                                    if (total > 0) _state.value = UpdState.Downloading(rel, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                                s.fsync(out)
                            }
                        }
                        val hex = md.digest().joinToString("") { "%02x".format(it) }
                        if (rel.sha256.isNotEmpty() && !hex.equals(rel.sha256, ignoreCase = true)) throw IOException("download damaged")
                    } finally {
                        c.disconnect()
                    }
                    _state.value = UpdState.Installing(rel)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val done = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
                    s.commit(done.intentSender)
                }
            }
        } catch (e: Exception) {
            if (id >= 0) runCatching { pi.abandonSession(id) }
            _state.value = UpdState.Failed(if (e.message == "download damaged") "The download was damaged. Try again." else "Download failed. Check the internet and try again.", rel)
        }
    }

    /** Result of the install session (from [InstallReceiver]). On success Android restarts the app as the new version. */
    internal fun onResult(context: Context, intent: Intent) {
        val rel = (_state.value as? UpdState.Installing)?.rel
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "Do you want to update this app?" screen
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = rel?.let { UpdState.Available(it) } ?: UpdState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _state.value = UpdState.Failed("This update is signed with a different key than the installed app. Uninstall the app once and install the new APK by hand.", rel)
            PackageInstaller.STATUS_FAILURE_STORAGE -> _state.value = UpdState.Failed("Not enough free space on the phone.", rel)
            else -> _state.value = UpdState.Failed("Install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"}", rel)
        }
    }

    private fun open(url: String, readMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = readMs
            instanceFollowRedirects = true   // release assets redirect to GitHub's file host (https -> https)
            setRequestProperty("User-Agent", "SolarMonitor-Android")
        }

    private fun getText(url: String): String {
        val c = open(url, 10_000).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = context.updater.onResult(context, intent)
}

val Context.updater: Updater get() = (applicationContext as com.solarmonitor.app.SolarApp).updater
