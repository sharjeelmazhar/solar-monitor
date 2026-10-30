package com.solarmonitor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.data.WifiNet
import com.solarmonitor.app.data.WifiSetup
import com.solarmonitor.app.ui.theme.LocalEnergy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Step {
    data object Form : Step
    data object Joining : Step
    data class Joined(val ip: String) : Step
    data object Left : Step
}

/** Moves the monitor to another Wi-Fi from inside the app: pick a network it can see, its password, and the monitor's admin password. */
@Composable
fun WifiDialog(repo: Repository, onClose: () -> Unit) {
    var nets by remember { mutableStateOf<List<WifiNet>?>(null) }
    var scanTick by remember { mutableStateOf(0) }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<Step>(Step.Form) }
    val scope = rememberCoroutineScope()
    val host = repo.info.value?.host?.takeIf { it.isNotEmpty() } ?: "solar"

    LaunchedEffect(scanTick) {
        nets = null
        repeat(12) {
            val r = runCatching { WifiSetup.parseScan(String(repo.api.get("/api/scan"))) }.getOrNull()
            if (r != null) { nets = r; return@LaunchedEffect }
            delay(1500)
        }
        nets = emptyList()
    }

    fun connect() {
        val err = WifiSetup.formError(ssid, pass, open, admin)
        if (err != null) { msg = err; return }
        msg = ""
        scope.launch {
            val (code, _) = repo.api.postResult("/api/wifi", mapOf("ssid" to ssid, "pass" to pass), basicAuth = "admin" to admin)
            when (code) {
                in 200..299 -> {
                    step = Step.Joining
                    delay(4000)
                    repeat(10) {
                        val r = runCatching { WifiSetup.joined(String(repo.api.get("/api/wifistatus", 3000)), ssid) }.getOrNull()
                        if (r != null && r.first) {
                            repo.prefs.update { it.copy(host = r.second) }
                            step = Step.Joined(r.second)
                            return@launch
                        }
                        delay(3000)
                    }
                    // gone from this network, which is what a good move looks like too: the app finds it by name afterwards
                    repo.prefs.update { it.copy(host = "$host.local") }
                    step = Step.Left
                }
                401 -> msg = "Wrong admin password. Nothing was changed."
                0 -> msg = "Could not reach the monitor. Nothing was changed."
                else -> msg = "The monitor refused this. Nothing was changed."
            }
        }
    }

    val joining = step == Step.Joining
    AlertDialog(
        onDismissRequest = { if (!joining) onClose() },
        title = { Text(when (step) { Step.Form -> "Change Wi-Fi"; Step.Joining -> "Connecting…"; is Step.Joined -> "Connected"; Step.Left -> "The monitor has left this network" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = step) {
                    Step.Form -> {
                        Text("Networks the monitor can see", style = MaterialTheme.typography.labelLarge)
                        val list = nets
                        if (list == null) { Text("Looking for networks…", style = MaterialTheme.typography.bodyMedium); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        else if (list.isEmpty()) Text("No networks found. Move the monitor closer to the router and search again.", style = MaterialTheme.typography.bodyMedium)
                        else Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            list.forEach { n ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { ssid = n.ssid; open = n.open; pass = ""; msg = "" }
                                    .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(n.ssid, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                        color = if (n.ssid == ssid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Text(n.signal, style = MaterialTheme.typography.bodySmall, color = if (n.signal == "weak") LocalEnergy.current.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!n.open) Icon(Icons.Rounded.Lock, "Password needed", Modifier.padding(start = 6.dp), tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        TextButton(onClick = { scanTick++ }, enabled = nets != null) { Text("Search again") }
                        OutlinedTextField(ssid, { ssid = it; open = false }, label = { Text("Network name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pass, { pass = it }, label = { Text("Wi-Fi password") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !open,
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPass) "Hide password" else "Show password") } })
                        OutlinedTextField(admin, { admin = it }, label = { Text("Monitor admin password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        Text("Check the Wi-Fi password carefully. If it is wrong, the monitor drops off this network. After about 90 seconds it opens its own hotspot, SolarMonitor-…, so you can set it up again from next to the monitor.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (msg.isNotEmpty()) Text(msg, color = LocalEnergy.current.crit, style = MaterialTheme.typography.bodyMedium)
                    }
                    Step.Joining -> {
                        Text("Moving the monitor to “$ssid”. This takes up to 30 seconds.", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is Step.Joined -> Text("The monitor is on “$ssid” at ${s.ip}.", style = MaterialTheme.typography.bodyMedium)
                    Step.Left -> {
                        Text("Connect this phone to “$ssid”. The app finds the monitor there by itself, or open http://$host.local/.", style = MaterialTheme.typography.bodyMedium)
                        Text("If it doesn't appear within 2 minutes, the password was probably wrong: look for the hotspot SolarMonitor-… next to the monitor and set it up again.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                Step.Form -> Button(onClick = ::connect) { Text("Connect") }
                Step.Joining -> {}
                else -> Button(onClick = onClose) { Text("Done") }
            }
        },
        dismissButton = { if (step == Step.Form) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
