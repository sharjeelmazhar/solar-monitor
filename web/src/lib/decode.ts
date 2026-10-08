// Human-readable decoding of Voltronic PI30 codes.

export const MODES: Record<string, { name: string; text: string }> = {
  L: { name: 'Grid', text: 'Grid is powering the home' },
  B: { name: 'Battery / Solar', text: 'Running from solar and battery' },
  S: { name: 'Standby', text: 'Standby' },
  F: { name: 'Fault', text: 'Inverter fault' },
  H: { name: 'Power saving', text: 'Power saving' },
  P: { name: 'Power on', text: 'Starting up' },
  D: { name: 'Shutdown', text: 'Shut down' },
}
export const modeOf = (m: string) => MODES[m] ?? { name: 'Unknown', text: '' }

export const WARNINGS: Record<number, string> = {
  1: 'Inverter fault', 2: 'Bus over-voltage', 3: 'Bus under-voltage', 4: 'Bus soft-start failed', 5: 'Grid not available',
  6: 'Output short circuit', 7: 'Inverter voltage too low', 8: 'Inverter voltage too high', 9: 'Over temperature',
  10: 'Fan locked', 11: 'Battery voltage too high', 12: 'Battery low', 14: 'Battery under-voltage shutdown', 16: 'Overload',
  17: 'EEPROM fault', 18: 'Inverter over-current', 19: 'Inverter soft-start failed', 20: 'Self-test failed',
  21: 'DC voltage on output', 22: 'Battery disconnected', 23: 'Current sensor failed', 24: 'Battery short circuit',
  25: 'Power limit active', 26: 'Solar (PV) voltage too high', 27: 'MPPT overload fault', 28: 'MPPT overload warning',
  29: 'Battery too low to charge',
}
export const SEVERE = new Set([1, 2, 3, 4, 6, 9, 10, 11, 14, 16, 17, 18, 19, 20, 21, 22, 23, 24, 27])

/** Active warning bits, without "grid not available" (shown as grid status instead). */
export function activeWarnings(warn: string): number[] {
  const out: number[] = []
  for (let i = 0; i < warn.length; i++) if (warn[i] === '1' && WARNINGS[i] && i !== 5) out.push(i)
  return out
}

export const BATT_TYPES = ['AGM', 'Flooded', 'User defined', 'Pylontech (lithium)', 'Shinheung (lithium)', 'WECO (lithium)', 'Soltaro (lithium)', 'BAK (lithium)', 'Lithium']
export const OUT_PRIO = ['Utility first (USB)', 'Solar first (SUB)', 'Solar → Battery → Utility (SBU)']
export const OUT_PRIO_HELP = [
  'The grid powers the home whenever it is available; solar and battery only take over during outages.',
  'Solar powers the home first; the grid fills in when solar is not enough; the battery is kept for outages.',
  'Solar first, then the battery, and the grid only when the battery reaches its low limit. Saves the most units.',
]
export const CHG_PRIO = ['Utility first', 'Solar first', 'Solar + Utility', 'Solar only']
export const CHG_PRIO_HELP = [
  'The battery charges from the grid first, solar helps.',
  'The battery charges from solar first; the grid only charges it when there is no solar.',
  'Solar and grid charge the battery together (fastest).',
  'Only solar charges the battery; the grid never does.',
]
const FLAG_NAMES: Record<string, string> = {
  a: 'Buzzer', b: 'Overload bypass', d: 'Solar feed to grid', j: 'Power saving', k: 'LCD returns to home screen', u: 'Overload auto-restart',
  v: 'Over-temp auto-restart', x: 'LCD backlight', y: 'Beep on grid loss', z: 'Fault code record',
}

export interface Rated {
  gridV: number
  outV: number
  outHz: number
  outVA: number
  outW: number
  battV: number
  recharge: number
  cutoff: number
  bulk: number
  float: number
  battType: number
  maxAc: number
  maxChg: number
  range: number
  outPrio: number
  chgPrio: number
  machine: string
  redischarge: number | null
}

export function parseRated(q: string | undefined): Rated | null {
  const f = (q || '').trim().split(/\s+/)
  if (f.length < 20) return null
  const n = (i: number) => parseFloat(f[i])
  return {
    gridV: n(0), outV: n(2), outHz: n(3), outVA: n(5), outW: n(6), battV: n(7), recharge: n(8), cutoff: n(9), bulk: n(10),
    float: n(11), battType: +f[12], maxAc: n(13), maxChg: n(14), range: +f[15], outPrio: +f[16], chgPrio: +f[17],
    machine: f[19], redischarge: f.length > 22 ? n(22) : null,
  }
}

export function parseFlags(q: string | undefined): { on: string[]; off: string[] } | null {
  const m = /^E([a-z]*)D([a-z]*)$/.exec(q || '')
  if (!m) return null
  const names = (s: string) => s.split('').map((c) => FLAG_NAMES[c]).filter(Boolean)
  return { on: names(m[1]), off: names(m[2]) }
}
