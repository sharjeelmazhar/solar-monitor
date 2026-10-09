import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { fmtW } from '../../lib/format'
import { useBattIdle } from '../../lib/prefs'
import { battState, sourcesSentence } from '../../lib/power'
import { sunPhase } from '../../lib/sun'
import type { Live } from '../../lib/types'
import { Value, cn } from '../ui/ui'

// Opening section of the Live page: the solar number floats in front of the 3D sun and drifts away
// (fade, lift, slight 3D tilt) as the page scrolls, handing over to the cards below.

export function Hero({ d, offline, noBatt }: { d: Live | null; offline: boolean; noBatt: boolean }) {
  const reduce = useReducedMotion()
  const { scrollY } = useScroll()
  const opacity = useTransform(scrollY, [0, 320], [1, 0])
  const y = useTransform(scrollY, [0, 320], [0, -70])
  const rotateX = useTransform(scrollY, [0, 320], [0, 18])
  const scale = useTransform(scrollY, [0, 320], [1, 0.94])
  const [, , idleW] = useBattIdle()
  const night = sunPhase() === 'night'
  const live = !!d?.ever && !offline

  const kicker = !d ? 'Connecting to your monitor' : !d.ever ? 'Waiting for the inverter' : offline ? 'Last reading' : night ? 'Solar right now · night' : 'Solar right now'
  const sentence = live ? sourcesSentence(d!, idleW) : !d ? 'Looking for the solar monitor on this Wi-Fi…' : offline ? 'The monitor stopped answering. These are the last values.' : 'The monitor is online, the inverter has not answered yet.'
  const bs = d?.ever ? battState(d, idleW) : 'idle'

  return (
    <motion.section
      style={reduce ? undefined : { opacity, y, rotateX, scale, transformPerspective: 900 }}
      className="relative flex min-h-[54svh] flex-col justify-end pb-4 pt-6 md:min-h-[46svh] md:justify-center md:pb-8"
      aria-label="Solar right now"
    >
      <motion.p initial={{ opacity: 0, y: 12, filter: 'blur(6px)' }} animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }} transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
        className="hero-ink text-[11px] font-semibold uppercase tracking-[0.28em] text-text-2">
        {kicker}
      </motion.p>
      <motion.h2 initial={{ opacity: 0, y: 24, filter: 'blur(10px)' }} animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }} transition={{ duration: 0.9, delay: 0.08, ease: [0.22, 1, 0.36, 1] }}
        className="hero-figure mt-2 text-[clamp(56px,15vw,132px)] leading-[0.92] tracking-[-0.05em]">
        {d?.ever ? <Value text={fmtW(d.pvW)} unitClass="hero-unit" /> : <span className="num font-semibold">—</span>}
      </motion.h2>
      <motion.p initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.8, delay: 0.18 }}
        className="hero-ink mt-3 max-w-[34ch] text-[15px] leading-snug text-text-2 md:text-base">
        {sentence}
      </motion.p>
      {d?.ever && (
        <motion.div initial="h" animate="s" transition={{ staggerChildren: 0.07, delayChildren: 0.28 }} className="mt-5 flex flex-wrap gap-2">
          <Chip tone="load" label="Home" value={fmtW(d.loadW)} />
          {!noBatt && <Chip tone="batt" label={bs === 'charging' ? 'Charging' : bs === 'discharging' ? 'Battery giving' : 'Battery'} value={`${d.battPct}%`} />}
          <Chip tone="grid" label="Grid" value={d.gridOn ? (d.gridW > 15 ? fmtW(d.gridW) : 'On') : 'Off'} dim={!d.gridOn} />
          {d.today.pvPeak > 0 && <Chip tone="solar" label="Peak today" value={fmtW(d.today.pvPeak)} />}
        </motion.div>
      )}
      <div aria-hidden className="hero-scroll mt-7 hidden items-center gap-2 text-[11px] uppercase tracking-[0.24em] text-text-3 md:flex">
        <span className="hero-scroll-line" /> Scroll through your system
      </div>
    </motion.section>
  )
}

function Chip({ tone, label, value, dim }: { tone: string; label: string; value: string; dim?: boolean }) {
  return (
    <motion.div variants={{ h: { opacity: 0, y: 14, scale: 0.96 }, s: { opacity: 1, y: 0, scale: 1 } }} transition={{ type: 'spring', bounce: 0.25, duration: 0.6 }}
      className={cn('glass-chip flex items-center gap-2 rounded-full py-1.5 pl-2.5 pr-3.5 text-[13px]', dim && 'opacity-60')}>
      <span className="size-2 rounded-full" style={{ background: `var(--${tone})`, boxShadow: `0 0 10px var(--${tone})` }} />
      <span className="text-text-2">{label}</span>
      <span className="num font-semibold">{value}</span>
    </motion.div>
  )
}
