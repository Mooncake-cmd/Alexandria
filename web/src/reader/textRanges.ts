import { fold, isLetterOrDigit } from '../core/text'
import { findTerms, type GlossaryTerm } from '../core/glossary'

/**
 * Texto de um trecho da página com o mapa de volta para os nós (para marcar resultados de busca e
 * termos do glossário com a CSS Custom Highlight API, sem mexer no texto do livro).
 */
export interface TextMap {
  text: string
  nodes: Text[]
  starts: number[]
}

export function textMap(root: Node): TextMap {
  const doc = root.ownerDocument ?? (root as Document)
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []
  const starts: number[] = []
  let text = ''
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const parent = (node as Text).parentElement
    if (parent && /^(script|style|title)$/i.test(parent.localName)) continue
    nodes.push(node as Text)
    starts.push(text.length)
    text += (node as Text).data
  }
  return { text, nodes, starts }
}

/** Range do trecho [start, end) do texto do mapa. */
export function rangeOf(map: TextMap, start: number, end: number): Range | null {
  const locate = (offset: number, preferEnd: boolean): [Text, number] | null => {
    let lo = 0
    let hi = map.nodes.length - 1
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1
      if (map.starts[mid] < offset || (!preferEnd && map.starts[mid] === offset)) lo = mid
      else hi = mid - 1
    }
    const node = map.nodes[lo]
    if (!node) return null
    return [node, Math.min(node.data.length, offset - map.starts[lo])]
  }
  const a = locate(start, false)
  const b = locate(end, true)
  if (!a || !b) return null
  const range = (a[0].ownerDocument as Document).createRange()
  range.setStart(a[0], a[1])
  range.setEnd(b[0], b[1])
  return range
}

/** Ocorrências do termo buscado (sem maiúsculas e acentos). */
export function searchRanges(map: TextMap, term: string): Range[] {
  const needle = fold(term.trim())
  if (!needle) return []
  const haystack = fold(map.text)
  const out: Range[] = []
  for (let from = haystack.indexOf(needle); from >= 0; from = haystack.indexOf(needle, from + needle.length)) {
    const r = rangeOf(map, from, from + needle.length)
    if (r) out.push(r)
  }
  return out
}

/** Termos do glossário no texto: palavras inteiras, termo mais longo primeiro (como o app). */
export function termRanges(map: TextMap, terms: GlossaryTerm[]): { range: Range; termId: number }[] {
  return findTerms(map.text, terms)
    .map((m) => ({ range: rangeOf(map, m.start, m.end), termId: m.termId }))
    .filter((x): x is { range: Range; termId: number } => !!x.range)
}

type HighlightWindow = Window & { CSS: typeof CSS & { highlights?: Map<string, unknown> }; Highlight?: new (...r: Range[]) => unknown }

/** Marca (ou limpa) um grupo de trechos num documento, se o navegador tiver a Highlight API. */
export function setHighlight(win: Window, name: string, ranges: Range[]) {
  const w = win as HighlightWindow
  if (!w.CSS?.highlights || !w.Highlight) return
  if (ranges.length) w.CSS.highlights.set(name, new w.Highlight(...ranges))
  else w.CSS.highlights.delete(name)
}

/** O termo do glossário sob o ponto clicado, se houver. */
export function termAtPoint(doc: Document, x: number, y: number, marks: { range: Range; termId: number }[]): number | null {
  for (const m of marks) {
    for (const r of Array.from(m.range.getClientRects())) {
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return m.termId
    }
  }
  void doc
  return null
}

export const wordBoundary = isLetterOrDigit
