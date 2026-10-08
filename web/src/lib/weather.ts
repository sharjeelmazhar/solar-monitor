// Sky over the house, so the dashboard can say "cloudy" when solar is low in daytime.
// The phone / browser asks Open-Meteo (free, no key) itself: the monitor board has no room for HTTPS.
// Same logic as android data/Weather.kt.
import { useEffect, useState } from 'react'
import type { Live } from './types'
import { sunPhase } from './sun'

export const WEATHER_AT = { name: 'Gujar Khan', lat: 33.253, lon: 73.304 }
const EVERY_MS = 15 * 60_000

export type Sky = 'clear' | 'partly' | 'cloudy' | 'rain'
export interface Weather { sky: Sky; cloud: number; at: number }

/** WMO weather code + cloud cover (%) to a simple sky. */
export function skyOf(code: number, cloud: number): Sky {
  if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) return 'rain'
  if (code === 3 || code === 45 || code === 48 || cloud >= 70) return 'cloudy'
  if (code === 2 || cloud >= 30) return 'partly'
  return 'clear'
}

let cache: Weather | null = null
let inFlight: Promise<void> | null = null
const subs = new Set<(w: Weather | null) => void>()

async function refresh() {
  if (inFlight || (cache && Date.now() - cache.at < EVERY_MS)) return
  inFlight = (async () => {
    try {
      const u = `https://api.open-meteo.com/v1/forecast?latitude=${WEATHER_AT.lat}&longitude=${WEATHER_AT.lon}&current=cloud_cover,weather_code`
      const r = await fetch(u, { signal: AbortSignal.timeout(8000) })
      const j = await r.json()
      const cloud = Number(j?.current?.cloud_cover), code = Number(j?.current?.weather_code)
      if (Number.isFinite(cloud) && Number.isFinite(code)) cache = { sky: skyOf(code, cloud), cloud, at: Date.now() }
    } catch {
      // no internet on this network: the watts-only guess below still works
    } finally {
      inFlight = null
      subs.forEach((f) => f(cache))
    }
  })()
}

/** Latest weather (null until known or when there is no internet), refreshed every 15 minutes. */
export function useWeather(): Weather | null {
  const [w, setW] = useState(cache)
  useEffect(() => {
    subs.add(setW)
    refresh()
    const i = setInterval(refresh, 60_000)
    return () => { subs.delete(setW); clearInterval(i) }
  }, [])
  return w && Date.now() - w.at < 3 * EVERY_MS ? w : null
}

export type SolarBadge = 'night' | 'cloudy' | 'rain' | 'cloudy?' | null

/** What to show on the solar circle: moon at night; cloud when the sky is cloudy (or, with no weather,
 *  when solar is far below today's peak in the middle of the day). */
export function solarBadge(d: Live, w: Weather | null, now = d.t ? new Date(d.t) : new Date()): SolarBadge {
  const phase = sunPhase(now)
  if (phase === 'night') return d.pvW < 15 ? 'night' : null
  if (w && (w.sky === 'rain' || w.sky === 'cloudy')) return w.sky
  if (!w && phase === 'day' && d.today.pvPeak >= 300 && d.pvW < Math.max(80, 0.25 * d.today.pvPeak)) return 'cloudy?'
  return null
}

/** Latest weather outside React (alerts text); null when unknown or stale. */
export const currentWeather = (): Weather | null => (cache && Date.now() - cache.at < 3 * EVERY_MS ? cache : null)

/** "cloudy outside" / "raining outside" when the weather says so, for alert texts. */
export function skyWords(w: Weather | null): string | null {
  if (!w) return null
  return w.sky === 'rain' ? `raining in ${WEATHER_AT.name}` : w.sky === 'cloudy' ? `cloudy in ${WEATHER_AT.name} (${Math.round(w.cloud)}% cloud)` : null
}
