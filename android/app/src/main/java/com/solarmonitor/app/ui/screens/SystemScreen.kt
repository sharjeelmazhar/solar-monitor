package com.solarmonitor.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.solarmonitor.app.ui.components.ScreenList
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.components.full
import com.solarmonitor.app.ui.dayLabel
import com.solarmonitor.app.ui.fmtDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SystemScreen(repo: Repository, padding: PaddingValues, toast: (String) -> Unit) {
    val d by repo.live.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    val interval by repo.updateInterval.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    ScreenList(padding) {
        d?.takeIf { it.ever }?.let { full("alerts") { AlertsCard(alertItems(it)) } }
        item(key = "inv") { androidx.compose.foundation.layout.Column { InverterSettingsCard(repo) } }
        item(key = "you") { YourSystemCard(repo, toast) }
        item(key = "look") { AppearanceCard(repo) }
        item(key = "dev") {
            val i = info
            val p = d
            val appV = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
            SectionCard("Monitor device", sub = (if (i != null) "firmware v${i.fw}" else "not connected") + " · app v$appV") {
                if (i != null) InfoRows(listOf(
                    "Address" to "${i.ip} · ${i.host}.local",
                    "Wi-Fi" to "${i.ssid} · ${i.rssi} dBm (${when { i.rssi > -60 -> "excellent"; i.rssi > -70 -> "good"; i.rssi > -80 -> "fair"; else -> "weak" }})",
                    "Up for" to fmtDuration(i.uptime / 60.0),
                    "Clock" to if (i.timeOk) "synced" else "not set",
                    "History stored since" to if (i.histFrom > 0) dayLabel(i.histFrom) else "today",
                    "Storage" to "${i.fsUsed / 1024} / ${i.fsTotal / 1024} KB",
                    "Free memory" to "${i.heap / 1024} KB",
                    "Readings" to (p?.let { "${"%,d".format(it.okCount)} ok · ${it.failCount} failed · ${it.crcErrors} CRC" } ?: "–"),
                    "Read cycle" to (p?.let { "${it.pollMs} ms" } ?: "–"),
                    "Open dashboards" to "${i.clients}",
                ))
                // the same three links as the web card; they open in the phone's browser
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Classic dashboard" to "/classic", "Firmware update" to "/update", "Change Wi-Fi" to "/setup").forEach { (label, path) ->
                        FilledTonalButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${repo.prefs.value.host}$path"))) } }) {
                            Text(label); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 6.dp).size(14.dp))
                        }
                    }
                }
            }
        }
        full("bill") { BillSettingsSection(repo, toast) }
    }
}
