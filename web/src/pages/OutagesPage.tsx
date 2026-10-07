import { ChevronLeft, ChevronRight, PlugZap, Zap, ZapOff } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { DayClock } from '../components/charts/DayClock'
import { GridStrip } from '../components/charts/charts'
import { Card, CardHeader, Empty, IconButton, Segmented, Skeleton, Stat, cn } from '../components/ui/ui'
import { addDays, dayLabel, fmtDuration, fmtWh, fromYmd, hhmm, hourLabel, ymd } from '../lib/format'
import { duringOutage, findOutages, outageStats, type Outage } from '../lib/outages'
import { fetchDay, useStale, useStore } from '../lib/store'
import type { MinRec } from '../lib/types'

export default function OutagesPage() {
  const today = useStore((s) => s.live?.today.date) || ymd(new Date())
  const histFrom = useStore((s) => s.info?.histFrom) || today
  const gridOn = useStore((s) => s.live?.gridOn)
  const nowT = useStore((s) => s.live?.t) || Date.now()
  const [span, setSpan] = useState<number>(7)
  const [data, setData] = useState<Map<number, MinRec[]>>(new Map())
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const stale = useStale()
  const [clockDay, setClockDay] = useState(today)
  const [showAll, setShowAll] = useState(false)

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
      let failedAny = false
      // two requests at a time: the ESP32 is small
      await Promise.all([0, 1].map(async () => {
        while (queue.length) {
          const k = queue.shift()!
          const r = await fetchDay(k, k === today)
          if (r) next.set(k, r)
          else failedAny = true
          if (alive) setData(new Map(next))
        }
      }))
      if (alive) { setLoading(false); setFailed(failedAny) }
    })()
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [days, today, gridOn, Math.floor(nowT / 300_000)]) // today again every 5 min

  const all = useMemo(() => days.slice().reverse().flatMap((k) => data.get(k) ?? []), [days, data])
  const events = useMemo(() => findOutages(all), [all])
  const stats = useMemo(() => outageStats(all, events), [all, events])
  const maxHour = Math.max(1, ...stats.byHour)
  const current = events.length && events[events.length - 1].ongoing ? events[events.length - 1] : null

  // newest first, grouped by the day the outage started
  // newest first; only the latest few until "Show all" so the list sits level with the clock
  const SHORT = 5
  const byDay = useMemo(() => {
    const m = new Map<number, Outage[]>()
    for (const o of events.slice().reverse().slice(0, showAll ? undefined : SHORT)) {
      const k = ymd(new Date(o.start))
      if (!m.has(k)) m.set(k, [])
      m.get(k)!.push(o)
    }
    return [...m.entries()]
  }, [events, showAll])

  const clockIdx = days.indexOf(clockDay)
  const clockRecs = data.get(clockDay)

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <Card className="flex flex-wrap items-center gap-3 !py-3">
        <div className="flex items-center gap-2 text-sm font-semibold"><PlugZap size={18} className="text-grid" /> Grid outages (load-shedding)</div>
        <div className="flex-1" />
        <Segmented label="Period" value={span} onChange={setSpan} options={[{ value: 1, label: 'Today' }, { value: 7, label: '7 days' }, { value: 14, label: '14 days' }, { value: 31, label: 'All' }]} />
      </Card>

      {gridOn != null && stale == null && <NowBanner on={gridOn} current={current} now={nowT} />}

      {loading && !all.length && <Skeleton className="h-60" />}
      {!loading && !all.length && <Empty>{failed ? 'Could not load history from the monitor. Is it on and connected to Wi-Fi?' : 'No history yet for this period.'}</Empty>}
      {all.length > 0 && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
            <Stat tone="grid" label="Outages" value={String(stats.count)} hint={span === 1 ? 'today' : `last ${days.length} day${days.length > 1 ? 's' : ''}`} />
            <Stat label="Time without grid" value={fmtDuration(stats.totalMin)} />
            <Stat label="Longest" value={stats.count ? fmtDuration(stats.longestMin) : '–'} />
            <Stat label="Average outage" value={stats.count ? fmtDuration(stats.averageMin) : '–'} />
            <Stat className="col-span-2 lg:col-span-1" label="Grid available" value={stats.monitoredMin ? `${Math.round(100 - (stats.totalMin / stats.monitoredMin) * 100)} %` : '–'} hint={`of ${fmtDuration(stats.monitoredMin)} monitored`} />
          </div>

          <div className={cn('grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]', showAll && 'items-start')}>
            {/* the clock stays in view while the list scrolls next to it */}
            <Card className={showAll ? 'lg:sticky lg:top-24' : 'flex flex-col'}>
              <CardHeader
                title="Day clock" info={<><p>Each slice is one hour. Its colours show what powered the home: <b>yellow</b> solar, <b>green</b> battery, <b>pink</b> grid. Longer slices mean more energy used.</p><p>The outer ring shows the grid: pink when available, <b>red stripes</b> when it was off.</p><p>Tap a slice or a red part for details.</p></>}
                sub={clockDay === today ? 'Today · midnight at the top' : dayLabel(clockDay, { weekday: 'long', day: 'numeric', month: 'short' })}
                action={
                  <div className="flex gap-1">
                    <IconButton label="Previous day" disabled={clockIdx < 0 || clockIdx >= days.length - 1} onClick={() => setClockDay(days[clockIdx + 1])}><ChevronLeft size={18} /></IconButton>
                    <IconButton label="Next day" disabled={clockIdx <= 0} onClick={() => setClockDay(days[clockIdx - 1])}><ChevronRight size={18} /></IconButton>
                  </div>
                }
              />
              <div className="flex flex-1 flex-col justify-center">
              {clockRecs ? (
                  <DayClock key={clockDay} recs={clockRecs} dayStart={fromYmd(clockDay).getTime()} outages={events} now={clockDay === today ? nowT : undefined} />
                ) : (
                  <Skeleton className="mx-auto aspect-square w-full max-w-[380px] rounded-full" />
                )}
              </div>
            </Card>

            <Card>
              <CardHeader title="What happened" sub="each card is one time the grid went off, newest first" />
              {!events.length && <Empty>No outages in this period.</Empty>}
              <div className="grid gap-4">
                {byDay.map(([k, list]) => (
                  <section key={k}>
                    <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-3">
                      {k === today ? 'Today' : k === addDays(today, -1) ? 'Yesterday' : dayLabel(k, { weekday: 'long', day: 'numeric', month: 'short' })}
                    </h3>
                    <div className="grid gap-2">
                      {list.map((o) => <EventCard key={o.start} o={o} recs={all} onShow={() => days.includes(k) && setClockDay(k)} />)}
                    </div>
                  </section>
                ))}
              </div>
              {events.length > SHORT && (
                <button onClick={() => setShowAll(!showAll)} className="focus-ring mt-3 flex min-h-11 w-full items-center justify-center gap-1 rounded-2xl bg-surface-2 text-sm font-medium text-text-2 hover:bg-surface-3 hover:text-text">
                  {showAll ? 'Show fewer' : `Show all ${events.length} outages`}
                </button>
              )}
            </Card>
          </div>

          {span > 1 && (
            // one card, two halves: the hour heat map and a strip per day (days only as many as the monitor has)
            <Card>
              <div className="grid gap-x-10 gap-y-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
                <div>
                  <CardHeader title="Usual outage hours" sub={`minutes without grid by hour, ${days.length === 1 ? 'today' : `last ${days.length} days`}`} />
                  <div className="grid grid-cols-12 gap-1.5" role="img" aria-label="Heat map of outage minutes by hour of day">
                    {stats.byHour.map((m, h) => (
                      <div key={h}>
                        <div className="aspect-square rounded-lg" title={`${hourLabel(h)}: ${m} min without grid`}
                          style={{ background: m ? `color-mix(in srgb, var(--crit) ${Math.round(18 + 72 * (m / maxHour))}%, var(--surface-2))` : 'var(--surface-2)' }} />
                        <div className="num mt-0.5 text-center text-[10px] text-text-3">{h % 3 === 0 ? hourLabel(h).replace(' ', '') : ''}</div>
                      </div>
                    ))}
                  </div>
                  <div className="mt-3 flex items-center gap-2 text-xs text-text-3">
                    <span>none</span>
                    <div className="h-2 flex-1 rounded-full" style={{ background: 'linear-gradient(90deg, var(--surface-2), var(--crit))' }} />
                    <span>most</span>
                  </div>
                </div>
                <div className="lg:border-l lg:border-border lg:pl-10">
                  <CardHeader title="Day by day" sub={`pink = grid on · red = grid off${days.length < span ? ` · the monitor has ${days.length} day${days.length === 1 ? '' : 's'} so far` : ''}`} />
                  <div className="grid gap-2.5">
                    {days.map((k) => (
                      <button key={k} onClick={() => setClockDay(k)} className={cn('focus-ring grid grid-cols-[72px_minmax(0,1fr)] items-start gap-3 rounded-xl p-1 text-left', k === clockDay && 'bg-surface-2')}>
                        <span className="pt-0.5 text-xs font-medium text-text-2">{k === today ? 'Today' : dayLabel(k, { weekday: 'short', day: 'numeric' })}</span>
                        {data.get(k) ? <GridStrip recs={data.get(k)!} dayStart={fromYmd(k).getTime()} /> : <Skeleton className="h-[22px]" />}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            </Card>
          )}
        </>
      )}
    </div>
  )
}

function NowBanner({ on, current, now }: { on: boolean; current: Outage | null; now: number }) {
  return (
    <div className={cn('flex items-center gap-3 rounded-3xl border px-4 py-3', on ? 'border-border bg-surface' : 'border-crit/40 bg-crit/10')} role="status">
      <span className={cn('grid size-10 shrink-0 place-items-center rounded-2xl', on ? 'bg-grid/15 text-grid' : 'bg-crit/15 text-crit')}>
        {on ? <Zap size={20} /> : <ZapOff size={20} />}
      </span>
      <div className="min-w-0">
        <div className="font-semibold">{on ? 'Grid is ON right now' : 'Grid is OFF right now'}</div>
        <div className="text-sm text-text-2">
          {on ? 'WAPDA supply is available.' : current ? `Off since ${hhmm(current.start)} · ${fmtDuration((now - current.start) / 60_000)} so far. The home runs on solar and battery.` : 'The home runs on solar and battery.'}
        </div>
      </div>
    </div>
  )
}

function EventCard({ o, recs, onShow }: { o: Outage; recs: MinRec[]; onShow: () => void }) {
  const d = useMemo(() => duringOutage(recs, o), [recs, o])
  const crossesMidnight = ymd(new Date(o.end - 1)) !== ymd(new Date(o.start))
  return (
    <button onClick={onShow} className="focus-ring grid grid-cols-[minmax(0,1fr)_auto] gap-3 rounded-2xl bg-surface-2 px-4 py-3 text-left hover:bg-surface-3">
      <div className="relative grid gap-2 pl-5">
        <span className="absolute bottom-2 left-[5px] top-2 w-0.5 rounded-full bg-crit/40" />
        <Line dot={o.ongoing ? 'var(--text-3)' : 'var(--grid)'} label={o.ongoing ? 'Still off' : 'Came back'}
          time={o.ongoing ? `${fmtDuration((Date.now() - o.start) / 60_000)} so far` : o.endKnown ? hhmm(o.end) + (crossesMidnight ? ' (next day)' : '') : 'unknown'}
          note={!o.ongoing && !o.endKnown ? `monitor was offline after ${hhmm(o.end)}` : undefined} />
        <Line dot="var(--crit)" label="Went off" time={o.startKnown ? hhmm(o.start) : `before ${hhmm(o.start)}`}
          note={o.startKnown ? undefined : 'already off when the monitor started'} />
        {d && (
          <p className="text-xs text-text-3">
            Home used {fmtWh(d.homeWh)}{d.solarWh > 1 ? ` · solar made ${fmtWh(d.solarWh)}` : ''} · battery {d.socFrom}% → {d.socTo}%
          </p>
        )}
      </div>
      <span className={cn('num self-start rounded-full px-3 py-1 text-sm font-semibold', o.ongoing ? 'bg-crit/15 text-crit' : 'bg-grid/15 text-text')}>
        {fmtDuration(o.minutes)}
      </span>
    </button>
  )
}

function Line({ dot, label, time, note }: { dot: string; label: string; time: string; note?: string }) {
  return (
    <div className="relative">
      <span className="absolute -left-5 top-1.5 size-3 rounded-full ring-2 ring-surface-2" style={{ background: dot }} />
      <div className="flex flex-wrap items-baseline gap-x-2">
        <span className="text-sm text-text-2">{label}</span>
        <span className="num text-sm font-semibold">{time}</span>
      </div>
      {note && <div className="text-xs text-text-3">{note}</div>}
    </div>
  )
}
