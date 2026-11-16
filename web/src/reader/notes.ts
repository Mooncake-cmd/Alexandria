/**
 * Notas de rodapé dos EPUB/MOBI (a mesma regra do script do app): acha o link mais perto do clique
 * e extrai o texto de uma nota, do capítulo aberto ou de outro arquivo do livro.
 */
const OPS = 'http://www.idpf.org/2007/ops'
const NOTE = /(^|\s)(footnote|endnote|rearnote|note|doc-footnote|doc-endnote)(\s|$)/
const KEEP = new Set(['i', 'em', 'b', 'strong', 'sup', 'sub', 'cite'])
const BLOCK = new Set(['p', 'div', 'li', 'dd', 'dt', 'blockquote', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'aside', 'section', 'tr'])

const tagOf = (e: Element) => (e.localName || e.tagName || '').toLowerCase()

function typeOf(e: Element): string {
  const type = e.getAttribute('epub:type') || e.getAttributeNS(OPS, 'type') || ''
  return `${type} ${e.getAttribute('role') ?? ''}`.toLowerCase()
}

/** A caixa da nota (aside/section/li marcados como nota) que contém [el], ou null. */
export function noteBox(el: Element | null): Element | null {
  for (let e = el; e; e = e.parentElement) {
    const tag = tagOf(e)
    if (tag === 'body' || tag === 'html') return null
    if (NOTE.test(typeOf(e))) return e
  }
  return null
}

export interface LinkHit {
  element: HTMLAnchorElement | Element
  href: string
  /** epub:type="noteref" ou role="doc-noteref". */
  noteref: boolean
  /** Sobrescrito (<sup> ou vertical-align: super). */
  raised: boolean
  /** O link está dentro de uma nota (é o número de volta ao texto). */
  inNote: boolean
  text: string
}

function raised(a: Element): boolean {
  const view = a.ownerDocument.defaultView
  let e: Element | null = a
  for (let i = 0; e && i < 3 && tagOf(e) !== 'body'; e = e.parentElement, i++) {
    if (tagOf(e) === 'sup') return true
    const v = view?.getComputedStyle(e).verticalAlign
    if (v === 'super' || v === 'top' || v === 'text-top') return true
  }
  return false
}

function distance(r: DOMRect, x: number, y: number) {
  const dx = x < r.left ? r.left - x : x > r.right ? x - r.right : 0
  const dy = y < r.top ? r.top - y : y > r.bottom ? y - r.bottom : 0
  return Math.hypot(dx, dy)
}

export function describeLink(link: Element): LinkHit {
  return {
    element: link,
    href: link.getAttribute('href') ?? '',
    noteref: /noteref/.test(typeOf(link)),
    raised: raised(link),
    inNote: !!noteBox(link),
    text: (link.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 24)
  }
}

/** O link sob o clique ou o mais perto dele, num raio de [radius] pixels. */
export function nearestLink(doc: Document, target: Element | null, x: number, y: number, radius: number): LinkHit | null {
  const direct = target?.closest?.('a[href]')
  if (direct) return describeLink(direct)
  let best: Element | null = null
  let bestDistance = radius + 0.01
  for (const link of Array.from(doc.querySelectorAll('a[href]'))) {
    for (const r of Array.from(link.getClientRects())) {
      if (r.width === 0 || r.bottom < y - radius || r.top > y + radius) continue
      const d = distance(r, x, y)
      if (d < bestDistance) {
        bestDistance = d
        best = link
      }
    }
  }
  return best ? describeLink(best) : null
}

const escapeHtml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** HTML só com itálico, negrito, sobrescrito e quebras de linha (seguro para mostrar no site). */
export function cleanNoteHtml(node: Node): string {
  let out = ''
  for (let c = node.firstChild; c; c = c.nextSibling) {
    if (c.nodeType === 3) {
      out += escapeHtml((c as Text).data.replace(/\s+/g, ' '))
      continue
    }
    if (c.nodeType !== 1) continue
    const tag = tagOf(c as Element)
    if (tag === 'script' || tag === 'style' || tag === 'img' || tag === 'svg') continue
    if (tag === 'br') {
      out += '<br>'
      continue
    }
    const inner = cleanNoteHtml(c)
    if (KEEP.has(tag)) out += `<${tag}>${inner}</${tag}>`
    else if (BLOCK.has(tag)) out += (out && !out.endsWith('<br>') ? '<br>' : '') + inner + '<br>'
    else out += inner
  }
  return out
}

export interface NoteContent {
  html: string
  text: string
}

/**
 * Texto da nota que começa em [el]. Sem marcação de nota, só vale se o link parecia uma chamada
 * de nota ([likely]) ou se for um arquivo fora da ordem de leitura ([force]).
 */
export function noteContent(el: Element | null, likely: boolean, force: boolean): NoteContent | null {
  if (!el) return null
  let box = noteBox(el)
  if (!box && (likely || force)) {
    let e: Element | null = el
    while (e && e.parentElement && !BLOCK.has(tagOf(e)) && tagOf(e) !== 'body') e = e.parentElement
    box = e && /^(body|html)$/.test(tagOf(e)) ? (force ? e : null) : e
  }
  if (!box) return null
  const text = (box.textContent ?? '').replace(/\s+/g, ' ').trim()
  if (!text || (!force && text.length > 4000)) return null
  let html = cleanNoteHtml(box).replace(/^(\s|<br>)+|(\s|<br>)+$/g, '').replace(/(<br>){3,}/g, '<br><br>')
  if (html.length > 20000) html = escapeHtml(text.slice(0, 6000)) + '…'
  return { html, text: text.slice(0, 6000) }
}

/** O link parece chamar uma nota? (noteref, ou número curto sobrescrito ou entre colchetes). */
export function linkLooksLikeNote(hit: LinkHit, looksLikeMark: (t: string) => boolean): boolean {
  return !hit.inNote && (hit.noteref || (looksLikeMark(hit.text) && (hit.raised || hit.text.startsWith('['))))
}
