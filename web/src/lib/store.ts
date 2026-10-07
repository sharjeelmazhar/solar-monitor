import { useSyncExternalStore } from 'react'
import { parseDays, parseMinutes, parseSamples } from './binary'
import type { DayRec, Info, Live, MinRec, Sample } from './types'

/** Base URL of the monitor (or, later, the logger). Empty = same origin. */
export const API_BASE: string = import.meta.env.VITE_API_BASE ?? ''

export type Conn = 'connecting' | 'live' | 'reconnecting' | 'offline'

export interface State {
  live: Live | null
  info: Info | null
  conn: Conn
  lastMsgAt: number // performance.now() of the last new reading
  intervalMs: number // average time between readings
  recent: Sample[] // last ~16 minutes, for the live chart
}

let state: State = { live: null, info: null, conn: 'connecting', lastMsgAt: 0, intervalMs: 0, recent: [] }
const listeners = new Set<() => void>()
const set = (p: Partial<State>) => {
  state = { ...state, ...p }
  listeners.forEach((l) => l())
}
const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}
export const useStore = <T,>(sel: (s: State) => T): T => useSyncExternalStore(subscribe, () => sel(state))
export const getState = () => state

const intervals: number[] = []

function onLive(d: Live) {
  const prev = state.live
  const now = performance.now()
  const p: Partial<State> = { live: d }
  if (!prev || d.seq !== prev.seq) {
    if (prev && state.lastMsgAt && d.seq > prev.seq && d.seq - prev.seq < 20) {
      // readings per second, even when unchanged readings were not pushed
      intervals.push((now - state.lastMsgAt) / (d.seq - prev.seq))
      if (intervals.length > 12) intervals.shift()
      p.intervalMs = intervals.reduce((a, b) => a + b, 0) / intervals.length
    }
    p.lastMsgAt = now
    if (d.ok && d.t) {
      const cut = d.t - 16 * 60_000
      const recent = state.recent.filter((s) => s.t >= cut)
      recent.push({ t: d.t, pv: d.pvW, load: d.loadW, grid: d.gridW, batt: d.battW })
      p.recent = recent
    }
  }
  set(p)
}

let es: EventSource | null = null
let pollTimer = 0
let started = false

function connect() {
  es?.close()
  es = new EventSource(API_BASE + '/events')
  es.addEventListener('live', (e) => {
    try {
      onLive(JSON.parse((e as MessageEvent).data))
      if (state.conn !== 'live') set({ conn: 'live' })
    } catch {
      /* ignore malformed message */
    }
  })
  es.onopen = () => {
    stopPolling()
    set({ conn: 'live' })
  }
  es.onerror = () => {
    set({ conn: state.live ? 'reconnecting' : 'connecting' })
    if (!pollTimer) pollTimer = window.setTimeout(startPolling, 4000)
  }
}

// Fallback while the stream is down: poll once a second.
function startPolling() {
  const tick = async () => {
    if (es?.readyState === EventSource.OPEN) return stopPolling()
    try {
      const r = await fetch(API_BASE + '/api/live', { cache: 'no-store' })
      onLive(await r.json())
      set({ conn: 'reconnecting' })
    } catch {
      set({ conn: 'offline' })
    }
    if (pollTimer) pollTimer = window.setTimeout(tick, 1000)
  }
  pollTimer = window.setTimeout(tick, 0)
}
function stopPolling() {
  clearTimeout(pollTimer)
  pollTimer = 0
}

export async function refreshInfo(): Promise<Info | null> {
  try {
    const info: Info = await (await fetch(API_BASE + '/api/info', { cache: 'no-store' })).json()
    set({ info })
    if (!info.timeOk) {
      // no internet for NTP: give the device our clock
      await fetch(API_BASE + '/api/time', { method: 'POST', body: new URLSearchParams({ t: String(Math.round(Date.now() / 1000)) }) })
    }
    return info
  } catch {
    return null
  }
}

let recentLoading = false
async function loadRecent() {
  if (recentLoading) return
  recentLoading = true
  try {
    const s = parseSamples(await (await fetch(API_BASE + '/api/recent', { cache: 'no-store' })).arrayBuffer())
    const last = s.length ? s[s.length - 1].t : 0
    set({ recent: s.concat(state.recent.filter((x) => x.t > last)) })
  } catch {
    /* live chart just starts empty */
  } finally {
    recentLoading = false
  }
}

export function start() {
  if (started) return
  started = true
  connect()
  refreshInfo().then(loadRecent)
  setInterval(() => !document.hidden && refreshInfo(), 30_000)
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return
    if (!es || es.readyState === EventSource.CLOSED) connect()
    loadRecent()
  })
}

const dayCache = new Map<number, MinRec[]>()

/** Minute records for a local date (YYYYMMDD). Past days are cached; null = request failed. */
export async function fetchDay(date: number, isToday: boolean): Promise<MinRec[] | null> {
  if (!isToday && dayCache.has(date)) return dayCache.get(date)!
  try {
    const r = await fetch(`${API_BASE}/api/day?d=${date}`, { cache: 'no-store' })
    const recs = r.ok ? parseMinutes(await r.arrayBuffer()) : r.status === 404 ? [] : null
    if (recs && !isToday) dayCache.set(date, recs)
    return recs
  } catch {
    return null
  }
}

export async function fetchDays(): Promise<DayRec[] | null> {
  try {
    return parseDays(await (await fetch(API_BASE + '/api/days', { cache: 'no-store' })).arrayBuffer())
  } catch {
    return null
  }
}

export async function saveSettings(p: Record<string, string>): Promise<boolean> {
  try {
    const r = await fetch(API_BASE + '/api/settings', { method: 'POST', body: new URLSearchParams(p) })
    if (!r.ok) return false
    set({ info: await r.json() })
    return true
  } catch {
    return false
  }
}

export async function refreshInverter() {
  try {
    await fetch(API_BASE + '/api/refresh', { method: 'POST' })
  } catch {
    /* ignore */
  }
}
