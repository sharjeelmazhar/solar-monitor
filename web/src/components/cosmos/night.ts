import { sunTimes } from '../../lib/sun'

// `?night=1` in the address previews the night look during the day (for checking the design).
export const forceNight = () => typeof location !== 'undefined' && /[?&]night=1\b/.test(location.search)

/** 0 by day, 1 at night, in between during the 90 minutes around sunrise and sunset. */
export function nightness(now = new Date()) {
  if (forceNight()) return 1
  const { rise, set } = sunTimes(now)
  const t = now.getTime()
  const ramp = 45 * 60000
  if (t < rise - ramp || t > set + ramp) return 1
  if (t > rise + ramp && t < set - ramp) return 0
  const edge = t < (rise + set) / 2 ? (rise + ramp - t) / (2 * ramp) : (t - (set - ramp)) / (2 * ramp)
  return Math.max(0, Math.min(1, edge))
}

/** The scene and the hero switch to the night look at the same moment. */
export const isNight = () => nightness() > 0.5
