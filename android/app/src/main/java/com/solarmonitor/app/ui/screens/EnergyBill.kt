package com.solarmonitor.app.ui.screens

import com.solarmonitor.app.ui.components.clearOnOutsideTap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.solarmonitor.app.data.BillCalc
import com.solarmonitor.app.data.BillConfig
import com.solarmonitor.app.data.DayRec
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.ui.hhmm
import com.solarmonitor.app.ui.hourLabel
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.dateOf
import com.solarmonitor.app.ui.dayLabel
import com.solarmonitor.app.ui.theme.LocalEnergy
import com.solarmonitor.app.ui.theme.NumberStyle
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

fun rs(v: Double) = "Rs " + String.format(Locale.US, "%,d", v.roundToInt())
private fun short(ymd: Int) = dateOf(ymd).format(DateTimeFormatter.ofPattern("d MMM"))
fun monthName(ym: Int, pattern: String = "MMM yy"): String = YearMonth.of(ym / 100, ym % 100).format(DateTimeFormatter.ofPattern(pattern))
private fun n(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

private const val BILL_INFO = "Protected homes used 200 units or less in each of the last 6 months and pay the lowest rates. Going over 200 even once bills that whole month at the unprotected rate and removes the status for the next 6 months.\n\n" +
    "Unprotected homes pay the rate of the slab they reach on every unit, so 201 units cost much more than 200.\n\n" +
    "Grid units are worked out by the monitor from the inverter. Your meter also counts anything not wired through this inverter: add that as \"Other units\" in Settings → Bill.\n\n" +
    "You get a notification at 150, 175 and 190 units, and early if the month is heading over 200.\n\n" +
    "Rates: NEPRA S.R.O. 279(I)/2026 (12 Feb 2026). Each line is calculated the way a real IESCO bill does it."

/** Expected IESCO bill for this billing month, with advice about the protected limit. Same logic as the web card. */
@Composable
fun BillCard(byDate: Map<Int, DayRec>, today: Int, cfg: BillConfig, live: Live? = null) {
    val e = LocalEnergy.current
    var open by rememberSaveable { mutableStateOf(false) }
    val nowT = LocalTime.now()
    val now = BillCalc.now(byDate, today, cfg, (nowT.hour * 60 + nowT.minute) / 1440.0, live?.t?.takeIf { it > 0 } ?: System.currentTimeMillis(), live?.cyc)
    val since = "${short(now.m.start)}, ${hourLabel(cfg.hr)}"
    val m = now.m
    val bill = now.bill
    val limit = cfg.limit
    val left = m.totalDays - m.elapsed
    val (past, verdict) = BillCalc.history(byDate, today, cfg)

    SectionCard("Electricity bill estimate", info = BILL_INFO, action = {
        Text(if (cfg.protected) "Protected" else "Unprotected", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = if (cfg.protected) e.good else e.warn,
            modifier = Modifier.clip(CircleShape).background((if (cfg.protected) e.good else e.warn).copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 5.dp))
    }) {
        Text("${monthName(now.ym, "MMMM yyyy")} bill · reading ${short(m.start)} – ${short(BillCalc.nextCycle(m.start))}, ${hourLabel(cfg.hr)} · day ${m.elapsed} of ${m.totalDays}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text("Expected bill", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(rs(bill.total), style = MaterialTheme.typography.displaySmall.merge(NumberStyle))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (now.basis == "measured") "Grid units used since " + (if (now.counter) since else short(m.start)) else "Grid units so far (estimated)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (now.basis == "measured") (if (m.soFar < 10) String.format(Locale.US, "%.1f", m.soFar) else "${m.soFar.roundToInt()}") else "≈ ${m.soFar.roundToInt()}") + " → ≈ ${m.projected.roundToInt()} by the reading", style = MaterialTheme.typography.titleMedium.merge(NumberStyle))
            }
            if (now.saved > 1) Column(Modifier.weight(1f)) {
                Text("Solar is saving you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("≈ ${rs(now.saved)}", style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = e.good)
            }
        }

        // units bar with the 100 / protected-limit marks
        Spacer(Modifier.height(14.dp))
        val scaleMax = maxOf(limit * 1.25, m.projected * 1.1, 50.0)
        fun f(u: Double) = (u / scaleMax).coerceIn(0.0, 1.0).toFloat()
        BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth(f(m.projected)).fillMaxHeight().clip(CircleShape).background(e.grid.copy(alpha = 0.35f)))
                Box(Modifier.fillMaxWidth(f(m.soFar)).fillMaxHeight().clip(CircleShape).background(e.grid))
            }
            for (u in listOf(100, limit)) {
                Box(Modifier.offset(x = w * f(u.toDouble()) - 1.dp, y = (-3).dp).width(2.dp).height(18.dp).background(MaterialTheme.colorScheme.outline))
                Text("$u", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.offset(x = w * f(u.toDouble()) - 14.dp, y = 16.dp).width(28.dp), textAlign = TextAlign.Center)
            }
        }
        Text("dark = used so far · light = expected by the meter reading" + when {
                now.counter && now.countFrom > 0 -> " · the monitor started counting on ${short(BillCalc.billDate(now.countFrom, 0))}, ${hhmm(now.countFrom)}; the days before are estimated from your bills (about ${now.recentAvg} units a month). From the next reading on, every unit is counted."
                now.counter -> " · counted live by the monitor since the reading ($since)" +
                    (if (now.missingDays > 0.25) "; it was off for about " + (if (now.missingDays < 1) "${(now.missingDays * 24).roundToInt()} hours" else String.format(Locale.US, "%.1f days", now.missingDays)) + ", filled in at the usual rate" else "") +
                    (if (m.covered < 7 && now.recentAvg > 0) "; the forecast leans on your last bills (about ${now.recentAvg} units) until a week is measured" else "")
                now.basis == "measured" -> " · counted by the monitor from the inverter since the reading on ${short(m.start)}" +
                    (if (m.covered < 7 && now.recentAvg > 0) "; the rest of the month uses your last bills (about ${now.recentAvg} units) until a week is measured" else "")
                now.basis == "bills" -> " · the monitor has only ${m.covered} day${if (m.covered == 1) "" else "s"} of this month, so this uses your last bills (about ${now.recentAvg} units)"
                m.covered < m.elapsed -> " · the monitor has ${m.covered} of ${m.elapsed} days, the rest are estimated"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val a = now.alert
        when {
            a != null && a.level > 0 -> Note(if (a.level == 2) e.crit else e.warn, if (a.level == 2) Icons.Rounded.GppBad else Icons.Rounded.Warning, a.title, a.text)
            cfg.protected && !bill.lostProtection -> Note(e.good, Icons.Rounded.VerifiedUser, "On track to stay protected",
                "About ${(limit - m.projected).roundToInt()} units to spare by the meter reading" +
                    (if (left > 0 && m.soFar < limit) "; up to ${String.format(Locale.US, "%.1f", (limit - m.soFar) / left)} units a day is safe for the remaining $left days" else "") + ".")
        }
        if (verdict != null && verdict != cfg.protected) Note(e.warn, Icons.Rounded.Info, "Check your status",
            "Your last 6 bills say ${if (verdict) "protected" else "unprotected"}, but the settings say ${if (cfg.protected) "protected" else "unprotected"}. Change it in Settings → Bill if the bill agrees.")

        TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            Spacer(Modifier.width(4.dp))
            Text("How it's worked out")
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val list = if (bill.protectedTier) cfg.ps else cfg.us
                Row2("Energy: ${bill.units} units" + if (bill.protectedTier && bill.slab > 0) " (first ${cfg.ps[bill.slab - 1].upTo} at Rs ${cfg.ps[bill.slab - 1].rate}, rest at Rs ${bill.rate})" else " × Rs ${bill.rate}", rs(bill.energy))
                Row2("Fixed charge (${n(cfg.kw)} kW × Rs ${n(list[bill.slab].fixedPerKw)})", rs(bill.fixed))
                if (bill.fcs != 0.0) Row2("F.C. surcharge (${bill.units} × Rs ${n(cfg.fc)})", rs(bill.fcs))
                if (bill.qta != 0.0) Row2("Quarterly adjustment (${bill.units} × Rs ${n(cfg.qta)})", rs(bill.qta))
                if (bill.fpa != 0.0) Row2("Fuel adjustment (${bill.fpaUnits} units of ${monthName(BillCalc.addMonths(now.ym, -2))} × Rs ${n(cfg.fpa)})", rs(bill.fpa))
                Row2("Electricity duty ${n(cfg.ed)}%", rs(bill.duty))
                Row2("Sales tax (GST) ${n(cfg.gst)}%", rs(bill.gst))
                if (cfg.ptv > 0) Row2("TV fee", rs(cfg.ptv))
                HorizontalDivider()
                Row2("Total", rs(bill.total), bold = true)
                if (cfg.fpa == 0.0) Text("Fuel adjustment (FPA) is not set: copy the Rs/unit from your latest bill into Settings → Bill for a closer estimate.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
                    Text("Last 6 bills: " + when (verdict) {
                        true -> "all at or under $limit → protected"
                        false -> "a month over $limit → unprotected"
                        null -> "some months missing, so the status comes from your setting"
                    }, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        past.reversed().forEach { p ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                val u = p.units
                                val c = if (u == null) MaterialTheme.colorScheme.outline else if (u > limit) e.crit else e.good
                                Text(u?.roundToInt()?.toString() ?: "–", style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = c, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (u != null) c.copy(alpha = 0.14f) else Color.Transparent).padding(vertical = 4.dp))
                                Text(monthName(p.month, "MMM"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    Text("From the bills you entered, or from the monitor for months it fully covered.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Row2(k: String, v: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.Bold else null, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun Note(tint: Color, icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.12f)).padding(12.dp)) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One row per day in units (kWh), newest first. Column titles wrap onto two lines instead of overlapping. */
@Composable
fun DailyUnits(rows: List<Pair<Int, DayRec>>, today: Int) {
    val e = LocalEnergy.current
    fun u(wh: Float) = String.format(Locale.US, if (wh >= 10_000) "%.1f" else "%.2f", wh / 1000)
    SectionCard("Daily units", info = "1 unit = 1 kWh, the same unit as your electricity bill. \"Solar + battery\" is the home's use that did not come from the grid.") {
        val head = MaterialTheme.typography.labelSmall
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("Day", style = head, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.25f))
            listOf("Solar\nmade" to e.solar, "Home\nused" to e.load, "Solar +\nbattery" to e.batt, "From\ngrid" to e.grid).forEach { (l, c) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.height(3.dp))
                    Text(l, style = head, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                }
            }
        }
        rows.forEach { (k, r) ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                val st = MaterialTheme.typography.bodyMedium.merge(NumberStyle)
                Text(if (k == today) "Today" else dayLabel(k).substringBeforeLast(' '), style = st, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.25f))
                Text(u(r.pvWh), style = st, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(u(r.loadWh), style = st, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(u(max(0f, r.loadWh - r.gridWh)), style = st, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(u(r.gridWh), style = st, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Units of every bill entered, with the protected limit, and what they show. */
@Composable
fun BillHistory(cfg: BillConfig) {
    val hist = cfg.hist
    if (hist.size < 2) return
    val e = LocalEnergy.current
    val cs = MaterialTheme.colorScheme
    val limit = cfg.limit
    SectionCard("Your bills", info = "From the bills entered in Settings → Bill. Bars turn amber at ${limit - 25}+ units and red above $limit. The dashed line is the protected limit.") {
        Text("${hist.size} months from ${monthName(hist.first().month)} to ${monthName(hist.last().month)} · units per bill", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        val max = maxOf(limit * 1.15f, hist.maxOf { it.units }.toFloat())
        var sel by rememberSaveable { mutableStateOf(-1) }
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(150.dp).clearOnOutsideTap { sel = -1 }.pointerInput(hist.size) {
            detectTapGestures { p -> val i = (p.x / (size.width.toFloat() / hist.size)).toInt().coerceIn(0, hist.size - 1); sel = if (sel == i) -1 else i }
        }) {
            val w = size.width / hist.size
            hist.forEachIndexed { i, b ->
                val h = size.height * b.units / max
                val c = if (b.units > limit) e.crit else if (b.units >= limit - 25) e.warn else e.grid
                drawRoundRect(c.copy(alpha = if (sel < 0) (if (i == hist.size - 1) 1f else 0.8f) else if (sel == i) 1f else 0.35f), androidx.compose.ui.geometry.Offset(i * w + w * 0.15f, size.height - h),
                    androidx.compose.ui.geometry.Size(w * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
            val y = size.height * (1 - limit / max)
            drawLine(e.crit, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val step = (hist.size + 9) / 10
            hist.forEachIndexed { i, b ->
                Text(if ((i % step == 0 && i < hist.size - 2) || i == hist.size - 1) monthName(b.month, "MMM") else "", style = MaterialTheme.typography.labelSmall, color = cs.outline,
                    textAlign = TextAlign.Center, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            }
        }
        hist.getOrNull(sel)?.let { b ->
            val prev = hist.firstOrNull { it.month == b.month - 100 }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(12.dp)) {
                Text(monthName(b.month, "MMMM yyyy"), style = MaterialTheme.typography.titleSmall)
                Text("${b.units} units" + (if (b.amount > 0) " · ${rs(b.amount.toDouble())} · Rs ${String.format(Locale.US, "%.1f", b.amount.toDouble() / b.units)}/unit" else ""),
                    style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = if (b.units > limit) e.crit else cs.onSurface)
                Text((if (b.units > limit) "over the protected limit" else "${limit - b.units} units under $limit") +
                    (prev?.let { " · ${if (b.units - it.units >= 0) "+" else ""}${b.units - it.units} units vs a year before" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Text("Tap a bar to see that bill.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        BillCalc.insights(hist, limit).forEach { x ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(when (x.tone) { "good" -> e.good; "warn" -> e.warn; "crit" -> e.crit; else -> cs.outline }))
                Spacer(Modifier.width(10.dp))
                Text(x.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
