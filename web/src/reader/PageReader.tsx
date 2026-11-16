import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { fitPage, turnDirection } from '../core/pages'
import { pageMatrix, svgMatrixValues, THEMES, hex, type Comfort } from '../core/comfort'
import type { PageSource } from './pageSource'
import type { GlossaryTerm } from '../core/glossary'
import { fold } from '../core/text'
import { searchRanges, setHighlight, termAtPoint, termRanges, textMap } from './textRanges'
import type { SearchHit } from './ToolsPanel'

export interface PageReaderHandle {
  goTo: (page: number) => void
  current: () => number
  search?: (term: string, isCancelled: () => boolean) => Promise<SearchHit[]>
  showHit: (hit: SearchHit, term: string) => void
  clearSearch: () => void
}

interface Props {
  source: PageSource
  paged: boolean
  sideLock: boolean
  comfort: Comfort
  start: number
  onPage: (page: number, atEnd: boolean, zoom: number) => void
  handle?: (h: PageReaderHandle) => void
  terms?: GlossaryTerm[]
  onTerm?: (termId: number) => void
}

const MIN_ZOOM = 1
const MAX_ZOOM = 5
const PAD = 12

/**
 * Leitor de páginas (PDF, quadrinhos, DJVU). Modo páginas: a página inteira na tela; vira com
 * setas, Page Down, espaço, roda do mouse (sem zoom) ou clique duplo nas bordas. Rolagem
 * contínua: páginas uma embaixo da outra. Zoom com Ctrl + roda (pinça no trackpad), Ctrl + "+"/"−"/"0"
 * ou clique duplo no meio; com zoom, roda e barras de rolagem movem a página. O bloqueio lateral
 * trava o movimento para os lados.
 */
export function PageReader({ source, paged, sideLock, comfort, start, onPage, handle, terms = [], onTerm }: Props) {
  const scroll = useRef<HTMLDivElement>(null)
  const [area, setArea] = useState({ w: 0, h: 0 })
  const [zoom, setZoom] = useState(1)
  const [page, setPage] = useState(Math.min(source.pageCount - 1, Math.max(0, start)))
  const [, bump] = useState(0)
  const focal = useRef<{ fx: number; fy: number; x: number; y: number } | null>(null)
  const pageRefs = useRef<(HTMLElement | null)[]>([])
  const continuousStart = useRef(start)

  useLayoutEffect(() => {
    const el = scroll.current!
    const observer = new ResizeObserver(() => setArea({ w: el.clientWidth, h: el.clientHeight }))
    observer.observe(el)
    setArea({ w: el.clientWidth, h: el.clientHeight })
    return () => observer.disconnect()
  }, [])

  // Tamanhos: página inteira (modo páginas) ou largura da coluna (contínua), vezes o zoom.
  const columnWidth = Math.max(100, Math.min(area.w - 2 * PAD, 1100))
  const sizeOf = (index: number): [number, number] => {
    const aspect = source.aspect(index)
    if (paged) {
      const [w, h] = fitPage(area.w - 2 * PAD, area.h - 2 * PAD, aspect)
      return [w * zoom, h * zoom]
    }
    const w = columnWidth * zoom
    return [w, w * aspect]
  }

  const atEndNow = () => {
    const el = scroll.current
    if (!el) return false
    if (paged) return page >= source.pageCount - 1
    return el.scrollTop + el.clientHeight >= el.scrollHeight - 2
  }

  /** Página no topo da tela (rolagem contínua). */
  const visiblePage = () => {
    const el = scroll.current
    if (!el) return page
    const y = el.scrollTop + el.clientHeight * 0.2
    let found = 0
    pageRefs.current.forEach((node, i) => {
      if (node && node.offsetTop <= y) found = i
    })
    return found
  }

  const goTo = useCallback((target: number, toBottom = false) => {
    const index = Math.min(source.pageCount - 1, Math.max(0, target))
    if (paged) {
      setPage(index)
      requestAnimationFrame(() => {
        const el = scroll.current
        if (el) el.scrollTo({ top: toBottom ? el.scrollHeight : 0, left: el.scrollLeft })
      })
    } else {
      const node = pageRefs.current[index]
      if (node && scroll.current) scroll.current.scrollTop = node.offsetTop - PAD
      setPage(index)
    }
  }, [paged, source])

  // Marcas na camada de texto do PDF: resultado da busca e termos do glossário, por página.
  const searchTerm = useRef('')
  const layers = useRef(new Map<number, HTMLElement>())
  const termMarks = useRef<{ range: Range; termId: number }[]>([])
  const refreshMarks = () => {
    const search: Range[] = []
    const marks: { range: Range; termId: number }[] = []
    for (const container of layers.current.values()) {
      if (!container.isConnected) continue
      const map = textMap(container)
      if (searchTerm.current) search.push(...searchRanges(map, searchTerm.current))
      if (terms.length) marks.push(...termRanges(map, terms))
    }
    termMarks.current = marks
    setHighlight(window, 'alx-search', search)
    setHighlight(window, 'alx-term', marks.map((m) => m.range))
  }
  useEffect(() => {
    refreshMarks()
    return () => {
      setHighlight(window, 'alx-term', [])
      setHighlight(window, 'alx-search', [])
    }
  }, [terms])
  const onText = (index: number, container: HTMLElement) => {
    layers.current.set(index, container)
    refreshMarks()
  }

  handle?.({
    goTo: (p) => goTo(p),
    current: () => (paged ? page : visiblePage()),
    search: source.pageText
      ? async (term, isCancelled) => {
          const needle = fold(term.trim())
          const hits: SearchHit[] = []
          if (!needle) return hits
          for (let i = 0; i < source.pageCount && !isCancelled(); i++) {
            const text = await source.pageText!(i)
            const haystack = fold(text)
            for (let at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length)) {
              hits.push({ position: i, label: `Pág. ${i + 1}`, excerpt: `…${text.slice(Math.max(0, at - 40), at + needle.length + 60).replace(/\s+/g, ' ')}…` })
            }
          }
          return hits
        }
      : undefined,
    showHit: (hit, term) => {
      searchTerm.current = term
      goTo(hit.position)
      setTimeout(refreshMarks, 400)
    },
    clearSearch: () => {
      searchTerm.current = ''
      refreshMarks()
    }
  })

  const turn = (direction: 1 | -1) => {
    if (paged) {
      if (direction > 0 ? page >= source.pageCount - 1 : page <= 0) return
      window.getSelection()?.removeAllRanges()
      goTo(page + direction, direction < 0 && zoom > 1.01)
      scroll.current?.animate?.([{ opacity: 0.6 }, { opacity: 1 }], { duration: 160 })
    } else {
      scroll.current?.scrollBy({ top: direction * scroll.current.clientHeight * 0.9 })
    }
  }

  const zoomTo = (next: number, clientX?: number, clientY?: number) => {
    const el = scroll.current
    if (!el) return
    const value = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next))
    if (Math.abs(value - zoom) < 0.001) return
    const box = el.getBoundingClientRect()
    const x = (clientX ?? box.left + box.width / 2) - box.left
    const y = (clientY ?? box.top + box.height / 2) - box.top
    focal.current = { fx: (el.scrollLeft + x) / Math.max(1, el.scrollWidth), fy: (el.scrollTop + y) / Math.max(1, el.scrollHeight), x, y }
    setZoom(value)
  }

  // Depois do zoom, mantém parado o ponto sob o cursor.
  useLayoutEffect(() => {
    const el = scroll.current
    const f = focal.current
    if (!el || !f) return
    focal.current = null
    el.scrollLeft = f.fx * el.scrollWidth - f.x
    el.scrollTop = f.fy * el.scrollHeight - f.y
  }, [zoom])

  // Mudou de modo: volta para a mesma página.
  useLayoutEffect(() => {
    if (!paged && area.h) {
      continuousStart.current = page
      requestAnimationFrame(() => goTo(continuousStart.current))
    }
  }, [paged, area.h > 0])

  // Página atual, fim do livro e zoom para a barra de baixo.
  useEffect(() => {
    onPage(paged ? page : visiblePage(), atEndNow(), zoom)
  }, [page, zoom, paged])
  useEffect(() => {
    const el = scroll.current!
    let frame = 0
    const onScroll = () => {
      if (frame) return
      frame = requestAnimationFrame(() => {
        frame = 0
        if (!paged) {
          const current = visiblePage()
          if (current !== page) setPage(current)
          onPage(current, atEndNow(), zoom)
        }
      })
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [paged, page, zoom])

  // Roda do mouse: Ctrl = zoom; modo páginas sem zoom = vira; com zoom, nas pontas passa de página.
  const wheel = useRef({ acc: 0, last: 0 })
  useEffect(() => {
    const el = scroll.current!
    const onWheel = (e: WheelEvent) => {
      if (e.ctrlKey || e.metaKey) {
        e.preventDefault()
        zoomTo(zoom * Math.exp(-e.deltaY * 0.0025), e.clientX, e.clientY)
        return
      }
      if (!paged) return
      const atTop = el.scrollTop <= 0
      const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 1
      const free = zoom <= 1.01 || (e.deltaY > 0 && atBottom) || (e.deltaY < 0 && atTop)
      if (!free || Math.abs(e.deltaX) > Math.abs(e.deltaY)) return
      e.preventDefault()
      wheel.current.acc += e.deltaY
      const now = performance.now()
      if (Math.abs(wheel.current.acc) >= 40 && now - wheel.current.last > 300) {
        turn(wheel.current.acc > 0 ? 1 : -1)
        wheel.current.acc = 0
        wheel.current.last = now
      }
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  })

  // Teclado.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.closest('input, textarea, [contenteditable], .modal')) return
      if (e.ctrlKey || e.metaKey) {
        if (e.key === '+' || e.key === '=') zoomTo(zoom * 1.25)
        else if (e.key === '-') zoomTo(zoom / 1.25)
        else if (e.key === '0') zoomTo(1)
        else return
        e.preventDefault()
        return
      }
      const zoomed = zoom > 1.01
      const next = e.key === 'ArrowRight' || e.key === 'PageDown' || (e.key === ' ' && !e.shiftKey) || (!zoomed && e.key === 'ArrowDown')
      const prev = e.key === 'ArrowLeft' || e.key === 'PageUp' || (e.key === ' ' && e.shiftKey) || (!zoomed && e.key === 'ArrowUp')
      if (paged && (next || prev)) {
        e.preventDefault()
        turn(next ? 1 : -1)
      } else if (e.key === 'Home' || e.key === 'End') {
        e.preventDefault()
        goTo(e.key === 'Home' ? 0 : source.pageCount - 1)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const matrix = pageMatrix(comfort.theme)
  const theme = THEMES[comfort.theme]
  const filter = matrix ? 'url(#alx-page-filter)' : undefined
  const renderPage = (index: number, lazy: boolean) => {
    const [w, h] = sizeOf(index)
    return (
      <PageView
        key={index}
        source={source}
        index={index}
        width={w}
        height={h}
        lazy={lazy}
        filter={filter}
        root={scroll}
        onAspect={() => bump((n) => n + 1)}
        onText={onText}
        nodeRef={(node) => (pageRefs.current[index] = node)}
      />
    )
  }

  return (
    <div class="page-reader" style={{ '--page-bg': hex(theme.dark ? theme.background : 0x6f7780) } as Record<string, string>}>
      {matrix && (
        <svg width="0" height="0" style={{ position: 'absolute' }} aria-hidden="true">
          <filter id="alx-page-filter" color-interpolation-filters="sRGB">
            <feColorMatrix type="matrix" values={svgMatrixValues(matrix)} />
          </filter>
        </svg>
      )}
      <div
        ref={scroll}
        class="page-scroll"
        style={{ overflowX: sideLock ? 'hidden' : 'auto' }}
        tabIndex={0}
        aria-label="Páginas do documento"
        onClick={(e) => {
          if (!onTerm || !termMarks.current.length) return
          const termId = termAtPoint(document, e.clientX, e.clientY, termMarks.current)
          if (termId !== null) {
            e.stopPropagation()
            onTerm(termId)
          }
        }}
        onDblClick={(e) => {
          const el = scroll.current!
          const box = el.getBoundingClientRect()
          const direction = paged ? turnDirection(e.clientX - box.left, box.width) : 0
          if (direction !== 0) {
            turn(direction)
            return
          }
          window.getSelection()?.removeAllRanges()
          zoomTo(zoom > 1.05 ? 1 : 2, e.clientX, e.clientY)
        }}
      >
        {area.w > 0 && paged && (
          <div class="page-stage" style={{ minWidth: `${sizeOf(page)[0] + 2 * PAD}px`, minHeight: `${sizeOf(page)[1] + 2 * PAD}px` }}>
            {renderPage(page, false)}
          </div>
        )}
        {area.w > 0 && !paged && (
          <div class="page-column" style={{ width: `${columnWidth * zoom}px` }}>
            {Array.from({ length: source.pageCount }, (_, i) => renderPage(i, true))}
          </div>
        )}
      </div>
    </div>
  )
}

/** Uma página: desenhada quando aparece, de novo (mais nítida) depois do zoom; texto selecionável no PDF. */
function PageView({ source, index, width, height, lazy, filter, root, onAspect, onText, nodeRef }: {
  source: PageSource
  index: number
  width: number
  height: number
  lazy: boolean
  filter?: string
  root: { current: HTMLElement | null }
  onAspect: () => void
  onText: (index: number, container: HTMLElement) => void
  nodeRef: (node: HTMLElement | null) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const canvasHolder = useRef<HTMLDivElement>(null)
  const textHolder = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(!lazy)

  useEffect(() => {
    if (!lazy) return
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), { root: root.current, rootMargin: '150% 0px' })
    observer.observe(box.current!)
    return () => observer.disconnect()
  }, [lazy])

  // Desenha (com um pequeno atraso durante o zoom) na resolução da tela.
  useEffect(() => {
    if (!visible) return
    let alive = true
    const timer = setTimeout(async () => {
      try {
        const aspect = source.aspect(index)
        const pixels = width * (window.devicePixelRatio || 1)
        const drawn = await source.render(index, pixels)
        if (!alive) return
        const canvas = drawn instanceof HTMLCanvasElement ? drawn : bitmapToCanvas(drawn)
        canvas.className = 'page-canvas'
        if (filter) canvas.style.filter = filter
        canvasHolder.current?.replaceChildren(canvas)
        if (Math.abs(source.aspect(index) - aspect) > 0.001) onAspect()
        if (source.textLayer && textHolder.current) {
          await source.textLayer(index, textHolder.current, width)
          if (alive) onText(index, textHolder.current)
        }
      } catch (e) {
        console.warn('Página não desenhada', index, e)
      }
    }, 120)
    return () => {
      alive = false
      clearTimeout(timer)
    }
  }, [visible, Math.round(width), filter])

  return (
    <div
      ref={(node) => {
        box.current = node
        nodeRef(node)
      }}
      class="page-box"
      style={{ width: `${width}px`, height: `${height}px` }}
      data-page={index + 1}
    >
      <div ref={canvasHolder} class="page-canvas-holder" />
      {source.textLayer && <div ref={textHolder} class="textLayer" />}
    </div>
  )
}

function bitmapToCanvas(bitmap: ImageBitmap): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = bitmap.width
  canvas.height = bitmap.height
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0)
  bitmap.close()
  return canvas
}
