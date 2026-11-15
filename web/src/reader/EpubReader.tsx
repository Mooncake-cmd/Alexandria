import { useCallback, useEffect, useRef, useState } from 'preact/hooks'
import { epubCss, THEMES, hex, type Comfort } from '../core/comfort'
import { turnDirection } from '../core/pages'
import { atkinsonFontFaces } from './fonts'
import { ensureSpacer, FRAME_TOP, isLastPage, maskTop, nextPage, previousPage, snapToLine } from './framePager'
import type { ReflowBook } from './reflowBook'
import { DEFAULT_TEXT_SIZE, MAX_TEXT_SIZE, MIN_TEXT_SIZE } from './prefs'

export interface EpubReaderHandle {
  position: () => number
  goToChapter: (index: number) => void
  turnChapter: (direction: 1 | -1) => void
}

interface Props {
  book: ReflowBook
  paged: boolean
  textSize: number
  comfort: Comfort
  /** Posição salva: fração de 0 a 10000 do livro inteiro (capítulo + parte dele), como no app. */
  start: number
  onPosition: (info: { position: number; chapter: number; atEnd: boolean; ready: boolean }) => void
  onTextSize: (size: number) => void
  handle?: (h: EpubReaderHandle) => void
}

/**
 * Leitor de EPUB/MOBI: cada capítulo num iframe protegido (sem scripts do livro, sem acesso à
 * internet), com o CSS de leitura do conforto visual. Modo páginas com linhas inteiras e
 * passagem automática de capítulo; rolagem contínua dentro do capítulo.
 */
export function EpubReader({ book, paged, textSize, comfort, start, onPosition, onTextSize, handle }: Props) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [chapter, setChapter] = useState(() => Math.min(book.chapterCount - 1, Math.floor((start * book.chapterCount) / 10000 + 1e-6)))
  const [src, setSrc] = useState<string | null>(null)
  const [mask, setMask] = useState(1e6)
  const pending = useRef<{ fraction: number; fragment: string | null }>({ fraction: (start * book.chapterCount) / 10000 - chapter, fragment: null })
  const ready = useRef(false)
  const lastPosition = useRef(start)

  const win = () => frame.current?.contentWindow ?? null

  const range = (w: Window) => {
    const spacer = paged && w.document.getElementById('alx-spacer') ? w.innerHeight : 0
    return Math.max(0, w.document.documentElement.scrollHeight - w.innerHeight - spacer)
  }
  const chapterFraction = (w: Window) => {
    const r = range(w)
    return r <= 0 ? 0 : Math.min(1, Math.max(0, w.scrollY / r))
  }
  const atEnd = (w: Window) => chapter === book.chapterCount - 1 && (paged ? isLastPage(w) : w.scrollY >= w.document.documentElement.scrollHeight - w.innerHeight - 2)
  const position = useCallback(() => {
    const w = win()
    if (!w || !ready.current) return lastPosition.current
    return Math.min(10000, Math.max(0, Math.round(((chapter + chapterFraction(w)) / book.chapterCount) * 10000)))
  }, [chapter, paged])

  const report = () => {
    const w = win()
    if (!w || !ready.current) return
    lastPosition.current = position()
    onPosition({ position: lastPosition.current, chapter, atEnd: atEnd(w), ready: true })
    if (paged) setMask(maskTop(w))
  }

  const openChapter = useCallback((index: number, fraction: number, fragment: string | null = null) => {
    if (index < 0 || index >= book.chapterCount) return
    ready.current = false
    pending.current = { fraction, fragment }
    setChapter(index)
  }, [book])

  // Carrega o documento do capítulo (e de novo quando o modo, a letra ou o conforto mudam).
  useEffect(() => {
    let alive = true
    let url: string | null = null
    book.chapter(chapter).then(({ html, type }) => {
      if (!alive) return
      url = URL.createObjectURL(new Blob([html], { type }))
      setSrc(url)
    }).catch((e) => {
      console.error(e)
      if (alive) setSrc(null)
    })
    return () => {
      alive = false
      if (url) setTimeout(() => URL.revokeObjectURL(url!), 1000)
    }
  }, [book, chapter])

  // Mudou modo, letra ou aparência: guarda o ponto e redesenha o capítulo.
  const styleKey = `${paged}|${textSize}|${JSON.stringify(comfort)}`
  const appliedKey = useRef(styleKey)
  useEffect(() => {
    if (appliedKey.current === styleKey) return
    appliedKey.current = styleKey
    const w = win()
    if (w && ready.current) pending.current = { fraction: chapterFraction(w), fragment: null }
    ready.current = false
    frame.current?.contentWindow?.location.reload()
  }, [styleKey])

  const turn = useCallback((direction: 1 | -1) => {
    const w = win()
    if (!w || !ready.current) return
    if (paged) {
      const moved = direction > 0 ? nextPage(w) : previousPage(w)
      if (!moved) {
        if (direction > 0 && chapter < book.chapterCount - 1) openChapter(chapter + 1, 0)
        else if (direction < 0 && chapter > 0) openChapter(chapter - 1, 1)
        return
      }
    } else {
      w.scrollBy(0, direction * Math.round(w.innerHeight * 0.9))
    }
    report()
    frame.current?.animate?.([{ opacity: 0.6 }, { opacity: 1 }], { duration: 160 })
  }, [paged, chapter, openChapter])

  handle?.({
    position,
    goToChapter: (index) => openChapter(index, 0),
    turnChapter: (direction) => openChapter(chapter + direction, 0)
  })

  // Teclado e roda do mouse (no site e dentro do capítulo).
  const onKey = (e: KeyboardEvent) => {
    const target = e.target as HTMLElement | null
    if (target?.closest?.('input, textarea, [contenteditable], .modal')) return
    if ((e.ctrlKey || e.metaKey) && (e.key === '+' || e.key === '=' || e.key === '-')) {
      e.preventDefault()
      onTextSize(Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, textSize + (e.key === '-' ? -1 : 1))))
      return
    }
    const next = ['ArrowRight', 'PageDown'].includes(e.key) || (paged && (e.key === 'ArrowDown' || (e.key === ' ' && !e.shiftKey)))
    const prev = ['ArrowLeft', 'PageUp'].includes(e.key) || (paged && (e.key === 'ArrowUp' || (e.key === ' ' && e.shiftKey)))
    if (next || prev) {
      e.preventDefault()
      turn(next ? 1 : -1)
    }
  }
  const wheel = useRef({ accumulated: 0, last: 0 })
  const onWheel = (e: WheelEvent) => {
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault()
      wheel.current.accumulated += e.deltaY
      if (Math.abs(wheel.current.accumulated) >= 60) {
        onTextSize(Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, textSize + (wheel.current.accumulated < 0 ? 1 : -1))))
        wheel.current.accumulated = 0
      }
      return
    }
    if (!paged) return
    e.preventDefault()
    wheel.current.accumulated += e.deltaY
    const now = performance.now()
    if (Math.abs(wheel.current.accumulated) >= 40 && now - wheel.current.last > 280) {
      turn(wheel.current.accumulated > 0 ? 1 : -1)
      wheel.current.accumulated = 0
      wheel.current.last = now
    }
  }
  const handlers = useRef({ onKey, onWheel })
  handlers.current = { onKey, onWheel }
  useEffect(() => {
    const key = (e: KeyboardEvent) => handlers.current.onKey(e)
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [])

  /** O capítulo terminou de carregar (com imagens): aplica o estilo, restaura o ponto e liga os gestos. */
  const onLoad = () => {
    const w = win()
    const doc = w?.document
    if (!w || !doc?.documentElement) return
    const style = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style')
    style.setAttribute('id', 'alx-comfort')
    style.textContent = `${epubCss(comfort, atkinsonFontFaces())}
      html { font-size: ${Math.round((textSize / DEFAULT_TEXT_SIZE) * 100)}% !important; ${paged ? 'overflow: hidden !important;' : ''} }
      body { padding-top: ${paged ? FRAME_TOP + 10 : 20}px !important; }`
    ;(doc.head ?? doc.documentElement).appendChild(style)
    if (paged) ensureSpacer(w)
    const { fraction, fragment } = pending.current
    const target = fragment ? doc.getElementById(fragment) : null
    if (target) target.scrollIntoView()
    else w.scrollTo(0, Math.round(fraction * range(w)))
    if (paged) snapToLine(w)
    ready.current = true

    w.addEventListener('keydown', (e) => handlers.current.onKey(e))
    w.addEventListener('wheel', (e) => handlers.current.onWheel(e), { passive: false })
    w.addEventListener('scroll', () => requestAnimationFrame(report), { passive: true })
    doc.addEventListener('dblclick', (e) => {
      if (!paged) return
      const direction = turnDirection(e.clientX, w.innerWidth)
      if (direction !== 0) {
        w.getSelection()?.removeAllRanges()
        turn(direction)
      }
    })
    // Links internos (notas, sumário do livro) abrem o capítulo certo; externos são ignorados.
    doc.addEventListener('click', async (e) => {
      const link = (e.target as Element | null)?.closest?.('a[href]')
      if (!link) return
      e.preventDefault()
      const resolved = await book.resolveLink(chapter, link.getAttribute('href')!)
      if (!resolved) return
      if (resolved.index === chapter && resolved.fragment) {
        doc.getElementById(resolved.fragment)?.scrollIntoView()
        if (paged) snapToLine(w)
        report()
      } else openChapter(resolved.index, 0, resolved.fragment)
    })
    report()
  }

  const theme = THEMES[comfort.theme]
  return (
    <div class="epub-reader" style={{ '--page-bg': hex(theme.background) } as Record<string, string>}>
      {src && (
        <iframe
          ref={frame}
          key={src}
          src={src}
          title="Capítulo do livro"
          sandbox="allow-same-origin"
          onLoad={onLoad}
        />
      )}
      {paged && (
        <>
          <div class="page-mask" style={{ top: 0, height: `${FRAME_TOP}px` }} aria-hidden="true" />
          <div class="page-mask" style={{ top: `${mask}px`, bottom: 0 }} aria-hidden="true" />
        </>
      )}
    </div>
  )
}

