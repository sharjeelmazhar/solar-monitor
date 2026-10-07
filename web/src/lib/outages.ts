import type { MinRec } from './types'

export interface Outage {
  start: number // epoch ms of the first minute without grid
  end: number // epoch ms when grid was seen again (or last data point if unknown)
  minutes: number
  ongoing: boolean // still off at the latest data point
  startKnown: boolean // false when the data begins while the grid was already off
  endKnown: boolean // false when the data stops (monitor offline) before the grid came back
}

const MIN = 60_000
const gridOn = (r: MinRec) => (r.flags & 1) === 1

/**
 * Turns per-minute grid flags into outage events.
 * - Records may span several days (midnight is not a boundary).
 * - A gap in the data longer than `maxGapMin` closes the event with endKnown = false.
 * - Grid returning for at most `flickerMin` minutes inside an outage is treated as a flicker and merged.
 */
export function findOutages(recs: MinRec[], opts: { maxGapMin?: number; flickerMin?: number; now?: number } = {}): Outage[] {
  const maxGap = (opts.maxGapMin ?? 3) * MIN
  const flicker = (opts.flickerMin ?? 1) * MIN
  const rs = [...recs].sort((a, b) => a.t - b.t)
  const out: Outage[] = []
  let cur: Outage | null = null
  let prevT = -Infinity

  for (const r of rs) {
    const gap = r.t - prevT > maxGap
    if (cur && gap) {
      // data stopped while the grid was off: we don't know when it came back
      cur.endKnown = false
      cur.end = prevT + MIN
      out.push(cur)
      cur = null
    }
    if (!gridOn(r)) {
      if (!cur) {
        const last = out[out.length - 1]
        if (last && last.endKnown && !gap && r.t - last.end <= flicker) {
          cur = out.pop()! // short flicker of grid: continue the previous outage
        } else {
          cur = { start: r.t, end: r.t + MIN, minutes: 0, ongoing: false, startKnown: !gap && prevT !== -Infinity, endKnown: true }
        }
      }
      cur.end = r.t + MIN
    } else if (cur) {
      cur.end = r.t
      out.push(cur)
      cur = null
    }
    prevT = r.t
  }
  if (cur) {
    cur.ongoing = true
    cur.endKnown = false
    out.push(cur)
  }
  for (const o of out) o.minutes = Math.round((o.end - o.start) / MIN)
  return out
}

export interface OutageStats {
  count: number
  totalMin: number
  longestMin: number
  averageMin: number
  byHour: number[] // minutes without grid per hour of day (0-23), across all records
  monitoredMin: number
}

export function outageStats(recs: MinRec[], events: Outage[]): OutageStats {
  const byHour = new Array(24).fill(0)
  for (const r of recs) if (!gridOn(r)) byHour[new Date(r.t).getHours()]++
  const totalMin = events.reduce((a, e) => a + e.minutes, 0)
  return {
    count: events.length,
    totalMin,
    longestMin: events.reduce((a, e) => Math.max(a, e.minutes), 0),
    averageMin: events.length ? totalMin / events.length : 0,
    byHour,
    monitoredMin: recs.length,
  }
}

export interface HourMix {
  hour: number
  minutes: number // minutes with data
  offMin: number // minutes without grid
  solar: number // Wh of home use covered by solar
  batt: number // Wh covered by the battery
  grid: number // Wh covered by the grid
  pv: number // Wh produced by solar (incl. charging the battery)
}

/**
 * What powered the home in each hour of the day. Per minute: grid share first (the inverter passes the grid
 * through), then battery discharge, and solar covers the rest.
 */
export function hourlyMix(recs: MinRec[]): HourMix[] {
  const h: HourMix[] = Array.from({ length: 24 }, (_, hour) => ({ hour, minutes: 0, offMin: 0, solar: 0, batt: 0, grid: 0, pv: 0 }))
  for (const r of recs) {
    const x = h[new Date(r.t).getHours()]
    x.minutes++
    if (!gridOn(r)) x.offMin++
    const g = Math.min(r.load, Math.max(0, r.grid))
    const b = Math.min(r.load - g, Math.max(0, -r.batt))
    x.grid += g / 60
    x.batt += b / 60
    x.solar += Math.max(0, r.load - g - b) / 60
    x.pv += r.pv / 60
  }
  return h
}

/** Energy used and battery change during an outage, from the minute records it covers. */
export function duringOutage(recs: MinRec[], o: Outage) {
  const rs = recs.filter((r) => r.t >= o.start && r.t < o.end)
  if (!rs.length) return null
  return {
    homeWh: rs.reduce((a, r) => a + r.load, 0) / 60,
    solarWh: rs.reduce((a, r) => a + r.pv, 0) / 60,
    socFrom: rs[0].soc,
    socTo: rs[rs.length - 1].soc,
  }
}
