import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import type { ReactNode } from 'react'

// Opening line of every tab in the cosmos style: small spaced kicker, large title, one plain sentence.
// Drifts up and fades as the page scrolls, like the Live hero.
export function PageHeader({ kicker, title, sub }: { kicker: string; title: ReactNode; sub: ReactNode }) {
  const reduce = useReducedMotion()
  const { scrollY } = useScroll()
  const opacity = useTransform(scrollY, [0, 220], [1, 0])
  const y = useTransform(scrollY, [0, 220], [0, -40])
  return (
    <motion.header style={reduce ? undefined : { opacity, y }} className="pb-2 pt-4 md:pb-4 md:pt-8">
      <motion.p initial={{ opacity: 0, y: 10, filter: 'blur(6px)' }} animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }} transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
        className="hero-ink text-[11px] font-semibold uppercase tracking-[0.28em] text-text-2">{kicker}</motion.p>
      <motion.h2 initial={{ opacity: 0, y: 18, filter: 'blur(8px)' }} animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }} transition={{ duration: 0.8, delay: 0.06, ease: [0.22, 1, 0.36, 1] }}
        className="page-title mt-2 text-[clamp(34px,7vw,58px)] font-semibold leading-[1.02] tracking-[-0.035em]">{title}</motion.h2>
      <motion.p initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.7, delay: 0.14 }}
        className="hero-ink mt-2 max-w-[52ch] text-[15px] leading-snug text-text-2">{sub}</motion.p>
    </motion.header>
  )
}
