/** Texto: ordem natural, busca sem acentos, decodificação e divisão em blocos. Porte de Books.kt e TextDecoding.kt. */

const isDigit = (c: string) => c >= '0' && c <= '9'

/** Ordem "natural" de nomes de arquivo: pagina2 antes de pagina10, sem diferenciar maiúsculas. */
export function naturalCompare(a: string, b: string): number {
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    const ca = a[i]
    const cb = b[j]
    if (isDigit(ca) && isDigit(cb)) {
      const startA = i
      const startB = j
      while (i < a.length && isDigit(a[i])) i++
      while (j < b.length && isDigit(b[j])) j++
      const numA = a.slice(startA, i).replace(/^0+/, '')
      const numB = b.slice(startB, j).replace(/^0+/, '')
      if (numA.length !== numB.length) return numA.length - numB.length
      if (numA !== numB) return numA < numB ? -1 : 1
    } else {
      const la = ca.toLowerCase()
      const lb = cb.toLowerCase()
      if (la !== lb) return la < lb ? -1 : 1
      i++
      j++
    }
  }
  const rest = a.length - i - (b.length - j)
  if (rest !== 0) return rest
  return a < b ? -1 : a > b ? 1 : 0
}

/**
 * Tira acentos e maiúsculas sem mudar o tamanho do texto ("Ação" → "acao"), para os índices
 * continuarem valendo no original.
 */
export function fold(text: string): string {
  let out = ''
  for (let k = 0; k < text.length; k++) {
    const c = text[k]
    const base = c.charCodeAt(0) < 128 ? c : (c.normalize('NFD')[0] ?? c)
    const lower = base.toLowerCase()
    out += lower.length === 1 ? lower : base
  }
  return out
}

/** Posições de todas as ocorrências de `term` em `text`, ignorando maiúsculas e acentos. */
export function findAll(text: string, term: string): number[] {
  const needle = fold(term.trim())
  if (!needle) return []
  const haystack = fold(text)
  const result: number[] = []
  let from = haystack.indexOf(needle)
  while (from >= 0) {
    result.push(from)
    from = haystack.indexOf(needle, from + needle.length)
  }
  return result
}

export const isLetterOrDigit = (c: string) => /[\p{L}\p{N}]/u.test(c)

/**
 * Converte bytes de um .txt em texto com quebras "\n": BOM de UTF-16, UTF-8 estrito e, se falhar,
 * Windows-1252. Arquivo cortado (`truncated`) pode terminar com meio caractere UTF-8.
 */
export function decodeText(bytes: Uint8Array, truncated = false): string {
  return normalizeLineEndings(decodeRaw(bytes, truncated).replace(/^﻿/, ''))
}

export const normalizeLineEndings = (text: string) => text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')

function decodeRaw(bytes: Uint8Array, truncated: boolean): string {
  if (bytes.length >= 2) {
    const le = bytes[0] === 0xff && bytes[1] === 0xfe
    const be = bytes[0] === 0xfe && bytes[1] === 0xff
    if (le || be) {
      const length = bytes.length % 2 === 1 ? bytes.length - 1 : bytes.length
      return new TextDecoder(le ? 'utf-16le' : 'utf-16be').decode(bytes.subarray(2, length))
    }
  }
  const maxCut = truncated ? 3 : 0
  for (let cut = 0; cut <= maxCut; cut++) {
    const length = bytes.length - cut
    if (length < 0) break
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, length))
    } catch {
      // tenta o próximo corte ou Windows-1252
    }
  }
  return new TextDecoder('windows-1252').decode(bytes)
}

export const CHUNK_TARGET = 2000
export const CHUNK_MAX = 4000

/**
 * Agrupa linhas inteiras em blocos de ~`target` caracteres; `chunks.join("\n")` reconstrói o texto.
 * Linhas maiores que `max` são cortadas num espaço próximo ao limite.
 */
export function chunkText(text: string, target = CHUNK_TARGET, max = CHUNK_MAX): string[] {
  if (target < 1 || target > max) throw new Error('target deve estar entre 1 e max')
  if (!text) return []
  const chunks: string[] = []
  let current = ''
  let hasLine = false
  for (const line of text.split('\n')) {
    if (line.length > max) {
      if (hasLine) chunks.push(current)
      current = ''
      hasLine = false
      chunks.push(...splitLongLine(line, max))
      continue
    }
    if (hasLine && current.length + 1 + line.length > target) {
      chunks.push(current)
      current = ''
      hasLine = false
    }
    if (hasLine) current += '\n'
    current += line
    hasLine = true
  }
  if (hasLine) chunks.push(current)
  return chunks
}

function splitLongLine(line: string, max: number): string[] {
  const parts: string[] = []
  let start = 0
  while (line.length - start > max) {
    const limit = start + max
    const space = line.lastIndexOf(' ', limit - 1)
    const end = space > start + max / 2 ? space + 1 : limit
    parts.push(line.slice(start, end))
    start = end
  }
  parts.push(line.slice(start))
  return parts
}
