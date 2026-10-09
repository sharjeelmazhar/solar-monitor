import { useEffect, useState, useSyncExternalStore } from 'react'
import { setHour12 } from './format'

// Per-viewer preferences kept in localStorage (theme, 3D effects). Every access is guarded:
// storage can be unavailable in private windows.

const read = (k: string) => {
  try {
    return localStorage.getItem(k)
  } catch {
    return null
  }
}
const write = (k: string, v: string) => {
  try {
    localStorage.setItem(k, v)
  } catch {
    /* not persisted */
  }
}

export type Theme = 'system' | 'light' | 'dark'

export function applyTheme(t: Theme) {
  const dark = t === 'dark' || (t === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.classList.toggle('dark', dark)
  return dark
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => (read('theme') as Theme) || 'system')
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  useEffect(() => {
    write('theme', theme)
    setDark(applyTheme(theme))
    const mq = matchMedia('(prefers-color-scheme: dark)')
    const on = () => theme === 'system' && setDark(applyTheme('system'))
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [theme])
  return { theme, setTheme, dark }
}

export function webglAvailable() {
  try {
    const c = document.createElement('canvas')
    return !!(c.getContext('webgl2') || c.getContext('webgl'))
  } catch {
    return false
  }
}

function default3d() {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return false
  const mem = (navigator as Navigator & { deviceMemory?: number }).deviceMemory
  return !(mem && mem <= 2)
}

// 12 / 24-hour clock. format.ts reads the module value; components re-render through useClock.
let clock12 = read('clock') !== '24'
setHour12(clock12)
const clockListeners = new Set<() => void>()
export function useClock() {
  const on = useSyncExternalStore((l) => { clockListeners.add(l); return () => clockListeners.delete(l) }, () => clock12)
  const set = (v: boolean) => {
    clock12 = v
    setHour12(v)
    write('clock', v ? '12' : '24')
    clockListeners.forEach((l) => l())
  }
  return [on, set] as const
}

// Battery flows under this many watts count as idle (on by default, 100 W).
type BattIdle = { on: boolean; w: number }
let battIdle: BattIdle = (() => {
  try {
    const v = JSON.parse(read('battIdle') ?? 'null')
    if (v && typeof v.on === 'boolean' && typeof v.w === 'number') return v
  } catch { /* default */ }
  return { on: true, w: 100 }
})()
const idleListeners = new Set<() => void>()
/** [settings, setter, effective threshold in W] */
export function useBattIdle() {
  const v = useSyncExternalStore((l) => { idleListeners.add(l); return () => idleListeners.delete(l) }, () => battIdle)
  const set = (n: BattIdle) => {
    battIdle = n
    write('battIdle', JSON.stringify(n))
    idleListeners.forEach((l) => l())
  }
  return [v, set, v.on ? v.w : 15] as const
}

// shared across components so toggling it in System updates the Live page at once
let fx3d: boolean | null = null
const fxListeners = new Set<() => void>()
const getFx = () => {
  if (fx3d == null) {
    // new key for the cosmos background: the old 'fx3d' (3D core) choice must not hide the new universe
    const v = read('cosmos3d')
    fx3d = v == null ? default3d() : v === '1'
  }
  return fx3d
}
const setFx = (on: boolean) => {
  fx3d = on
  write('cosmos3d', on ? '1' : '0')
  fxListeners.forEach((l) => l())
}

/** [enabled and supported, setter, user's choice] */
export function use3d() {
  const on = useSyncExternalStore((l) => { fxListeners.add(l); return () => fxListeners.delete(l) }, getFx)
  return [on && webglAvailable(), setFx, on] as const
}
