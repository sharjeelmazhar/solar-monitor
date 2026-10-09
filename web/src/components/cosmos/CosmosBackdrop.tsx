import { useEffect, useRef } from 'react'
import * as THREE from 'three'
import { parseRated } from '../../lib/decode'
import { getState } from '../../lib/store'
import { HOME, sunPhase, sunTimes } from '../../lib/sun'
import EARTH_DAY from '../../assets/earth/day.webp'
import EARTH_NIGHT from '../../assets/earth/night.webp'
import { forceNight } from './night'
import {
  ATMO_FRAG, CORONA_FRAG, EARTH_FRAG, MOON_FRAG, GALAXY_VERT, POINT_FRAG, SKY_FRAG, SKY_VERT, SPHERE_VERT, STAR_VERT, STREAM_VERT, SUN_FRAG,
} from './shaders'

// Full-screen 3D universe behind the whole web app: a live sun (its glow follows solar output), the real earth
// turned to the current time with a beacon on home, the moon (the star of the night view), a spiral galaxy
// and a starfield. Scrolling flies the camera through it,
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

const D2R = Math.PI / 180
/** Direction of a latitude/longitude on three.js's sphere (matches an equirectangular map's UVs). */
function geoDir(lat: number, lon: number) {
  const phi = (lon + 180) * D2R, c = Math.cos(lat * D2R)
  return new THREE.Vector3(-Math.cos(phi) * c, Math.sin(lat * D2R), Math.sin(phi) * c)
}

function markerTexture() {
  const c = document.createElement('canvas')
  c.width = c.height = 64
  const g = c.getContext('2d')!
  const r = g.createRadialGradient(32, 32, 0, 32, 32, 32)
  r.addColorStop(0, 'rgba(255,255,255,1)')
  r.addColorStop(0.18, 'rgba(255,214,120,1)')
  r.addColorStop(0.4, 'rgba(255,170,40,.35)')
  r.addColorStop(1, 'rgba(255,150,0,0)')
  g.fillStyle = r
  g.fillRect(0, 0, 64, 64)
  return new THREE.CanvasTexture(c)
}

function moonGlowTexture() {
  const c = document.createElement('canvas')
  c.width = c.height = 128
  const g = c.getContext('2d')!
  const r = g.createRadialGradient(64, 64, 0, 64, 64, 64)
  r.addColorStop(0.2, 'rgba(200,215,255,.45)')
  r.addColorStop(0.5, 'rgba(150,170,255,.12)')
  r.addColorStop(1, 'rgba(120,140,255,0)')
  g.fillStyle = r
  g.fillRect(0, 0, 128, 128)
  return new THREE.CanvasTexture(c)
}

/** 0 by day, 1 at night, in between during the 90 minutes around sunrise and sunset. */
function nightness(now = new Date()) {
  if (forceNight()) return 1
  const { rise, set } = sunTimes(now)
  const t = now.getTime()
  const ramp = 45 * 60000
  if (t < rise - ramp || t > set + ramp) return 1
  if (t > rise + ramp && t < set - ramp) return 0
  const edge = t < (rise + set) / 2 ? (rise + ramp - t) / (2 * ramp) : (t - (set - ramp)) / (2 * ramp)
  return Math.max(0, Math.min(1, edge))
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

    const renderer = new THREE.WebGLRenderer({ antialias: !mobile, alpha: false, powerPreference: 'high-performance' })
    renderer.setPixelRatio(dpr)
    renderer.setSize(innerWidth, innerHeight, false)
    renderer.outputColorSpace = THREE.SRGBColorSpace
    el.appendChild(renderer.domElement)
    renderer.domElement.style.cssText = 'width:100%;height:100%;display:block'

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(50, innerWidth / innerHeight, 0.1, 600)
    const uTime = { value: 0 }
    const uLight = { value: props.current.light ? 1 : 0 }
    const uNight = { value: nightness() }
    const uPix = { value: dpr }
    const uAct = { value: 0.5 }
    const uFlow = { value: 0.2 }
    const toSun = new THREE.Vector3().subVectors(SUN, EARTH).normalize()
    const up = new THREE.Vector3(0, 1, 0)
    const add = THREE.AdditiveBlending
    const disposables: { dispose(): void }[] = []

    const sky = new THREE.Mesh(new THREE.SphereGeometry(500, 48, 24),
      new THREE.ShaderMaterial({ vertexShader: SKY_VERT, fragmentShader: SKY_FRAG, uniforms: { uTime, uLight, uNight }, side: THREE.BackSide, depthWrite: false }))
    sky.renderOrder = -10
    scene.add(sky)

    scene.add(new THREE.Points(stars(mobile ? 1600 : 2600),
      new THREE.ShaderMaterial({ vertexShader: STAR_VERT, fragmentShader: POINT_FRAG, uniforms: { uTime, uPix, uLight, uNight }, blending: add, depthWrite: false, transparent: true })))

    const gal = new THREE.Points(galaxy(mobile ? 5000 : 9000),
      new THREE.ShaderMaterial({ vertexShader: GALAXY_VERT, fragmentShader: POINT_FRAG, uniforms: { uTime, uPix, uLight }, blending: add, depthWrite: false, transparent: true }))
    gal.position.copy(GALAXY)
    gal.rotation.set(0.95, 0.3, 0.35)
    scene.add(gal)

    // the sun: surface, corona shell and a soft glow sprite (all dim at night)
    scene.add(new THREE.Mesh(new THREE.SphereGeometry(1.6, 64, 48),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: SUN_FRAG, uniforms: { uTime, uAct, uNight } })))
    scene.add(new THREE.Mesh(new THREE.SphereGeometry(2.6, 48, 32),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: CORONA_FRAG, uniforms: { uTime, uAct, uLight }, blending: add, side: THREE.BackSide, depthWrite: false, transparent: true })))
    const glowTex = glowTexture()
    disposables.push(glowTex)
    const glow = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTex, blending: add, depthWrite: false, transparent: true }))
    scene.add(glow)

    // the earth from real maps, turned to the real time of day; a beacon marks home (Pakistan)
    const tl = new THREE.TextureLoader()
    const dayTex = tl.load(EARTH_DAY), lightsTex = tl.load(EARTH_NIGHT)
    for (const t of [dayTex, lightsTex]) { t.anisotropy = 4; disposables.push(t) }
    const earthU = { uTime, uSun: { value: toSun }, uDay: { value: dayTex }, uLights: { value: lightsTex } }
    const earth = new THREE.Mesh(new THREE.SphereGeometry(0.85, 96, 64),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: EARTH_FRAG, uniforms: earthU }))
    earth.position.copy(EARTH)
    scene.add(earth)
    const atmo = new THREE.Mesh(new THREE.SphereGeometry(0.98, 48, 32),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: ATMO_FRAG, uniforms: { uSun: earthU.uSun, uLight }, blending: add, side: THREE.BackSide, depthWrite: false, transparent: true }))
    atmo.position.copy(EARTH)
    scene.add(atmo)
    const HOME_OBJ = geoDir(HOME.lat, HOME.lon)
    const markTex = markerTexture()
    disposables.push(markTex)
    const marker = new THREE.Sprite(new THREE.SpriteMaterial({ map: markTex, blending: add, depthWrite: false, transparent: true }))
    marker.position.copy(HOME_OBJ).multiplyScalar(0.875)
    earth.add(marker)
    // which way the earth faces: the point under the sun (subsolar longitude from UTC) must face the sun
    const homeWorld = new THREE.Vector3()
    const turnEarth = () => {
      const utcH = (Date.now() / 3600000) % 24
      const sub = geoDir(0, (12 - utcH) * 15)
      earth.rotation.y = Math.atan2(sub.z, sub.x) - Math.atan2(toSun.z, toSun.x)
      earth.updateMatrixWorld()
      homeWorld.copy(HOME_OBJ).applyQuaternion(earth.quaternion).normalize()
    }
    turnEarth()

    // the moon: small and orbiting by day; at night it comes forward, big and moonlit, for the opening view
    const moonLight = { value: new THREE.Vector3() }
    const moon = new THREE.Mesh(new THREE.SphereGeometry(0.26, 48, 32),
      new THREE.ShaderMaterial({ vertexShader: SPHERE_VERT, fragmentShader: MOON_FRAG, uniforms: { uSun: moonLight } }))
    scene.add(moon)
    const moonGlowTex = moonGlowTexture()
    disposables.push(moonGlowTex)
    const moonGlow = new THREE.Sprite(new THREE.SpriteMaterial({ map: moonGlowTex, blending: add, depthWrite: false, transparent: true }))
    scene.add(moonGlow)

    // energy stream sun -> earth (quadratic curve that bows upwards)
    const ctrl = new THREE.Vector3().addVectors(SUN, EARTH).multiplyScalar(0.5).add(new THREE.Vector3(0, 1.6, 0.8))
    const from = new THREE.Vector3().subVectors(EARTH, SUN).normalize().multiplyScalar(1.7)
    const to = EARTH.clone().add(toSun.clone().multiplyScalar(0.95))
    scene.add(new THREE.Points(stream(mobile ? 260 : 420),
      new THREE.ShaderMaterial({
        vertexShader: STREAM_VERT, fragmentShader: POINT_FRAG, blending: add, depthWrite: false, transparent: true,
        uniforms: { uTime, uFlow, uPix, uA: { value: from }, uB: { value: ctrl }, uC: { value: to } },
      })))

    // Two camera flights, blended by night-ness. Day: the sun -> home on the earth -> wide shot with the galaxy.
    // Night: the moon up close -> home on the dark side (city lights) -> the same wide shot.
    const dayCam = new THREE.CatmullRomCurve3([
      new THREE.Vector3(2.4, 1.6, 14.5), new THREE.Vector3(5.2, 1.6, 7.6), new THREE.Vector3(), new THREE.Vector3(5, 5, 14), new THREE.Vector3(-2, 9, 25),
    ])
    const dayLook = new THREE.CatmullRomCurve3([
      new THREE.Vector3(-2.2, -1.5, -1), new THREE.Vector3(3.4, -0.5, -1.6), EARTH.clone(), new THREE.Vector3(-4, 2, -20), new THREE.Vector3(-14, 5, -45),
    ])
    const nightCam = new THREE.CatmullRomCurve3([new THREE.Vector3(), new THREE.Vector3(), new THREE.Vector3(), new THREE.Vector3(5, 5, 14), new THREE.Vector3(-2, 9, 25)])
    const nightLook = new THREE.CatmullRomCurve3([new THREE.Vector3(), new THREE.Vector3(), EARTH.clone(), new THREE.Vector3(-4, 2, -20), new THREE.Vector3(-14, 5, -45)])
    const away = toSun.clone().negate()
    const side = new THREE.Vector3().crossVectors(up, away).normalize()
    const moonHero = EARTH.clone().addScaledVector(away, 3.4).addScaledVector(side, 1.7).addScaledVector(up, 0.9)
    const placePaths = () => {
      // look at home from a little sun-ward (day) or straight on (night) so the beacon sits on the visible face
      const dayView = homeWorld.clone().multiplyScalar(0.8).addScaledVector(toSun, 0.35).addScaledVector(up, 0.12).normalize()
      dayCam.points[2].copy(EARTH).addScaledVector(dayView, 3.4)
      const nightView = homeWorld.clone().addScaledVector(up, 0.12).normalize()
      nightCam.points[2].copy(EARTH).addScaledVector(nightView, 3.2)
      nightCam.points[0].copy(moonHero).addScaledVector(away, 4.4).addScaledVector(side, -1.6).addScaledVector(up, 0.5)
      nightLook.points[0].copy(moonHero).addScaledVector(side, -2.0).addScaledVector(up, -1.0)
      nightCam.points[1].copy(nightCam.points[0]).lerp(nightCam.points[2], 0.5).addScaledVector(up, 0.8)
      nightLook.points[1].copy(moonHero).lerp(EARTH, 0.7)
      for (const c of [dayCam, nightCam, dayLook, nightLook]) c.updateArcLengths()
    }
    placePaths()

    const view = { p: 0, tab: props.current.tab * 0.55, mx: 0, my: 0, tmx: 0, tmy: 0 }
    const pos = new THREE.Vector3(), look = new THREE.Vector3(), tmp = new THREE.Vector3(), rot = new THREE.Matrix4()

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

    let raf = 0, last = performance.now(), slow = 0, frames = 0, skip = false, odd = false, warned = false
    const level = { act: 0.5, flow: 0.2, night: uNight.value, at: 0 }
    const frame = (now: number) => {
      raf = requestAnimationFrame(frame)
      const dt = Math.min(0.1, (now - last) / 1000)
      last = now
      // quality governor: lower resolution, then halve the frame rate; finally suggest turning 3D off
      frames++
      if (dt > 0.026) slow++
      if (frames === 90) {
        if (slow > 45 && dpr > 0.75) { dpr = Math.max(0.75, dpr - 0.25); renderer.setPixelRatio(dpr); uPix.value = dpr; renderer.setSize(innerWidth, innerHeight, false) }
        else if (slow > 45 && !skip) skip = true
        else if (slow > 60 && skip && !warned) { warned = true; dispatchEvent(new CustomEvent('cosmos-slow')) }
        frames = slow = 0
      }
      if (skip && (odd = !odd)) return

      if (now - level.at > 1000) {
        Object.assign(level, solarLevel(), { night: nightness(), at: now })
        turnEarth()
        placePaths()
      }
      if (!reduce) uTime.value += dt
      uAct.value = smooth(uAct.value, level.act, dt, 1.2)
      uFlow.value = smooth(uFlow.value, level.flow, dt, 1.2)
      uNight.value = smooth(uNight.value, level.night, dt, 0.8)
      uLight.value = smooth(uLight.value, props.current.light ? 1 : 0, dt, 2.5)
      const n = uNight.value
      glow.scale.setScalar((10 + 12 * uAct.value) * (1 - 0.45 * n))
      ;(glow.material as THREE.SpriteMaterial).opacity = (0.55 + 0.45 * uAct.value) * (1 - 0.6 * uLight.value) * (1 - 0.55 * n)

      // moon: orbit by day, the big moonlit hero at night
      const ma = uTime.value * 0.12 + 2.2
      tmp.set(EARTH.x + Math.cos(ma) * 2.1, EARTH.y + Math.sin(ma) * 0.45, EARTH.z + Math.sin(ma) * 2.1)
      moon.position.copy(tmp).lerp(moonHero, n)
      moon.scale.setScalar(1 + 1.6 * n)
      moon.rotation.y += dt * 0.02
      tmp.subVectors(SUN, moon.position).normalize()
      moonLight.value.copy(tmp).lerp(tmp.subVectors(camera.position, moon.position).normalize().addScaledVector(side, -0.5), 0.8 * n).normalize()
      moonGlow.position.copy(moon.position)
      moonGlow.scale.setScalar(2.4 * (1 + 1.6 * n))
      ;(moonGlow.material as THREE.SpriteMaterial).opacity = n * (1 - 0.8 * uLight.value)

      // home beacon pulses softly
      const beat = 0.5 + 0.5 * Math.sin(uTime.value * 2.4)
      marker.scale.setScalar(0.1 + 0.05 * beat)
      ;(marker.material as THREE.SpriteMaterial).opacity = 0.75 + 0.25 * beat

      // camera: gentle springs on scroll, tab swing and pointer so every move eases in and out
      view.p = smooth(view.p, progress(), dt, 2.6)
      view.tab = smooth(view.tab, props.current.tab * 0.55, dt, 1.4)
      view.mx = smooth(view.mx, view.tmx, dt, 1.8)
      view.my = smooth(view.my, view.tmy, dt, 1.8)
      const p = view.p * view.p * (3 - 2 * view.p) // ease in-out along the flight
      const wobble = reduce ? 0 : Math.sin(uTime.value * 0.07) * 0.06
      rot.makeRotationY(view.tab * (1 - p * 0.5) + wobble)
      dayCam.getPointAt(p, pos)
      pos.lerp(nightCam.getPointAt(p, tmp), n)
      dayLook.getPointAt(p, look)
      look.lerp(nightLook.getPointAt(p, tmp), n)
      // tabs swing the view around the earth/sun midpoint
      pos.sub(EARTH).applyMatrix4(rot).add(EARTH)
      if (camera.aspect < 0.8) { const k = 1 - Math.min(1, view.p * 4); look.x += 2.6 * k * (1 - n); look.y -= 4.6 * k * (1 - n) + 0.5 * k * n; look.addScaledVector(side, 1.5 * k * n) }
      pos.x += view.mx * 0.6
      pos.y -= view.my * 0.4
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
      disposables.forEach((d) => d.dispose())
      renderer.dispose()
      renderer.domElement.remove()
    }
  }, [])

  return <div ref={host} className="cosmos-canvas pointer-events-none fixed inset-0 z-0" aria-hidden />
}
