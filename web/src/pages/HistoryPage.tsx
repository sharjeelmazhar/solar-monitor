import { ChevronLeft, ChevronRight, Download } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { GridStrip, Legend, TimeChart, useHidden, type Series } from '../components/charts/charts'
import { Button, Card, CardHeader, ChartCard, Empty, IconButton, Skeleton, Stat } from '../components/ui/ui'
import { addDays, dayLabel, fmtDuration, fmtW, fmtWh, fromYmd, hhmm, isoToYmd, ymd, ymdIso } from '../lib/format'
import { findOutages } from '../lib/outages'
import { fetchDay, useStore } from '../lib/store'
import type { MinRec } from '../lib/types'

const kfmt = (v: number) => (Math.abs(v) >= 1000 ? (v / 1000).toFixed(1) + 'k' : String(Math.round(v)))

export default function HistoryPage() {
  const today = useStore((s) => s.live?.today.date) || ymd(new Date())
  const histFrom = useStore((s) => s.info?.histFrom) || today
  const [date, setDate] = useState(today)
  const [recs, setRecs] = useState<MinRec[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [hidden, toggle] = useHidden()

  useEffect(() => {
    let alive = true
    setRecs(null)
    setFailed(false)
    const load = async () => {
      const r = await fetchDay(date, date === today)
      if (!alive) return
      if (r) setRecs(r)
      else setFailed(true)
    }
    load()
    const i = date === today ? setInterval(load, 60_000) : 0
    return () => {
      alive = false
      clearInterval(i)
    }
  }, [date, today])

  const d0 = fromYmd(date).getTime()
  const xs = useMemo(() => recs?.map((r) => r.t) ?? [], [recs])
  const power: Series[] = useMemo(() => [
    { key: 'pv', name: 'Solar', color: 'var(--solar)', values: recs?.map((r) => r.pv) ?? [], area: true },
    { key: 'load', name: 'Home', color: 'var(--load)', values: recs?.map((r) => r.load) ?? [] },
    { key: 'batt', name: 'Battery (+ in / − out)', color: 'var(--batt)', values: recs?.map((r) => r.batt) ?? [] },
    { key: 'grid', name: 'Grid (est.)', color: 'var(--grid)', values: recs?.map((r) => r.grid) ?? [] },
  ], [recs])
  const totals = useMemo(() => {
    const r = recs ?? []
    return {
      pv: r.reduce((a, x) => a + x.pv / 60, 0), load: r.reduce((a, x) => a + x.load / 60, 0), grid: r.reduce((a, x) => a + x.grid / 60, 0),
      on: r.filter((x) => x.flags & 1).length, peak: r.reduce((a, x) => Math.max(a, x.pv), 0), peakLoad: r.reduce((a, x) => Math.max(a, x.load), 0),
      temp: r.reduce((a, x) => Math.max(a, x.temp), -99), socMin: r.reduce((a, x) => Math.min(a, x.soc), 100), socMax: r.reduce((a, x) => Math.max(a, x.soc), 0),
    }
  }, [recs])
  const outages = useMemo(() => (recs ? findOutages(recs) : []), [recs])

  const csv = () => {
    if (!recs?.length) return
    const rows = ['time,solar_W,home_W,grid_W_est,battery_W,battery_pct,battery_V,pv_V,grid_V,output_V,inverter_C,mode,grid_present']
    for (const r of recs) rows.push([`${ymdIso(date)} ${hhmm(r.t)}`, r.pv, r.load, r.grid, r.batt, r.soc, r.battV, r.pvV, r.gridV, r.outV, r.temp, r.mode, r.flags & 1].join(','))
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([rows.join('\n')], { type: 'text/csv' }))
    a.download = `solar-${ymdIso(date)}.csv`
    a.click()
    setTimeout(() => URL.revokeObjectURL(a.href), 1000)
  }

  return (
    <div className="grid gap-4">
      <Card className="flex flex-wrap items-center gap-2 !py-3">
        <IconButton label="Previous day" disabled={date <= histFrom} onClick={() => setDate(addDays(date, -1))}><ChevronLeft size={18} /></IconButton>
        <label className="relative flex min-h-10 flex-1 items-center justify-center rounded-2xl bg-surface-2 px-3 text-sm font-semibold sm:flex-none sm:min-w-64">
          <span>{date === today ? 'Today · ' : ''}{dayLabel(date, { weekday: 'long', day: 'numeric', month: 'long' })}</span>
          <input type="date" aria-label="Pick a day" className="absolute inset-0 cursor-pointer opacity-0" value={ymdIso(date)} min={ymdIso(histFrom)} max={ymdIso(today)}
            onChange={(e) => e.target.value && setDate(isoToYmd(e.target.value))} />
        </label>
        <IconButton label="Next day" disabled={date >= today} onClick={() => setDate(addDays(date, 1))}><ChevronRight size={18} /></IconButton>
        <div className="flex-1" />
        <Button onClick={csv} disabled={!recs?.length}><Download size={16} /> CSV</Button>
      </Card>

      {!recs && !failed && <Skeleton className="h-72" />}
      {failed && <Empty>Couldn't load this day. Check that you are on the home Wi-Fi.</Empty>}
      {recs && !recs.length && <Empty>No history was recorded for {dayLabel(date)}.</Empty>}
      {recs && recs.length > 0 && (
        <>
          <ChartCard title="Power" sub="one-minute averages" legend={<Legend items={power} hidden={hidden} onToggle={toggle} />} height={260}
            render={(h) => <TimeChart xs={xs} series={power.filter((s) => !hidden.has(s.key))} x0={d0} x1={d0 + 864e5} height={h} gapMs={5 * 60_000} yFmt={kfmt} valueFmt={fmtW} />} />
          <div className="grid gap-4 lg:grid-cols-2">
            <ChartCard title="Battery charge" sub={`${totals.socMin}–${totals.socMax} % during the day`} height={170}
              render={(h) => (
                <TimeChart xs={xs} series={[{ key: 'soc', name: 'Battery', color: 'var(--batt)', values: recs.map((r) => r.soc), area: true }]} x0={d0} x1={d0 + 864e5} height={h}
                  yMin={0} yMax={100} gapMs={5 * 60_000} yFmt={(v) => v + '%'} valueFmt={(v) => v + ' %'} titleFmt={(t, i) => `${hhmm(t)} · ${recs[i].battV.toFixed(2)} V`} />
              )} />
            <Card>
              <CardHeader title="Grid availability" sub={`${fmtDuration(totals.on)} of ${fmtDuration(recs.length)} monitored · ${outages.length} outage${outages.length === 1 ? '' : 's'}`} />
              <GridStrip recs={recs} dayStart={d0} />
              <div className="mt-3 grid max-h-40 gap-1.5 overflow-y-auto text-sm">
                {outages.map((o) => (
                  <div key={o.start} className="num flex justify-between rounded-xl bg-surface-2 px-3 py-2">
                    <span>{o.startKnown ? hhmm(o.start) : 'before ' + hhmm(o.start)} → {o.ongoing ? 'now' : o.endKnown ? hhmm(o.end) : 'unknown'}</span>
                    <span className="text-text-2">{fmtDuration(o.minutes)}</span>
                  </div>
                ))}
              </div>
            </Card>
          </div>
          <Card>
            <CardHeader title="Day totals" sub={dayLabel(date)} />
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
              <Stat tone="solar" label="Solar" value={fmtWh(totals.pv)} />
              <Stat tone="load" label="Home" value={fmtWh(totals.load)} />
              <Stat tone="grid" label="Grid (est.)" value={fmtWh(totals.grid)} />
              <Stat label="Peak solar" value={fmtW(totals.peak)} />
              <Stat label="Peak load" value={fmtW(totals.peakLoad)} />
              <Stat label="Max inverter temp" value={`${totals.temp} °C`} />
            </div>
          </Card>
        </>
      )}
    </div>
  )
}
