import { useEffect, useState, useSyncExternalStore } from 'react'
import { parseBill, type BillConfig } from './bill'
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
  lastRxAt: number // performance.now() of the last message of any kind (heartbeats too)
  intervalMs: number // average time between readings
  recent: Sample[] // last ~16 minutes, for the live chart
  bill: BillConfig | null // bill estimator settings, shared through the monitor
}

let state: State = { live: null, info: null, conn: 'connecting', lastMsgAt: 0, lastRxAt: 0, intervalMs: 0, recent: [], bill: null }
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
  const p: Partial<State> = { live: d, lastRxAt: now }
  if (!prev || d.seq !== prev.seq) {
    if (prev && state.lastMsgAt && d.seq > prev.seq && d.seq - prev.seq < 20) {
      // time per reading, even when unchanged readings were not pushed. The monitor's own timestamps are used when it
      // has a clock, so pushes that arrive bunched up (slow Wi-Fi, buffering) don't distort it.
      const dt = d.t && prev.t && d.t > prev.t ? d.t - prev.t : now - state.lastMsgAt
      intervals.push(dt / (d.seq - prev.seq))
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
  refreshInfo().then(loadRecent).then(loadBill)
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


async function loadBill() {
  try {
    set({ bill: parseBill(await (await fetch(API_BASE + '/api/bill', { cache: 'no-store' })).json()) })
    syncCycle()
  } catch {
    if (!state.bill) set({ bill: parseBill(null) })
  }
}

/** The monitor counts grid units from the meter reading (day + hour from the bill settings): keep it told. */
function syncCycle() {
  const b = state.bill
  const i = state.info
  if (!b || !i || i.cycDay === undefined) return // older firmware has no counter
  if (i.cycDay !== b.day || i.cycHour !== b.hr) void saveSettings({ cycDay: String(b.day), cycHour: String(b.hr) })
}

export async function saveBill(c: BillConfig): Promise<boolean> {
  try {
    const r = await fetch(API_BASE + '/api/bill', { method: 'POST', body: new URLSearchParams({ v: JSON.stringify(c) }) })
    if (!r.ok) return false
    set({ bill: parseBill(await r.json()) })
    syncCycle()
    return true
  } catch {
    return false
  }
}

/** Readings older than this mean the monitor (or the link to it) is down. Heartbeat is 5 s. */
export const STALE_MS = 12_000

/**
 * null while live; otherwise seconds since the last reading (Infinity if none yet).
 * Re-checks every second so the page reacts even when no message arrives.
 */
export function useStale(): number | null {
  const at = useStore((s) => s.lastRxAt)
  const [, tick] = useState(0)
  useEffect(() => {
    const i = setInterval(() => tick((x) => x + 1), 1000)
    return () => clearInterval(i)
  }, [])
  const age = at ? performance.now() - at : Infinity
  return age < STALE_MS ? null : age / 1000
}
