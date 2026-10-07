package com.solarmonitor.app.notify

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.solarmonitor.app.R
import com.solarmonitor.app.data.Conn
import com.solarmonitor.app.data.Decode
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.repo
import com.solarmonitor.app.ui.fmtW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a quiet connection to the monitor while the app is closed so grid/battery alerts arrive
 * within seconds. Shows a low-priority ongoing status notification (required for a foreground service).
 */
class MonitorService : Service() {
    companion object {
        private const val ID = 1

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID, build(null, Conn.Connecting), type)
        val repo = repo
        repo.serviceActive.value = true
        scope.launch {
            combine(repo.live, repo.conn) { l, c -> l to c }.collectLatest { (l, c) ->
                val n = build(l, c)
                val key = text(l, c).joinToString()
                if (key != lastText) {
                    lastText = key
                    runCatching { NotificationManagerCompat.from(this@MonitorService).notify(ID, n) }
                }
                delay(3000)   // at most one status update every 3 s
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        repo.serviceActive.value = false
        scope.cancel()
        super.onDestroy()
    }

    private fun text(l: Live?, c: Conn): List<String> = when {
        l == null || !l.ever -> listOf(if (c == Conn.Offline) "Not connected" else "Connecting…", "Waiting for the solar monitor on your Wi-Fi")
        c == Conn.Offline -> listOf("Not connected", "Last: battery ${l.battPct}% · grid ${if (l.gridOn) "on" else "off"}")
        !l.ok -> listOf("Inverter not answering", l.err)
        else -> listOf(
            "Battery ${l.battPct}% · Grid ${if (l.gridOn) "on" else "off"}",
            "Solar ${fmtW(l.pvW)} · Home ${fmtW(l.loadW)} · ${Decode.modeName(l.mode)}",
        )
    }

    private fun build(l: Live?, c: Conn): Notification {
        val (title, body) = text(l, c)
        return NotificationCompat.Builder(this, Alerts.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_solar)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(Alerts.openAppIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
