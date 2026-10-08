package com.solarmonitor.app.ui.screens

import com.solarmonitor.app.ui.components.Segmented
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.Conn
import com.solarmonitor.app.data.Discovery
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.notify.MonitorService
import com.solarmonitor.app.ui.components.ScreenList
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.components.full
import kotlinx.coroutines.launch

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(repo: Repository, padding: PaddingValues, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val s by repo.prefs.state.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    val conn by repo.conn.collectAsStateWithLifecycle()
    val bill by repo.bill.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var host by remember(s.host) { mutableStateOf(s.host) }
    var searching by remember { mutableStateOf(false) }

    val pm = ctx.getSystemService(PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    var notifAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifAllowed = it }
    val battLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    LaunchedEffect(Unit) { if (info == null) repo.refreshInfo(); if (bill == null) repo.loadBill() }

    ScreenList(padding) {
        item(key = "conn") {
            SectionCard("Solar monitor") {
                Text(
                    when (conn) { Conn.Live -> "Connected"; Conn.Connecting -> "Connecting…"; Conn.Offline -> "Not reachable"; Conn.Idle -> "Idle" } +
                        (info?.let { " · v${it.fw}" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(host, { host = it }, label = { Text("Address (IP or name)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        searching = true
                        scope.launch {
                            val ip = Discovery.find(ctx)
                            searching = false
                            if (ip != null) { host = ip; repo.clearCache(); repo.prefs.update { it.copy(host = ip) }; toast("Found monitor at $ip") }
                            else toast("Not found. Are you on the home Wi-Fi?")
                        }
                    }, enabled = !searching, modifier = Modifier.weight(1f)) {
                        if (searching) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp) else Text("Find automatically")
                    }
                    Button(onClick = { repo.clearCache(); repo.prefs.update { it.copy(host = host) }; toast("Saved") }, enabled = host.isNotBlank() && host != s.host, modifier = Modifier.weight(1f)) { Text("Save") }
                }
            }
        }
        item(key = "notif") {
            SectionCard("Notifications") {
                if (!notifAllowed) {
                    Text("Notifications are turned off for this app.", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Allow notifications") }
                }
                Toggle("Background monitoring", "Keeps a light connection so alerts arrive within seconds, even with the app closed", s.background) { on ->
                    repo.prefs.update { it.copy(background = on) }
                    if (on) MonitorService.start(ctx) else MonitorService.stop(ctx)
                }
                if (s.background && !unrestricted) {
                    Text("Samsung and other phones may stop background apps. Allow this app to run without battery restrictions so alerts keep working.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    FilledTonalButton(onClick = {
                        battLauncher.launch(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Allow unrestricted battery use") }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Toggle("Grid off / back on", "WAPDA power cuts and restores", s.alertGrid) { on -> repo.prefs.update { it.copy(alertGrid = on) } }
                Toggle("Battery low", "When discharging below ${s.battLowPct}%", s.alertBattLow) { on -> repo.prefs.update { it.copy(alertBattLow = on) } }
                if (s.alertBattLow) {
                    var v by remember(s.battLowPct) { mutableStateOf(s.battLowPct.toFloat()) }
                    Slider(v, { v = it }, valueRange = 10f..60f, steps = 9, onValueChangeFinished = { repo.prefs.update { it.copy(battLowPct = v.toInt()) } })
                }
                Toggle("Battery full", "When the battery reaches 100%", s.alertBattFull) { on -> repo.prefs.update { it.copy(alertBattFull = on) } }
                Toggle("Inverter problems", "Faults, warnings, inverter not answering", s.alertFault) { on -> repo.prefs.update { it.copy(alertFault = on) } }
                Toggle("Little sun, battery in use", "Daytime, grid off and the battery is running the home (cloudy?): suggests turning the grid on", s.alertWeakSolar) { on -> repo.prefs.update { it.copy(alertWeakSolar = on) } }
                Toggle("Monthly units", "At 150, 175 and 190 grid units, and early if the month is heading over 200", s.alertUnits) { on -> repo.prefs.update { it.copy(alertUnits = on) } }
                Text("Only one notification is shown at a time: the latest alert replaces the status line, and during a grid cut it shows a running timer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item(key = "about") {
            val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            Text("Solar Monitor app $v · all data stays on your home network", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(8.dp))
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/** Web System page: "Your system" (saved on the monitor, shared with the web dashboard). */
@Composable
fun YourSystemCard(repo: Repository, toast: (String) -> Unit) {
    val info by repo.info.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by remember(info?.name) { mutableStateOf(info?.name?.takeIf { it != "Solar" } ?: "") }
    var ah by remember(info?.battAh) { mutableStateOf(info?.battAh?.takeIf { it > 0 }?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    var saving by remember { mutableStateOf(false) }
    SectionCard("Your system", sub = "saved on the monitor, shared with the web dashboard") {
        OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, placeholder = { Text("Solar Monitor") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(ah, { ah = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Battery capacity (Ah)") }, placeholder = { Text("e.g. 200") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(0.5f).padding(top = 8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Button(onClick = {
            saving = true
            scope.launch {
                val ok = repo.saveDeviceSettings(name.ifBlank { "Solar" }, ah.toDoubleOrNull() ?: 0.0, info?.tariff ?: 0.0)
                saving = false
                toast(if (ok) "Saved" else "Could not reach the monitor")
            }
        }, enabled = !saving && info != null, modifier = Modifier.padding(top = 12.dp)) { Text(if (saving) "Saving…" else "Save") }
    }
}

/** Web System page: "Appearance" (theme, time format, battery idle limit, 3D core). Kept on this phone. */
@Composable
fun AppearanceCard(repo: Repository) {
    val s by repo.prefs.state.collectAsStateWithLifecycle()
    SectionCard("Appearance") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Theme", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Segmented(s.theme, listOf(0 to "System", 1 to "Light", 2 to "Dark"), { v -> repo.prefs.update { it.copy(theme = v) } })
        }
        Spacer(Modifier.padding(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Time format", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Segmented(s.hour12, listOf(true to "2:30 PM", false to "14:30"), { v -> repo.prefs.update { it.copy(hour12 = v) } })
        }
        Spacer(Modifier.padding(6.dp))
        Toggle("Ignore small battery flows", "At full charge the inverter often takes a little from the battery. Below the limit it shows as idle, not charging or discharging.", s.battIdleOn) { on -> repo.prefs.update { it.copy(battIdleOn = on) } }
        if (s.battIdleOn) {
            var w by remember(s.battIdleW) { mutableStateOf(s.battIdleW.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(w, { w = it }, valueRange = 20f..300f, steps = 27, onValueChangeFinished = { repo.prefs.update { it.copy(battIdleW = w.toInt()) } }, modifier = Modifier.weight(1f))
                Text("${w.toInt()} W", style = MaterialTheme.typography.labelLarge.merge(com.solarmonitor.app.ui.theme.NumberStyle), modifier = Modifier.padding(start = 12.dp))
            }
        }
        Toggle("3D energy core", "Animated background behind the flow diagram. Turn it off on slow phones.", s.fx3d) { on -> repo.prefs.update { it.copy(fx3d = on) } }
    }
}

/** Web System page: "Bill settings". */
@Composable
fun BillSettingsSection(repo: Repository, toast: (String) -> Unit) {
    val bill by repo.bill.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (bill == null) repo.loadBill() }
    BillSettingsCard(repo, bill, toast)
}
