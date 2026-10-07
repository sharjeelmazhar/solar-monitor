import { useEffect, useState, useSyncExternalStore } from 'react'

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

// shared across components so toggling it in System updates the Live page at once
let fx3d: boolean | null = null
const fxListeners = new Set<() => void>()
const getFx = () => {
  if (fx3d == null) {
    const v = read('fx3d')
    fx3d = v == null ? default3d() : v === '1'
  }
  return fx3d
}
const setFx = (on: boolean) => {
  fx3d = on
  write('fx3d', on ? '1' : '0')
  fxListeners.forEach((l) => l())
}

/** [enabled and supported, setter, user's choice] */
export function use3d() {
  const on = useSyncExternalStore((l) => { fxListeners.add(l); return () => fxListeners.delete(l) }, getFx)
  return [on && webglAvailable(), setFx, on] as const
}
