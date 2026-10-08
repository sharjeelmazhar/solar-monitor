package com.solarmonitor.app.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.solarmonitor.app.data.Live
import com.solarmonitor.app.ui.theme.LocalEnergy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// The web dashboard's three.js "energy core" (web/src/components/flow/EnergyCore3D.tsx), drawn the same way on the
// phone's GPU with OpenGL ES 2.0: the same camera (fov 45, z 5.2), a wireframe geodesic shell (icosahedron, detail 1)
// turning slowly, an additive glowing sphere that grows with solar, and 900 additive coloured points whose ring
// spins faster with the home load. Purely decorative: all numbers are drawn by PowerFlow on top.
// Rendering runs on its own thread, is paced to at most 60 fps, and stops when the app or the card is off screen.

@Composable
fun EnergyCore3D(d: Live, ratedW: Int, modifier: Modifier = Modifier) {
    val e = LocalEnergy.current
    val owner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx -> CoreView(ctx, e.inv, e.solar, e.batt).also { it.scene.linePx = (ctx.resources.displayMetrics.density * 0.5f).coerceAtLeast(1f) } },
        update = { v ->
            v.scene.solar = (d.pvW / ratedW.toFloat()).coerceIn(0f, 1f)
            v.scene.load = (d.loadW / ratedW.toFloat()).coerceIn(0f, 1f)
            v.scene.battery = (d.battPct / 100f).coerceIn(0f, 1f)
            v.scene.setColors(e.inv, e.solar, e.batt)
        },
        onRelease = { it.stop() },
        modifier = modifier.aspectRatio(1f),
    )
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) CoreView.paused = true
            if (ev == Lifecycle.Event.ON_START) CoreView.paused = false
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

private class CoreView(ctx: Context, inv: Color, solar: Color, batt: Color) : TextureView(ctx), TextureView.SurfaceTextureListener {
    companion object { @Volatile var paused = false }
    val scene = CoreScene(inv, solar, batt)
    private var thread: GlThread? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    // Like the web canvas (dpr capped at 1.5): draw at most 1.5 pixels per dp and let the view scale it up.
    // On a 3.5x phone screen that is ~5x fewer pixels for the GPU, and the soft glow looks the same.
    private val scale = (1.5f / resources.displayMetrics.density).coerceAtMost(1f)
    private fun bufW(w: Int) = (w * scale).toInt().coerceAtLeast(1)
    private fun bufH(h: Int) = (h * scale).toInt().coerceAtLeast(1)

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        st.setDefaultBufferSize(bufW(w), bufH(h))
        thread = GlThread(st, scene).also { it.resize(bufW(w), bufH(h)); it.start() }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        st.setDefaultBufferSize(bufW(w), bufH(h))
        thread?.resize(bufW(w), bufH(h))
    }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stop(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    fun stop() {
        thread?.let { it.quit(); it.join(500) }
        thread = null
    }
}

/** EGL setup + frame loop on a dedicated thread. */
private class GlThread(private val st: SurfaceTexture, private val scene: CoreScene) : Thread("EnergyCore3D") {
    @Volatile private var running = true
    @Volatile private var w = 1
    @Volatile private var h = 1
    @Volatile private var sizeChanged = true

    fun resize(width: Int, height: Int) { w = width; h = height; sizeChanged = true }
    fun quit() { running = false; interrupt() }

    override fun run() {
        val dpy: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return
        val cfg = chooseConfig(dpy, 4) ?: chooseConfig(dpy, 0) ?: return
        val ctx: EGLContext = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf: EGLSurface = EGL14.eglCreateWindowSurface(dpy, cfg, st, intArrayOf(EGL14.EGL_NONE), 0)
        if (surf == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
            EGL14.eglDestroyContext(dpy, ctx); EGL14.eglTerminate(dpy); return
        }
        try {
            scene.init()
            // A decoration must never slow the app down. Frame budget: 60 fps; if the GPU can't keep up drop to 30 fps,
            // and if it is really slow (software rendering, very old phones) only refresh once a second.
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
            var slow = Regex("SwiftShader|llvmpipe|softpipe", RegexOption.IGNORE_CASE).containsMatchIn(renderer)
            var avgMs = 0f
            var interval = if (slow) 1000L else 16L
            var last = System.nanoTime()
            while (running) {
                if (CoreView.paused) { try { sleep(200) } catch (_: InterruptedException) {}; last = System.nanoTime(); continue }
                val now = System.nanoTime()
                val dt = min(0.05f, (now - last) / 1e9f)
                last = now
                if (sizeChanged) { sizeChanged = false; GLES20.glViewport(0, 0, w, h) }
                scene.draw(dt, w, h)
                if (!EGL14.eglSwapBuffers(dpy, surf)) break
                val spent = (System.nanoTime() - now) / 1_000_000
                avgMs = if (avgMs == 0f) spent.toFloat() else avgMs * 0.9f + spent * 0.1f
                if (!slow) interval = when { avgMs > 120 -> { slow = true; 1000L }; avgMs > 20 -> 33L; else -> 16L }
                val wait = (interval - spent).coerceAtLeast(4)   // always leave the CPU/GPU some room
                try { sleep(wait) } catch (_: InterruptedException) {}
            }
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
    }

    private fun chooseConfig(dpy: EGLDisplay, samples: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL14.EGL_SAMPLES, samples, EGL14.EGL_NONE,
        )
        val out = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        return if (EGL14.eglChooseConfig(dpy, attrs, 0, out, 0, 1, n, 0) && n[0] > 0) out[0] else null
    }
}

/** Geometry, shaders and animation state. All GL calls happen on the render thread. */
private class CoreScene(inv: Color, solar: Color, batt: Color) {
    @Volatile var solar = 0f
    @Volatile var load = 0f
    @Volatile var battery = 0f
    @Volatile private var cInv = inv
    @Volatile private var cSolar = solar
    @Volatile private var cBatt = batt
    @Volatile private var colorsDirty = true
    fun setColors(inv: Color, solar: Color, batt: Color) {
        if (inv != cInv || solar != cSolar || batt != cBatt) { cInv = inv; cSolar = solar; cBatt = batt; colorsDirty = true }
    }

    // animation: shell angles, ring angle, ring speed, clock
    private var shellY = 0f; private var shellX = 0f; private var ring = 0f; private var speed = 0.2f; private var clock = 0f

    private var flatProg = 0; private var pointProg = 0
    private var lineProg = 0
    private lateinit var shellQuads: FloatBuffer; private var shellCount = 0
    private lateinit var sphereVerts: FloatBuffer; private lateinit var sphereIdx: ShortBuffer; private var sphereCount = 0
    private lateinit var pointPos: FloatBuffer
    private lateinit var pointCol: FloatBuffer
    private val proj = FloatArray(16); private val view = FloatArray(16); private val group = FloatArray(16)
    private val model = FloatArray(16); private val mv = FloatArray(16); private val mvp = FloatArray(16); private val tmp = FloatArray(16)
    var linePx = 1f   // wire thickness in pixels (set from the screen density)

    fun init() {
        // each wire edge is a thin quad widened on screen in the vertex shader: same thickness on every phone, unlike
        // glLineWidth, which many GPUs cap at 1 pixel (too thin on high-density screens)
        lineProg = program(
            "uniform mat4 uMvp; uniform vec2 uHalf; uniform float uWidth; attribute vec3 aPos; attribute vec3 aOther; attribute float aSide;" +
                "void main(){ vec4 a = uMvp * vec4(aPos, 1.0); vec4 b = uMvp * vec4(aOther, 1.0);" +
                " vec2 d = normalize(b.xy / b.w * uHalf - a.xy / a.w * uHalf); vec2 n = vec2(-d.y, d.x) * aSide * uWidth * 0.5;" +
                " a.xy += n / uHalf * a.w; gl_Position = a; }",
            "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor = uColor; }",
        )
        flatProg = program(
            "uniform mat4 uMvp; attribute vec3 aPos; void main(){ gl_Position = uMvp * vec4(aPos, 1.0); }",
            "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor = uColor; }",
        )
        // three.js PointsMaterial with sizeAttenuation: size * (drawing-buffer height / 2) / -z
        pointProg = program(
            "uniform mat4 uMv; uniform mat4 uP; uniform float uSize; uniform float uScale; attribute vec3 aPos; attribute vec3 aCol; varying vec3 vCol;" +
                "void main(){ vec4 p = uMv * vec4(aPos, 1.0); gl_Position = uP * p; gl_PointSize = uSize * uScale / -p.z; vCol = aCol; }",
            "precision mediump float; uniform float uOpacity; varying vec3 vCol; void main(){ gl_FragColor = vec4(vCol, uOpacity); }",
        )
        shellQuads = floats(lineQuads(geodesicLines(1.05f))); shellCount = shellQuads.capacity() / 7
        val (sv, si) = uvSphere(0.62f, 32, 32)
        sphereVerts = floats(sv); sphereIdx = shorts(si); sphereCount = si.size
        val rnd = Random(11)
        val pos = FloatArray(900 * 3)
        for (i in 0 until 900) {
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            val r = 1.45f + rnd.nextFloat() * 0.6f
            pos[i * 3] = cos(a) * r; pos[i * 3 + 1] = (rnd.nextFloat() - 0.5f) * 0.35f * r; pos[i * 3 + 2] = sin(a) * r
        }
        pointPos = floats(pos)
        pointCol = floats(FloatArray(900 * 3))
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
    }

    fun draw(dt: Float, w: Int, h: Int) {
        if (colorsDirty) {
            colorsDirty = false
            val cols = arrayOf(cSolar, cBatt, cInv)
            for (i in 0 until 900) { val c = cols[i % 3]; pointCol.put(i * 3, c.red); pointCol.put(i * 3 + 1, c.green); pointCol.put(i * 3 + 2, c.blue) }
        }
        speed += ((0.12f + 0.9f * load) - speed) * min(1f, dt * 2)
        shellY += dt * 0.25f; shellX += dt * 0.08f; ring += dt * speed; clock += dt

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.perspectiveM(proj, 0, 45f, w / h.toFloat().coerceAtLeast(1f), 0.1f, 100f)
        Matrix.setLookAtM(view, 0, 0f, 0f, 5.2f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setRotateM(group, 0, deg(0.42f), 1f, 0f, 0f)

        // shell: normal blending, writes depth (three.js transparent wireframe)
        GLES20.glUseProgram(lineProg)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
        Matrix.setRotateM(tmp, 0, deg(shellX), 1f, 0f, 0f)
        Matrix.rotateM(tmp, 0, deg(shellY), 0f, 1f, 0f)
        Matrix.multiplyMM(model, 0, group, 0, tmp, 0)
        setMvp(lineProg, model)
        color(lineProg, cInv, 0.55f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lineProg, "uHalf"), w / 2f, h / 2f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lineProg, "uWidth"), linePx)
        attrib(lineProg, "aPos", shellQuads, 3, 7, 0)
        attrib(lineProg, "aOther", shellQuads, 3, 7, 3)
        attrib(lineProg, "aSide", shellQuads, 1, 7, 6)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, shellCount)

        GLES20.glUseProgram(flatProg)

        // glowing heart: additive, grows with solar, breathes
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        val s = 0.8f + 0.25f * solar + 0.1f * battery
        Matrix.scaleM(model, 0, group, 0, s, s, s)
        setMvp(flatProg, model)
        color(flatProg, cSolar, (0.25f + 0.45f * solar + 0.08f * sin(clock * 1000f / 600f)).coerceIn(0f, 1f))
        attrib(flatProg, "aPos", sphereVerts, 3)
        // one layer only, like three.js FrontSide (front or back faces give the same flat disc)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereCount, GLES20.GL_UNSIGNED_SHORT, sphereIdx)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        // particle ring: additive points, spin follows the load
        GLES20.glUseProgram(pointProg)
        Matrix.setRotateM(tmp, 0, deg(ring), 0f, 1f, 0f)
        Matrix.multiplyMM(model, 0, group, 0, tmp, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(pointProg, "uMv"), 1, false, mv, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(pointProg, "uP"), 1, false, proj, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pointProg, "uSize"), 0.035f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pointProg, "uScale"), h / 2f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pointProg, "uOpacity"), 0.85f)
        attrib(pointProg, "aPos", pointPos, 3)
        attrib(pointProg, "aCol", pointCol, 3)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 900)
        GLES20.glDepthMask(true)
    }

    private fun setMvp(prog: Int, m: FloatArray) {
        Matrix.multiplyMM(mv, 0, view, 0, m, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
    }
    private fun color(prog: Int, c: Color, a: Float) = GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), c.red, c.green, c.blue, a)
    private fun attrib(prog: Int, name: String, buf: FloatBuffer, size: Int, stride: Int = 0, offset: Int = 0) {
        val loc = GLES20.glGetAttribLocation(prog, name)
        buf.position(offset)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride * 4, buf)
    }
    private fun deg(rad: Float) = rad * 180f / PI.toFloat()

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.w("EnergyCore3D", "shader: " + GLES20.glGetShaderInfoLog(it))
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
            val ok = IntArray(1); GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.w("EnergyCore3D", "link: " + GLES20.glGetProgramInfoLog(it))
        }
    }
}

private fun floats(a: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
private fun shorts(a: ShortArray): ShortBuffer =
    ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }

/** Icosahedron subdivided once and pushed onto a sphere (three.js IcosahedronGeometry(r, 1)): 120 edges as line pairs. */
internal fun geodesicLines(radius: Float): FloatArray {
    val p = ((1 + sqrt(5.0)) / 2).toFloat()
    fun norm(a: FloatArray): FloatArray { val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    val verts = listOf(
        floatArrayOf(-1f, p, 0f), floatArrayOf(1f, p, 0f), floatArrayOf(-1f, -p, 0f), floatArrayOf(1f, -p, 0f),
        floatArrayOf(0f, -1f, p), floatArrayOf(0f, 1f, p), floatArrayOf(0f, -1f, -p), floatArrayOf(0f, 1f, -p),
        floatArrayOf(p, 0f, -1f), floatArrayOf(p, 0f, 1f), floatArrayOf(-p, 0f, -1f), floatArrayOf(-p, 0f, 1f),
    ).map(::norm).toMutableList()
    fun dist(a: FloatArray, b: FloatArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    val faces = ArrayList<IntArray>()
    for (i in 0 until 12) for (j in i + 1 until 12) for (k in j + 1 until 12)
        if (dist(verts[i], verts[j]) < 1.1f && dist(verts[j], verts[k]) < 1.1f && dist(verts[i], verts[k]) < 1.1f) faces += intArrayOf(i, j, k)
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
    val out = FloatArray(edges.size * 6)
    var o = 0
    for ((i, j) in edges) for (v in listOf(verts[i], verts[j])) { out[o++] = v[0] * radius; out[o++] = v[1] * radius; out[o++] = v[2] * radius }
    return out
}

/** Line pairs (x,y,z,x,y,z) -> two triangles per edge: pos(3), other end(3), side(1) per vertex. */
internal fun lineQuads(lines: FloatArray): FloatArray {
    val out = FloatArray(lines.size / 6 * 6 * 7)
    var o = 0
    for (e in 0 until lines.size / 6) {
        val a = lines.copyOfRange(e * 6, e * 6 + 3); val b = lines.copyOfRange(e * 6 + 3, e * 6 + 6)
        // seen from b the direction flips, so the side sign flips too
        val v = listOf(Triple(a, b, 1f), Triple(a, b, -1f), Triple(b, a, 1f), Triple(b, a, -1f))
        for (i in intArrayOf(0, 1, 2, 0, 2, 3)) {
            val (p, q, side) = v[i]
            p.forEach { out[o++] = it }; q.forEach { out[o++] = it }; out[o++] = side
        }
    }
    return out
}

/** UV sphere (three.js SphereGeometry(r, w, h)) as indexed triangles. */
internal fun uvSphere(r: Float, wSeg: Int, hSeg: Int): Pair<FloatArray, ShortArray> {
    val v = FloatArray((wSeg + 1) * (hSeg + 1) * 3)
    var o = 0
    for (y in 0..hSeg) {
        val th = y * PI.toFloat() / hSeg
        for (x in 0..wSeg) {
            val ph = x * 2 * PI.toFloat() / wSeg
            v[o++] = -r * cos(ph) * sin(th); v[o++] = r * cos(th); v[o++] = r * sin(ph) * sin(th)
        }
    }
    val idx = ArrayList<Short>()
    for (y in 0 until hSeg) for (x in 0 until wSeg) {
        val a = y * (wSeg + 1) + x; val b = a + wSeg + 1
        idx += a.toShort(); idx += b.toShort(); idx += (a + 1).toShort()
        idx += b.toShort(); idx += (b + 1).toShort(); idx += (a + 1).toShort()
    }
    return v to idx.toShortArray()
}
