import type { LineSpan } from '../core/pages'

/**
 * Paginador por linhas do EPUB/MOBI, agindo de fora sobre o documento do capítulo (que não roda
 * scripts). Mesma lógica do PAGER_JS do app: acha a linha na borda pelo ponto na tela, cobre a
 * linha cortada e, ao virar, rola até ela ficar no topo. Uma folga em branco no fim deixa a última
 * página subir sem repetir linhas.
 */
export const FRAME_TOP = 10
export const FRAME_BOTTOM = 10

export interface Window2 {
  t: number
  b: number
  h: number
}

export function frameWindow(win: Window): Window2 {
  const b = win.innerHeight - FRAME_BOTTOM
  return { t: FRAME_TOP, b, h: b - FRAME_TOP }
}

type CaretDoc = Document & {
  caretRangeFromPoint?: (x: number, y: number) => Range | null
  caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
}

function caretAt(doc: Document, x: number, y: number): { node: Node; offset: number } | null {
  const d = doc as CaretDoc
  if (d.caretRangeFromPoint) {
    const r = d.caretRangeFromPoint(x, y)
    return r ? { node: r.startContainer, offset: r.startOffset } : null
  }
  if (d.caretPositionFromPoint) {
    const p = d.caretPositionFromPoint(x, y)
    return p ? { node: p.offsetNode, offset: p.offset } : null
  }
  return null
}

const BLOCKS = new Set(['img', 'svg', 'image', 'video', 'canvas'])

/** A linha (ou imagem) que passa pela altura y, com a entrelinha incluída. */
export function lineAt(win: Window, y: number): LineSpan | null {
  const doc = win.document
  const width = win.innerWidth
  for (const fraction of [0.5, 0.3, 0.7, 0.15, 0.85]) {
    const x = width * fraction
    const el = doc.elementFromPoint(x, y)
    if (!el) continue
    if (BLOCKS.has(el.localName)) {
      const r = el.getBoundingClientRect()
      if (r.top <= y && r.bottom >= y) return { top: r.top, bottom: r.bottom }
      continue
    }
    const caret = caretAt(doc, x, y)
    if (!caret || caret.node.nodeType !== 3) continue
    const node = caret.node as Text
    for (const shift of [0, -1]) {
      const o = Math.min(caret.offset + shift, node.length - 1)
      if (o < 0) continue
      const range = doc.createRange()
      range.setStart(node, o)
      range.setEnd(node, o + 1)
      for (const r of Array.from(range.getClientRects())) {
        if (r.height > 0 && r.top <= y && r.bottom >= y) {
          const parent = node.parentElement
          let lineHeight = parent ? parseFloat(win.getComputedStyle(parent).lineHeight) : NaN
          if (Number.isNaN(lineHeight)) lineHeight = r.height * 1.2
          const half = Math.max(0, (lineHeight - r.height) / 2)
          return { top: r.top - half, bottom: r.bottom + half }
        }
      }
    }
  }
  return null
}

/** Linha cortada pela borda de baixo, se houver. */
export function cutLine(win: Window): LineSpan | null {
  const w = frameWindow(win)
  const l = lineAt(win, w.b - 0.5)
  return l && l.bottom > w.b + 0.5 && l.top > w.t + 0.5 ? l : null
}

/** Folga em branco no fim do capítulo (modo páginas), da altura da tela. */
export function ensureSpacer(win: Window): HTMLElement | null {
  const doc = win.document
  const existing = doc.getElementById('alx-spacer')
  if (existing) return existing
  if (!doc.body) return null
  const spacer = doc.createElement('div')
  spacer.id = 'alx-spacer'
  spacer.setAttribute('aria-hidden', 'true')
  spacer.style.height = `${win.innerHeight}px`
  doc.body.appendChild(spacer)
  return spacer
}

/** Onde o texto do capítulo acaba (topo da folga), na altura da tela. */
export function contentEnd(win: Window): number {
  const spacer = win.document.getElementById('alx-spacer')
  return spacer ? spacer.getBoundingClientRect().top : win.document.documentElement.scrollHeight - win.scrollY
}

export const isLastPage = (win: Window) => contentEnd(win) <= frameWindow(win).b + 1

/** Próxima página. false = acabou o capítulo (o leitor abre o próximo). */
export function nextPage(win: Window): boolean {
  const w = frameWindow(win)
  if (contentEnd(win) <= w.b + 1) return false
  const c = cutLine(win)
  let d = c ? c.top - w.t : w.h
  if (d < w.h * 0.25) d = w.h
  win.scrollBy(0, Math.round(d))
  return true
}

/** Página anterior. false = já está no começo do capítulo. */
export function previousPage(win: Window): boolean {
  if (win.scrollY <= 0) return false
  const w = frameWindow(win)
  win.scrollBy(0, -Math.round(w.h))
  const s = lineAt(win, w.t + 0.5)
  if (s && s.top < w.t - 0.5 && s.bottom - w.t < w.h * 0.75) win.scrollBy(0, Math.round(s.bottom - w.t))
  return true
}

/** Ao abrir ou pular para um ponto: a linha cortada no topo desce e aparece inteira. */
export function snapToLine(win: Window) {
  const w = frameWindow(win)
  const s = lineAt(win, w.t + 0.5)
  if (s && s.top < w.t - 0.5 && w.t - s.top < w.h * 0.5) win.scrollBy(0, Math.floor(s.top - w.t))
}

/** Onde começa a cobertura de baixo (topo da linha cortada, ou a borda). */
export function maskTop(win: Window): number {
  return cutLine(win)?.top ?? frameWindow(win).b
}
