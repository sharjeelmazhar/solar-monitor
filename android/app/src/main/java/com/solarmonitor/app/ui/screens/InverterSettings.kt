package com.solarmonitor.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.Choice
import com.solarmonitor.app.data.Decode
import com.solarmonitor.app.data.InvSet
import com.solarmonitor.app.data.LogEntry
import com.solarmonitor.app.data.Rated
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.data.SetDef
import com.solarmonitor.app.data.SetKind
import com.solarmonitor.app.data.SetResult
import com.solarmonitor.app.ui.theme.NumberStyle
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.dayLabel
import com.solarmonitor.app.ui.hhmm
import com.solarmonitor.app.ui.theme.LocalEnergy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// Same card as the web dashboard's Inverter settings (web/src/components/InverterSettings.tsx): read-only by default,
// edit mode behind the settings password, every change confirmed (old -> new), read back by the monitor and logged.

private object EditMode {
    var pw = ""
    var until = 0L
    val on get() = System.currentTimeMillis() < until
}
private const val EDIT_MS = 10 * 60_000L

@Composable
private fun SetRow(k: String, v: String, help: String? = null, onEdit: (() -> Unit)? = null, first: Boolean = false) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(v, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
            if (onEdit != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Edit, "Change $k", Modifier.size(16.dp)) }
            }
        }
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun InverterSettingsCard(repo: Repository) {
    val info by repo.info.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val e = LocalEnergy.current
    var busy by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(EditMode.on) }
    var unlock by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SetDef?>(null) }
    var probe by remember { mutableStateOf(false) }
    var logKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(edit) {
        if (edit) { delay((EditMode.until - System.currentTimeMillis()).coerceAtLeast(0)); edit = false; EditMode.pw = ""; EditMode.until = 0 }
    }

    val i = info
    val r = i?.rated
    val chg = i?.chgCur ?: ""
    val ac = i?.acCur ?: ""
    val canEdit = edit && r != null && i.proto != "PI18"
    fun v(d: SetDef) = if (r == null) "–" else InvSet.label(d, InvSet.current(d, r, i.qflag), r, chg, ac)
    fun ed(key: String): (() -> Unit)? = if (canEdit) ({ editing = InvSet.def(key) }) else null

    SectionCard("Inverter settings", action = {
        IconButton(onClick = { scope.launch { busy = true; repo.refreshInverter(); delay(4000); repo.refreshInfo(); busy = false } }, enabled = !busy) {
            Icon(Icons.Rounded.Refresh, "Read settings from inverter again")
        }
        IconButton(onClick = { if (edit) { edit = false; EditMode.pw = ""; EditMode.until = 0 } else unlock = true }) {
            if (edit) Icon(Icons.Rounded.LockOpen, "Turn off edit mode", tint = e.warn) else Icon(Icons.Rounded.Edit, "Turn on edit mode")
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Icon(if (edit) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null, Modifier.size(13.dp), tint = if (edit) e.warn else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(4.dp))
            Text(if (edit) "edit mode on · changes go to the inverter" else "read-only", style = MaterialTheme.typography.labelMedium,
                color = if (edit) e.warn else MaterialTheme.colorScheme.outline)
        }
        if (r == null) {
            Text(if (i?.proto == "PI18") "This inverter speaks PI18: live data works, settings are not shown yet." else "Not read yet.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SetRow("Output priority", v(InvSet.def("outPrio")), Decode.outPrioHelp.getOrNull(r.outPrio), ed("outPrio"), first = true)
            SetRow("Charger priority", v(InvSet.def("chgPrio")), Decode.chgPrioHelp.getOrNull(r.chgPrio), ed("chgPrio"))
            SetRow("Battery", "${r.battV.fmt1()} V · ${Decode.battTypes.getOrElse(r.battType) { "type ${r.battType}" }}",
                if (r.battType == 2) "User-defined type: the inverter estimates battery % from voltage, so it can read 100 % while discharging lightly." else null)
            if (canEdit) {
                SetRow("Bulk charge", v(InvSet.def("bulk")), onEdit = ed("bulk"))
                SetRow("Float charge", v(InvSet.def("float")), onEdit = ed("float"))
            } else SetRow("Bulk / float charge", "${r.bulk.fmt1()} V / ${r.float.fmt1()} V", "Bulk: voltage the charger pushes up to. Float: voltage it holds once full.")
            SetRow("Low cut-off", v(InvSet.def("cutoff")), "Below this the inverter switches the battery off to protect it.", ed("cutoff"))
            if (canEdit) {
                SetRow("Back to grid at", v(InvSet.def("recharge")), onEdit = ed("recharge"))
                SetRow("Back to battery at", v(InvSet.def("redischarge")), onEdit = ed("redischarge"))
            } else SetRow("Back to grid / back to battery",
                "${r.recharge.fmt1()} V / ${when (r.redischarge) { null -> "–"; 0.0 -> "full"; else -> r.redischarge.fmt1() + " V" }}",
                "Battery voltage at which the inverter switches the home to the grid, and back to battery after recharging.")
            if (canEdit) {
                SetRow("Max charge current", v(InvSet.def("maxChg")), onEdit = ed("maxChg"))
                SetRow("Max grid charge current", v(InvSet.def("maxAc")), onEdit = ed("maxAc"))
            } else SetRow("Max charge current", "${r.maxChg} A (from grid ${r.maxAc} A)")
            SetRow("AC input range", v(InvSet.def("range")),
                if (r.range == 1) "Switches to battery quickly; protects computers." else "Tolerates wider grid voltage; fine for most homes.", ed("range"))
            SetRow("Rated power", "${r.outW} W / ${r.outVA} VA")
            if (i.qid.isNotEmpty()) SetRow("Serial number", i.qid)
            if (i.qvfw.isNotEmpty()) SetRow("Inverter firmware", i.qvfw.removePrefix("VERFW:"))
            if (i.proto.isNotEmpty()) SetRow("Protocol", i.proto)
            if (!canEdit) Decode.enabledFlags(i.qflag)?.let { SetRow("Enabled features", it) }
            if (canEdit) InvSet.all.filter { it.kind == SetKind.Flag }.forEach { d -> SetRow(d.label, v(d), onEdit = { editing = d }) }
        }
        if (edit) {
            Spacer(Modifier.size(12.dp))
            FilledTonalButton(onClick = { probe = true }) { Icon(Icons.Rounded.Radar, null, Modifier.size(18.dp)); Text("  Detect inverter (read-only probe)") }
        }
    }

    if (unlock) UnlockDialog(repo, onClose = { unlock = false }) { pw ->
        EditMode.pw = pw; EditMode.until = System.currentTimeMillis() + EDIT_MS; edit = true; unlock = false
    }
    val ed = editing
    if (ed != null && r != null && i != null) ChangeDialog(repo, ed, r, chg, ac, i.qflag) { editing = null; logKey++ }
    if (probe) ProbeDialog(repo) { probe = false }
    // the change log sits right under this card (same as the web)
    ChangeLog(repo, logKey)
}

private fun Double.fmt1() = "%.1f".format(this)

@Composable
private fun Warning(text: String) {
    val e = LocalEnergy.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(e.warn.copy(alpha = 0.12f))
        .border(1.dp, e.warn.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Icon(Icons.Rounded.WarningAmber, null, tint = e.warn, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UnlockDialog(repo: Repository, onClose: () -> Unit, onUnlocked: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Turn on edit mode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Warning("Changes are sent straight to the inverter. A wrong battery voltage can damage the battery or switch the home off. " +
                    "Only change what you understand, and use your battery maker's values. Every change is checked, read back and logged.")
                OutlinedTextField(pw, { pw = it }, label = { Text("Settings password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg, color = LocalEnergy.current.crit, style = MaterialTheme.typography.bodyMedium)
                Text("Edit mode turns itself off after 10 minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            Button(enabled = pw.isNotEmpty() && !busy, onClick = {
                scope.launch {
                    busy = true
                    val r = InvSet.checkPassword(repo.api, pw)
                    busy = false
                    if (r == "ok") onUnlocked(pw) else msg = if (r == "wrong") "Wrong password" else "Could not reach the monitor"
                }
            }) { Text("Turn on") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun ChangeDialog(repo: Repository, d: SetDef, r: Rated, chg: String, ac: String, qflag: String, onClose: () -> Unit) {
    val choices = remember(d, r, chg, ac) { InvSet.choices(d, r, chg, ac) ?: emptyList() }
    val cur = InvSet.current(d, r, qflag)
    var pick by remember { mutableStateOf(cur ?: choices.firstOrNull()?.value) }
    var step by remember { mutableStateOf("pick") }
    var result by remember { mutableStateOf<SetResult?>(null) }
    val scope = rememberCoroutineScope()
    val e = LocalEnergy.current
    fun label(v: Double?) = InvSet.label(d, v, r, chg, ac)
    val changed = pick != null && (cur == null || abs(pick!! - cur) > 0.001)

    AlertDialog(
        onDismissRequest = { if (step != "sending") onClose() },
        title = { Text(d.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (step) {
                    "pick" -> {
                        Text(d.help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("Now", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(label(cur), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.Medium)
                        }
                        if (choices.isEmpty()) Text("The allowed values have not been read from the inverter yet. Press refresh and try again.", color = e.crit)
                        else if (d.kind == SetKind.Volts) VoltPicker(choices, pick) { pick = it }
                        else choices.forEach { c ->
                            val on = pick != null && abs(pick!! - c.value) < 0.001
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .border(BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
                                .background(if (on) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(role = Role.RadioButton) { pick = c.value }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Row {
                                    Text(c.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    if (cur != null && abs(cur - c.value) < 0.001) Text("current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                c.help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                    }
                    "confirm" -> {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                Text(label(cur), style = MaterialTheme.typography.titleMedium.merge(NumberStyle), fontWeight = FontWeight.SemiBold)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.outline)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("New", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                Text(label(pick), style = MaterialTheme.typography.titleMedium.merge(NumberStyle), fontWeight = FontWeight.SemiBold, color = e.load)
                            }
                        }
                        Warning("This is sent to the inverter now. The monitor reads the setting back afterwards to make sure it took.")
                    }
                    "sending" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text("Sending and reading back…")
                    }
                    else -> result?.let { res ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background((if (res.ok) e.good else e.crit).copy(alpha = 0.15f)).padding(16.dp)) {
                            Icon(if (res.ok) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = if (res.ok) e.good else e.crit)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(if (res.ok) "${d.label} is now ${label(pick)}" else "Not changed", fontWeight = FontWeight.Medium)
                                Text(res.msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                "pick" -> Button(enabled = changed, onClick = { step = "confirm" }) { Text("Review change") }
                "confirm" -> Button(onClick = {
                    step = "sending"
                    scope.launch { result = InvSet.send(repo.api, d, pick!!, EditMode.pw); repo.refreshInfo(); step = "done" }
                }) { Text("Send to inverter") }
                "done" -> Button(onClick = onClose) { Text("Done") }
            }
        },
        dismissButton = {
            when (step) {
                "pick" -> TextButton(onClick = onClose) { Text("Cancel") }
                "confirm" -> TextButton(onClick = { step = "pick" }) { Text("Back") }
            }
        },
    )
}

@Composable
private fun VoltPicker(choices: List<Choice>, value: Double?, onChange: (Double) -> Unit) {
    val volts = choices.filter { it.value > 0 }
    val hasFull = choices.any { it.value == 0.0 }
    val full = value == 0.0
    var i = volts.indexOfFirst { value != null && abs(it.value - value) < 0.001 }
    if (i < 0) i = 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hasFull) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onChange(0.0) }, Modifier.weight(1f), border = if (full) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface) else null) { Text("Full battery") }
            FilledTonalButton(onClick = { onChange(volts[i].value) }, Modifier.weight(1f), border = if (!full) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface) else null) { Text("A voltage") }
        }
        if (!full && volts.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onChange(volts[(i - 1).coerceAtLeast(0)].value) }, enabled = i > 0) { Icon(Icons.Rounded.Remove, "Lower") }
                Text(volts[i].label, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = NumberStyle)
                FilledTonalIconButton(onClick = { onChange(volts[(i + 1).coerceAtMost(volts.size - 1)].value) }, enabled = i < volts.size - 1) { Icon(Icons.Rounded.Add, "Higher") }
            }
            if (volts.size > 1) Slider(i.toFloat(), { onChange(volts[it.toInt().coerceIn(0, volts.size - 1)].value) }, valueRange = 0f..(volts.size - 1).toFloat(),
                steps = (volts.size - 2).coerceAtLeast(0))
            Row {
                Text(volts.first().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text("allowed range", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(volts.last().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ChangeLog(repo: Repository, key: Int) {
    var log by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    LaunchedEffect(key) { log = InvSet.log(repo.api) }
    if (log.isEmpty()) return
    val e = LocalEnergy.current
    Spacer(Modifier.size(14.dp))
    SectionCard("Settings change log") {
        Text("every change sent to the inverter, newest first", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 6.dp))
        log.take(20).forEachIndexed { idx, it ->
            if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${InvSet.logLabel(it.k)}  ${it.o} → ${it.n}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    val day = java.time.Instant.ofEpochSecond(it.t).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    val ymd = day.year * 10000 + day.monthValue * 100 + day.dayOfMonth
                    Text((if (it.t > 0) "${dayLabel(ymd)} ${hhmm(it.t * 1000)}" else "") + " · ${it.by}" + if (it.m.isNotEmpty()) " · ${it.m}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                val ok = it.r == "ok"
                Box(Modifier.clip(CircleShape).background((if (ok) e.good else e.crit).copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(if (ok) "done" else "failed", color = if (ok) e.good else e.crit, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ProbeDialog(repo: Repository, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("idle") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { InvSet.readProbe(repo.api).let { if (it.startsWith("state: done")) { text = it; state = "done" } } }
    AlertDialog(
        onDismissRequest = { if (state != "running") onClose() },
        title = { Text("Detect inverter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Asks the inverter read-only questions in every protocol the monitor knows (PI30, PI18 and Modbus), to learn how a new " +
                    "inverter brand talks. Nothing is changed. Live readings pause for about half a minute.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state == "running") Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Probing…")
                }
                if (state == "error") Text("The probe did not finish. Check the connection and try again.", color = LocalEnergy.current.crit)
                if (text.isNotEmpty()) Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        },
        confirmButton = {
            Button(enabled = state != "running", onClick = {
                scope.launch {
                    state = "running"; text = ""
                    if (!InvSet.startProbe(repo.api, EditMode.pw)) { state = "error"; return@launch }
                    val until = System.currentTimeMillis() + 90_000
                    while (System.currentTimeMillis() < until) {
                        delay(2000)
                        val t = InvSet.readProbe(repo.api)
                        if (t.startsWith("state: done")) { text = t; state = "done"; return@launch }
                    }
                    state = "error"
                }
            }) { Icon(Icons.Rounded.Radar, null, Modifier.size(18.dp)); Text("  Run probe") }
        },
        dismissButton = {
            Row {
                if (text.isNotEmpty()) TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("probe", text))
                }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Text(" Copy") }
                TextButton(onClick = onClose, enabled = state != "running") { Text("Close") }
            }
        },
    )
}

