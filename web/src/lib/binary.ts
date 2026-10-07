import type { DayRec, MinRec, Sample } from './types'

// Little-endian record formats documented in the README ("Device API").

export function parseSamples(buf: ArrayBuffer): Sample[] {
  const dv = new DataView(buf)
  const out: Sample[] = []
  for (let o = 0; o + 16 <= buf.byteLength; o += 16) {
    const t = dv.getUint32(o, true)
    if (!t) continue // device clock was not set yet
    out.push({
      t: t * 1000 + dv.getUint16(o + 4, true),
      pv: dv.getUint16(o + 6, true),
      load: dv.getUint16(o + 8, true),
      grid: dv.getUint16(o + 10, true),
      batt: dv.getInt16(o + 12, true),
    })
  }
  return out
}

export function parseMinutes(buf: ArrayBuffer): MinRec[] {
  const dv = new DataView(buf)
  const out: MinRec[] = []
  for (let o = 0; o + 24 <= buf.byteLength; o += 24) {
    out.push({
      t: dv.getUint32(o, true) * 1000,
      pv: dv.getUint16(o + 4, true),
      load: dv.getUint16(o + 6, true),
      grid: dv.getUint16(o + 8, true),
      batt: dv.getInt16(o + 10, true),
      battV: dv.getUint16(o + 12, true) / 100,
      pvV: dv.getUint16(o + 14, true) / 10,
      gridV: dv.getUint16(o + 16, true) / 10,
      outV: dv.getUint16(o + 18, true) / 10,
      soc: dv.getUint8(o + 20),
      temp: dv.getInt8(o + 21),
      mode: String.fromCharCode(dv.getUint8(o + 22)),
      flags: dv.getUint8(o + 23),
    })
  }
  return out
}

export function parseDays(buf: ArrayBuffer): DayRec[] {
  const dv = new DataView(buf)
  const out: DayRec[] = []
  for (let o = 0; o + 40 <= buf.byteLength; o += 40) {
    out.push({
      date: dv.getUint32(o, true),
      pv: dv.getFloat32(o + 4, true),
      load: dv.getFloat32(o + 8, true),
      grid: dv.getFloat32(o + 12, true),
      chg: dv.getFloat32(o + 16, true),
      dis: dv.getFloat32(o + 20, true),
      pvPeak: dv.getUint16(o + 24, true),
      loadPeak: dv.getUint16(o + 26, true),
      gridOnMin: dv.getUint16(o + 28, true),
      onlineMin: dv.getUint16(o + 30, true),
      battMin: dv.getUint8(o + 32),
      battMax: dv.getUint8(o + 33),
      tempMax: dv.getInt8(o + 34),
      outages: dv.getUint8(o + 35),
    })
  }
  return out
}
