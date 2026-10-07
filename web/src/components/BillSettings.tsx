import { ChevronDown, RotateCcw } from 'lucide-react'
import { useEffect, useState } from 'react'
import { DEFAULT_BILL, parseBill, type BillConfig, type Slab } from '../lib/bill'
import { saveBill, useStore } from '../lib/store'
import { Button, Card, CardHeader, Segmented, cn } from './ui/ui'

const field = 'focus-ring min-h-11 w-full rounded-2xl border border-border bg-surface-2 px-3 text-sm num'
const cell = 'focus-ring min-h-9 w-full rounded-xl border border-border bg-surface-2 px-2 text-sm num text-right'

/** Numbers are edited as text so a half-typed "1." or "-" doesn't jump around. */
type Draft = { [K in keyof BillConfig]: BillConfig[K] extends number ? string : BillConfig[K] extends Slab[] ? string[][] : BillConfig[K] }

const toDraft = (c: BillConfig): Draft => ({
  st: c.st, kw: String(c.kw), day: String(c.day), fpa: String(c.fpa), qta: String(c.qta), gst: String(c.gst), ed: String(c.ed), ptv: String(c.ptv), extra: String(c.extra),
  ps: c.ps.map((s) => s.map(String)), us: c.us.map((s) => s.map(String)),
})
const fromDraft = (d: Draft): BillConfig => parseBill({
  st: d.st, kw: +d.kw, day: +d.day, fpa: +d.fpa, qta: +d.qta, gst: +d.gst, ed: +d.ed, ptv: +d.ptv, extra: +d.extra,
  ps: d.ps.map((s) => s.map(Number)), us: d.us.map((s) => s.map(Number)),
})
const numIn = (v: string, neg = false) => v.replace(neg ? /[^\d.-]/g : /[^\d.]/g, '')

export function BillSettings() {
  const saved = useStore((s) => s.bill)
  const [d, setD] = useState<Draft>(() => toDraft(saved ?? DEFAULT_BILL))
  const [rates, setRates] = useState(false)
  const [msg, setMsg] = useState('')
  useEffect(() => { if (saved) setD(toDraft(saved)) }, [saved])
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setD((x) => ({ ...x, [k]: v }))

  // a plain function, not a component: a component defined here would remount (and lose focus) on every key
  const num = (k: 'kw' | 'day' | 'fpa' | 'qta' | 'gst' | 'ed' | 'ptv' | 'extra', label: string, hint?: string, neg = false) => (
    <label key={k} className="grid content-start gap-1 text-xs text-text-2">
      {label}
      <input className={field} inputMode="decimal" value={d[k]} onChange={(e) => set(k, numIn(e.target.value, neg))} />
      {hint && <span className="text-[11px] leading-snug text-text-3">{hint}</span>}
    </label>
  )

  const slabTable = (key: 'ps' | 'us', title: string) => (
    <div>
      <p className="mb-1.5 text-xs font-semibold text-text-2">{title}</p>
      <div className="grid grid-cols-3 gap-1.5 text-[11px] text-text-3 [&>*]:min-w-0">
        <span>Up to units (0 = above)</span><span className="text-right">Rs / unit</span><span className="text-right">Fixed Rs / kW</span>
        {d[key].map((row, i) =>
          row.map((v, j) => (
            <input key={`${i}-${j}`} className={cell} inputMode="decimal" aria-label={`${title} slab ${i + 1} ${['limit', 'rate', 'fixed'][j]}`} value={v}
              onChange={(e) => set(key, d[key].map((r, ri) => (ri === i ? r.map((x, ci) => (ci === j ? numIn(e.target.value) : x)) : r)))} />
          )),
        )}
      </div>
    </div>
  )

  return (
    <Card>
      <CardHeader title="Bill settings" sub="IESCO home tariff · saved on the monitor, shared with the phone app" />
      <form className="grid gap-4" onSubmit={async (e) => {
        e.preventDefault()
        const ok = await saveBill(fromDraft(d))
        setMsg(ok ? 'Saved' : 'Could not reach the monitor')
        setTimeout(() => setMsg(''), 3000)
      }}>
        <div className="grid gap-1.5">
          <span className="text-xs text-text-2">Your status (printed on the bill)</span>
          <Segmented label="Consumer status" value={d.st} onChange={(v) => set('st', v)} options={[{ value: 'p', label: 'Protected' }, { value: 'u', label: 'Unprotected' }]} />
          <span className="text-[11px] text-text-3">Protected = every one of the last 6 months at or under 200 units.</span>
        </div>
        <div className="grid grid-cols-2 gap-3 [&>*]:min-w-0">
          {num('kw', 'Sanctioned load (kW)', 'on your bill; fixed charges are per kW')}
          {num('day', 'Meter reading day', 'day of month the bill period starts')}
          {num('fpa', 'Fuel adjustment (Rs/unit)', 'FPA on the latest bill', true)}
          {num('qta', 'Quarterly adjustment (Rs/unit)', 'QTA, can be negative', true)}
          {num('extra', 'Other units / month', 'grid use not through this inverter')}
          {num('ptv', 'PTV fee (Rs)')}
          {num('gst', 'Sales tax (%)')}
          {num('ed', 'Electricity duty (%)')}
        </div>

        <button type="button" onClick={() => setRates(!rates)} className="focus-ring flex min-h-10 items-center gap-1 justify-self-start rounded-xl text-sm font-medium text-text-2 hover:text-text" aria-expanded={rates}>
          <ChevronDown size={16} className={cn('transition-transform', rates && 'rotate-180')} /> Slab rates (NEPRA, Feb 2026)
        </button>
        {rates && (
          <div className="grid gap-4">
            {slabTable('ps', 'Protected')}
            {slabTable('us', 'Unprotected')}
            <Button type="button" onClick={() => setD((x) => ({ ...x, ps: toDraft(DEFAULT_BILL).ps, us: toDraft(DEFAULT_BILL).us }))}>
              <RotateCcw size={14} /> Reset rates to Feb 2026
            </Button>
          </div>
        )}

        <div className="flex items-center gap-3">
          <Button variant="primary" type="submit">Save</Button>
          <span className="text-sm text-text-2" role="status">{msg}</span>
        </div>
      </form>
    </Card>
  )
}
