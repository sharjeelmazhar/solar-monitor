import { BarChart3, CalendarClock, Cpu, Gauge, Monitor, Moon, PlugZap, Sun } from 'lucide-react'
import { MotionConfig, motion } from 'motion/react'
import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react'
import { IconButton, cn } from './components/ui/ui'
import { hhmmss } from './lib/format'
import { use3d, useClock, useTheme, type Theme } from './lib/prefs'
import { useStale, useStore } from './lib/store'
import EnergyPage from './pages/EnergyPage'
import HistoryPage from './pages/HistoryPage'
import OutagesPage from './pages/OutagesPage'
import OverviewPage from './pages/OverviewPage'
import SystemPage from './pages/SystemPage'

const CosmosBackdrop = lazy(() => import('./components/cosmos/CosmosBackdrop'))

const TABS = [
  { id: 'overview', label: 'Live', icon: Gauge },
  { id: 'history', label: 'History', icon: CalendarClock },
  { id: 'energy', label: 'Energy', icon: BarChart3 },
  { id: 'outages', label: 'Outages', icon: PlugZap },
  { id: 'system', label: 'System', icon: Cpu },
] as const
type Tab = (typeof TABS)[number]['id']

function useRoute(): [Tab, (t: Tab) => void] {
  const get = () => (TABS.find((t) => location.hash === '#/' + t.id)?.id ?? 'overview') as Tab
  const [tab, setTab] = useState<Tab>(get)
  useEffect(() => {
    const on = () => setTab(get())
    addEventListener('hashchange', on)
    return () => removeEventListener('hashchange', on)
  }, [])
  return [tab, (t) => { location.hash = '/' + t; window.scrollTo({ top: 0 }) }]
}

export default function App() {
  const [tab, go] = useRoute()
  const { theme, setTheme, dark } = useTheme()
  const [h12] = useClock() // re-render every page when the clock format changes
  const name = useStore((s) => s.info?.name)
  const title = name && name !== 'Solar' ? name : 'Solar Monitor'
  useEffect(() => { document.title = title }, [title])
  const nextTheme: Record<Theme, Theme> = { system: dark ? 'light' : 'dark', light: 'dark', dark: 'system' }
  const ThemeIcon = theme === 'system' ? Monitor : theme === 'dark' ? Moon : Sun
  const [fx3d, setFx] = use3d()
  useEffect(() => { document.documentElement.classList.toggle('cosmos-3d', fx3d) }, [fx3d])
  useSpotlight()
  const [slowTip, setSlowTip] = useSlowTip()

  let page: ReactNode
  if (tab === 'history') page = <HistoryPage />
  else if (tab === 'energy') page = <EnergyPage />
  else if (tab === 'outages') page = <OutagesPage />
  else if (tab === 'system') page = <SystemPage theme={theme} setTheme={setTheme} />
  else page = <OverviewPage dark={dark} />

  return (
    <MotionConfig reducedMotion="user">
    {/* the universe behind every page (WebGL); without 3D a painted starfield in CSS takes its place */}
    {fx3d && <Suspense fallback={null}><CosmosBackdrop tab={TABS.findIndex((t) => t.id === tab)} light={!dark} /></Suspense>}
    <div className="relative z-[1] mx-auto min-h-dvh max-w-[1400px] px-4 pb-[calc(88px+env(safe-area-inset-bottom))] sm:px-6 md:pb-10">
      <header className="sticky top-0 z-30 mb-4 pt-[env(safe-area-inset-top)]">
        {/* edge-to-edge frosted backdrop that fades out at the bottom: no box, no border */}
        <div aria-hidden className="pointer-events-none absolute -bottom-7 left-1/2 top-0 -z-10 w-screen -translate-x-1/2 backdrop-blur-xl"
          style={{ background: 'linear-gradient(to bottom, var(--header-veil) calc(100% - 28px), transparent)', maskImage: 'linear-gradient(to bottom, #000 calc(100% - 28px), transparent)', WebkitMaskImage: 'linear-gradient(to bottom, #000 calc(100% - 28px), transparent)' }} />
        <div className="flex min-h-[72px] items-center gap-3">
          <Logo />
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-lg font-semibold tracking-tight">{title}</h1>
            <Clock />
          </div>
          <nav className="hidden items-center gap-0.5 md:flex" aria-label="Sections">
            {TABS.map((t) => (
              <button key={t.id} onClick={() => go(t.id)} aria-current={tab === t.id ? 'page' : undefined}
                className={cn('focus-ring relative flex min-h-9 items-center gap-2 rounded-xl px-3 text-sm font-medium transition-colors', tab === t.id ? 'text-text' : 'text-text-2 hover:text-text')}>
                {tab === t.id && <motion.span layoutId="tab" className="absolute inset-0 rounded-xl bg-surface-3" transition={{ type: 'spring', bounce: 0.15, duration: 0.4 }} />}
                <t.icon size={16} className="relative" />
                <span className="relative">{t.label}</span>
              </button>
            ))}
          </nav>
          <StatusPill />
          <IconButton label={`Theme: ${theme}`} onClick={() => setTheme(nextTheme[theme])}><ThemeIcon size={18} /></IconButton>
        </div>
      </header>

      {/* entry-only fade: an exit animation can stall in throttled/background tabs and block the new page */}
      <motion.main key={tab + (h12 ? 12 : 24)} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.2 }}>
        {page}
      </motion.main>

      {/* credit at the end of every page, same as the Android app */}
      <footer className="mt-6 flex justify-center">
        <a href="https://github.com/sharjeelmazhar" target="_blank" rel="noopener"
          className="focus-ring inline-flex min-h-10 items-center gap-1 rounded-full px-4 text-xs text-text-3 transition-colors hover:text-text">
          Developed by <span className="font-bold tracking-wider text-text">SMR</span> <span aria-hidden>↗</span>
        </a>
      </footer>

      {slowTip && (
        <motion.div role="status" initial={{ opacity: 0, y: 16, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ type: 'spring', bounce: 0.3, duration: 0.6 }}
          className="glass-chip fixed bottom-[calc(84px+env(safe-area-inset-bottom))] left-1/2 z-40 flex w-max max-w-[calc(100vw-32px)] -translate-x-1/2 items-center gap-3 rounded-full py-1.5 pl-4 pr-1.5 text-[13px] md:bottom-6">
          <span className="text-text-2">3D looks slow on this device</span>
          <button className="focus-ring min-h-8 rounded-full bg-white/10 px-3 font-medium text-text hover:bg-white/20" onClick={() => { setFx(false); setSlowTip(false) }}>Turn off</button>
          <button aria-label="Dismiss" className="focus-ring grid size-8 place-items-center rounded-full text-text-3 hover:text-text" onClick={() => setSlowTip(false)}>✕</button>
        </motion.div>
      )}

      <nav className="glass-nav fixed left-1/2 bottom-[calc(12px+env(safe-area-inset-bottom))] z-30 flex w-[75%] max-w-[440px] -translate-x-1/2 rounded-full p-[5px] md:hidden" aria-label="Sections">
        {TABS.map((t) => (
          <button key={t.id} onClick={() => go(t.id)} aria-current={tab === t.id ? 'page' : undefined}
            className={cn('focus-ring relative flex min-h-[50px] flex-1 flex-col items-center justify-center gap-0.5 rounded-full text-[10.5px] font-medium', tab === t.id ? 'text-text' : 'text-text-3')}>
            {tab === t.id && <motion.span layoutId="mtab" className="absolute inset-0 rounded-full bg-black/[0.07] ring-1 ring-white/60 dark:bg-white/[0.14] dark:ring-white/20" transition={{ type: 'spring', bounce: 0.15, duration: 0.4 }} />}
            <t.icon size={18} className="relative" />
            <span className="relative">{t.label}</span>
          </button>
        ))}
      </nav>
    </div>
    </MotionConfig>
  )
}

/** Small tip (once per browser) when the 3D background can't keep up even at its lowest quality. */
function useSlowTip() {
  const [show, setShow] = useState(false)
  useEffect(() => {
    const on = () => {
      try { if (localStorage.getItem('slowTipShown')) return; localStorage.setItem('slowTipShown', '1') } catch { /* private mode */ }
      setShow(true)
    }
    addEventListener('cosmos-slow', on)
    return () => removeEventListener('cosmos-slow', on)
  }, [])
  useEffect(() => { if (!show) return; const t = setTimeout(() => setShow(false), 14000); return () => clearTimeout(t) }, [show])
  return [show, setShow] as const
}

/** Cards light up softly under the mouse (a radial highlight that follows the pointer). Mouse/trackpad only. */
function useSpotlight() {
  useEffect(() => {
    if (!matchMedia('(hover: hover) and (pointer: fine)').matches) return
    let lastEl: HTMLElement | null = null
    const on = (e: PointerEvent) => {
      const el = (e.target as HTMLElement | null)?.closest?.('.glass') as HTMLElement | null
      if (lastEl && lastEl !== el) lastEl.style.removeProperty('--spot')
      lastEl = el
      if (!el) return
      const r = el.getBoundingClientRect()
      el.style.setProperty('--mx', `${e.clientX - r.left}px`)
      el.style.setProperty('--my', `${e.clientY - r.top}px`)
      el.style.setProperty('--spot', '1')
    }
    addEventListener('pointermove', on, { passive: true })
    return () => removeEventListener('pointermove', on)
  }, [])
}

function Logo() {
  return (
    <div className="relative grid size-10 shrink-0 place-items-center rounded-2xl bg-surface-2">
      <span className="absolute inset-1 rounded-xl bg-solar/20 blur-md" />
      <svg viewBox="0 0 32 32" className="relative size-6" aria-hidden>
        <circle cx="16" cy="16" r="6.5" fill="var(--solar)" />
        <g stroke="var(--solar)" strokeWidth="2.6" strokeLinecap="round">
          <path d="M16 2.5v3.5M16 26v3.5M2.5 16H6M26 16h3.5M6.5 6.5l2.4 2.4M23.1 23.1l2.4 2.4M6.5 25.5l2.4-2.4M23.1 8.9l2.4-2.4" />
        </g>
      </svg>
    </div>
  )
}

function Clock() {
  const t = useStore((s) => s.live?.t ?? 0)
  const at = useStore((s) => s.lastMsgAt)
  const [, tick] = useState(0)
  useEffect(() => {
    const i = setInterval(() => tick((x) => x + 1), 1000)
    return () => clearInterval(i)
  }, [])
  const now = t ? new Date(t + (performance.now() - at)) : new Date()
  return (
    <p className="num truncate text-xs text-text-3">
      {hhmmss(now.getTime())} · {now.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}
    </p>
  )
}

function StatusPill() {
  const conn = useStore((s) => s.conn)
  const ok = useStore((s) => s.live?.ok)
  const ever = useStore((s) => s.live?.ever)
  const at = useStore((s) => s.lastMsgAt)
  const stale = useStale() // also re-renders every second
  const hasData = useStore((s) => !!s.live)
  const ago = at ? Math.round((performance.now() - at) / 1000) : null
  let text = 'Connecting'
  let tone = 'var(--text-3)'
  let pulse = false
  if (conn === 'offline' || (hasData && stale != null)) { text = 'Offline'; tone = 'var(--crit)' }
  else if (ever === false) { text = 'No inverter'; tone = 'var(--crit)' }
  else if (ok === false) { text = 'No data'; tone = 'var(--crit)' }
  else if (conn === 'reconnecting') { text = 'Reconnecting'; tone = 'var(--warn)' }
  else if (conn === 'live' && ago != null) {
    if (ago > 10) { text = `${ago}s ago`; tone = 'var(--warn)' } else { text = 'Online'; tone = 'var(--good)'; pulse = true }
  }
  return (
    <div className="flex min-h-10 items-center gap-2 rounded-full bg-surface-2 px-3.5 text-sm font-medium" role="status" aria-live="polite">
      <span className="relative flex size-2.5">
        {pulse && <span className="absolute inline-flex size-full animate-ping rounded-full opacity-60" style={{ background: tone }} />}
        <span className="relative inline-flex size-2.5 rounded-full" style={{ background: tone }} />
      </span>
      <span className="hidden sm:inline">{text}</span>
      <span className="sr-only sm:hidden">{text}</span>
    </div>
  )
}
