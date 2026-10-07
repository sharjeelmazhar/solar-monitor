import { useEffect, useMemo, useState } from 'react'
import { BillCard } from '../components/BillCard'
import { BarChart, Legend, useHidden, type Series } from '../components/charts/charts'
import { Card, CardHeader, ChartCard, Empty, Segmented, Skeleton, Stat, Value } from '../components/ui/ui'
import { addDays, dayLabel, fmtDuration, fmtPkr, fmtUnits, fmtWh, fromYmd, isoToYmd, ymd, ymdIso } from '../lib/format'
import { fetchDays, useStore } from '../lib/store'
import type { DayRec } from '../lib/types'

type Range = 'month' | '7' | '30' | 'year' | 'custom'

export default function EnergyPage() {
  const live = useStore((s) => s.live)
  const tariff = useStore((s) => s.info?.tariff ?? 0)
  const today = live?.today.date || ymd(new Date())
  const [days, setDays] = useState<DayRec[] | null>(null)
  const [range, setRange] = useState<Range>('month')
  const [from, setFrom] = useState(addDays(today, -13))
  const [to, setTo] = useState(today)
  const [hidden, toggle] = useHidden()

  useEffect(() => {
    let alive = true
    const load = () => fetchDays().then((d) => alive && d && setDays(d))
    load()
    const i = setInterval(load, 5 * 60_000)
    return () => {
      alive = false
      clearInterval(i)
    }
  }, [])

  // merge the finished days with today's running totals
  const byDate = useMemo(() => {
    const m = new Map<number, DayRec>()
    days?.forEach((d) => m.set(d.date, d))
    const t = live?.today
    if (t?.date) m.set(t.date, { date: t.date, pv: t.pv, load: t.load, grid: t.grid, chg: t.chg, dis: t.dis, pvPeak: t.pvPeak, loadPeak: t.loadPeak, gridOnMin: t.gridOnMin, onlineMin: t.onlineMin, battMin: 0, battMax: 0, tempMax: 0, outages: t.outages })
    return m
  }, [days, live?.today])

  const [start, end] = useMemo((): [number, number] => {
    const d = fromYmd(today)
    if (range === 'month') return [ymd(new Date(d.getFullYear(), d.getMonth(), 1)), today]
    if (range === '7') return [addDays(today, -6), today]
    if (range === '30') return [addDays(today, -29), today]
    if (range === 'year') return [addDays(today, -364), today]
    return from <= to ? [from, to] : [to, from]
  }, [range, today, from, to])

  const keys: number[] = []
  for (let k = start; k <= end; k = addDays(k, 1)) keys.push(k)
  const rows = keys.map((k) => byDate.get(k))
  const have = rows.filter((r): r is DayRec => !!r)
  const sum = (f: (r: DayRec) => number) => have.reduce((a, r) => a + f(r), 0)
  const tot = { pv: sum((r) => r.pv), load: sum((r) => r.load), grid: sum((r) => r.grid), on: sum((r) => r.gridOnMin), mon: sum((r) => r.onlineMin), out: sum((r) => r.outages), chg: sum((r) => r.chg), dis: sum((r) => r.dis) }

  // monthly buckets for long ranges, daily otherwise
  const monthly = keys.length > 62
  const buckets = useMemo(() => {
    if (!monthly) return keys.map((k, i) => ({ label: fromYmd(k).toLocaleDateString(undefined, keys.length > 14 ? { day: 'numeric' } : { weekday: 'short', day: 'numeric' }), title: dayLabel(k), rows: rows[i] ? [rows[i]!] : [] }))
    const m = new Map<string, { label: string; title: string; rows: DayRec[] }>()
    keys.forEach((k, i) => {
      const d = fromYmd(k)
      const id = `${d.getFullYear()}-${d.getMonth()}`
      if (!m.has(id)) m.set(id, { label: d.toLocaleDateString(undefined, { month: 'short' }), title: d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' }), rows: [] })
      if (rows[i]) m.get(id)!.rows.push(rows[i]!)
    })
    return [...m.values()]
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [byDate, start, end])
  const val = (f: (r: DayRec) => number) => buckets.map((b) => (b.rows.length ? b.rows.reduce((a, r) => a + f(r), 0) : null))
  const series: Series[] = [
    { key: 'self', name: 'Home from solar/battery', color: 'var(--batt)', values: val((r) => Math.max(0, r.load - r.grid)) },
    { key: 'grid', name: 'Home from grid', color: 'var(--grid)', values: val((r) => r.grid) },
    { key: 'pv', name: 'Solar produced', color: 'var(--solar)', values: val((r) => r.pv) },
  ]

  const monthStart = ymd(new Date(fromYmd(today).getFullYear(), fromYmd(today).getMonth(), 1))
  const month = [...byDate.values()].filter((r) => r.date >= monthStart && r.date <= today)
  const mPv = month.reduce((a, r) => a + r.pv, 0)
  const mGrid = month.reduce((a, r) => a + r.grid, 0)
  const self = tot.load > 0 ? Math.round(Math.max(0, Math.min(1, 1 - tot.grid / tot.load)) * 100) : null

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <section className="grid gap-4 md:grid-cols-3">
        <Card className="relative overflow-hidden md:col-span-2">
          <span className="pointer-events-none absolute -right-10 -top-16 size-56 rounded-full bg-solar opacity-20 blur-3xl" />
          <p className="text-sm font-medium text-text-2">Solar units made this month</p>
          <div className="mt-1 flex flex-wrap items-end gap-x-6 gap-y-2">
            <Value text={`${fmtUnits(mPv)} units`} className="text-5xl tracking-tight sm:text-6xl" unitClass="text-[0.35em]" />
            {tariff > 0 && <div className="pb-2"><p className="text-xs text-text-3">saved at Rs {tariff}/unit</p><Value text={fmtPkr((mPv / 1000) * tariff)} className="text-2xl text-good" /></div>}
          </div>
          <p className="mt-2 text-sm text-text-2">
            {fromYmd(today).toLocaleDateString(undefined, { month: 'long' })} so far · grid ≈ <span className="num font-semibold text-text">{fmtUnits(mGrid)}</span> units (estimated)
          </p>
        </Card>
        <Card>
          <p className="text-sm font-medium text-text-2">Self-powered ({have.length} day{have.length === 1 ? '' : 's'})</p>
          <Value text={self == null ? '–' : `${self} %`} className="mt-1 block text-5xl" />
          <div className="mt-3 h-2 overflow-hidden rounded-full bg-grid/40"><div className="h-full rounded-full bg-batt transition-[width] duration-700" style={{ width: `${self ?? 0}%` }} /></div>
          <p className="mt-2 text-xs text-text-3">Share of home energy that did not come from the grid.</p>
        </Card>
      </section>

      {days && <BillCard byDate={byDate} today={today} />}

      <Card className="flex flex-wrap items-center gap-3 !py-3">
        <Segmented label="Range" value={range} onChange={setRange} className="flex-wrap"
          options={[{ value: 'month', label: 'Month' }, { value: '7', label: '7 days' }, { value: '30', label: '30 days' }, { value: 'year', label: 'Year' }, { value: 'custom', label: 'Custom' }]} />
        {range === 'custom' && (
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <input type="date" aria-label="From" className="focus-ring min-h-10 rounded-2xl border border-border bg-surface-2 px-3" value={ymdIso(from)} max={ymdIso(today)} onChange={(e) => e.target.value && setFrom(isoToYmd(e.target.value))} />
            <span className="text-text-3">to</span>
            <input type="date" aria-label="To" className="focus-ring min-h-10 rounded-2xl border border-border bg-surface-2 px-3" value={ymdIso(to)} max={ymdIso(today)} onChange={(e) => e.target.value && setTo(isoToYmd(e.target.value))} />
          </div>
        )}
      </Card>

      {!days && <Skeleton className="h-72" />}
      {days && !have.length && <Empty>No energy data in this range yet. Daily totals build up as the monitor runs.</Empty>}
      {days && have.length > 0 && (
        <>
          <ChartCard title={monthly ? 'Energy per month' : 'Energy per day'} sub={`${dayLabel(start)} – ${dayLabel(end)}`} height={270}
            legend={<Legend items={series} hidden={hidden} onToggle={toggle} />}
            render={(h) => (
              <BarChart labels={buckets.map((b) => b.label)} titles={buckets.map((b) => b.title)} series={series.filter((s) => !hidden.has(s.key))}
                stacks={[['self', 'grid'], ['pv']]} height={h} valueFmt={fmtWh}
                yFmt={(v) => (v === 0 ? '0' : v >= 1000 ? `${+(v / 1000).toFixed(1)} kWh` : `${Math.round(v)} Wh`)} />
            )} />
          <Card>
            <CardHeader title="Totals" sub={`${have.length} day${have.length === 1 ? '' : 's'} with data`} />
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Stat tone="solar" label="Solar produced" value={fmtWh(tot.pv)} hint={`${fmtUnits(tot.pv)} units`} />
              <Stat tone="load" label="Home used" value={fmtWh(tot.load)} />
              <Stat tone="grid" label="From grid (est.)" value={fmtWh(tot.grid)} hint={`${fmtUnits(tot.grid)} units`} />
              <Stat tone="batt" label="Battery in / out" value={fmtWh(tot.chg)} hint={`out ${fmtWh(tot.dis)}`} />
              <Stat label="Grid available" value={tot.mon ? `${Math.round((tot.on / tot.mon) * 100)} %` : '–'} hint={tot.mon ? `${fmtDuration(tot.on)} of ${fmtDuration(tot.mon)}` : undefined} />
              <Stat label="Grid outages" value={String(tot.out)} />
              <Stat label="Saved by solar" value={tariff ? fmtPkr((tot.pv / 1000) * tariff) : '–'} />
              <Stat label="Average solar / day" value={fmtWh(tot.pv / have.length)} />
            </div>
          </Card>
          <DailyTable rows={keys.map((k, i) => [k, rows[i]] as const).filter((x): x is readonly [number, DayRec] => !!x[1]).reverse().slice(0, 62)} today={today} />
        </>
      )}
    </div>
  )
}

/** One row per day in units (kWh), newest first. */
function DailyTable({ rows, today }: { rows: (readonly [number, DayRec])[]; today: number }) {
  if (!rows.length) return null
  const u = (wh: number) => (wh / 1000).toFixed(wh >= 10000 ? 1 : 2)
  return (
    <Card>
      <CardHeader title="Daily units" sub="1 unit = 1 kWh, the same unit as your electricity bill" />
      <div className="-mx-1 overflow-x-auto">
        <table className="num w-full min-w-[520px] text-sm">
          <thead>
            <tr className="text-left text-xs text-text-3 [&_th]:px-1 [&_th]:pb-2 [&_th]:font-medium">
              <th>Day</th>
              <th className="text-right"><span className="mr-1 inline-block size-2 rounded-full bg-solar" />Solar made</th>
              <th className="text-right"><span className="mr-1 inline-block size-2 rounded-full bg-load" />Home used</th>
              <th className="text-right"><span className="mr-1 inline-block size-2 rounded-full bg-batt" />From solar + battery</th>
              <th className="text-right"><span className="mr-1 inline-block size-2 rounded-full bg-grid" />From grid</th>
              <th className="text-right">Outages</th>
            </tr>
          </thead>
          <tbody className="[&_td]:border-t [&_td]:border-border [&_td]:px-1 [&_td]:py-2">
            {rows.map(([k, r]) => (
              <tr key={k}>
                <td className="font-sans text-text-2">{k === today ? 'Today' : dayLabel(k)}</td>
                <td className="text-right">{u(r.pv)}</td>
                <td className="text-right">{u(r.load)}</td>
                <td className="text-right">{u(Math.max(0, r.load - r.grid))}</td>
                <td className="text-right font-semibold">{u(r.grid)}</td>
                <td className="text-right text-text-2">{r.outages || '–'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}
