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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.ui.theme.LocalEnergy
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// The same scene as the web dashboard's three.js core (web/src/components/flow/EnergyCore3D.tsx), projected by hand
// on a Canvas: a geodesic shell (icosahedron subdivided once, 120 edges) turning slowly, a glowing heart that
// grows with solar, and a ring of 900 coloured particles whose speed follows the home load. Tilted 0.42 rad.

private class Geo(val v: List<FloatArray>, val e: List<Pair<Int, Int>>)

/** Icosahedron subdivided once and pushed onto the unit sphere (42 vertices, 120 edges). */
private val GEO: Geo = run {
    val p = ((1 + sqrt(5.0)) / 2).toFloat()
    val base = listOf(
        floatArrayOf(-1f, p, 0f), floatArrayOf(1f, p, 0f), floatArrayOf(-1f, -p, 0f), floatArrayOf(1f, -p, 0f),
        floatArrayOf(0f, -1f, p), floatArrayOf(0f, 1f, p), floatArrayOf(0f, -1f, -p), floatArrayOf(0f, 1f, -p),
        floatArrayOf(p, 0f, -1f), floatArrayOf(p, 0f, 1f), floatArrayOf(-p, 0f, -1f), floatArrayOf(-p, 0f, 1f),
    )
    fun norm(a: FloatArray): FloatArray { val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    val verts = base.map(::norm).toMutableList()
    fun dist(a: FloatArray, b: FloatArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    val adj = { i: Int, j: Int -> dist(verts[i], verts[j]) < 1.1f }
    val faces = ArrayList<IntArray>()
    for (i in 0 until 12) for (j in i + 1 until 12) for (k in j + 1 until 12) if (adj(i, j) && adj(j, k) && adj(i, k)) faces += intArrayOf(i, j, k)
    val mids = HashMap<Long, Int>()
    fun mid(a: Int, b: Int): Int = mids.getOrPut(min(a, b).toLong() * 100 + maxOf(a, b)) {
        verts += norm(floatArrayOf((verts[a][0] + verts[b][0]) / 2, (verts[a][1] + verts[b][1]) / 2, (verts[a][2] + verts[b][2]) / 2)); verts.size - 1
    }
    val edges = LinkedHashSet<Pair<Int, Int>>()
    fun edge(a: Int, b: Int) { edges += if (a < b) a to b else b to a }
    for (f in faces) {
        val (a, b, c) = Triple(f[0], f[1], f[2])
        val ab = mid(a, b); val bc = mid(b, c); val ca = mid(c, a)
        edge(a, ab); edge(ab, b); edge(b, bc); edge(bc, c); edge(c, ca); edge(ca, a); edge(ab, bc); edge(bc, ca); edge(ca, ab)
    }
    Geo(verts, edges.toList())
}

private class Particle(val r: Float, val a: Float, val y: Float, val kind: Int)

@Composable
fun EnergyCore3D(d: Live, ratedW: Int, modifier: Modifier = Modifier) {
    val e = LocalEnergy.current
    val live by rememberUpdatedState(d)
    val particles = remember {
        val rnd = Random(11)
        List(900) { i -> val r = 1.45f + rnd.nextFloat() * 0.6f; Particle(r, rnd.nextFloat() * 6.2832f, (rnd.nextFloat() - 0.5f) * 0.35f * r, i % 3) }
    }
    val t = remember { FloatArray(4) }   // shell y, shell x, ring angle, ring speed
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        t[3] = 0.2f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else min(0.05f, (now - last) / 1e9f)
                last = now
                val load = (live.loadW / ratedW.toFloat()).coerceIn(0f, 1f)
                t[3] += ((0.12f + 0.9f * load) - t[3]) * min(1f, dt * 2)
                t[0] += dt * 0.25f; t[1] += dt * 0.08f; t[2] += dt * t[3]
                frame.longValue = now
            }
        }
    }

    Canvas(modifier.aspectRatio(1f)) {
        frame.longValue   // redraw every frame
        val x = live
        val c = Offset(size.width / 2, size.height / 2)
        // same camera as the web canvas (fov 45, z = 5.2) so the scene fills the same share of the box
        val focal = size.minDimension / 2 / 0.4142f
        val camZ = 5.2f
        val tilt = 0.42f; val ct = cos(tilt); val st = sin(tilt)
        val solar = (x.pvW / ratedW.toFloat()).coerceIn(0f, 1f)
        val battery = (x.battPct / 100f).coerceIn(0f, 1f)

        fun proj(px: Float, py: Float, pz: Float, out: FloatArray) {
            // group tilt about X, then perspective
            val y1 = py * ct - pz * st; val z1 = py * st + pz * ct
            val f = focal / (camZ - z1)
            out[0] = c.x + px * f; out[1] = c.y - y1 * f; out[2] = z1
        }

        // glowing heart (additive, grows with solar)
        val heartR = focal / camZ * 0.62f * (0.8f + 0.25f * solar + 0.1f * battery)
        val glow = 0.25f + 0.45f * solar + 0.08f * sin(t[0] * 10f)
        drawCircle(Brush.radialGradient(listOf(e.solar.copy(alpha = glow), e.solar.copy(alpha = glow * 0.35f), Color.Transparent), c, heartR * 1.6f),
            heartR * 1.6f, c, blendMode = BlendMode.Plus)

        // geodesic shell: rotate about Y then X (own rotation), drawn back to front by depth alpha
        val cy = cos(t[0]); val sy = sin(t[0]); val cx = cos(t[1]); val sx = sin(t[1])
        val pts = Array(GEO.v.size) { FloatArray(3) }
        GEO.v.forEachIndexed { i, v ->
            val r = 1.05f
            val x1 = v[0] * cy + v[2] * sy; val z1 = -v[0] * sy + v[2] * cy
            val y2 = v[1] * cx - z1 * sx; val z2 = v[1] * sx + z1 * cx
            proj(x1 * r, y2 * r, z2 * r, pts[i])
        }
        val line = 1.1f * density
        for ((i, j) in GEO.e) {
            val depth = ((pts[i][2] + pts[j][2]) / 2 / 1.05f + 1) / 2   // 0 back .. 1 front
            drawLine(e.inv.copy(alpha = 0.25f + 0.45f * depth), Offset(pts[i][0], pts[i][1]), Offset(pts[j][0], pts[j][1]), line, StrokeCap.Round)
        }

        // particle ring
        val q = FloatArray(3)
        val colors = arrayOf(e.solar, e.batt, e.inv)
        val dot = 0.035f * focal / camZ / 3.6f
        for (p in particles) {
            val a = p.a + t[2]
            proj(cos(a) * p.r, p.y, sin(a) * p.r, q)
            val near = (q[2] / 2.05f + 1) / 2
            drawCircle(colors[p.kind].copy(alpha = 0.45f + 0.4f * near), dot * (0.7f + 0.6f * near) * focal / (camZ - q[2]) / (focal / camZ), Offset(q[0], q[1]), blendMode = BlendMode.SrcOver)
        }
    }
}
