import { useEffect, useId, useRef } from 'react'
import { fmtW } from '../../lib/format'
import { battState } from '../../lib/power'
import type { Live } from '../../lib/types'
import { BatteryIcon, GridIcon, GridOffMark, HomeIcon, InverterIcon, SolarIcon } from '../icons/EnergyIcons'

// Design space 400 x 310; the SVG scales to its container.
const P = { solar: [72, 80], grid: [328, 80], inv: [200, 155], batt: [72, 230], home: [328, 230] } as const
const PATHS = {
  solar: `M72 80 C 140 80, 200 104, 200 155`,
  grid: `M328 80 C 260 80, 200 104, 200 155`,
  batt: `M72 230 C 140 230, 200 206, 200 155`,
  home: `M200 155 C 200 206, 260 230, 328 230`,
}
type Key = keyof typeof PATHS
const COLORS: Record<Key, string> = { solar: 'var(--solar)', grid: 'var(--grid)', batt: 'var(--batt)', home: 'var(--load)' }
const DEADBAND = 15 // W: smaller flows are shown as idle so lines don't flicker
const MAX_DOTS = 7

interface Flow { w: number; reverse: boolean }

/** Watts and direction on each line. Paths run solar→inv, grid→inv, batt→inv, inv→home. */
export function flowsOf(d: Live | null, idleW = DEADBAND): Record<Key, Flow> {
  const z = { w: 0, reverse: false }
  if (!d || !d.ok) return { solar: z, grid: z, batt: z, home: z }
  const f = (w: number, reverseWhenNegative: boolean, band = DEADBAND): Flow =>
    Math.abs(w) < band ? z : { w: Math.abs(w), reverse: reverseWhenNegative ? w < 0 : false }
  return {
    solar: f(d.pvW, false),
    grid: f(d.gridW, true), // negative would mean exporting
    batt: f(-d.battW, true, Math.max(DEADBAND, idleW)), // battW < 0 = discharging = battery → inverter (forward)
    home: f(d.loadW, false),
  }
}

/** still: the monitor stopped answering, so nothing moves (the last values stay visible). */
export function FlowDiagram({ d, ratedW, still = false, idleW = DEADBAND }: { d: Live | null; ratedW: number; still?: boolean; idleW?: number }) {
  const uid = useId().replace(/:/g, '')
  const flows = flowsOf(still ? null : d, idleW)
  const bs = d ? battState(d, idleW) : 'idle'
  const live = useRef({ flows, ratedW })
  live.current = { flows, ratedW }
  const svgRef = useRef<SVGSVGElement>(null)
  const pathRefs = useRef<Partial<Record<Key, SVGPathElement>>>({})
  const dotRefs = useRef<Partial<Record<Key, (SVGGElement | null)[]>>>({})

  useEffect(() => {
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
    const phase: Record<Key, number> = { solar: 0, grid: 0, batt: 0, home: 0 }
    let raf = 0
    let last = 0
    let visible = true
    const io = new IntersectionObserver(([e]) => {
      visible = e.isIntersecting
      if (visible && !raf) raf = requestAnimationFrame(frame)
    })
    if (svgRef.current) io.observe(svgRef.current)

    function frame(ts: number) {
      raf = 0
      const dt = last ? Math.min(0.1, (ts - last) / 1000) : 0
      last = ts
      const { flows, ratedW } = live.current
      for (const k of Object.keys(PATHS) as Key[]) {
        const path = pathRefs.current[k]
        const dots = dotRefs.current[k] ?? []
        if (!path) continue
        const len = path.getTotalLength()
        const fl = flows[k]
        const frac = Math.min(1, fl.w / ratedW)
        const n = fl.w ? 2 + Math.round((MAX_DOTS - 2) * Math.sqrt(frac)) : 0
        // The dots carry information (direction of power), so with "reduce motion" they still move, just slowly and evenly.
        phase[k] = (phase[k] + (reduce ? 18 : 36 + 150 * Math.sqrt(frac)) * dt) % len
        for (let i = 0; i < MAX_DOTS; i++) {
          const g = dots[i]
          if (!g) continue
          if (i >= n) {
            g.style.display = 'none'
            continue
          }
          let s = (phase[k] + (i * len) / n) % len
          if (fl.reverse) s = len - s
          const p = path.getPointAtLength(s)
          g.setAttribute('transform', `translate(${p.x.toFixed(1)} ${p.y.toFixed(1)})`)
          g.style.opacity = Math.min(1, Math.min(s, len - s) / 28).toFixed(2)
          g.style.display = ''
        }
      }
      if (visible && !document.hidden) raf = requestAnimationFrame(frame)
    }
    const onVis = () => {
      if (!document.hidden && !raf) {
        last = 0
        raf = requestAnimationFrame(frame)
      }
    }
    document.addEventListener('visibilitychange', onVis)
    raf = requestAnimationFrame(frame)
    return () => {
      cancelAnimationFrame(raf)
      io.disconnect()
      document.removeEventListener('visibilitychange', onVis)
    }
  }, [])

  const ok = !!d?.ok && !still
  const solarFrac = d ? Math.min(1, d.pvW / ratedW) : 0
  const label = d?.ever
    ? `Solar ${fmtW(d.pvW)}, home ${fmtW(d.loadW)}, battery ${d.battPct} percent ${bs}, grid ${d.gridOn ? 'on' : 'off'}`
    : 'Power flow, waiting for data'

  const node = (k: 'solar' | 'grid' | 'batt' | 'home' | 'inv', active: boolean, color: string, r: number) => (
    <>
      <circle cx={P[k][0]} cy={P[k][1]} r={r + 14} fill={`url(#${uid}-glow-${k})`} opacity={active ? 1 : 0} style={{ transition: 'opacity .6s' }} />
      <circle cx={P[k][0]} cy={P[k][1]} r={r} fill="var(--surface-solid)" stroke={active ? color : 'var(--border-strong)'} strokeWidth={2} style={{ transition: 'stroke .5s' }} />
    </>
  )
  const lineWidth = (k: Key) => (flows[k].w ? 3 + 4 * Math.sqrt(Math.min(1, flows[k].w / ratedW)) : 3)

  return (
    <svg ref={svgRef} viewBox="0 0 400 310" className="block h-auto w-full overflow-visible" role="img" aria-label={label}>
      <defs>
        {(['solar', 'grid', 'batt', 'home', 'inv'] as const).map((k) => (
          <radialGradient key={k} id={`${uid}-glow-${k}`}>
            <stop offset="55%" stopColor={k === 'inv' ? 'var(--inv)' : COLORS[k as Key]} stopOpacity={0.35} />
            <stop offset="100%" stopColor={k === 'inv' ? 'var(--inv)' : COLORS[k as Key]} stopOpacity={0} />
          </radialGradient>
        ))}
      </defs>

      {(Object.keys(PATHS) as Key[]).map((k) => (
        <path key={k} ref={(el) => { if (el) pathRefs.current[k] = el }} d={PATHS[k]} fill="none" strokeLinecap="round"
          stroke={flows[k].w ? COLORS[k] : 'var(--border-strong)'} strokeOpacity={flows[k].w ? 0.38 : 1} strokeWidth={lineWidth(k)}
          style={{ transition: 'stroke-width .6s, stroke .5s' }} />
      ))}

      {(Object.keys(PATHS) as Key[]).map((k) => (
        <g key={k + 'dots'}>
          {Array.from({ length: MAX_DOTS }, (_, i) => (
            <g key={i} ref={(el) => { (dotRefs.current[k] ??= [])[i] = el }} style={{ display: 'none' }}>
              <circle r={7.5} fill={COLORS[k]} opacity={0.22} />
              <circle r={3.8} fill={COLORS[k]} />
            </g>
          ))}
        </g>
      ))}

      {node('inv', ok, 'var(--inv)', 29)}
      <g transform={`translate(${P.inv[0]} ${P.inv[1]})`}><InverterIcon active={ok} /></g>

      {node('solar', ok && flows.solar.w > 0, 'var(--solar)', 32)}
      <g transform={`translate(${P.solar[0]} ${P.solar[1]})`}><SolarIcon intensity={ok ? solarFrac : 0} spinS={ok && flows.solar.w ? 26 - 22 * solarFrac : 0} /></g>

      {node('grid', !!d?.gridOn, 'var(--grid)', 32)}
      <g transform={`translate(${P.grid[0]} ${P.grid[1]})`}>
        <GridIcon on={!d || d.gridOn} importing={ok && flows.grid.w > 0} />
        {d?.ever && !d.gridOn && <GridOffMark />}
      </g>

      {node('batt', ok && flows.batt.w > 0, 'var(--batt)', 32)}
      <g transform={`translate(${P.batt[0]} ${P.batt[1]})`}><BatteryIcon pct={d?.battPct ?? 0} charging={ok && bs === 'charging'} /></g>

      {node('home', ok && flows.home.w > 0, 'var(--load)', 32)}
      <g transform={`translate(${P.home[0]} ${P.home[1]})`}><HomeIcon id={`${uid}-home`} frac={d ? Math.max(d.loadW > 0 ? 0.06 : 0, d.loadPct / 100) : 0} /></g>

      {d?.ever && (
        <g className="num" textAnchor="middle">
          <NodeText x={72} y={80 - 50} big={fmtW(d.pvW)} small="Solar" smallBelow={80 + 50} />
          <NodeText x={328} y={80 - 50} big={d.gridOn ? (d.gridW > DEADBAND ? fmtW(d.gridW) : `${Math.round(d.gridV)} V`) : 'Off'} small={d.gridOn ? (d.gridW > DEADBAND ? 'Grid · in use' : 'Grid · standby') : 'Grid off'} smallBelow={80 + 50} />
          <NodeText x={72} y={230 + 58} big={`${d.battPct}%`} small={bs === 'charging' ? `Charging ${fmtW(d.battW)}` : bs === 'discharging' ? `Discharging ${fmtW(-d.battW)}` : d.battPct >= 99 ? 'Full' : 'Idle'} smallBelow={230 - 46} />
          <NodeText x={328} y={230 + 58} big={fmtW(d.loadW)} small={`Home · ${d.loadPct}%`} smallBelow={230 - 46} />
        </g>
      )}
    </svg>
  )
}

function NodeText({ x, y, big, small, smallBelow }: { x: number; y: number; big: string; small: string; smallBelow: number }) {
  return (
    <>
      <text x={x} y={y} dy="0.35em" className="fill-text text-[20px] font-semibold">{big}</text>
      <text x={x} y={smallBelow} dy="0.35em" className="fill-text-2 font-sans text-[12.5px] font-medium">{small}</text>
    </>
  )
}
