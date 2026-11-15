/** Formatos aceitos e textos de progresso. Porte de Formats.kt. */

export type Format = 'pdf' | 'epub' | 'mobi' | 'djvu' | 'docx' | 'txt' | 'cbz' | 'cbr'

export const SUPPORTED: readonly Format[] = ['pdf', 'epub', 'mobi', 'djvu', 'docx', 'txt', 'cbz', 'cbr']
export const READABLE = new Set<string>(SUPPORTED)
/** Posição salva = índice da página. */
export const PAGED = new Set<string>(['pdf', 'cbz', 'cbr', 'djvu'])
/** Total salvo = capítulos. */
export const CHAPTERED = new Set<string>(['epub', 'mobi'])

const ALIASES: Record<string, Format> = { azw: 'mobi', azw3: 'mobi', djv: 'djvu' }
const MIMES: Record<string, Format> = {
  'application/pdf': 'pdf',
  'application/epub+zip': 'epub',
  'text/plain': 'txt',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
  'application/x-mobipocket-ebook': 'mobi',
  'application/vnd.amazon.ebook': 'mobi',
  'image/vnd.djvu': 'djvu',
  'image/x-djvu': 'djvu',
  'application/vnd.comicbook+zip': 'cbz',
  'application/x-cbz': 'cbz',
  'application/vnd.comicbook-rar': 'cbr',
  'application/x-cbr': 'cbr'
}

/** Formato pelo nome (extensão) e, se não der, pelo tipo MIME. */
export function detectFormat(name: string, mime?: string | null): Format | null {
  const dot = name.lastIndexOf('.')
  const extension = dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''
  if ((SUPPORTED as readonly string[]).includes(extension)) return extension as Format
  if (ALIASES[extension]) return ALIASES[extension]
  return (mime && MIMES[mime]) || null
}

/** O que o cartão precisa para descrever o progresso. */
export interface ProgressInfo {
  format: string
  position: number
  pageCount: number
  openedAt: number
  finishedAt: number
}

export const FINISHED_FRACTION = 9950

export function describeStatus(item: ProgressInfo): string {
  if (!READABLE.has(item.format)) return 'Leitor em breve'
  if (item.finishedAt > 0) return 'Concluído'
  if (PAGED.has(item.format)) return describePages(item.position, item.pageCount, item.openedAt > 0)
  if (CHAPTERED.has(item.format) && item.position <= 0) {
    return item.pageCount > 0 ? `Toque para ler · ${item.pageCount} capítulos` : 'Toque para ler'
  }
  if (item.position <= 0) return 'Toque para ler'
  if (item.position >= FINISHED_FRACTION) return 'Lido até o fim'
  return `${Math.trunc(item.position / 100)}% lido`
}

/** Progresso de 0 a 1 para a barra do cartão. */
export function progressOf(item: ProgressInfo): number {
  let value: number
  if (item.finishedAt > 0) value = 1
  else if (PAGED.has(item.format)) {
    value = item.pageCount > 0 && (item.openedAt > 0 || item.position > 0) ? (item.position + 1) / item.pageCount : 0
  } else value = item.position / 10000
  return Math.min(1, Math.max(0, value))
}

function describePages(page: number, pageCount: number, opened: boolean): string {
  if (pageCount <= 0) return page <= 0 ? 'Toque para ler' : `Parou na página ${page + 1}`
  if (!opened && page <= 0) return `Toque para ler · ${pageCount} páginas`
  if (page >= pageCount - 1 && pageCount > 1) return `Última página · ${pageCount} páginas`
  return `Página ${page + 1} de ${pageCount} · ${Math.trunc(((page + 1) * 100) / pageCount)}%`
}

/** Onde fica uma anotação, em texto curto. */
export function describeNoteLocation(format: string, position: number, pageCount: number): string {
  if (PAGED.has(format)) return `Pág. ${position + 1}`
  if (CHAPTERED.has(format) && pageCount > 0) {
    const chapter = Math.min(pageCount - 1, Math.max(0, Math.trunc((position * pageCount) / 10000)))
    return `Cap. ${chapter + 1} · ${Math.trunc(position / 100)}%`
  }
  return `${Math.trunc(position / 100)}%`
}
