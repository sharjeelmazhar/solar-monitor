package com.solarmonitor.app.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppBad
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
import com.solarmonitor.app.data.BillCalc
import com.solarmonitor.app.data.BillConfig
import com.solarmonitor.app.data.DayRec
import com.solarmonitor.app.ui.components.SectionCard
import com.solarmonitor.app.ui.dateOf
import com.solarmonitor.app.ui.dayLabel
import com.solarmonitor.app.ui.theme.LocalEnergy
import com.solarmonitor.app.ui.theme.NumberStyle
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

fun rs(v: Double) = "Rs " + String.format(Locale.US, "%,d", v.roundToInt())
private fun short(ymd: Int) = dateOf(ymd).format(DateTimeFormatter.ofPattern("d MMM"))

/** Expected IESCO bill for this billing month, with advice about the protected limit. Same logic as the web card. */
@Composable
fun BillCard(byDate: Map<Int, DayRec>, today: Int, cfg: BillConfig) {
    val e = LocalEnergy.current
    var open by rememberSaveable { mutableStateOf(false) }
    val nowT = LocalTime.now()
    val m = BillCalc.monthUse(byDate, today, cfg, (nowT.hour * 60 + nowT.minute) / 1440.0)
    val bill = BillCalc.compute(m.projected, cfg)
    val limit = cfg.limit
    val left = m.totalDays - m.elapsed
    val withoutSolar = BillCalc.compute(m.projected + m.selfUnits / max(1, m.covered) * m.totalDays, cfg)
    val saved = withoutSolar.total - bill.total
    val (past, verdict) = BillCalc.history(byDate, today, cfg)

    SectionCard("Electricity bill estimate", action = {
        Text(if (cfg.protected) "Protected" else "Unprotected", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = if (cfg.protected) e.good else e.warn,
            modifier = Modifier.clip(CircleShape).background((if (cfg.protected) e.good else e.warn).copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 5.dp))
    }) {
        Text("IESCO home tariff · ${short(m.start)} – ${short(m.end)} · day ${m.elapsed} of ${m.totalDays}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text("Expected bill this month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(rs(bill.total), style = MaterialTheme.typography.displaySmall.merge(NumberStyle))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Grid units", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${m.soFar.roundToInt()} so far → ≈ ${m.projected.roundToInt()}", style = MaterialTheme.typography.titleMedium.merge(NumberStyle))
            }
            if (saved > 1) Column(Modifier.weight(1f)) {
                Text("Solar is saving you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("≈ ${rs(saved)}", style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = e.good)
            }
        }

        // units bar with the 100 / protected-limit marks
        Spacer(Modifier.height(14.dp))
        val scaleMax = maxOf(limit * 1.25, m.projected * 1.1, 50.0)
        fun f(u: Double) = (u / scaleMax).coerceIn(0.0, 1.0).toFloat()
        BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth(f(m.projected)).fillMaxHeight().clip(CircleShape).background(e.grid.copy(alpha = 0.35f)))
                Box(Modifier.fillMaxWidth(f(m.soFar)).fillMaxHeight().clip(CircleShape).background(e.grid))
            }
            for (u in listOf(100, limit)) {
                Box(Modifier.offset(x = w * f(u.toDouble()) - 1.dp, y = (-3).dp).width(2.dp).height(18.dp).background(MaterialTheme.colorScheme.outline))
                Text("$u", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.offset(x = w * f(u.toDouble()) - 10.dp, y = 15.dp).width(20.dp), textAlign = TextAlign.Center)
            }
        }
        Text("dark = used so far · light = expected by month end" + if (m.covered < m.elapsed) " · monitor has ${m.covered} of ${m.elapsed} days, the rest are estimated" else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // advice
        val step = BillCalc.unitsToNextStep(m.projected, cfg)
        val perDayLeft = if (left > 0 && m.soFar < limit) String.format(Locale.US, "%.1f", (limit - m.soFar) / left) else null
        when {
            cfg.protected && bill.lostProtection -> {
                val cross = if (m.perDay > 0) BillCalc.addDays(m.start, (limit / m.perDay).toLong()) else null
                Note(e.crit, Icons.Rounded.GppBad,
                    "At this pace the month ends near ${m.projected.roundToInt()} units: over $limit" + (if (cross != null && cross <= m.end) ", around ${short(cross)}" else "") +
                        ". The whole month would then be billed at the unprotected rate (≈ ${rs(bill.total)} instead of ≈ ${rs(BillCalc.compute(limit.toDouble(), cfg).total)} at $limit units), and you lose protected status for the next 6 months." +
                        (perDayLeft?.let { " To stay protected, keep to about $it units a day for the remaining $left days." } ?: ""))
            }
            cfg.protected && step != null -> Note(e.good, Icons.Rounded.VerifiedUser,
                "On track to stay protected. ${(limit - m.projected).roundToInt()} units of headroom by month end" + (perDayLeft?.let { " · up to $it units/day is safe for the remaining $left days" } ?: "") + ".")
            !cfg.protected && step != null && step.first < 40 -> Note(e.warn, Icons.Rounded.Warning,
                "${step.first} units before the next slab (${step.second}). Unprotected bills charge the slab you reach on every unit, so crossing it raises the whole bill.")
        }

        TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            Spacer(Modifier.width(4.dp))
            Text("How it's worked out")
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val list = if (bill.protectedTier) cfg.ps else cfg.us
                Row2("Energy: ${bill.units} units" + if (bill.protectedTier && bill.slab > 0) " (first ${cfg.ps[bill.slab - 1].upTo} at Rs ${cfg.ps[bill.slab - 1].rate}, rest at Rs ${bill.rate})" else " × Rs ${bill.rate}", rs(bill.energy))
                Row2("Fixed charge (${fmtNum(cfg.kw)} kW × Rs ${fmtNum(list[bill.slab].fixedPerKw)})", rs(bill.fixed))
                if (bill.adjust != 0.0) Row2("Fuel / quarterly adjustment", rs(bill.adjust))
                Row2("Electricity duty ${fmtNum(cfg.ed)}%", rs(bill.duty))
                Row2("Sales tax (GST) ${fmtNum(cfg.gst)}%", rs(bill.gst))
                if (cfg.ptv > 0) Row2("PTV fee", rs(cfg.ptv))
                HorizontalDivider()
                Row2("Total", rs(bill.total), bold = true)
                Text("Rates: NEPRA S.R.O. 279(I)/2026. Protected = every one of the last 6 months at or under $limit units; going over once bills that month at the unprotected rate and removes the status for 6 months. " +
                    "Unprotected homes pay the rate of the slab they reach on every unit. Grid units are estimated by the inverter, and your meter also counts anything not wired through it: add that as \"other units\" in Settings → Bill. FPA changes every month; copy it from your bill.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
                    Text("Last 6 months from the monitor: " + when (verdict) {
                        true -> "all at or under $limit → protected"
                        false -> "a month over $limit → unprotected"
                        null -> "not enough history yet, so the status comes from your setting"
                    }, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        past.reversed().forEach { p ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                val c = if (!p.full) MaterialTheme.colorScheme.outline else if (p.units > limit) e.crit else e.good
                                Text(if (p.full) "${p.units.roundToInt()}" else "–", style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = c, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (p.full) c.copy(alpha = 0.14f) else Color.Transparent).padding(vertical = 4.dp))
                                Text(dateOf(p.start).format(DateTimeFormatter.ofPattern("MMM")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtNum(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

@Composable
private fun Row2(k: String, v: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.Bold else null, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun Note(tint: Color, icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.12f)).padding(12.dp)) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One row per day in units (kWh), newest first. */
@Composable
fun DailyUnits(rows: List<Pair<Int, DayRec>>, today: Int) {
    val e = LocalEnergy.current
    fun u(wh: Float) = String.format(Locale.US, if (wh >= 10_000) "%.1f" else "%.2f", wh / 1000)
    SectionCard("Daily units") {
        Text("1 unit = 1 kWh, the same unit as your electricity bill", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val head = MaterialTheme.typography.labelSmall
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("Day", style = head, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.3f))
            listOf("Solar" to e.solar, "Home" to e.load, "Solar+batt" to e.batt, "Grid" to e.grid).forEach { (l, c) ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(7.dp).height(7.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(4.dp))
                    Text(l, style = head, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        rows.forEach { (k, r) ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                val st = MaterialTheme.typography.bodySmall.merge(NumberStyle)
                Text(if (k == today) "Today" else dayLabel(k).substringBeforeLast(' '), style = st, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.3f), maxLines = 1)
                Text(u(r.pvWh), style = st, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(u(r.loadWh), style = st, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(u(max(0f, r.loadWh - r.gridWh)), style = st, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(u(r.gridWh), style = st, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}
