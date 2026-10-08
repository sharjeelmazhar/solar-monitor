package com.solarmonitor.app.ui

import com.solarmonitor.app.ui.components.geodesicLines
import com.solarmonitor.app.ui.components.lineQuads
import com.solarmonitor.app.ui.components.uvSphere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** The 3D core must keep the same shapes as the web's three.js scene (IcosahedronGeometry(1.05, 1), SphereGeometry(0.62, 32, 32)). */
class EnergyCore3DTest {
    @Test fun shellHas120EdgesOnTheSphere() {
        val l = geodesicLines(1.05f)
        assertEquals(120 * 6, l.size)
        for (i in l.indices step 3) {
            val r = sqrt(l[i] * l[i] + l[i + 1] * l[i + 1] + l[i + 2] * l[i + 2])
            assertTrue("vertex off the sphere: $r", abs(r - 1.05f) < 1e-4f)
        }
        // every edge has the same short length (no edge jumps across the shell)
        for (e in 0 until 120) {
            val o = e * 6
            val d = sqrt((l[o] - l[o + 3]).let { it * it } + (l[o + 1] - l[o + 4]).let { it * it } + (l[o + 2] - l[o + 5]).let { it * it })
            assertTrue("edge $e length $d", d in 0.5f..0.7f)
        }
    }

    @Test fun wireQuadsAreTwoTrianglesPerEdgeWithBothSides() {
        val q = lineQuads(geodesicLines(1.05f))
        assertEquals(120 * 6 * 7, q.size)
        for (v in 0 until q.size / 7) assertTrue(q[v * 7 + 6] == 1f || q[v * 7 + 6] == -1f)
    }

    @Test fun sphereIndicesStayInRange() {
        val (v, idx) = uvSphere(0.62f, 32, 32)
        assertEquals(33 * 33 * 3, v.size)
        assertEquals(32 * 32 * 6, idx.size)
        assertTrue(idx.all { it in 0 until v.size / 3 })
    }
}
