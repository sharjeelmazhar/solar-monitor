package com.solarmonitor.app.ui.screens

import com.solarmonitor.app.ui.components.Segmented
import androidx.compose.foundation.horizontalScroll
import kotlin.math.max
import com.solarmonitor.app.ui.components.BigValue
import com.solarmonitor.app.ui.theme.GeistMono
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import com.solarmonitor.app.ui.theme.NumberStyle
import com.solarmonitor.app.ui.components.ChartCard
import com.solarmonitor.app.data.Outages
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Download
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
import com.solarmonitor.app.data.BillConfig
import com.solarmonitor.app.data.DayRec
import com.solarmonitor.app.data.MinRec
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.ui.components.BarChart
import com.solarmonitor.app.ui.components.ChartLegend
import com.solarmonitor.app.ui.components.ChartSeries
import com.solarmonitor.app.ui.components.Grid
import com.solarmonitor.app.ui.components.GridStrip
import com.solarmonitor.app.ui.components.LineChart
import com.solarmonitor.app.ui.components.ScreenList
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.components.full
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // same columns as the web dashboard's CSV download
    val saveCsv = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val r = recs ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val iso = dateOf(date).toString()
        val text = buildString {
            append("time,solar_W,home_W,grid_W_est,battery_W,battery_pct,battery_V,pv_V,grid_V,output_V,inverter_C,mode,grid_present\n")
            for (x in r) append("$iso ${hhmm(x.t)},${x.pvW},${x.loadW},${x.gridW},${x.battW},${x.battPct},${x.battV},${x.pvV},${x.gridV},${x.outV},${x.tempC},${x.mode},${if (x.gridOn) 1 else 0}\n")
        }
        runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
    }

    LaunchedEffect(date) {
        recs = null; failed = false
        while (true) {
            val r = repo.day(date, date == today)
            if (r != null) { recs = r; failed = false } else if (recs == null) failed = true
            if (date != today) break
            delay(60_000)   // today keeps growing
        }
    }

    ScreenList(padding) {
        full("nav") {
            // same bar as the web: previous / the day (tap to pick) / next, and the CSV button on the right
            SectionCard(null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { date = ymd(dateOf(date).minusDays(1)) }, enabled = date > histFrom) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { picker = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center) {
                        // narrow phone or large font: short date on one line instead of wrapping letter by letter
                        androidx.compose.foundation.layout.BoxWithConstraints(contentAlignment = Alignment.Center) {
                            val long = maxWidth > 190.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.2f
                            Text(if (long) (if (date == today) "Today · " else "") + dateOf(date).format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
                                 else if (date == today) "Today" else dateOf(date).format(DateTimeFormatter.ofPattern("EEE d MMM")),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                maxLines = if (long) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    FilledTonalIconButton(onClick = { date = ymd(dateOf(date).plusDays(1)) }, enabled = date < today) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { saveCsv.launch("solar-${dateOf(date)}.csv") }, enabled = !recs.isNullOrEmpty()) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Text(" CSV")
                    }
                }
            }
        }
        val r = recs
        if (r == null) {
            full("load") { SectionCard(null) { if (failed) Text("Couldn't load this day. Check that you are on the home Wi-Fi.") else LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            return@ScreenList
        }
        if (r.isEmpty()) {
            full("empty") { SectionCard(null) { Text("No history was recorded for ${dayLabel(date)}.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            return@ScreenList
        }
        val d0 = dayStartMs(date)
        val xs = LongArray(r.size) { r[it].t }
        full("power") {
            val all = listOf(
                ChartSeries("Solar", e.solar, FloatArray(r.size) { r[it].pvW.toFloat() }, area = true),
                ChartSeries("Home", e.load, FloatArray(r.size) { r[it].loadW.toFloat() }),
                ChartSeries("Battery (+ in / − out)", e.batt, FloatArray(r.size) { r[it].battW.toFloat() }),
                ChartSeries("Grid (est.)", e.grid, FloatArray(r.size) { r[it].gridW.toFloat() }),
            )
            ChartCard("Power", sub = "one-minute averages",
                legend = { ChartLegend(all.map { it.name to it.color }, hidden) { n -> hidden = if (n in hidden) hidden - n else hidden + n } }) { h ->
                LineChart(xs, all.filter { it.name !in hidden }, d0, d0 + 86_400_000, height = h + 40.dp, gapMs = 5 * 60_000,
                    yFmt = { if (kotlin.math.abs(it) >= 1000) "${fmt1(it / 1000.0)}k" else "${it.roundToInt()}" }, valueFmt = { fmtW(it) })
            }
        }
        item(key = "soc") {
            ChartCard("Battery charge", sub = "${r.minOf { it.battPct }}–${r.maxOf { it.battPct }} % during the day") { h ->
                LineChart(xs, listOf(ChartSeries("Battery", e.batt, FloatArray(r.size) { r[it].battPct.toFloat() }, area = true)), d0, d0 + 86_400_000,
                    height = h - 50.dp, yMin = 0f, yMax = 100f, gapMs = 5 * 60_000, yFmt = { "${it.roundToInt()}%" }, valueFmt = { "${it.roundToInt()} %" },
                    title = { "${hhmm(xs[it])} · ${fmt2(r[it].battV)} V" })
            }
        }
        item(key = "grid") {
            val on = r.count { it.gridOn }
            val outages = remember(r) { Outages.find(r) }
            SectionCard("Grid availability", sub = "${fmtDuration(on.toDouble())} of ${fmtDuration(r.size.toDouble())} monitored · ${outages.size} outage${if (outages.size == 1) "" else "s"}") {
                GridStrip(r, d0, e.grid)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf(0, 6, 12, 18, 24).map { com.solarmonitor.app.ui.hourLabel(it) }.forEachIndexed { i, s ->
                        Text(s, style = MaterialTheme.typography.labelSmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                            textAlign = if (i == 0) TextAlign.Start else if (i == 4) TextAlign.End else TextAlign.Center)
                    }
                }
                if (outages.isNotEmpty()) Column(Modifier.padding(top = 12.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    outages.forEach { o ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text((if (o.startKnown) hhmm(o.start) else "before " + hhmm(o.start)) + " → " + (if (o.ongoing) "now" else if (o.endKnown) hhmm(o.end) else "unknown"),
                                style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), modifier = Modifier.weight(1f))
                            Text(fmtDuration(o.minutes.toDouble()), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        full("totals") {
            val pv = r.sumOf { it.pvW } / 60.0; val load = r.sumOf { it.loadW } / 60.0; val grid = r.sumOf { it.gridW } / 60.0
            SectionCard("Day totals", sub = dayLabel(date)) {
                Grid(2, listOf(
                    { m -> StatTile("Solar", fmtWh(pv), m, tone = e.solar) }, { m -> StatTile("Home", fmtWh(load), m, tone = e.load) },
                    { m -> StatTile("Grid (est.)", fmtWh(grid), m, tone = e.grid) }, { m -> StatTile("Peak solar", fmtW(r.maxOf { it.pvW }), m) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyScreen(repo: Repository, padding: PaddingValues) {
    val e = LocalEnergy.current
    val live by repo.live.collectAsStateWithLifecycle()
    var days by remember { mutableStateOf<List<DayRec>?>(null) }
    val bill by repo.bill.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf("month") }   // month, 7, 30, year, custom (same choices as the web)
    var hidden by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { if (bill == null) repo.loadBill() }
    LaunchedEffect(Unit) { while (true) { repo.days()?.let { days = it }; delay(5 * 60_000) } }

    val t = live?.today
    val today = t?.date?.takeIf { it > 0 } ?: todayYmd()
    var from by rememberSaveable { mutableIntStateOf(ymd(dateOf(today).minusDays(13))) }
    var to by rememberSaveable { mutableIntStateOf(today) }
    var pick by remember { mutableStateOf<String?>(null) }   // "from" / "to" while a date picker is open

    // finished days + today's running totals
    val map = HashMap<Int, DayRec>()
    days?.forEach { map[it.date] = it }
    if (t != null && t.date > 0) map[t.date] = DayRec(t.date, t.pvWh.toFloat(), t.loadWh.toFloat(), t.gridWh.toFloat(), t.chgWh.toFloat(), t.disWh.toFloat(),
        t.pvPeak, t.loadPeak, t.gridOnMin, t.onlineMin, 0, 0, 0, t.outages)
    val end0 = dateOf(today)
    val (start, end) = when (range) {
        "month" -> ymd(end0.withDayOfMonth(1)) to today
        "7" -> ymd(end0.minusDays(6)) to today
        "30" -> ymd(end0.minusDays(29)) to today
        "year" -> ymd(end0.minusDays(364)) to today
        else -> if (from <= to) from to to else to to from
    }
    val keys = generateSequence(dateOf(start)) { it.plusDays(1) }.takeWhile { !it.isAfter(dateOf(end)) }.map { ymd(it) }.toList()
    val rows = keys.map { map[it] }
    val have = rows.filterNotNull()
    fun sum(f: (DayRec) -> Float) = have.sumOf { f(it).toDouble() }
    val pv = sum { it.pvWh }; val load = sum { it.loadWh }; val grid = sum { it.gridWh }; val chg = sum { it.chgWh }; val dis = sum { it.disWh }
    val on = have.sumOf { it.gridOnMin }; val mon = have.sumOf { it.onlineMin }; val outages = have.sumOf { it.outages }

    // monthly bars for long ranges, daily otherwise
    val monthly = keys.size > 62
    data class Bucket(val label: String, val title: String, val rows: List<DayRec>)
    val buckets = if (!monthly) keys.mapIndexed { i, k ->
        Bucket(dateOf(k).format(DateTimeFormatter.ofPattern(if (keys.size > 14) "d" else "EEE d")), dayLabel(k), listOfNotNull(rows[i]))
    } else keys.indices.groupBy { dateOf(keys[it]).withDayOfMonth(1) }.map { (m, idx) ->
        Bucket(m.format(DateTimeFormatter.ofPattern("MMM")), m.format(DateTimeFormatter.ofPattern("MMMM yyyy")), idx.mapNotNull { rows[it] })
    }
    fun vals(f: (DayRec) -> Float) = FloatArray(buckets.size) { i -> buckets[i].rows.takeIf { it.isNotEmpty() }?.sumOf { f(it).toDouble() }?.toFloat() ?: Float.NaN }
    val series = listOf(
        ChartSeries("Home from solar/battery", e.batt, vals { max(0f, it.loadWh - it.gridWh) }),
        ChartSeries("Home from grid", e.grid, vals { it.gridWh }),
        ChartSeries("Solar produced", e.solar, vals { it.pvWh }),
    )
    val monthStart = ymd(end0.withDayOfMonth(1))
    val month = map.values.filter { it.date in monthStart..today }
    val mPv = month.sumOf { it.pvWh.toDouble() }; val mGrid = month.sumOf { it.gridWh.toDouble() }
    val self = if (load > 0) ((1 - grid / load).coerceIn(0.0, 1.0) * 100).roundToInt() else null
    fun units(wh: Double) = String.format(Locale.US, if (wh >= 100_000) "%.0f" else if (wh >= 10_000) "%.1f" else "%.2f", wh / 1000)

    ScreenList(padding) {
        item(key = "month") {
            // "Solar units made this month" with the warm glow of the web card
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Box(Modifier.fillMaxWidth().drawBehind {
                    val c = androidx.compose.ui.geometry.Offset(size.width - 72.dp.toPx(), 48.dp.toPx())
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(e.solar.copy(alpha = 0.22f), Color.Transparent), c, 150.dp.toPx()), 150.dp.toPx(), c)
                }.padding(16.dp)) {
                    Column {
                        Text("Solar units made this month", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BigValue("${units(mPv)} units", style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp, lineHeight = 56.sp))
                        Text(buildAnnotatedString {
                            append("${end0.format(DateTimeFormatter.ofPattern("MMMM"))} so far · grid ≈ ")
                            withStyle(SpanStyle(fontFamily = GeistMono, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append(units(mGrid)) }
                            append(" units (estimated)")
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "self") {
            SectionCard(null) {
                Text("Self-powered (${have.size} day${if (have.size == 1) "" else "s"})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BigValue(if (self == null) "–" else "$self %", style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp, lineHeight = 56.sp))
                Spacer(Modifier.height(10.dp))
                val w by animateFloatAsState((self ?: 0) / 100f, tween(700), label = "self")
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(e.grid.copy(alpha = 0.4f))) {
                    Box(Modifier.fillMaxWidth(w).height(8.dp).clip(CircleShape).background(e.batt))
                }
                Text("Share of home energy that did not come from the grid.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
            }
        }
        // always present, so the list doesn't keep its old scroll position and hide the card above it once data arrives
        item(key = "bill") {
            if (days != null && t != null && t.date > 0) BillCard(map, t.date, bill ?: BillConfig(), live)
            else SectionCard("Electricity bill estimate") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        item(key = "bills") { BillHistory(bill ?: BillConfig()) }
        full("range") {
            SectionCard(null) {
                Segmented(range, listOf("month" to "Month", "7" to "7 days", "30" to "30 days", "year" to "Year", "custom" to "Custom"), { range = it },
                    Modifier.horizontalScroll(rememberScrollState()))
                if (range == "custom") Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pick = "from" }) { Text(dayLabel(from)) }
                    Text("  to  ", color = MaterialTheme.colorScheme.outline)
                    OutlinedButton(onClick = { pick = "to" }) { Text(dayLabel(to)) }
                }
            }
        }
        if (days == null) { full("load") { SectionCard(null) { LinearProgressIndicator(Modifier.fillMaxWidth()) } }; return@ScreenList }
        if (have.isEmpty()) {
            full("empty") { SectionCard(null) { Text("No energy data in this range yet. Daily totals build up as the monitor runs.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            return@ScreenList
        }
        full("bars") {
            ChartCard(if (monthly) "Energy per month" else "Energy per day", sub = "${dayLabel(start)} – ${dayLabel(end)}",
                legend = { ChartLegend(series.map { it.name to it.color }, hidden) { n -> hidden = if (n in hidden) hidden - n else hidden + n } }) { h ->
                BarChart(buckets.map { it.label }, series.filter { it.name !in hidden }, height = h + 50.dp,
                    stacks = listOf(listOf("Home from solar/battery", "Home from grid"), listOf("Solar produced")),
                    yFmt = { if (it == 0f) "0" else if (it >= 1000) "${fmt1(it / 1000.0).removeSuffix(".0")} kWh" else "${it.roundToInt()} Wh" },
                    valueFmt = { fmtWh(it) }, title = { buckets[it].title })
            }
        }
        full("totals") {
            SectionCard("Totals", sub = "${have.size} day${if (have.size == 1) "" else "s"} with data") {
                Grid(2, listOf(
                    { m -> StatTile("Solar produced", fmtWh(pv), m, hint = "${units(pv)} units", tone = e.solar) },
                    { m -> StatTile("Home used", fmtWh(load), m, tone = e.load) },
                    { m -> StatTile("From grid (est.)", fmtWh(grid), m, info = GRID_EST_INFO, hint = "${units(grid)} units", tone = e.grid) },
                    { m -> StatTile("Battery in / out", fmtWh(chg), m, hint = "out ${fmtWh(dis)}", tone = e.batt) },
                    { m -> StatTile("Grid available", if (mon > 0) "${(on * 100.0 / mon).roundToInt()} %" else "–", m,
                        hint = if (mon > 0) "${fmtDuration(on.toDouble())} of ${fmtDuration(mon.toDouble())}" else null) },
                    { m -> StatTile("Grid outages", "$outages", m) },
                    { m -> StatTile("From solar + battery", "${units(max(0.0, load - grid))} units", m, tone = e.batt) },
                    { m -> StatTile("Average solar / day", fmtWh(pv / have.size), m) },
                ))
            }
        }
        val daily = keys.zip(rows).mapNotNull { (k, r) -> r?.let { k to it } }.reversed().take(62)
        if (daily.isNotEmpty()) full("daily") { DailyUnits(daily, today) }
    }

    pick?.let { which ->
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dateOf(if (which == "from") from else to).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = ymd(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()) <= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { pick = null },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { val d = ymd(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()); if (which == "from") from = d else to = d }
                pick = null
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pick = null }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
