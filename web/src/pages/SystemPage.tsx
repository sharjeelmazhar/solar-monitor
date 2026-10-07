import { ExternalLink, Lock, RefreshCw } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { BillSettings } from '../components/BillSettings'
import { Button, Card, CardHeader, IconButton, Segmented, Switch } from '../components/ui/ui'
import { BATT_TYPES, CHG_PRIO, CHG_PRIO_HELP, OUT_PRIO, OUT_PRIO_HELP, parseFlags, parseRated } from '../lib/decode'
import { dayLabel, fmtDuration } from '../lib/format'
import { use3d, useBattIdle, useClock, webglAvailable, type Theme } from '../lib/prefs'
import { API_BASE, refreshInfo, refreshInverter, saveSettings, useStore } from '../lib/store'
import { Alerts, alertsOf } from './OverviewPage'

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
  const r = parseRated(info?.inv.qpiri)
  const flags = parseFlags(info?.inv.qflag)
  const [busy, setBusy] = useState(false)

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
      {d?.ever && <Alerts items={alertsOf(d)} />}
      <div className="grid gap-4 lg:grid-cols-2 [&>*]:min-w-0">
        <Card>
          <CardHeader
            title="Inverter settings"
            sub={<span className="inline-flex items-center gap-1"><Lock size={12} /> read-only · changing settings comes with Advanced mode</span>}
            action={<IconButton label="Read settings from inverter again" disabled={busy} onClick={async () => { setBusy(true); await refreshInverter(); setTimeout(async () => { await refreshInfo(); setBusy(false) }, 4000) }}>
              <RefreshCw size={16} className={busy ? 'animate-spin' : ''} />
            </IconButton>}
          />
          {!r && <p className="text-sm text-text-2">Not read yet.</p>}
          {r && (
            <div>
              <Row k="Output priority" v={OUT_PRIO[r.outPrio] ?? r.outPrio} help={OUT_PRIO_HELP[r.outPrio]} />
              <Row k="Charger priority" v={CHG_PRIO[r.chgPrio] ?? r.chgPrio} help={CHG_PRIO_HELP[r.chgPrio]} />
              <Row k="Battery" v={`${r.battV} V · ${BATT_TYPES[r.battType] ?? 'type ' + r.battType}`}
                help={r.battType === 2 ? 'User-defined type: the inverter estimates battery % from voltage, so it can read 100 % while discharging lightly.' : undefined} />
              <Row k="Bulk / float charge" v={`${r.bulk} V / ${r.float} V`} help="Bulk: voltage the charger pushes up to. Float: voltage it holds once full." />
              <Row k="Low cut-off" v={`${r.cutoff} V`} help="Below this the inverter switches the battery off to protect it." />
              <Row k="Back to grid / back to battery" v={`${r.recharge} V / ${r.redischarge ?? '–'} V`} help="Battery voltage at which the inverter switches the home to the grid, and back to battery after recharging." />
              <Row k="Max charge current" v={`${r.maxChg} A (from grid ${r.maxAc} A)`} />
              <Row k="AC input range" v={r.range === 1 ? 'UPS (narrow)' : 'Appliance (wide)'} help={r.range === 1 ? 'Switches to battery quickly; protects computers.' : 'Tolerates wider grid voltage; fine for most homes.'} />
              <Row k="Rated power" v={`${r.outW} W / ${r.outVA} VA`} />
              {info?.inv.qid && <Row k="Serial number" v={info.inv.qid} />}
              {info?.inv.qvfw && <Row k="Inverter firmware" v={info.inv.qvfw.replace(/^VERFW:/, '')} />}
              {flags && <Row k="Enabled features" v={flags.on.join(', ') || '–'} />}
            </div>
          )}
        </Card>

        <div className="grid content-start gap-4 [&>*]:min-w-0">
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
          <BillSettings />
        </div>
      </div>

      <Card>
        <CardHeader title="Monitor device" sub={info ? `firmware v${info.fw}` : 'not connected'} />
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
        </div>
      </Card>
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
  const supported = webglAvailable()
  return (
    <div className="flex items-center justify-between gap-3">
      <div>
        <div className="text-sm">3D energy core</div>
        <div className="text-xs text-text-3">{supported ? 'Animated background behind the flow diagram. Turn it off on slow phones.' : 'Not supported by this browser'}</div>
      </div>
      <Switch label="3D energy core" checked={wanted && supported} onChange={setOn} />
    </div>
  )
}
