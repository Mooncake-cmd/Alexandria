import { fold, isLetterOrDigit } from './text'

/** Termo do glossário de um livro. */
export interface GlossaryTerm {
  id: number
  itemId: number
  term: string
  definition: string
  createdAt: number
}

export interface GlossaryMatch {
  start: number
  end: number
  termId: number
}

/**
 * Ocorrências dos termos no texto: palavras inteiras, sem diferenciar maiúsculas nem acentos.
 * Termos mais longos têm preferência ("Casa Atreides" antes de "Casa"). Porte de `Glossary.find`.
 */
export function findTerms(text: string, terms: GlossaryTerm[]): GlossaryMatch[] {
  if (!terms.length || !text) return []
  const haystack = fold(text)
  const taken = new Uint8Array(haystack.length)
  const result: GlossaryMatch[] = []
  for (const term of [...terms].sort((a, b) => b.term.length - a.term.length)) {
    const needle = fold(term.term.trim())
    if (!needle) continue
    let from = haystack.indexOf(needle)
    while (from >= 0) {
      const end = from + needle.length
      const wordStart = from === 0 || !isLetterOrDigit(haystack[from - 1])
      const wordEnd = end === haystack.length || !isLetterOrDigit(haystack[end])
      if (wordStart && wordEnd && !taken.subarray(from, end).some((v) => v === 1)) {
        taken.fill(1, from, end)
        result.push({ start: from, end, termId: term.id })
      }
      from = haystack.indexOf(needle, from + 1)
    }
  }
  return result.sort((a, b) => a.start - b.start)
}

const SKIP_TAGS = new Set(['script', 'style', 'title', 'head'])

/**
 * Marca os termos no HTML de um capítulo com <span class="alx-term" data-term="id">, só no texto
 * entre as tags e fora de <head>, <script>, <style> e <title>. Porte de `Glossary.markHtml`.
 */
export function markHtml(html: string, terms: GlossaryTerm[]): string {
  if (!terms.length) return html
  let out = ''
  let skipping: string | null = null
  let position = 0
  for (const match of html.matchAll(/<[^>]*>/g)) {
    const at = match.index ?? 0
    const textPart = html.slice(position, at)
    out += skipping === null ? markText(textPart, terms) : textPart
    const raw = match[0]
    out += raw
    const name = /^<\/?\s*([a-zA-Z0-9:-]+)/.exec(raw)?.[1]?.toLowerCase().split(':').pop()
    if (name) {
      const closing = raw.startsWith('</')
      if (skipping === null && !closing && SKIP_TAGS.has(name) && !raw.endsWith('/>')) skipping = name
      else if (skipping !== null && closing && name === skipping) skipping = null
    }
    position = at + raw.length
  }
  const tail = html.slice(position)
  out += skipping === null ? markText(tail, terms) : tail
  return out
}

function markText(text: string, terms: GlossaryTerm[]): string {
  if (!text.trim()) return text
  const matches = findTerms(text, terms)
  if (!matches.length) return text
  let out = ''
  let last = 0
  for (const m of matches) {
    out += text.slice(last, m.start)
    out += `<span class="alx-term" data-term="${m.termId}">${text.slice(m.start, m.end)}</span>`
    last = m.end
  }
  return out + text.slice(last)
}
