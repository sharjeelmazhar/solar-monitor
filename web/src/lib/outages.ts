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
