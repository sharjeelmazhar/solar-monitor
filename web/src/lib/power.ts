import { modeOf } from './decode'
import type { Live } from './types'

// Plain-language reading of what is powering the home. Same rules as android/.../data/Power.kt.

/** Smaller solar / grid flows are treated as zero so labels don't flicker. */
export const DEADBAND = 15

export type BattState = 'charging' | 'discharging' | 'idle'

/**
 * idleW: battery flows smaller than this count as idle. At full charge the inverter often takes a little from
 * the battery (tens of watts) even when solar covers the home; the user can choose to hide that.
 */
export function battState(d: Live, idleW: number): BattState {
  if (d.battW >= Math.max(DEADBAND, idleW)) return 'charging'
  if (-d.battW >= Math.max(DEADBAND, idleW)) return 'discharging'
  return 'idle'
}

/** What is supplying the home right now, in order of importance. */
export function sourcesOf(d: Live, idleW: number): ('Solar' | 'Grid' | 'Battery')[] {
  if (!d.ok) return []
  const out: ('Solar' | 'Grid' | 'Battery')[] = []
  if (d.pvW >= DEADBAND) out.push('Solar')
  if (d.gridOn && d.gridW >= DEADBAND) out.push('Grid')
  if (battState(d, idleW) === 'discharging') out.push('Battery')
  return out
}

/** "Solar + Grid", "Solar + Battery", "Battery"… or the inverter mode when nothing is flowing. */
export function sourcesLabel(d: Live, idleW: number): string {
  const s = sourcesOf(d, idleW)
  return s.length ? s.join(' + ') : modeOf(d.mode).name
}

export function sourcesSentence(d: Live, idleW: number): string {
  const s = sourcesOf(d, idleW).map((x) => (x === 'Grid' ? 'the grid' : x === 'Battery' ? 'the battery' : 'solar'))
  if (!s.length) return modeOf(d.mode).text
  const list = s.length === 1 ? s[0] : s.slice(0, -1).join(', ') + ' and ' + s[s.length - 1]
  return list[0].toUpperCase() + list.slice(1) + (s.length === 1 ? ' powers' : ' power') + ' the home'
}

/**
 * Daytime, grid off, little sun and the battery is carrying the home: usually clouds (or the grid was switched off
 * and forgotten). Daytime = 7 AM to 6 PM local and solar already made real power today.
 */
export function weakSolar(d: Live, idleW: number, now = new Date()): boolean {
  if (!d.ok || d.gridOn) return false
  const h = now.getHours()
  if (h < 7 || h >= 18 || d.today.pvPeak < 300) return false
  return d.pvW < Math.max(80, 0.3 * d.loadW) && battState(d, Math.max(idleW, 50)) === 'discharging'
}
