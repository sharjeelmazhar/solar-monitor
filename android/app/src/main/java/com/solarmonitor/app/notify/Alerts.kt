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
import com.solarmonitor.app.data.BillCalc
import com.solarmonitor.app.data.BillConfig
import com.solarmonitor.app.data.DayRec
import com.solarmonitor.app.data.Decode
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.data.Notice
import com.solarmonitor.app.data.Power
import com.solarmonitor.app.data.Prefs
import com.solarmonitor.app.ui.fmtDuration
import com.solarmonitor.app.ui.hhmm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

/**
 * An alert to show. While background monitoring runs, alerts are shown in the one ongoing notification of
 * [MonitorService] (with sound once), so the phone never shows two notifications from this app. Without the
 * service they are posted as ordinary notifications.
 */
data class Headline(
    val id: Long,              // changes for every new alert (= play the sound once)
    val channel: String,
    val icon: Int,
    val title: String,
    val text: String,
    val until: Long,           // shown until then (Long.MAX_VALUE = until replaced)
)

/** Turns changes in the readings into alerts: grid off/on, battery, inverter problems, weak solar, monthly units. */
class Alerts(private val context: Context, private val prefs: Prefs) {
    companion object {
        const val CH_GRID = "grid"
        const val CH_BATTERY = "battery"
        const val CH_FAULT = "fault"
        const val CH_USAGE = "usage"
        const val CH_STATUS = "status"
        private const val ID_GRID = 10
        private const val ID_BATT = 11
        private const val ID_FAULT = 12
        private const val ID_NOREPLY = 13
        private const val ID_USAGE = 14

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** True while [MonitorService] shows the alerts in its own notification. */
    @Volatile var serviceShows = false

    private val _headline = MutableStateFlow<Headline?>(null)
    val headline: StateFlow<Headline?> = _headline.asStateFlow()
    /** Start of the current outage (epoch ms), 0 when the grid is on or the start is unknown. */
    private val _outageStart = MutableStateFlow(0L)
    val outageStart: StateFlow<Long> = _outageStart.asStateFlow()
    private val _gridOff = MutableStateFlow(false)
    val gridOff: StateFlow<Boolean> = _gridOff.asStateFlow()

    private var lastOk: Live? = null
    private var lowSent = false
    private var fullSent = true
    private var activeWarn = emptySet<Int>()
    private var notOkSince = 0L
    private var noReplySent = false
    private var weakSince = 0L
    private var weakClearSince = 0L
    private var weakSent = false
    private var weakLastAt = 0L

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_GRID, "Grid power", NotificationManager.IMPORTANCE_HIGH).apply { description = "When grid (WAPDA) power goes off or comes back" },
            NotificationChannel(CH_BATTERY, "Battery and solar", NotificationManager.IMPORTANCE_HIGH).apply { description = "Battery low or full; little sun while the battery carries the home" },
            NotificationChannel(CH_FAULT, "Inverter problems", NotificationManager.IMPORTANCE_HIGH).apply { description = "Inverter faults and warnings" },
            NotificationChannel(CH_USAGE, "Electricity units", NotificationManager.IMPORTANCE_HIGH).apply { description = "Monthly grid units: 150, 175 and 190, and when the month is heading over 200" },
            NotificationChannel(CH_STATUS, "Live status", NotificationManager.IMPORTANCE_LOW).apply { description = "Quiet status while background monitoring is on"; setShowBadge(false) },
        ))
    }

    /** True on battery-less inverters: battery alerts and battery warning bits are skipped. */
    var noBattery: () -> Boolean = { false }

    fun onReading(prev: Live?, d: Live) {
        val s = prefs.value
        val now = System.currentTimeMillis()
        if (!d.ever) return
        _headline.value?.let { if (it.until < now) _headline.value = null }

        // inverter stopped answering for 30 s
        if (!d.ok) {
            if (notOkSince == 0L) notOkSince = now
            if (s.alertFault && !noReplySent && now - notOkSince > 30_000) {
                alert(ID_NOREPLY, CH_FAULT, R.drawable.ic_stat_warning, "Inverter not responding", "The monitor can't read the inverter: ${d.err.ifEmpty { "no reply" }}", 60)
                noReplySent = true
            }
            return
        }
        notOkSince = 0
        if (noReplySent) { cancel(ID_NOREPLY); noReplySent = false }

        val p = lastOk
        lastOk = d
        if (p == null) {   // first good reading: just remember the state
            activeWarn = Decode.activeWarnings(d.warn, noBattery()).toSet()
            lowSent = d.battPct <= s.battLowPct
            fullSent = d.battPct >= 100
            _gridOff.value = !d.gridOn
            _outageStart.value = 0   // if the grid is already off we don't know when it went off
            return
        }

        if (p.gridOn != d.gridOn) {
            _gridOff.value = !d.gridOn
            if (!d.gridOn) {
                _outageStart.value = now
                if (s.alertGrid) alert(ID_GRID, CH_GRID, R.drawable.ic_stat_grid, "Grid power is OFF",
                    "${if (d.pvW > 20) "Running on solar and battery" else "Running on battery"} · battery ${d.battPct}% · home ${d.loadW} W", 0)
            } else {
                val start = _outageStart.value
                val lasted = if (start > 0) " after ${fmtDuration((now - start) / 60_000.0)}" else ""
                _outageStart.value = 0
                if (s.alertGrid) alert(ID_GRID, CH_GRID, R.drawable.ic_stat_grid, "Grid power is back$lasted",
                    "Back at ${hhmm(now)}${if (start > 0) " · off since ${hhmm(start)}" else ""} · battery ${d.battPct}%", 30)
            }
        }

        val discharging = Power.batt(d, s.idleW) == Power.Batt.Discharging
        if (s.alertBattLow && !noBattery()) {
            if (!lowSent && d.battPct <= s.battLowPct && discharging) {
                alert(ID_BATT, CH_BATTERY, R.drawable.ic_stat_battery, "Battery low: ${d.battPct}%",
                    "Giving ${-d.battW} W · ${String.format(java.util.Locale.US, "%.1f", -Power.battAmps(d))} A · ${d.battV} V${if (!d.gridOn) " · grid is off" else ""}", 60)
                lowSent = true
            } else if (lowSent && d.battPct >= s.battLowPct + 5) lowSent = false
        }
        if (s.alertBattFull && !noBattery()) {
            if (!fullSent && d.battPct >= 100) { alert(ID_BATT, CH_BATTERY, R.drawable.ic_stat_battery, "Battery full", "Battery is at 100% (${d.battV} V)", 30); fullSent = true }
            else if (fullSent && d.battPct <= 95) fullSent = false
        }

        // little sun during the day while the grid is off: suggest switching the grid on (5 min to confirm, at most hourly)
        if (s.alertWeakSolar) {
            if (Power.weakSolar(d, s.idleW)) {
                weakClearSince = 0
                if (weakSince == 0L) weakSince = now
                if (!weakSent && now - weakSince > 5 * 60_000 && now - weakLastAt > 60 * 60_000) {
                    alert(ID_BATT, CH_BATTERY, R.drawable.ic_stat_battery, "Little sun: the battery is running the home",
                        "Solar ${d.pvW} W, home ${d.loadW} W, battery ${d.battPct}% (giving ${-d.battW} W). It may be cloudy. Turn the grid on to save the battery.", 60)
                    weakSent = true; weakLastAt = now
                }
            } else {
                weakSince = 0
                if (weakClearSince == 0L) weakClearSince = now
                if (weakSent && now - weakClearSince > 5 * 60_000) weakSent = false
            }
        }

        val warn = Decode.activeWarnings(d.warn, noBattery()).toSet() + if (d.mode == 'F') setOf(1) else emptySet()
        val fresh = warn - activeWarn
        activeWarn = warn
        if (s.alertFault && fresh.isNotEmpty()) {
            val text = fresh.mapNotNull { Decode.warnings[it] }.joinToString(", ")
            val severe = fresh.any { it in Decode.severe }
            alert(ID_FAULT, CH_FAULT, R.drawable.ic_stat_warning, if (severe) "Inverter problem" else "Inverter warning", text, 120)
        }
    }

    /**
     * Monthly units check (run by the service every 30 minutes). Each step (150, 175, 190, heading over, over 200)
     * notifies once per billing month.
     */
    fun checkUnits(days: List<DayRec>, live: Live?, cfg: BillConfig) {
        if (!prefs.value.alertUnits || live == null || live.today.date <= 0) return
        val t = live.today
        val map = HashMap<Int, DayRec>()
        days.forEach { map[it.date] = it }
        map[t.date] = DayRec(t.date, t.pvWh.toFloat(), t.loadWh.toFloat(), t.gridWh.toFloat(), t.chgWh.toFloat(), t.disWh.toFloat(), t.pvPeak, t.loadPeak, t.gridOnMin, t.onlineMin, 0, 0, 0, t.outages)
        val now = LocalTime.now()
        val n = BillCalc.now(map, t.date, cfg, (now.hour * 60 + now.minute) / 1440.0, live.t.takeIf { it > 0 } ?: System.currentTimeMillis(), live.cyc)
        val a = n.alert ?: return
        if (n.m.covered < 3 && a.key == -1) return   // too little data for a projection
        val markKey = "units_${n.ym}"
        val done = prefs.getMark(markKey)?.split(',')?.toSet() ?: emptySet()
        if ("${a.key}" in done) return
        prefs.setMark(markKey, (done + "${a.key}").joinToString(","))
        alert(ID_USAGE, CH_USAGE, R.drawable.ic_stat_grid, a.title, a.text, 120)
    }

    /** Records the alert and shows it: in the service notification when it runs, else as its own notification. */
    private fun alert(id: Int, channel: String, icon: Int, title: String, text: String, showMinutes: Int) {
        prefs.addNotice(Notice(System.currentTimeMillis(), channel, title, text))
        if (serviceShows) {
            val until = if (showMinutes == 0) Long.MAX_VALUE else System.currentTimeMillis() + showMinutes * 60_000L
            _headline.value = Headline(System.nanoTime(), channel, icon, title, text, until)
        } else post(id, channel, icon, title, text)
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
