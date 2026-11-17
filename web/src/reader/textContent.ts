import { chunkText, decodeText } from '../core/text'
import { readDocxBlocks, type DocxBlock, type DocxFigure, type DocxParagraph, type DocxTable } from '../core/docx'
import { CHUNK_TARGET } from '../core/text'
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

/** Trechos do parágrafo com negrito, itálico, sublinhado, tachado, tamanho e sobrescrito. */
function runsHtml(p: DocxParagraph): string {
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
    let piece = escape(p.text.slice(from, to)).replace(/\n/g, '<br>')
    const active = p.styles.filter((r) => r.start <= from && r.end >= to)
    if (active.some((r) => r.strike)) piece = `<s>${piece}</s>`
    if (active.some((r) => r.underline)) piece = `<u>${piece}</u>`
    if (active.some((r) => r.italic)) piece = `<em>${piece}</em>`
    if (active.some((r) => r.bold)) piece = `<strong>${piece}</strong>`
    const vert = active.find((r) => r.vert)?.vert
    if (vert) piece = `<${vert}>${piece}</${vert}>`
    const size = active.find((r) => r.size)?.size
    if (size && p.kind === 'NORMAL') piece = `<span style="font-size:${size}em">${piece}</span>`
    body += piece
  }
  return body
}

/** Um parágrafo de DOCX em HTML: títulos, listas, estilos, alinhamento, recuos e espaços. */
export function paragraphHtml(p: DocxParagraph): string {
  const body = runsHtml(p)
  const classes: string[] = []
  if (p.align === 'center' || p.align === 'right') classes.push(`docx-${p.align}`)
  const style: string[] = []
  if (p.indent) style.push(`margin-left:${p.indent}em`)
  if (p.firstLine) style.push(`text-indent:${p.firstLine}em`)
  if (p.before) style.push(`margin-top:${p.before}em`)
  if (p.after !== undefined && p.after < 1) style.push(`margin-bottom:${Math.max(0.3, p.after)}em`)
  const attrs = `${classes.length ? ` class="${classes.join(' ')}"` : ''}${style.length ? ` style="${style.join(';')}"` : ''}`
  switch (p.kind) {
    case 'TITLE':
      return `<h1 class="docx-title${classes.length ? ' ' + classes.join(' ') : ''}"${style.length ? ` style="${style.join(';')}"` : ''}>${body}</h1>`
    case 'HEADING1':
      return `<h2${attrs}>${body}</h2>`
    case 'HEADING2':
      return `<h3${attrs}>${body}</h3>`
    case 'HEADING3':
      return `<h4${attrs}>${body}</h4>`
    case 'LIST_ITEM':
      return `<p class="docx-item${classes.length ? ' ' + classes.join(' ') : ''}"${style.length ? ` style="${style.join(';')}"` : ''}>•&nbsp;&nbsp;${body}</p>`
    default:
      return `<p${attrs}>${body}</p>`
  }
}

/** Figuras de um parágrafo, cada uma do seu lado (como as logos de um cabeçalho), no tamanho delas. */
export function figuresHtml(figures: DocxFigure[], align: DocxParagraph['align'], url: (path: string) => string | undefined): string {
  const groups: Record<'l' | 'c' | 'r', string[]> = { l: [], c: [], r: [] }
  for (const f of figures) {
    const src = url(f.path)
    if (!src) continue
    const side = f.side ?? (align === 'center' ? 'center' : align === 'right' ? 'right' : 'left')
    const size = f.width > 0 ? ` width="${f.width}"${f.height > 0 ? ` height="${f.height}"` : ''}` : ''
    groups[side === 'left' ? 'l' : side === 'right' ? 'r' : 'c'].push(`<img src="${src}" alt=""${size}>`)
  }
  if (!groups.l.length && !groups.c.length && !groups.r.length) return ''
  return `<div class="docx-figures"><span class="l">${groups.l.join('')}</span><span class="c">${groups.c.join('')}</span><span class="r">${groups.r.join('')}</span></div>`
}

/** Tabela com as colunas do documento, células mescladas e bordas. */
export function tableHtml(table: DocxTable, url: (path: string) => string | undefined): string {
  const cols = table.columns.map((w) => `<col style="width:${Math.round(w * 1000) / 10}%">`).join('')
  const rows = table.rows
    .map((row) => `<tr>${row.map((cell) => {
      const span = cell.span > 1 ? ` colspan="${cell.span}"` : ''
      const rowSpan = cell.rowSpan > 1 ? ` rowspan="${cell.rowSpan}"` : ''
      return `<td${span}${rowSpan}>${blocksHtml(cell.blocks, url)}</td>`
    }).join('')}</tr>`)
    .join('')
  return `<table class="docx-table${table.borders ? ' bordered' : ''}"><colgroup>${cols}</colgroup><tbody>${rows}</tbody></table>`
}

/** Parágrafo com as figuras: as flutuantes (logos) antes do texto; as do meio do texto, depois. */
function paragraphWithFigures(p: DocxParagraph, url: (path: string) => string | undefined): string {
  const figures = p.figures ?? []
  const floats = figures.filter((f) => f.side)
  const inline = figures.filter((f) => !f.side)
  return (floats.length ? figuresHtml(floats, p.align, url) : '') + (p.text ? paragraphHtml(p) : '') + (inline.length ? figuresHtml(inline, p.align, url) : '')
}

function blocksHtml(list: DocxBlock[], url: (path: string) => string | undefined): string {
  return list.map((b) => (b.type === 'table' ? tableHtml(b.table, url) : paragraphWithFigures(b.p, url))).join('')
}

/** Texto puro de um parágrafo (busca e seleção), com o marcador de lista. */
const paragraphText = (p: DocxParagraph) => (p.kind === 'LIST_ITEM' ? `•  ${p.text}` : p.text)

export function blockText(block: DocxBlock): string {
  if (block.type === 'p') return paragraphText(block.p)
  return block.table.rows.map((row) => row.map((cell) => cell.blocks.map(blockText).filter(Boolean).join(' ')).join('\t')).join('\n')
}

const IMAGE_TYPES: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp', svg: 'image/svg+xml' }

/** Figuras presas ao topo ou ao pé da página (cabeçalho e rodapé desenhados) vão para o começo ou o fim. */
function liftPageFigures(list: DocxBlock[]): { start: DocxFigure[]; end: DocxFigure[] } {
  const start: DocxFigure[] = []
  const end: DocxFigure[] = []
  for (const b of list) {
    if (b.type !== 'p' || !b.p.figures) continue
    start.push(...b.p.figures.filter((f) => f.place === 'start'))
    end.push(...b.p.figures.filter((f) => f.place === 'end'))
    b.p.figures = b.p.figures.filter((f) => f.place === 'inline')
  }
  return { start, end }
}

export async function loadDocx(file: Blob): Promise<TextContent> {
  const zip = await ZipArchive.open(file)
  const blocks = await readDocxBlocks(zip)
  const { start, end } = liftPageFigures(blocks)
  const urls = new Map<string, string>()
  const collect = async (list: DocxBlock[]) => {
    for (const b of list) {
      if (b.type === 'table') {
        for (const row of b.table.rows) for (const cell of row) await collect(cell.blocks)
        continue
      }
      for (const f of b.p.figures ?? []) await load(f.path)
    }
  }
  const load = async (path: string) => {
    if (urls.has(path)) return
    const extension = path.slice(path.lastIndexOf('.') + 1).toLowerCase()
    // Formatos que o navegador não mostra (EMF/WMF do Word) ficam de fora.
    if (!IMAGE_TYPES[extension]) return
    urls.set(path, URL.createObjectURL(new Blob([await zip.bytes(path)], { type: IMAGE_TYPES[extension] })))
  }
  await collect(blocks)
  for (const f of [...start, ...end]) await load(f.path)
  const url = (path: string) => urls.get(path)

  // Blocos do leitor: parágrafos agrupados até ~CHUNK_TARGET caracteres; títulos começam bloco novo.
  const chunks: TextChunk[] = []
  let html = ''
  let text: string[] = []
  let size = 0
  const flush = () => {
    if (html) chunks.push({ kind: 'html', html, text: text.join('\n\n') })
    html = ''
    text = []
    size = 0
  }
  const add = (pieceHtml: string, pieceText: string, newChunk = false) => {
    if (!pieceHtml) return
    if (html && (newChunk || size + pieceText.length > CHUNK_TARGET)) flush()
    html += pieceHtml
    if (pieceText) text.push(pieceText)
    size += pieceText.length + 2
  }
  add(figuresHtml(start.map((f) => ({ ...f, side: f.side ?? 'center' })), 'center', url), '')
  for (const b of blocks) {
    if (b.type === 'table') {
      add(tableHtml(b.table, url), blockText(b))
      continue
    }
    const heading = b.p.kind !== 'NORMAL' && b.p.kind !== 'LIST_ITEM'
    add(paragraphWithFigures(b.p, url), b.p.text ? paragraphText(b.p) : '', heading)
  }
  add(figuresHtml(end.map((f) => ({ ...f, side: f.side ?? 'center' })), 'center', url), '')
  flush()
  return { chunks, truncated: false, dispose: () => urls.forEach((u) => URL.revokeObjectURL(u)) }
}
