import { ArrowRight, Check, Copy, Lock, LockOpen, Minus, Pencil, Plus, Radar, RefreshCw, RotateCcw, TriangleAlert, X } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { BATT_TYPES, parseFlags, parseRated, type Rated } from '../lib/decode'
import { dayLabel, hhmm, ymd } from '../lib/format'
import {
  INV_SETTINGS, RESTORE_CODE, RESTORE_WORD, checkPassword, choicesOf, currentOf, fetchLog, labelOf, logLabel, parseEq, readProbe, sendSetting, setId, startProbe,
  type Choice, type LogEntry, type SetDef,
} from '../lib/invset'
import { refreshInfo, refreshInverter, useStore } from '../lib/store'
import { Button, Card, CardHeader, IconButton, Modal, cn } from './ui/ui'

// Edit mode lives only in memory and switches itself off after 10 minutes.
const EDIT_MS = 10 * 60_000
let editPw = ''
let editUntil = 0

function Row({ k, v, help, onEdit }: { k: string; v: ReactNode; help?: string; onEdit?: () => void }) {
  return (
    <div className="border-b border-border py-2.5 last:border-0">
      <div className="flex items-center justify-between gap-4">
        <span className="text-sm text-text-2">{k}</span>
        <span className="flex items-center gap-2">
          <span className="num text-right text-sm font-medium">{v}</span>
          {onEdit && (
            <button onClick={onEdit} aria-label={`Change ${k}`} className="focus-ring inline-grid size-8 place-items-center rounded-xl border border-border bg-surface-2 text-text-2 hover:bg-surface-3 hover:text-text">
              <Pencil size={14} />
            </button>
          )}
        </span>
      </div>
      {help && <p className="mt-1 text-xs leading-relaxed text-text-3">{help}</p>}
    </div>
  )
}

export function InverterSettings() {
  const info = useStore((s) => s.info)
  const r = parseRated(info?.inv.qpiri)
  const flags = parseFlags(info?.inv.qflag)
  const [busy, setBusy] = useState(false)
  const [edit, setEdit] = useState(() => Date.now() < editUntil)
  const [unlock, setUnlock] = useState(false)
  const [editing, setEditing] = useState<SetDef | null>(null)
  const [probe, setProbe] = useState(false)
  const [logKey, setLogKey] = useState(0)
  const [restore, setRestore] = useState(false)

  useEffect(() => {
    if (!edit) return
    const t = setTimeout(() => { setEdit(false); editPw = ''; editUntil = 0 }, Math.max(0, editUntil - Date.now()))
    return () => clearTimeout(t)
  }, [edit])

  const chg = info?.inv.chgCur ?? ''
  const ac = info?.inv.acCur ?? ''
  const proto = info?.inv.proto
  const beqi = info?.inv.beqi ?? ''
  const eq = parseEq(beqi)
  const canEdit = edit && !!r && proto !== 'PI18'
  const val = (d: SetDef) => (r ? labelOf(d, currentOf(d, r, info?.inv.qflag ?? '', beqi), r, chg, ac) : '–')
  const def = (key: string) => INV_SETTINGS.find((d) => d.key === key)!
  const row = (key: string, k: string, help?: string, v?: ReactNode) => {
    const d = def(key)
    return <Row k={k} v={v ?? val(d)} help={help} onEdit={canEdit ? () => setEditing(d) : undefined} />
  }

  return (
    <>
      <Card>
        <CardHeader
          title="Inverter settings"
          sub={edit
            ? <span className="inline-flex items-center gap-1 text-warn"><LockOpen size={12} /> edit mode on · changes go to the inverter</span>
            : <span className="inline-flex items-center gap-1"><Lock size={12} /> read-only</span>}
          action={
            <div className="flex items-center gap-2">
              <IconButton label="Read settings from inverter again" disabled={busy} onClick={async () => { setBusy(true); await refreshInverter(); setTimeout(async () => { await refreshInfo(); setBusy(false) }, 4000) }}>
                <RefreshCw size={16} className={busy ? 'animate-spin' : ''} />
              </IconButton>
              <IconButton label={edit ? 'Turn off edit mode' : 'Turn on edit mode'} className={edit ? 'border-warn text-warn' : ''}
                onClick={() => { if (edit) { setEdit(false); editPw = ''; editUntil = 0 } else setUnlock(true) }}>
                {edit ? <LockOpen size={16} /> : <Pencil size={16} />}
              </IconButton>
            </div>
          }
        />
        {!r && <p className="text-sm text-text-2">{proto === 'PI18' ? 'This inverter speaks PI18: live data works, settings are not shown yet.' : 'Not read yet.'}</p>}
        {r && (
          <div>
            {row('outPrio', 'Output priority', choicesOf(def('outPrio'), r, chg, ac)?.[r.outPrio]?.help)}
            {row('chgPrio', 'Charger priority', choicesOf(def('chgPrio'), r, chg, ac)?.[r.chgPrio]?.help)}
            <Row k="Battery" v={`${r.battV} V · ${BATT_TYPES[r.battType] ?? 'type ' + r.battType}`}
              help={r.battType === 2 ? 'User-defined type: the inverter estimates battery % from voltage, so it can read 100 % while discharging lightly.' : undefined}
              onEdit={canEdit ? () => setEditing(def('battType')) : undefined} />
            {canEdit ? <>
              {row('bulk', 'Bulk charge')}
              {row('float', 'Float charge')}
            </> : <Row k="Bulk / float charge" v={`${r.bulk} V / ${r.float} V`} help="Bulk: voltage the charger pushes up to. Float: voltage it holds once full." />}
            {row('cutoff', 'Low cut-off', 'Below this the inverter switches the battery off to protect it.')}
            {canEdit ? <>
              {row('recharge', 'Back to grid at')}
              {row('redischarge', 'Back to battery at')}
            </> : <Row k="Back to grid / back to battery" v={`${r.recharge} V / ${r.redischarge === 0 ? 'full' : r.redischarge ?? '–'}${r.redischarge === 0 ? '' : ' V'}`} help="Battery voltage at which the inverter switches the home to the grid, and back to battery after recharging." />}
            {canEdit ? <>
              {row('maxChg', 'Max charge current')}
              {row('maxAc', 'Max grid charge current')}
            </> : <Row k="Max charge current" v={`${r.maxChg} A (from grid ${r.maxAc} A)`} />}
            {row('range', 'AC input range', r.range === 1 ? 'Switches to battery quickly; protects computers.' : 'Tolerates wider grid voltage; fine for most homes.')}
            {canEdit ? <>
              {row('outV', 'Output voltage')}
              {row('outHz', 'Output frequency')}
            </> : <Row k="Output" v={`${r.outV} V · ${r.outHz} Hz`} />}
            <Row k="Rated power" v={`${r.outW} W / ${r.outVA} VA`} />
            {info?.inv.qid && <Row k="Serial number" v={info.inv.qid} />}
            {info?.inv.qvfw && <Row k="Inverter firmware" v={info.inv.qvfw.replace(/^VERFW:/, '')} />}
            {proto && <Row k="Protocol" v={proto} />}
            {!canEdit && flags && <Row k="Enabled features" v={flags.on.join(', ') || '–'} />}
            {canEdit && INV_SETTINGS.filter((d) => d.kind === 'flag').map((d) => <Row key={setId(d)} k={d.label} v={val(d)} onEdit={() => setEditing(d)} />)}
            {eq && <>
              <h3 className="mt-5 mb-1 text-sm font-semibold">Battery equalization</h3>
              {canEdit ? <>
                {row('eqEn', 'Equalization', 'For flooded lead-acid batteries only.')}
                {eq[0] === 1 && row('eqNow', 'Equalize now')}
                {row('eqVolt', 'Voltage')}
                {row('eqTime', 'Time')}
                {row('eqTimeout', 'Time-out')}
                {row('eqPeriod', 'Every')}
              </> : <>
                <Row k="Equalization" v={eq[0] === 1 ? (eq[8] === 1 ? 'On · running now' : 'On') : 'Off'} help="A regular higher charge for flooded lead-acid batteries." />
                <Row k="Voltage · time · every" v={`${eq[5].toFixed(2)} V · ${eq[1]} min · ${eq[2]} days`} help={`Gives up after ${eq[7]} min if the voltage is not reached.`} />
              </>}
            </>}
          </div>
        )}
        {edit && (
          <div className="mt-4 flex flex-wrap gap-2">
            <Button onClick={() => setProbe(true)}><Radar size={16} /> Detect inverter (read-only probe)</Button>
            {canEdit && <Button className="border-crit/50 text-crit" onClick={() => setRestore(true)}><RotateCcw size={16} /> Restore factory defaults</Button>}
          </div>
        )}
      </Card>
      <ChangeLog key={logKey} />
      <UnlockDialog open={unlock} onClose={() => setUnlock(false)} onUnlocked={(pw) => { editPw = pw; editUntil = Date.now() + EDIT_MS; setEdit(true); setUnlock(false) }} />
      {restore && <RestoreDialog onClose={() => { setRestore(false); setLogKey((x) => x + 1) }} />}
      {editing && r && <ChangeDialog d={editing} r={r} chg={chg} ac={ac} qflag={info?.inv.qflag ?? ''} beqi={beqi} onClose={() => { setEditing(null); setLogKey((x) => x + 1) }} />}
      <ProbeDialog open={probe} onClose={() => setProbe(false)} />
    </>
  )
}

function Warning({ children }: { children: ReactNode }) {
  return (
    <div className="flex gap-3 rounded-2xl border border-warn/40 bg-warn/10 p-3 text-sm leading-relaxed">
      <TriangleAlert size={18} className="mt-0.5 shrink-0 text-warn" />
      <div>{children}</div>
    </div>
  )
}

function UnlockDialog({ open, onClose, onUnlocked }: { open: boolean; onClose: () => void; onUnlocked: (pw: string) => void }) {
  const [pw, setPw] = useState('')
  const [msg, setMsg] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (open) { setPw(''); setMsg('') } }, [open])
  return (
    <Modal open={open} onOpenChange={(o) => !o && onClose()} title="Turn on edit mode">
      <form className="grid gap-4" onSubmit={async (e) => {
        e.preventDefault()
        setBusy(true)
        const r = await checkPassword(pw)
        setBusy(false)
        if (r === 'ok') onUnlocked(pw)
        else setMsg(r === 'wrong' ? 'Wrong password' : 'Could not reach the monitor')
      }}>
        <Warning>
          Changes are sent straight to the inverter. A wrong battery voltage can damage the battery or switch the home off.
          Only change what you understand, and use your battery maker's values. Every change is checked, read back and logged.
        </Warning>
        <label className="grid gap-1 text-xs text-text-2">Settings password
          <input type="password" autoComplete="current-password" autoFocus value={pw} onChange={(e) => setPw(e.target.value)}
            className="focus-ring min-h-11 w-full rounded-2xl border border-border bg-surface-2 px-3 text-sm" />
        </label>
        <div className="flex items-center gap-3">
          <Button variant="primary" type="submit" disabled={!pw || busy}>Turn on</Button>
          <span className="text-sm text-crit" role="status">{msg}</span>
        </div>
        <p className="text-xs text-text-3">Edit mode turns itself off after 10 minutes.</p>
      </form>
    </Modal>
  )
}

function ChangeDialog({ d, r, chg, ac, qflag, beqi, onClose }: { d: SetDef; r: Rated; chg: string; ac: string; qflag: string; beqi: string; onClose: () => void }) {
  const choices = choicesOf(d, r, chg, ac) ?? []
  const cur = currentOf(d, r, qflag, beqi)
  const stepped = d.kind === 'volts' || d.kind === 'steps'
  const [pick, setPick] = useState<number | null>(cur ?? choices[0]?.value ?? null)
  const [step, setStep] = useState<'pick' | 'confirm' | 'sending' | 'done'>('pick')
  const [result, setResult] = useState<{ ok: boolean; msg: string } | null>(null)
  const label = (v: number | null) => labelOf(d, v, r, chg, ac)
  const changed = pick != null && (cur == null || Math.abs(pick - cur) > 0.001)

  return (
    <Modal open onOpenChange={(o) => !o && step !== 'sending' && onClose()} title={d.label}>
      <div className="grid gap-4">
        {step === 'pick' && <>
          <p className="text-sm leading-relaxed text-text-2">{d.help}</p>
          <div className="flex items-center justify-between rounded-2xl bg-surface-2 px-4 py-3 text-sm">
            <span className="text-text-2">Now</span><span className="num font-medium">{label(cur)}</span>
          </div>
          {choices.length === 0 && <p className="text-sm text-crit">The allowed values have not been read from the inverter yet. Press refresh and try again.</p>}
          {stepped && choices.length > 0 && <VoltPicker choices={choices} value={pick} onChange={setPick} full={d.key === 'redischarge'} />}
          {!stepped && choices.length > 0 && (
            <div className="grid gap-2" role="radiogroup" aria-label={d.label}>
              {choices.map((c) => (
                <button key={c.value} role="radio" aria-checked={pick === c.value} onClick={() => setPick(c.value)}
                  className={cn('focus-ring rounded-2xl border px-4 py-3 text-left text-sm transition', pick === c.value ? 'border-text bg-surface-3' : 'border-border bg-surface-2 hover:bg-surface-3')}>
                  <div className="flex items-center justify-between gap-3 font-medium">{c.label}{c.value === cur && <span className="text-xs font-normal text-text-3">current</span>}</div>
                  {c.help && <div className="mt-1 text-xs leading-relaxed text-text-3">{c.help}</div>}
                </button>
              ))}
            </div>
          )}
          <div className="flex gap-2">
            <Button variant="primary" disabled={!changed} onClick={() => setStep('confirm')}>Review change</Button>
            <Button onClick={onClose}>Cancel</Button>
          </div>
        </>}
        {step === 'confirm' && <>
          <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-3 rounded-2xl bg-surface-2 p-4 text-center">
            <div><div className="text-xs text-text-3">Now</div><div className="num text-lg font-semibold">{label(cur)}</div></div>
            <ArrowRight size={20} className="text-text-3" />
            <div><div className="text-xs text-text-3">New</div><div className="num text-lg font-semibold text-load">{label(pick)}</div></div>
          </div>
          <Warning>This is sent to the inverter now. The monitor reads the setting back afterwards to make sure it took.</Warning>
          <div className="flex gap-2">
            <Button variant="primary" onClick={async () => { setStep('sending'); setResult(await sendSetting(d, pick!, editPw)); setStep('done') }}>Send to inverter</Button>
            <Button onClick={() => setStep('pick')}>Back</Button>
          </div>
        </>}
        {step === 'sending' && (
          <div className="flex items-center gap-3 rounded-2xl bg-surface-2 p-4 text-sm"><RefreshCw size={18} className="animate-spin" /> Sending and reading back…</div>
        )}
        {step === 'done' && result && <>
          <div className={cn('flex gap-3 rounded-2xl p-4 text-sm', result.ok ? 'bg-good/15' : 'bg-crit/15')}>
            {result.ok ? <Check size={18} className="shrink-0 text-good" /> : <X size={18} className="shrink-0 text-crit" />}
            <div><div className="font-medium">{result.ok ? `${d.label} is now ${label(pick)}` : 'Not changed'}</div><div className="mt-0.5 text-text-2">{result.msg}</div></div>
          </div>
          <div><Button variant="primary" onClick={onClose}>Done</Button></div>
        </>}
      </div>
    </Modal>
  )
}

/** Stepper + slider over an ordered list (voltages, minutes, days). full = the list has a "Full battery" choice (0). */
function VoltPicker({ choices, value, onChange, full: hasFull = false }: { choices: Choice[]; value: number | null; onChange: (v: number) => void; full?: boolean }) {
  const volts = hasFull ? choices.filter((c) => c.value > 0) : choices
  let i = volts.findIndex((c) => value != null && Math.abs(c.value - value) < 0.001)
  const full = hasFull && value === 0
  if (i < 0 && !full) i = 0
  const move = (dir: number) => onChange(volts[Math.max(0, Math.min(volts.length - 1, (i < 0 ? 0 : i) + dir))].value)
  return (
    <div className="grid gap-3">
      {hasFull && (
        <div className="grid grid-cols-2 gap-2">
          <Button className={full ? 'border-text bg-surface-3' : ''} onClick={() => onChange(0)}>Full battery</Button>
          <Button className={!full ? 'border-text bg-surface-3' : ''} onClick={() => onChange(volts[Math.max(0, i)].value)}>A voltage</Button>
        </div>
      )}
      {!full && volts.length > 0 && <>
        <div className="flex items-center gap-3">
          <IconButton label="Lower" onClick={() => move(-1)} disabled={i <= 0}><Minus size={16} /></IconButton>
          <div className="num flex-1 text-center text-2xl font-semibold">{volts[i].label}</div>
          <IconButton label="Higher" onClick={() => move(1)} disabled={i >= volts.length - 1}><Plus size={16} /></IconButton>
        </div>
        <input type="range" min={0} max={volts.length - 1} value={i} onChange={(e) => onChange(volts[+e.target.value].value)} className="accent-[var(--load)]" aria-label="Value" />
        <div className="flex justify-between text-xs text-text-3"><span>{volts[0].label}</span><span>allowed range</span><span>{volts[volts.length - 1].label}</span></div>
      </>}
    </div>
  )
}

function RestoreDialog({ onClose }: { onClose: () => void }) {
  const [word, setWord] = useState('')
  const [step, setStep] = useState<'ask' | 'sending' | 'done'>('ask')
  const [result, setResult] = useState<{ ok: boolean; msg: string } | null>(null)
  const d = INV_SETTINGS.find((x) => x.key === 'restore')!
  return (
    <Modal open onOpenChange={(o) => !o && step !== 'sending' && onClose()} title="Restore factory defaults">
      <div className="grid gap-4">
        {step === 'ask' && <>
          <Warning>
            Every inverter setting goes back to the factory values: battery type and voltages, charge currents, priorities and
            all switches. If your battery needs other values, the inverter may charge it wrongly until you set them again.
            Note the current settings first.
          </Warning>
          <label className="grid gap-1 text-xs text-text-2"><span>Type <b className="num text-text">{RESTORE_WORD}</b> to confirm</span>
            <input autoFocus value={word} onChange={(e) => setWord(e.target.value.toUpperCase())} autoComplete="off" spellCheck={false}
              className="focus-ring num min-h-11 w-full rounded-2xl border border-border bg-surface-2 px-3 text-sm tracking-widest" />
          </label>
          <div className="flex gap-2">
            <Button variant="primary" className="bg-crit text-white" disabled={word !== RESTORE_WORD}
              onClick={async () => { setStep('sending'); setResult(await sendSetting(d, RESTORE_CODE, editPw)); setStep('done') }}>Restore defaults</Button>
            <Button onClick={onClose}>Cancel</Button>
          </div>
        </>}
        {step === 'sending' && <div className="flex items-center gap-3 rounded-2xl bg-surface-2 p-4 text-sm"><RefreshCw size={18} className="animate-spin" /> Sending…</div>}
        {step === 'done' && result && <>
          <div className={cn('flex gap-3 rounded-2xl p-4 text-sm', result.ok ? 'bg-good/15' : 'bg-crit/15')}>
            {result.ok ? <Check size={18} className="shrink-0 text-good" /> : <X size={18} className="shrink-0 text-crit" />}
            <div><div className="font-medium">{result.ok ? 'Defaults restored' : 'Not changed'}</div><div className="mt-0.5 text-text-2">{result.msg}</div></div>
          </div>
          <div><Button variant="primary" onClick={onClose}>Done</Button></div>
        </>}
      </div>
    </Modal>
  )
}

function ChangeLog() {
  const [log, setLog] = useState<LogEntry[] | null>(null)
  useEffect(() => { fetchLog().then(setLog) }, [])
  if (!log || log.length === 0) return null
  return (
    <Card>
      <CardHeader title="Settings change log" sub="every change sent to the inverter, newest first" />
      <div className="grid gap-0">
        {log.slice(0, 20).map((e, i) => (
          <div key={i} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 border-b border-border py-2.5 text-sm last:border-0">
            <div className="min-w-0">
              <div className="font-medium">{logLabel(e.k)} <span className="num text-text-2">{e.o} → {e.n}</span></div>
              <div className="text-xs text-text-3">{e.t ? `${dayLabel(ymd(new Date(e.t * 1000)))} ${hhmm(e.t * 1000)}` : ''} · {e.by}{e.m ? ' · ' + e.m : ''}</div>
            </div>
            <span className={cn('rounded-full px-2.5 py-0.5 text-xs font-medium', e.r === 'ok' ? 'bg-good/15 text-good' : 'bg-crit/15 text-crit')}>{e.r === 'ok' ? 'done' : 'failed'}</span>
          </div>
        ))}
      </div>
    </Card>
  )
}

function ProbeDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [text, setText] = useState('')
  const [state, setState] = useState<'idle' | 'running' | 'done' | 'error'>('idle')
  useEffect(() => {
    if (!open) return
    let stop = false
    readProbe().then((t) => { if (!stop && t.startsWith('state: done')) { setText(t); setState('done') } })
    return () => { stop = true }
  }, [open])
  const run = async () => {
    setState('running'); setText('')
    if (!(await startProbe(editPw))) { setState('error'); return }
    const until = Date.now() + 90_000
    while (Date.now() < until) {
      await new Promise((r) => setTimeout(r, 2000))
      const t = await readProbe()
      if (t.startsWith('state: done')) { setText(t); setState('done'); return }
    }
    setState('error')
  }
  return (
    <Modal open={open} onOpenChange={(o) => !o && state !== 'running' && onClose()} title="Detect inverter" wide>
      <div className="grid gap-4">
        <p className="text-sm leading-relaxed text-text-2">
          Asks the inverter read-only questions in every protocol the monitor knows (PI30, PI18 and Modbus), to learn how a new
          inverter brand talks. Nothing is changed. Live readings pause for about half a minute.
        </p>
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" disabled={state === 'running'} onClick={run}>
            {state === 'running' ? <><RefreshCw size={16} className="animate-spin" /> Probing…</> : <><Radar size={16} /> Run probe</>}
          </Button>
          {text && <Button onClick={() => navigator.clipboard?.writeText(text)}><Copy size={16} /> Copy result</Button>}
        </div>
        {state === 'error' && <p className="text-sm text-crit">The probe did not finish. Check the connection and try again.</p>}
        {text && <pre className="max-h-[50vh] overflow-auto whitespace-pre-wrap break-all rounded-2xl bg-surface-2 p-4 font-mono text-xs leading-relaxed">{text}</pre>}
      </div>
    </Modal>
  )
}
