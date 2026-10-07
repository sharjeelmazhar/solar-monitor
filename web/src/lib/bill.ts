// IESCO residential (tariff A-1, sanctioned load under 5 kW) bill estimate.
// Rates: NEPRA S.R.O. 279(I)/2026, effective 12 Feb 2026. Rules:
//  - Protected = every one of the last 6 months at or under 200 units (S.R.O. 1165(I)/2022). Going over 200
//    once bills that month as unprotected and loses the status for the next 6 months.
//  - Protected homes get the benefit of one previous slab (150 units = 100 at the first rate + 50 at the second).
//  - Unprotected homes pay the rate of the slab they reach on every unit (201 units = 201 x the 201-300 rate).
//  - Fixed charges are Rs per kW of sanctioned load per month, by the slab reached.
// The Kotlin copy in android/.../data/Bill.kt must give the same results (shared/bill-vectors.json).

/** [up to units (0 = no limit), Rs per unit, fixed Rs per kW per month] */
export type Slab = [number, number, number]

export interface BillConfig {
  st: 'p' | 'u' // protected / unprotected
  kw: number // sanctioned load
  day: number // meter reading day of month (1-28); the billing month starts on this day
  ps: Slab[]
  us: Slab[]
  fpa: number // fuel price adjustment, Rs per unit (from your bill, can be negative)
  qta: number // quarterly tariff adjustment, Rs per unit
  gst: number // %
  ed: number // electricity duty, % of energy charges
  ptv: number // PTV fee, Rs per month
  extra: number // grid units per month the monitor can't see (other inverter, direct loads)
}

export const DEFAULT_BILL: BillConfig = {
  st: 'p',
  kw: 1,
  day: 1,
  ps: [[100, 10.54, 200], [200, 13.01, 300]],
  us: [[100, 22.44, 275], [200, 28.91, 300], [300, 33.1, 350], [400, 36.46, 400], [500, 38.95, 500], [600, 40.22, 675], [700, 41.85, 675], [0, 47.2, 675]],
  fpa: 0,
  qta: 0,
  gst: 18,
  ed: 1.5,
  ptv: 35,
  extra: 0,
}

const num = (v: unknown, d: number, lo: number, hi: number) => (typeof v === 'number' && Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : d)
const slabs = (v: unknown, d: Slab[]): Slab[] =>
  Array.isArray(v) && v.length > 0 && v.length <= 12 && v.every((s) => Array.isArray(s) && s.length === 3 && s.every((x) => typeof x === 'number' && Number.isFinite(x) && x >= 0))
    ? (v as Slab[])
    : d

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
    fpa: num(j.fpa, d.fpa, -100, 100),
    qta: num(j.qta, d.qta, -100, 100),
    gst: num(j.gst, d.gst, 0, 100),
    ed: num(j.ed, d.ed, 0, 100),
    ptv: num(j.ptv, d.ptv, 0, 10000),
    extra: num(j.extra, d.extra, 0, 100000),
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
  adjust: number // FPA + QTA
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

export function computeBill(unitsIn: number, c: BillConfig): Bill {
  const u = Math.max(0, Math.round(unitsIn)) // the meter bills whole units
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
  const adjust = u * (c.fpa + c.qta)
  const duty = (energy * c.ed) / 100
  const gst = (Math.max(0, energy + fixed + adjust) * c.gst) / 100
  const total = energy + fixed + adjust + duty + gst + c.ptv
  return {
    units: u, tier: prot ? 'protected' : 'unprotected', lostProtection: c.st === 'p' && !prot, slab: i, rate,
    energy: r2(energy), fixed: r2(fixed), adjust: r2(adjust), duty: r2(duty), gst: r2(gst), ptv: c.ptv, total: r2(total),
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
