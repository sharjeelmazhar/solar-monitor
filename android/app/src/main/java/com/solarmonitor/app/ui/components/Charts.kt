package com.solarmonitor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solarmonitor.app.data.MinRec
import com.solarmonitor.app.ui.hhmm
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class ChartSeries(val name: String, val color: Color, val values: FloatArray, val area: Boolean = false, val fmt: ((Float) -> String)? = null)

private fun niceStep(range: Float, n: Int): Float {
    val raw = range / n
    val p = 10f.pow(floor(log10(raw)))
    val f = raw / p
    return (if (f < 1.5f) 1f else if (f < 3f) 2f else if (f < 7f) 5f else 10f) * p
}

private class Axis(val lo: Float, val hi: Float, val step: Float)

private fun axis(series: List<ChartSeries>, yMin: Float?, yMax: Float?, ticks: Int, minRange: Float): Axis {
    var lo = yMin ?: Float.POSITIVE_INFINITY
    var hi = yMax ?: Float.NEGATIVE_INFINITY
    if (yMin == null || yMax == null) for (s in series) for (v in s.values) if (!v.isNaN()) {
        if (yMin == null && v < lo) lo = v
        if (yMax == null && v > hi) hi = v
    }
    if (lo.isInfinite()) lo = 0f
    if (hi.isInfinite()) hi = minRange
    if (yMin == null) lo = minOf(0f, lo)
    if (hi - lo < minRange) hi = lo + minRange
    val step = niceStep(hi - lo, ticks)
    if (yMin == null) lo = floor(lo / step) * step
    if (yMax == null) hi = ceil(hi / step) * step
    return Axis(lo, hi, step)
}

/** Legend that doubles as series on/off toggles. */
@Composable
fun ChartLegend(items: List<Pair<String, Color>>, hidden: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEach { (name, color) ->
            FilterChip(
                selected = name !in hidden, onClick = { onToggle(name) },
                label = { Text(name, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(if (name in hidden) color.copy(alpha = 0.3f) else color)) },
            )
        }
    }
}

/** Shared touch handling: tap or drag to inspect; the tooltip goes when you tap elsewhere, or after a few seconds. */
@Composable
private fun rememberHover(): Pair<Float?, (Float?) -> Unit> {
    var hover by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(hover) { if (hover != null) { delay(8000); hover = null } }
    return hover to { v: Float? -> hover = v }
}

@Composable
private fun Modifier.hoverInput(set: (Float?) -> Unit) = this
    .clearOnOutsideTap { set(null) }
    .pointerInput(Unit) { detectTapGestures(onPress = { set(it.x) }) }
    .pointerInput(Unit) { detectHorizontalDragGestures(onDragStart = { set(it.x) }) { c, _ -> set(c.position.x) } }

private class ChartColors(val grid: Color, val axis: Color, val text: Color, val tipBg: Color, val tipText: Color, val tipSub: Color, val surface: Color)

@Composable
private fun chartColors() = MaterialTheme.colorScheme.let {
    ChartColors(it.outlineVariant.copy(alpha = 0.55f), it.outline, it.onSurfaceVariant, it.inverseSurface, it.inverseOnSurface, it.inverseOnSurface.copy(alpha = 0.7f), it.surfaceContainerLow)
}

private fun DrawScope.tooltip(tm: TextMeasurer, c: ChartColors, x: Float, top: Float, title: String, rows: List<Triple<Color, String, String>>) {
    val d = density
    val small = TextStyle(color = c.tipSub, fontSize = 11.sp)
    val body = TextStyle(color = c.tipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", fontFamily = com.solarmonitor.app.ui.theme.GeistMono)
    val tt = tm.measure(title, small)
    val names = rows.map { tm.measure(it.second, body) }
    val vals = rows.map { tm.measure(it.third, body.copy(fontWeight = FontWeight.Bold)) }
    val pad = 10 * d
    val lineH = 18 * d
    val w = maxOf(tt.size.width.toFloat(), (names.maxOfOrNull { it.size.width } ?: 0) + 14 * d + 16 * d + (vals.maxOfOrNull { it.size.width } ?: 0)) + pad * 2
    val h = tt.size.height + rows.size * lineH + pad * 2 - 2 * d
    var left = x + 12 * d
    if (left + w > size.width) left = x - 12 * d - w
    left = left.coerceAtLeast(0f)
    drawRoundRect(c.tipBg, Offset(left, top), Size(w, h), CornerRadius(10 * d))
    drawText(tt, topLeft = Offset(left + pad, top + pad - 2 * d))
    rows.forEachIndexed { i, r ->
        val y = top + pad + tt.size.height + i * lineH
        drawCircle(r.first, 4 * d, Offset(left + pad + 4 * d, y + lineH / 2 - 1 * d))
        drawText(names[i], topLeft = Offset(left + pad + 14 * d, y + (lineH - names[i].size.height) / 2 - 1 * d))
        drawText(vals[i], topLeft = Offset(left + w - pad - vals[i].size.width, y + (lineH - vals[i].size.height) / 2 - 1 * d))
    }
}

/**
 * Time-series line chart. [xs] are epoch ms; NaN values break the line; a gap longer than [gapMs] also breaks it.
 */
@Composable
fun LineChart(
    xs: LongArray, series: List<ChartSeries>, xMin: Long, xMax: Long,
    modifier: Modifier = Modifier, height: Dp = 220.dp, yMin: Float? = null, yMax: Float? = null, minRange: Float = 100f,
    gapMs: Long = 20_000, yFmt: (Float) -> String, valueFmt: (Float) -> String, title: (Int) -> String = { hhmm(xs[it]) },
) {
    val tm = rememberTextMeasurer()
    val cc = chartColors()
    val (hover, setHover) = rememberHover()
    Canvas(modifier.fillMaxWidth().height(height).hoverInput(setHover)) {
        val d = density
        val label = TextStyle(color = cc.text, fontSize = 11.sp, fontFeatureSettings = "tnum", fontFamily = com.solarmonitor.app.ui.theme.GeistMono)
        val ax = axis(series, yMin, yMax, maxOf(2, ((size.height - 30 * d) / (46 * d)).roundToInt()), minRange)
        val yLabels = generateSequence(ax.lo) { it + ax.step }.takeWhile { it <= ax.hi + ax.step / 2 }.toList()
        val left = (yLabels.maxOf { tm.measure(yFmt(it), label).size.width } + 8 * d)
        val right = 4 * d; val top = 8 * d; val bottom = 22 * d
        val pw = size.width - left - right; val ph = size.height - top - bottom
        val span = (xMax - xMin).coerceAtLeast(1)
        fun X(t: Long) = left + (t - xMin).toFloat() / span * pw
        fun Y(v: Float) = top + (ax.hi - v) / (ax.hi - ax.lo) * ph

        for (v in yLabels) {
            val y = Y(v)
            drawLine(if (abs(v) < ax.step / 1000 && ax.lo < 0) cc.axis else cc.grid, Offset(left, y), Offset(size.width - right, y), 1 * d)
            val r = tm.measure(yFmt(v), label)
            drawText(r, topLeft = Offset(left - 6 * d - r.size.width, y - r.size.height / 2))
        }
        // time ticks on whole local minutes/hours
        val cand = longArrayOf(60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000, 7_200_000, 10_800_000, 21_600_000)
        val iv = cand.firstOrNull { pw / (span.toFloat() / it) >= (if (com.solarmonitor.app.ui.hour12) 74 else 62) * d } ?: 21_600_000   // "2:30 PM" is wider
        val off = java.util.TimeZone.getDefault().getOffset(xMin).toLong()
        var t = ((xMin + off) / iv + 1) * iv - off
        while (t <= xMax) {
            val x = X(t)
            if (x > left + 14 * d && x < size.width - right - 14 * d) {
                val r = tm.measure(hhmm(t), label)
                drawText(r, topLeft = Offset(x - r.size.width / 2, size.height - r.size.height))
            }
            t += iv
        }
        if (xs.isEmpty()) {
            val r = tm.measure("No data yet", label)
            drawText(r, topLeft = Offset(left + pw / 2 - r.size.width / 2, top + ph / 2 - r.size.height / 2))
            return@Canvas
        }
        val zeroY = Y(maxOf(ax.lo, 0f))
        for (s in series) {
            val path = Path(); val area = Path()
            var started = false; var segStartX = 0f; var lastX = 0f
            fun close() { if (started && s.area) { area.lineTo(lastX, zeroY); area.lineTo(segStartX, zeroY); area.close() } }
            for (i in xs.indices) {
                val v = s.values[i]
                val brk = v.isNaN() || (i > 0 && xs[i] - xs[i - 1] > gapMs)
                if (brk) { close(); started = false }
                if (v.isNaN()) continue
                val x = X(xs[i]); val y = Y(v)
                if (!started) { path.moveTo(x, y); if (s.area) { area.moveTo(x, zeroY); area.lineTo(x, y) }; segStartX = x; started = true }
                else { path.lineTo(x, y); if (s.area) area.lineTo(x, y) }
                lastX = x
            }
            close()
            if (s.area) drawPath(area, s.color.copy(alpha = 0.16f))
            drawPath(path, s.color, style = Stroke(2 * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // hover
        val hx = hover ?: return@Canvas
        if (hx < left || hx > size.width - right) return@Canvas
        val target = xMin + ((hx - left) / pw * span).toLong()
        var lo = 0; var hi = xs.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (xs[m] < target) lo = m else hi = m }
        val idx = if (abs(xs[lo] - target) < abs(xs[hi] - target)) lo else hi
        if (abs(xs[idx] - target) > span / pw * 40 * d) return@Canvas
        val x = X(xs[idx])
        drawLine(cc.axis, Offset(x, top), Offset(x, top + ph), 1 * d)
        val rows = mutableListOf<Triple<Color, String, String>>()
        for (s in series) {
            val v = s.values[idx]
            if (v.isNaN()) continue
            drawCircle(cc.surface, 6 * d, Offset(x, Y(v)))
            drawCircle(s.color, 4.5f * d, Offset(x, Y(v)))
            rows += Triple(s.color, s.name, (s.fmt ?: valueFmt)(v))
        }
        tooltip(tm, cc, x, top + 2 * d, title(idx), rows)
    }
}

/** Grouped bar chart, one group per day. */
@Composable
fun BarChart(
    labels: List<String>, series: List<ChartSeries>, modifier: Modifier = Modifier, height: Dp = 240.dp,
    yFmt: (Float) -> String, valueFmt: (Float) -> String, title: (Int) -> String,
    stacks: List<List<String>>? = null,   // series names drawn on top of each other in one bar (web BarChart stacks)
) {
    // each group is one bar per label; a group with several series is stacked bottom to top
    val groups = stacks?.map { g -> g.mapNotNull { n -> series.firstOrNull { it.name == n } } }?.filter { it.isNotEmpty() } ?: series.map { listOf(it) }
    val sums = groups.map { g -> ChartSeries(g.first().name, g.first().color, FloatArray(labels.size) { i -> g.sumOf { s -> s.values[i].takeIf { v -> !v.isNaN() && v > 0 }?.toDouble() ?: 0.0 }.toFloat() }) }
    val tm = rememberTextMeasurer()
    val cc = chartColors()
    val (hover, setHover) = rememberHover()
    Canvas(modifier.fillMaxWidth().height(height).hoverInput(setHover)) {
        val d = density
        val n = labels.size
        val label = TextStyle(color = cc.text, fontSize = 11.sp, fontFeatureSettings = "tnum", fontFamily = com.solarmonitor.app.ui.theme.GeistMono)
        val ax = axis(sums, null, null, maxOf(2, ((size.height - 30 * d) / (46 * d)).roundToInt()), 1000f)
        val yLabels = generateSequence(ax.lo) { it + ax.step }.takeWhile { it <= ax.hi + ax.step / 2 }.toList()
        val left = (yLabels.maxOf { tm.measure(yFmt(it), label).size.width } + 8 * d)
        val right = 4 * d; val top = 8 * d; val bottom = 22 * d
        val pw = size.width - left - right; val ph = size.height - top - bottom
        fun Y(v: Float) = top + (ax.hi - v) / (ax.hi - ax.lo) * ph
        for (v in yLabels) {
            drawLine(cc.grid, Offset(left, Y(v)), Offset(size.width - right, Y(v)), 1 * d)
            val r = tm.measure(yFmt(v), label)
            drawText(r, topLeft = Offset(left - 6 * d - r.size.width, Y(v) - r.size.height / 2))
        }
        if (n == 0) return@Canvas
        val gw = pw / n
        val every = ceil(n / maxOf(1f, floor(pw / (58 * d)))).toInt().coerceAtLeast(1)
        for (i in 0 until n) if ((n - 1 - i) % every == 0) {
            val r = tm.measure(labels[i], label)
            drawText(r, topLeft = Offset(left + (i + .5f) * gw - r.size.width / 2, size.height - r.size.height))
        }
        val k = groups.size
        val gap = 2 * d
        val bw = ((gw * 0.78f - (k - 1) * gap) / k).coerceIn(2 * d, 22 * d)
        val hoverIdx = hover?.let { ((it - left) / gw).toInt() }?.takeIf { it in 0 until n }
        if (hoverIdx != null) drawRect(cc.text.copy(alpha = 0.07f), Offset(left + hoverIdx * gw, top), Size(gw, ph))
        groups.forEachIndexed { j, g ->
            for (i in 0 until n) {
                val x = left + (i + .5f) * gw - (k * bw + (k - 1) * gap) / 2 + j * (bw + gap)
                var base = 0f
                val parts = g.filter { val v = it.values[i]; !v.isNaN() && v > 0 }
                parts.forEachIndexed { pi, s ->
                    val v = s.values[i]
                    val yb = Y(base) - if (pi > 0) gap else 0f   // 2 dp surface gap between stacked segments
                    val y = Y(base + v); val h = yb - y
                    if (h <= 0) { base += v; return@forEachIndexed }
                    val r = if (pi == parts.lastIndex) minOf(4 * d, bw / 2, h) else 0f   // only the top end is rounded
                    val p = Path().apply {
                        moveTo(x, yb); lineTo(x, y + r); quadraticTo(x, y, x + r, y); lineTo(x + bw - r, y); quadraticTo(x + bw, y, x + bw, y + r); lineTo(x + bw, yb); close()
                    }
                    drawPath(p, s.color)
                    base += v
                }
            }
        }
        if (hoverIdx != null) {
            val rows = series.map { Triple(it.color, it.name, if (it.values[hoverIdx].isNaN()) "–" else valueFmt(it.values[hoverIdx])) }
            tooltip(tm, cc, left + (hoverIdx + .5f) * gw, top + 2 * d, title(hoverIdx), rows)
        }
    }
}

/** 24-hour strip: grid colour where available, red where off, empty where not monitored. */
@Composable
fun GridStrip(minutes: List<MinRec>, dayStart: Long, color: Color, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    val off = com.solarmonitor.app.ui.theme.LocalEnergy.current.crit
    Canvas(modifier.fillMaxWidth().height(18.dp).clip(MaterialTheme.shapes.small)) {
        drawRect(bg)
        val w = size.width / 1440f
        for (m in minutes) {
            val x = (m.t - dayStart) / 86_400_000f * size.width
            drawRect(if (m.gridOn) color.copy(alpha = 0.75f) else off, Offset(x, 0f), Size(w + 0.6f, size.height))
        }
    }
}

/**
 * The web ChartCard: a card with a chart that can be opened full screen. [chart] draws the chart at the given height;
 * [legend] (optional) sits above it in both places.
 */
@Composable
fun ChartCard(
    title: String, sub: String? = null, info: String? = null,
    action: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    legend: (@Composable () -> Unit)? = null,
    chart: @Composable (Dp) -> Unit,
) {
    var full by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    SectionCard(title, sub = sub, info = info, action = {
        action?.invoke(this)
        androidx.compose.material3.FilledTonalIconButton(onClick = { full = true }) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.OpenInFull, "Open full screen", Modifier.size(18.dp))
        }
    }) {
        legend?.let { it(); androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
        chart(220.dp)
    }
    if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.IconButton(onClick = { full = false }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Close") }
                }
                legend?.let { it(); androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
                val h = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 260).coerceIn(300, 620)
                chart(h.dp)
            }
        }
    }
}
