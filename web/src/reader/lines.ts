import type { LineSpan } from '../core/pages'

/**
 * Linhas visíveis (e um pouco além) de um leitor de texto, em pixels a partir do topo da área de
 * leitura. Texto: as caixas de cada linha (Range.getClientRects); imagens e afins: o bloco inteiro.
 */
export function visibleLines(scroller: HTMLElement, blocks: HTMLElement[]): LineSpan[] {
  const box = scroller.getBoundingClientRect()
  const height = scroller.clientHeight
  const raw: LineSpan[] = []
  for (const block of blocks) {
    const r = block.getBoundingClientRect()
    if (r.bottom - box.top < -height || r.top - box.top > height * 2) continue
    if (block.dataset.kind === 'image') {
      if (r.height > 0) raw.push({ top: r.top - box.top, bottom: r.bottom - box.top })
      continue
    }
    const range = document.createRange()
    range.selectNodeContents(block)
    for (const rect of Array.from(range.getClientRects())) {
      if (rect.height <= 0 || rect.width <= 0) continue
      raw.push({ top: rect.top - box.top, bottom: rect.bottom - box.top })
    }
    // Imagens dentro de um bloco de texto (figuras do DOCX) também contam como linha; cada linha de
    // tabela também (o texto das células fica junto com ela, sem cortar a linha ao meio).
    for (const el of Array.from(block.querySelectorAll('img, tr'))) {
      const ir = el.getBoundingClientRect()
      if (ir.height > 0) raw.push({ top: ir.top - box.top, bottom: ir.bottom - box.top })
    }
  }
  return mergeLines(raw)
}

/** Junta as caixas que estão na mesma linha (palavras em negrito, links…) numa só. */
export function mergeLines(rects: LineSpan[]): LineSpan[] {
  const sorted = [...rects].sort((a, b) => a.top - b.top || a.bottom - b.bottom)
  const lines: LineSpan[] = []
  for (const r of sorted) {
    const last = lines[lines.length - 1]
    const overlap = last ? Math.min(last.bottom, r.bottom) - Math.max(last.top, r.top) : 0
    if (last && overlap > 0.5 * Math.min(last.bottom - last.top, r.bottom - r.top)) {
      last.top = Math.min(last.top, r.top)
      last.bottom = Math.max(last.bottom, r.bottom)
    } else {
      lines.push({ ...r })
    }
  }
  return lines
}
