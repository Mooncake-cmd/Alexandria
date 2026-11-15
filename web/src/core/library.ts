import { naturalCompare } from './text'

/** Páginas de um quadrinho: imagens do arquivo, em ordem natural, sem pastas do macOS nem ocultos. */
const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'])

export function comicPageNames(entryNames: string[]): string[] {
  return entryNames
    .filter((name) => {
      const file = name.slice(name.lastIndexOf('/') + 1)
      const dot = file.lastIndexOf('.')
      const extension = dot >= 0 ? file.slice(dot + 1).toLowerCase() : ''
      return !name.endsWith('/') && !name.startsWith('__MACOSX/') && !file.startsWith('.') && IMAGE_EXTENSIONS.has(extension)
    })
    .sort(naturalCompare)
}

/** Um ZIP começa com "PK": muitos .cbr são ZIPs com a extensão trocada. */
export const looksLikeZip = (header: Uint8Array) => header.length >= 2 && header[0] === 0x50 && header[1] === 0x4b

/** Ordem das prateleiras: sem diferenciar maiúsculas. */
export function shelfCompare(a: string, b: string): number {
  const la = a.toLowerCase()
  const lb = b.toLowerCase()
  if (la !== lb) return la < lb ? -1 : 1
  return a < b ? -1 : a > b ? 1 : 0
}

/** Reaproveita uma prateleira existente se o nome só difere em maiúsculas ou espaços. */
export function resolveShelf(typed: string, existing: string[]): string {
  const clean = typed.trim().replace(/\s+/g, ' ')
  return existing.find((s) => s.trim().toLowerCase() === clean.toLowerCase()) ?? clean
}

/** Caminhos dentro de um ZIP: resolve "../", tira "./" e decodifica %20. Porte de ZipPaths. */
export function resolveZipPath(baseDir: string, href: string): string {
  const clean = decodePath(href.split('#')[0].split('?')[0])
  const parts: string[] = clean.startsWith('/') ? [] : baseDir.split('/').filter(Boolean)
  for (const part of clean.split('/')) {
    if (part === '' || part === '.') continue
    if (part === '..') parts.pop()
    else parts.push(part)
  }
  return parts.join('/')
}

export const dirOf = (path: string) => (path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '')

function decodePath(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

/** Nome do livro sem a extensão conhecida ("Dom Casmurro.epub" → "Dom Casmurro"). */
export function displayTitle(name: string): string {
  const dot = name.lastIndexOf('.')
  if (dot <= 0) return name
  const extension = name.slice(dot + 1).toLowerCase()
  return ['pdf', 'epub', 'mobi', 'azw', 'azw3', 'djvu', 'djv', 'docx', 'txt', 'cbz', 'cbr'].includes(extension) ? name.slice(0, dot) : name
}
