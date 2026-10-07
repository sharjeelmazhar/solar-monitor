import { describe, expect, it } from 'vitest'
import { parseDays, parseMinutes, parseSamples } from './binary'
import { activeWarnings, parseFlags, parseRated } from './decode'
import { addDays, fmtDuration, fmtUnits, fmtW, fmtWh, splitUnit, ymd, fromYmd } from './format'
import { findOutages, outageStats } from './outages'
import type { MinRec } from './types'

const MIN = 60_000
const T0 = new Date(2026, 9, 7, 23, 50).getTime() // 23:50 local, to cross midnight
const rec = (i: number, on: boolean, base = T0): MinRec => ({
  t: base + i * MIN, pv: 0, load: 500, grid: 0, batt: -500, battV: 26, pvV: 0, gridV: on ? 230 : 0, outV: 230,
  soc: 80, temp: 40, mode: on ? 'L' : 'B', flags: on ? 1 : 0,
})
const series = (pattern: string, base = T0) => [...pattern].map((c, i) => rec(i, c === '1', base))

describe('outages', () => {
  it('finds one outage with known start and end', () => {
    const e = findOutages(series('1110000111'))
    expect(e).toHaveLength(1)
    expect(e[0]).toMatchObject({ minutes: 4, startKnown: true, endKnown: true, ongoing: false })
    expect(e[0].start).toBe(T0 + 3 * MIN)
    expect(e[0].end).toBe(T0 + 7 * MIN)
  })

  it('handles an outage that spans midnight', () => {
    const e = findOutages(series('11' + '0'.repeat(20) + '1')) // 23:52 -> 00:12
    expect(e).toHaveLength(1)
    expect(new Date(e[0].start).getDate()).not.toBe(new Date(e[0].end).getDate())
    expect(e[0].minutes).toBe(20)
  })

  it('merges a one-minute grid flicker into the same outage', () => {
    expect(findOutages(series('1000100011'))).toHaveLength(1)
    expect(findOutages(series('10001100011'))).toHaveLength(2) // two minutes back is a real return
  })

  it('marks the start unknown when data begins during an outage', () => {
    const e = findOutages(series('00011'))
    expect(e[0].startKnown).toBe(false)
  })

  it('marks an ongoing outage', () => {
    const e = findOutages(series('11000'))
    expect(e[0]).toMatchObject({ ongoing: true, endKnown: false, minutes: 3 })
  })

  it('closes an outage at a data gap without inventing an end time', () => {
    const recs = [...series('1100'), ...series('0011', T0 + 60 * MIN)]
    const e = findOutages(recs)
    expect(e).toHaveLength(2)
    expect(e[0]).toMatchObject({ endKnown: false, minutes: 2 })
    expect(e[1]).toMatchObject({ startKnown: false, endKnown: true })
  })

  it('handles no data and no outages', () => {
    expect(findOutages([])).toEqual([])
    expect(findOutages(series('11111'))).toEqual([])
  })

  it('computes stats and the hour-of-day heat map', () => {
    const recs = series('11000011000011')
    const s = outageStats(recs, findOutages(recs))
    expect(s.count).toBe(2)
    expect(s.totalMin).toBe(8)
    expect(s.longestMin).toBe(4)
    expect(s.byHour.reduce((a, b) => a + b, 0)).toBe(8)
    expect(s.byHour[23] + s.byHour[0]).toBe(8)
  })
})

describe('format', () => {
  it('formats power, energy and units', () => {
    expect(fmtW(749)).toBe('749 W')
    expect(fmtW(-83)).toBe('-83 W')
    expect(fmtW(1100)).toBe('1.10 kW')
    expect(fmtWh(999)).toBe('999 Wh')
    expect(fmtWh(12300)).toBe('12.3 kWh')
    expect(fmtUnits(182400)).toBe('182')
    expect(fmtUnits(1234)).toBe('1.23')
    expect(splitUnit('1.10 kW')).toEqual(['1.10', 'kW'])
    expect(splitUnit('Off')).toEqual(['Off', ''])
  })
  it('formats durations', () => {
    expect(fmtDuration(0)).toBe('0m')
    expect(fmtDuration(61)).toBe('1h 01m')
  })
  it('does date maths across month and year ends', () => {
    expect(addDays(20261031, 1)).toBe(20261101)
    expect(addDays(20261231, 1)).toBe(20270101)
    expect(addDays(20280301, -1)).toBe(20280229)
    expect(ymd(fromYmd(20261007))).toBe(20261007)
  })
})

describe('decode', () => {
  it('parses inverter ratings', () => {
    const r = parseRated('230.0 13.9 230.0 50.0 13.9 3200 3200 24.0 25.5 22.1 27.8 27.5 02 010 050 0 1 1 1 01 0 0 26.5 0 1')!
    expect(r).toMatchObject({ outW: 3200, battV: 24, cutoff: 22.1, bulk: 27.8, float: 27.5, battType: 2, maxChg: 50, outPrio: 1, chgPrio: 1, redischarge: 26.5 })
    expect(parseRated('')).toBeNull()
  })
  it('decodes warnings and flags', () => {
    expect(activeWarnings('000001000100000000000000000000000000')).toEqual([9])
    expect(parseFlags('EakxyzDbdjuv')?.on).toContain('Buzzer')
    expect(parseFlags('nonsense')).toBeNull()
  })
})

describe('binary', () => {
  it('parses each record format and ignores truncated tails', () => {
    const m = new DataView(new ArrayBuffer(24 + 10))
    m.setUint32(0, 1791355680, true); m.setUint16(4, 700, true); m.setInt16(10, -55, true); m.setUint16(12, 2750, true)
    m.setUint8(20, 100); m.setInt8(21, -3); m.setUint8(22, 66); m.setUint8(23, 1)
    const mins = parseMinutes(m.buffer)
    expect(mins).toHaveLength(1)
    expect(mins[0]).toMatchObject({ t: 1791355680000, pv: 700, batt: -55, battV: 27.5, soc: 100, temp: -3, mode: 'B', flags: 1 })

    const d = new DataView(new ArrayBuffer(40))
    d.setUint32(0, 20261007, true); d.setFloat32(4, 1200, true); d.setUint8(35, 3)
    expect(parseDays(d.buffer)[0]).toMatchObject({ date: 20261007, pv: 1200, outages: 3 })

    const s = new DataView(new ArrayBuffer(32))
    s.setUint32(16, 1791355680, true); s.setUint16(20, 250, true); s.setInt16(28, -30, true)
    expect(parseSamples(s.buffer)).toEqual([{ t: 1791355680250, pv: 0, load: 0, grid: 0, batt: -30 }])
  })
})

describe('hourly mix', () => {
  it('splits home use into grid, battery and solar', async () => {
    const { hourlyMix } = await import('./outages')
    const base = new Date(2026, 9, 7, 14, 0).getTime()
    const rec = (k: number, o: Partial<MinRec>): MinRec => ({ t: base + k * 60_000, pv: 0, load: 600, grid: 0, batt: 0, battV: 26, pvV: 0, gridV: 0, outV: 230, soc: 80, temp: 30, mode: 'B', flags: 1, ...o })
    const h = hourlyMix([
      rec(0, { grid: 600 }), // all from grid
      rec(1, { batt: -600, flags: 0 }), // all from battery, grid off
      rec(2, { pv: 900, batt: 300 }), // solar runs the home and charges
      rec(3, { pv: 200, batt: -400 }), // solar 200 + battery 400
    ])[14]
    expect(h.minutes).toBe(4)
    expect(h.offMin).toBe(1)
    expect(h.grid).toBeCloseTo(10)
    expect(h.batt).toBeCloseTo(10 + 400 / 60)
    expect(h.solar).toBeCloseTo(10 + 200 / 60)
    expect(h.pv).toBeCloseTo(1100 / 60)
  })
})
