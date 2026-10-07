package com.solarmonitor.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.solarmonitor.app.data.BillConfig
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.data.Slab
import com.solarmonitor.app.ui.components.SectionCard
import kotlinx.coroutines.launch

private fun n(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
private fun slabRows(l: List<Slab>) = l.map { listOf(it.upTo.toString(), n(it.rate), n(it.fixedPerKw)) }

/** IESCO bill settings, stored on the monitor so the web dashboard and other phones use the same values. */
@Composable
fun BillSettingsCard(repo: Repository, saved: BillConfig?, toast: (String) -> Unit) {
    val c = saved ?: BillConfig()
    val scope = rememberCoroutineScope()
    var prot by remember(saved) { mutableStateOf(c.protected) }
    val f = remember(saved) {
        mutableStateListOf(n(c.kw), c.day.toString(), n(c.fpa), n(c.qta), n(c.extra), n(c.ptv), n(c.gst), n(c.ed))
    }
    val ps = remember(saved) { mutableStateListOf(*slabRows(c.ps).toTypedArray()) }
    val us = remember(saved) { mutableStateListOf(*slabRows(c.us).toTypedArray()) }
    var rates by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    SectionCard("Bill (IESCO)") {
        Text("Saved on the monitor, shared with the web dashboard.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text("Your status (printed on the bill)", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf(true to "Protected", false to "Unprotected").forEachIndexed { i, (v, l) ->
                SegmentedButton(selected = prot == v, onClick = { prot = v }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
            }
        }
        Text("Protected = every one of the last 6 months at or under 200 units.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        val labels = listOf(
            "Sanctioned load (kW)" to false, "Meter reading day" to false, "Fuel adj. FPA (Rs/unit)" to true, "Quarterly adj. QTA (Rs/unit)" to true,
            "Other units / month" to false, "PTV fee (Rs)" to false, "Sales tax (%)" to false, "Electricity duty (%)" to false,
        )
        labels.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { j, (label, neg) ->
                    val i = row * 2 + j
                    OutlinedTextField(f[i], { v -> f[i] = v.filter { it.isDigit() || it == '.' || (neg && it == '-') } }, label = { Text(label, maxLines = 1) },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        }
        Text("Other units: grid use the monitor can't see (e.g. another inverter or loads wired straight to the meter).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

        TextButton(onClick = { rates = !rates }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(if (rates) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            Spacer(Modifier.width(4.dp))
            Text("Slab rates (NEPRA, Feb 2026)")
        }
        AnimatedVisibility(rates) {
            Column {
                SlabTable("Protected", ps)
                Spacer(Modifier.height(10.dp))
                SlabTable("Unprotected", us)
                OutlinedButton(onClick = {
                    ps.clear(); ps.addAll(slabRows(BillConfig.DEFAULT_PS)); us.clear(); us.addAll(slabRows(BillConfig.DEFAULT_US))
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Reset rates to Feb 2026") }
            }
        }

        Button(onClick = {
            saving = true
            fun d(i: Int) = f[i].toDoubleOrNull()
            fun slabs(l: List<List<String>>) = l.map { r -> Slab(r[0].toDoubleOrNull()?.toInt() ?: -1, r[1].toDoubleOrNull() ?: -1.0, r[2].toDoubleOrNull() ?: -1.0) }
            val draft = BillConfig(prot, d(0) ?: c.kw, d(1)?.toInt() ?: c.day, slabs(ps), slabs(us), d(2) ?: 0.0, d(3) ?: 0.0, d(6) ?: c.gst, d(7) ?: c.ed, d(5) ?: c.ptv, d(4) ?: 0.0)
            scope.launch {
                // parse() clamps ranges and rejects broken slab tables, exactly like the web app
                val ok = repo.saveBill(BillConfig.parse(draft.toJson()))
                saving = false
                toast(if (ok) "Saved to monitor" else "Couldn't reach the monitor")
            }
        }, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(if (saving) "Saving…" else "Save bill settings") }
    }
}

@Composable
private fun SlabTable(title: String, rows: SnapshotStateList<List<String>>) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        listOf("Up to units (0 = above)", "Rs / unit", "Fixed Rs / kW").forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
    }
    rows.forEachIndexed { i, r ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            r.forEachIndexed { j, v ->
                OutlinedTextField(v, { nv -> rows[i] = r.toMutableList().also { it[j] = nv.filter { c -> c.isDigit() || c == '.' } } },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.End),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
    }
}
