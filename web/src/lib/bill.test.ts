import { describe, expect, it } from 'vitest'
import vectors from '../../../shared/bill-vectors.json'
import { addMonths, billMonth, computeBill, cycleStart, DEFAULT_BILL, nextCycle, parseBill, prevCycle, unitsToNextStep } from './bill'

describe('bill estimate (shared vectors)', () => {
  for (const v of vectors.cases) {
    it(v.name, () => {
      const x = v as { fpaUnits?: number; paper?: number; lost?: boolean }
      const b = computeBill(v.units, parseBill({ ...DEFAULT_BILL, ...v.cfg }), x.fpaUnits)
      expect(b.tier).toBe(v.tier)
      expect(b.lostProtection).toBe(!!x.lost)
      expect(b.energy).toBeCloseTo(v.energy, 1)
      expect(b.fixed).toBeCloseTo(v.fixed, 1)
      expect(Math.abs(b.total - v.total)).toBeLessThan(0.02)
      if (x.paper) expect(Math.abs(b.total - x.paper)).toBeLessThan(2) // IESCO rounds each line
    })
  }
})

describe('bill helpers', () => {
  it('falls back to defaults for bad settings', () => {
    expect(parseBill(null)).toEqual(DEFAULT_BILL)
    expect(parseBill({ kw: 'x', ps: [[1, 2]], gst: 500 }).gst).toBe(100)
    expect(parseBill({ kw: 'x', ps: [[1, 2]] }).ps).toEqual(DEFAULT_BILL.ps)
    expect(parseBill({ hist: [[202602, 71, 1013], [202513, 1, 1], [202601, 113, 1567], [202602, 70, 1]] }).hist).toEqual([[202601, 113, 1567], [202602, 70, 1]])
  })
  it('units to the next price step', () => {
    expect(unitsToNextStep(150, DEFAULT_BILL)).toEqual({ left: 50, at: 200 })
    expect(unitsToNextStep(250, { ...DEFAULT_BILL, st: 'u' })).toEqual({ left: 50, at: 300 })
    expect(unitsToNextStep(900, { ...DEFAULT_BILL, st: 'u' })).toBeNull()
  })
  it('billing months follow the meter reading day', () => {
    expect(cycleStart(20261007, 1)).toBe(20261001)
    expect(cycleStart(20261007, 15)).toBe(20260915)
    expect(cycleStart(20260105, 10)).toBe(20251210)
    expect(nextCycle(20251210)).toBe(20260110)
    expect(prevCycle(20260110)).toBe(20251210)
    expect(billMonth(20260108)).toBe(202602) // read on 8 Feb = the Feb bill
    expect(billMonth(20261001)).toBe(202611)
    expect(addMonths(202602, -2)).toBe(202512)
    expect(addMonths(202511, 3)).toBe(202602)
  })
})

describe('unit alerts', () => {
  it('steps up as the month gets close to 200', async () => {
    const { unitAlert } = await import('./bill')
    expect(unitAlert(40, 90, DEFAULT_BILL)).toBeNull()
    expect(unitAlert(100, 150, DEFAULT_BILL)).toBeNull()
    expect(unitAlert(150, 180, DEFAULT_BILL)?.key).toBe(150)
    expect(unitAlert(120, 260, DEFAULT_BILL)?.key).toBe(-1)
    expect(unitAlert(176, 190, DEFAULT_BILL)?.key).toBe(175)
    expect(unitAlert(185, 190, DEFAULT_BILL)?.key).toBe(175)
    expect(unitAlert(191, 199, DEFAULT_BILL)).toMatchObject({ key: 190, level: 2 })
    expect(unitAlert(199, 199, DEFAULT_BILL)?.key).toBe(190)
    expect(unitAlert(201, 230, DEFAULT_BILL)?.key).toBe(200)
  })
})

describe('bill insights', () => {
  it('summarises a year of bills', async () => {
    const { billInsights } = await import('./bill')
    const h: [number, number, number][] = [[202509, 183, 2235], [202510, 173, 2097], [202511, 119, 1427], [202512, 152, 2164], [202601, 113, 1567], [202602, 71, 1013],
      [202603, 74, 1425], [202604, 95, 1644], [202605, 99, 1578], [202606, 170, 2894], [202607, 181, 2579], [202608, 180, 2678], [202609, 166, 2894]]
    const t = billInsights(h).map((x) => x.text).join(' | ')
    expect(t).toContain('stayed at or under 200')
    expect(t).toContain('close calls')
    expect(t).toContain('Sep 26: 166 units vs 183 a year ago (-17, -9%)')
    expect(t).toContain('Highest: Jul 26 with 181 units')
  })
})
