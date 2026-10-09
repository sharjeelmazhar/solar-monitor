import { ExternalLink } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { BillSettings } from '../components/BillSettings'
import { InverterSettings } from '../components/InverterSettings'
import { Button, Card, CardHeader, Segmented, Switch } from '../components/ui/ui'
import { dayLabel, fmtDuration } from '../lib/format'
import { gpuSoftware, use3d, useBattIdle, useClock, webglAvailable, type Theme } from '../lib/prefs'
import { API_BASE, saveSettings, useStore } from '../lib/store'
import { parseRated } from '../lib/decode'
import { Alerts, alertsOf } from './OverviewPage'
import { PageHeader } from '../components/cosmos/PageHeader'

function Row({ k, v, help }: { k: string; v: ReactNode; help?: string }) {
  return (
    <div className="border-b border-border py-2.5 last:border-0">
      <div className="flex items-baseline justify-between gap-4">
        <span className="text-sm text-text-2">{k}</span>
        <span className="num text-right text-sm font-medium">{v}</span>
      </div>
      {help && <p className="mt-1 text-xs leading-relaxed text-text-3">{help}</p>}
    </div>
  )
}

export default function SystemPage({ theme, setTheme }: { theme: Theme; setTheme: (t: Theme) => void }) {
  const d = useStore((s) => s.live)
  const info = useStore((s) => s.info)

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      <PageHeader kicker="System" title="Inverter and monitor" sub="Inverter settings, device health, alerts and how the app looks." />
      {d?.ever && <Alerts items={alertsOf(d, undefined, parseRated(info?.inv.qpiri)?.battV === 0)} />}
      <div className="grid gap-4 lg:grid-cols-2 [&>*]:min-w-0">
        <div className="flex flex-col gap-4 [&>*]:min-w-0 [&>*:last-child]:flex-1">
        <InverterSettings />
        </div>

        <div className="flex flex-col gap-4 [&>*]:min-w-0 [&>*:last-child]:flex-1">
          <SettingsCard />
          <Card>
            <CardHeader title="Appearance" />
            <div className="grid gap-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <span className="text-sm">Theme</span>
                <Segmented label="Theme" value={theme} onChange={setTheme} options={[{ value: 'system', label: 'System' }, { value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }]} />
              </div>
              <ClockFormat />
              <BattIdle />
              <ThreeD />
            </div>
          </Card>
        </div>
      </div>
      <Card>
        <CardHeader title="Monitor device" sub={`${info ? `firmware v${info.fw}` : 'not connected'} · web app v${__APP_VERSION__}`} />
        {info && (
          <div className="grid gap-x-8 sm:grid-cols-2">
            <div>
              <Row k="Address" v={`${info.ip} · ${info.host}.local`} />
              <Row k="Wi-Fi" v={`${info.ssid} · ${info.rssi} dBm (${info.rssi > -60 ? 'excellent' : info.rssi > -70 ? 'good' : info.rssi > -80 ? 'fair' : 'weak'})`} />
              <Row k="Up for" v={fmtDuration(info.uptime / 60)} />
              <Row k="Clock" v={info.timeOk ? 'synced' : 'not set'} />
              <Row k="History stored since" v={info.histFrom ? dayLabel(info.histFrom) : 'today'} />
            </div>
            <div>
              <Row k="Storage" v={`${Math.round(info.fsUsed / 1024)} / ${Math.round(info.fsTotal / 1024)} KB`} />
              <Row k="Free memory" v={`${Math.round(info.heap / 1024)} KB`} />
              <Row k="Readings" v={d ? `${d.poll.ok.toLocaleString()} ok · ${d.poll.fail} failed · ${d.poll.crc} CRC` : '–'} />
              <Row k="Read cycle" v={d ? `${d.poll.ms} ms` : '–'} />
              <Row k="Open dashboards" v={info.clients} />
            </div>
          </div>
        )}
        <div className="mt-4 flex flex-wrap gap-2">
          <a className="focus-ring inline-flex min-h-10 items-center gap-2 rounded-2xl border border-border bg-surface-2 px-4 text-sm font-medium hover:bg-surface-3" href={API_BASE + '/classic'}>Classic dashboard <ExternalLink size={14} /></a>
          <a className="focus-ring inline-flex min-h-10 items-center gap-2 rounded-2xl border border-border bg-surface-2 px-4 text-sm font-medium hover:bg-surface-3" href={API_BASE + '/update'} target="_blank" rel="noopener">Firmware update <ExternalLink size={14} /></a>
          <a className="focus-ring inline-flex min-h-10 items-center gap-2 rounded-2xl border border-border bg-surface-2 px-4 text-sm font-medium hover:bg-surface-3" href={API_BASE + '/setup'} target="_blank" rel="noopener">Change Wi-Fi <ExternalLink size={14} /></a>
        </div>
      </Card>
      <BillSettings />

    </div>
  )
}

function SettingsCard() {
  const info = useStore((s) => s.info)
  const [name, setName] = useState('')
  const [ah, setAh] = useState('')
  const [msg, setMsg] = useState('')
  useEffect(() => {
    if (!info) return
    setName(info.name !== 'Solar' ? info.name : '')
    setAh(info.battAh ? String(info.battAh) : '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [info?.name, info?.battAh])
  const field = 'focus-ring min-h-11 w-full rounded-2xl border border-border bg-surface-2 px-3 text-sm'
  return (
    <Card>
      <CardHeader title="Your system" sub="saved on the monitor, shared with the phone app" />
      <form className="grid gap-3" onSubmit={async (e) => {
        e.preventDefault()
        const ok = await saveSettings({ name: name || 'Solar', battAh: ah || '0' })
        setMsg(ok ? 'Saved' : 'Could not reach the monitor')
        setTimeout(() => setMsg(''), 3000)
      }}>
        <label className="grid gap-1 text-xs text-text-2">Name<input className={field} value={name} maxLength={30} placeholder="Solar Monitor" onChange={(e) => setName(e.target.value)} /></label>
        <div className="grid grid-cols-2 gap-3 [&>*]:min-w-0">
          <label className="grid gap-1 text-xs text-text-2">Battery capacity (Ah)<input className={field} inputMode="decimal" value={ah} placeholder="e.g. 200" onChange={(e) => setAh(e.target.value.replace(/[^\d.]/g, ''))} /></label>
        </div>
        <div className="flex items-center gap-3">
          <Button variant="primary" type="submit" disabled={!info}>Save</Button>
          <span className="text-sm text-text-2" role="status">{msg}</span>
        </div>
      </form>
    </Card>
  )
}

function BattIdle() {
  const [v, set] = useBattIdle()
  return (
    <div className="grid gap-2">
      <div className="flex items-center justify-between gap-3">
        <div>
          <div className="text-sm">Ignore small battery flows</div>
          <div className="text-xs text-text-3">At full charge the inverter often takes a little from the battery. Below the limit it shows as idle, not charging or discharging.</div>
        </div>
        <Switch label="Ignore small battery flows" checked={v.on} onChange={(on) => set({ ...v, on })} />
      </div>
      {v.on && (
        <label className="flex items-center gap-3 text-sm">
          <input type="range" min={20} max={300} step={10} value={v.w} onChange={(e) => set({ ...v, w: +e.target.value })} className="flex-1 accent-[var(--batt)]" aria-label="Battery idle limit in watts" />
          <span className="num w-16 text-right">{v.w} W</span>
        </label>
      )}
    </div>
  )
}

function ClockFormat() {
  const [h12, set] = useClock()
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <span className="text-sm">Time format</span>
      <Segmented label="Time format" value={h12 ? '12' : '24'} onChange={(v) => set(v === '12')} options={[{ value: '12', label: '2:30 PM' }, { value: '24', label: '14:30' }]} />
    </div>
  )
}

function ThreeD() {
  const [, setOn, wanted] = use3d()
  const sw = gpuSoftware()
  const supported = webglAvailable() && !sw
  return (
    <div className="flex items-center justify-between gap-3">
      <div>
        <div className="text-sm">3D universe background</div>
        <div className="text-xs text-text-3">{sw ? 'Paused: the browser is drawing without the graphics card right now (this happens after a graphics crash). Restart the browser to bring it back.' : supported ? 'Live sun, earth and galaxy behind the app. Turn it off on slow phones.' : '3D needs graphics acceleration, which is off in this browser. In Chrome: Settings → System → "Use graphics acceleration when available", then restart Chrome.'}</div>
      </div>
      <Switch label="3D universe background" checked={wanted && supported} onChange={setOn} />
    </div>
  )
}
