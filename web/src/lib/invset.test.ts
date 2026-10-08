import { describe, expect, it } from 'vitest'
import { parseRated } from './decode'
import { INV_SETTINGS, choicesOf, currentOf, labelOf, parseEq } from './invset'

const QPIRI = '230.0 13.9 230.0 50.0 13.9 3200 3200 24.0 24.0 22.1 27.2 26.8 02 010 050 1 1 1 1 01 0 0 26.5 0 1'
const BEQI = '0 060 030 050 030 29.20 000 120 0 0000'   // real QBEQI from the Inverex Veyron
const r = parseRated(QPIRI)!
const def = (key: string, letter?: string) => INV_SETTINGS.find((d) => d.key === key && d.letter === letter)!

describe('inverter settings', () => {
  it('reads equalization from QBEQI', () => {
    expect(parseEq(BEQI)?.slice(0, 9)).toEqual([0, 60, 30, 50, 30, 29.2, 0, 120, 0])
    expect(parseEq('')).toBeNull()
    expect(currentOf(def('eqTime'), r, '', BEQI)).toBe(60)
    expect(currentOf(def('eqVolt'), r, '', BEQI)).toBe(29.2)
    expect(currentOf(def('eqTime'), r, '', '')).toBeNull()
  })
  it('offers the same ranges as the firmware', () => {
    const t = choicesOf(def('eqTime'), r, '', '')!
    expect([t[0].value, t[t.length - 1].value, t.length]).toEqual([5, 900, 180])
    const v = choicesOf(def('eqVolt'), r, '', '')!
    expect([v[0].value, v[v.length - 1].value]).toEqual([24, 30.5])
    expect(choicesOf(def('eqPeriod'), r, '', '')!.map((c) => c.value)).toContain(0)
    expect(choicesOf(def('battType'), r, '', '')!.map((c) => c.value)).toEqual([0, 1, 2, 3])
    expect(choicesOf(def('outV'), r, '', '')!.map((c) => c.value)).toEqual([220, 230, 240])
  })
  it('shows current output and battery type', () => {
    expect(labelOf(def('outV'), currentOf(def('outV'), r, ''), r, '', '')).toBe('230 V')
    expect(labelOf(def('eqPeriod'), 30, r, '', '')).toBe('30 days')
    expect(currentOf(def('flag', 'd'), r, 'EakxyzDbdjuv')).toBe(0)
  })
})
