export function fmtW(w: number): string {
  const a = Math.abs(w)
  if (a >= 10000) return (w / 1000).toFixed(1) + ' kW'
  if (a >= 1000) return (w / 1000).toFixed(2) + ' kW'
  return Math.round(w) + ' W'
}

/** Splits a value and its unit so the unit can be styled smaller. */
export function splitUnit(s: string): [string, string] {
  const m = /^(-?[\d.,]+)\s*(.*)$/.exec(s)
  return m ? [m[1], m[2]] : [s, '']
}

export function fmtWh(wh: number): string {
  if (wh >= 100000) return Math.round(wh / 1000) + ' kWh'
  if (wh >= 10000) return (wh / 1000).toFixed(1) + ' kWh'
  if (wh >= 1000) return (wh / 1000).toFixed(2) + ' kWh'
  return Math.round(wh) + ' Wh'
}

/** "Units" on a Pakistani electricity bill are kWh. */
export function fmtUnits(wh: number): string {
  const u = wh / 1000
  return (u >= 100 ? Math.round(u).toString() : u >= 10 ? u.toFixed(1) : u.toFixed(2))
}

export function fmtDuration(minutes: number): string {
  const m = Math.round(minutes)
  const h = Math.floor(m / 60)
  return h ? `${h}h ${String(m % 60).padStart(2, '0')}m` : `${m}m`
}

export function fmtPkr(rs: number): string {
  return 'Rs ' + Math.round(rs).toLocaleString('en-PK')
}

const pad = (n: number) => String(n).padStart(2, '0')

// 12-hour clock by default (what most people in Pakistan read); the viewer can switch in System.
let h12 = true
export const setHour12 = (v: boolean) => { h12 = v }

/** "2:05 PM" or "14:05" */
export const hhmm = (t: number) => {
  const d = new Date(t)
  const h = d.getHours()
  return h12 ? `${h % 12 || 12}:${pad(d.getMinutes())} ${h < 12 ? 'AM' : 'PM'}` : `${pad(h)}:${pad(d.getMinutes())}`
}
/** "2:05:09 PM" or "14:05:09" */
export const hhmmss = (t: number) => {
  const d = new Date(t)
  const h = d.getHours()
  const ms = `${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  return h12 ? `${h % 12 || 12}:${ms} ${h < 12 ? 'AM' : 'PM'}` : `${pad(h)}:${ms}`
}
/** An hour of the day: "6 AM" / "06:00" */
export const hourLabel = (h: number) => (h12 ? `${h % 12 || 12} ${h % 24 < 12 ? 'AM' : 'PM'}` : `${pad(h % 24)}:00`)

/** Dates as YYYYMMDD numbers in local time (the device names its files the same way). */
export const ymd = (d: Date) => d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate()
export const fromYmd = (n: number) => new Date(Math.floor(n / 10000), (Math.floor(n / 100) % 100) - 1, n % 100)
export const ymdIso = (n: number) => {
  const d = fromYmd(n)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
export const isoToYmd = (s: string) => Number(s.replaceAll('-', ''))
export const addDays = (n: number, k: number) => {
  const d = fromYmd(n)
  d.setDate(d.getDate() + k)
  return ymd(d)
}
export const dayLabel = (n: number, opts: Intl.DateTimeFormatOptions = { weekday: 'short', day: 'numeric', month: 'short' }) =>
  fromYmd(n).toLocaleDateString(undefined, opts)
