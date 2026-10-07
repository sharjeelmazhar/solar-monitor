import { Canvas, useFrame } from '@react-three/fiber'
import { useEffect, useMemo, useRef, useState } from 'react'
import * as THREE from 'three'

// Ambient 3D "energy core" drawn behind the flow diagram. Purely decorative: all numbers live in the SVG on top.

function readVar(name: string) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888'
}

function Core({ solar, load, battery }: { solar: number; load: number; battery: number }) {
  const shell = useRef<THREE.Mesh>(null)
  const inner = useRef<THREE.Mesh>(null)
  const ring = useRef<THREE.Points>(null)
  const speed = useRef(0.2)
  const colors = useMemo(() => ({ inv: new THREE.Color(readVar('--inv')), solar: new THREE.Color(readVar('--solar')), batt: new THREE.Color(readVar('--batt')) }), [])

  const points = useMemo(() => {
    const n = 900
    const pos = new Float32Array(n * 3)
    const col = new Float32Array(n * 3)
    for (let i = 0; i < n; i++) {
      const a = Math.random() * Math.PI * 2
      const r = 1.45 + Math.random() * 0.6
      const y = (Math.random() - 0.5) * 0.35 * r
      pos.set([Math.cos(a) * r, y, Math.sin(a) * r], i * 3)
      const c = i % 3 === 0 ? colors.solar : i % 3 === 1 ? colors.batt : colors.inv
      col.set([c.r, c.g, c.b], i * 3)
    }
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    g.setAttribute('color', new THREE.BufferAttribute(col, 3))
    return g
  }, [colors])

  useFrame((_, dt) => {
    speed.current += ((0.12 + 0.9 * load) - speed.current) * Math.min(1, dt * 2)
    if (shell.current) {
      shell.current.rotation.y += dt * 0.25
      shell.current.rotation.x += dt * 0.08
    }
    if (ring.current) ring.current.rotation.y += dt * speed.current
    if (inner.current) {
      const m = inner.current.material as THREE.MeshBasicMaterial
      m.opacity = 0.25 + 0.45 * solar + 0.08 * Math.sin(performance.now() / 600)
      inner.current.scale.setScalar(0.8 + 0.25 * solar + 0.1 * battery)
    }
  })

  return (
    <group rotation={[0.42, 0, 0]}>
      <mesh ref={shell}>
        <icosahedronGeometry args={[1.05, 1]} />
        <meshBasicMaterial color={colors.inv} wireframe transparent opacity={0.55} />
      </mesh>
      <mesh ref={inner}>
        <sphereGeometry args={[0.62, 32, 32]} />
        <meshBasicMaterial color={colors.solar} transparent opacity={0.4} blending={THREE.AdditiveBlending} depthWrite={false} />
      </mesh>
      <points ref={ring} geometry={points}>
        <pointsMaterial size={0.035} vertexColors transparent opacity={0.85} blending={THREE.AdditiveBlending} depthWrite={false} sizeAttenuation />
      </points>
    </group>
  )
}

export default function EnergyCore3D({ solar, load, battery }: { solar: number; load: number; battery: number }) {
  const host = useRef<HTMLDivElement>(null)
  const [active, setActive] = useState(true)
  useEffect(() => {
    const io = new IntersectionObserver(([e]) => setActive(e.isIntersecting && !document.hidden))
    const onVis = () => setActive(!document.hidden)
    if (host.current) io.observe(host.current)
    document.addEventListener('visibilitychange', onVis)
    return () => {
      io.disconnect()
      document.removeEventListener('visibilitychange', onVis)
    }
  }, [])
  return (
    <div ref={host} className="pointer-events-none absolute inset-0" aria-hidden>
      <Canvas dpr={[1, 1.5]} frameloop={active ? 'always' : 'never'} camera={{ position: [0, 0, 5.2], fov: 45 }} gl={{ antialias: true, alpha: true, powerPreference: 'low-power' }}>
        <Core solar={solar} load={load} battery={battery} />
      </Canvas>
    </div>
  )
}

