// Sky over the house, so the dashboard can say "cloudy" when solar is low in daytime.
// The phone / browser asks Open-Meteo (free, no key) itself: the monitor board has no room for HTTPS.
// Same logic as android data/Weather.kt.
import { useEffect, useState } from 'react'
import type { Live } from './types'
import { sunPhase, sunTimes } from './sun'

export const WEATHER_AT = { name: 'Gujar Khan', lat: 33.253, lon: 73.304 }
const EVERY_MS = 15 * 60_000

export type Sky = 'clear' | 'partly' | 'cloudy' | 'rain'
export interface Weather { sky: Sky; cloud: number; at: number }

/** WMO weather code + cloud cover (%) to a simple sky. With `mm` (rain measured right now) a rain code only
 *  counts when rain is actually falling under a heavy sky: the model often flags drizzle or a thunderstorm
 *  for a 15-minute slot while the sun is out. */
export function skyOf(code: number, cloud: number, mm?: number): Sky {
  const rainCode = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95
  if (rainCode && (mm == null || (mm > 0 && cloud >= 50))) return 'rain'
  if (rainCode) return cloud >= 70 ? 'cloudy' : cloud >= 30 ? 'partly' : 'clear'
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
      const u = `https://api.open-meteo.com/v1/forecast?latitude=${WEATHER_AT.lat}&longitude=${WEATHER_AT.lon}&current=cloud_cover,weather_code,rain,showers`
      const r = await fetch(u, { signal: AbortSignal.timeout(8000) })
      const j = await r.json()
      const cloud = Number(j?.current?.cloud_cover), code = Number(j?.current?.weather_code)
      const mm = (Number(j?.current?.rain) || 0) + (Number(j?.current?.showers) || 0)
      if (Number.isFinite(cloud) && Number.isFinite(code)) cache = { sky: skyOf(code, cloud, mm), cloud, at: Date.now() }
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

// Last minute of solar readings, so one dip (a bird, a passing cloud edge) doesn't flip the badge.
const pvLog: { t: number; w: number }[] = []
/** Average solar watts over the last 60 s of readings (the current reading alone until there are more). */
export function pvMinuteAvg(d: Live): number {
  const t = d.t || Date.now()
  if (!pvLog.length || pvLog[pvLog.length - 1].t !== t) pvLog.push({ t, w: d.pvW })
  while (pvLog.length && t - pvLog[0].t > 60_000) pvLog.shift()
  if (pvLog.length > 200) pvLog.splice(0, pvLog.length - 200)
  return pvLog.reduce((s, x) => s + x.w, 0) / pvLog.length
}
export const _resetPvLog = () => { pvLog.length = 0 }

/** What to show on the solar circle: moon at night; rain / cloud from the weather; and a cloud from the panels
 *  themselves when the sun is up (more than 40 min from sunrise/sunset) but solar has stayed under 50 W for a
 *  minute on a system that can make 300 W+. The panels are the ground truth: the weather model can say "clear"
 *  for a town while a cloud sits over the house. */
export function solarBadge(d: Live, w: Weather | null, now = d.t ? new Date(d.t) : new Date()): SolarBadge {
  const phase = sunPhase(now)
  if (phase === 'night') return d.pvW < 15 ? 'night' : null
  if (w && (w.sky === 'rain' || w.sky === 'cloudy')) return w.sky
  const { rise, set } = sunTimes(now)
  const t = now.getTime()
  const sunUp = t > rise + 40 * 60000 && t < set - 40 * 60000
  if (sunUp && d.today.pvPeak >= 300 && pvMinuteAvg(d) < 50) return 'cloudy'
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
