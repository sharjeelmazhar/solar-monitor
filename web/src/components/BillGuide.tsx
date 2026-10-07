import guide from '../assets/bill-guide.svg'

const STEPS: [string, string][] = [
  ['Bill month', 'the month to choose under Your bills'],
  ['Reading date', 'its day (8) is the Meter reading day'],
  ['Load', 'Sanctioned load in kW'],
  ['Units', 'the units of that bill'],
  ['Payable within due date', 'the amount of that bill'],
  ['FPA … @ 2.0581', 'Fuel adjustment, Rs per unit'],
  ['QTR. Tariff Adj.', 'divide by the units: −235.72 ÷ 166 = −1.42 Rs per unit'],
  ['F.C Surcharge', 'divide by the units: 71.38 ÷ 166 = 0.43 Rs per unit'],
  ['Month / Units / Bill table', 'the last 12 months: add each one under Your bills'],
]

/** Where each value is on an IESCO bill: an illustration (no real personal data) with numbered boxes. */
export function BillGuide() {
  return (
    <div className="grid gap-3">
      <img src={guide} alt="Illustration of an IESCO bill with the fields to copy highlighted" className="w-full rounded-2xl border border-border" />
      <ol className="grid gap-1.5 text-sm">
        {STEPS.map(([k, v], i) => (
          <li key={k} className="flex gap-2.5">
            <span className="grid size-6 shrink-0 place-items-center rounded-full bg-[#e5007a] text-xs font-bold text-white">{i + 1}</span>
            <span><b className="font-semibold text-text">{k}</b>: {v}</span>
          </li>
        ))}
      </ol>
      <p className="text-xs text-text-3">Illustration of the 2026 IESCO bill layout; the numbers are an example.</p>
    </div>
  )
}
