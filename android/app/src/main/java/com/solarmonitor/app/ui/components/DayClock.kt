package com.solarmonitor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.solarmonitor.app.data.HourMix
import com.solarmonitor.app.data.MinRec
import com.solarmonitor.app.data.Outage
import com.solarmonitor.app.data.Outages
import com.solarmonitor.app.ui.fmtDuration
import com.solarmonitor.app.ui.fmtWh
import com.solarmonitor.app.ui.hhmm
import com.solarmonitor.app.ui.hourLabel
import com.solarmonitor.app.ui.theme.LocalEnergy
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Same dial as the web dashboard (web/src/components/charts/DayClock.tsx):
// midnight at the top, clockwise; hour wedges = what powered the home; outer ring = grid on / off.
private const val C = 180f          // 360 x 360 design space: room for the hour labels at the sides
private const val R0 = 52f
private const val R1 = 118f
private const val RING0 = 126f
private const val RING1 = 138f
private const val DAY = 86_400_000L
private const val MIN = 60_000L

private sealed interface Sel {
    data class Hour(val h: Int) : Sel
    data class Out(val o: Outage) : Sel
}

private class Run(val on: Boolean, val from: Long, var to: Long)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayClock(recs: List<MinRec>, dayStart: Long, outages: List<Outage>, now: Long?, modifier: Modifier = Modifier) {
    val e = LocalEnergy.current
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val mix = remember(recs) { Outages.hourly(recs) }
    val ring = remember(recs) {
        val out = ArrayList<Run>()
        for (r in recs) {
            val last = out.lastOrNull()
            if (last != null && last.on == r.gridOn && r.t - last.to <= 2 * MIN) last.to = r.t + MIN else out += Run(r.gridOn, r.t, r.t + MIN)
        }
        out
    }
    val outs = remember(outages, dayStart) { outages.filter { it.end > dayStart && it.start < dayStart + DAY } }
    var sel by remember(dayStart) { mutableStateOf<Sel?>(null) }
    val maxWh = max(1.0, mix.maxOf { it.homeWh })
    val showNow = now != null && now >= dayStart && now < dayStart + DAY
    fun frac(t: Long) = ((t - dayStart).toFloat() / DAY).coerceIn(0f, 1f)
    val desc = "24-hour clock: ${outs.size} outages, " + mix.filter { it.minutes > 0 }.joinToString { "${hourLabel(it.hour)} ${fmtWh(it.homeWh)}" }

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f).align(Alignment.CenterHorizontally)
                .semantics { contentDescription = desc }
                .pointerInput(recs, outs) {
                    detectTapGestures { p ->
                        val s = size.width / 360f
                        val dx = p.x / s - C; val dy = p.y / s - C
                        val r = hypot(dx, dy)
                        var f = ((atan2(dy, dx) + PI / 2) / (2 * PI)).toFloat()
                        if (f < 0) f += 1f
                        val t = dayStart + (f * DAY).toLong()
                        sel = when {
                            r in R0..R1 + 4 -> Sel.Hour((f * 24).toInt().coerceIn(0, 23)).takeIf { it != sel }
                            r in RING0 - 8..RING1 + 14 -> outs.firstOrNull { t >= it.start - 3 * MIN && t < it.end + 3 * MIN }?.let { Sel.Out(it) }?.takeIf { it != sel }
                            else -> null
                        }
                    }
                },
        ) {
            val s = size.width / 360f
            fun deg(f: Float) = f * 360f - 90f
            fun arc(r0: Float, r1: Float, a0: Float, sweep: Float, color: Color, alpha: Float = 1f, shift: Offset = Offset.Zero) {
                val rm = (r0 + r1) / 2 * s
                drawArc(color, a0, sweep, false, Offset(C * s - rm, C * s - rm) + shift, Size(rm * 2, rm * 2), alpha, Stroke((r1 - r0) * s, cap = StrokeCap.Butt))
            }
            fun at(r: Float, a: Double) = Offset((C + r * cos(a)).toFloat() * s, (C + r * sin(a)).toFloat() * s)
            fun rad(f: Float) = f * 2 * PI - PI / 2

            // hour ticks + labels
            for (h in 0 until 24) {
                val a = rad(h / 24f)
                val major = h % 6 == 0
                drawLine(cs.outline.copy(alpha = 0.6f), at(RING1 + 3, a), at(RING1 + if (major) 9 else 6, a), (if (major) 2f else 1f) * s)
                if (h % 3 == 0) {
                    val text = if (major) hourLabel(h) else hourLabel(h).replace(Regex(" ?[AP]M$"), "").removeSuffix(":00")
                    val st = TextStyle(color = if (major) cs.onSurfaceVariant else cs.outline, fontSize = ((if (major) 11f else 10f) * s).toSp(),
                        fontWeight = if (major) FontWeight.SemiBold else FontWeight.Normal, fontFeatureSettings = "tnum")
                    val m = tm.measure(text, st)
                    val p = at(RING1 + if (major) 22 else 17, a)
                    drawText(m, topLeft = Offset(p.x - m.size.width / 2f, p.y - m.size.height / 2f))
                }
            }

            // outer ring: grid state
            arc(RING0, RING1, 0f, 360f, cs.surfaceContainerHighest)
            for (r in ring) {
                val f0 = frac(r.from); val f1 = frac(r.to)
                if (f1 - f0 < 0.0005f) continue
                val selected = !r.on && (sel as? Sel.Out)?.o?.let { it.start < r.to && it.end > r.from } == true
                val r0 = if (selected) RING0 - 4 else RING0; val r1 = if (selected) RING1 + 3 else RING1
                if (r.on) arc(r0, r1, deg(f0), (f1 - f0) * 360f, e.grid, 0.55f)
                else {
                    arc(r0, r1, deg(f0), (f1 - f0) * 360f, e.crit, 0.3f)
                    // diagonal-ish hatching so "off" doesn't rely on red vs pink alone
                    var f = f0
                    while (f < f1) {
                        val a = rad(f); val b = rad(minOf(f1, f + 0.003f))
                        drawLine(e.crit, at(r0, a), at(r1, b), 1.6f * s)
                        f += 0.0045f
                    }
                }
            }

            // hour wedges
            for (h in mix) wedge(h, maxWh, sel, s, e.solar, e.batt, e.grid, cs.surfaceContainerHigh, cs.surfaceContainerHighest, cs.onSurface)

            // now hand
            if (showNow) {
                val a = rad(frac(now!!))
                drawLine(cs.onSurface, at(R0 - 2, a), at(RING1 + 4, a), 2f * s, StrokeCap.Round)
                drawCircle(cs.onSurface, 4f * s, at(RING1 + 4, a))
            }

            // centre
            drawCircle(cs.surfaceContainerLow, (R0 - 4) * s, Offset(C * s, C * s))
            val (l1, l2, l3) = when (val x = sel) {
                is Sel.Hour -> Triple("${hourLabel(x.h)}–${hourLabel(x.h + 1)}".replace(Regex(" (AM|PM)–(\\d+) \\1$"), "–$2 $1"),
                    mix[x.h].let { if (it.minutes > 0) fmtWh(it.homeWh) else "no data" }, mix[x.h].let { if (it.offMin > 0) "grid off ${it.offMin}m" else if (it.minutes > 0) "home use" else "" })
                is Sel.Out -> Triple("Grid off", fmtDuration(x.o.minutes.toDouble()), hhmm(x.o.start))
                null -> {
                    val on = mix.sumOf { it.minutes }
                    val off = mix.sumOf { it.offMin }
                    Triple(if (showNow) hhmm(now!!) else if (on > 0) "Whole day" else "",
                        if (outs.isNotEmpty()) "${outs.size} outage${if (outs.size > 1) "s" else ""}" else if (on > 0) "No outages" else "No data",
                        if (off > 0) "${fmtDuration(off.toDouble())} off" else if (on > 0) "grid all day" else "")
                }
            }
            centre(tm, l1, -15f, 10f, cs.outline, FontWeight.Medium, s)
            centre(tm, l2, 1f, 15f, cs.onSurface, FontWeight.Bold, s)
            centre(tm, l3, 17f, 10f, cs.onSurfaceVariant, FontWeight.Normal, s)
        }

        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
            Key(e.solar, "Solar"); Key(e.batt, "Battery"); Key(e.grid, "Grid (used)"); Key(e.grid.copy(alpha = 0.55f), "Grid available", ring = true); Key(e.crit, "Grid off", hatch = true)
        }
        Spacer(Modifier.size(10.dp))
        Details(sel, mix, outs, recs) { sel = null }
    }
}

private fun DrawScope.wedge(h: HourMix, maxWh: Double, sel: Sel?, s: Float, solar: Color, batt: Color, grid: Color, bg: Color, bgSel: Color, edge: Color) {
    val isSel = (sel as? Sel.Hour)?.h == h.hour
    val a0 = h.hour * 15f - 90f + 0.7f
    val sweep = 15f - 1.4f
    val mid = Math.toRadians((a0 + sweep / 2).toDouble())
    val shift = if (isSel) Offset((cos(mid) * 7 * s).toFloat(), (sin(mid) * 7 * s).toFloat()) else Offset.Zero
    fun ring(r0: Float, r1: Float, color: Color, alpha: Float = 1f, stroke: Stroke? = null) {
        val rm = (r0 + r1) / 2 * s
        drawArc(color, a0, sweep, false, Offset(C * s - rm, C * s - rm) + shift, Size(rm * 2, rm * 2), alpha, stroke ?: Stroke((r1 - r0) * s))
    }
    ring(R0, R1, if (isSel) bgSel else bg)
    val total = h.homeWh
    if (h.minutes == 0 || total <= 0) return
    val len = (R1 - R0) * max(0.16, sqrt(total / maxWh)).toFloat()
    var r = R0
    val dim = if (sel != null && !isSel) 0.45f else 1f
    for ((v, c) in listOf(h.solarWh to solar, h.battWh to batt, h.gridWh to grid)) {
        if (v <= 0) continue
        val r1 = r + (len * v / total).toFloat()
        ring(r, r1, c, dim)
        r = r1
    }
}

private fun DrawScope.centre(tm: androidx.compose.ui.text.TextMeasurer, text: String, dy: Float, sp: Float, color: Color, w: FontWeight, s: Float) {
    if (text.isEmpty()) return
    val m = tm.measure(text, TextStyle(color = color, fontSize = (sp * s).toSp(), fontWeight = w, fontFeatureSettings = "tnum"))
    drawText(m, topLeft = Offset(C * s - m.size.width / 2f, (C + dy) * s - m.size.height / 2f))
}

@Composable
private fun Key(color: Color, label: String, ring: Boolean = false, hatch: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Canvas(Modifier.size(12.dp)) {
            when {
                ring -> drawCircle(color, size.minDimension / 2 - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                hatch -> clipRect {
                    drawRect(color.copy(alpha = 0.3f))
                    for (i in -3..3) drawLine(color, Offset(i * 4.dp.toPx(), size.height), Offset(i * 4.dp.toPx() + size.height, 0f), 1.5.dp.toPx())
                }
                else -> drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class Item(val color: Color, val text: String, val hatch: Boolean = false)

/** One fact per line, each with the colour it has on the clock. */
@Composable
private fun Details(sel: Sel?, mix: List<HourMix>, outs: List<Outage>, recs: List<MinRec>, onClear: () -> Unit) {
    val e = LocalEnergy.current
    val cs = MaterialTheme.colorScheme
    var head = ""
    val items = ArrayList<Item>()
    when (sel) {
        is Sel.Hour -> {
            val h = mix[sel.h]
            head = "${hourLabel(sel.h)} – ${hourLabel(sel.h + 1)}" + if (h.minutes > 0) " · home used ${fmtWh(h.homeWh)}" else ""
            if (h.minutes == 0) items += Item(cs.outline, "The monitor has no data for this hour.")
            else {
                fun pct(v: Double) = if (h.homeWh > 0) (v / h.homeWh * 100).roundToInt() else 0
                items += Item(e.solar, "Solar: ${pct(h.solarWh)}% of home use (${fmtWh(h.solarWh)}) · made ${fmtWh(h.pvWh)} in total")
                items += Item(e.batt, "Battery: ${pct(h.battWh)}% (${fmtWh(h.battWh)})")
                items += Item(e.grid, "Grid: ${pct(h.gridWh)}% (${fmtWh(h.gridWh)})")
                fun hourOf(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(com.solarmonitor.app.ui.zone).hour
                outs.filter { hourOf(it.start) <= sel.h && hourOf(it.end - 1) >= sel.h }.forEach {
                    items += Item(e.crit, "Grid off ${hhmm(it.start)} – ${if (it.ongoing) "now" else hhmm(it.end)} (${fmtDuration(it.minutes.toDouble())})", hatch = true)
                }
                if (h.minutes < 55) items += Item(cs.outline, "The monitor saw ${h.minutes} of 60 minutes")
            }
        }
        is Sel.Out -> {
            val o = sel.o
            head = "Grid off · ${fmtDuration(o.minutes.toDouble())}"
            items += Item(e.crit, "Went off ${if (o.startKnown) "at" else "before"} ${hhmm(o.start)}", hatch = true)
            items += Item(if (o.ongoing) cs.outline else e.grid, if (o.ongoing) "Still off" else if (o.endKnown) "Came back at ${hhmm(o.end)}" else "The monitor went offline before it came back")
            Outages.during(recs, o)?.let {
                items += Item(e.load, "Home used ${fmtWh(it.homeWh)} during it")
                if (it.solarWh > 1) items += Item(e.solar, "Solar made ${fmtWh(it.solarWh)}")
                items += Item(e.batt, "Battery ${it.socFrom}% → ${it.socTo}%")
            }
        }
        null -> {}
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
        if (sel == null) {
            Text("Tap an hour or a red part of the ring to see what happened then.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(head, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
            items.forEach { it ->
                Row(Modifier.padding(vertical = 3.dp, horizontal = 0.dp), verticalAlignment = Alignment.Top) {
                    Canvas(Modifier.padding(top = 5.dp).size(11.dp)) {
                        if (it.hatch) {
                            drawCircle(it.color.copy(alpha = 0.3f))
                            drawCircle(it.color, style = Stroke(1.5.dp.toPx()))
                            drawLine(it.color, Offset(size.width * 0.2f, size.height * 0.8f), Offset(size.width * 0.8f, size.height * 0.2f), 1.5.dp.toPx())
                        } else drawCircle(it.color)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(it.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }
}
