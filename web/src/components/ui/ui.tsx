import { Dialog } from '@base-ui/react/dialog'
import { Switch as BaseSwitch } from '@base-ui/react/switch'
import { clsx, type ClassValue } from 'clsx'
import { Maximize2, X } from 'lucide-react'
import { animate, motion, useMotionValue, useReducedMotion, useTransform } from 'motion/react'
import { useEffect, useId, useState, type ReactNode } from 'react'
import { twMerge } from 'tailwind-merge'
import { splitUnit } from '../../lib/format'

export const cn = (...c: ClassValue[]) => twMerge(clsx(c))

export function Card({ className, children, ...rest }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={cn('glass rounded-3xl p-4 sm:p-5', className)} {...rest}>
      {children}
    </div>
  )
}

export function CardHeader({ title, sub, action, icon }: { title: ReactNode; sub?: ReactNode; action?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="mb-3 flex min-h-9 flex-wrap items-center gap-x-3 gap-y-2">
      {icon && <span className="text-text-2">{icon}</span>}
      <div className="min-w-0 flex-1">
        <h2 className="truncate text-[15px] font-semibold tracking-tight">{title}</h2>
        {sub && <p className="truncate text-xs text-text-3">{sub}</p>}
      </div>
      {action}
    </div>
  )
}

/** Value with a smaller unit, counting smoothly between updates. */
export function Value({ text, className, unitClass }: { text: string; className?: string; unitClass?: string }) {
  const [num, unit] = splitUnit(text)
  const n = Number(num)
  return (
    <span className={cn('num font-semibold', className)}>
      {Number.isFinite(n) && num !== '' ? <Counter value={n} decimals={(num.split('.')[1] ?? '').length} /> : num}
      {unit && <span className={cn('ml-1 text-[0.55em] font-medium text-text-2', unitClass)}>{unit}</span>}
    </span>
  )
}

function Counter({ value, decimals }: { value: number; decimals: number }) {
  const reduce = useReducedMotion()
  const mv = useMotionValue(value)
  const text = useTransform(mv, (v) => v.toFixed(decimals))
  useEffect(() => {
    if (reduce) {
      mv.set(value)
      return
    }
    const c = animate(mv, value, { duration: 0.45, ease: 'easeOut' })
    return () => c.stop()
  }, [value, mv, reduce])
  return <motion.span>{text}</motion.span>
}

export function Segmented<T extends string | number>({
  value, options, onChange, label, className,
}: { value: T; options: { value: T; label: ReactNode }[]; onChange: (v: T) => void; label: string; className?: string }) {
  const id = useId()
  return (
    <div role="radiogroup" aria-label={label} className={cn('inline-flex rounded-2xl bg-surface-2 p-1', className)}>
      {options.map((o) => {
        const on = o.value === value
        return (
          <button
            key={String(o.value)}
            role="radio"
            aria-checked={on}
            onClick={() => onChange(o.value)}
            className={cn('focus-ring relative min-h-11 rounded-xl px-3 text-sm font-medium transition-colors md:min-h-9', on ? 'text-text' : 'text-text-2 hover:text-text')}
          >
            {on && <motion.span layoutId={id} className="absolute inset-0 rounded-xl bg-surface-solid shadow-sm ring-1 ring-border" transition={{ type: 'spring', bounce: 0.15, duration: 0.4 }} />}
            <span className="relative">{o.label}</span>
          </button>
        )
      })}
    </div>
  )
}

export function IconButton({ label, className, children, ...rest }: React.ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return (
    <button aria-label={label} title={label} className={cn('focus-ring inline-grid size-10 place-items-center rounded-2xl border border-border bg-surface-2 text-text-2 transition hover:bg-surface-3 hover:text-text disabled:opacity-40', className)} {...rest}>
      {children}
    </button>
  )
}

export function Button({ variant = 'soft', className, ...rest }: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'soft' | 'primary' }) {
  return (
    <button
      className={cn(
        'focus-ring inline-flex min-h-10 items-center justify-center gap-2 rounded-2xl px-4 text-sm font-medium transition disabled:opacity-50',
        variant === 'primary' ? 'bg-text text-bg hover:opacity-90' : 'border border-border bg-surface-2 text-text hover:bg-surface-3',
        className,
      )}
      {...rest}
    />
  )
}

export function Switch({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <BaseSwitch.Root
      checked={checked}
      onCheckedChange={(v) => onChange(v)}
      aria-label={label}
      className="focus-ring relative inline-flex h-7 w-12 shrink-0 items-center rounded-full bg-surface-3 transition-colors data-[checked]:bg-load"
    >
      <BaseSwitch.Thumb className="size-5 translate-x-1 rounded-full bg-white shadow transition-transform data-[checked]:translate-x-6" />
    </BaseSwitch.Root>
  )
}

export function Modal({ open, onOpenChange, title, children, wide }: { open: boolean; onOpenChange: (o: boolean) => void; title: ReactNode; children: ReactNode; wide?: boolean }) {
  return (
    <Dialog.Root open={open} onOpenChange={(o) => onOpenChange(o)}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm transition-opacity data-[ending-style]:opacity-0 data-[starting-style]:opacity-0" />
        <Dialog.Popup
          className={cn(
            'glass fixed left-1/2 top-1/2 z-50 flex max-h-[calc(100dvh-24px)] w-[calc(100vw-24px)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-3xl !bg-surface-solid transition-all data-[ending-style]:scale-95 data-[ending-style]:opacity-0 data-[starting-style]:scale-95 data-[starting-style]:opacity-0',
            wide ? 'max-w-6xl' : 'max-w-lg',
          )}
        >
          <div className="flex items-center gap-3 border-b border-border px-5 py-3">
            <Dialog.Title className="flex-1 truncate text-base font-semibold">{title}</Dialog.Title>
            <Dialog.Close className="focus-ring inline-grid size-10 place-items-center rounded-2xl text-text-2 hover:bg-surface-2" aria-label="Close">
              <X size={18} />
            </Dialog.Close>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-5">{children}</div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** A card with a chart that can be opened full-screen. `render(height)` draws the chart. */
export function ChartCard({ title, sub, action, legend, render, height = 240, className }: {
  title: ReactNode; sub?: ReactNode; action?: ReactNode; legend?: ReactNode; render: (h: number) => ReactNode; height?: number; className?: string
}) {
  const [full, setFull] = useState(false)
  return (
    <Card className={className}>
      <CardHeader
        title={title}
        sub={sub}
        action={
          <div className="flex items-center gap-2">
            {action}
            <IconButton label="Open full screen" onClick={() => setFull(true)}>
              <Maximize2 size={16} />
            </IconButton>
          </div>
        }
      />
      {legend}
      {render(height)}
      <Modal open={full} onOpenChange={setFull} title={title} wide>
        {legend}
        {full && render(Math.max(300, Math.min(window.innerHeight - 220, 620)))}
      </Modal>
    </Card>
  )
}

export function Stat({ label, value, hint, className, tone }: { label: string; value: string; hint?: string; className?: string; tone?: string }) {
  return (
    <div className={cn('min-w-0 rounded-2xl bg-surface-2 px-4 py-3', className)}>
      <div className="flex items-center gap-2 text-xs font-medium text-text-2">
        {tone && <span className="size-2 rounded-full" style={{ background: `var(--${tone})` }} />}
        <span className="truncate">{label}</span>
      </div>
      <Value text={value} className="mt-1 block truncate text-xl" />
      {hint && <div className="mt-0.5 truncate text-xs text-text-3">{hint}</div>}
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="grid min-h-32 place-items-center rounded-2xl bg-surface-2 p-6 text-center text-sm text-text-2">{children}</div>
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded-2xl bg-surface-2', className)} />
}
