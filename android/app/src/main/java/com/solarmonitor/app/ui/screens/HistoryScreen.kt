package com.solarmonitor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.DayRec
import com.solarmonitor.app.data.MinRec
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.ui.components.BarChart
import com.solarmonitor.app.ui.components.ChartLegend
import com.solarmonitor.app.ui.components.ChartSeries
import com.solarmonitor.app.ui.components.Grid
import com.solarmonitor.app.ui.components.GridStrip
import com.solarmonitor.app.ui.components.LineChart
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.components.StatTile
import com.solarmonitor.app.ui.dateOf
import com.solarmonitor.app.ui.dayLabel
import com.solarmonitor.app.ui.dayStartMs
import com.solarmonitor.app.ui.fmt1
import com.solarmonitor.app.ui.fmt2
import com.solarmonitor.app.ui.fmtDuration
import com.solarmonitor.app.ui.fmtW
import com.solarmonitor.app.ui.fmtWh
import com.solarmonitor.app.ui.hhmm
import com.solarmonitor.app.ui.theme.LocalEnergy
import com.solarmonitor.app.ui.todayYmd
import com.solarmonitor.app.ui.ymd
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(repo: Repository, padding: PaddingValues) {
    val e = LocalEnergy.current
    val live by repo.live.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    val today = live?.today?.date?.takeIf { it > 0 } ?: todayYmd()
    var date by rememberSaveable { mutableIntStateOf(today) }
    var recs by remember { mutableStateOf<List<MinRec>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var picker by remember { mutableStateOf(false) }
    val histFrom = info?.histFrom?.takeIf { it > 0 } ?: today

    LaunchedEffect(date) {
        recs = null; failed = false
        while (true) {
            val r = repo.day(date, date == today)
            if (r != null) { recs = r; failed = false } else if (recs == null) failed = true
            if (date != today) break
            delay(60_000)   // today keeps growing
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "nav") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { date = ymd(dateOf(date).minusDays(1)) }, enabled = date > histFrom) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
                FilledTonalButton(onClick = { picker = true }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.CalendarMonth, null)
                    Spacer(Modifier.padding(4.dp))
                    Text(if (date == today) "Today · ${dayLabel(date)}" else dayLabel(date))
                }
                FilledTonalIconButton(onClick = { date = ymd(dateOf(date).plusDays(1)) }, enabled = date < today) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day") }
            }
        }
        val r = recs
        if (r == null) {
            item(key = "load") { SectionCard(null) { if (failed) Text("Couldn't load this day. Check that you're on the home Wi-Fi.") else LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            return@LazyColumn
        }
        if (r.isEmpty()) {
            item(key = "empty") { SectionCard(null) { Text("No history recorded for ${dayLabel(date)}.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            return@LazyColumn
        }
        val d0 = dayStartMs(date)
        val xs = LongArray(r.size) { r[it].t }
        item(key = "power") {
            val all = listOf(
                ChartSeries("Solar", e.solar, FloatArray(r.size) { r[it].pvW.toFloat() }, area = true),
                ChartSeries("Home", e.load, FloatArray(r.size) { r[it].loadW.toFloat() }),
                ChartSeries("Battery", e.batt, FloatArray(r.size) { r[it].battW.toFloat() }),
                ChartSeries("Grid", e.grid, FloatArray(r.size) { r[it].gridW.toFloat() }),
            )
            SectionCard("Power") {
                ChartLegend(all.map { it.name to it.color }, hidden) { n -> hidden = if (n in hidden) hidden - n else hidden + n }
                Spacer(Modifier.height(6.dp))
                LineChart(xs, all.filter { it.name !in hidden }, d0, d0 + 86_400_000, height = 240.dp, gapMs = 5 * 60_000,
                    yFmt = { if (kotlin.math.abs(it) >= 1000) "${fmt1(it / 1000.0)}k" else "${it.roundToInt()}" }, valueFmt = { fmtW(it) })
            }
        }
        item(key = "soc") {
            SectionCard("Battery charge", action = { Text("${r.minOf { it.battPct }}–${r.maxOf { it.battPct }}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                LineChart(xs, listOf(ChartSeries("Battery", e.batt, FloatArray(r.size) { r[it].battPct.toFloat() }, area = true)), d0, d0 + 86_400_000,
                    height = 130.dp, yMin = 0f, yMax = 100f, gapMs = 5 * 60_000, yFmt = { "${it.roundToInt()}%" }, valueFmt = { "${it.roundToInt()}%" },
                    title = { "${hhmm(xs[it])} · ${fmt2(r[it].battV)} V" })
            }
        }
        item(key = "grid") {
            val on = r.count { it.gridOn }
            SectionCard("Grid availability", action = { Text("${fmtDuration(on.toDouble())} of ${fmtDuration(r.size.toDouble())}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                GridStrip(r, d0, e.grid)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEachIndexed { i, s ->
                        Text(s, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                            textAlign = if (i == 0) androidx.compose.ui.text.style.TextAlign.Start else if (i == 4) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        item(key = "totals") {
            val pv = r.sumOf { it.pvW } / 60.0; val load = r.sumOf { it.loadW } / 60.0; val grid = r.sumOf { it.gridW } / 60.0
            SectionCard("Day totals") {
                Grid(2, listOf(
                    { m -> StatTile("Solar", fmtWh(pv), m) }, { m -> StatTile("Home", fmtWh(load), m) },
                    { m -> StatTile("Grid (est.)", fmtWh(grid), m) }, { m -> StatTile("Peak solar", fmtW(r.maxOf { it.pvW }), m) },
                    { m -> StatTile("Peak load", fmtW(r.maxOf { it.loadW }), m) }, { m -> StatTile("Max inverter temp", "${r.maxOf { it.tempC }} °C", m) },
                ))
            }
        }
    }

    if (picker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dateOf(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = ymd(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate())
                    return d in histFrom..today
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = ymd(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; picker = false }) { Text("Show") } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
fun EnergyScreen(repo: Repository, padding: PaddingValues) {
    val e = LocalEnergy.current
    val live by repo.live.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    var days by remember { mutableStateOf<List<DayRec>?>(null) }
    var range by rememberSaveable { mutableIntStateOf(7) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { while (true) { repo.days()?.let { days = it }; delay(5 * 60_000) } }

    val t = live?.today
    val map = HashMap<Int, DayRec>()
    days?.forEach { map[it.date] = it }
    if (t != null && t.date > 0) map[t.date] = DayRec(t.date, t.pvWh.toFloat(), t.loadWh.toFloat(), t.gridWh.toFloat(), t.chgWh.toFloat(), t.disWh.toFloat(),
        t.pvPeak, t.loadPeak, t.gridOnMin, t.onlineMin, 0, 0, 0, t.outages)
    val end = t?.date?.takeIf { it > 0 }?.let { dateOf(it) } ?: LocalDate.now()
    var keys = (range - 1 downTo 0).map { ymd(end.minusDays(it.toLong())) }
    if (range > 31) keys.indexOfFirst { it in map }.takeIf { it > 0 }?.let { keys = keys.drop(it) }
    val rows = keys.map { map[it] }
    val fmtLabel = DateTimeFormatter.ofPattern(if (range > 31) "d MMM" else "EEE d")

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "range") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(7 to "7 days", 30 to "30 days", 365 to "Year").forEachIndexed { i, (n, l) ->
                    SegmentedButton(selected = range == n, onClick = { range = n }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
        }
        item(key = "bars") {
            val all = listOf(
                ChartSeries("Solar", e.solar, FloatArray(rows.size) { rows[it]?.pvWh ?: Float.NaN }),
                ChartSeries("Home", e.load, FloatArray(rows.size) { rows[it]?.loadWh ?: Float.NaN }),
                ChartSeries("Grid", e.grid, FloatArray(rows.size) { rows[it]?.gridWh ?: Float.NaN }),
            )
            SectionCard("Energy per day") {
                ChartLegend(all.map { it.name to it.color }, hidden) { n -> hidden = if (n in hidden) hidden - n else hidden + n }
                Spacer(Modifier.height(6.dp))
                if (days == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                BarChart(keys.map { dateOf(it).format(fmtLabel) }, all.filter { it.name !in hidden },
                    yFmt = { if (it == 0f) "0" else if (it >= 1000) "${fmt1(it / 1000.0)} kWh" else "${it.roundToInt()} Wh" },
                    valueFmt = { fmtWh(it) }, title = { dayLabel(keys[it]) })
            }
        }
        item(key = "totals") {
            val have = rows.filterNotNull()
            val pv = have.sumOf { it.pvWh.toDouble() }; val load = have.sumOf { it.loadWh.toDouble() }; val grid = have.sumOf { it.gridWh.toDouble() }
            val on = have.sumOf { it.gridOnMin }; val mins = have.sumOf { it.onlineMin }; val outages = have.sumOf { it.outages }
            val tariff = info?.tariff ?: 0.0
            SectionCard("${have.size} day${if (have.size == 1) "" else "s"} total") {
                Grid(2, listOf(
                    { m -> StatTile("Solar produced", fmtWh(pv), m) }, { m -> StatTile("Home used", fmtWh(load), m) },
                    { m -> StatTile("From grid (est.)", fmtWh(grid), m) },
                    { m -> StatTile("Self-powered", if (load > 0) "${((1 - grid / load).coerceIn(0.0, 1.0) * 100).roundToInt()}%" else "–", m) },
                    { m -> StatTile("Grid available", if (mins > 0) "${(on * 100.0 / mins).roundToInt()}% of time" else "–", m) },
                    { m -> StatTile("Grid outages", "$outages", m) },
                    { m -> StatTile("Saved (solar)", if (tariff > 0) "Rs " + String.format(Locale.US, "%,d", (pv / 1000 * tariff).roundToInt()) else "Set price", m) },
                    { m -> StatTile("Avg solar / day", if (have.isNotEmpty()) fmtWh(pv / have.size) else "–", m) },
                ))
            }
        }
    }
}
