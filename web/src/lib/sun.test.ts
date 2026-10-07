import { describe, expect, it } from 'vitest'
import { sunTimes } from './sun'

// Islamabad, PKT (UTC+5): 7 Oct 2026 06:06 / 17:45 (solar noon 11:56), 21 Jun about 05:00 / 19:21 (published)
const pkt = (ms: number) => { const d = new Date(ms + 5 * 3600000); return d.getUTCHours() * 60 + d.getUTCMinutes() }

describe('sunTimes', () => {
  it('October', () => {
    const s = sunTimes(new Date(2026, 9, 7))
    expect(Math.abs(pkt(s.rise) - (6 * 60 + 6))).toBeLessThanOrEqual(4)
    expect(Math.abs(pkt(s.set) - (17 * 60 + 45))).toBeLessThanOrEqual(4)
  })
  it('June', () => {
    const s = sunTimes(new Date(2026, 5, 21))
    expect(Math.abs(pkt(s.rise) - (5 * 60 + 0))).toBeLessThanOrEqual(4)
    expect(Math.abs(pkt(s.set) - (19 * 60 + 21))).toBeLessThanOrEqual(4)
  })
})
