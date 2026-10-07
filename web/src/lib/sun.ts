// Sunrise and sunset worked out on the device (the standard sunrise equation, within about a minute), so no
// internet is needed. Default place: Islamabad / Rawalpindi (IESCO area). Same code as android data/Sun.kt.

export const HOME = { lat: 33.684, lon: 73.048 }

const rad = Math.PI / 180

/** Sunrise and sunset (ms since epoch) for the local day of `day`. */
export function sunTimes(day: Date, lat = HOME.lat, lon = HOME.lon): { rise: number; set: number } {
  const noon = new Date(day.getFullYear(), day.getMonth(), day.getDate(), 12).getTime()
  const n = Math.round(noon / 86400000 + 2440587.5 - 2451545.0 + 0.0008)
  const j = n - lon / 360
  const m = (357.5291 + 0.98560028 * j) % 360
  const c = 1.9148 * Math.sin(m * rad) + 0.02 * Math.sin(2 * m * rad) + 0.0003 * Math.sin(3 * m * rad)
  const l = (m + c + 180 + 102.9372) % 360
  const transit = 2451545 + j + 0.0053 * Math.sin(m * rad) - 0.0069 * Math.sin(2 * l * rad)
  const dec = Math.asin(Math.sin(l * rad) * Math.sin(23.44 * rad))
  const cosW = (Math.sin(-0.833 * rad) - Math.sin(lat * rad) * Math.sin(dec)) / (Math.cos(lat * rad) * Math.cos(dec))
  const w = Math.acos(Math.max(-1, Math.min(1, cosW))) / rad
  const ms = (jd: number) => Math.round((jd - 2440587.5) * 86400000)
  return { rise: ms(transit - w / 360), set: ms(transit + w / 360) }
}

export type SunPhase = 'night' | 'low' | 'day'

/** night = before sunrise or after sunset; low = within 90 min of either (little solar is normal); day otherwise. */
export function sunPhase(now = new Date()): SunPhase {
  const { rise, set } = sunTimes(now)
  const t = now.getTime()
  if (t < rise || t > set) return 'night'
  if (t < rise + 90 * 60000 || t > set - 90 * 60000) return 'low'
  return 'day'
}
