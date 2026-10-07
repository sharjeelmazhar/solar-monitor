package com.solarmonitor.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.Decode
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.ui.components.InfoRows
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.dayLabel
import com.solarmonitor.app.ui.fmtDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SystemScreen(repo: Repository, padding: PaddingValues) {
    val d by repo.live.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    val interval by repo.updateInterval.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        d?.takeIf { it.ever }?.let { item(key = "alerts") { AlertsCard(alertItems(it)) } }
        item(key = "inv") {
            val i = info
            val r = i?.rated
            val rows = buildList {
                if (r != null) {
                    add("Rated power" to "${r.outW} W / ${r.outVA} VA")
                    add("Battery system" to "${r.battV} V · ${Decode.battTypes.getOrElse(r.battType) { "type ${r.battType}" }}")
                    add("Output priority" to Decode.outPrio.getOrElse(r.outPrio) { "${r.outPrio}" })
                    add("Charger priority" to Decode.chgPrio.getOrElse(r.chgPrio) { "${r.chgPrio}" })
                    add("Bulk / float" to "${r.bulk} V / ${r.float} V")
                    add("Cut-off / recharge" to "${r.cutoff} V / ${r.recharge} V")
                    r.redischarge?.let { add("Back to battery at" to "$it V") }
                    add("Max charge current" to "${r.maxChg} A (grid ${r.maxAc} A)")
                    add("AC input range" to if (r.range == 1) "UPS (narrow)" else "Appliance (wide)")
                }
                if (i != null) {
                    if (i.qid.isNotEmpty()) add("Serial number" to i.qid)
                    if (i.qvfw.isNotEmpty()) add("Inverter firmware" to i.qvfw.removePrefix("VERFW:"))
                    Decode.enabledFlags(i.qflag)?.let { add("Enabled" to it) }
                }
            }
            SectionCard("Inverter", action = {
                IconButton(onClick = { scope.launch { repo.refreshInverter(); delay(4000); repo.refreshInfo() } }) { Icon(Icons.Rounded.Refresh, "Re-read inverter settings") }
            }) {
                if (rows.isEmpty()) Text("Not read yet") else InfoRows(rows)
            }
        }
        item(key = "dev") {
            val i = info
            val p = d
            SectionCard("Monitor device") {
                if (i == null) Text("Not connected") else InfoRows(listOf(
                    "Address" to "${i.ip} · ${i.host}.local",
                    "Wi-Fi" to "${i.ssid} · ${i.rssi} dBm (${when { i.rssi > -60 -> "excellent"; i.rssi > -70 -> "good"; i.rssi > -80 -> "fair"; else -> "weak" }})",
                    "Up for" to fmtDuration(i.uptime / 60.0),
                    "Clock" to if (i.timeOk) "synced" else "not set",
                    "History stored" to if (i.histFrom > 0) "since ${dayLabel(i.histFrom)}" else "starting today",
                    "Storage" to "${i.fsUsed / 1024} / ${i.fsTotal / 1024} KB",
                    "Readings" to (p?.let { "${it.okCount} ok · ${it.failCount} failed · ${it.crcErrors} CRC" } ?: "–"),
                    "Read cycle" to (p?.let { "${it.pollMs} ms" } ?: "–"),
                    "Updates in app" to if (interval > 0) "every ${"%.1f".format(interval / 1000.0)} s" else "–",
                    "Viewers" to "${i.clients}",
                    "Firmware" to "v${i.fw}",
                ))
                Row(Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${repo.prefs.value.host}/"))) }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
                        Text("  Open web dashboard")
                    }
                }
            }
        }
    }
}
