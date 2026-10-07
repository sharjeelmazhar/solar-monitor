// IESCO residential (tariff A-1, sanctioned load under 5 kW) bill estimate.
// Rates: NEPRA S.R.O. 279(I)/2026, effective 12 Feb 2026. Rules:
//  - Protected = every one of the last 6 months at or under 200 units (S.R.O. 1165(I)/2022). Going over 200
//    once bills that month as unprotected and loses the status for the next 6 months.
//  - Protected homes get the benefit of one previous slab (150 units = 100 at the first rate + 50 at the second).
//  - Unprotected homes pay the rate of the slab they reach on every unit (201 units = 201 x the 201-300 rate).
//  - Fixed charges are Rs per kW of sanctioned load per month, by the slab reached (from 12 Feb 2026).
// How the other lines are worked out was checked against real IESCO bills (Feb 2026 and Sep 2026):
//  - F.C. surcharge and quarterly adjustment are Rs per unit of this month.
//  - FPA is Rs per unit of the month two bills back (the bill says "FPA on 152 units of Dec-25").
//  - Electricity duty = % of (energy + QTA + FPA), not the fixed charge; GST = % of everything incl. the duty.
// The Kotlin copy in android/.../data/Bill.kt must give the same results (shared/bill-vectors.json).

/** [up to units (0 = no limit), Rs per unit, fixed Rs per kW per month] */
export type Slab = [number, number, number]
/** A real bill: [bill month YYYYMM, units, amount Rs] */
export type PastBill = [number, number, number]

export interface BillConfig {
  st: 'p' | 'u' // protected / unprotected
  kw: number // sanctioned load
  day: number // meter reading day of month (1-28); the billing month starts on this day
  ps: Slab[]
  us: Slab[]
  fc: number // financing cost surcharge, Rs per unit
  fpa: number // fuel price adjustment, Rs per unit (from your bill, can be negative)
  qta: number // quarterly tariff adjustment, Rs per unit
  gst: number // %
  ed: number // electricity duty, %
  ptv: number // PTV fee, Rs per month (not on IESCO bills in 2026)
  extra: number // grid units per month the monitor can't see (other inverter, direct loads)
  hist: PastBill[] // bills entered by the user, newest last
}

export const DEFAULT_BILL: BillConfig = {
  st: 'p',
  kw: 1,
  day: 1,
  ps: [[100, 10.54, 200], [200, 13.01, 300]],
  us: [[100, 22.44, 275], [200, 28.91, 300], [300, 33.1, 350], [400, 36.46, 400], [500, 38.95, 500], [600, 40.22, 675], [700, 41.85, 675], [0, 47.2, 675]],
  fc: 0.43,
  fpa: 0,
  qta: 0,
  gst: 18,
  ed: 1.5,
  ptv: 0,
  extra: 0,
  hist: [],
}

const num = (v: unknown, d: number, lo: number, hi: number) => (typeof v === 'number' && Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : d)
const nums = (s: unknown, n: number) => Array.isArray(s) && s.length === n && s.every((x) => typeof x === 'number' && Number.isFinite(x) && x >= 0)
const slabs = (v: unknown, d: Slab[]): Slab[] => (Array.isArray(v) && v.length > 0 && v.length <= 12 && v.every((s) => nums(s, 3)) ? (v as Slab[]) : d)
const validMonth = (m: number) => m >= 200001 && m <= 210012 && m % 100 >= 1 && m % 100 <= 12
const hist = (v: unknown): PastBill[] =>
  Array.isArray(v)
    ? (v.filter((s) => nums(s, 3) && validMonth((s as number[])[0])) as PastBill[])
        .sort((a, b) => a[0] - b[0])
        .filter((s, i, a) => i === a.length - 1 || a[i + 1][0] !== s[0]) // one bill per month
        .slice(-24)
    : []

/** Reads settings saved by any app version; anything missing or invalid falls back to the default. */
export function parseBill(o: unknown): BillConfig {
  const j = (o && typeof o === 'object' ? o : {}) as Record<string, unknown>
  const d = DEFAULT_BILL
  return {
    st: j.st === 'u' ? 'u' : 'p',
    kw: num(j.kw, d.kw, 0, 100),
    day: Math.round(num(j.day, d.day, 1, 28)),
    ps: slabs(j.ps, d.ps),
    us: slabs(j.us, d.us),
    fc: num(j.fc, d.fc, 0, 100),
    fpa: num(j.fpa, d.fpa, -100, 100),
    qta: num(j.qta, d.qta, -100, 100),
    gst: num(j.gst, d.gst, 0, 100),
    ed: num(j.ed, d.ed, 0, 100),
    ptv: num(j.ptv, d.ptv, 0, 10000),
    extra: num(j.extra, d.extra, 0, 100000),
    hist: hist(j.hist),
  }
}

export interface Bill {
  units: number
  tier: 'protected' | 'unprotected'
  lostProtection: boolean // protected home went over the protected limit this month
  slab: number // index of the slab reached
  rate: number // Rs per unit of the slab reached
  energy: number
  fixed: number
  fcs: number // F.C. surcharge
  qta: number
  fpa: number
  fpaUnits: number
  duty: number
  gst: number
  ptv: number
  total: number
}

/** Index of the slab that `u` units fall in. */
function slabOf(list: Slab[], u: number) {
  const i = list.findIndex((s) => s[0] === 0 || u <= s[0])
  return i < 0 ? list.length - 1 : i
}

const r2 = (x: number) => Math.round(x * 100) / 100

/** fpaUnits: units of the month two bills back (FPA is charged on those); defaults to this month's units. */
export function computeBill(unitsIn: number, c: BillConfig, fpaUnits?: number): Bill {
  const u = Math.max(0, Math.round(unitsIn)) // the meter bills whole units
  const fu = Math.max(0, Math.round(fpaUnits ?? u))
  const limit = c.ps[c.ps.length - 1][0] // 200
  const prot = c.st === 'p' && u <= limit
  const list = prot ? c.ps : c.us
  const i = slabOf(list, u)
  const [, rate, fixedPerKw] = list[i]
  let energy: number
  if (prot && i > 0) {
    const below = list[i - 1][0]
    energy = below * list[i - 1][1] + (u - below) * rate
  } else {
    energy = u * rate
  }
  const fixed = fixedPerKw * c.kw
  const fcs = u * c.fc
  const qta = u * c.qta
  const fpa = fu * c.fpa
  const duty = (Math.max(0, energy + qta + fpa) * c.ed) / 100 // not on the fixed charge
  const gst = (Math.max(0, energy + fixed + fcs + qta + fpa + duty) * c.gst) / 100
  const total = energy + fixed + fcs + qta + fpa + duty + gst + c.ptv
  return {
    units: u, tier: prot ? 'protected' : 'unprotected', lostProtection: c.st === 'p' && !prot, slab: i, rate,
    energy: r2(energy), fixed: r2(fixed), fcs: r2(fcs), qta: r2(qta), fpa: r2(fpa), fpaUnits: fu, duty: r2(duty), gst: r2(gst), ptv: c.ptv, total: r2(total),
  }
}

/** Units left before the next price step (protected limit or next slab); null above the last slab. */
export function unitsToNextStep(units: number, c: BillConfig): { left: number; at: number } | null {
  const u = Math.max(0, Math.round(units))
  const list = c.st === 'p' && u <= c.ps[c.ps.length - 1][0] ? c.ps : c.us
  const s = list[slabOf(list, u)]
  return s[0] === 0 ? null : { left: s[0] - u, at: s[0] }
}

// ---- billing months ----

/** Start date (YYYYMMDD) of the billing month that contains `date`, for a reading day of `day`. */
export function cycleStart(date: number, day: number): number {
  let y = Math.floor(date / 10000)
  let m = Math.floor(date / 100) % 100
  if (date % 100 < day) {
    m -= 1
    if (m === 0) { m = 12; y -= 1 }
  }
  return y * 10000 + m * 100 + day
}

/** Start of the billing month after the one starting at `start`. */
export function nextCycle(start: number): number {
  let y = Math.floor(start / 10000)
  let m = Math.floor(start / 100) % 100 + 1
  if (m === 13) { m = 1; y += 1 }
  return y * 10000 + m * 100 + (start % 100)
}

export function prevCycle(start: number): number {
  let y = Math.floor(start / 10000)
  let m = Math.floor(start / 100) % 100 - 1
  if (m === 0) { m = 12; y -= 1 }
  return y * 10000 + m * 100 + (start % 100)
}

/** The bill month (YYYYMM) a billing period belongs to: the month of the reading that closes it. */
export const billMonth = (start: number) => Math.floor(nextCycle(start) / 100)

/** YYYYMM shifted by k months. */
export function addMonths(ym: number, k: number) {
  const t = Math.floor(ym / 100) * 12 + (ym % 100) - 1 + k
  return Math.floor(t / 12) * 100 + (t % 12) + 1
}

// ---- unit alerts (same steps as the Android notifications) ----

export interface UnitAlert {
  key: number // the step reached (150, 175, 190, 200, or -1 for "heading over"); one notification per step per month
  level: 0 | 1 | 2 // info, warning, urgent
  title: string
  text: string
}

/** The most important note about this month's units, or null. soFar / projected in grid units. */
export function unitAlert(soFar: number, projected: number, c: BillConfig): UnitAlert | null {
  const u = Math.floor(soFar)
  const p = Math.round(projected)
  if (c.st === 'p') {
    const L = c.ps[c.ps.length - 1][0]
    const left = L - u
    if (u > L) return { key: L, level: 2, title: `Over ${L} units this month`, text: `${u} units used. This month will be billed at the unprotected rate and protected status is lost for the next 6 months.` }
    if (left <= 10) return { key: L - 10, level: 2, title: `${u} units used: only ${left} left before ${L}`, text: `If you use more than ${L} units this month, the whole month is billed at the unprotected rate and you lose protected status for 6 months. Please keep grid use to a minimum until the meter reading.` }
    if (u >= L - 25) return { key: L - 25, level: 1, title: `High use: ${u} units this month`, text: `${left} units left before the protected limit of ${L}. Expected by month end: about ${p}.` }
    if (p > L) return { key: -1, level: 1, title: `Heading over ${L} units`, text: `At this pace the month ends near ${p} units. To stay protected, use less grid power for the rest of the month.` }
    if (u >= L - 50) return { key: L - 50, level: 0, title: `${u} units used this month`, text: `${left} units left before ${L}. Expected by month end: about ${p}.` }
    return null
  }
  const next = unitsToNextStep(u, c)
  if (next && next.left <= 10) return { key: next.at - 10, level: 1, title: `${next.left} units before the next slab`, text: `Above ${next.at} units every unit this month is charged at the higher rate.` }
  return null
}

// ---- insights from the bills the user entered ----

export interface BillInsight { tone: 'good' | 'warn' | 'crit' | 'info'; text: string }

const MONTH = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const mName = (ym: number) => `${MONTH[(ym % 100) - 1]} ${String(Math.floor(ym / 100)).slice(2)}`

/** Plain-language findings from past bills (oldest first). Same wording as the Android app. */
export function billInsights(hist: PastBill[], limit = 200): BillInsight[] {
  if (hist.length < 2) return []
  const out: BillInsight[] = []
  const last12 = hist.slice(-12)
  const avgU = last12.reduce((a, b) => a + b[1], 0) / last12.length
  const paid = last12.filter((b) => b[2] > 0)
  const avgRs = paid.length ? paid.reduce((a, b) => a + b[2], 0) / paid.length : 0
  out.push({ tone: 'info', text: `Last ${last12.length} bills: about ${Math.round(avgU)} units and Rs ${Math.round(avgRs).toLocaleString('en-PK')} a month on average.` })
  const over = last12.filter((b) => b[1] > limit)
  const close = last12.filter((b) => b[1] >= limit - 25 && b[1] <= limit)
  if (over.length) out.push({ tone: 'crit', text: `${over.length} month${over.length > 1 ? 's' : ''} went over ${limit} units (${over.map((b) => mName(b[0])).join(', ')}).` })
  else out.push({ tone: 'good', text: `Every month stayed at or under ${limit} units, so you keep the protected rates.` })
  if (close.length) out.push({ tone: 'warn', text: `${close.length} close call${close.length > 1 ? 's' : ''} at ${limit - 25}+ units: ${close.map((b) => `${mName(b[0])} (${b[1]})`).join(', ')}. Summer months need the most care.` })
  const top = last12.reduce((a, b) => (b[1] > a[1] ? b : a))
  out.push({ tone: 'info', text: `Highest: ${mName(top[0])} with ${top[1]} units${top[2] ? ` (Rs ${top[2].toLocaleString('en-PK')})` : ''}.` })
  const latest = hist[hist.length - 1]
  const yearAgo = hist.find((b) => b[0] === addMonths(latest[0], -12))
  if (yearAgo) {
    const du = latest[1] - yearAgo[1]
    const dr = latest[2] && yearAgo[2] ? latest[2] - yearAgo[2] : 0
    out.push({ tone: du <= 0 ? 'good' : 'warn', text: `${mName(latest[0])}: ${latest[1]} units vs ${yearAgo[1]} a year ago (${du <= 0 ? '' : '+'}${du}${yearAgo[1] ? `, ${du <= 0 ? '' : '+'}${Math.round((du / yearAgo[1]) * 100)}%` : ''})${dr ? `, but the bill was ${dr > 0 ? 'Rs ' + dr.toLocaleString('en-PK') + ' higher' : 'Rs ' + (-dr).toLocaleString('en-PK') + ' lower'}` : ''}.` })
  }
  const rate = (bs: PastBill[]) => { const p = bs.filter((b) => b[1] > 0 && b[2] > 0); return p.length ? p.reduce((a, b) => a + b[2], 0) / p.reduce((a, b) => a + b[1], 0) : 0 }
  const rNow = rate(hist.slice(-3))
  const rThen = rate(hist.filter((b) => b[0] <= addMonths(latest[0], -10) && b[0] >= addMonths(latest[0], -14)))
  if (rNow && rThen) out.push({ tone: rNow > rThen * 1.1 ? 'warn' : 'info', text: `Each unit now costs about Rs ${rNow.toFixed(1)} all-in, against Rs ${rThen.toFixed(1)} a year ago${rNow > rThen * 1.1 ? ' (fixed charges since Feb 2026 and fuel adjustments)' : ''}.` })
  return out
}
