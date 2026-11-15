import { chunkText, decodeText } from '../core/text'
import { groupParagraphs, plainText, readDocx, type DocxParagraph } from '../core/docx'
import { ZipArchive } from '../core/zip'

/** Bloco do leitor de texto: texto puro (TXT), HTML formatado (DOCX) ou imagem (figura do DOCX). */
export type TextChunk =
  | { kind: 'text'; text: string }
  | { kind: 'html'; html: string; text: string }
  | { kind: 'image'; url: string; text: '' }

export interface TextContent {
  chunks: TextChunk[]
  truncated: boolean
  /** Libera as URLs temporárias das imagens. */
  dispose: () => void
}

/** Mesmo limite do app: arquivos maiores abrem só no começo. */
export const MAX_TEXT_BYTES = 8 * 1024 * 1024

export async function loadTxt(file: Blob): Promise<TextContent> {
  const truncated = file.size > MAX_TEXT_BYTES
  const bytes = new Uint8Array(await file.slice(0, MAX_TEXT_BYTES).arrayBuffer())
  const text = decodeText(bytes, truncated)
  return { chunks: chunkText(text).map((t) => ({ kind: 'text', text: t })), truncated, dispose: () => {} }
}

const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** Um parágrafo de DOCX em HTML: títulos, listas, negrito, itálico e sublinhado. */
export function paragraphHtml(p: DocxParagraph): string {
  // Fronteiras dos estilos, para abrir e fechar as marcas na ordem certa.
  const cuts = new Set<number>([0, p.text.length])
  for (const r of p.styles) {
    cuts.add(Math.min(r.start, p.text.length))
    cuts.add(Math.min(r.end, p.text.length))
  }
  const points = [...cuts].sort((a, b) => a - b)
  let body = ''
  for (let k = 0; k < points.length - 1; k++) {
    const [from, to] = [points[k], points[k + 1]]
    if (from >= to) continue
    let piece = escape(p.text.slice(from, to))
    const active = p.styles.filter((r) => r.start <= from && r.end >= to)
    if (active.some((r) => r.underline)) piece = `<u>${piece}</u>`
    if (active.some((r) => r.italic)) piece = `<em>${piece}</em>`
    if (active.some((r) => r.bold)) piece = `<strong>${piece}</strong>`
    body += piece
  }
  switch (p.kind) {
    case 'TITLE':
      return `<h1 class="docx-title">${body}</h1>`
    case 'HEADING1':
      return `<h2>${body}</h2>`
    case 'HEADING2':
      return `<h3>${body}</h3>`
    case 'HEADING3':
      return `<h4>${body}</h4>`
    case 'LIST_ITEM':
      return `<p class="docx-item">•&nbsp;&nbsp;${body}</p>`
    default:
      return `<p>${body}</p>`
  }
}

const IMAGE_TYPES: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp', svg: 'image/svg+xml' }

export async function loadDocx(file: Blob): Promise<TextContent> {
  const zip = await ZipArchive.open(file)
  const paragraphs = await readDocx(zip)
  const urls: string[] = []
  const chunks: TextChunk[] = []
  let text: DocxParagraph[] = []
  const flush = () => {
    for (const group of groupParagraphs(text)) chunks.push({ kind: 'html', html: group.map(paragraphHtml).join(''), text: plainText(group) })
    text = []
  }
  for (const p of paragraphs) {
    if (p.text.trim()) text.push({ ...p, images: [] })
    if (p.images.length) {
      flush()
      for (const path of p.images) {
        const extension = path.slice(path.lastIndexOf('.') + 1).toLowerCase()
        // Formatos que o navegador não mostra (EMF/WMF do Word) ficam de fora.
        if (!IMAGE_TYPES[extension]) continue
        const url = URL.createObjectURL(new Blob([await zip.bytes(path)], { type: IMAGE_TYPES[extension] }))
        urls.push(url)
        chunks.push({ kind: 'image', url, text: '' })
      }
    }
  }
  flush()
  return { chunks, truncated: false, dispose: () => urls.forEach((u) => URL.revokeObjectURL(u)) }
}
