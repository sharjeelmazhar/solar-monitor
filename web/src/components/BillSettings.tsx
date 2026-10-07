import { ChevronDown, Plus, RotateCcw, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { DEFAULT_BILL, parseBill, type BillConfig, type PastBill, type Slab } from '../lib/bill'
import { fmtPkr } from '../lib/format'
import { saveBill, useStore } from '../lib/store'
import { monthName } from './BillCard'
import { BillGuide } from './BillGuide'
import { Button, Card, CardHeader, IconButton, InfoButton, Segmented, cn } from './ui/ui'

const field = 'focus-ring min-h-11 w-full rounded-2xl border border-border bg-surface-2 px-3 text-sm num'
const cell = 'focus-ring min-h-9 w-full rounded-xl border border-border bg-surface-2 px-2 text-sm num text-right'

type NumKey = 'kw' | 'day' | 'fc' | 'fpa' | 'qta' | 'gst' | 'ed' | 'ptv' | 'extra'
/** Numbers are edited as text so a half-typed "1." or "-" doesn't jump around. */
type Draft = { st: 'p' | 'u'; ps: string[][]; us: string[][]; hist: PastBill[] } & Record<NumKey, string>

const KEYS: NumKey[] = ['kw', 'day', 'fc', 'fpa', 'qta', 'gst', 'ed', 'ptv', 'extra']
const toDraft = (c: BillConfig): Draft => ({
  st: c.st, ps: c.ps.map((s) => s.map(String)), us: c.us.map((s) => s.map(String)), hist: c.hist,
  ...(Object.fromEntries(KEYS.map((k) => [k, String(c[k])])) as Record<NumKey, string>),
})
const fromDraft = (d: Draft): BillConfig => parseBill({
  st: d.st, hist: d.hist, ps: d.ps.map((s) => s.map(Number) as Slab), us: d.us.map((s) => s.map(Number) as Slab),
  ...Object.fromEntries(KEYS.map((k) => [k, +d[k]])),
})
const numIn = (v: string, neg = false) => v.replace(neg ? /[^\d.-]/g : /[^\d.]/g, '')

const FIELDS: [NumKey, string, string?, boolean?][] = [
  ['kw', 'Sanctioned load (kW)', 'LOAD on your bill; fixed charges are per kW'],
  ['day', 'Meter reading day', 'READING DATE on your bill (day of month)'],
  ['fpa', 'Fuel adjustment (Rs/unit)', 'the "@" rate next to FPA on the bill', true],
  ['qta', 'Quarterly adjustment (Rs/unit)', 'QTR. TARIFF ADJ ÷ units; can be negative', true],
  ['fc', 'F.C. surcharge (Rs/unit)', 'F.C SURCHARGE ÷ units (0.43 in 2026)'],
  ['extra', 'Other units / month', "grid use the monitor can't see"],
  ['gst', 'Sales tax (%)'],
  ['ed', 'Electricity duty (%)', 'ED@ on the bill'],
  ['ptv', 'TV fee (Rs)', '0 if your bill has none'],
]

export function BillSettings() {
  const saved = useStore((s) => s.bill)
  const [d, setD] = useState<Draft>(() => toDraft(saved ?? DEFAULT_BILL))
  const [rates, setRates] = useState(false)
  const [msg, setMsg] = useState('')
  useEffect(() => { if (saved) setD(toDraft(saved)) }, [saved])
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setD((x) => ({ ...x, [k]: v }))
  const save = async (next = d) => {
    const ok = await saveBill(fromDraft(next))
    setMsg(ok ? 'Saved' : 'Could not reach the monitor')
    setTimeout(() => setMsg(''), 3000)
  }

  // a plain function, not a component: a component defined here would remount (and lose focus) on every key
  const num = (k: NumKey, label: string, hint?: string, neg = false) => (
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
      <CardHeader title="Bill settings" sub="IESCO home tariff · saved on the monitor, shared with the phone app"
        info={<><p>Copy these from your latest IESCO bill. The estimate then follows the same steps as the bill itself.</p><BillGuide /></>} />
      <form className="grid gap-4" onSubmit={(e) => { e.preventDefault(); save() }}>
        <div className="grid gap-1.5">
          <span className="text-xs text-text-2">Your status (printed on the bill)</span>
          <Segmented label="Consumer status" value={d.st} onChange={(v) => set('st', v)} options={[{ value: 'p', label: 'Protected' }, { value: 'u', label: 'Unprotected' }]} />
          <span className="text-[11px] text-text-3">Protected = every one of the last 6 months at or under 200 units.</span>
        </div>
        <div className="grid grid-cols-2 gap-3 [&>*]:min-w-0">{FIELDS.map(([k, l, h, n]) => num(k, l, h, n))}</div>

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

      <PastBills bills={d.hist} onChange={(hist) => { const next = { ...d, hist }; setD(next); save(next) }} />
    </Card>
  )
}

/** Real bills: their units decide protected status and the FPA estimate. */
function PastBills({ bills, onChange }: { bills: PastBill[]; onChange: (b: PastBill[]) => void }) {
  const now = new Date()
  const [month, setMonth] = useState(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`)
  const [units, setUnits] = useState('')
  const [amount, setAmount] = useState('')
  const add = () => {
    const ym = Number(month.replace('-', ''))
    if (!ym || !units) return
    onChange([...bills.filter((b) => b[0] !== ym), [ym, Math.round(+units), Math.round(+amount || 0)] as PastBill].sort((a, b) => a[0] - b[0]))
    setUnits('')
    setAmount('')
  }
  return (
    <div className="mt-6 border-t border-border pt-4">
      <h3 className="flex items-center gap-1 text-sm font-semibold">Your bills <InfoButton title="Where to find it on your bill" small><BillGuide /></InfoButton></h3>
      <p className="mb-3 text-xs text-text-3">Add the units and amount from each bill (the table on the bill lists the last 12 months). Used for protected status and the fuel adjustment.</p>
      <div className="grid grid-cols-[1.3fr_1fr_1fr_auto] items-end gap-2 [&>*]:min-w-0">
        <label className="grid gap-1 text-xs text-text-2">Bill month<input type="month" className={field} value={month} onChange={(e) => setMonth(e.target.value)} /></label>
        <label className="grid gap-1 text-xs text-text-2">Units<input className={field} inputMode="numeric" value={units} onChange={(e) => setUnits(numIn(e.target.value))} /></label>
        <label className="grid gap-1 text-xs text-text-2">Amount Rs<input className={field} inputMode="numeric" value={amount} onChange={(e) => setAmount(numIn(e.target.value))} /></label>
        <IconButton label="Add bill" onClick={add} disabled={!units}><Plus size={18} /></IconButton>
      </div>
      {bills.length > 0 && (
        <table className="num mt-3 w-full text-sm">
          <thead><tr className="text-left text-xs text-text-3 [&_th]:pb-1 [&_th]:font-medium"><th>Month</th><th className="text-right">Units</th><th className="text-right">Amount</th><th className="pr-4 text-right">Rs / unit</th><th /></tr></thead>
          <tbody className="[&_td]:border-t [&_td]:border-border [&_td]:py-1.5">
            {bills.slice().reverse().map((b) => (
              <tr key={b[0]}>
                <td className="font-sans text-text-2">{monthName(b[0])}</td>
                <td className={cn('text-right font-semibold', b[1] > 200 && 'text-crit')}>{b[1]}</td>
                <td className="text-right">{b[2] ? fmtPkr(b[2]) : '–'}</td>
                <td className="pr-4 text-right text-text-3">{b[2] && b[1] ? (b[2] / b[1]).toFixed(1) : "–"}</td>
                <td className="w-14 pl-2 text-right"><IconButton label={`Remove ${monthName(b[0])}`} onClick={() => onChange(bills.filter((x) => x[0] !== b[0]))}><Trash2 size={15} /></IconButton></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
