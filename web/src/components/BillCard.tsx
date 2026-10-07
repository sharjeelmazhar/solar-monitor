import { AlertTriangle, ChevronDown, ShieldCheck, ShieldOff } from 'lucide-react'
import { useState } from 'react'
import { computeBill, cycleStart, DEFAULT_BILL, nextCycle, prevCycle, unitsToNextStep, type BillConfig } from '../lib/bill'
import { addDays, dayLabel, fmtPkr, fromYmd } from '../lib/format'
import { useStore } from '../lib/store'
import type { DayRec } from '../lib/types'
import { Card, CardHeader, Value, cn } from './ui/ui'

const daysBetween = (a: number, b: number) => Math.round((fromYmd(b).getTime() - fromYmd(a).getTime()) / 864e5)

export interface MonthUse {
  start: number
  end: number // last day of the billing month
  totalDays: number
  elapsed: number // days of this billing month so far, including today
  covered: number // days the monitor has data for
  gridUnits: number // measured so far
  selfUnits: number // home use not from the grid (solar + battery)
  solarUnits: number
  projected: number // grid units by the end of the month incl. extra units
  soFar: number // grid units so far incl. the extra units pro rata
  perDay: number
}

export function monthUse(byDate: Map<number, DayRec>, today: number, c: BillConfig): MonthUse {
  const start = cycleStart(today, c.day)
  const end = addDays(nextCycle(start), -1)
  const totalDays = daysBetween(start, end) + 1
  const elapsed = daysBetween(start, today) + 1
  let grid = 0
  let self = 0
  let solar = 0
  let covered = 0
  for (let k = start; k <= today; k = addDays(k, 1)) {
    const r = byDate.get(k)
    if (!r) continue
    covered++
    grid += r.grid / 1000
    self += Math.max(0, r.load - r.grid) / 1000
    solar += r.pv / 1000
  }
  // today counts as part of a day; days the monitor missed are assumed to be like the average
  const dayFrac = Math.max(0.25, new Date().getHours() / 24 + new Date().getMinutes() / 1440)
  const coveredDays = Math.max(dayFrac, covered - 1 + dayFrac)
  const perDay = covered ? grid / coveredDays : 0
  const extraPerDay = c.extra / totalDays
  return {
    start, end, totalDays, elapsed, covered, gridUnits: grid, selfUnits: self, solarUnits: solar,
    soFar: grid + extraPerDay * (elapsed - 1 + dayFrac),
    projected: perDay * totalDays + c.extra,
    perDay: perDay + extraPerDay,
  }
}

/** Protected status as far as the monitor's own history can tell (needs 6 full billing months). */
export function protectedFromHistory(byDate: Map<number, DayRec>, today: number, c: BillConfig) {
  const limit = c.ps[c.ps.length - 1][0]
  const months: { start: number; units: number; full: boolean }[] = []
  let s = prevCycle(cycleStart(today, c.day))
  for (let i = 0; i < 6; i++, s = prevCycle(s)) {
    const e = addDays(nextCycle(s), -1)
    let units = 0
    let days = 0
    for (let k = s; k <= e; k = addDays(k, 1)) {
      const r = byDate.get(k)
      if (r) { units += r.grid / 1000; days++ }
    }
    months.push({ start: s, units: units + c.extra, full: days >= daysBetween(s, e) + 1 - 2 })
  }
  const over = months.find((m) => m.full && m.units > limit)
  const complete = months.every((m) => m.full)
  return { months, over, verdict: over ? 'u' as const : complete ? 'p' as const : null }
}

export function BillCard({ byDate, today }: { byDate: Map<number, DayRec>; today: number }) {
  const cfg = useStore((s) => s.bill) ?? DEFAULT_BILL
  const [open, setOpen] = useState(false)
  const m = monthUse(byDate, today, cfg)
  const bill = computeBill(m.projected, cfg)
  const limit = cfg.ps[cfg.ps.length - 1][0]
  const step = unitsToNextStep(m.projected, cfg)
  const left = m.totalDays - m.elapsed
  const hist = protectedFromHistory(byDate, today, cfg)
  // what the solar/battery share would have cost on top of the grid units
  const withoutSolar = computeBill(m.projected + (m.selfUnits / Math.max(1, m.covered)) * m.totalDays, cfg)
  const saved = withoutSolar.total - bill.total

  let advice: React.ReactNode = null
  if (cfg.st === 'p') {
    if (bill.lostProtection) {
      const crossDay = m.perDay > 0 ? addDays(m.start, Math.max(0, Math.floor(limit / m.perDay))) : null
      const ifStay = computeBill(limit, cfg)
      advice = (
        <Note tone="crit" icon={<ShieldOff size={18} />}>
          At this pace the month ends near <b className="num">{Math.round(m.projected)}</b> units: over {limit}{crossDay && crossDay <= m.end ? `, around ${dayLabel(crossDay, { day: 'numeric', month: 'short' })}` : ''}.
          The whole month would then be billed at the unprotected rate (≈ {fmtPkr(bill.total)} instead of ≈ {fmtPkr(ifStay.total)} at {limit} units), and you lose protected status for the next 6 months.
          {left > 0 && m.soFar < limit && <> To stay protected, keep to about <b className="num">{((limit - m.soFar) / left).toFixed(1)}</b> units a day for the remaining {left} days.</>}
        </Note>
      )
    } else if (step) {
      advice = (
        <Note tone="good" icon={<ShieldCheck size={18} />}>
          On track to stay protected. {Math.round(limit - m.projected)} units of headroom by month end
          {left > 0 && <> · up to <b className="num">{((limit - m.soFar) / left).toFixed(1)}</b> units/day is safe for the remaining {left} days</>}.
        </Note>
      )
    }
  } else if (step && step.left < 40) {
    advice = (
      <Note tone="warn" icon={<AlertTriangle size={18} />}>
        {step.left} units before the next slab ({step.at}). Unprotected bills charge the slab you reach on every unit, so crossing it raises the whole bill.
      </Note>
    )
  }

  const scaleMax = Math.max(limit * 1.25, m.projected * 1.1, 50)
  const pct = (u: number) => `${Math.min(100, (u / scaleMax) * 100)}%`

  return (
    <Card>
      <CardHeader
        title="Electricity bill estimate"
        sub={`IESCO home tariff · billing month ${dayLabel(m.start, { day: 'numeric', month: 'short' })} – ${dayLabel(m.end, { day: 'numeric', month: 'short' })} · day ${m.elapsed} of ${m.totalDays}`}
        action={<span className={cn('rounded-full px-3 py-1 text-xs font-semibold', cfg.st === 'p' ? 'bg-good/15 text-good' : 'bg-warn/15 text-warn')}>{cfg.st === 'p' ? 'Protected' : 'Unprotected'}</span>}
      />
      <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
        <div>
          <p className="text-xs text-text-3">Expected bill this month</p>
          <Value text={fmtPkr(bill.total)} className="text-4xl tracking-tight" />
        </div>
        <div>
          <p className="text-xs text-text-3">Grid units</p>
          <p className="num text-lg font-semibold">{Math.round(m.soFar)} so far → ≈ {Math.round(m.projected)}</p>
        </div>
        {saved > 1 && (
          <div>
            <p className="text-xs text-text-3">Solar is saving you</p>
            <Value text={`≈ ${fmtPkr(saved)}`} className="text-lg text-good" />
          </div>
        )}
      </div>

      {/* units bar with the protected limit */}
      <div className="mt-4">
        <div className="relative h-3 rounded-full bg-surface-3">
          <div className="absolute inset-y-0 left-0 rounded-full bg-grid/35" style={{ width: pct(m.projected) }} />
          <div className="absolute inset-y-0 left-0 rounded-full bg-grid" style={{ width: pct(m.soFar) }} />
          {[100, limit].map((u) => <span key={u} className="absolute -bottom-1 -top-1 w-0.5 rounded bg-text-3" style={{ left: pct(u) }} />)}
        </div>
        <div className="num relative mt-1 h-4 text-[11px] text-text-3">
          {[100, limit].map((u) => <span key={u} className="absolute -translate-x-1/2" style={{ left: pct(u) }}>{u}</span>)}
        </div>
        <p className="mt-1 text-xs text-text-3">dark = used so far · light = expected by month end{m.covered < m.elapsed ? ` · monitor has ${m.covered} of ${m.elapsed} days, the rest are estimated` : ''}</p>
      </div>

      {advice && <div className="mt-3">{advice}</div>}

      <button onClick={() => setOpen(!open)} className="focus-ring mt-3 flex min-h-10 items-center gap-1 rounded-xl text-sm font-medium text-text-2 hover:text-text" aria-expanded={open}>
        <ChevronDown size={16} className={cn('transition-transform', open && 'rotate-180')} /> How it's worked out
      </button>
      {open && (
        <div className="mt-1 grid gap-3 text-sm">
          <table className="w-full">
            <tbody className="num [&_td]:py-1 [&_td:last-child]:text-right">
              <tr><td className="text-text-2">Energy: {bill.units} units{bill.tier === 'protected' && bill.slab > 0 ? ` (first ${cfg.ps[bill.slab - 1][0]} at Rs ${cfg.ps[bill.slab - 1][1]}, rest at Rs ${bill.rate})` : ` × Rs ${bill.rate}`}</td><td>{fmtPkr(bill.energy)}</td></tr>
              <tr><td className="text-text-2">Fixed charge ({cfg.kw} kW × Rs {(bill.tier === 'protected' ? cfg.ps : cfg.us)[bill.slab][2]})</td><td>{fmtPkr(bill.fixed)}</td></tr>
              {bill.adjust !== 0 && <tr><td className="text-text-2">Fuel / quarterly adjustment</td><td>{fmtPkr(bill.adjust)}</td></tr>}
              <tr><td className="text-text-2">Electricity duty {cfg.ed}%</td><td>{fmtPkr(bill.duty)}</td></tr>
              <tr><td className="text-text-2">Sales tax (GST) {cfg.gst}%</td><td>{fmtPkr(bill.gst)}</td></tr>
              {cfg.ptv > 0 && <tr><td className="text-text-2">PTV fee</td><td>{fmtPkr(cfg.ptv)}</td></tr>}
              <tr className="border-t border-border font-semibold"><td>Total</td><td>{fmtPkr(bill.total)}</td></tr>
            </tbody>
          </table>
          <p className="text-xs leading-relaxed text-text-3">
            Rates: NEPRA S.R.O. 279(I)/2026. Protected = every one of the last 6 months at or under {limit} units; going over once bills that month at the unprotected rate and removes the status for 6 months.
            Unprotected homes pay the rate of the slab they reach on every unit. Grid units are estimated from the inverter (it does not measure the grid directly), and your meter also counts anything not wired through this inverter: add that as "other units" in System → Bill settings. FPA changes every month; copy it from your bill.
          </p>
          <History hist={hist} limit={limit} />
        </div>
      )}
    </Card>
  )
}

function History({ hist, limit }: { hist: ReturnType<typeof protectedFromHistory>; limit: number }) {
  return (
    <div className="rounded-2xl bg-surface-2 p-3 text-xs">
      <p className="mb-2 font-semibold text-text-2">
        Last 6 months from the monitor: {hist.verdict === 'p' ? `all at or under ${limit} → protected` : hist.verdict === 'u' ? `${dayLabel(hist.over!.start, { month: 'short', year: 'numeric' })} was over ${limit} → unprotected` : 'not enough history yet, so the status comes from your setting'}
      </p>
      <div className="grid grid-cols-6 gap-1.5">
        {hist.months.slice().reverse().map((x) => (
          <div key={x.start} className="text-center">
            <div className={cn('num rounded-lg py-1 font-semibold', !x.full ? 'text-text-3' : x.units > limit ? 'bg-crit/15 text-crit' : 'bg-good/15 text-good')}>{x.full ? Math.round(x.units) : '–'}</div>
            <div className="mt-0.5 text-text-3">{fromYmd(x.start).toLocaleDateString(undefined, { month: 'short' })}</div>
          </div>
        ))}
      </div>
    </div>
  )
}

function Note({ tone, icon, children }: { tone: 'good' | 'warn' | 'crit'; icon: React.ReactNode; children: React.ReactNode }) {
  const c = { good: 'bg-good/10 text-good', warn: 'bg-warn/10 text-warn', crit: 'bg-crit/10 text-crit' }[tone]
  return (
    <div className={cn('flex gap-3 rounded-2xl p-3 text-sm leading-relaxed', c)}>
      <span className="mt-0.5 shrink-0">{icon}</span>
      <span className="text-text">{children}</span>
    </div>
  )
}
