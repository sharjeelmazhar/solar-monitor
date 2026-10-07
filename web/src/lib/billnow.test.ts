import { describe, expect, it } from 'vitest'
import { billNow } from '../components/BillCard'
import { DEFAULT_BILL, type BillConfig } from './bill'
import type { DayRec } from './types'

const day = (date: number, gridWh: number): DayRec => ({
  date, pv: 0, load: gridWh, grid: gridWh, chg: 0, dis: 0, pvPeak: 0, loadPeak: 0, gridOnMin: 0, onlineMin: 0, battMin: 0, battMax: 0, tempMax: 0, outages: 0,
})
const cfg: BillConfig = { ...DEFAULT_BILL, day: 8, hist: [[202608, 180, 0], [202609, 180, 0], [202610, 180, 0]] }

describe('bill month from the meter reading day', () => {
  it('counts real units from the reading day when the monitor has every day', () => {
    const by = new Map([[20261008, day(20261008, 3000)], [20261009, day(20261009, 6000)]])
    const n = billNow(by, 20261009, cfg)
    expect(n.basis).toBe('measured')
    expect(n.m.start).toBe(20261008)
    expect(n.m.soFar).toBeCloseTo(9, 5)
    expect(n.m.projected).toBeGreaterThan(9)
    expect(n.m.projected).toBeLessThan(250)
  })
  it('falls back to past bills when the start of the month is missing', () => {
    const by = new Map([[20261007, day(20261007, 3000)]])
    const n = billNow(by, 20261007, cfg)
    expect(n.basis).toBe('bills')
    expect(n.m.start).toBe(20260908)
  })
})

describe('bill month from the monitor counter (reading day 8 at 8 PM)', () => {
  const c8: BillConfig = { ...cfg, hr: 20 }
  const at = (d: number, h: number, m = 0) => new Date(2026, 9, d, h, m).getTime()
  const cyc = (sMs: number, gWh: number, minutes: number) => ({ s: sMs / 1000, f: sMs / 1000, g: gWh, l: gWh, p: 0, m: minutes, ps: 0, pg: 0, pm: 0 })
  it('the month starts at 8 PM on the 8th, not at midnight', () => {
    const n = billNow(new Map(), 20261008, c8, { now: at(8, 19), cyc: cyc(new Date(2026, 8, 8, 20).getTime(), 170000, 29 * 1440) })
    expect(n.m.start).toBe(20260908)
    const after = billNow(new Map(), 20261008, c8, { now: at(8, 21), cyc: cyc(at(8, 20), 500, 60) })
    expect(after.m.start).toBe(20261008)
    expect(after.counter).toBe(true)
    expect(after.basis).toBe('measured')
    expect(after.m.soFar).toBeCloseTo(0.5, 5)
  })
  it('fills in time the monitor was off and projects the month', () => {
    // 2 days into the month, counted only 1 day of it
    const n = billNow(new Map(), 20261010, c8, { now: at(10, 20), cyc: cyc(at(8, 20), 6000, 1440) })
    expect(n.basis).toBe('monitor')
    expect(n.missingDays).toBeCloseTo(1, 5)
    expect(n.m.soFar).toBeGreaterThan(6)
    expect(n.m.projected).toBeGreaterThan(n.m.soFar)
  })
  it('ignores a counter from another month', () => {
    const n = billNow(new Map(), 20261010, c8, { now: at(10, 20), cyc: cyc(at(1, 20), 6000, 1440) })
    expect(n.counter).toBe(false)
  })
})
