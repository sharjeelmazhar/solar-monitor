import { describe, expect, it } from 'vitest'
import { formError, parseScan, signalOf } from './wifi'

describe('wifi', () => {
  it('waits while scanning', () => expect(parseScan({ scanning: true })).toBeNull())
  it('lists strongest first, no repeats, no hidden names', () => {
    const n = parseScan({ nets: [{ ssid: 'A', rssi: -80, open: false }, { ssid: 'B', rssi: -50, open: true }, { ssid: 'A', rssi: -60, open: false }, { ssid: '', rssi: -40, open: false }] })!
    expect(n.map((x) => x.ssid)).toEqual(['B', 'A'])
    expect(n[1].rssi).toBe(-60)
    expect(signalOf(n[0].rssi)).toBe('strong')
    expect(signalOf(n[1].rssi)).toBe('good')
  })
  it('checks the form', () => {
    expect(formError('', 'password1', false, 'x')).toBeTruthy()
    expect(formError('N', 'short', false, 'x')).toBeTruthy()
    expect(formError('N', 'password1', false, '')).toBeTruthy()
    expect(formError('N'.repeat(33), 'password1', false, 'x')).toBeTruthy()
    expect(formError('N', 'p'.repeat(64), false, 'x')).toBeTruthy()
    expect(formError('N', 'password1', false, 'x')).toBeNull()
    expect(formError('N', '', true, 'x')).toBeNull()
    expect(formError('N', '', false, 'x')).toBeNull()
  })
})
