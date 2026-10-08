import { AlertTriangle, CheckCircle2, Info as InfoIcon, WifiOff, XCircle } from 'lucide-react'
import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import { Legend, TimeChart, useHidden, type Series } from '../components/charts/charts'
import { FlowDiagram } from '../components/flow/FlowDiagram'
import { Card, CardHeader, ChartCard, Segmented, Stat, Value, cn } from '../components/ui/ui'
import { activeWarnings, modeOf, parseRated, SEVERE, WARNINGS } from '../lib/decode'
import { fmtDuration, fmtUnits, fmtW, fmtWh, hhmm, hhmmss } from '../lib/format'
import { use3d, useBattIdle } from '../lib/prefs'
import { battAmps, battState, sourcesLabel, sourcesSentence, weakSolar } from '../lib/power'
import { sunPhase, sunTimes } from '../lib/sun'
import { currentWeather, skyWords } from '../lib/weather'
import { useStale, useStore } from '../lib/store'
import type { Info, Live } from '../lib/types'

const EnergyCore3D = lazy(() => import('../components/flow/EnergyCore3D'))

export default function OverviewPage({ dark }: { dark: boolean }) {
  const d = useStore((s) => s.live)
  const info = useStore((s) => s.info)
  const rated = useMemo(() => parseRated(info?.inv.qpiri), [info?.inv.qpiri])
  const ratedW = rated?.outW || 3200
  const [fx3d] = use3d()
  const stale = useStale()
  const [, , idleW] = useBattIdle()
  const offline = !!d && stale != null
  const noBatt = rated?.battV === 0   // battery-less inverter (e.g. Galaxy Envy running on solar + grid only)
  const alerts = d?.ever && !offline ? alertsOf(d, idleW, noBatt) : []

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <section className="grid gap-4 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
        <Card className="relative overflow-hidden">
          <CardHeader
            title="Energy flow"
            sub={d?.ever ? sourcesSentence(d, idleW) : 'Waiting for the inverter…'}
            info={<FlowInfo />}
            action={d?.ever && <span className="rounded-full border border-border-strong bg-surface-2 px-3 py-1 text-xs font-semibold">{sourcesLabel(d, idleW)}</span>}
          />
          <div className="relative mx-auto max-w-[560px]">
            {offline && <OfflineBadge seconds={stale!} t={d!.t} />}
            <div className={cn('transition-[filter,opacity] duration-500', offline && 'pointer-events-none opacity-40 blur-[1.5px] grayscale')} aria-hidden={offline || undefined}>
            {fx3d && d?.ok && !offline && (
              <div className="absolute left-1/2 top-1/2 aspect-square w-[62%] -translate-x-1/2 -translate-y-1/2 opacity-80">
                <Suspense fallback={null}>
                  <EnergyCore3D key={dark ? 'd' : 'l'} solar={Math.min(1, d.pvW / ratedW)} load={Math.min(1, d.loadW / ratedW)} battery={d.battPct / 100} />
                </Suspense>
              </div>
            )}
            <div className="relative"><FlowDiagram d={d} ratedW={ratedW} still={offline} idleW={idleW} noBatt={noBatt} /></div>
            </div>
          </div>
        </Card>
        <div className={cn('grid transition-[filter,opacity] duration-500', offline && 'opacity-45 grayscale')}>
          <NowTiles d={d} info={info} battRatedV={rated?.battV} float={rated?.float} idleW={idleW} noBatt={noBatt} />
        </div>
      </section>

      {alerts.some((a) => a.level > 0) && <Alerts items={alerts.filter((a) => a.level > 0)} />}
      {d?.ever && <TodayCard d={d} />}
      <LiveChart />
    </div>
  )
}

/** Shown over the flow diagram when readings stop: nothing moves, last values greyed out. */
function OfflineBadge({ seconds, t }: { seconds: number; t: number }) {
  const ago = !Number.isFinite(seconds) ? '' : seconds < 90 ? `${Math.round(seconds)} s ago` : `${fmtDuration(seconds / 60)} ago`
  return (
    <div className="absolute inset-0 z-10 grid place-items-center" role="status">
      <div className="glass flex max-w-[86%] items-center gap-3 rounded-2xl px-4 py-3 text-sm shadow-lg">
        <WifiOff size={20} className="shrink-0 text-crit" />
        <div>
          <div className="font-semibold">Monitor not responding</div>
          <div className="text-xs text-text-2">Values below are from the last reading{t ? ` at ${hhmm(t)}` : ''}{ago ? ` (${ago})` : ''}. The monitor may be off or out of Wi-Fi range.</div>
        </div>
      </div>
    </div>
  )
}

function NowTiles({ d, info, battRatedV, float, idleW, noBatt }: { d: Live | null; info: Info | null; battRatedV?: number; float?: number; idleW: number; noBatt: boolean }) {
  if (!d?.ever) {
    return (
      <Card className="grid place-items-center text-sm text-text-2">
        <p>{d ? 'The monitor is online but the inverter is not answering yet.' : 'Connecting to the solar monitor…'}</p>
      </Card>
    )
  }
  const battTone = d.battPct <= 20 ? 'crit' : d.battPct <= 45 ? 'warn' : 'batt'
  const eta = battEta(d, info?.battAh ?? 0, battRatedV)
  const bs = battState(d, idleW)
  const charging = d.st[6] === '1' && d.st[7] === '1' ? 'Solar + grid' : d.st[6] === '1' ? 'From solar' : d.st[7] === '1' ? 'From grid' : 'Not charging'
  return (
    <Card>
      <CardHeader title="Right now" sub={<LiveRate />} />
      <div className="grid grid-cols-2 gap-3">
        <Tile tone="solar" label="Solar" value={fmtW(d.pvW)} lines={[...pvLines(d), `Peak today ${fmtW(d.today.pvPeak)}`, sunLine()]} />
        {noBatt ? <Tile tone="batt" label="Battery" value="None" small lines={['This inverter runs on solar', 'and grid only']} /> : <Tile tone="batt" label="Battery" value={`${d.battPct} %`}
          lines={[`${d.battV.toFixed(2)} V · ${Math.abs(battAmps(d)).toFixed(1)} A · ${bs === 'charging' ? 'charging ' + fmtW(d.battW) : bs === 'discharging' ? 'giving ' + fmtW(-d.battW) : 'idle'}`, eta ?? (info && !info.battAh ? 'Add battery Ah in System' : ' ')]}
          bar={d.battPct} barTone={battTone} />}
        <Tile tone="load" label="Home" value={fmtW(d.loadW)} lines={[`${d.loadPct}% load · ${d.loadVA} VA`, `${d.outV.toFixed(1)} V · ${d.outHz.toFixed(1)} Hz`]} bar={d.loadPct} />
        <Tile tone="grid" label="Grid (WAPDA)" value={d.gridOn ? `${Math.round(d.gridV)} V` : 'Off'}
          lines={d.gridOn ? [`${d.gridHz.toFixed(1)} Hz · available`, d.gridW > 15 ? `Importing ≈ ${fmtW(d.gridW)}` : 'Not in use'] : ['No grid supply', d.today.outages ? `${d.today.outages} outage${d.today.outages > 1 ? 's' : ''} today` : ' ']} />
        <Tile tone="inv" label="Inverter" value={`${d.tempC} °C`} lines={[sourcesSentence(d, idleW), `Mode: ${modeOf(d.mode).name} · DC bus ${d.busV} V`]} />
        {noBatt ? <Tile tone="text-3" label="Charging" value="—" small lines={['No battery to charge', ' ']} /> : <Tile tone="text-3" label="Charging" value={charging} small lines={[bs === 'charging' ? `${fmtW(d.battW)} · ${battAmps(d).toFixed(1)} A into battery` : ' ', float ? `Float ${float} V` : ' ']} />}
      </div>
    </Card>
  )
}

/** One line per solar input: "PV1 166 V · 1.3 A" and "PV2 …" on two-input inverters. */
function pvLines(d: Live): string[] {
  if (!d.pv2V) return [`${d.pvV.toFixed(1)} V · ${d.pvA.toFixed(1)} A`]
  return [`PV1 ${Math.round(d.pvV)} V · ${d.pvA.toFixed(1)} A · ${fmtW(d.pvV * d.pvA)}`, `PV2 ${Math.round(d.pv2V)} V · ${(d.pv2A ?? 0).toFixed(1)} A · ${fmtW(d.pv2V * (d.pv2A ?? 0))}`]
}

function Tile({ tone, label, value, lines, bar, barTone, small }: { tone: string; label: string; value: string; lines: string[]; bar?: number; barTone?: string; small?: boolean }) {
  return (
    <div className="relative min-w-0 overflow-hidden rounded-2xl bg-surface-2 p-3.5">
      <span className="pointer-events-none absolute -right-6 -top-6 size-20 rounded-full opacity-25 blur-2xl" style={{ background: `var(--${tone})` }} />
      <div className="flex items-center gap-2 text-[11px] font-semibold uppercase tracking-wider text-text-2">
        <span className="size-2 rounded-full" style={{ background: `var(--${tone})` }} />
        {label}
      </div>
      {small ? <div className="mt-1.5 text-lg font-semibold leading-snug">{value}</div> : <Value text={value} className="mt-1 block text-[26px] leading-tight" />}
      {lines.map((l, i) => <div key={i} className="num text-xs leading-snug text-text-2">{l}</div>)}
      {bar != null && (
        <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-surface-3">
          <div className="h-full rounded-full transition-[width] duration-700" style={{ width: `${Math.max(0, Math.min(100, bar))}%`, background: `var(--${barTone ?? tone})` }} />
        </div>
      )}
    </div>
  )
}

function LiveRate() {
  const ms = useStore((s) => s.intervalMs)
  return <span className="num">{ms ? `updating every ${(ms / 1000).toFixed(1)} s` : 'live'}</span>
}

function battEta(d: Live, ah: number, ratedV?: number) {
  if (!ah) return null
  const wh = ah * (ratedV || (d.battV > 40 ? 48 : d.battV > 20 ? 24 : 12))
  if (d.battW < -20) return `≈ ${fmtDuration((wh * d.battPct) / 100 / -d.battW * 60)} left`
  if (d.battW > 20 && d.battPct < 100) return `≈ ${fmtDuration((wh * (100 - d.battPct)) / 100 / d.battW * 60)} to full`
  return null
}

function TodayCard({ d }: { d: Live }) {
  const t = d.today
  const self = t.load > 1 ? Math.round(Math.max(0, Math.min(1, 1 - t.grid / t.load)) * 100) : null
  return (
    <Card>
      <CardHeader title="Today" sub={t.onlineMin ? `monitored ${fmtDuration(t.onlineMin)}` : undefined} />
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 xl:grid-cols-8">
        <Stat tone="solar" label="Solar produced" value={fmtWh(t.pv)} />
        <Stat tone="load" label="Home used" value={fmtWh(t.load)} />
        <Stat tone="grid" label="From grid (est.)" info="The inverter does not report grid power directly. The monitor works it out from the home load minus what solar and the battery supply, so treat it as a close estimate. Your IESCO meter is the final word." value={fmtWh(t.grid)} />
        <Stat tone="batt" label="Battery in / out" value={`${fmtWh(t.chg)}`} hint={`out ${fmtWh(t.dis)}`} />
        <Stat label="Self-powered" info="Share of the home's energy today that did not come from the grid (solar and battery)." value={self == null ? '–' : `${self} %`} />
        <Stat label="Grid available" value={t.onlineMin ? fmtDuration(t.gridOnMin) : '–'} />
        <Stat label="Grid outages" value={String(t.outages)} />
        <Stat tone="batt" label="From solar + battery" value={`${fmtUnits(Math.max(0, t.load - t.grid))} units`} hint="home use not from the grid" />
      </div>
    </Card>
  )
}

function LiveChart() {
  const recent = useStore((s) => s.recent)
  const [minutes, setMinutes] = useState(15)
  const [hidden, toggle] = useHidden()
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const i = setInterval(() => !document.hidden && setNow(Date.now()), 1000)
    return () => clearInterval(i)
  }, [])
  const from = now - minutes * 60_000
  const pts = recent.filter((s) => s.t >= from - 5000)
  const all: Series[] = [
    { key: 'pv', name: 'Solar', color: 'var(--solar)', values: pts.map((s) => s.pv), area: true },
    { key: 'load', name: 'Home', color: 'var(--load)', values: pts.map((s) => s.load) },
    { key: 'batt', name: 'Battery (+ in / − out)', color: 'var(--batt)', values: pts.map((s) => s.batt) },
    { key: 'grid', name: 'Grid (est.)', color: 'var(--grid)', values: pts.map((s) => s.grid) },
  ]
  return (
    <ChartCard
      title="Live power"
      sub="every reading from the inverter"
      action={<Segmented label="Time window" value={minutes} onChange={setMinutes} options={[{ value: 5, label: '5 min' }, { value: 15, label: '15 min' }]} />}
      legend={<Legend items={all} hidden={hidden} onToggle={toggle} />}
      render={(h) => (
        <TimeChart xs={pts.map((s) => s.t)} series={all.filter((s) => !hidden.has(s.key))} x0={from} x1={now} height={h}
          yFmt={(v) => (Math.abs(v) >= 1000 ? (v / 1000).toFixed(1) + 'k' : String(Math.round(v)))} valueFmt={fmtW} titleFmt={(t) => hhmmss(t)} />
      )}
    />
  )
}

export interface AlertItem { level: 0 | 1 | 2; text: string }

export function alertsOf(d: Live, idleW = 15, noBatt = false): AlertItem[] {
  const out: AlertItem[] = []
  if (!d.ok) out.push({ level: 2, text: `The inverter has not answered for a while (last error: ${d.poll.err || 'none'}). Check the cable to the inverter.` })
  if (d.mode === 'F') out.push({ level: 2, text: 'Inverter is in FAULT mode' })
  for (const i of activeWarnings(d.warn, noBatt)) out.push({ level: SEVERE.has(i) ? 2 : 1, text: WARNINGS[i] })
  if (d.ok && !noBatt && d.battPct <= 20 && d.battW < 0) out.push({ level: 1, text: `Battery is low (${d.battPct}%)` })
  if (d.tempC >= 60) out.push({ level: 1, text: `Inverter is hot (${d.tempC} °C)` })
  if (weakSolar(d, idleW)) out.push({ level: 1, text: `Little sun right now (${skyWords(currentWeather()) ?? 'cloudy?'}) and the battery is powering the home (${fmtW(-d.battW)}, battery ${d.battPct}%). Turn the grid on to save the battery.` })
  else if (!d.gridOn) {
    const ph = sunPhase()
    out.push({ level: 0, text: ph === 'day' ? 'Grid supply is off: running on solar and battery'
      : `Grid supply is off and the sun is ${ph === 'night' ? 'down' : 'low'} (sunset ${hhmm(sunTimes(new Date()).set)}): the battery is carrying the home` })
  }
  return out
}

export function Alerts({ items }: { items: AlertItem[] }) {
  return (
    <Card>
      <CardHeader title="Alerts" />
      <div className="grid gap-2">
        {items.length === 0 && (
          <div className="flex items-center gap-3 rounded-2xl bg-surface-2 p-3 text-sm"><CheckCircle2 className="text-good" size={20} /> All good: no warnings from the inverter</div>
        )}
        {items.map((a, i) => {
          const Icon = a.level === 2 ? XCircle : a.level === 1 ? AlertTriangle : InfoIcon
          return (
            <div key={i} className={cn('flex items-center gap-3 rounded-2xl bg-surface-2 p-3 text-sm')}>
              <Icon size={20} className={a.level === 2 ? 'text-crit' : a.level === 1 ? 'text-warn' : 'text-text-2'} />
              <span><b className="font-semibold">{['Info', 'Warning', 'Problem'][a.level]}:</b> {a.text}</span>
            </div>
          )
        })}
      </div>
    </Card>
  )
}

function FlowInfo() {
  return (
    <>
      <p>Dots move in the direction energy flows, faster and denser with more power. The label on the right says what is powering the home right now.</p>
      <p><b>Battery idle:</b> at full charge the inverter often draws a little from the battery even when solar covers the home. Flows under the limit set in System (100 W by default) are shown as idle.</p>
      <p><b>Grid (est.):</b> the inverter does not measure grid power directly; it is worked out from the home load and what solar and the battery supply.</p>
    </>
  )
}

/** "Sunrise 6:06 AM · Sunset 5:45 PM" for today (worked out on the device for Islamabad, no internet needed). */
function sunLine() {
  const s = sunTimes(new Date())
  return `Sunrise ${hhmm(s.rise)} · Sunset ${hhmm(s.set)}`
}
