import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { hhmm, hourLabel } from '../../lib/format'
import { cn } from '../ui/ui'

export interface Series {
  key: string
  name: string
  color: string // CSS colour, e.g. 'var(--solar)'
  values: (number | null)[]
  area?: boolean
  fmt?: (v: number) => string
}

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [w, setW] = useState(0)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(() => setW(el.clientWidth))
    ro.observe(el)
    setW(el.clientWidth)
    return () => ro.disconnect()
  }, [])
  return [ref, w] as const
}

function niceStep(range: number, n: number) {
  const raw = range / Math.max(1, n)
  const p = Math.pow(10, Math.floor(Math.log10(raw)))
  const f = raw / p
  return (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * p
}

function yAxis(values: number[], h: number, fixed: { min?: number; max?: number }, minRange: number) {
  let lo = fixed.min ?? Infinity
  let hi = fixed.max ?? -Infinity
  for (const v of values) {
    if (fixed.min == null && v < lo) lo = v
    if (fixed.max == null && v > hi) hi = v
  }
  if (!isFinite(lo)) lo = 0
  if (!isFinite(hi)) hi = minRange
  if (fixed.min == null) lo = Math.min(0, lo)
  if (hi - lo < minRange) hi = lo + minRange
  const step = niceStep(hi - lo, Math.max(2, Math.round(h / 52)))
  if (fixed.min == null) lo = Math.floor(lo / step) * step
  if (fixed.max == null) hi = Math.ceil(hi / step) * step
  const ticks: number[] = []
  for (let v = lo; v <= hi + step / 2; v += step) ticks.push(+v.toFixed(6))
  return { lo, hi, ticks }
}

/** Tooltip box placed next to x, flipped and clamped so it never leaves the chart. */
function Tip({ x, width, title, rows }: { x: number; width: number; title: string; rows: { color: string; name: string; value: string }[] }) {
  const ref = useRef<HTMLDivElement>(null)
  const [tw, setTw] = useState(160)
  useLayoutEffect(() => {
    if (ref.current) setTw(ref.current.offsetWidth)
  })
  let left = x + 14
  if (left + tw > width) left = x - 14 - tw
  left = Math.max(0, Math.min(left, width - tw))
  return (
    <div ref={ref} className="pointer-events-none absolute top-1 z-10 min-w-36 rounded-2xl border border-border-strong bg-surface-solid/95 px-3 py-2 text-xs shadow-xl backdrop-blur" style={{ left }}>
      <div className="num mb-1 text-text-2">{title}</div>
      {rows.map((r) => (
        <div key={r.name} className="flex items-center gap-2 py-0.5">
          <span className="size-2 shrink-0 rounded-full" style={{ background: r.color }} />
          <span className="flex-1 whitespace-nowrap text-text-2">{r.name}</span>
          <span className="num whitespace-nowrap pl-3 font-semibold">{r.value}</span>
        </div>
      ))}
    </div>
  )
}

export function Legend({ items, hidden, onToggle }: { items: { key: string; name: string; color: string }[]; hidden: Set<string>; onToggle: (k: string) => void }) {
  return (
    <div className="mb-2 flex flex-wrap gap-1.5" role="group" aria-label="Show or hide series">
      {items.map((s) => {
        const off = hidden.has(s.key)
        return (
          <button
            key={s.key}
            aria-pressed={!off}
            onClick={() => onToggle(s.key)}
            className={cn('focus-ring inline-flex min-h-11 items-center gap-2 rounded-full border px-3 text-xs font-medium transition md:min-h-9', off ? 'border-border text-text-3' : 'border-border-strong bg-surface-2 text-text')}
          >
            <span className="size-2.5 rounded-full transition-opacity" style={{ background: s.color, opacity: off ? 0.3 : 1 }} />
            {s.name}
          </button>
        )
      })}
    </div>
  )
}

export function useHidden() {
  const [hidden, set] = useState<Set<string>>(new Set())
  const toggle = (k: string) => set((h) => {
    const n = new Set(h)
    if (n.has(k)) n.delete(k)
    else n.add(k)
    return n
  })
  return [hidden, toggle] as const
}

const L = 8 // left padding before the y labels area is computed
const BOTTOM = 24
const TOP = 10

export function TimeChart({
  xs, series, x0, x1, height = 240, yMin, yMax, minRange = 100, gapMs = 20_000, yFmt, valueFmt, titleFmt = hhmm, empty = 'No data yet',
}: {
  xs: number[]; series: Series[]; x0: number; x1: number; height?: number; yMin?: number; yMax?: number; minRange?: number; gapMs?: number
  yFmt: (v: number) => string; valueFmt: (v: number) => string; titleFmt?: (t: number, i: number) => string; empty?: ReactNode
}) {
  const [ref, W] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const all = useMemo(() => series.flatMap((s) => s.values.filter((v): v is number => v != null)), [series])
  const ax = yAxis(all, height - TOP - BOTTOM, { min: yMin, max: yMax }, minRange)
  const left = Math.max(...ax.ticks.map((t) => yFmt(t).length)) * 7 + L + 6
  const pw = Math.max(1, W - left - 6)
  const ph = height - TOP - BOTTOM
  const span = Math.max(1, x1 - x0)
  const X = (t: number) => left + ((t - x0) / span) * pw
  const Y = (v: number) => TOP + ((ax.hi - v) / (ax.hi - ax.lo || 1)) * ph

  const paths = useMemo(() => series.map((s) => {
    let line = ''
    let area = ''
    let open = false
    let segStart = 0
    let lastX = 0
    const base = Y(Math.max(ax.lo, 0))
    for (let i = 0; i < xs.length; i++) {
      const v = s.values[i]
      const brk = v == null || (i > 0 && xs[i] - xs[i - 1] > gapMs)
      if (brk && open) {
        if (s.area) area += `L${lastX},${base}L${segStart},${base}Z`
        open = false
      }
      if (v == null) continue
      const x = X(xs[i])
      const y = Y(v)
      if (!open) {
        line += `M${x},${y}`
        if (s.area) area += `M${x},${base}L${x},${y}`
        segStart = x
        open = true
      } else {
        line += `L${x},${y}`
        if (s.area) area += `L${x},${y}`
      }
      lastX = x
    }
    if (open && s.area) area += `L${lastX},${base}L${segStart},${base}Z`
    return { line, area }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [series, xs, W, height, ax.lo, ax.hi, x0, x1])

  // time ticks on whole local minutes/hours
  const cand = [60e3, 120e3, 300e3, 600e3, 900e3, 1800e3, 3600e3, 7200e3, 10800e3, 21600e3]
  const iv = cand.find((c) => pw / (span / c) >= 64) ?? 21600e3
  const tz = new Date(x0).getTimezoneOffset() * 60e3
  const xt: number[] = []
  for (let t = Math.ceil((x0 - tz) / iv) * iv + tz; t <= x1; t += iv) if (X(t) > left + 16 && X(t) < W - 16) xt.push(t)

  let idx: number | null = null
  if (hover != null && xs.length) {
    const t = x0 + ((hover - left) / pw) * span
    let a = 0
    let b = xs.length - 1
    while (b - a > 1) {
      const m = (a + b) >> 1
      if (xs[m] < t) a = m
      else b = m
    }
    idx = Math.abs(xs[a] - t) <= Math.abs(xs[b] - t) ? a : b
    if (Math.abs(xs[idx] - t) > (span / pw) * 40) idx = null
  }
  const move = (e: React.PointerEvent) => {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
    const x = e.clientX - r.left
    setHover(x >= left && x <= W ? x : null)
  }

  return (
    <div ref={ref} className="relative w-full select-none" style={{ height, touchAction: 'pan-y' }} onPointerMove={move} onPointerDown={move} onPointerLeave={() => setHover(null)}>
      {W > 0 && (
        <svg width={W} height={height} className="block overflow-visible" role="img" aria-label={series.map((s) => s.name).join(', ') + ' chart'}>
          {ax.ticks.map((t) => (
            <g key={t}>
              <line x1={left} x2={W - 6} y1={Y(t)} y2={Y(t)} stroke={t === 0 && ax.lo < 0 ? 'var(--text-3)' : 'var(--grid-line)'} strokeWidth={1} />
              <text x={left - 8} y={Y(t)} dy="0.32em" textAnchor="end" className="num fill-text-3 text-[11px]">{yFmt(t)}</text>
            </g>
          ))}
          {xt.map((t) => (
            <text key={t} x={X(t)} y={height - 6} textAnchor="middle" className="num fill-text-3 text-[11px]">{hhmm(t)}</text>
          ))}
          {series.map((s, i) => s.area && <path key={s.key + 'a'} d={paths[i].area} fill={s.color} opacity={0.14} />)}
          {series.map((s, i) => (
            <path key={s.key} d={paths[i].line} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          ))}
          {idx != null && (
            <g>
              <line x1={X(xs[idx])} x2={X(xs[idx])} y1={TOP} y2={TOP + ph} stroke="var(--text-3)" strokeDasharray="3 3" />
              {series.map((s) => s.values[idx!] != null && (
                <circle key={s.key} cx={X(xs[idx!])} cy={Y(s.values[idx!]!)} r={4.5} fill={s.color} stroke="var(--surface-solid)" strokeWidth={2} />
              ))}
            </g>
          )}
        </svg>
      )}
      {W > 0 && !xs.length && <div className="absolute inset-0 grid place-items-center text-sm text-text-3" style={{ paddingLeft: left }}>{empty}</div>}
      {idx != null && (
        <Tip x={X(xs[idx])} width={W} title={titleFmt(xs[idx], idx)} rows={series.filter((s) => s.values[idx!] != null).map((s) => ({ color: s.color, name: s.name, value: (s.fmt ?? valueFmt)(s.values[idx!]!) }))} />
      )}
    </div>
  )
}

/** Bars per category. Each entry of `stacks` is one bar made of the listed series stacked bottom-up. */
export function BarChart({
  labels, titles, series, stacks, height = 260, yFmt, valueFmt,
}: { labels: string[]; titles: string[]; series: Series[]; stacks: string[][]; height?: number; yFmt: (v: number) => string; valueFmt: (v: number) => string }) {
  const [ref, W] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const byKey = Object.fromEntries(series.map((s) => [s.key, s]))
  const visibleStacks = stacks.map((st) => st.filter((k) => byKey[k])).filter((st) => st.length)
  const n = labels.length
  const totals: number[] = []
  for (let i = 0; i < n; i++) for (const st of visibleStacks) totals.push(st.reduce((a, k) => a + Math.max(0, byKey[k].values[i] ?? 0), 0))
  const ax = yAxis(totals, height - TOP - BOTTOM, { min: 0 }, 100)
  const left = Math.max(...ax.ticks.map((t) => yFmt(t).length)) * 7 + L + 6
  const pw = Math.max(1, W - left - 6)
  const ph = height - TOP - BOTTOM
  const gw = pw / Math.max(1, n)
  const k = visibleStacks.length
  const bw = Math.max(2, Math.min(26, (gw * 0.78 - (k - 1) * 3) / Math.max(1, k)))
  const Y = (v: number) => TOP + ((ax.hi - v) / (ax.hi - ax.lo || 1)) * ph
  const every = Math.max(1, Math.ceil(n / Math.max(1, Math.floor(pw / 56))))

  const move = (e: React.PointerEvent) => {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
    const i = Math.floor((e.clientX - r.left - left) / gw)
    setHover(i >= 0 && i < n ? i : null)
  }
  const bar = (x: number, y0: number, y1: number, top: boolean, color: string, key: string) => {
    const h = Math.max(0, y0 - y1)
    if (h < 0.5) return null
    const r = top ? Math.min(5, bw / 2, h) : 0
    return (
      <path key={key} fill={color}
        d={`M${x},${y0}L${x},${y1 + r}Q${x},${y1} ${x + r},${y1}L${x + bw - r},${y1}Q${x + bw},${y1} ${x + bw},${y1 + r}L${x + bw},${y0}Z`} />
    )
  }

  return (
    <div ref={ref} className="relative w-full select-none" style={{ height, touchAction: 'pan-y' }} onPointerMove={move} onPointerDown={move} onPointerLeave={() => setHover(null)}>
      {W > 0 && (
        <svg width={W} height={height} className="block" role="img" aria-label="Bar chart">
          {ax.ticks.map((t) => (
            <g key={t}>
              <line x1={left} x2={W - 6} y1={Y(t)} y2={Y(t)} stroke="var(--grid-line)" />
              <text x={left - 8} y={Y(t)} dy="0.32em" textAnchor="end" className="num fill-text-3 text-[11px]">{yFmt(t)}</text>
            </g>
          ))}
          {hover != null && <rect x={left + hover * gw} y={TOP} width={gw} height={ph} rx={8} fill="var(--text)" opacity={0.05} />}
          {labels.map((l, i) => (n - 1 - i) % every === 0 && (
            <text key={i} x={left + (i + 0.5) * gw} y={height - 6} textAnchor="middle" className="fill-text-3 text-[11px]">{l}</text>
          ))}
          {labels.map((_, i) => visibleStacks.map((st, j) => {
            const x = left + (i + 0.5) * gw - (k * bw + (k - 1) * 3) / 2 + j * (bw + 3)
            let acc = 0
            return st.map((key, m) => {
              const v = Math.max(0, byKey[key].values[i] ?? 0)
              const y0 = Y(acc)
              acc += v
              return bar(x, y0, Y(acc), m === st.length - 1 || st.slice(m + 1).every((kk) => !(byKey[kk].values[i] ?? 0)), byKey[key].color, `${i}-${key}`)
            })
          }))}
        </svg>
      )}
      {hover != null && (
        <Tip x={left + (hover + 0.5) * gw} width={W} title={titles[hover]}
          rows={series.map((s) => ({ color: s.color, name: s.name, value: s.values[hover] == null ? '–' : (s.fmt ?? valueFmt)(s.values[hover]!) }))} />
      )}
    </div>
  )
}

/** 24 h strip: coloured where the grid was on, dim where off, empty where the monitor had no data. */
export function GridStrip({ recs, dayStart }: { recs: { t: number; flags: number }[]; dayStart: number }) {
  const [ref, W] = useWidth<HTMLDivElement>()
  return (
    <div ref={ref} className="w-full">
      <svg width={W} height={22} className="block overflow-hidden rounded-lg" role="img" aria-label="Grid availability over the day">
        <rect width={W} height={22} fill="var(--surface-2)" />
        {recs.map((r) => (
          <rect key={r.t} x={((r.t - dayStart) / 864e5) * W} width={Math.max(1, W / 1440 + 0.4)} height={22}
            fill={r.flags & 1 ? 'var(--grid)' : 'var(--crit)'} opacity={r.flags & 1 ? 0.75 : 1} />
        ))}
      </svg>
      <div className="num mt-1 flex justify-between text-[11px] text-text-3">
        {[0, 6, 12, 18, 24].map((h) => <span key={h}>{hourLabel(h)}</span>)}
      </div>
    </div>
  )
}
