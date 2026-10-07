import { describe, expect, it } from 'vitest'
import vectors from '../../../shared/bill-vectors.json'
import { computeBill, cycleStart, DEFAULT_BILL, nextCycle, parseBill, prevCycle, unitsToNextStep } from './bill'

describe('bill estimate (shared vectors)', () => {
  for (const v of vectors.cases) {
    it(v.name, () => {
      const b = computeBill(v.units, parseBill({ ...DEFAULT_BILL, ...v.cfg }))
      expect(b.tier).toBe(v.tier)
      expect(b.lostProtection).toBe(!!(v as { lost?: boolean }).lost)
      expect(b.energy).toBeCloseTo(v.energy, 1)
      expect(b.fixed).toBeCloseTo(v.fixed, 1)
      expect(Math.abs(b.total - v.total)).toBeLessThan(0.02)
    })
  }
})

describe('bill helpers', () => {
  it('falls back to defaults for bad settings', () => {
    expect(parseBill(null)).toEqual(DEFAULT_BILL)
    expect(parseBill({ kw: 'x', ps: [[1, 2]], gst: 500 }).gst).toBe(100)
    expect(parseBill({ kw: 'x', ps: [[1, 2]] }).ps).toEqual(DEFAULT_BILL.ps)
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
  })
})
