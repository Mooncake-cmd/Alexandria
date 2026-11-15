import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { afterBackDelta, alignDelta, maskStart, nextDelta, turnDirection } from '../core/pages'
import { FONTS, LINE_WIDTHS, MARGINS, THEMES, hex, type Comfort } from '../core/comfort'
import { visibleLines } from './lines'
import type { TextContent } from './textContent'
import { MAX_TEXT_SIZE, MIN_TEXT_SIZE } from './prefs'

/** Margens da janela de leitura no modo páginas (como o padding da lista do app). */
const WIN_TOP = 16
const WIN_BOTTOM = 24

export interface TextReaderHandle {
  fraction: () => number
}

interface Props {
  content: TextContent
  paged: boolean
  textSize: number
  comfort: Comfort
  /** Posição salva (0 a 10000). */
  start: number
  onPosition: (fraction: number, atEnd: boolean) => void
  onTextSize: (size: number) => void
  handle?: (h: TextReaderHandle) => void
}

/**
 * Leitor de texto corrido (TXT e DOCX). Modo páginas: cada página termina na última linha
 * inteira e a próxima começa na linha seguinte (setas, Page Down, espaço, roda do mouse ou
 * clique duplo nas bordas). Rolagem contínua: rola livremente. A posição é uma fração do texto.
 */
export function TextReader({ content, paged, textSize, comfort, start, onPosition, onTextSize, handle }: Props) {
  const scroller = useRef<HTMLDivElement>(null)
  const blocks = useRef<HTMLElement[]>([])
  const [mask, setMask] = useState<{ top: number; bottom: number }>({ top: 0, bottom: 1e6 })
  const readCover = useRef<{ y: number; at: number } | null>(null)
  const restoring = useRef(true)

  const el = () => scroller.current!
  const winBottom = () => el().clientHeight - WIN_BOTTOM
  const atEnd = () => el().scrollTop + el().clientHeight >= el().scrollHeight - 2
  const lines = () => visibleLines(el(), blocks.current)

  /** Bloco na linha de leitura e quanto dele já passou: fração de 0 a 10000 (como o app). */
  const fraction = useCallback((): number => {
    const s = scroller.current
    const list = blocks.current
    if (!s || !list.length) return 0
    if (atEnd()) return 10000
    const top = s.getBoundingClientRect().top + WIN_TOP
    // Busca binária pelo bloco que contém a linha de leitura.
    let lo = 0
    let hi = list.length - 1
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1
      if (list[mid].getBoundingClientRect().top <= top) lo = mid
      else hi = mid - 1
    }
    const r = list[lo].getBoundingClientRect()
    const passed = r.height > 0 ? Math.min(1, Math.max(0, (top - r.top) / r.height)) : 0
    return Math.min(10000, Math.max(0, Math.round(((lo + passed) / list.length) * 10000)))
  }, [])

  const updateMask = useCallback(() => {
    const s = scroller.current
    if (!s || !paged) return
    if (readCover.current && readCover.current.at !== s.scrollTop) readCover.current = null
    setMask({ top: readCover.current?.y ?? WIN_TOP, bottom: maskStart(lines(), WIN_TOP, winBottom()) })
  }, [paged])

  /** Vai para a fração dada; no modo páginas, a primeira linha fica inteira no topo. */
  const restore = useCallback((value: number) => {
    const list = blocks.current
    const s = scroller.current
    if (!s || !list.length) return
    restoring.current = true
    const place = () => {
      const exact = (Math.min(10000, Math.max(0, value)) * list.length) / 10000 + 1e-6
      const index = Math.min(list.length - 1, Math.floor(exact))
      const target = list[index]
      const y = target.getBoundingClientRect().top - s.getBoundingClientRect().top + s.scrollTop
      s.scrollTop = Math.round(y - WIN_TOP + (exact - index) * target.getBoundingClientRect().height)
    }
    place()
    // Os blocos perto do ponto só ganham a altura real depois de desenhados: confere de novo.
    requestAnimationFrame(() => {
      place()
      requestAnimationFrame(() => {
        if (paged) {
          const d = Math.round(alignDelta(lines(), WIN_TOP, winBottom()))
          if (d) s.scrollTop += d
        }
        restoring.current = false
        updateMask()
        onPosition(fraction(), atEnd())
      })
    })
  }, [paged, updateMask])

  /** Vira a página: a próxima começa na linha seguinte à última inteira desta. */
  const turn = useCallback((direction: 1 | -1) => {
    const s = scroller.current
    if (!s) return
    readCover.current = null
    if (direction > 0) {
      if (atEnd()) return
      const ls = lines()
      const delta = nextDelta(ls, WIN_TOP, winBottom())
      const target = ls.find((l) => l.top >= WIN_TOP + delta - 0.5)
      const contentY = target ? target.top + s.scrollTop : null
      s.scrollTop += Math.round(delta)
      // Perto do fim o texto não sobe tudo: cobre as linhas já lidas acima da que abre a página.
      if (contentY !== null) {
        const now = contentY - s.scrollTop
        if (now > WIN_TOP + 1) readCover.current = { y: now, at: s.scrollTop }
      }
    } else {
      if (s.scrollTop <= 0) return
      s.scrollTop -= winBottom() - WIN_TOP
      const d = Math.round(afterBackDelta(lines(), WIN_TOP, winBottom()))
      if (d) s.scrollTop += d
    }
    updateMask()
    s.animate?.([{ opacity: 0.6 }, { opacity: 1 }], { duration: 160 })
  }, [updateMask])

  handle?.({ fraction })

  // Abre na posição salva; muda de modo ou de letra mantendo o ponto de leitura.
  const lastFraction = useRef(start)
  useLayoutEffect(() => {
    restore(lastFraction.current)
  }, [content, paged, textSize, comfort.lineSpacing, comfort.letterSpacing, comfort.margin, comfort.lineWidth, comfort.font])

  // Posição, fim do livro e cobertura acompanham a rolagem.
  useEffect(() => {
    const s = el()
    let frame = 0
    const onScroll = () => {
      if (frame) return
      frame = requestAnimationFrame(() => {
        frame = 0
        updateMask()
        if (!restoring.current) {
          lastFraction.current = fraction()
          onPosition(lastFraction.current, atEnd())
        }
      })
    }
    s.addEventListener('scroll', onScroll, { passive: true })
    const resize = new ResizeObserver(() => {
      if (!restoring.current) restore(lastFraction.current)
    })
    resize.observe(s)
    return () => {
      s.removeEventListener('scroll', onScroll)
      resize.disconnect()
      cancelAnimationFrame(frame)
    }
  }, [paged, updateMask, restore])

  // Teclado: setas, Page Up/Down, espaço, Home/End; Ctrl + "+"/"−" muda a letra.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.closest('input, textarea, [contenteditable], .modal')) return
      if ((e.ctrlKey || e.metaKey) && (e.key === '+' || e.key === '=' || e.key === '-')) {
        e.preventDefault()
        onTextSize(Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, textSize + (e.key === '-' ? -1 : 1))))
        return
      }
      if (!paged) return
      const next = ['ArrowRight', 'ArrowDown', 'PageDown'].includes(e.key) || (e.key === ' ' && !e.shiftKey)
      const prev = ['ArrowLeft', 'ArrowUp', 'PageUp'].includes(e.key) || (e.key === ' ' && e.shiftKey)
      if (next || prev) {
        e.preventDefault()
        turn(next ? 1 : -1)
      } else if (e.key === 'Home' || e.key === 'End') {
        e.preventDefault()
        restore(e.key === 'Home' ? 0 : 10000)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [paged, textSize, turn, restore])

  // Roda do mouse: no modo páginas vira a página; Ctrl + roda muda a letra.
  useEffect(() => {
    const s = el()
    let accumulated = 0
    let lastTurn = 0
    const onWheel = (e: WheelEvent) => {
      if (e.ctrlKey || e.metaKey) {
        e.preventDefault()
        accumulated += e.deltaY
        if (Math.abs(accumulated) >= 60) {
          onTextSize(Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, textSize + (accumulated < 0 ? 1 : -1))))
          accumulated = 0
        }
        return
      }
      if (!paged) return
      e.preventDefault()
      accumulated += e.deltaY
      const now = performance.now()
      if (Math.abs(accumulated) >= 40 && now - lastTurn > 280) {
        turn(accumulated > 0 ? 1 : -1)
        accumulated = 0
        lastTurn = now
      }
    }
    s.addEventListener('wheel', onWheel, { passive: false })
    return () => s.removeEventListener('wheel', onWheel)
  }, [paged, textSize, turn])

  blocks.current.length = content.chunks.length
  const theme = THEMES[comfort.theme]
  const chars = LINE_WIDTHS[comfort.lineWidth].chars
  const font = FONTS[comfort.font].css
  const style = {
    '--page-bg': hex(theme.background),
    '--page-text': hex(theme.text),
    '--page-link': hex(theme.link),
    fontSize: `${textSize}px`,
    lineHeight: comfort.lineSpacing / 100,
    letterSpacing: `${comfort.letterSpacing / 100}em`,
    textAlign: comfort.justify ? 'justify' : 'left',
    ...(font ? { fontFamily: font } : {})
  } as Record<string, string | number>
  const column = {
    maxWidth: chars ? `${chars * 0.5}em` : 'none',
    paddingLeft: `${MARGINS[comfort.margin].px}px`,
    paddingRight: `${MARGINS[comfort.margin].px}px`
  }

  return (
    <div class="text-reader" style={style}>
      <div
        ref={scroller}
        class={paged ? 'text-scroller paged' : 'text-scroller'}
        style={{ paddingTop: `${WIN_TOP}px`, paddingBottom: `${WIN_BOTTOM}px` }}
        tabIndex={0}
        aria-label="Texto do livro"
        onDblClick={(e) => {
          if (!paged) return
          const box = el().getBoundingClientRect()
          const direction = turnDirection(e.clientX - box.left, box.width)
          if (direction !== 0) {
            window.getSelection()?.removeAllRanges()
            turn(direction)
          }
        }}
      >
        <div class="text-column" style={column}>
          {content.chunks.map((chunk, i) => {
            const ref = (node: HTMLElement | null) => {
              if (node) blocks.current[i] = node
            }
            if (chunk.kind === 'image') return <div key={i} ref={ref} data-kind="image" class="chunk chunk-image"><img src={chunk.url} alt="" /></div>
            if (chunk.kind === 'html') return <div key={i} ref={ref} class="chunk chunk-html" dangerouslySetInnerHTML={{ __html: chunk.html }} />
            return <div key={i} ref={ref} class="chunk chunk-text">{chunk.text}</div>
          })}
        </div>
      </div>
      {paged && (
        <>
          <div class="page-mask" style={{ top: 0, height: `${mask.top}px` }} aria-hidden="true" />
          <div class="page-mask" style={{ top: `${mask.bottom}px`, bottom: 0 }} aria-hidden="true" />
        </>
      )}
    </div>
  )
}
