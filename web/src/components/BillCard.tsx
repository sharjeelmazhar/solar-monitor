import { AlertTriangle, BellRing, ChevronDown, Info, ShieldCheck, ShieldOff } from 'lucide-react'
import { useState } from 'react'
import { addMonths, billMonth, computeBill, cycleStart, DEFAULT_BILL, nextCycle, prevCycle, unitAlert, type BillConfig } from '../lib/bill'
import { addDays, dayLabel, fmtPkr, fromYmd } from '../lib/format'
import { useStore } from '../lib/store'
import type { DayRec } from '../lib/types'
import { Card, CardHeader, Value, cn } from './ui/ui'

const daysBetween = (a: number, b: number) => Math.round((fromYmd(b).getTime() - fromYmd(a).getTime()) / 864e5)
export const monthName = (ym: number, opts: Intl.DateTimeFormatOptions = { month: 'short', year: '2-digit' }) =>
  new Date(Math.floor(ym / 100), (ym % 100) - 1, 1).toLocaleDateString(undefined, opts)

export interface MonthUse {
  start: number
  end: number // last day of the billing month
  totalDays: number
  elapsed: number // days of this billing month so far, including today
  covered: number // days the monitor has data for
  gridUnits: number // measured so far
  selfUnits: number // home use not from the grid (solar + battery)
  projected: number // grid units by the end of the month incl. extra units
  soFar: number // grid units so far incl. the extra units pro rata
  perDay: number
}

export function monthUse(byDate: Map<number, DayRec>, today: number, c: BillConfig, start = cycleStart(today, c.day)): MonthUse {
  const end = addDays(nextCycle(start), -1)
  const totalDays = daysBetween(start, end) + 1
  const last = Math.min(today, end)
  const elapsed = daysBetween(start, last) + 1
  let grid = 0
  let self = 0
  let covered = 0
  for (let k = start; k <= last; k = addDays(k, 1)) {
    const r = byDate.get(k)
    if (!r) continue
    covered++
    grid += r.grid / 1000
    self += Math.max(0, r.load - r.grid) / 1000
  }
  // today counts as part of a day; days the monitor missed are assumed to be like the average
  const now = new Date()
  const dayFrac = last === today ? Math.max(0.25, now.getHours() / 24 + now.getMinutes() / 1440) : 1
  const coveredDays = Math.max(dayFrac, covered - 1 + dayFrac)
  const perDay = covered ? grid / coveredDays : 0
  const extraPerDay = c.extra / totalDays
  return {
    start, end, totalDays, elapsed, covered, gridUnits: grid, selfUnits: self,
    soFar: grid + extraPerDay * (elapsed - 1 + dayFrac),
    projected: perDay * totalDays + c.extra,
    perDay: perDay + extraPerDay,
  }
}

/** Units of a past bill month: the bill you entered, else the monitor's data if it covered the whole month. */
export function unitsOf(ym: number, byDate: Map<number, DayRec>, c: BillConfig): { units: number; from: 'bill' | 'monitor' } | null {
  const b = c.hist.find((h) => h[0] === ym)
  if (b) return { units: b[1], from: 'bill' }
  // billing period that closes in month ym
  const start = prevCycle(Math.floor(ym) * 100 + c.day)
  const end = addDays(nextCycle(start), -1)
  let units = 0
  let days = 0
  for (let k = start; k <= end; k = addDays(k, 1)) {
    const r = byDate.get(k)
    if (r) { units += r.grid / 1000; days++ }
  }
  return days >= daysBetween(start, end) - 1 ? { units: units + c.extra, from: 'monitor' } : null
}

/** Protected status from the last 6 bills: true / false, or null when months are missing. */
export function protectedFromHistory(byDate: Map<number, DayRec>, today: number, c: BillConfig) {
  const limit = c.ps[c.ps.length - 1][0]
  const cur = billMonth(cycleStart(today, c.day))
  const months = [1, 2, 3, 4, 5, 6].map((k) => ({ ym: addMonths(cur, -k), u: unitsOf(addMonths(cur, -k), byDate, c) }))
  const over = months.find((m) => m.u && m.u.units > limit)
  return { months, over, verdict: over ? false : months.every((m) => m.u) ? true : null }
}

/** Everything the bill card shows, shared with the Live page. */
export function billNow(byDate: Map<number, DayRec>, today: number, cfg: BillConfig) {
  const m = monthUse(byDate, today, cfg)
  const ym = billMonth(m.start)
  // With under a week of monitor data a projection is guesswork: use the average of the last 3 bills instead.
  const recent = [1, 2, 3].map((k) => cfg.hist.find((h) => h[0] === addMonths(ym, -k))?.[1]).filter((u): u is number => u != null)
  const basis: 'monitor' | 'bills' = m.covered < 7 && recent.length ? 'bills' : 'monitor'
  if (basis === 'bills') {
    const avg = recent.reduce((a, b) => a + b, 0) / recent.length
    m.projected = Math.max(m.soFar, avg)
    m.soFar = Math.max(m.soFar, (avg * m.elapsed) / m.totalDays)
    m.perDay = avg / m.totalDays
  }
  const fpaUnits = unitsOf(addMonths(ym, -2), byDate, cfg)?.units
  const bill = computeBill(m.projected, cfg, fpaUnits)
  const withoutSolar = computeBill(m.projected + (m.selfUnits / Math.max(1, m.covered)) * m.totalDays, cfg, fpaUnits)
  return { m, ym, bill, fpaUnits, basis, recentAvg: recent.length ? Math.round(recent.reduce((a, b) => a + b, 0) / recent.length) : 0, // both need real measurements: a week of monitor data, not an estimate from past bills
    saved: m.covered >= 7 ? withoutSolar.total - bill.total : 0,
    alert: basis === 'monitor' && m.covered >= 3 ? unitAlert(m.soFar, m.projected, cfg) : null }
}

export function BillCard({ byDate, today }: { byDate: Map<number, DayRec>; today: number }) {
  const cfg = useStore((s) => s.bill) ?? DEFAULT_BILL
  const [open, setOpen] = useState(false)
  const { m, ym, bill, saved, alert, basis, recentAvg } = billNow(byDate, today, cfg)
  const limit = cfg.ps[cfg.ps.length - 1][0]
  const left = m.totalDays - m.elapsed
  const hist = protectedFromHistory(byDate, today, cfg)
  const statusMismatch = hist.verdict != null && hist.verdict !== (cfg.st === 'p')

  let advice: React.ReactNode = null
  if (alert && alert.level > 0) {
    advice = <Note tone={alert.level === 2 ? 'crit' : 'warn'} icon={alert.level === 2 ? <ShieldOff size={18} /> : <AlertTriangle size={18} />} title={alert.title}>{alert.text}</Note>
  } else if (cfg.st === 'p' && !bill.lostProtection) {
    advice = (
      <Note tone="good" icon={<ShieldCheck size={18} />} title="On track to stay protected">
        About {Math.round(limit - m.projected)} units to spare by the meter reading{left > 0 && m.soFar < limit ? `; up to ${((limit - m.soFar) / left).toFixed(1)} units a day is safe for the remaining ${left} days` : ''}.
      </Note>
    )
  }

  const scaleMax = Math.max(limit * 1.25, m.projected * 1.1, 50)
  const pct = (u: number) => `${Math.min(100, (u / scaleMax) * 100)}%`

  return (
    <Card>
      <CardHeader
        title="Electricity bill estimate"
        info={<BillInfo limit={limit} />}
        sub={`${monthName(ym, { month: 'long', year: 'numeric' })} bill · reading ${dayLabel(m.start, { day: 'numeric', month: 'short' })} – ${dayLabel(nextCycle(m.start), { day: 'numeric', month: 'short' })} · day ${m.elapsed} of ${m.totalDays}`}
        action={<span className={cn('rounded-full px-3 py-1 text-xs font-semibold', cfg.st === 'p' ? 'bg-good/15 text-good' : 'bg-warn/15 text-warn')}>{cfg.st === 'p' ? 'Protected' : 'Unprotected'}</span>}
      />
      <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
        <div>
          <p className="text-xs text-text-3">Expected bill</p>
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

      {/* units bar with the 100 / protected-limit marks */}
      <div className="mt-4">
        <div className="relative h-3 rounded-full bg-surface-3">
          <div className="absolute inset-y-0 left-0 rounded-full bg-grid/35" style={{ width: pct(m.projected) }} />
          <div className="absolute inset-y-0 left-0 rounded-full bg-grid" style={{ width: pct(m.soFar) }} />
          {[100, limit].map((u) => <span key={u} className="absolute -bottom-1 -top-1 w-0.5 rounded bg-text-3" style={{ left: pct(u) }} />)}
        </div>
        <div className="num relative mt-1 h-4 text-[11px] text-text-3">
          {[100, limit].map((u) => <span key={u} className="absolute -translate-x-1/2" style={{ left: pct(u) }}>{u}</span>)}
        </div>
        <p className="mt-1 text-xs text-text-3">dark = used so far · light = expected by the meter reading{basis === 'bills' ? ` · the monitor has only ${m.covered} day${m.covered === 1 ? '' : 's'} of this month, so this uses your last bills (about ${recentAvg} units)` : m.covered < m.elapsed ? ` · the monitor has ${m.covered} of ${m.elapsed} days, the rest are estimated` : ''}</p>
      </div>

      {advice && <div className="mt-3">{advice}</div>}
      {statusMismatch && (
        <div className="mt-3">
          <Note tone="warn" icon={<Info size={18} />} title="Check your status">
            Your last 6 bills say {hist.verdict ? 'protected' : 'unprotected'}, but the settings say {cfg.st === 'p' ? 'protected' : 'unprotected'}. Change it in System → Bill settings if the bill agrees.
          </Note>
        </div>
      )}

      <button onClick={() => setOpen(!open)} className="focus-ring mt-3 flex min-h-10 items-center gap-1 rounded-xl text-sm font-medium text-text-2 hover:text-text" aria-expanded={open}>
        <ChevronDown size={16} className={cn('transition-transform', open && 'rotate-180')} /> How it's worked out
      </button>
      {open && (
        <div className="mt-1 grid gap-3 text-sm">
          <table className="w-full">
            <tbody className="num [&_td]:py-1 [&_td:last-child]:whitespace-nowrap [&_td:last-child]:pl-3 [&_td:last-child]:text-right">
              <tr><td className="text-text-2">Energy: {bill.units} units{bill.tier === 'protected' && bill.slab > 0 ? ` (first ${cfg.ps[bill.slab - 1][0]} at Rs ${cfg.ps[bill.slab - 1][1]}, rest at Rs ${bill.rate})` : ` × Rs ${bill.rate}`}</td><td>{fmtPkr(bill.energy)}</td></tr>
              <tr><td className="text-text-2">Fixed charge ({cfg.kw} kW × Rs {(bill.tier === 'protected' ? cfg.ps : cfg.us)[bill.slab][2]})</td><td>{fmtPkr(bill.fixed)}</td></tr>
              {bill.fcs !== 0 && <tr><td className="text-text-2">F.C. surcharge ({bill.units} × Rs {cfg.fc})</td><td>{fmtPkr(bill.fcs)}</td></tr>}
              {bill.qta !== 0 && <tr><td className="text-text-2">Quarterly adjustment ({bill.units} × Rs {cfg.qta})</td><td>{fmtPkr(bill.qta)}</td></tr>}
              {bill.fpa !== 0 && <tr><td className="text-text-2">Fuel adjustment ({bill.fpaUnits} units of {monthName(addMonths(ym, -2))} × Rs {cfg.fpa})</td><td>{fmtPkr(bill.fpa)}</td></tr>}
              <tr><td className="text-text-2">Electricity duty {cfg.ed}%</td><td>{fmtPkr(bill.duty)}</td></tr>
              <tr><td className="text-text-2">Sales tax (GST) {cfg.gst}%</td><td>{fmtPkr(bill.gst)}</td></tr>
              {cfg.ptv > 0 && <tr><td className="text-text-2">PTV fee</td><td>{fmtPkr(cfg.ptv)}</td></tr>}
              <tr className="border-t border-border font-semibold"><td>Total</td><td>{fmtPkr(bill.total)}</td></tr>
            </tbody>
          </table>
          {cfg.fpa === 0 && <p className="text-xs text-text-3">Fuel adjustment (FPA) is not set: copy the Rs/unit from your latest bill into System → Bill settings for a closer estimate.</p>}
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
        Last 6 bills: {hist.verdict === true ? `all at or under ${limit} → protected` : hist.verdict === false ? `${monthName(hist.over!.ym)} was over ${limit} → unprotected` : 'some months missing, so the status comes from your setting'}
      </p>
      <div className="grid grid-cols-6 gap-1.5">
        {hist.months.slice().reverse().map((x) => (
          <div key={x.ym} className="text-center">
            <div className={cn('num rounded-lg py-1 font-semibold', !x.u ? 'text-text-3' : x.u.units > limit ? 'bg-crit/15 text-crit' : 'bg-good/15 text-good')}>{x.u ? Math.round(x.u.units) : '–'}</div>
            <div className="mt-0.5 text-text-3">{monthName(x.ym, { month: 'short' })}</div>
          </div>
        ))}
      </div>
      <p className="mt-2 text-text-3">From the bills you entered, or from the monitor for months it fully covered.</p>
    </div>
  )
}

function Note({ tone, icon, title, children }: { tone: 'good' | 'warn' | 'crit'; icon: React.ReactNode; title?: string; children: React.ReactNode }) {
  const c = { good: 'bg-good/10 text-good', warn: 'bg-warn/10 text-warn', crit: 'bg-crit/10 text-crit' }[tone]
  return (
    <div className={cn('flex gap-3 rounded-2xl p-3 text-sm leading-relaxed', c)}>
      <span className="mt-0.5 shrink-0">{icon}</span>
      <span className="text-text">{title && <b className="block font-semibold">{title}</b>}{children}</span>
    </div>
  )
}

function BillInfo({ limit }: { limit: number }) {
  return (
    <>
      <p><b>Protected</b> homes used {limit} units or less in each of the last 6 months. They pay the lowest rates. Going over {limit} even once bills that whole month at the unprotected rate and removes the status for the next 6 months.</p>
      <p><b>Unprotected</b> homes pay the rate of the slab they reach on every unit, so 201 units cost much more than 200.</p>
      <p><b>Grid units</b> are worked out by the monitor from the inverter. Your meter also counts anything not wired through this inverter; add that as "Other units" in System → Bill settings.</p>
      <p><b>Alerts</b> <BellRing size={14} className="inline" />: the phone app notifies you at 150, 175 and 190 units, and early if the month is heading over {limit}.</p>
      <p>Rates: NEPRA S.R.O. 279(I)/2026 (12 Feb 2026). The way each line is calculated was checked against a real IESCO bill.</p>
    </>
  )
}
