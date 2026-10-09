import { useEffect, useRef } from 'react'
import * as THREE from 'three'
import { parseRated } from '../../lib/decode'
import { getState } from '../../lib/store'
import { sunPhase } from '../../lib/sun'
import {
  ATMO_FRAG, CORONA_FRAG, EARTH_FRAG, MOON_FRAG, GALAXY_VERT, POINT_FRAG, SKY_FRAG, SKY_VERT, SPHERE_VERT, STAR_VERT, STREAM_VERT, SUN_FRAG,
} from './shaders'

// Full-screen 3D universe behind the whole web app: a live sun (its glow follows solar output), the earth
// receiving a stream of energy, a spiral galaxy and a starfield. Scrolling flies the camera through it,
// switching tabs swings it to a new angle, and the pointer adds a little parallax. One WebGL canvas,
// all procedural (no textures), paused when the tab is hidden, quality steps down on slow phones.

const SUN = new THREE.Vector3(0, 0, 0)
const EARTH = new THREE.Vector3(7.2, 0.5, -3.2)
const GALAXY = new THREE.Vector3(-34, 16, -95)

function smooth(cur: number, target: number, dt: number, rate: number) {
  return cur + (target - cur) * (1 - Math.exp(-dt * rate))
}

function glowTexture() {
  const c = document.createElement('canvas')
  c.width = c.height = 128
  const g = c.getContext('2d')!
  const r = g.createRadialGradient(64, 64, 0, 64, 64, 64)
  r.addColorStop(0, 'rgba(255,236,190,1)')
  r.addColorStop(0.18, 'rgba(255,190,90,.55)')
  r.addColorStop(0.45, 'rgba(255,120,30,.16)')
  r.addColorStop(1, 'rgba(255,90,0,0)')
  g.fillStyle = r
  g.fillRect(0, 0, 128, 128)
  return new THREE.CanvasTexture(c)
}

function stars(n: number) {
  const pos = new Float32Array(n * 3), col = new Float32Array(n * 3), size = new Float32Array(n), ph = new Float32Array(n)
  const tint = [new THREE.Color('#9fb8ff'), new THREE.Color('#ffffff'), new THREE.Color('#ffe1b0'), new THREE.Color('#cdd8ff')]
  for (let i = 0; i < n; i++) {
    const v = new THREE.Vector3().randomDirection().multiplyScalar(260 + Math.random() * 80)
    pos.set([v.x, v.y, v.z], i * 3)
    const c = tint[i % 4]
    const b = 0.45 + Math.random() * 0.55
    col.set([c.r * b, c.g * b, c.b * b], i * 3)
    size[i] = Math.random() < 0.04 ? 2.6 + Math.random() * 1.6 : 0.9 + Math.random() * 1.3
    ph[i] = Math.random()
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3))
  g.setAttribute('color', new THREE.BufferAttribute(col, 3))
  g.setAttribute('aSize', new THREE.BufferAttribute(size, 1))
  g.setAttribute('aPhase', new THREE.BufferAttribute(ph, 1))
  return g
}

function galaxy(n: number) {
  const pos = new Float32Array(n * 3), col = new Float32Array(n * 3), r = new Float32Array(n), ang = new Float32Array(n), size = new Float32Array(n)
  const core = new THREE.Color('#ffd9a0'), mid = new THREE.Color('#d08cff'), edge = new THREE.Color('#5aa8ff')
  const arms = 4
  for (let i = 0; i < n; i++) {
    const rad = Math.pow(Math.random(), 1.6) * 30
    const arm = ((i % arms) / arms) * Math.PI * 2
    const spin = rad * 0.32
    const sc = Math.pow(Math.random(), 2.2) * (Math.random() < 0.5 ? 1 : -1) * (0.8 + rad * 0.16)
    r[i] = rad + sc * 0.6
    ang[i] = arm + spin + sc * 0.25
    pos.set([0, (Math.random() - 0.5) * (2.4 - rad * 0.06) * Math.pow(Math.random(), 2), 0], i * 3)
    const t = rad / 30
    const c = t < 0.35 ? core.clone().lerp(mid, t / 0.35) : mid.clone().lerp(edge, (t - 0.35) / 0.65)
    const b = 0.5 + Math.random() * 0.5
    col.set([c.r * b, c.g * b, c.b * b], i * 3)
    size[i] = 0.6 + Math.random() * 1.4 + (rad < 3 ? 1.2 : 0)
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3))
  g.setAttribute('color', new THREE.BufferAttribute(col, 3))
  g.setAttribute('aR', new THREE.BufferAttribute(r, 1))
  g.setAttribute('aAng', new THREE.BufferAttribute(ang, 1))
  g.setAttribute('aSize', new THREE.BufferAttribute(size, 1))
  return g
}

function stream(n: number) {
  const t = new Float32Array(n), gate = new Float32Array(n), jit = new Float32Array(n * 3), pos = new Float32Array(n * 3)
  for (let i = 0; i < n; i++) {
    t[i] = Math.random()
    gate[i] = Math.random() * 0.98 + 0.01
    const v = new THREE.Vector3().randomDirection().multiplyScalar(0.15 + Math.random() * 0.55)
    jit.set([v.x, v.y, v.z], i * 3)
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3))
  g.setAttribute('aT', new THREE.BufferAttribute(t, 1))
  g.setAttribute('aGate', new THREE.BufferAttribute(gate, 1))
  g.setAttribute('aJit', new THREE.BufferAttribute(jit, 3))
  g.boundingSphere = new THREE.Sphere(new THREE.Vector3(4, 0, -1), 12) // positions are computed in the shader
  return g
}

/** Solar output as 0..1 (relative to the inverter's rating or today's peak), plus whether it is night. */
function solarLevel() {
  const s = getState()
  const d = s.live
  const night = sunPhase() === 'night'
  if (!d?.ever || !d.ok) return { act: night ? 0.12 : 0.45, flow: 0.12, night }
  const rated = parseRated(s.info?.inv.qpiri)?.outW || 3200
  const ref = Math.max(800, Math.min(rated, Math.max(d.today.pvPeak, rated * 0.6)))
  const f = Math.min(1, d.pvW / ref)
  return { act: night ? 0.1 : 0.32 + 0.68 * f, flow: f < 0.01 ? 0 : 0.08 + 0.92 * f, night }
}

export default function CosmosBackdrop({ tab, light }: { tab: number; light: boolean }) {
  const host = useRef<HTMLDivElement>(null)
  const props = useRef({ tab, light })
  props.current = { tab, light }

  useEffect(() => {
    const el = host.current!
    const mobile = matchMedia('(max-width: 768px)').matches
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
    let dpr = Math.min(devicePixelRatio || 1, mobile ? 1.25 : 1.5)

    const renderer = new THREE.WebGLRenderer({ antialias: false, alpha: false, powerPreference: 'high-performance' })
    renderer.setPixelRatio(dpr)
    renderer.setSize(innerWidth, innerHeight, false)
    renderer.outputColorSpace = THREE.SRGBColorSpace
    el.appendChild(renderer.domElement)
    renderer.domElement.style.cssText = 'width:100%;height:100%;display:block'

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(50, innerWidth / innerHeight, 0.1, 600)
    const uTime = { value: 0 }
    const uLight = { value: props.current.light ? 1 : 0 }
    const uPix = { value: dpr }
    const uAct = { value: 0.5 }
    const uFlow = { value: 0.2 }
    const sunDir = new THREE.Vector3().subVectors(SUN, EARTH).normalize()
    const add = THREE.AdditiveBlending

    // sky dome (always behind everything)
    const sky = new THREE.Mesh(new THREE.SphereGeometry(500, 48, 24),
      new THREE.ShaderMaterial({ vertexShader: SKY_VERT, fragmentShader: SKY_FRAG, uniforms: { uTime, uLight }, side: THREE.BackSide, depthWrite: false }))
    sky.renderOrder = -10
    scene.add(sky)

    const starPts = new THREE.Points(stars(mobile ? 1600 : 2600),
      new THREE.ShaderMaterial({ vertexShader: STAR_VERT, fragmentShader: POINT_FRAG, uniforms: { uTime, uPix, uLight }, blending: add, depthWrite: false, transparent: true }))
    scene.add(starPts)

    const gal = new THREE.Points(galaxy(mobile ? 5000 : 9000),
      new THREE.ShaderMaterial({ vertexShader: GALAXY_VERT, fragmentShader: POINT_FRAG, uniforms: { uTime, uPix, uLight }, blending: add, depthWrite: false, transparent: true }))
    gal.position.copy(GALAXY)
    gal.rotation.set(0.95, 0.3, 0.35)
    scene.add(gal)

    // the sun: surface, corona shell and a soft glow sprite
    const sun = new THREE.Mesh(new THREE.SphereGeometry(1.6, 64, 48),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: SUN_FRAG, uniforms: { uTime, uAct } }))
    scene.add(sun)
    const corona = new THREE.Mesh(new THREE.SphereGeometry(2.6, 48, 32),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: CORONA_FRAG, uniforms: { uTime, uAct, uLight }, blending: add, side: THREE.BackSide, depthWrite: false, transparent: true }))
    scene.add(corona)
    const glowTex = glowTexture()
    const glow = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTex, blending: add, depthWrite: false, transparent: true }))
    glow.scale.setScalar(13)
    scene.add(glow)

    // the earth + atmosphere
    const earthU = { uTime, uSun: { value: sunDir } }
    const earth = new THREE.Mesh(new THREE.SphereGeometry(0.85, 64, 48),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: EARTH_FRAG, uniforms: earthU }))
    earth.position.copy(EARTH)
    earth.rotation.z = 0.41
    scene.add(earth)
    const atmo = new THREE.Mesh(new THREE.SphereGeometry(0.98, 48, 32),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: ATMO_FRAG, uniforms: { uSun: earthU.uSun, uLight }, blending: add, side: THREE.BackSide, depthWrite: false, transparent: true }))
    atmo.position.copy(EARTH)
    scene.add(atmo)

    // the moon, orbiting the earth
    const moon = new THREE.Mesh(new THREE.SphereGeometry(0.24, 40, 28),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: MOON_FRAG, uniforms: { uSun: earthU.uSun } }))
    scene.add(moon)

    // energy stream sun -> earth (quadratic curve that bows upwards)
    const ctrl = new THREE.Vector3().addVectors(SUN, EARTH).multiplyScalar(0.5).add(new THREE.Vector3(0, 1.6, 0.8))
    const from = new THREE.Vector3().subVectors(EARTH, SUN).normalize().multiplyScalar(1.7)
    const to = EARTH.clone().add(new THREE.Vector3().subVectors(SUN, EARTH).normalize().multiplyScalar(0.95))
    const flow = new THREE.Points(stream(mobile ? 260 : 420),
      new THREE.ShaderMaterial({
        vertexShader: STREAM_VERT, fragmentShader: POINT_FRAG, blending: add, depthWrite: false, transparent: true,
        uniforms: { uTime, uFlow, uPix, uA: { value: from }, uB: { value: ctrl }, uC: { value: to } },
      }))
    scene.add(flow)

    // camera flight path: hero on the sun -> past the earth -> wide shot with the galaxy
    const camPath = new THREE.CatmullRomCurve3([
      new THREE.Vector3(2.4, 1.6, 14.5), new THREE.Vector3(5.2, 1.6, 7.6), new THREE.Vector3(7.4, 1.6, 1.4),
      new THREE.Vector3(5, 5, 14), new THREE.Vector3(-2, 9, 25),
    ])
    const lookPath = new THREE.CatmullRomCurve3([
      new THREE.Vector3(-2.2, -1.5, -1), new THREE.Vector3(3.4, -0.5, -1.6), new THREE.Vector3(6.6, 0.3, -3.2),
      new THREE.Vector3(-4, 2, -20), new THREE.Vector3(-14, 5, -45),
    ])
    const view = { p: 0, tab: props.current.tab * 0.55, mx: 0, my: 0, tmx: 0, tmy: 0 }
    const pos = new THREE.Vector3(), look = new THREE.Vector3(), rot = new THREE.Matrix4()

    const progress = () => {
      const max = document.documentElement.scrollHeight - innerHeight
      return max > 40 ? Math.min(1, Math.max(0, scrollY / max)) : 0
    }
    const onMove = (e: PointerEvent) => { view.tmx = (e.clientX / innerWidth) * 2 - 1; view.tmy = (e.clientY / innerHeight) * 2 - 1 }
    const onResize = () => {
      camera.aspect = innerWidth / innerHeight
      camera.fov = camera.aspect < 0.8 ? 62 : 50 // portrait phones see a wider slice
      camera.updateProjectionMatrix()
      renderer.setSize(innerWidth, innerHeight, false)
    }
    onResize()
    addEventListener('pointermove', onMove, { passive: true })
    addEventListener('resize', onResize)

    let raf = 0, last = performance.now(), slow = 0, frames = 0, skip = false, odd = false
    const level = { act: 0.5, flow: 0.2, at: 0 }
    const frame = (now: number) => {
      raf = requestAnimationFrame(frame)
      const dt = Math.min(0.1, (now - last) / 1000)
      last = now
      // quality governor: if frames take > 26 ms for a while, lower resolution, then halve the frame rate
      frames++
      if (dt > 0.026) slow++
      if (frames === 90) {
        if (slow > 45 && dpr > 0.75) { dpr = Math.max(0.75, dpr - 0.25); renderer.setPixelRatio(dpr); uPix.value = dpr; renderer.setSize(innerWidth, innerHeight, false) }
        else if (slow > 45) skip = true
        frames = slow = 0
      }
      if (skip && (odd = !odd)) return

      if (now - level.at > 1000) Object.assign(level, solarLevel(), { at: now })
      if (!reduce) uTime.value += dt
      uAct.value = smooth(uAct.value, level.act, dt, 1.5)
      uFlow.value = smooth(uFlow.value, level.flow, dt, 1.5)
      uLight.value = smooth(uLight.value, props.current.light ? 1 : 0, dt, 3)
      glow.scale.setScalar(10 + 12 * uAct.value)
      ;(glow.material as THREE.SpriteMaterial).opacity = (0.55 + 0.45 * uAct.value) * (1 - 0.6 * uLight.value)
      earth.rotation.y += dt * 0.06
      const ma = uTime.value * 0.12 + 2.2
      moon.position.set(EARTH.x + Math.cos(ma) * 2.1, EARTH.y + Math.sin(ma) * 0.45, EARTH.z + Math.sin(ma) * 2.1)

      view.p = smooth(view.p, progress(), dt, 4)
      view.tab = smooth(view.tab, props.current.tab * 0.55, dt, 2.2)
      view.mx = smooth(view.mx, view.tmx, dt, 2.5)
      view.my = smooth(view.my, view.tmy, dt, 2.5)
      const wobble = reduce ? 0 : Math.sin(uTime.value * 0.07) * 0.08
      rot.makeRotationY(view.tab + wobble)
      camPath.getPointAt(view.p, pos).applyMatrix4(rot)
      lookPath.getPointAt(view.p, look).applyMatrix4(rot)
      // portrait phones: sun near the top, above the headline (fades out as the flight moves on)
      if (camera.aspect < 0.8) { const k = 1 - Math.min(1, view.p * 4); look.x += 2.6 * k; look.y -= 4.6 * k }
      pos.x += view.mx * 0.7
      pos.y -= view.my * 0.45
      camera.position.copy(pos)
      camera.lookAt(look)
      renderer.render(scene, camera)
    }
    const start = () => { if (!raf) { last = performance.now(); raf = requestAnimationFrame(frame) } }
    const stop = () => { cancelAnimationFrame(raf); raf = 0 }
    const onVis = () => (document.hidden ? stop() : start())
    document.addEventListener('visibilitychange', onVis)
    start()

    return () => {
      stop()
      document.removeEventListener('visibilitychange', onVis)
      removeEventListener('pointermove', onMove)
      removeEventListener('resize', onResize)
      scene.traverse((o) => {
        const m = o as THREE.Mesh
        m.geometry?.dispose()
        ;(m.material as THREE.Material | undefined)?.dispose?.()
      })
      glowTex.dispose()
      renderer.dispose()
      renderer.domElement.remove()
    }
  }, [])

  return <div ref={host} className="cosmos-canvas pointer-events-none fixed inset-0 z-0" aria-hidden />
}
