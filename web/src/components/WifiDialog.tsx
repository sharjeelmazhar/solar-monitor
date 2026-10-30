import { Eye, EyeOff, Lock } from 'lucide-react'
import { useEffect, useState } from 'react'
import { API_BASE, useStore } from '../lib/store'
import { formError, parseScan, signalOf, type WifiNet } from '../lib/wifi'
import { Button, Modal } from './ui/ui'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const inputCls = 'focus-ring min-h-11 w-full rounded-2xl border border-border bg-surface-2 px-3 text-sm'

/** Moves the monitor to another Wi-Fi from inside the app: pick a network it can see, its password, and the monitor's admin password. */
export function WifiDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const host = useStore((s) => s.info?.host) || 'solar'
  const [nets, setNets] = useState<WifiNet[] | null>(null)
  const [scanTick, setScanTick] = useState(0)
  const [ssid, setSsid] = useState('')
  const [pass, setPass] = useState('')
  const [admin, setAdmin] = useState('')
  const [isOpen, setIsOpen] = useState(false)
  const [showPass, setShowPass] = useState(false)
  const [msg, setMsg] = useState('')
  const [step, setStep] = useState<'form' | 'joining' | 'left' | { ip: string }>('form')

  useEffect(() => {
    if (open) { setSsid(''); setPass(''); setAdmin(''); setIsOpen(false); setShowPass(false); setMsg(''); setStep('form') }
  }, [open])

  useEffect(() => {
    if (!open) return
    let stop = false
    setNets(null)
    ;(async () => {
      for (let k = 0; k < 12 && !stop; k++) {
        try {
          const r = parseScan(await (await fetch(API_BASE + '/api/scan', { cache: 'no-store' })).json())
          if (r) { if (!stop) setNets(r); return }
        } catch { /* keep trying */ }
        await sleep(1500)
      }
      if (!stop) setNets([])
    })()
    return () => { stop = true }
  }, [open, scanTick])

  async function connect(e: React.FormEvent) {
    e.preventDefault()
    const err = formError(ssid, pass, isOpen, admin)
    if (err) { setMsg(err); return }
    setMsg('')
    let code = 0
    try {
      code = (await fetch(API_BASE + '/api/wifi', {
        method: 'POST',
        headers: { Authorization: 'Basic ' + btoa(unescape(encodeURIComponent('admin:' + admin))) },
        body: new URLSearchParams({ ssid, pass }),
      })).status
    } catch { /* unreachable */ }
    if (code === 401) { setMsg('Wrong admin password. Nothing was changed.'); return }
    if (code === 0) { setMsg('Could not reach the monitor. Nothing was changed.'); return }
    if (code < 200 || code > 299) { setMsg('The monitor refused this. Nothing was changed.'); return }
    setStep('joining')
    await sleep(4000)
    for (let k = 0; k < 10; k++) {
      try {
        const c = new AbortController()
        const t = setTimeout(() => c.abort(), 3000)
        const j = await (await fetch(API_BASE + '/api/wifistatus', { cache: 'no-store', signal: c.signal })).json()
        clearTimeout(t)
        if (j.connected && j.ssid === ssid) { setStep({ ip: j.ip }); return }
      } catch { /* gone from this network */ }
      await sleep(3000)
    }
    setStep('left')
  }

  const title = step === 'form' ? 'Change Wi-Fi' : step === 'joining' ? 'Connecting…' : step === 'left' ? 'The monitor has left this network' : 'Connected'
  return (
    <Modal open={open} onOpenChange={(o) => { if (!o && step !== 'joining') onClose() }} title={title}>
      {step === 'form' && (
        <form className="grid gap-3" onSubmit={connect}>
          <p className="text-xs font-medium text-text-2">Networks the monitor can see</p>
          {nets === null && <p className="text-sm text-text-2" role="status">Looking for networks…</p>}
          {nets?.length === 0 && <p className="text-sm text-text-2">No networks found. Move the monitor closer to the router and search again.</p>}
          {!!nets?.length && (
            <ul className="grid max-h-56 gap-1 overflow-y-auto">
              {nets.map((n) => (
                <li key={n.ssid}>
                  <button type="button" onClick={() => { setSsid(n.ssid); setIsOpen(n.open); setPass(''); setMsg('') }}
                    className={'focus-ring flex min-h-10 w-full items-center gap-2 rounded-xl px-2 text-left text-sm hover:bg-surface-2 ' + (n.ssid === ssid ? 'font-semibold text-accent' : '')}>
                    <span className="min-w-0 flex-1 truncate">{n.ssid}</span>
                    <span className={'text-xs ' + (signalOf(n.rssi) === 'weak' ? 'text-warn' : 'text-text-2')}>{signalOf(n.rssi)}</span>
                    {!n.open && <Lock size={14} aria-label="Password needed" className="text-text-3" />}
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div><Button type="button" disabled={nets === null} onClick={() => setScanTick((x) => x + 1)}>Search again</Button></div>
          <label className="grid gap-1 text-xs text-text-2">Network name
            <input value={ssid} autoComplete="off" onChange={(e) => { setSsid(e.target.value); setIsOpen(false) }} className={inputCls} />
          </label>
          <label className="grid gap-1 text-xs text-text-2">Wi-Fi password
            <span className="relative block">
              <input type={showPass ? 'text' : 'password'} value={pass} disabled={isOpen} autoComplete="off" onChange={(e) => setPass(e.target.value)} className={inputCls + ' pr-11'} />
              <button type="button" aria-label={showPass ? 'Hide password' : 'Show password'} onClick={() => setShowPass(!showPass)} className="focus-ring absolute right-1 top-1 grid size-9 place-items-center rounded-xl text-text-2">
                {showPass ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </span>
          </label>
          <label className="grid gap-1 text-xs text-text-2">Monitor admin password
            <input type="password" value={admin} autoComplete="off" onChange={(e) => setAdmin(e.target.value)} className={inputCls} />
          </label>
          <p className="text-xs leading-relaxed text-text-3">Check the Wi-Fi password carefully. If it is wrong, the monitor drops off this network. After about 90 seconds it opens its own hotspot, SolarMonitor-…, so you can set it up again from next to the monitor.</p>
          <div className="flex flex-wrap items-center gap-3">
            <Button variant="primary" type="submit">Connect</Button>
            <Button type="button" onClick={onClose}>Cancel</Button>
            <span className="text-sm text-crit" role="status">{msg}</span>
          </div>
        </form>
      )}
      {step === 'joining' && <p className="text-sm" role="status">Moving the monitor to “{ssid}”. This takes up to 30 seconds.</p>}
      {typeof step === 'object' && (
        <div className="grid gap-4"><p className="text-sm">The monitor is on “{ssid}” at {step.ip}.</p><div><Button variant="primary" onClick={onClose}>Done</Button></div></div>
      )}
      {step === 'left' && (
        <div className="grid gap-3">
          <p className="text-sm">Connect this device to “{ssid}”, then open http://{host}.local/.</p>
          <p className="text-xs leading-relaxed text-text-3">If it doesn't appear within 2 minutes, the password was probably wrong: look for the hotspot SolarMonitor-… next to the monitor and set it up again.</p>
          <div><Button variant="primary" onClick={onClose}>Done</Button></div>
        </div>
      )}
    </Modal>
  )
}
