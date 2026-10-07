// Animated SVG icons drawn around (0,0), roughly 40 units wide. Used inside the flow diagram and on tiles.

export function SolarIcon({ intensity, spinS }: { intensity: number; spinS: number }) {
  // intensity 0..1 (solar W / rated W): sun grows brighter and rays longer
  const r = 7 + 2 * intensity
  const ray = 4 + 4 * intensity
  return (
    <g>
      <circle r={r + 7} fill="var(--solar)" opacity={0.08 + 0.22 * intensity} />
      <circle r={r} fill="var(--solar)" opacity={0.35 + 0.65 * Math.max(intensity, 0.15)} />
      <g style={{ animation: spinS ? `spin-slow ${spinS}s linear infinite` : 'none', transformBox: 'fill-box', transformOrigin: 'center' }}>
        {Array.from({ length: 8 }, (_, i) => (
          <line key={i} x1={0} y1={-(r + 3.5)} x2={0} y2={-(r + 3.5 + ray)} stroke="var(--solar)" strokeWidth={2.4} strokeLinecap="round"
            transform={`rotate(${i * 45})`} opacity={0.4 + 0.6 * Math.max(intensity, 0.1)} />
        ))}
      </g>
    </g>
  )
}

export function GridIcon({ on, importing }: { on: boolean; importing: boolean }) {
  const c = 'var(--grid)'
  return (
    <g opacity={on ? 1 : 0.35}>
      <g fill="none" stroke={c} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
        <path d="M-9 17 L-3 -10 L0 -16 L3 -10 L9 17" />
        <path d="M-15 -7H15M-11 1H11M-3 -10 L5 1 M3 -10 L-5 1 M-5.5 1 L7.5 17 M5.5 1 L-7.5 17" />
        <path d="M-15 -7v4M15 -7v4M-11 1v4M11 1v4" strokeWidth={2.4} />
      </g>
      {importing && (
        <path d="M-15 -3 Q 0 4 15 -3" fill="none" stroke={c} strokeWidth={1.6} strokeDasharray="3 5" style={{ animation: 'wave 0.8s linear infinite' }} />
      )}
    </g>
  )
}

export function GridOffMark() {
  return <path d="M-19 19 L19 -19" stroke="var(--crit)" strokeWidth={3} strokeLinecap="round" />
}

export function BatteryIcon({ pct, charging }: { pct: number; charging: boolean }) {
  const fill = pct <= 20 ? 'var(--crit)' : pct <= 45 ? 'var(--warn)' : 'var(--batt)'
  const w = Math.max(1.5, 23 * Math.min(1, Math.max(0, pct / 100)))
  return (
    <g>
      <rect x={-16} y={-10} width={29} height={20} rx={4.5} fill="none" stroke="var(--text-2)" strokeWidth={2} />
      <rect x={14} y={-4.5} width={3.5} height={9} rx={1.5} fill="var(--text-2)" />
      <rect x={-13} y={-7} width={w} height={14} rx={2} fill={fill} style={{ transition: 'width .8s, fill .5s', animation: charging ? 'pulse-soft 1.6s ease-in-out infinite' : 'none' }} />
      {charging && <path d="M0.5 -8 L-5 1 H-0.5 L-1.5 8 L4 -1 H-0.5 Z" fill="var(--surface-solid)" />}
    </g>
  )
}

export function HomeIcon({ frac, id }: { frac: number; id: string }) {
  const h = 27 * Math.min(1, Math.max(0, frac))
  return (
    <g>
      <defs>
        <clipPath id={id}><path d="M-12 -2 L0 -13 L12 -2 V14 H-12 Z" /></clipPath>
      </defs>
      <rect x={-14} y={14 - h} width={28} height={h} fill="var(--load)" opacity={0.85} clipPath={`url(#${id})`} style={{ transition: 'y .8s, height .8s' }} />
      <path d="M-15 -1 L0 -14 L15 -1 M-12 -3.5 V14 H12 V-3.5" fill="none" stroke="var(--load)" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" />
    </g>
  )
}

export function InverterIcon({ active }: { active: boolean }) {
  return (
    <g>
      <rect x={-13} y={-15} width={26} height={30} rx={5} fill="none" stroke="var(--inv)" strokeWidth={2.2} />
      <path d="M-6 -8h12" stroke="var(--inv)" strokeWidth={2.2} strokeLinecap="round" />
      <path d="M-7 4q3.5-8 7 0t7 0" fill="none" stroke="var(--inv)" strokeWidth={2.2} strokeLinecap="round"
        strokeDasharray={active ? '4 2' : undefined} style={{ animation: active ? 'wave 1.2s linear infinite' : 'none' }} />
    </g>
  )
}
