import { EXACT_SCALE } from '../core/bookmarks'
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { afterBackDelta, alignDelta, maskStart, nextDelta, turnDirection } from '../core/pages'
import { FONTS, LINE_WIDTHS, MARGINS, THEMES, hex, type Comfort } from '../core/comfort'
import { visibleLines } from './lines'
import { searchRanges, setHighlight, termAtPoint, termRanges, textMap } from './textRanges'
import type { GlossaryTerm } from '../core/glossary'
import type { SearchHit } from './ToolsPanel'
import type { TextContent } from './textContent'
import { MAX_TEXT_SIZE, MIN_TEXT_SIZE } from './prefs'

/** Margens da janela de leitura no modo páginas (como o padding da lista do app). */
const WIN_TOP = 16
const WIN_BOTTOM = 24

export interface TextReaderHandle {
  fraction: () => number
  /** Posição precisa (0 a 1.000.000) e quanto dela cabe numa tela: marcadores. */
  exact: () => number
  span: () => number
  goToExact: (value: number) => void
  goTo: (fraction: number) => void
  search: (term: string) => SearchHit[]
  showHit: (hit: SearchHit, term: string) => void
  clearSearch: () => void
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
  terms?: GlossaryTerm[]
  onTerm?: (termId: number) => void
}

/**
 * Leitor de texto corrido (TXT e DOCX). Modo páginas: cada página termina na última linha
 * inteira e a próxima começa na linha seguinte (setas, Page Down, espaço, roda do mouse ou
 * clique duplo nas bordas). Rolagem contínua: rola livremente. A posição é uma fração do texto.
 */
export function TextReader({ content, paged, textSize, comfort, start, onPosition, onTextSize, handle, terms = [], onTerm }: Props) {
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
    if (!s || !blocks.current.length) return 0
    if (atEnd()) return 10000
    return Math.min(10000, Math.max(0, Math.round(fractionAt(s.getBoundingClientRect().top + WIN_TOP) * 10000)))
  }, [])

  /** Fração do texto (0 a 1) na altura [top] da tela. */
  const fractionAt = (top: number): number => {
    const list = blocks.current
    if (!list.length) return 0
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
    return Math.min(1, Math.max(0, (lo + passed) / list.length))
  }
  const exact = (): number => {
    const s = scroller.current
    if (!s || !blocks.current.length) return 0
    if (atEnd()) return 10000 * EXACT_SCALE
    return Math.round(fractionAt(s.getBoundingClientRect().top + WIN_TOP) * 10000 * EXACT_SCALE)
  }
  const span = (): number => {
    const s = scroller.current
    if (!s || !blocks.current.length) return 1
    const box = s.getBoundingClientRect()
    const top = fractionAt(box.top + WIN_TOP)
    const bottom = fractionAt(box.top + winBottom())
    return Math.max(1, Math.round((bottom - top) * 10000 * EXACT_SCALE))
  }

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

  // Glossário: sublinha os termos nos blocos perto da tela (sem mexer no texto).
  const termMarks = useRef<{ range: Range; termId: number }[]>([])
  const markTerms = useCallback(() => {
    const s = scroller.current
    if (!s) return
    if (!terms.length) {
      termMarks.current = []
      setHighlight(window, 'alx-term', [])
      return
    }
    const box = s.getBoundingClientRect()
    const marks: { range: Range; termId: number }[] = []
    for (const block of blocks.current) {
      const r = block?.getBoundingClientRect()
      if (!r || r.bottom < box.top - box.height || r.top > box.bottom + box.height) continue
      marks.push(...termRanges(textMap(block), terms))
    }
    termMarks.current = marks
    setHighlight(window, 'alx-term', marks.map((m) => m.range))
  }, [terms])
  useEffect(() => {
    markTerms()
    const s = el()
    let timer = 0
    const later = () => {
      clearTimeout(timer)
      timer = window.setTimeout(markTerms, 120)
    }
    s.addEventListener('scroll', later, { passive: true })
    return () => {
      s.removeEventListener('scroll', later)
      clearTimeout(timer)
      setHighlight(window, 'alx-term', [])
    }
  }, [markTerms, content, textSize, paged])

  /** Busca em todos os blocos; a posição de cada resultado é a fração do livro. */
  const search = (term: string): SearchHit[] => {
    const hits: SearchHit[] = []
    const list = blocks.current
    list.forEach((block, index) => {
      const map = textMap(block)
      for (const range of searchRanges(map, term)) {
        const start = map.starts[map.nodes.indexOf(range.startContainer as Text)] + range.startOffset
        const position = Math.round(((index + start / Math.max(1, map.text.length)) / list.length) * 10000)
        const excerpt = map.text.slice(Math.max(0, start - 40), start + term.length + 60).replace(/\s+/g, ' ')
        hits.push({ position, label: `${Math.trunc(position / 100)}%`, excerpt: `…${excerpt}…`, data: { block: index, start } })
      }
    })
    return hits
  }
  const showHit = (hit: SearchHit, term: string) => {
    const s = scroller.current
    const { block, start: offset } = hit.data as { block: number; start: number }
    const target = blocks.current[block]
    if (!s || !target) return
    const map = textMap(target)
    const ranges = searchRanges(map, term)
    const range = ranges.find((r) => map.starts[map.nodes.indexOf(r.startContainer as Text)] + r.startOffset === offset) ?? ranges[0]
    if (!range) return
    const r = range.getBoundingClientRect()
    const box = s.getBoundingClientRect()
    s.scrollTop += Math.round(r.top - box.top - WIN_TOP - (paged ? 0 : s.clientHeight * 0.3))
    if (paged) {
      const d = Math.round(alignDelta(lines(), WIN_TOP, winBottom()))
      if (d) s.scrollTop += d
    }
    setHighlight(window, 'alx-search', ranges)
    setHighlight(window, 'alx-search-current', [range])
    updateMask()
  }
  const clearSearch = () => {
    setHighlight(window, 'alx-search', [])
    setHighlight(window, 'alx-search-current', [])
  }

  handle?.({ fraction, exact, span, goToExact: (v) => restore(v / EXACT_SCALE), goTo: (f) => restore(f), search, showHit, clearSearch })

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
      if (target.closest?.('input, textarea, select, [contenteditable], .modal') || document.querySelector('.modal')) return
    if (e.altKey) return // Alt + setas move a régua; Alt + ← é o voltar do navegador
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
        onClick={(e) => {
          if (!onTerm || !termMarks.current.length) return
          const termId = termAtPoint(document, e.clientX, e.clientY, termMarks.current)
          if (termId !== null) {
            e.stopPropagation()
            onTerm(termId)
          }
        }}
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
