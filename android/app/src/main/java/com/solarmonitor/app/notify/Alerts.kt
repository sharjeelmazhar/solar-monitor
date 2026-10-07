package com.solarmonitor.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.solarmonitor.app.MainActivity
import com.solarmonitor.app.R
import com.solarmonitor.app.data.Decode
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.data.Prefs
import com.solarmonitor.app.ui.fmtDuration

/** Turns changes in the readings into notifications (grid off/on, battery low/full, inverter problems). */
class Alerts(private val context: Context, private val prefs: Prefs) {
    companion object {
        const val CH_GRID = "grid"
        const val CH_BATTERY = "battery"
        const val CH_FAULT = "fault"
        const val CH_STATUS = "status"
        private const val ID_GRID = 10
        private const val ID_BATT = 11
        private const val ID_FAULT = 12
        private const val ID_NOREPLY = 13

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private var lastOk: Live? = null
    private var outageStart = 0L
    private var lowSent = false
    private var fullSent = true
    private var activeWarn = emptySet<Int>()
    private var notOkSince = 0L
    private var noReplySent = false

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_GRID, "Grid power", NotificationManager.IMPORTANCE_HIGH).apply { description = "When grid (WAPDA) power goes off or comes back" },
            NotificationChannel(CH_BATTERY, "Battery", NotificationManager.IMPORTANCE_HIGH).apply { description = "Battery low or full" },
            NotificationChannel(CH_FAULT, "Inverter problems", NotificationManager.IMPORTANCE_HIGH).apply { description = "Inverter faults and warnings" },
            NotificationChannel(CH_STATUS, "Live status", NotificationManager.IMPORTANCE_LOW).apply { description = "Ongoing status while background monitoring is on"; setShowBadge(false) },
        ))
    }

    fun onReading(prev: Live?, d: Live) {
        val s = prefs.value
        val now = System.currentTimeMillis()
        if (!d.ever) return

        // inverter stopped answering for 30 s
        if (!d.ok) {
            if (notOkSince == 0L) notOkSince = now
            if (s.alertFault && !noReplySent && now - notOkSince > 30_000) {
                post(ID_NOREPLY, CH_FAULT, R.drawable.ic_stat_warning, "Inverter not responding", "The monitor can't read the inverter: ${d.err.ifEmpty { "no reply" }}")
                noReplySent = true
            }
            return
        }
        notOkSince = 0
        if (noReplySent) { cancel(ID_NOREPLY); noReplySent = false }

        val p = lastOk
        lastOk = d
        if (p == null) {   // first good reading: just remember the state
            activeWarn = Decode.activeWarnings(d.warn).toSet()
            lowSent = d.battPct <= s.battLowPct
            fullSent = d.battPct >= 100
            outageStart = 0   // if the grid is already off we don't know when it went off
            return
        }

        if (s.alertGrid && p.gridOn != d.gridOn) {
            if (!d.gridOn) {
                outageStart = now
                post(ID_GRID, CH_GRID, R.drawable.ic_stat_grid, "Grid power is OFF",
                    "Running on ${if (d.pvW > 20) "solar and " else ""}battery · battery ${d.battPct}% · home ${d.loadW} W")
            } else {
                val lasted = if (outageStart > 0) " after ${fmtDuration((now - outageStart) / 60_000.0)}" else ""
                post(ID_GRID, CH_GRID, R.drawable.ic_stat_grid, "Grid power is back$lasted", "Grid ${d.gridV.toInt()} V · battery ${d.battPct}%")
                outageStart = 0
            }
        }

        if (s.alertBattLow) {
            if (!lowSent && d.battPct <= s.battLowPct && d.battW < 0) {
                post(ID_BATT, CH_BATTERY, R.drawable.ic_stat_battery, "Battery low: ${d.battPct}%",
                    "Discharging ${-d.battW} W · ${d.battV} V${if (!d.gridOn) " · grid is off" else ""}")
                lowSent = true
            } else if (lowSent && d.battPct >= s.battLowPct + 5) lowSent = false
        }
        if (s.alertBattFull) {
            if (!fullSent && d.battPct >= 100) { post(ID_BATT, CH_BATTERY, R.drawable.ic_stat_battery, "Battery full", "Battery is at 100% (${d.battV} V)"); fullSent = true }
            else if (fullSent && d.battPct <= 95) fullSent = false
        }

        val warn = Decode.activeWarnings(d.warn).toSet() + if (d.mode == 'F') setOf(1) else emptySet()
        val fresh = warn - activeWarn
        activeWarn = warn
        if (s.alertFault && fresh.isNotEmpty()) {
            val text = fresh.mapNotNull { Decode.warnings[it] }.joinToString(", ")
            val severe = fresh.any { it in Decode.severe }
            post(ID_FAULT, CH_FAULT, R.drawable.ic_stat_warning, if (severe) "Inverter problem" else "Inverter warning", text)
        }
    }

    private fun post(id: Int, channel: String, icon: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    private fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)
}
