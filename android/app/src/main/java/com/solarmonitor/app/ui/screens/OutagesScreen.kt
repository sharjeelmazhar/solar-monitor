package com.solarmonitor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.MinRec
import com.solarmonitor.app.data.Outage
import com.solarmonitor.app.data.Outages
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.ui.components.DayClock
import com.solarmonitor.app.ui.components.Grid
import com.solarmonitor.app.ui.components.GridStrip
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.components.StatTile
import com.solarmonitor.app.ui.dateOf
import com.solarmonitor.app.ui.dayLabel
import com.solarmonitor.app.ui.dayStartMs
import com.solarmonitor.app.ui.fmtDuration
import com.solarmonitor.app.ui.fmtWh
import com.solarmonitor.app.ui.hhmm
import com.solarmonitor.app.ui.rememberStaleMs
import com.solarmonitor.app.ui.theme.LocalEnergy
import com.solarmonitor.app.ui.todayYmd
import com.solarmonitor.app.ui.ymd
import com.solarmonitor.app.ui.zone
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun OutagesScreen(repo: Repository, padding: PaddingValues) {
    val e = LocalEnergy.current
    val live by repo.live.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    val stale = rememberStaleMs(repo)
    val today = live?.today?.date?.takeIf { it > 0 } ?: todayYmd()
    val histFrom = info?.histFrom?.takeIf { it > 0 } ?: today
    val gridOn = live?.gridOn
    var span by rememberSaveable { mutableIntStateOf(7) }
    var clockDay by rememberSaveable { mutableIntStateOf(today) }
    var data by remember { mutableStateOf<Map<Int, List<MinRec>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    val days = remember(today, histFrom, span) {
        val out = ArrayList<Int>()
        var d = dateOf(today)
        while (out.size < span && ymd(d) >= histFrom) { out += ymd(d); d = d.minusDays(1) }
        out
    }

    LaunchedEffect(days, gridOn) {
        while (true) {
            loading = true
            var fail = false
            val next = HashMap<Int, List<MinRec>>()
            for (k in days) {
                val r = repo.day(k, k == today)
                if (r != null) next[k] = r else fail = true
                data = HashMap(next)
            }
            loading = false; failed = fail
            delay(5 * 60_000)   // today keeps growing
        }
    }

    val all = remember(data, days) { days.reversed().flatMap { data[it].orEmpty() } }
    val events = remember(all) { Outages.find(all) }
    val current = events.lastOrNull()?.takeIf { it.ongoing }
    val now = live?.t?.takeIf { it > 0 } ?: System.currentTimeMillis()

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "span") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(1 to "Today", 7 to "7 days", 14 to "14 days", 31 to "All").forEachIndexed { i, (n, l) ->
                    SegmentedButton(selected = span == n, onClick = { span = n }, shape = SegmentedButtonDefaults.itemShape(i, 4), icon = {}) { Text(l) }
                }
            }
        }
        if (gridOn != null && stale == null) item(key = "now") { NowBanner(gridOn, current, now) }

        if (all.isEmpty()) {
            item(key = "empty") {
                SectionCard(null) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else Text(if (failed) "Couldn't load history from the monitor. Is it on and on the same Wi-Fi?" else "No history yet for this period.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@LazyColumn
        }

        item(key = "stats") {
            val total = events.sumOf { it.minutes }
            Grid(2, listOf(
                { m -> StatTile("Outages", "${events.size}", m) },
                { m -> StatTile("Time without grid", fmtDuration(total.toDouble()), m) },
                { m -> StatTile("Longest", if (events.isEmpty()) "–" else fmtDuration(events.maxOf { it.minutes }.toDouble()), m) },
                { m -> StatTile("Average", if (events.isEmpty()) "–" else fmtDuration(total.toDouble() / events.size), m) },
                { m -> StatTile("Grid available", if (all.isNotEmpty()) "${(100 - total * 100.0 / all.size).roundToInt().coerceIn(0, 100)}%" else "–", m) },
                { m -> StatTile("Monitored", fmtDuration(all.size.toDouble()), m) },
            ))
        }

        item(key = "clock") {
            val idx = days.indexOf(clockDay)
            SectionCard(if (clockDay == today) "Today" else dayLabel(clockDay), info = "Each slice is one hour. Its colours show what powered the home: yellow solar, green battery, pink grid. Longer slices mean more energy used.\n\nThe outer ring shows the grid: pink when available, red stripes when it was off. Tap a slice or a red part for details.", action = {
                FilledTonalIconButton(onClick = { clockDay = days[idx + 1] }, enabled = idx >= 0 && idx < days.size - 1) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(onClick = { clockDay = days[idx - 1] }, enabled = idx > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day") }
            }) {
                Text("24-hour clock · midnight at the top", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val r = data[clockDay]
                if (r == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else DayClock(r, dayStartMs(clockDay), events, if (clockDay == today) now else null)
            }
        }

        item(key = "events-title") {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text("What happened", style = MaterialTheme.typography.titleMedium)
                Text("Each card is one time the grid went off, newest first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (events.isEmpty()) item(key = "none") { SectionCard(null) { Text("No outages in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        val byDay = events.reversed().groupBy { ymd(Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate()) }
        byDay.forEach { (k, list) ->
            item(key = "d$k") {
                Text(
                    when (k) { today -> "Today"; ymd(dateOf(today).minusDays(1)) -> "Yesterday"; else -> dateOf(k).format(DateTimeFormatter.ofPattern("EEEE d MMM")) }.uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            list.forEach { o -> item(key = "o${o.start}") { EventCard(o, all) { if (k in days) clockDay = k } } }
        }

        if (span > 1) item(key = "strips") {
            SectionCard("Day by day") {
                Text("Tap a day to show it on the clock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    days.forEach { k ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (k == clockDay) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                                .clickable { clockDay = k }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (k == today) "Today" else dateOf(k).format(DateTimeFormatter.ofPattern("EEE d")), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                            val r = data[k]
                            if (r != null) GridStrip(r, dayStartMs(k), e.grid, Modifier.weight(1f)) else LinearProgressIndicator(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowBanner(on: Boolean, current: Outage?, now: Long) {
    val e = LocalEnergy.current
    val tint = if (on) e.grid else e.crit
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (on) MaterialTheme.colorScheme.surfaceContainerLow else e.crit.copy(alpha = 0.12f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(if (on) Icons.Rounded.Bolt else Icons.Rounded.PowerOff, null, tint = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (on) "Grid is ON right now" else "Grid is OFF right now", style = MaterialTheme.typography.titleSmall)
            Text(
                if (on) "WAPDA supply is available."
                else (current?.let { "Off since ${hhmm(it.start)} · ${fmtDuration((now - it.start) / 60_000.0)} so far. " } ?: "") + "The home runs on solar and battery.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventCard(o: Outage, recs: List<MinRec>, onClick: () -> Unit) {
    val e = LocalEnergy.current
    val d = remember(o, recs.size) { Outages.during(recs, o) }
    val nextDay = Instant.ofEpochMilli(o.end - 1).atZone(zone).toLocalDate() != Instant.ofEpochMilli(o.start).atZone(zone).toLocalDate()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick).padding(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            // newest event on top, like the list itself
            Line(if (o.ongoing) MaterialTheme.colorScheme.outline else e.grid, if (o.ongoing) "Still off" else "Came back",
                if (o.ongoing) "${fmtDuration((System.currentTimeMillis() - o.start) / 60_000.0)} so far" else if (o.endKnown) hhmm(o.end) + (if (nextDay) " (next day)" else "") else "unknown",
                if (!o.ongoing && !o.endKnown) "monitor was offline after ${hhmm(o.end)}" else null)
            Box(Modifier.padding(start = 5.dp).width(2.dp).height(12.dp).background(e.crit.copy(alpha = 0.4f)))
            Line(e.crit, "Went off", if (o.startKnown) hhmm(o.start) else "before ${hhmm(o.start)}", if (o.startKnown) null else "already off when the monitor started")
            d?.let {
                Text("Home used ${fmtWh(it.homeWh)}" + (if (it.solarWh > 1) " · solar made ${fmtWh(it.solarWh)}" else "") + " · battery ${it.socFrom}% → ${it.socTo}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Text(fmtDuration(o.minutes.toDouble()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (o.ongoing) e.crit else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clip(CircleShape).background((if (o.ongoing) e.crit else e.grid).copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
private fun Line(dot: Color, label: String, time: String, note: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}
