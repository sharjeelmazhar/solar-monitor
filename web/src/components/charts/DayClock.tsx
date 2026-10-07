import { useId, useMemo, useState } from 'react'
import { fmtDuration, fmtWh, hhmm, hourLabel } from '../../lib/format'
import { duringOutage, hourlyMix, type HourMix, type Outage } from '../../lib/outages'
import type { MinRec } from '../../lib/types'
import { cn } from '../ui/ui'

// A 24-hour dial: midnight at the top, noon at the bottom, clockwise.
//  - 24 hour wedges: what powered the home (solar inside, battery, grid outside); longer wedge = more use.
//  - outer ring: grid available (pink) or off (red hatching), to the minute.
const C = 160
const R0 = 52 // hole for the centre text
const R1 = 118 // longest wedge
const RING0 = 126
const RING1 = 138
const DAY = 864e5
const MIN = 60_000

const ang = (frac: number) => frac * 2 * Math.PI - Math.PI / 2
const pt = (r: number, a: number) => `${(C + r * Math.cos(a)).toFixed(2)} ${(C + r * Math.sin(a)).toFixed(2)}`

/** Ring segment between radii r0..r1 and angles a0..a1 (radians). */
function sector(r0: number, r1: number, a0: number, a1: number) {
  const large = a1 - a0 > Math.PI ? 1 : 0
  return `M${pt(r1, a0)} A${r1} ${r1} 0 ${large} 1 ${pt(r1, a1)} L${pt(r0, a1)} A${r0} ${r0} 0 ${large} 0 ${pt(r0, a0)} Z`
}

interface Run { on: boolean; from: number; to: number }

/** Consecutive minutes with the same grid state (gaps over 2 minutes = no data, not drawn). */
function runs(recs: MinRec[]): Run[] {
  const out: Run[] = []
  for (const r of recs) {
    const on = (r.flags & 1) === 1
    const last = out[out.length - 1]
    if (last && last.on === on && r.t - last.to <= 2 * MIN) last.to = r.t + MIN
    else out.push({ on, from: r.t, to: r.t + MIN })
  }
  return out
}

type Sel = { kind: 'hour'; h: number } | { kind: 'out'; i: number } | null

export function DayClock({ recs, dayStart, outages, now }: { recs: MinRec[]; dayStart: number; outages: Outage[]; now?: number }) {
  const uid = useId().replace(/:/g, '')
  const mix = useMemo(() => hourlyMix(recs), [recs])
  const ring = useMemo(() => runs(recs), [recs])
  const outs = useMemo(() => outages.filter((o) => o.end > dayStart && o.start < dayStart + DAY), [outages, dayStart])
  const [hover, setHover] = useState<Sel>(null)
  const [pinned, setPinned] = useState<Sel>(null)
  const sel = hover ?? pinned
  const maxWh = Math.max(1, ...mix.map((h) => h.solar + h.batt + h.grid))
  const frac = (t: number) => Math.min(1, Math.max(0, (t - dayStart) / DAY))
  const pick = (s: Sel) => setPinned((p) => (JSON.stringify(p) === JSON.stringify(s) ? null : s))
  const offTotal = mix.reduce((a, h) => a + h.offMin, 0)

  const wedge = (h: HourMix) => {
    const total = h.solar + h.batt + h.grid
    const a0 = ang(h.hour / 24) + 0.012
    const a1 = ang((h.hour + 1) / 24) - 0.012
    const isSel = sel?.kind === 'hour' && sel.h === h.hour
    const mid = (a0 + a1) / 2
    const pop = isSel ? 7 : 0
    const len = h.minutes ? (R1 - R0) * Math.max(0.16, Math.sqrt(total / maxWh)) : 0
    let r = R0
    const parts: { r0: number; r1: number; color: string }[] = []
    if (total > 0) {
      for (const [v, color] of [[h.solar, 'var(--solar)'], [h.batt, 'var(--batt)'], [h.grid, 'var(--grid)']] as const) {
        if (v <= 0) continue
        const r1 = r + (len * v) / total
        parts.push({ r0: r, r1, color })
        r = r1
      }
    }
    return (
      <g key={h.hour} transform={`translate(${(Math.cos(mid) * pop).toFixed(2)} ${(Math.sin(mid) * pop).toFixed(2)})`} style={{ transition: 'transform .2s ease-out' }}
        role="button" tabIndex={0} aria-label={hourText(h)} className="cursor-pointer outline-none"
        onPointerEnter={(e) => e.pointerType === 'mouse' && setHover({ kind: 'hour', h: h.hour })}
        onPointerLeave={() => setHover(null)} onFocus={() => setHover({ kind: 'hour', h: h.hour })} onBlur={() => setHover(null)}
        onClick={() => pick({ kind: 'hour', h: h.hour })} onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), pick({ kind: 'hour', h: h.hour }))}>
        {/* hit area: the whole hour slice */}
        <path d={sector(R0, R1, a0, a1)} fill={isSel ? 'var(--surface-3)' : 'var(--surface-2)'} />
        {parts.map((p, i) => <path key={i} d={sector(p.r0, p.r1, a0, a1)} fill={p.color} opacity={sel && !isSel ? 0.45 : 1} style={{ transition: 'opacity .2s' }} />)}
        {isSel && <path d={sector(R0, R1, a0, a1)} fill="none" stroke="var(--text)" strokeWidth={1.5} />}
      </g>
    )
  }

  const nowA = now != null && now >= dayStart && now < dayStart + DAY ? ang(frac(now)) : null

  return (
    <div className="grid gap-3">
      <svg viewBox="0 0 320 320" className="mx-auto block w-full max-w-[380px] select-none overflow-visible" role="group" aria-label="24-hour clock of the day: what powered the home each hour and when the grid was off">
        <defs>
          <pattern id={`${uid}-hatch`} width="5" height="5" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="5" height="5" fill="var(--crit)" opacity="0.28" />
            <rect width="2.2" height="5" fill="var(--crit)" />
          </pattern>
        </defs>

        {/* hour ticks + labels */}
        {Array.from({ length: 24 }, (_, h) => {
          const a = ang(h / 24)
          const major = h % 6 === 0
          return (
            <g key={h}>
              <path d={`M${pt(RING1 + 3, a)} L${pt(RING1 + (major ? 9 : 6), a)}`} stroke="var(--border-strong)" strokeWidth={major ? 2 : 1} />
              {h % 3 === 0 && (
                <text x={C + (RING1 + (major ? 22 : 17)) * Math.cos(a)} y={C + (RING1 + (major ? 22 : 17)) * Math.sin(a)} dy="0.35em" textAnchor="middle"
                  className={cn('num', major ? 'fill-text-2 text-[11px] font-semibold' : 'fill-text-3 text-[10px]')}>
                  {major ? hourLabel(h) : hourLabel(h).replace(/ ?[AP]M$/, '').replace(/:00$/, '')}
                </text>
              )}
            </g>
          )
        })}

        {/* outer ring: grid state */}
        <circle cx={C} cy={C} r={(RING0 + RING1) / 2} fill="none" stroke="var(--surface-3)" strokeWidth={RING1 - RING0} />
        {ring.map((r, i) => {
          const a0 = ang(frac(r.from))
          const a1 = Math.min(ang(frac(r.to)), a0 + 2 * Math.PI - 0.001) // a full circle would be an empty arc
          if (a1 - a0 < 0.002) return null
          const outIdx = r.on ? -1 : outs.findIndex((o) => o.start < r.to && o.end > r.from)
          const isSel = !r.on && sel?.kind === 'out' && sel.i === outIdx
          const s: Sel = outIdx >= 0 ? { kind: 'out', i: outIdx } : null
          return (
            <path key={i} d={sector(isSel ? RING0 - 4 : RING0, isSel ? RING1 + 3 : RING1, a0, a1)}
              fill={r.on ? 'var(--grid)' : `url(#${uid}-hatch)`} opacity={r.on ? 0.55 : 1}
              stroke={r.on ? 'none' : 'var(--crit)'} strokeWidth={r.on ? 0 : 1}
              className={s ? 'cursor-pointer' : undefined}
              onPointerEnter={(e) => s && e.pointerType === 'mouse' && setHover(s)} onPointerLeave={() => setHover(null)}
              onClick={() => s && pick(s)} />
          )
        })}

        {mix.map(wedge)}

        {/* now hand */}
        {nowA != null && (
          <g pointerEvents="none">
            <path d={`M${pt(R0 - 2, nowA)} L${pt(RING1 + 4, nowA)}`} stroke="var(--text)" strokeWidth={2} strokeLinecap="round" />
            <circle cx={C + (RING1 + 4) * Math.cos(nowA)} cy={C + (RING1 + 4) * Math.sin(nowA)} r={4} fill="var(--text)" />
          </g>
        )}

        {/* centre */}
        <circle cx={C} cy={C} r={R0 - 4} fill="var(--surface-solid)" stroke="var(--border)" />
        <CenterText sel={sel} mix={mix} outs={outs} offTotal={offTotal} now={nowA != null ? now : undefined} />
      </svg>

      <div className="flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs text-text-2">
        <Key color="var(--solar)">Solar</Key>
        <Key color="var(--batt)">Battery</Key>
        <Key color="var(--grid)">Grid (used)</Key>
        <Key color="var(--grid)" ring>Grid available</Key>
        <Key hatch={`url(#${uid}-hatch)`}>Grid off</Key>
      </div>

      <Details sel={sel} mix={mix} outs={outs} recs={recs} onClear={() => setPinned(null)} pinned={!!pinned} />
    </div>
  )
}

function hourText(h: HourMix) {
  const total = h.solar + h.batt + h.grid
  const span = `${hourLabel(h.hour)} to ${hourLabel(h.hour + 1)}`
  if (!h.minutes) return `${span}: no data`
  const pct = (v: number) => (total > 0 ? Math.round((v / total) * 100) : 0)
  return `${span}: home used ${fmtWh(total)}, solar ${pct(h.solar)}%, battery ${pct(h.batt)}%, grid ${pct(h.grid)}%` + (h.offMin ? `, grid off ${h.offMin} min` : '')
}

function CenterText({ sel, mix, outs, offTotal, now }: { sel: Sel; mix: HourMix[]; outs: Outage[]; offTotal: number; now?: number }) {
  let a = ''
  let b = ''
  let c = ''
  if (sel?.kind === 'hour') {
    const h = mix[sel.h]
    a = `${hourLabel(sel.h)}–${hourLabel(sel.h + 1)}`.replace(/ (AM|PM)–(\d+) \1$/, '–$2 $1')
    b = h.minutes ? fmtWh(h.solar + h.batt + h.grid) : 'no data'
    c = h.offMin ? `grid off ${h.offMin}m` : h.minutes ? 'home use' : ''
  } else if (sel?.kind === 'out') {
    const o = outs[sel.i]
    a = 'Grid off'
    b = fmtDuration(o.minutes)
    c = `${hhmm(o.start)}`
  } else {
    const on = mix.reduce((x, h) => x + h.minutes, 0)
    a = now != null ? hhmm(now) : on ? 'Whole day' : ''
    b = outs.length ? `${outs.length} outage${outs.length > 1 ? 's' : ''}` : on ? 'No outages' : 'No data'
    c = offTotal ? `${fmtDuration(offTotal)} off` : on ? 'grid all day' : ''
  }
  return (
    <g textAnchor="middle" pointerEvents="none">
      <text x={C} y={C - 15} dy="0.35em" className="num fill-text-3 text-[10px] font-medium">{a}</text>
      <text x={C} y={C + 1} dy="0.35em" className="num fill-text text-[15px] font-semibold">{b}</text>
      <text x={C} y={C + 17} dy="0.35em" className="num fill-text-2 text-[10px]">{c}</text>
    </g>
  )
}

/** One fact per line, each with the colour it has on the clock. */
function Details({ sel, mix, outs, recs, onClear, pinned }: { sel: Sel; mix: HourMix[]; outs: Outage[]; recs: MinRec[]; onClear: () => void; pinned: boolean }) {
  let head: React.ReactNode = null
  let items: { color: string; hatch?: boolean; text: React.ReactNode }[] = []
  if (sel?.kind === 'hour') {
    const h = mix[sel.h]
    const total = h.solar + h.batt + h.grid
    const pct = (v: number) => (total > 0 ? Math.round((v / total) * 100) : 0)
    const inHour = outs.filter((o) => new Date(o.start).getHours() <= sel.h && new Date(o.end - 1).getHours() >= sel.h)
    head = <>{hourLabel(sel.h)} – {hourLabel(sel.h + 1)}{h.minutes ? <> · home used <span className="num">{fmtWh(total)}</span></> : null}</>
    if (!h.minutes) items = [{ color: 'var(--text-3)', text: 'The monitor has no data for this hour.' }]
    else {
      items = [
        { color: 'var(--solar)', text: <>Solar: <b className="num">{pct(h.solar)}%</b> of home use ({fmtWh(h.solar)}) · made {fmtWh(h.pv)} in total</> },
        { color: 'var(--batt)', text: <>Battery: <b className="num">{pct(h.batt)}%</b> ({fmtWh(h.batt)})</> },
        { color: 'var(--grid)', text: <>Grid: <b className="num">{pct(h.grid)}%</b> ({fmtWh(h.grid)})</> },
        ...inHour.map((o) => ({ color: 'var(--crit)', hatch: true, text: <>Grid off <b className="num">{hhmm(o.start)} – {o.ongoing ? 'now' : hhmm(o.end)}</b> ({fmtDuration(o.minutes)})</> })),
      ]
      if (h.minutes < 55) items.push({ color: 'var(--text-3)', text: `The monitor saw ${h.minutes} of 60 minutes` })
    }
  } else if (sel?.kind === 'out') {
    const o = outs[sel.i]
    const d = duringOutage(recs, o)
    head = <>Grid off · <span className="num">{fmtDuration(o.minutes)}</span></>
    items = [
      { color: 'var(--crit)', hatch: true, text: <>Went off {o.startKnown ? 'at' : 'before'} <b className="num">{hhmm(o.start)}</b></> },
      { color: o.ongoing ? 'var(--text-3)' : 'var(--grid)', text: o.ongoing ? 'Still off' : o.endKnown ? <>Came back at <b className="num">{hhmm(o.end)}</b></> : 'The monitor went offline before it came back' },
    ]
    if (d) {
      items.push({ color: 'var(--load)', text: <>Home used <b className="num">{fmtWh(d.homeWh)}</b> during it</> })
      if (d.solarWh > 1) items.push({ color: 'var(--solar)', text: <>Solar made <b className="num">{fmtWh(d.solarWh)}</b></> })
      items.push({ color: 'var(--batt)', text: <>Battery <b className="num">{d.socFrom}% → {d.socTo}%</b></> })
    }
  }
  return (
    <div className="min-h-12 rounded-2xl bg-surface-2 px-4 py-3 text-sm" aria-live="polite">
      {!sel ? (
        <span className="text-text-3">Tap an hour or a red part of the ring to see what happened then.</span>
      ) : (
        <>
          <div className="mb-1.5 flex items-center gap-2">
            <b className="flex-1 font-semibold">{head}</b>
            {pinned && <button onClick={onClear} className="focus-ring shrink-0 rounded-xl px-2 py-1 text-xs text-text-2 hover:bg-surface-3">Clear</button>}
          </div>
          <ul className="grid gap-1">
            {items.map((it, i) => (
              <li key={i} className="flex items-start gap-2.5 leading-relaxed">
                <span className="mt-[0.45em] size-2.5 shrink-0 rounded-full" style={{ background: it.hatch ? `repeating-linear-gradient(45deg, ${it.color} 0 2px, transparent 2px 4px)` : it.color, boxShadow: it.hatch ? `inset 0 0 0 1px ${it.color}` : undefined }} />
                <span className="text-text-2 [&_b]:font-semibold [&_b]:text-text">{it.text}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

function Key({ color, ring, hatch, children }: { color?: string; ring?: boolean; hatch?: string; children: React.ReactNode }) {
  return (
    <span className="inline-flex min-h-6 items-center gap-1.5">
      <svg width="14" height="14" aria-hidden>
        {hatch ? <rect x="1" y="1" width="12" height="12" rx="3" fill={hatch} stroke="var(--crit)" />
          : ring ? <circle cx="7" cy="7" r="5" fill="none" stroke={color} strokeWidth="3" opacity="0.6" />
            : <rect x="1" y="1" width="12" height="12" rx="3" fill={color} />}
      </svg>
      {children}
    </span>
  )
}
