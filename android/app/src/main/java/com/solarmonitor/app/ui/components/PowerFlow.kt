package com.solarmonitor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.data.Power
import com.solarmonitor.app.ui.fmtW
import com.solarmonitor.app.ui.theme.LocalEnergy
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Geometry in a 400 x 292 design space (same as the web dashboard)
private const val VW = 400f
private const val VH = 292f
private const val DIM = 0.35f   // opacity of a part that is not available (night solar, grid off, no battery), as on the web
private val SOLAR = Offset(72f, 78f)
private val GRID = Offset(328f, 78f)
private val INV = Offset(200f, 150f)
private val BATT = Offset(72f, 222f)
private val HOME = Offset(328f, 222f)

private fun curve(a: Offset, c1: Offset, c2: Offset, b: Offset) = Path().apply { moveTo(a.x, a.y); cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y) }

private class Flow(val path: Path) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val len = measure.length
    var phase = 0f
}

/**
 * Solar, grid, battery and home around the inverter. Dots travel along each line in the direction
 * energy is moving, faster and denser with more power.
 */
@Composable
fun PowerFlow(d: Live?, ratedW: Int, modifier: Modifier = Modifier, still: Boolean = false, idleW: Int = 15, noBatt: Boolean = false) {
    val idle by rememberUpdatedState(idleW)
    val e = LocalEnergy.current
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val live by rememberUpdatedState(d)
    val frozen by rememberUpdatedState(still)   // monitor offline: nothing moves, last values stay
    val flows = remember {
        listOf(
            // lines start and end on the circles' edges (outer r 32, inverter r 29), never inside them,
            // so a faded node (no battery, night, grid off) doesn't show a line running into it (same as the web)
            Flow(curve(Offset(104f, 78f), Offset(160f, 78f), Offset(200f, 98f), Offset(200f, 121f))),
            Flow(curve(Offset(296f, 78f), Offset(240f, 78f), Offset(200f, 98f), Offset(200f, 121f))),
            Flow(curve(Offset(104f, 222f), Offset(160f, 222f), Offset(200f, 202f), Offset(200f, 179f))),
            Flow(curve(Offset(200f, 179f), Offset(200f, 202f), Offset(240f, 222f), Offset(296f, 222f))),
        )
    }
    val frame = remember { mutableLongStateOf(0L) }
    val anim = remember { FloatArray(2) }   // [0] sun rotation degrees, [1] seconds
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            if (frozen) {
                // nothing moves: redraw once, then idle instead of drawing every frame
                frame.longValue = -1L
                last = 0L
                kotlinx.coroutines.delay(250)
                continue
            }
            androidx.compose.runtime.withFrameMillis { now ->
                val dt = if (last == 0L) 0f else min(0.1f, (now - last) / 1000f)
                last = now
                val x = live
                val ws = flowWatts(x, frozen, idle)
                for (i in 0..3) {
                    val frac = min(1f, ws[i] / ratedW.toFloat())
                    if (ws[i] > 0) flows[i].phase = (flows[i].phase + (38f + 150f * sqrt(frac)) * dt) % flows[i].len
                }
                val pv = x?.pvW ?: 0
                if (!frozen && x?.ok == true && pv >= 8) anim[0] = (anim[0] + dt * 360f / (24f - 21f * min(1f, pv / ratedW.toFloat()))) % 360f
                anim[1] += dt
                frame.longValue = now
            }
        }
    }

    val desc = d?.let { "Solar ${fmtW(it.pvW)}, home ${fmtW(it.loadW)}, ${if (noBatt) "no battery" else "battery ${it.battPct} percent"}, grid ${if (it.gridOn) "on" else "off"}" } ?: "Power flow"
    Canvas(modifier.fillMaxWidth().aspectRatio(VW / VH).semantics { contentDescription = desc }) {
        frame.longValue   // redraw every frame (draw phase only, no recomposition)
        val s = size.width / VW
        val x = live
        val ok = x?.ok == true && !frozen
        val ws = flowWatts(x, frozen, idle)
        val lineBase = cs.outlineVariant
        val colors = listOf(e.solar, e.grid, e.batt, e.load)
        val reverse = listOf(false, false, (x?.battW ?: 0) > 0, false)
        // parts that are not available right now are faded, line included (they stay drawn: they are wired up)
        val badge = if (x?.ever == true) com.solarmonitor.app.data.Weather.badge(x, com.solarmonitor.app.data.Weather.current()) else null
        val dim = listOf(
            badge == com.solarmonitor.app.data.Weather.Badge.Night,
            x?.ever == true && !x.gridOn,
            noBatt,
            false,
        )
        fun faded(on: Boolean, c: Offset, r: Float, block: () -> Unit) {
            if (!on) return block()
            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(c, r + 4f), androidx.compose.ui.graphics.Paint().apply { alpha = DIM })
            block()
            drawContext.canvas.restore()
        }

        withTransform({ scale(s, s, Offset.Zero) }) {
            // lines
            flows.forEachIndexed { i, f ->
                val active = ws[i] > 0
                drawPath(f.path, if (active) lerp(lineBase, colors[i], 0.42f) else lineBase, alpha = if (dim[i]) DIM else 1f, style = Stroke(4f, cap = StrokeCap.Round))
            }
            // particles
            flows.forEachIndexed { i, f ->
                if (ws[i] <= 0f) return@forEachIndexed
                val frac = min(1f, ws[i] / ratedW.toFloat())
                val n = 2 + (4 * sqrt(frac)).toInt()
                for (k in 0 until n) {
                    var pos = (f.phase + k * f.len / n) % f.len
                    if (reverse[i]) pos = f.len - pos
                    val p = f.measure.getPosition(pos)
                    val a = min(1f, min(pos, f.len - pos) / 30f)
                    drawCircle(colors[i].copy(alpha = 0.22f * a), 7f, p)
                    drawCircle(colors[i].copy(alpha = a), 3.6f, p)
                }
            }
            // nodes
            fun ring(c: Offset, r: Float, color: Color, on: Boolean) {
                drawCircle(cs.surfaceContainerLow, r, c)
                drawCircle(if (on) lerp(cs.surfaceContainerLow, color, 0.7f) else cs.outlineVariant, r, c, style = Stroke(2f))
            }
            ring(INV, 29f, e.inv, ok)
            translate(INV.x, INV.y) { inverterIcon(e.inv) }

            faded(dim[0], SOLAR, 32f) {
                ring(SOLAR, 32f, e.solar, ok && (x?.pvW ?: 0) >= 8)
                translate(SOLAR.x, SOLAR.y) { sunIcon(e.solar, anim[0]) }
            }

            // sky badge on the solar circle's top-right corner (like an unread badge), not faded (same as the web):
            // moon at night, cloud when cloudy (rain drops when raining)
            if (badge != null) {
                val b = Offset(SOLAR.x + 23f, SOLAR.y - 23f)
                drawCircle(cs.surfaceContainerLow, 11f, b)
                drawCircle(cs.outlineVariant, 11f, b, style = Stroke(1.5f))
                if (badge == com.solarmonitor.app.data.Weather.Badge.Night) {
                    drawCircle(Color(0xFF8B9CF7), 6f, b)
                    drawCircle(cs.surfaceContainerLow, 5f, b + Offset(2.8f, -2.4f))
                } else {
                    val rain = badge == com.solarmonitor.app.data.Weather.Badge.Rain
                    val c = b + Offset(0f, if (rain) -2f else 0f)
                    val cloud = Color(0xFF94A3B8)
                    drawCircle(cloud, 3.4f, c + Offset(-3f, 0.5f))
                    drawCircle(cloud, 4.2f, c + Offset(1f, -1.8f))
                    drawCircle(cloud, 3f, c + Offset(4.4f, 1f))
                    drawRoundRect(cloud, c + Offset(-6.4f, 0.5f), Size(13.8f, 3.6f), CornerRadius(1.8f))
                    if (rain) for (dx in listOf(-3f, 1f, 5f)) drawLine(Color(0xFF60A5FA), c + Offset(dx, 6.5f), c + Offset(dx - 1f, 9.1f), 1.5f, StrokeCap.Round)
                }
            }

            val gridOn = x?.gridOn == true
            faded(dim[1], GRID, 32f) {
                ring(GRID, 32f, e.grid, gridOn)
                translate(GRID.x, GRID.y) { pylonIcon(e.grid, if (x == null || gridOn) 1f else 0.35f); if (x != null && !gridOn) drawLine(e.crit, Offset(-19f, 19f), Offset(19f, -19f), 3f, StrokeCap.Round) }
            }

            val battPct = x?.battPct ?: 0
            val bs = x?.let { Power.batt(it, idle) } ?: Power.Batt.Idle
            val charging = ok && bs == Power.Batt.Charging
            faded(dim[2], BATT, 32f) {
            ring(BATT, 32f, e.batt, ok && bs != Power.Batt.Idle)
            translate(BATT.x, BATT.y) {
                val fill = when { battPct <= 20 -> e.crit; battPct <= 45 -> e.warn; else -> e.batt }
                val pulse = if (charging) 0.75f + 0.25f * sin(anim[1] * 2 * PI.toFloat() / 1.6f) else 1f
                batteryIcon(cs.onSurfaceVariant, fill.copy(alpha = if (noBatt) 0.5f else pulse), battPct / 100f, charging, cs.surfaceContainerLow)
            }
            }

            ring(HOME, 32f, e.load, ok && (x?.loadW ?: 0) >= 8)
            translate(HOME.x, HOME.y) { homeIcon(e.load, ((x?.loadPct ?: 0) / 100f).coerceIn(if ((x?.loadW ?: 0) > 0) 0.06f else 0f, 1f)) }
        }

        // labels (drawn unscaled so text stays crisp)
        fun label(text: String, at: Offset, big: Boolean) {
            val style = TextStyle(
                color = if (big) cs.onSurface else cs.onSurfaceVariant,
                fontSize = ((if (big) 19f else 12f) * s).toSp(),
                fontWeight = if (big) FontWeight.Bold else FontWeight.Medium,
                fontFeatureSettings = "tnum", fontFamily = if (big) com.solarmonitor.app.ui.theme.GeistMono else com.solarmonitor.app.ui.theme.Geist,
            )
            val r = tm.measure(text, style)
            drawText(r, topLeft = Offset(at.x * s - r.size.width / 2f, at.y * s - r.size.height / 2f))
        }
        if (x != null && x.ever) {
            label(fmtW(x.pvW), Offset(SOLAR.x, SOLAR.y - 48), true)
            label(when (badge) {
                com.solarmonitor.app.data.Weather.Badge.Night -> "Solar · night"
                com.solarmonitor.app.data.Weather.Badge.Cloudy -> "Solar · cloudy"
                com.solarmonitor.app.data.Weather.Badge.Rain -> "Solar · rain"
                com.solarmonitor.app.data.Weather.Badge.CloudyGuess -> "Solar · cloudy?"
                null -> "Solar"
            }, Offset(SOLAR.x, SOLAR.y + 46), false)
            label(if (x.gridOn) (if (x.gridW > 0) fmtW(x.gridW) else "${x.gridV.toInt()} V") else "Off", Offset(GRID.x, GRID.y - 48), true)
            label(if (x.gridOn) (if (x.gridW > 0) "Grid · in use" else "Grid · standby") else "Grid off", Offset(GRID.x, GRID.y + 46), false)
            label(if (noBatt) "—" else "${x.battPct}%", Offset(BATT.x, BATT.y + 50), true)
            if (noBatt) label("No battery", Offset(BATT.x, BATT.y - 46), false) else label(when (Power.batt(x, idle)) { Power.Batt.Charging -> "Charging ${fmtW(x.battW)}"; Power.Batt.Discharging -> "Discharging ${fmtW(-x.battW)}"; else -> if (x.battPct >= 99) "Full" else "Idle" }, Offset(BATT.x, BATT.y - 46), false)
            label(fmtW(x.loadW), Offset(HOME.x, HOME.y + 50), true)
            label("Home · ${x.loadPct}%", Offset(HOME.x, HOME.y - 46), false)
        }
    }
}

/** Watts flowing on each line: solar, grid, battery, home (0 = idle). */
private fun flowWatts(x: Live?, still: Boolean, idleW: Int): FloatArray {
    if (x == null || !x.ok || still) return FloatArray(4)
    fun f(w: Int) = if (kotlin.math.abs(w) >= 8) kotlin.math.abs(w).toFloat() else 0f
    val b = if (Power.batt(x, idleW) == Power.Batt.Idle) 0f else kotlin.math.abs(x.battW).toFloat()
    return floatArrayOf(f(x.pvW), f(x.gridW), b, f(x.loadW))
}

private fun DrawScope.sunIcon(c: Color, deg: Float) {
    drawCircle(c, 7.5f, Offset.Zero)
    rotate(deg, Offset.Zero) {
        for (i in 0 until 8) {
            rotate(i * 45f, Offset.Zero) { drawLine(c, Offset(0f, -12.5f), Offset(0f, -17f), 2.4f, StrokeCap.Round) }
        }
    }
}

private fun DrawScope.pylonIcon(c: Color, alpha: Float) {
    val st = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val col = c.copy(alpha = alpha)
    drawPath(Path().apply { moveTo(-9f, 17f); lineTo(-3f, -10f); lineTo(0f, -16f); lineTo(3f, -10f); lineTo(9f, 17f) }, col, style = st)
    drawPath(Path().apply {
        moveTo(-15f, -7f); lineTo(15f, -7f); moveTo(-11f, 1f); lineTo(11f, 1f)
        moveTo(-3f, -10f); lineTo(5f, 1f); moveTo(3f, -10f); lineTo(-5f, 1f)
        moveTo(-5.5f, 1f); lineTo(7.5f, 17f); moveTo(5.5f, 1f); lineTo(-7.5f, 17f)
    }, col, style = st)
    for (px in listOf(-15f, 15f)) drawLine(col, Offset(px, -7f), Offset(px, -3f), 2.4f, StrokeCap.Round)
    for (px in listOf(-11f, 11f)) drawLine(col, Offset(px, 1f), Offset(px, 5f), 2.4f, StrokeCap.Round)
}

private fun DrawScope.batteryIcon(outline: Color, fill: Color, frac: Float, charging: Boolean, bg: Color) {
    drawRoundRect(outline, Offset(-16f, -10f), Size(29f, 20f), CornerRadius(4.5f), style = Stroke(2f))
    drawRoundRect(outline, Offset(14f, -4.5f), Size(3.5f, 9f), CornerRadius(1.5f))
    drawRoundRect(fill, Offset(-13f, -7f), Size(maxOf(1.5f, 23f * frac.coerceIn(0f, 1f)), 14f), CornerRadius(2f))
    if (charging) {
        val bolt = Path().apply { moveTo(0.5f, -8f); lineTo(-5f, 1f); lineTo(-0.5f, 1f); lineTo(-1.5f, 8f); lineTo(4f, -1f); lineTo(-0.5f, -1f); close() }
        drawPath(bolt, bg)
    }
}

private fun DrawScope.homeIcon(c: Color, frac: Float) {
    val house = Path().apply { moveTo(-12f, -2f); lineTo(0f, -13f); lineTo(12f, -2f); lineTo(12f, 14f); lineTo(-12f, 14f); close() }
    val h = 27f * frac
    clipPath(house) { drawRect(c.copy(alpha = 0.85f), Offset(-14f, 14f - h), Size(28f, h)) }
    drawPath(Path().apply { moveTo(-15f, -1f); lineTo(0f, -14f); lineTo(15f, -1f); moveTo(-12f, -3.5f); lineTo(-12f, 14f); lineTo(12f, 14f); lineTo(12f, -3.5f) },
        c, style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.inverterIcon(c: Color) {
    drawRoundRect(c, Offset(-13f, -15f), Size(26f, 30f), CornerRadius(5f), style = Stroke(2.2f))
    drawLine(c, Offset(-6f, -8f), Offset(6f, -8f), 2.2f, StrokeCap.Round)
    drawPath(Path().apply { moveTo(-7f, 4f); quadraticTo(-3.5f, -4f, 0f, 4f); quadraticTo(3.5f, 12f, 7f, 4f) }, c, style = Stroke(2.2f, cap = StrokeCap.Round))
}
