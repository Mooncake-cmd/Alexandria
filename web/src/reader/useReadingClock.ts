import { useEffect, useRef, useState } from 'preact/hooks'
import { ReadingClock, today } from '../core/days'
import { formatClock, formatReadingTime } from '../core/readingTime'
import { addReadingTime, getPref, setPref } from '../storage/library'

export interface ReadingTimer {
  label: string
  paused: boolean
  interaction: () => void
  /** Uma página (ou uma tela de texto) foi virada: o cronômetro aprende o ritmo de leitura. */
  pageTurn: () => void
  /** Pausa ou retoma (botão ⏱). Devolve true se ficou pausado. */
  toggle: () => boolean
}

/**
 * Cronômetro de leitura (como o do app): conta com o livro aberto e a aba à vista e pausa sozinho
 * pelo ritmo de leitura do livro (3 vezes o tempo normal de uma página sem tecla, clique ou
 * rolagem). A espera longa só conta se o leitor voltar antes da pausa. Grava por dia a cada 30 s e
 * ao sair; o ritmo fica guardado por livro.
 */
export function useReadingClock(itemId: number, secondsAtOpen: number): ReadingTimer {
  const clock = useRef(new ReadingClock())
  const [now, setNow] = useState(Date.now())
  const savedSession = useRef(0)

  useEffect(() => {
    let alive = true
    // O ritmo deste livro guardado na última leitura.
    getPref<number[]>(`pace:${itemId}`).then((pace) => {
      if (!alive || !pace?.length) return
      const fresh = new ReadingClock(undefined, undefined, pace)
      const old = clock.current
      fresh.start(Date.now())
      // Mantém o que já foi contado nestes primeiros instantes.
      for (const [day, seconds] of Object.entries(old.drain())) addReadingTime(itemId, day, seconds)
      clock.current = fresh
    })
    const flush = () => {
      const c = clock.current
      for (const [day, seconds] of Object.entries(c.drain())) {
        savedSession.current += seconds
        addReadingTime(itemId, day, seconds)
      }
      setPref(`pace:${itemId}`, c.pace())
    }
    const start = () => clock.current.start(Date.now())
    const stop = () => {
      const t = Date.now()
      clock.current.stop(t, today(t))
      flush()
    }
    if (!document.hidden) start()
    let ticks = 0
    const timer = setInterval(() => {
      const t = Date.now()
      if (!document.hidden) clock.current.tick(t, today(t))
      if (++ticks % 30 === 0) flush()
      setNow(t)
    }, 1000)
    const onVisibility = () => (document.hidden ? stop() : start())
    const touch = () => clock.current.interaction(Date.now())
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', stop)
    for (const type of ['keydown', 'pointerdown', 'wheel', 'scroll'] as const) window.addEventListener(type, touch, { capture: true, passive: true })
    return () => {
      alive = false
      clearInterval(timer)
      stop()
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', stop)
      for (const type of ['keydown', 'pointerdown', 'wheel', 'scroll'] as const) window.removeEventListener(type, touch, { capture: true })
    }
  }, [itemId])

  const c = clock.current
  const session = Math.floor(c.shownMs / 1000)
  const total = secondsAtOpen + session
  const paused = c.paused ? ' · pausado (clique para retomar)' : c.isIdle(now) ? ' · pausado' : ''
  return {
    label: `⏱ ${formatClock(session)} · ${formatReadingTime(total)} neste livro${paused}`,
    paused: c.paused,
    interaction: () => clock.current.interaction(Date.now()),
    pageTurn: () => {
      const t = Date.now()
      if (clock.current.paused) clock.current.resume(t)
      clock.current.pageTurn(t)
    },
    toggle: () => {
      const t = Date.now()
      const k = clock.current
      if (k.paused) k.resume(t)
      else k.pause(t, today(t))
      setNow(t)
      return k.paused
    }
  }
}
