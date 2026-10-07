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
import com.solarmonitor.app.alerts
import com.solarmonitor.app.data.Conn
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.data.Power
import com.solarmonitor.app.repo
import com.solarmonitor.app.ui.fmtW
import com.solarmonitor.app.ui.hhmm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a quiet connection to the monitor while the app is closed so alerts arrive within seconds.
 * Its one notification is everything the app shows: a quiet status line normally, and the latest alert (with
 * sound once) when something happens. During a grid outage it is a "live" notification with a running timer;
 * on Android 16 it is promoted to the status bar chip / Now Bar.
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
    private var lastKey = ""
    private var lastAlertId = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID, build(State(null, Conn.Connecting, null, false, 0L), alert = false), type)
        val repo = repo
        val alerts = alerts
        alerts.serviceShows = true
        repo.serviceActive.value = true
        scope.launch {
            combine(repo.live, repo.conn, alerts.headline, alerts.gridOff, alerts.outageStart) { l, c, h, off, start -> State(l, c, h, off, start) }
                .collectLatest { st ->
                    val newAlert = st.headline != null && st.headline.id != lastAlertId
                    val key = text(st).joinToString() + st.headline?.id + st.gridOff
                    if (newAlert || key != lastKey) {
                        lastKey = key
                        if (newAlert) lastAlertId = st.headline!!.id
                        runCatching { NotificationManagerCompat.from(this@MonitorService).notify(ID, build(st, newAlert)) }
                    }
                    delay(3000)   // at most one quiet update every 3 s
                }
        }
        // monthly units: check every 30 minutes
        scope.launch {
            delay(20_000)
            while (isActive) {
                runCatching {
                    val days = repo.days()
                    if (repo.bill.value == null) repo.loadBill()
                    if (days != null) alerts.checkUnits(days, repo.live.value, repo.bill.value ?: com.solarmonitor.app.data.BillConfig())
                }
                delay(30 * 60_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        alerts.serviceShows = false
        repo.serviceActive.value = false
        scope.cancel()
        super.onDestroy()
    }

    private data class State(val live: Live?, val conn: Conn, val headline: Headline?, val gridOff: Boolean, val outageStart: Long)

    private fun text(st: State): List<String> {
        val l = st.live
        val h = st.headline
        if (h != null) return listOf(h.title, h.text)
        return when {
            l == null || !l.ever -> listOf(if (st.conn == Conn.Offline) "Not connected" else "Connecting…", "Waiting for the solar monitor on your Wi-Fi")
            st.conn == Conn.Offline -> listOf("Monitor not reachable", "Last: battery ${l.battPct}% · grid ${if (l.gridOn) "on" else "off"}")
            !l.ok -> listOf("Inverter not answering", l.err)
            !l.gridOn -> listOf(
                if (st.outageStart > 0) "Grid off since ${hhmm(st.outageStart)}" else "Grid is off",
                "${Power.label(l, repo.prefs.value.idleW)} · battery ${l.battPct}% · home ${fmtW(l.loadW)}",
            )
            else -> listOf(
                "Battery ${l.battPct}% · Grid on",
                "${Power.label(l, repo.prefs.value.idleW)} · solar ${fmtW(l.pvW)} · home ${fmtW(l.loadW)}",
            )
        }
    }

    private fun build(st: State, alert: Boolean): Notification {
        val (title, body) = text(st)
        val h = st.headline
        val outage = st.gridOff && st.live?.ok == true
        val b = NotificationCompat.Builder(this, h?.channel ?: if (outage) Alerts.CH_GRID else Alerts.CH_STATUS)
            .setSmallIcon(h?.icon ?: if (outage) R.drawable.ic_stat_grid else R.drawable.ic_stat_solar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(Alerts.openAppIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)       // sound/vibration only for a new alert
            .setSilent(!alert)
            .setCategory(if (alert) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (alert || outage) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (outage && st.outageStart > 0) {
            // live timer since the grid went off; Android 16 shows it as a status-bar chip
            b.setWhen(st.outageStart).setShowWhen(true).setUsesChronometer(true)
                .setRequestPromotedOngoing(true).setShortCriticalText("Grid off")
        } else b.setShowWhen(false)
        return b.build()
    }
}
