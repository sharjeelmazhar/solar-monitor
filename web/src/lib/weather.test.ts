import { describe, expect, it } from 'vitest'
import { _resetPvLog, skyOf, solarBadge } from './weather'
import type { Live } from './types'

const live = (pvW: number, pvPeak: number, t: number) => ({ t, pvW, today: { pvPeak } }) as unknown as Live
const at = (h: number) => new Date(2026, 9, 8, h, 0).getTime()

describe('weather', () => {
  it('ignores a rain/thunder code when no rain is falling', () => {
    expect(skyOf(95, 48, 0)).toBe('partly')
    expect(skyOf(51, 20, 0)).toBe('clear')
    expect(skyOf(61, 90, 0)).toBe('cloudy')
    expect(skyOf(61, 90, 0.4)).toBe('rain')
    expect(skyOf(95, 40, 0.3)).not.toBe('rain')
  })

  it('maps WMO codes and cloud cover to a sky', () => {
    expect(skyOf(0, 5)).toBe('clear')
    expect(skyOf(2, 40)).toBe('partly')
    expect(skyOf(3, 100)).toBe('cloudy')
    expect(skyOf(1, 80)).toBe('cloudy')
    expect(skyOf(61, 90)).toBe('rain')
    expect(skyOf(95, 90)).toBe('rain')
  })
  it('shows a cloud from the panels when solar stays under 50 W with the sun up, even if the weather says clear', () => {
    const clear = { sky: 'clear' as const, cloud: 5, at: Date.now() }
    _resetPvLog()
    const t0 = new Date(2026, 9, 9, 16, 30).getTime() // Asr, about 70 min before sunset
    for (let i = 0; i < 60; i += 2) solarBadge(live(20 + (i % 3) * 10, 2700, t0 + i * 1000), clear)
    expect(solarBadge(live(22, 2700, t0 + 60_000), clear)).toBe('cloudy')
    _resetPvLog()
    expect(solarBadge(live(22, 2700, new Date(2026, 9, 9, 17, 20).getTime()), clear)).toBe(null) // 20 min to sunset: low is normal
    _resetPvLog()
    expect(solarBadge(live(22, 200, t0), clear)).toBe(null) // tiny system, no evidence
    _resetPvLog()
    const t1 = new Date(2026, 9, 9, 12, 0).getTime()
    for (let i = 0; i < 60; i += 2) solarBadge(live(900, 2700, t1 + i * 1000), clear)
    expect(solarBadge(live(10, 2700, t1 + 60_000), clear)).toBe(null) // one dip after a sunny minute
  })
  it('picks the solar badge', () => {
    _resetPvLog()
    expect(solarBadge(live(0, 1800, at(21)), null)).toBe('night')
    expect(solarBadge(live(900, 1800, at(12)), { sky: 'cloudy', cloud: 90, at: Date.now() })).toBe('cloudy')
    expect(solarBadge(live(900, 1800, at(12)), { sky: 'clear', cloud: 0, at: Date.now() })).toBe(null)
    expect(solarBadge(live(100, 1800, at(12)), null)).toBe('cloudy?')   // no internet: watts-only guess
    expect(solarBadge(live(1200, 1800, at(12)), null)).toBe(null)
  })
})
