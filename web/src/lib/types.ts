// Shapes of the monitor's API (see firmware/solar_monitor_v3/solar_monitor_v3.ino).

export interface Today {
  date: number
  pv: number
  load: number
  grid: number
  chg: number
  dis: number
  gridOnMin: number
  onlineMin: number
  outages: number
  pvPeak: number
  loadPeak: number
}

/** Billing month counter kept by the monitor (energy in Wh since the last meter reading). */
export interface Cyc {
  s: number // epoch s of the reading the month started at
  f: number // epoch s when counting began (later than s if the monitor was off or set up mid-month)
  g: number // grid Wh
  l: number // home Wh
  p: number // solar Wh
  m: number // minutes the monitor was reading the inverter
  ps: number // previous month: start
  pg: number // previous month: grid Wh
  pm: number // previous month: minutes monitored
}

export interface Live {
  cyc?: Cyc
  seq: number
  t: number // epoch ms, 0 when the device clock is not set
  ok: boolean
  ever: boolean
  age: number
  mode: string
  pvW: number
  pvV: number
  pvA: number
  /** second solar input (two-MPPT models), 0 when absent */
  pv2V?: number
  pv2A?: number
  pvChgW: number
  battV: number
  battPct: number
  chgA: number
  dischgA: number
  battW: number
  loadW: number
  loadVA: number
  loadPct: number
  outV: number
  outHz: number
  gridOn: boolean
  gridV: number
  gridHz: number
  gridW: number
  tempC: number
  busV: number
  st: string
  st2: string
  warn: string
  today: Today
  poll: { ms: number; ok: number; fail: number; crc: number; err: string }
  timeOk: boolean
}

export interface Info {
  fw: string
  name: string
  host: string
  ip: string
  mac: string
  ssid: string
  rssi: number
  ap: boolean
  uptime: number
  heap: number
  minHeap: number
  fsUsed: number
  fsTotal: number
  fsOk: boolean
  timeOk: boolean
  time: number
  tz: string
  clients: number
  histFrom: number
  battAh: number
  cycDay?: number
  cycHour?: number
  tariff: number
  inv: { proto?: string; qpiri: string; qid: string; qvfw: string; qflag: string; chgCur?: string; acCur?: string; beqi?: string }
  ui?: string
}

export interface Sample {
  t: number
  pv: number
  load: number
  grid: number
  batt: number
}

export interface MinRec {
  t: number
  pv: number
  load: number
  grid: number
  batt: number
  battV: number
  pvV: number
  gridV: number
  outV: number
  soc: number
  temp: number
  mode: string
  flags: number
}

export interface DayRec {
  date: number
  pv: number
  load: number
  grid: number
  chg: number
  dis: number
  pvPeak: number
  loadPeak: number
  gridOnMin: number
  onlineMin: number
  battMin: number
  battMax: number
  tempMax: number
  outages: number
}
