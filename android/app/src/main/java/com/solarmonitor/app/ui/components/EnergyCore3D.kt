package com.solarmonitor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.ui.theme.LocalEnergy
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Same idea as the web dashboard's 3D core (web/src/components/flow/EnergyCore3D.tsx), drawn with plain
// perspective projection on a Canvas: a slowly turning icosahedron, a glowing heart and a ring of particles.
// Spin follows solar power, the heart's glow the battery level, the ring's speed the home load.

private val PHI = ((1 + sqrt(5.0)) / 2).toFloat()
private val VERTS = arrayOf(
    floatArrayOf(-1f, PHI, 0f), floatArrayOf(1f, PHI, 0f), floatArrayOf(-1f, -PHI, 0f), floatArrayOf(1f, -PHI, 0f),
    floatArrayOf(0f, -1f, PHI), floatArrayOf(0f, 1f, PHI), floatArrayOf(0f, -1f, -PHI), floatArrayOf(0f, 1f, -PHI),
    floatArrayOf(PHI, 0f, -1f), floatArrayOf(PHI, 0f, 1f), floatArrayOf(-PHI, 0f, -1f), floatArrayOf(-PHI, 0f, 1f),
).map { v -> val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }

/** The 30 edges: vertex pairs at the icosahedron's edge length. */
private val EDGES: List<Pair<Int, Int>> = buildList {
    for (i in VERTS.indices) for (j in i + 1 until VERTS.size) {
        val a = VERTS[i]; val b = VERTS[j]
        val d = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
        if (d < 1.1f) add(i to j)
    }
}

private class Particle(val r: Float, val a: Float, val y: Float, val speed: Float, val size: Float)

@Composable
fun EnergyCore3D(d: Live, ratedW: Int, modifier: Modifier = Modifier) {
    val e = LocalEnergy.current
    val live by rememberUpdatedState(d)
    val particles = remember { Random(7).let { rnd -> List(110) { Particle(1.35f + rnd.nextFloat() * 0.45f, rnd.nextFloat() * 6.2832f, (rnd.nextFloat() - 0.5f) * 0.18f, 0.6f + rnd.nextFloat() * 0.8f, 0.6f + rnd.nextFloat()) } } }
    val t = remember { FloatArray(3) }   // [0] core angle, [1] ring angle, [2] seconds
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else min(0.05f, (now - last) / 1e9f)
                last = now
                val x = live
                val solar = (x.pvW / ratedW.toFloat()).coerceIn(0f, 1f)
                val load = (x.loadW / ratedW.toFloat()).coerceIn(0f, 1f)
                t[0] += dt * (0.18f + 0.9f * solar)
                t[1] += dt * (0.12f + 0.7f * load)
                t[2] += dt
                frame.longValue = now
            }
        }
    }

    Canvas(modifier.aspectRatio(1f)) {
        frame.longValue   // redraw every frame
        val x = live
        val c = Offset(size.width / 2, size.height / 2)
        val unit = size.minDimension / 2 / 2.15f
        val battery = (x.battPct / 100f).coerceIn(0f, 1f)
        val heart = lerp(e.batt, e.solar, (x.pvW / ratedW.toFloat()).coerceIn(0f, 1f) * 0.6f)
        val pulse = 0.85f + 0.15f * sin(t[2] * 2.2f)

        // glowing heart
        drawCircle(Brush.radialGradient(listOf(heart.copy(alpha = 0.55f * pulse), heart.copy(alpha = 0.12f), Color.Transparent), c, unit * 1.3f), unit * 1.3f, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), heart.copy(alpha = 0.9f), heart.copy(alpha = 0f)), c, unit * (0.32f + 0.18f * battery)), unit * (0.32f + 0.18f * battery), c)

        // perspective projection of a point rotated about Y then tilted about X
        val ay = t[0]; val ax = 0.45f + 0.15f * sin(t[2] * 0.3f)
        val cy = cos(ay); val sy = sin(ay); val cx = cos(ax); val sx = sin(ax)
        fun project(px: Float, py: Float, pz: Float, out: FloatArray) {
            val x1 = px * cy + pz * sy; val z1 = -px * sy + pz * cy
            val y2 = py * cx - z1 * sx; val z2 = py * sx + z1 * cx
            val f = 3.2f / (3.2f + z2)
            out[0] = c.x + x1 * f * unit; out[1] = c.y + y2 * f * unit; out[2] = z2
        }

        // wireframe icosahedron, front edges brighter
        val p = Array(VERTS.size) { FloatArray(3) }
        VERTS.forEachIndexed { i, v -> project(v[0] * 0.95f, v[1] * 0.95f, v[2] * 0.95f, p[i]) }
        val edge = lerp(e.inv, e.solar, 0.35f)
        for ((i, j) in EDGES) {
            val depth = ((p[i][2] + p[j][2]) / 2 + 1) / 2   // 0 front .. 1 back
            drawLine(edge.copy(alpha = 0.85f - 0.6f * depth), Offset(p[i][0], p[i][1]), Offset(p[j][0], p[j][1]), (2.2f - depth) * density / 1.6f, StrokeCap.Round)
        }
        for (v in p) drawCircle(edge.copy(alpha = 0.9f - 0.5f * (v[2] + 1) / 2), 2.4f * density / 1.6f, Offset(v[0], v[1]))

        // particle ring on a tilted plane
        val q = FloatArray(3)
        for (pt in particles) {
            val a = pt.a + t[1] * pt.speed
            project(cos(a) * pt.r, pt.y, sin(a) * pt.r, q)
            val depth = (q[2] / 2.1f + 1) / 2
            drawCircle(e.solar.copy(alpha = (0.95f - 0.7f * depth) * 0.9f), pt.size * (1.5f - depth) * density * 0.8f, Offset(q[0], q[1]))
        }
    }
}
