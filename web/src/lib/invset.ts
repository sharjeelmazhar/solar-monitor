// Inverter settings that can be changed from the apps (Advanced mode). Same list, ranges and wording as the
// Android app (android/.../data/InvSet.kt) and the firmware check (firmware/solar_monitor_v3/invset.h), which
// refuses anything outside these ranges a second time before it reaches the inverter.
import { CHG_PRIO, CHG_PRIO_HELP, OUT_PRIO, OUT_PRIO_HELP, type Rated } from './decode'
import { API_BASE, refreshInfo } from './store'

export type SetKind = 'choice' | 'volts' | 'amps' | 'flag'

export interface SetDef {
  key: string
  label: string
  help: string
  kind: SetKind
  letter?: string // flags only
}

export const INV_SETTINGS: SetDef[] = [
  { key: 'outPrio', label: 'Output priority', kind: 'choice', help: 'Which source powers the home first.' },
  { key: 'chgPrio', label: 'Charger priority', kind: 'choice', help: 'Which source charges the battery.' },
  { key: 'bulk', label: 'Bulk charge voltage', kind: 'volts', help: 'The voltage the charger pushes the battery up to. Follow your battery maker\'s value.' },
  { key: 'float', label: 'Float charge voltage', kind: 'volts', help: 'The voltage the charger holds once the battery is full. Never above bulk.' },
  { key: 'cutoff', label: 'Low cut-off voltage', kind: 'volts', help: 'Below this the inverter switches the battery off to protect it.' },
  { key: 'recharge', label: 'Back to grid at', kind: 'volts', help: 'Battery voltage at which the home switches to the grid (SBU / Solar first).' },
  { key: 'redischarge', label: 'Back to battery at', kind: 'volts', help: 'Battery voltage at which the home goes back to the battery after recharging. "Full" waits for a full battery.' },
  { key: 'maxChg', label: 'Max charge current', kind: 'amps', help: 'Total charging current limit (solar + grid).' },
  { key: 'maxAc', label: 'Max grid charge current', kind: 'amps', help: 'Charging current limit from the grid.' },
  { key: 'range', label: 'AC input range', kind: 'choice', help: 'Appliance accepts a wider grid voltage; UPS switches to battery faster (for computers).' },
  { key: 'flag', letter: 'a', label: 'Buzzer', kind: 'flag', help: 'Beeps on alarms and key presses.' },
  { key: 'flag', letter: 'y', label: 'Beep on grid loss', kind: 'flag', help: 'Beeps when the grid goes off.' },
  { key: 'flag', letter: 'x', label: 'LCD backlight', kind: 'flag', help: 'Keeps the screen lit.' },
  { key: 'flag', letter: 'k', label: 'LCD returns to home screen', kind: 'flag', help: 'The screen goes back to the main page after a minute.' },
  { key: 'flag', letter: 'u', label: 'Overload auto-restart', kind: 'flag', help: 'Restarts by itself after an overload trip.' },
  { key: 'flag', letter: 'v', label: 'Over-temperature auto-restart', kind: 'flag', help: 'Restarts by itself after cooling down.' },
  { key: 'flag', letter: 'b', label: 'Overload bypass', kind: 'flag', help: 'Switches to the grid when the load is too big for the inverter.' },
  { key: 'flag', letter: 'j', label: 'Power saving', kind: 'flag', help: 'Turns the inverter output off when nothing is connected.' },
  { key: 'flag', letter: 'z', label: 'Fault code record', kind: 'flag', help: 'Keeps a history of fault codes in the inverter.' },
]

export const setId = (d: SetDef) => (d.letter ? 'flag:' + d.letter : d.key)

export interface Choice { value: number; label: string; help?: string }

/** Allowed values for a setting given the current ratings; null when it can't be changed yet. */
export function choicesOf(d: SetDef, r: Rated, chgCur: string, acCur: string): Choice[] | null {
  const k = r.battV / 12
  if (![1, 2, 4].includes(k)) return null
  const volts = (lo: number, hi: number, step: number): Choice[] => {
    const out: Choice[] = []
    for (let v = lo; v <= hi + 1e-6; v += step) out.push({ value: Math.round(v * 100) / 100, label: (Math.round(v * 100) / 100).toFixed(1) + ' V' })
    return out
  }
  const list = (s: string, max = 999): Choice[] =>
    s.trim().split(/\s+/).filter(Boolean).map(Number).filter((n) => n > 0 && n <= max).map((n) => ({ value: n, label: n + ' A' }))
  switch (d.key) {
    case 'outPrio': return OUT_PRIO.map((label, value) => ({ value, label, help: OUT_PRIO_HELP[value] }))
    case 'chgPrio': return CHG_PRIO.map((label, value) => ({ value, label, help: CHG_PRIO_HELP[value] }))
    case 'range': return [{ value: 0, label: 'Appliance (wide)' }, { value: 1, label: 'UPS (narrow)' }]
    case 'bulk': return volts(12 * k, 14.6 * k, 0.1).filter((c) => c.value >= r.float - 1e-6)
    case 'float': return volts(12 * k, 14.6 * k, 0.1).filter((c) => c.value <= r.bulk + 1e-6)
    case 'cutoff': return volts(10.5 * k, 12 * k, 0.1).filter((c) => c.value < r.recharge - 1e-6)
    case 'recharge': return volts(11 * k, 12.75 * k, 0.25 * k).filter((c) => c.value > r.cutoff + 1e-6 && (!r.redischarge || c.value < r.redischarge - 1e-6))
    case 'redischarge': return [{ value: 0, label: 'Full battery' }, ...volts(12 * k, 14.5 * k, 0.25 * k).filter((c) => c.value > r.recharge + 1e-6)]
    case 'maxChg': return list(chgCur)
    case 'maxAc': return list(acCur, 99)
    case 'flag': return [{ value: 1, label: 'On' }, { value: 0, label: 'Off' }]
  }
  return null
}

/** Current value of a setting (number), from QPIRI / QFLAG. */
export function currentOf(d: SetDef, r: Rated, qflag: string): number | null {
  if (d.kind === 'flag') {
    const m = /^E([a-z]*)D([a-z]*)$/.exec(qflag)
    if (!m || !d.letter) return null
    return m[1].includes(d.letter) ? 1 : m[2].includes(d.letter) ? 0 : null
  }
  const v = (r as unknown as Record<string, number | null>)[d.key]
  return v == null || Number.isNaN(v) ? null : v
}

export function labelOf(d: SetDef, v: number | null, r: Rated, chgCur: string, acCur: string): string {
  if (v == null) return '–'
  const c = choicesOf(d, r, chgCur, acCur)?.find((x) => Math.abs(x.value - v) < 0.01)
  if (c) return c.label
  if (d.kind === 'volts') return v === 0 ? 'Full battery' : v.toFixed(1) + ' V'
  if (d.kind === 'amps') return v + ' A'
  return String(v)
}

// ---- talking to the monitor ----

export async function checkPassword(pw: string): Promise<'ok' | 'wrong' | 'offline'> {
  try {
    const r = await fetch(API_BASE + '/api/inv/auth', { method: 'POST', body: new URLSearchParams({ pw }) })
    return r.ok ? 'ok' : r.status === 401 ? 'wrong' : 'offline'
  } catch {
    return 'offline'
  }
}

/** Sends one change and waits for the monitor to read it back. Resolves with ok + a message for the person. */
export async function sendSetting(d: SetDef, value: number, pw: string): Promise<{ ok: boolean; msg: string }> {
  try {
    const body = new URLSearchParams({ pw, key: d.key, value: String(value), by: 'web' })
    if (d.letter) body.set('letter', d.letter)
    const r = await fetch(API_BASE + '/api/inv/set', { method: 'POST', body })
    const j = await r.json().catch(() => ({}))
    if (!r.ok) return { ok: false, msg: j.error ?? 'The monitor refused the change' }
    const id = j.id
    const until = Date.now() + 20_000
    while (Date.now() < until) {
      await new Promise((res) => setTimeout(res, 600))
      const s = await (await fetch(API_BASE + '/api/inv/job', { cache: 'no-store' })).json()
      if (s.id !== id) continue
      if (s.state === 'ok' || s.state === 'failed') {
        await refreshInfo()
        return { ok: s.state === 'ok', msg: s.msg }
      }
    }
    return { ok: false, msg: 'No answer from the monitor in 20 s. Check the change log.' }
  } catch {
    return { ok: false, msg: 'Could not reach the monitor' }
  }
}

export interface LogEntry { t: number; by: string; k: string; o: string; n: string; r: string; m: string }

export async function fetchLog(): Promise<LogEntry[]> {
  try {
    return ((await (await fetch(API_BASE + '/api/inv/log', { cache: 'no-store' })).json()) as LogEntry[]).reverse()
  } catch {
    return []
  }
}

export function logLabel(k: string): string {
  const d = INV_SETTINGS.find((x) => setId(x) === k)
  return d ? d.label : k
}

export async function startProbe(pw: string): Promise<boolean> {
  try {
    return (await fetch(API_BASE + '/api/probe', { method: 'POST', body: new URLSearchParams({ pw }) })).ok
  } catch {
    return false
  }
}

export async function readProbe(): Promise<string> {
  try {
    return await (await fetch(API_BASE + '/api/probe', { cache: 'no-store' })).text()
  } catch {
    return ''
  }
}
