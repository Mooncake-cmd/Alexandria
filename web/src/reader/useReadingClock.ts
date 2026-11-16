import { useEffect, useRef, useState } from 'preact/hooks'
import { ReadingClock, today } from '../core/days'
import { formatClock, formatReadingTime } from '../core/readingTime'
import { addReadingTime } from '../storage/library'

/**
 * Cronômetro de leitura (como o do app): conta com o livro aberto e a aba à vista, para depois de
 * 5 minutos sem nenhuma tecla, clique ou rolagem, e grava o tempo por dia (a cada 30 s e ao sair).
 */
export function useReadingClock(itemId: number, secondsAtOpen: number): { label: string; interaction: () => void } {
  const clock = useRef(new ReadingClock())
  const [now, setNow] = useState(Date.now())
  const savedSession = useRef(0)

  useEffect(() => {
    const c = clock.current
    const flush = () => {
      for (const [day, seconds] of Object.entries(c.drain())) {
        savedSession.current += seconds
        addReadingTime(itemId, day, seconds)
      }
    }
    const start = () => c.start(Date.now())
    const stop = () => {
      const t = Date.now()
      c.stop(t, today(t))
      flush()
    }
    if (!document.hidden) start()
    let ticks = 0
    const timer = setInterval(() => {
      const t = Date.now()
      if (!document.hidden) c.tick(t, today(t))
      if (++ticks % 30 === 0) flush()
      setNow(t)
    }, 1000)
    const onVisibility = () => (document.hidden ? stop() : start())
    const touch = () => c.interaction(Date.now())
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', stop)
    for (const type of ['keydown', 'pointerdown', 'wheel', 'scroll'] as const) window.addEventListener(type, touch, { capture: true, passive: true })
    return () => {
      clearInterval(timer)
      stop()
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', stop)
      for (const type of ['keydown', 'pointerdown', 'wheel', 'scroll'] as const) window.removeEventListener(type, touch, { capture: true })
    }
  }, [itemId])

  const c = clock.current
  const session = Math.floor(c.sessionMs / 1000)
  const total = secondsAtOpen + session
  const paused = c.isIdle(now) ? ' · pausado' : ''
  return {
    label: `⏱ ${formatClock(session)} · ${formatReadingTime(total)} neste livro${paused}`,
    interaction: () => c.interaction(Date.now())
  }
}
