import { useCallback, useEffect, useRef, useState } from 'preact/hooks'
import { epubCss, THEMES, hex, type Comfort } from '../core/comfort'
import { turnDirection } from '../core/pages'
import { atkinsonFontFaces } from './fonts'
import { ensureSpacer, FRAME_TOP, isLastPage, maskTop, nextPage, previousPage, snapToLine } from './framePager'
import type { LinkTarget, ReflowBook } from './reflowBook'
import { linkLooksLikeNote, nearestLink, noteContent, type LinkHit, type NoteContent } from './notes'
import { looksLikeNoteMark, noteTitle } from '../core/links'
import { EXACT_SCALE } from '../core/bookmarks'
import { markHtml, type GlossaryTerm } from '../core/glossary'
import { searchRanges, setHighlight, textMap } from './textRanges'
import { fold } from '../core/text'
import type { SearchHit } from './ToolsPanel'
import { DEFAULT_TEXT_SIZE, MAX_TEXT_SIZE, MIN_TEXT_SIZE } from './prefs'

export interface EpubReaderHandle {
  position: () => number
  /** Posição precisa (0 a 1.000.000) e quanto dela cabe numa tela: marcadores e "Voltar". */
  exact: () => number
  span: () => number
  goToExact: (value: number) => void
  goToChapter: (index: number) => void
  turnChapter: (direction: 1 | -1) => void
  goTo: (position: number) => void
  search: (term: string, isCancelled: () => boolean) => Promise<SearchHit[]>
  showHit: (hit: SearchHit, term: string) => void
  clearSearch: () => void
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
  /** Clique simples no capítulo (fora de links e seleções) e clique duplo. */
  onTap?: () => void
  onDoubleTap?: () => void
  terms?: GlossaryTerm[]
  onTerm?: (termId: number) => void
  onSelection?: (text: string | null, rect?: DOMRect) => void
  onInteraction?: () => void
  /** Nota de rodapé para mostrar em janela; [goTo] mostra no lugar (null: só a janela). */
  onNote?: (title: string, note: NoteContent, goTo: (() => void) | null) => void
  /** O livro vai pular por um link: [returnTo] (posição precisa) é o ponto para "Voltar". */
  onJump?: (returnTo: number) => void
}

/** Raio (px) em que um clique ainda acerta um link (o número de uma nota é pequeno). */
const LINK_RADIUS = 10
type Anchor = ((doc: Document) => Element | null) | null

/**
 * Leitor de EPUB/MOBI: cada capítulo num iframe protegido (sem scripts do livro, sem acesso à
 * internet), com o CSS de leitura do conforto visual. Modo páginas com linhas inteiras e
 * passagem automática de capítulo; rolagem contínua dentro do capítulo.
 */
export function EpubReader({ book, paged, textSize, comfort, start, onPosition, onTextSize, handle, onTap, onDoubleTap, terms = [], onTerm, onSelection, onInteraction, onNote, onJump }: Props) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [chapter, setChapter] = useState(() => Math.min(book.chapterCount - 1, Math.floor((start * book.chapterCount) / 10000 + 1e-6)))
  const [src, setSrc] = useState<string | null>(null)
  const [mask, setMask] = useState(1e6)
  const pending = useRef<{ fraction: number; anchor: Anchor }>({ fraction: (start * book.chapterCount) / 10000 - chapter, anchor: null })
  const ready = useRef(false)
  const lastPosition = useRef(start)

  const win = () => frame.current?.contentWindow ?? null
  // Só recarrega o capítulo quando os termos mudam de verdade (não a cada nova lista igual).
  const termsKey = terms.map((t) => `${t.id}:${t.term}`).join('|')

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
  const exact = () => {
    const w = win()
    if (!w || !ready.current) return lastPosition.current * EXACT_SCALE
    return Math.min(10000 * EXACT_SCALE, Math.max(0, Math.round(((chapter + chapterFraction(w)) / book.chapterCount) * 10000 * EXACT_SCALE)))
  }
  const span = () => {
    const w = win()
    if (!w || !ready.current) return 1
    const r = range(w)
    const screen = r <= 0 ? 1 : Math.min(1, w.innerHeight / (r + w.innerHeight))
    return Math.max(1, Math.round((screen / book.chapterCount) * 10000 * EXACT_SCALE))
  }
  const goToFraction = (value: number) => {
    const exactChapter = Math.min(1, Math.max(0, value)) * book.chapterCount + 1e-9
    const index = Math.min(book.chapterCount - 1, Math.floor(exactChapter))
    if (index === chapter && win() && ready.current) {
      const w = win()!
      w.scrollTo(0, Math.round((exactChapter - index) * range(w)))
      if (paged) snapToLine(w)
      report()
    } else openChapter(index, exactChapter - index)
  }

  const report = () => {
    const w = win()
    if (!w || !ready.current) return
    lastPosition.current = position()
    onPosition({ position: lastPosition.current, chapter, atEnd: atEnd(w), ready: true })
    if (paged) setMask(maskTop(w))
  }

  const openChapter = useCallback((index: number, fraction: number, anchor: Anchor = null) => {
    if (index < 0 || index >= book.chapterCount) return
    ready.current = false
    pending.current = { fraction, anchor }
    setChapter(index)
  }, [book])

  // Carrega o documento do capítulo (e de novo quando o modo, a letra ou o conforto mudam).
  useEffect(() => {
    let alive = true
    let url: string | null = null
    book.chapter(chapter).then(({ html, type }) => {
      if (!alive) return
      // Termos do glossário viram <span class="alx-term"> (como no app).
      url = URL.createObjectURL(new Blob([markHtml(html, terms)], { type }))
      setSrc(url)
    }).catch((e) => {
      console.error(e)
      if (alive) setSrc(null)
    })
    return () => {
      alive = false
      if (url) setTimeout(() => URL.revokeObjectURL(url!), 1000)
    }
  }, [book, chapter, termsKey])

  // Mudou modo, letra ou aparência: guarda o ponto e redesenha o capítulo.
  const styleKey = `${paged}|${textSize}|${JSON.stringify(comfort)}`
  const appliedKey = useRef(styleKey)
  useEffect(() => {
    if (appliedKey.current === styleKey) return
    appliedKey.current = styleKey
    const w = win()
    if (w && ready.current) pending.current = { fraction: chapterFraction(w), anchor: null }
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

  const pendingFind = useRef<{ term: string; nth: number } | null>(null)
  /** Marca a ocorrência número `nth` do termo no capítulo aberto e vai até ela. */
  const find = (term: string, nth: number) => {
    const w = win()
    if (!w?.document.body) return
    const ranges = searchRanges(textMap(w.document.body), term)
    const range = ranges[Math.min(nth, ranges.length - 1)]
    setHighlight(w, 'alx-search', ranges)
    setHighlight(w, 'alx-search-current', range ? [range] : [])
    if (!range) return
    const r = range.getBoundingClientRect()
    w.scrollBy(0, Math.round(r.top - (paged ? FRAME_TOP + 4 : w.innerHeight * 0.3)))
    if (paged) snapToLine(w)
    report()
  }
  handle?.({
    position,
    exact,
    span,
    goToExact: (value) => goToFraction(value / (10000 * EXACT_SCALE)),
    goToChapter: (index) => openChapter(index, 0),
    turnChapter: (direction) => openChapter(chapter + direction, 0),
    goTo: (value) => goToFraction(value / 10000),
    async search(term, isCancelled) {
      const hits: SearchHit[] = []
      for (let i = 0; i < book.chapterCount && !isCancelled(); i++) {
        const { html, type } = await book.chapter(i)
        const doc = new DOMParser().parseFromString(html, type)
        const text = (doc.body ?? doc.documentElement).textContent ?? ''
        const haystack = fold(text)
        const needle = fold(term.trim())
        if (!needle) break
        let nth = 0
        for (let at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length)) {
          const position = Math.round(((i + at / Math.max(1, text.length)) / book.chapterCount) * 10000)
          const title = book.chapterTitles[i] ?? `Cap. ${i + 1}`
          hits.push({ position, label: title, excerpt: `…${text.slice(Math.max(0, at - 40), at + needle.length + 60).replace(/\s+/g, ' ')}…`, data: { chapter: i, nth: nth++ } })
        }
      }
      return hits
    },
    showHit(hit, term) {
      const { chapter: index, nth } = hit.data as { chapter: number; nth: number }
      if (index === chapter && ready.current) find(term, nth)
      else {
        pendingFind.current = { term, nth }
        openChapter(index, (hit.position * book.chapterCount) / 10000 - index)
      }
    },
    clearSearch() {
      const w = win()
      if (w) {
        setHighlight(w, 'alx-search', [])
        setHighlight(w, 'alx-search-current', [])
      }
    }
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
  /** Segue um link do livro: nota em janela, pulo no capítulo ou outro capítulo (com "Voltar"). */
  const followLink = async (hit: LinkHit) => {
    const w = win()
    const doc = w?.document
    if (!w || !doc) return
    const target: LinkTarget | null = await book.resolveLink(chapter, hit.href)
    if (!target) return
    const likely = linkLooksLikeNote(hit, looksLikeNoteMark)
    const title = noteTitle(hit.text)
    const here = target.index === chapter
    const jump = () => {
      handlers.current.onJump?.(exact())
      if (!here) return openChapter(target.index, 0, target.anchor)
      const el = target.anchor(doc)
      if (el) {
        el.scrollIntoView()
        w.scrollBy(0, -12)
      } else w.scrollTo(0, 0)
      if (paged) snapToLine(w)
      report()
    }
    const show = handlers.current.onNote
    if (here) {
      const note = likely && show ? noteContent(target.anchor(doc), true, false) : null
      return note ? show!(title, note, jump) : jump()
    }
    if (target.index >= 0 && (!likely || !show)) return jump()
    const other = await book.linkedDocument(target)
    const outside = target.index < 0
    const note = other && show ? noteContent(target.anchor(other) ?? (outside ? other.body : null), likely, outside) : null
    if (note) show!(title, note, outside ? null : jump)
    else if (!outside) jump()
  }

  const handlers = useRef({ onKey, onWheel, onTap, onDoubleTap, onTerm, onSelection, onInteraction, onNote, onJump, followLink })
  handlers.current = { onKey, onWheel, onTap, onDoubleTap, onTerm, onSelection, onInteraction, onNote, onJump, followLink }
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
      body { padding-top: ${paged ? FRAME_TOP + 10 : 20}px !important; }
      ::highlight(alx-search) { background-color: rgba(255, 213, 0, 0.55); }
      ::highlight(alx-search-current) { background-color: rgba(255, 140, 0, 0.75); }
      .alx-term { cursor: pointer; }`
    ;(doc.head ?? doc.documentElement).appendChild(style)
    if (paged) ensureSpacer(w)
    const { fraction, anchor } = pending.current
    const target = anchor ? anchor(doc) : null
    if (target) {
      target.scrollIntoView()
      w.scrollBy(0, -12)
    } else w.scrollTo(0, Math.round(fraction * range(w)))
    if (paged) snapToLine(w)
    ready.current = true
    if (pendingFind.current) {
      const { term, nth } = pendingFind.current
      pendingFind.current = null
      setTimeout(() => find(term, nth), 50)
    }
    // Seleção no capítulo: a moldura mostra a barra com Copiar, Definir termo e Anotar.
    doc.addEventListener('selectionchange', () => {
      const sel = w.getSelection()
      const text = sel && !sel.isCollapsed ? sel.toString().trim() : ''
      if (!text || !sel?.rangeCount) return handlers.current.onSelection?.(null)
      const r = sel.getRangeAt(0).getBoundingClientRect()
      const frameBox = frame.current!.getBoundingClientRect()
      handlers.current.onSelection?.(text, new DOMRect(r.left + frameBox.left, r.top + frameBox.top, r.width, r.height))
    })

    for (const type of ['keydown', 'pointerdown', 'wheel', 'scroll']) w.addEventListener(type, () => handlers.current.onInteraction?.(), { capture: true, passive: true })
    w.addEventListener('keydown', (e) => handlers.current.onKey(e))
    w.addEventListener('wheel', (e) => handlers.current.onWheel(e), { passive: false })
    w.addEventListener('scroll', () => requestAnimationFrame(report), { passive: true })
    doc.addEventListener('dblclick', (e) => {
      handlers.current.onDoubleTap?.()
      if (!paged) return
      const direction = turnDirection(e.clientX, w.innerWidth)
      if (direction !== 0) {
        w.getSelection()?.removeAllRanges()
        turn(direction)
      }
    })
    // Links internos: nota de rodapé em janela, pulo no capítulo ou outro capítulo; externos são
    // ignorados. Vale o link mais perto do clique (o número de uma nota é pequeno).
    doc.addEventListener('click', (e) => {
      const term = (e.target as Element | null)?.closest?.('.alx-term')
      if (term && handlers.current.onTerm) {
        handlers.current.onTerm(Number(term.getAttribute('data-term')))
        return
      }
      const collapsed = w.getSelection()?.isCollapsed ?? true
      const hit = collapsed ? nearestLink(doc, e.target as Element | null, e.clientX, e.clientY, LINK_RADIUS) : null
      if (!hit) {
        if ((e.target as Element | null)?.closest?.('a[href]')) e.preventDefault()
        else if (collapsed) handlers.current.onTap?.()
        return
      }
      e.preventDefault()
      handlers.current.followLink(hit)
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

