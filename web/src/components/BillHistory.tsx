import { useState } from 'react'
import { useStore } from '../lib/store'
import { billInsights, DEFAULT_BILL } from '../lib/bill'
import { fmtPkr } from '../lib/format'
import { monthName } from './BillCard'
import { Card, CardHeader, cn } from './ui/ui'

const TONE = { good: 'var(--good)', warn: 'var(--warn)', crit: 'var(--crit)', info: 'var(--text-3)' }

/** Units of every bill entered, with the protected limit, and what they show. */
export function BillHistory() {
  const cfg = useStore((s) => s.bill) ?? DEFAULT_BILL
  const hist = cfg.hist
  const [sel, setSel] = useState<number | null>(null)
  if (hist.length < 2) return null
  const limit = cfg.ps[cfg.ps.length - 1][0]
  const max = Math.max(limit * 1.15, ...hist.map((b) => b[1]))
  const W = 100 / hist.length
  const y = (u: number) => 100 - (u / max) * 100
  return (
    <Card>
      <CardHeader title="Your bills" sub={`${hist.length} months from ${monthName(hist[0][0])} to ${monthName(hist[hist.length - 1][0])} · units per bill`}
        info={<p>From the bills entered in System → Bill settings. Bars turn amber at {limit - 25}+ units and red above {limit}. The dashed line is the protected limit.</p>} />
      <div className="relative h-44">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="absolute inset-0 h-full w-full overflow-visible" role="img" aria-label="Units per bill">
          {hist.map((b, i) => (
            <rect key={b[0]} x={i * W + W * 0.15} width={W * 0.7} y={y(b[1])} height={100 - y(b[1])} rx={0.8}
              fill={b[1] > limit ? 'var(--crit)' : b[1] >= limit - 25 ? 'var(--warn)' : 'var(--grid)'}
              opacity={sel == null ? (i === hist.length - 1 ? 1 : 0.8) : sel === i ? 1 : 0.35} className="cursor-pointer" onClick={() => setSel(sel === i ? null : i)}>
              <title>{`${monthName(b[0], { month: 'long', year: 'numeric' })}: ${b[1]} units${b[2] ? ', ' + fmtPkr(b[2]) : ''}`}</title>
            </rect>
          ))}
          <line x1="0" x2="100" y1={y(limit)} y2={y(limit)} stroke="var(--crit)" strokeWidth="0.6" strokeDasharray="2 1.5" vectorEffect="non-scaling-stroke" />
        </svg>
        <span className="num absolute right-0 -translate-y-full text-[11px] font-semibold text-crit" style={{ top: `${y(limit)}%` }}>{limit}</span>
      </div>
      <div className="num mt-1 flex text-[10px] text-text-3">
        {hist.map((b, i) => (
          <span key={b[0]} className="text-center" style={{ width: `${W}%` }}>{(i % Math.ceil(hist.length / 10) === 0 && i < hist.length - 2) || i === hist.length - 1 ? monthName(b[0], { month: 'short' }).slice(0, 3) : ''}</span>
        ))}
      </div>
      {sel != null && hist[sel] && (() => {
        const b = hist[sel]
        const prev = hist.find((x) => x[0] === b[0] - 100)
        return (
          <div className="mt-3 flex flex-wrap items-baseline gap-x-4 gap-y-1 rounded-2xl bg-surface-2 px-4 py-3 text-sm">
            <b className="font-semibold">{monthName(b[0], { month: 'long', year: 'numeric' })}</b>
            <span className="num"><b className={b[1] > limit ? 'text-crit' : ''}>{b[1]}</b> units</span>
            {b[2] > 0 && <span className="num">{fmtPkr(b[2])} · Rs {(b[2] / b[1]).toFixed(1)}/unit</span>}
            <span className="text-text-3">{b[1] > limit ? 'over the protected limit' : `${limit - b[1]} units under ${limit}`}{prev ? ` · ${b[1] - prev[1] >= 0 ? '+' : ''}${b[1] - prev[1]} units vs a year before` : ''}</span>
          </div>
        )
      })()}
      <p className="mt-2 text-xs text-text-3">Tap a bar to see that bill.</p>
      <ul className="mt-3 grid gap-1.5 text-sm">
        {billInsights(hist, limit).map((x, i) => (
          <li key={i} className="flex items-start gap-2.5 leading-relaxed">
            <span className="mt-[0.45em] size-2.5 shrink-0 rounded-full" style={{ background: TONE[x.tone] }} />
            <span className={cn('text-text-2')}>{x.text}</span>
          </li>
        ))}
      </ul>
    </Card>
  )
}
