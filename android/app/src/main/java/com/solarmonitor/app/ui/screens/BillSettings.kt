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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.solarmonitor.app.data.BillConfig
import com.solarmonitor.app.data.PastBill
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.data.Slab
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.theme.LocalEnergy
import com.solarmonitor.app.ui.theme.NumberStyle
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.util.Locale

private fun n(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
private fun slabRows(l: List<Slab>) = l.map { listOf(it.upTo.toString(), n(it.rate), n(it.fixedPerKw)) }

private data class Field(val label: String, val hint: String?, val neg: Boolean = false)
private val FIELDS = listOf(
    Field("Sanctioned load (kW)", "LOAD on your bill"), Field("Meter reading day", "READING DATE (day of month)"),
    Field("Fuel adj. FPA (Rs/unit)", "the \"@\" rate next to FPA", true), Field("Quarterly adj. (Rs/unit)", "QTR. TARIFF ADJ ÷ units", true),
    Field("F.C. surcharge (Rs/unit)", "F.C SURCHARGE ÷ units"), Field("Other units / month", "use the monitor can't see"),
    Field("Sales tax (%)", null), Field("Electricity duty (%)", "ED@ on the bill"), Field("TV fee (Rs)", "0 if your bill has none"),
    Field("Reading time (hour 0-23)", "20 = 8 PM: the new month starts then"),
)

/** IESCO bill settings, stored on the monitor so the web dashboard and other phones use the same values. */
@Composable
fun BillSettingsCard(repo: Repository, saved: BillConfig?, toast: (String) -> Unit) {
    val c = saved ?: BillConfig()
    val scope = rememberCoroutineScope()
    var prot by remember(saved) { mutableStateOf(c.protected) }
    val f = remember(saved) { mutableStateListOf(n(c.kw), c.day.toString(), n(c.fpa), n(c.qta), n(c.fc), n(c.extra), n(c.gst), n(c.ed), n(c.ptv), c.hr.toString()) }
    val ps = remember(saved) { mutableStateListOf(*slabRows(c.ps).toTypedArray()) }
    val us = remember(saved) { mutableStateListOf(*slabRows(c.us).toTypedArray()) }
    var rates by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun draft(hist: List<PastBill> = c.hist): BillConfig {
        fun d(i: Int) = f[i].toDoubleOrNull()
        fun slabs(l: List<List<String>>) = l.map { r -> Slab(r[0].toDoubleOrNull()?.toInt() ?: -1, r[1].toDoubleOrNull() ?: -1.0, r[2].toDoubleOrNull() ?: -1.0) }
        val raw = BillConfig(prot, d(0) ?: c.kw, d(1)?.toInt() ?: c.day, slabs(ps), slabs(us), d(4) ?: c.fc, d(2) ?: 0.0, d(3) ?: 0.0, d(6) ?: c.gst, d(7) ?: c.ed, d(8) ?: 0.0, d(5) ?: 0.0, hist, d(9)?.toInt() ?: c.hr)
        return BillConfig.parse(raw.toJson())   // clamps ranges and rejects broken slab tables, like the web app
    }
    fun save(cfg: BillConfig) {
        saving = true
        scope.launch {
            val ok = repo.saveBill(cfg)
            saving = false
            toast(if (ok) "Saved to monitor" else "Couldn't reach the monitor")
        }
    }

    SectionCard("Bill (IESCO)", info = "Copy these from your latest IESCO bill; the estimate then follows the same steps as the bill. Saved on the monitor, so the web dashboard and other phones use the same values.") {
        BillGuideButton()
        Text("Your status (printed on the bill)", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf(true to "Protected", false to "Unprotected").forEachIndexed { i, (v, l) ->
                SegmentedButton(selected = prot == v, onClick = { prot = v }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
            }
        }
        Text("Protected = every one of the last 6 months at or under 200 units.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        FIELDS.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { j, fd ->
                    val i = row * 2 + j
                    OutlinedTextField(f[i], { v -> f[i] = v.filter { it.isDigit() || it == '.' || (fd.neg && it == '-') } },
                        label = { Text(fd.label) }, supportingText = fd.hint?.let { { Text(it) } },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

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

        Button(onClick = { save(draft()) }, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(if (saving) "Saving…" else "Save bill settings") }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        PastBills(c.hist) { save(draft(it)) }
    }
}

/** Real bills: their units decide protected status and the fuel adjustment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastBills(bills: List<PastBill>, onChange: (List<PastBill>) -> Unit) {
    val e = LocalEnergy.current
    val months = remember { val now = YearMonth.now(); List(24) { now.minusMonths(it.toLong()) } }
    var month by remember { mutableStateOf(months[0]) }
    var open by remember { mutableStateOf(false) }
    var units by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    Text("Your bills", style = MaterialTheme.typography.titleSmall)
    BillGuideButton()
    Text("Add units and amount from each bill (the table on the bill lists the last 12 months). Used for protected status and the fuel adjustment.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(monthName(month.year * 100 + month.monthValue, "MMMM yyyy"), {}, readOnly = true, label = { Text("Bill month") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            months.forEach { m -> DropdownMenuItem(text = { Text(monthName(m.year * 100 + m.monthValue, "MMMM yyyy")) }, onClick = { month = m; open = false }) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(units, { v -> units = v.filter { it.isDigit() } }, label = { Text("Units") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(amount, { v -> amount = v.filter { it.isDigit() } }, label = { Text("Amount Rs") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        FilledTonalIconButton(onClick = {
            val ym = month.year * 100 + month.monthValue
            onChange((bills.filter { it.month != ym } + PastBill(ym, units.toInt(), amount.toIntOrNull() ?: 0)).sortedBy { it.month })
            units = ""; amount = ""
        }, enabled = units.isNotEmpty()) { Icon(Icons.Rounded.Add, "Add bill") }
    }
    if (bills.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
            listOf("Month" to 1.4f, "Units" to 1f, "Amount" to 1.3f, "Rs/unit" to 1f).forEach { (l, w) ->
                Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w),
                    textAlign = if (l == "Month") TextAlign.Start else TextAlign.End)
            }
            Spacer(Modifier.width(52.dp))
        }
        bills.reversed().forEach { b ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val st = MaterialTheme.typography.bodyMedium.merge(NumberStyle)
                Text(monthName(b.month), style = st, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f))
                Text("${b.units}", style = st, fontWeight = FontWeight.Bold, color = if (b.units > 200) e.crit else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(if (b.amount > 0) rs(b.amount.toDouble()) else "–", style = st, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f))
                Text(if (b.amount > 0 && b.units > 0) String.format(Locale.US, "%.1f", b.amount.toDouble() / b.units) else "–", style = st,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { onChange(bills.filter { it.month != b.month }) }, modifier = Modifier.width(40.dp)) { Icon(Icons.Rounded.Delete, "Remove ${monthName(b.month)}") }
            }
        }
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
