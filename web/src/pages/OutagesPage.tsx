import { PlugZap } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { GridStrip } from '../components/charts/charts'
import { Card, CardHeader, Empty, Segmented, Skeleton, Stat } from '../components/ui/ui'
import { addDays, dayLabel, fmtDuration, fromYmd, hhmm, ymd } from '../lib/format'
import { findOutages, outageStats } from '../lib/outages'
import { fetchDay, useStore } from '../lib/store'
import type { MinRec } from '../lib/types'

export default function OutagesPage() {
  const today = useStore((s) => s.live?.today.date) || ymd(new Date())
  const histFrom = useStore((s) => s.info?.histFrom) || today
  const gridOn = useStore((s) => s.live?.gridOn)
  const [span, setSpan] = useState<number>(7)
  const [data, setData] = useState<Map<number, MinRec[]>>(new Map())
  const [loading, setLoading] = useState(true)

  const days = useMemo(() => {
    const out: number[] = []
    for (let k = today; out.length < span && k >= histFrom; k = addDays(k, -1)) out.push(k)
    return out
  }, [today, histFrom, span])

  useEffect(() => {
    let alive = true
    setLoading(true)
    ;(async () => {
      const next = new Map<number, MinRec[]>()
      const queue = [...days]
      // two requests at a time: the ESP32 is small
      await Promise.all([0, 1].map(async () => {
        while (queue.length) {
          const k = queue.shift()!
          const r = await fetchDay(k, k === today)
          if (r) next.set(k, r)
          if (alive) setData(new Map(next))
        }
      }))
      if (alive) setLoading(false)
    })()
    return () => { alive = false }
  }, [days, today, gridOn])

  const all = useMemo(() => days.slice().reverse().flatMap((k) => data.get(k) ?? []), [days, data])
  const events = useMemo(() => findOutages(all), [all])
  const stats = useMemo(() => outageStats(all, events), [all, events])
  const maxHour = Math.max(1, ...stats.byHour)

  return (
    <div className="grid gap-4">
      <Card className="flex flex-wrap items-center gap-3 !py-3">
        <div className="flex items-center gap-2 text-sm font-semibold"><PlugZap size={18} className="text-grid" /> Load-shedding log</div>
        <div className="flex-1" />
        <Segmented label="Period" value={span} onChange={setSpan} options={[{ value: 1, label: 'Today' }, { value: 7, label: '7 days' }, { value: 14, label: '14 days' }, { value: 31, label: 'All' }]} />
      </Card>

      {loading && !all.length && <Skeleton className="h-60" />}
      {!loading && !all.length && <Empty>No history yet for this period.</Empty>}
      {all.length > 0 && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
            <Stat tone="grid" label="Outages" value={String(stats.count)} hint={`${days.length} day${days.length > 1 ? 's' : ''}`} />
            <Stat label="Total time without grid" value={fmtDuration(stats.totalMin)} />
            <Stat label="Longest" value={fmtDuration(stats.longestMin)} />
            <Stat label="Average" value={stats.count ? fmtDuration(stats.averageMin) : '–'} />
            <Stat label="Grid available" value={stats.monitoredMin ? `${Math.round(100 - (stats.totalMin / stats.monitoredMin) * 100)} %` : '–'} hint={`of ${fmtDuration(stats.monitoredMin)} monitored`} />
          </div>

          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
            <Card>
              <CardHeader title="When does the grid go off?" sub="minutes without grid, by hour of day" />
              <div className="grid grid-cols-12 gap-1.5" role="img" aria-label="Heat map of outage minutes by hour">
                {stats.byHour.map((m, h) => (
                  <div key={h} className="group relative">
                    <div className="aspect-square rounded-lg ring-1 ring-border" title={`${String(h).padStart(2, '0')}:00 – ${m} min`}
                      style={{ background: m ? `color-mix(in srgb, var(--grid) ${Math.round(18 + 82 * (m / maxHour))}%, var(--surface-2))` : 'var(--surface-2)' }} />
                    <div className="num mt-0.5 text-center text-[10px] text-text-3">{h}</div>
                  </div>
                ))}
              </div>
              <div className="mt-3 flex items-center gap-2 text-xs text-text-3">
                <span>less</span>
                <div className="h-2 flex-1 rounded-full" style={{ background: 'linear-gradient(90deg, var(--surface-2), var(--grid))' }} />
                <span>more</span>
              </div>
            </Card>

            <Card>
              <CardHeader title="Timeline" sub="pink = grid available" />
              <div className="grid gap-2.5">
                {days.map((k) => (
                  <div key={k} className="grid grid-cols-[72px_minmax(0,1fr)] items-start gap-3">
                    <span className="pt-0.5 text-xs font-medium text-text-2">{k === today ? 'Today' : dayLabel(k, { weekday: 'short', day: 'numeric' })}</span>
                    {data.get(k) ? <GridStrip recs={data.get(k)!} dayStart={fromYmd(k).getTime()} /> : <Skeleton className="h-[22px]" />}
                  </div>
                ))}
              </div>
            </Card>
          </div>

          <Card>
            <CardHeader title="Events" sub="newest first · times are when the monitor saw the change" />
            {!events.length && <Empty>No outages in this period.</Empty>}
            <div className="grid gap-2">
              {events.slice().reverse().map((o) => (
                <div key={o.start} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-3 rounded-2xl bg-surface-2 px-4 py-3">
                  <div className="min-w-0">
                    <div className="num text-sm font-semibold">
                      {o.startKnown ? hhmm(o.start) : `before ${hhmm(o.start)}`} → {o.ongoing ? <span className="text-crit">still off</span> : o.endKnown ? hhmm(o.end) : 'monitor offline'}
                    </div>
                    <div className="truncate text-xs text-text-3">{dayLabel(ymd(new Date(o.start)), { weekday: 'long', day: 'numeric', month: 'short' })}{ymd(new Date(o.end)) !== ymd(new Date(o.start)) ? ' (past midnight)' : ''}</div>
                  </div>
                  <span className="num rounded-full bg-grid/15 px-3 py-1 text-sm font-semibold text-text">{fmtDuration(o.minutes)}</span>
                </div>
              ))}
            </div>
          </Card>
        </>
      )}
    </div>
  )
}
