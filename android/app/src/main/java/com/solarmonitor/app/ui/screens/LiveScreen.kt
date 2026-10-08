package com.solarmonitor.app.ui.screens

import com.solarmonitor.app.ui.components.Segmented
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.Decode
import com.solarmonitor.app.data.Power
import com.solarmonitor.app.data.Sun
import com.solarmonitor.app.ui.components.EnergyCore3D
import androidx.compose.foundation.layout.fillMaxWidth
import com.solarmonitor.app.data.Info
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.ui.components.ChartCard
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import com.solarmonitor.app.ui.components.ChartLegend
import com.solarmonitor.app.ui.components.ChartSeries
import com.solarmonitor.app.ui.components.Grid
import com.solarmonitor.app.ui.components.KpiTile
import com.solarmonitor.app.ui.components.LineChart
import com.solarmonitor.app.ui.components.PowerFlow
import com.solarmonitor.app.ui.components.ScreenList
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.components.full
import com.solarmonitor.app.ui.components.StatTile
import com.solarmonitor.app.ui.fmt1
import com.solarmonitor.app.ui.fmt2
import com.solarmonitor.app.ui.fmtDuration
import com.solarmonitor.app.ui.fmtW
import com.solarmonitor.app.ui.fmtWh
import com.solarmonitor.app.ui.hhmm
import com.solarmonitor.app.ui.rememberStaleMs
import com.solarmonitor.app.ui.theme.EnergyColors
import com.solarmonitor.app.ui.theme.LocalEnergy
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun LiveScreen(repo: Repository, padding: PaddingValues, wide: Boolean) {
    val d by repo.live.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    val rated = info?.rated
    val e = LocalEnergy.current
    val stale = rememberStaleMs(repo)
    val offline = d != null && stale != null
    val s by repo.prefs.state.collectAsStateWithLifecycle()
    val idleW = s.idleW

    ScreenList(padding) {
        full("flow") {
            SectionCard("Energy flow", info = FLOW_INFO, sub = d?.takeIf { it.ever }?.let { Power.sentence(it, idleW) } ?: "Waiting for the inverter…",
                action = { d?.takeIf { it.ever }?.let { SourcePill(Power.label(it, idleW)) } }) {
                Box(contentAlignment = Alignment.Center) {
                    // offline: last values stay visible but faded and still, with a note on top
                    val fade = if (!offline) Modifier else Modifier.alpha(0.35f).then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(2.dp) else Modifier)
                    val cur = d
                    if (s.fx3d && !offline && cur != null && cur.ok) EnergyCore3D(cur, rated?.outW?.takeIf { it > 0 } ?: 3200, Modifier.fillMaxWidth(0.62f).alpha(0.8f).then(fade))
                    PowerFlow(d, rated?.outW?.takeIf { it > 0 } ?: 3200, Modifier.padding(vertical = 4.dp).then(fade), still = offline, idleW = idleW, noBatt = rated?.battV == 0.0)
                    if (offline) OfflineBadge(stale!!, d!!.t)
                }
            }
        }
        val x = d
        if (x == null || !x.ever) {
            full("wait") { SectionCard(null) { Text(if (x == null) "Connecting to the solar monitor…" else "Waiting for the inverter…", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            return@ScreenList
        }
        val alerts = if (offline) emptyList() else alertItems(x, idleW, info?.rated?.battV == 0.0)
        if (alerts.any { it.first > 0 }) full("alerts") { AlertsCard(alerts.filter { it.first > 0 }) }
        full("now") { Box(if (offline) Modifier.alpha(0.45f) else Modifier) { NowCard(x, info, e, 2, idleW, repo) } }
        item(key = "today") { TodayCard(x, info) }
        item(key = "chart") { LiveChartCard(repo, e) }
    }
}

/** Shown over the power flow when readings stop. */
@Composable
private fun OfflineBadge(staleMs: Long, t: Long) {
    val sec = staleMs / 1000
    val ago = if (sec < 90) "$sec s ago" else "${fmtDuration(sec / 60.0)} ago"
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp, modifier = Modifier.padding(horizontal = 24.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiOff, null, tint = LocalEnergy.current.crit)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Monitor not responding", style = MaterialTheme.typography.titleSmall)
                Text("Showing the last reading" + (if (t > 0) " from ${hhmm(t)}" else "") + " ($ago). It may be off or out of Wi-Fi range.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NowCard(d: Live, info: Info?, e: EnergyColors, cols: Int, idleW: Int, repo: Repository) {
    val bs = Power.batt(d, idleW)
    val rated = info?.rated
    val noBatt = rated?.battV == 0.0   // battery-less inverter (e.g. Galaxy Envy on solar + grid only)
    val battFill = when { d.battPct <= 20 -> e.crit; d.battPct <= 45 -> e.warn; else -> e.batt }
    val interval by repo.updateInterval.collectAsStateWithLifecycle()
    SectionCard("Right now", sub = if (interval > 0) "updating every ${fmt1(interval / 1000.0)} s" else "live") {
        Grid(cols, listOf(
            { m -> KpiTile("Solar", e.solar, fmtW(d.pvW), pvLine(d), (if (d.pv2V > 0) pv2Line(d) + "\n" else "") + "Peak today ${fmtW(d.today.pvPeak)}\n" + Sun.times().let { "Sunrise ${hhmm(it.rise)} · Sunset ${hhmm(it.set)}" }, m) },
            { m ->
                if (noBatt) KpiTile("Battery", e.batt, "None", "This inverter runs on solar", "and grid only", m, smallValue = true)
                else KpiTile("Battery", e.batt, "${d.battPct} %",
                    "${fmt2(d.battV)} V · ${fmt1(kotlin.math.abs(Power.battAmps(d)))} A · " + when (bs) { Power.Batt.Charging -> "charging ${fmtW(d.battW)}"; Power.Batt.Discharging -> "giving ${fmtW(-d.battW)}"; else -> "idle" },
                    battEta(d, info, rated?.battV) ?: if ((info?.battAh ?: 0.0) == 0.0) "Add battery Ah in System" else " ",
                    m, progress = d.battPct / 100f, progressColor = battFill)
            },
            { m -> KpiTile("Home", e.load, fmtW(d.loadW), "${d.loadPct}% load · ${d.loadVA} VA", "${fmt1(d.outV)} V · ${fmt1(d.outHz)} Hz", m, progress = d.loadPct / 100f) },
            { m ->
                if (d.gridOn) KpiTile("Grid (WAPDA)", e.grid, "${d.gridV.roundToInt()} V", "${fmt1(d.gridHz)} Hz · available", if (d.gridW > 15) "Importing ≈ ${fmtW(d.gridW)}" else "Not in use", m)
                else KpiTile("Grid (WAPDA)", e.grid, "Off", "No grid supply", if (d.today.outages > 0) "${d.today.outages} outage${if (d.today.outages > 1) "s" else ""} today" else " ", m, smallValue = false)
            },
            { m -> KpiTile("Inverter", e.inv, "${d.tempC} °C", Power.sentence(d, idleW), "Mode: ${Decode.modeName(d.mode)} · DC bus ${d.busV} V", m) },
            { m ->
                if (noBatt) KpiTile("Charging", MaterialTheme.colorScheme.outline, "—", "No battery to charge", " ", m, smallValue = true)
                else KpiTile("Charging", MaterialTheme.colorScheme.outline,
                    when { d.solarCharging && d.gridCharging -> "Solar + grid"; d.solarCharging -> "From solar"; d.gridCharging -> "From grid"; else -> "Not charging" },
                    if (bs == Power.Batt.Charging) "${fmtW(d.battW)} · ${fmt1(Power.battAmps(d))} A into battery" else "",
                    rated?.let { "Float ${it.float} V" } ?: " ", m, smallValue = true)
            },
        ))
    }
}

/** "166.1 V · 1.3 A", or per input on two-input inverters: "PV1 166 V · 1.3 A · 216 W". */
private fun pvLine(d: Live) = if (d.pv2V > 0) "PV1 ${d.pvV.roundToInt()} V · ${fmt1(d.pvA)} A · ${fmtW((d.pvV * d.pvA).roundToInt())}" else "${fmt1(d.pvV)} V · ${fmt1(d.pvA)} A"
private fun pv2Line(d: Live) = "PV2 ${d.pv2V.roundToInt()} V · ${fmt1(d.pv2A)} A · ${fmtW((d.pv2V * d.pv2A).roundToInt())}"

private fun battEta(d: Live, info: Info?, ratedV: Double?): String? {
    val ah = info?.battAh ?: return null
    if (ah <= 0) return null
    val nomV = ratedV?.takeIf { it > 0 } ?: if (d.battV > 40) 48.0 else if (d.battV > 20) 24.0 else 12.0
    val wh = ah * nomV
    return when {
        d.battW < -20 -> "≈ ${fmtDuration(wh * d.battPct / 100 / -d.battW * 60)} left"
        d.battW > 20 && d.battPct < 100 -> "≈ ${fmtDuration(wh * (100 - d.battPct) / 100 / d.battW * 60)} to full"
        else -> null
    }
}

@Composable
private fun TodayCard(d: Live, info: Info?) {
    val t = d.today
    val e = LocalEnergy.current
    SectionCard("Today", sub = if (t.onlineMin > 0) "monitored ${fmtDuration(t.onlineMin.toDouble())}" else null) {
        Grid(2, listOf(
            { m -> StatTile("Solar produced", fmtWh(t.pvWh), m, tone = e.solar) },
            { m -> StatTile("Home used", fmtWh(t.loadWh), m, tone = e.load) },
            { m -> StatTile("From grid (est.)", fmtWh(t.gridWh), m, info = GRID_EST_INFO, tone = e.grid) },
            { m -> StatTile("Battery in / out", fmtWh(t.chgWh), m, hint = "out ${fmtWh(t.disWh)}", tone = e.batt) },
            { m -> StatTile("Self-powered", if (t.loadWh > 1) "${((1 - t.gridWh / t.loadWh).coerceIn(0.0, 1.0) * 100).roundToInt()} %" else "–", m, info = "Share of the home's energy today that did not come from the grid (solar and battery).") },
            { m -> StatTile("Grid available", if (t.onlineMin > 0) fmtDuration(t.gridOnMin.toDouble()) else "–", m) },
            { m -> StatTile("Grid outages", "${t.outages}", m) },
            { m -> StatTile("From solar + battery", String.format(Locale.US, "%.2f units", maxOf(0.0, t.loadWh - t.gridWh) / 1000), m, hint = "home use not from the grid", tone = e.batt) },
        ))
    }
}

@Composable
private fun LiveChartCard(repo: Repository, e: EnergyColors) {
    val recent by repo.recent.collectAsStateWithLifecycle()
    var minutes by rememberSaveable { mutableStateOf(15) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val from = now - minutes * 60_000L
    val pts = recent.filter { it.t >= from - 5000 }
    val all = listOf(
        ChartSeries("Solar", e.solar, FloatArray(pts.size) { pts[it].pvW.toFloat() }, area = true),
        ChartSeries("Home", e.load, FloatArray(pts.size) { pts[it].loadW.toFloat() }),
        ChartSeries("Battery (+ in / − out)", e.batt, FloatArray(pts.size) { pts[it].battW.toFloat() }),
        ChartSeries("Grid (est.)", e.grid, FloatArray(pts.size) { pts[it].gridW.toFloat() }),
    )
    ChartCard("Live power", sub = "every reading from the inverter",
        action = {
            Segmented(minutes, listOf(5 to "5 min", 15 to "15 min"), { minutes = it })
            Spacer(Modifier.width(8.dp))
        },
        legend = { ChartLegend(all.map { it.name to it.color }, hidden) { n -> hidden = if (n in hidden) hidden - n else hidden + n } },
    ) { h ->
        LineChart(LongArray(pts.size) { pts[it].t }, all.filter { it.name !in hidden }, from, now, height = h,
            yFmt = { if (kotlin.math.abs(it) >= 1000) "${fmt1(it / 1000.0)}k" else "${it.roundToInt()}" }, valueFmt = { fmtW(it) },
            title = { com.solarmonitor.app.ui.hhmmss(pts[it].t) })
    }
}

/** (severity 0 info, 1 warning, 2 problem, -1 all-good) to text */
fun alertItems(d: Live, idleW: Int = Power.DEADBAND, noBatt: Boolean = false): List<Pair<Int, String>> {
    val out = mutableListOf<Pair<Int, String>>()
    if (!d.ok) out += 2 to "The inverter has not answered for a while (last error: ${d.err.ifEmpty { "none" }}). Check the cable to the inverter."
    if (d.mode == 'F') out += 2 to "Inverter is in FAULT mode"
    Decode.activeWarnings(d.warn, noBatt).forEach { out += (if (it in Decode.severe) 2 else 1) to (Decode.warnings[it] ?: "Warning $it") }
    if (d.ok && !noBatt && d.battPct <= 20 && d.battW < 0) out += 1 to "Battery is low (${d.battPct}%)"
    if (d.tempC >= 60) out += 1 to "Inverter is hot (${d.tempC} °C)"
    if (Power.weakSolar(d, idleW)) out += 1 to "Little sun right now (cloudy?) and the battery is powering the home (${fmtW(-d.battW)}, battery ${d.battPct}%). Turn the grid on to save the battery."
    else if (!d.gridOn) {
        val ph = Sun.phase()
        out += 0 to if (ph == Sun.Phase.Day) "Grid supply is off: running on solar and battery"
        else "Grid supply is off and the sun is ${if (ph == Sun.Phase.Night) "down" else "low"} (sunset ${hhmm(Sun.times().set)}): the battery is carrying the home"
    }
    return out
}

@Composable
fun AlertsCard(items: List<Pair<Int, String>>) {
    val e = LocalEnergy.current
    SectionCard("Alerts") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (items.isEmpty()) AlertRow(Icons.Rounded.CheckCircle, e.good, "All good: no warnings from the inverter")
            items.forEach { (sev, text) ->
                when (sev) {
                    2 -> AlertRow(Icons.Rounded.Error, e.crit, text)
                    1 -> AlertRow(Icons.Rounded.Warning, e.warn, text)
                    else -> AlertRow(Icons.Rounded.Info, MaterialTheme.colorScheme.onSurfaceVariant, text)
                }
            }
        }
    }
}

@Composable
private fun AlertRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

const val GRID_EST_INFO = "The inverter does not report grid power directly. The monitor works it out from the home load minus what solar and the battery supply, so treat it as a close estimate. Your IESCO meter is the final word."
private const val FLOW_INFO = "Dots move in the direction energy flows, faster and denser with more power. The label on the right says what is powering the home right now.\n\nBattery idle: at full charge the inverter often draws a little from the battery even when solar covers the home. Flows under the limit in Settings (100 W by default) are shown as idle."

/** The pill on the right of the Energy flow card: what is powering the home right now. */
@Composable
private fun SourcePill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}
