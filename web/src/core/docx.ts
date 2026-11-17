import { parseXml, walk } from './xml'
import { resolveZipPath } from './library'
import type { BookFiles } from './epub'
import { CHUNK_TARGET } from './text'

/** Parágrafo de DOCX convertido em texto, com trechos em negrito/itálico/sublinhado. Porte de DocxParagraph. */
export type DocxKind = 'TITLE' | 'HEADING1' | 'HEADING2' | 'HEADING3' | 'LIST_ITEM' | 'NORMAL'
export type DocxAlign = 'left' | 'center' | 'right' | 'justify'

export interface StyleRange {
  start: number
  end: number
  bold: boolean
  italic: boolean
  underline: boolean
  strike?: boolean
  /** Tamanho da letra em relação ao tamanho padrão do documento (1 = igual). */
  size?: number
  vert?: 'sup' | 'sub'
}

/** Figura do documento, com o tamanho dela (pixels a 96 dpi, como no Word) e onde fica. */
export interface DocxFigure {
  path: string
  width: number
  height: number
  /** Figura flutuante: de que lado da página ela fica. null = no texto (segue o alinhamento). */
  side: 'left' | 'right' | 'center' | null
  /** Presa ao topo ou ao pé da página (cabeçalhos e rodapés desenhados no corpo do texto). */
  place: 'inline' | 'start' | 'end'
}

export interface DocxParagraph {
  text: string
  kind: DocxKind
  styles: StyleRange[]
  /** Imagens do parágrafo, como caminhos no ZIP (ex.: "word/media/image1.png"). */
  images: string[]
  figures?: DocxFigure[]
  align?: DocxAlign
  /** Recuos e espaços, em "em" (tamanho da letra do documento). */
  indent?: number
  firstLine?: number
  before?: number
  after?: number
}

export interface DocxCell {
  blocks: DocxBlock[]
  /** Colunas ocupadas (mesclagem horizontal) e linhas (mesclagem vertical). */
  span: number
  rowSpan: number
}

export interface DocxTable {
  /** Largura de cada coluna, em fração da tabela (soma 1). */
  columns: number[]
  rows: DocxCell[][]
  borders: boolean
}

export type DocxBlock = { type: 'p'; p: DocxParagraph } | { type: 'table'; table: DocxTable }

const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
const WP = 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'

export interface DocxStyles {
  levels: Map<string, number>
  /** Tamanho padrão da letra, em meios-pontos (24 = 12 pt). */
  defaultSize: number
}

export async function readDocxBlocks(zip: BookFiles): Promise<DocxBlock[]> {
  const safe = async <T>(path: string, parse: (xml: string) => T, fallback: T): Promise<T> => {
    if (!zip.has(path)) return fallback
    try {
      return parse(await zip.text(path))
    } catch {
      return fallback
    }
  }
  const styles = await safe('word/styles.xml', styleInfo, { levels: new Map<string, number>(), defaultSize: 22 })
  const relations = await safe('word/_rels/document.xml.rels', (x) => relationships(x, 'word'), new Map<string, string>())
  if (!zip.has('word/document.xml')) throw new Error('DOCX sem word/document.xml')
  const keep = (blocks: DocxBlock[]): DocxBlock[] =>
    blocks.flatMap((b): DocxBlock[] => {
      if (b.type === 'table') {
        b.table.rows.forEach((row) => row.forEach((cell) => (cell.blocks = keep(cell.blocks))))
        return [b]
      }
      const p = b.p
      p.images = p.images.filter((i) => zip.has(i))
      p.figures = p.figures?.filter((f) => zip.has(f.path))
      return p.text.trim() || p.images.length ? [b] : []
    })
  return keep(blocks(await zip.text('word/document.xml'), styles, relations))
}

/** Parágrafos em sequência (tabelas viram um parágrafo por célula): busca e testes. */
export async function readDocx(zip: BookFiles): Promise<DocxParagraph[]> {
  return flatten(await readDocxBlocks(zip))
}

export function flatten(list: DocxBlock[]): DocxParagraph[] {
  return list.flatMap((b) => (b.type === 'p' ? [b.p] : b.table.rows.flatMap((row) => row.flatMap((cell) => flatten(cell.blocks)))))
}

/** Id da relação → caminho no ZIP. */
export function relationships(xml: string, baseDir: string): Map<string, string> {
  const result = new Map<string, string>()
  for (const e of Array.from(parseXml(xml).getElementsByTagNameNS('*', 'Relationship'))) {
    const id = e.getAttribute('Id')
    const target = e.getAttribute('Target')
    if (!id || !target || e.getAttribute('TargetMode') === 'External') continue
    result.set(id, resolveZipPath(baseDir, target))
  }
  return result
}

/** Id do estilo → nível de título ("Ttulo1" no Word em português tem o nome interno "heading 1"). */
export function headingLevels(xml: string): Map<string, number> {
  return styleInfo(xml).levels
}

/** Níveis de título e tamanho padrão da letra (docDefaults, ou o estilo padrão de parágrafo). */
export function styleInfo(xml: string): DocxStyles {
  const levels = new Map<string, number>()
  let defaultSize = 20 // sem nada declarado, o Word usa 10 pt
  let styleId: string | null = null
  let inDefaults = false
  let inDefaultStyle = false
  let styleSize: number | null = null
  walk(parseXml(xml), {
    start: (e) => {
      if (e.namespaceURI !== W) return
      if (e.localName === 'docDefaults') inDefaults = true
      else if (e.localName === 'style') {
        styleId = e.getAttributeNS(W, 'styleId')
        inDefaultStyle = e.getAttributeNS(W, 'type') === 'paragraph' && ['1', 'true', 'on'].includes(e.getAttributeNS(W, 'default') ?? '')
      } else if (e.localName === 'sz') {
        const value = Number(e.getAttributeNS(W, 'val'))
        if (Number.isFinite(value) && value > 0) {
          if (inDefaults) defaultSize = value
          else if (inDefaultStyle) styleSize = value
        }
      } else if (e.localName === 'name' && styleId) {
        const name = (e.getAttributeNS(W, 'val') ?? '').toLowerCase()
        if (name === 'title') levels.set(styleId, 0)
        else if (name.startsWith('heading ')) {
          const level = Number(name.slice(8))
          if (Number.isInteger(level)) levels.set(styleId, level)
        }
      } else if (e.localName === 'outlineLvl' && styleId) {
        const level = Number(e.getAttributeNS(W, 'val'))
        if (Number.isInteger(level) && !levels.has(styleId)) levels.set(styleId, level + 1)
      }
    },
    end: (e) => {
      if (e.namespaceURI !== W) return
      if (e.localName === 'docDefaults') inDefaults = false
      else if (e.localName === 'style') inDefaultStyle = false
    }
  })
  return { levels, defaultSize: styleSize ?? defaultSize }
}

const OFF = new Set(['0', 'false', 'off', 'none'])
const EMU_PER_PX = 9525 // 914400 por polegada ÷ 96 pixels
const TWIPS_PER_EM = 240 // 12 pt
const clampEm = (value: number, max: number) => Math.round(Math.min(max, Math.max(-max, value)) * 100) / 100

/** Compatível com a versão anterior: só os parágrafos (tabelas viram um parágrafo por célula). */
export function paragraphs(xml: string, levels = new Map<string, number>(), relations = new Map<string, string>()): DocxParagraph[] {
  return flatten(blocks(xml, { levels, defaultSize: 22 }, relations))
}

interface Drawing {
  anchor: boolean
  cx: number
  cy: number
  hRelative: string
  hAlign: string | null
  hOffset: number | null
  vRelative: string
  vOffset: number | null
  behind: boolean
  axis: 'h' | 'v' | null
}

interface TableBuild {
  table: DocxTable
  grid: number[]
  row: DocxCell[] | null
  /** Para cada linha, a coluna inicial de cada célula e se ela continua uma mesclagem vertical. */
  merges: { column: number; cont: boolean }[][]
  rowMerges: { column: number; cont: boolean }[]
  cell: { cell: DocxCell; vMerge: 'restart' | 'continue' | null } | null
}

/**
 * Documento em blocos: parágrafos (com alinhamento, recuos, tamanhos de letra e figuras com o
 * tamanho delas) e tabelas (colunas, células mescladas e bordas).
 */
export function blocks(xml: string, styles: DocxStyles = { levels: new Map(), defaultSize: 22 }, relations = new Map<string, string>()): DocxBlock[] {
  const doc = parseXml(xml)
  // Tamanho da página (fica no fim do documento), para saber de que lado fica uma figura flutuante.
  const pgSz = doc.getElementsByTagNameNS(W, 'pgSz')[0]
  const pgMar = doc.getElementsByTagNameNS(W, 'pgMar')[0]
  const pageWidth = (Number(pgSz?.getAttributeNS(W, 'w')) || 11906) * 635
  const pageHeight = (Number(pgSz?.getAttributeNS(W, 'h')) || 16838) * 635
  const marginLeft = (Number(pgMar?.getAttributeNS(W, 'left')) || 1440) * 635

  const body: DocxBlock[] = []
  const containers: DocxBlock[][] = [body]
  const tables: TableBuild[] = []
  const images: string[] = []
  let figures: DocxFigure[] = []
  let text = ''
  let ranges: StyleRange[] = []
  let level: number | null = null
  let list = false
  let align: DocxAlign | undefined
  let indent = 0
  let firstLine = 0
  let before = 0
  let after = 0
  let depth = 0 // caixas de texto põem parágrafos dentro de parágrafos: só o mais externo conta
  let inPPr = false
  let inRun = false
  let inRunProps = false
  let bold = false
  let italic = false
  let underline = false
  let strike = false
  let size: number | undefined
  let vert: 'sup' | 'sub' | undefined
  let runStart = 0
  let drawing: Drawing | null = null
  let inTcPr = false
  let inTblBorders = false
  const flag = (e: Element) => {
    const value = e.getAttributeNS(W, 'val')
    return value === null || !OFF.has(value)
  }
  const twips = (e: Element, ...names: string[]) => {
    for (const name of names) {
      const value = e.getAttributeNS(W, name)
      if (value !== null && value !== '') return Number(value) || 0
    }
    return 0
  }
  const figureOf = (path: string): DocxFigure => {
    const d = drawing
    const width = d && d.cx > 0 ? Math.round(d.cx / EMU_PER_PX) : 0
    const height = d && d.cy > 0 ? Math.round(d.cy / EMU_PER_PX) : 0
    if (!d?.anchor) return { path, width, height, side: null, place: 'inline' }
    let side: DocxFigure['side'] = 'center'
    const alignH = (d.hAlign ?? '').toLowerCase()
    if (alignH === 'left' || alignH === 'inside') side = 'left'
    else if (alignH === 'right' || alignH === 'outside') side = 'right'
    else if (alignH === 'center') side = 'center'
    else if (d.hOffset !== null) {
      const origin = d.hRelative === 'page' ? 0 : marginLeft
      const middle = (origin + d.hOffset + d.cx / 2) / pageWidth
      side = middle < 0.4 ? 'left' : middle > 0.6 ? 'right' : 'center'
    }
    let place: DocxFigure['place'] = 'inline'
    if ((d.vRelative === 'page' || d.vRelative === 'margin') && d.vOffset !== null) {
      const top = d.vOffset / pageHeight
      if (top > 0.75) place = 'end'
      else if (top < 0.12) place = 'start'
    }
    return { path, width, height, side, place }
  }

  walk(doc, {
    start: (e) => {
      const ns = e.namespaceURI
      if (ns === WP) {
        switch (e.localName) {
          case 'anchor':
          case 'inline':
            drawing = { anchor: e.localName === 'anchor', cx: 0, cy: 0, hRelative: 'column', hAlign: null, hOffset: null, vRelative: 'paragraph', vOffset: null, behind: e.getAttribute('behindDoc') === '1', axis: null }
            break
          case 'extent':
            if (drawing) {
              drawing.cx = Number(e.getAttribute('cx')) || 0
              drawing.cy = Number(e.getAttribute('cy')) || 0
            }
            break
          case 'positionH':
          case 'positionV':
            if (drawing) {
              drawing.axis = e.localName === 'positionH' ? 'h' : 'v'
              const relative = e.getAttribute('relativeFrom') ?? ''
              if (drawing.axis === 'h') drawing.hRelative = relative
              else drawing.vRelative = relative
            }
            break
          case 'align':
            if (drawing?.axis === 'h') drawing.hAlign = (e.textContent ?? '').trim()
            break
          case 'posOffset':
            if (drawing?.axis === 'h') drawing.hOffset = Number((e.textContent ?? '').trim()) || 0
            else if (drawing?.axis === 'v') drawing.vOffset = Number((e.textContent ?? '').trim()) || 0
            break
        }
        return
      }
      if (depth > 0 && (e.localName === 'blip' || e.localName === 'imagedata')) {
        const id = e.getAttributeNS(R, 'embed') ?? e.getAttributeNS(R, 'id')
        const path = id ? relations.get(id) : undefined
        // Fundo atrás do texto do tamanho da página (marca d'água): fica de fora.
        const d = drawing as Drawing | null
        const background = !!d && d.behind && d.cx > pageWidth * 0.5
        if (path && !images.includes(path) && !background) {
          images.push(path)
          figures.push(figureOf(path))
        }
        return
      }
      if (ns !== W) return
      const table = tables[tables.length - 1]
      switch (e.localName) {
        case 'tbl':
          if (depth > 0) break
          tables.push({ table: { columns: [], rows: [], borders: false }, grid: [], row: null, merges: [], rowMerges: [], cell: null })
          break
        case 'gridCol':
          if (table && depth === 0) table.grid.push(twips(e, 'w'))
          break
        case 'tblBorders':
          inTblBorders = true
          break
        case 'tcPr':
          inTcPr = true
          break
        case 'top':
        case 'bottom':
        case 'left':
        case 'right':
        case 'start':
        case 'end':
        case 'insideH':
        case 'insideV':
          if (table && (inTblBorders || inTcPr) && !['nil', 'none'].includes(e.getAttributeNS(W, 'val') ?? 'nil')) table.table.borders = true
          break
        case 'tr':
          if (table && depth === 0) {
            table.row = []
            table.rowMerges = []
          }
          break
        case 'tc':
          if (table && depth === 0) {
            table.cell = { cell: { blocks: [], span: 1, rowSpan: 1 }, vMerge: null }
            containers.push(table.cell.cell.blocks)
          }
          break
        case 'gridSpan':
          if (table?.cell && inTcPr) table.cell.cell.span = Math.max(1, Number(e.getAttributeNS(W, 'val')) || 1)
          break
        case 'vMerge':
          if (table?.cell && inTcPr) table.cell.vMerge = e.getAttributeNS(W, 'val') === 'restart' ? 'restart' : 'continue'
          break
        case 'p':
          if (depth === 0) {
            text = ''
            ranges = []
            images.length = 0
            figures = []
            level = null
            list = false
            align = undefined
            indent = firstLine = before = after = 0
          } else if (text && !text.endsWith('\n')) text += '\n'
          depth++
          break
        case 'pPr':
          inPPr = depth === 1
          break
        case 'jc':
          if (inPPr) {
            const value = e.getAttributeNS(W, 'val')
            align = value === 'center' ? 'center' : value === 'right' || value === 'end' ? 'right' : value === 'both' || value === 'distribute' ? 'justify' : 'left'
          }
          break
        case 'ind':
          if (inPPr) {
            indent = clampEm(twips(e, 'left', 'start') / TWIPS_PER_EM, 8)
            const hanging = twips(e, 'hanging')
            firstLine = clampEm((hanging ? -hanging : twips(e, 'firstLine')) / TWIPS_PER_EM, 4)
          }
          break
        case 'spacing':
          if (inPPr) {
            before = clampEm(twips(e, 'before') / TWIPS_PER_EM, 3)
            after = clampEm(twips(e, 'after') / TWIPS_PER_EM, 3)
          }
          break
        case 'pStyle': {
          const id = e.getAttributeNS(W, 'val')
          if (id) {
            const heading = /^heading(\d)$/i.exec(id)
            level = styles.levels.get(id) ?? (heading ? Number(heading[1]) : id.toLowerCase() === 'title' ? 0 : null)
          }
          break
        }
        case 'outlineLvl':
          if (level === null) {
            const value = Number(e.getAttributeNS(W, 'val'))
            if (Number.isInteger(value)) level = value + 1
          }
          break
        case 'numPr':
          list = true
          break
        case 'r':
          inRun = true
          bold = italic = underline = strike = false
          size = vert = undefined
          runStart = text.length
          break
        case 'rPr':
          inRunProps = inRun
          break
        case 'b':
          if (inRunProps) bold = flag(e)
          break
        case 'i':
          if (inRunProps) italic = flag(e)
          break
        case 'u':
          if (inRunProps) underline = flag(e)
          break
        case 'strike':
        case 'dstrike':
          if (inRunProps) strike = flag(e)
          break
        case 'sz':
          if (inRunProps) {
            const value = Number(e.getAttributeNS(W, 'val'))
            if (value > 0 && styles.defaultSize > 0) {
              const relative = Math.round((value / styles.defaultSize) * 100) / 100
              size = Math.min(3, Math.max(0.6, relative))
            }
          }
          break
        case 'vertAlign':
          if (inRunProps) {
            const value = e.getAttributeNS(W, 'val')
            vert = value === 'superscript' ? 'sup' : value === 'subscript' ? 'sub' : undefined
          }
          break
        case 't':
          if (depth > 0) text += e.textContent ?? ''
          return true
        case 'tab':
          if (inRun) text += '\t'
          break
        case 'br':
        case 'cr':
          if (inRun) text += '\n'
          break
      }
    },
    end: (e) => {
      if (e.namespaceURI === WP) {
        if (e.localName === 'anchor' || e.localName === 'inline') drawing = null
        else if (e.localName === 'positionH' || e.localName === 'positionV') {
          if (drawing) drawing.axis = null
        }
        return
      }
      if (e.namespaceURI !== W) return
      const table = tables[tables.length - 1]
      switch (e.localName) {
        case 'pPr':
          inPPr = false
          break
        case 'tblBorders':
          inTblBorders = false
          break
        case 'tcPr':
          inTcPr = false
          break
        case 'rPr':
          inRunProps = false
          break
        case 'r': {
          inRun = false
          const sized = size !== undefined && Math.abs(size - 1) > 0.04
          if (text.length > runStart && (bold || italic || underline || strike || sized || vert)) {
            const range: StyleRange = { start: runStart, end: text.length, bold, italic, underline }
            if (strike) range.strike = true
            if (sized) range.size = size
            if (vert) range.vert = vert
            ranges.push(range)
          }
          break
        }
        case 'p': {
          depth--
          if (depth > 0) {
            text += '\n'
            break
          }
          const kind: DocxKind =
            level === 0 ? 'TITLE' : level === 1 ? 'HEADING1' : level === 2 ? 'HEADING2' : level !== null && level >= 3 ? 'HEADING3' : list ? 'LIST_ITEM' : 'NORMAL'
          if (text.trim() || images.length) {
            const p: DocxParagraph = { text: text.trim() ? text : '', kind, styles: [...ranges], images: [...images] }
            if (figures.length) p.figures = [...figures]
            if (align) p.align = align
            if (indent) p.indent = indent
            if (firstLine) p.firstLine = firstLine
            if (before) p.before = before
            if (after) p.after = after
            containers[containers.length - 1].push({ type: 'p', p })
          }
          break
        }
        case 'tc':
          if (table?.cell && depth === 0) {
            containers.pop()
            const start = (table.row ?? []).reduce((sum, c) => sum + c.span, 0)
            table.rowMerges.push({ column: start, cont: table.cell.vMerge === 'continue' })
            table.row?.push(table.cell.cell)
            table.cell = null
          }
          break
        case 'tr':
          if (table?.row && depth === 0) {
            table.table.rows.push(table.row)
            table.merges.push(table.rowMerges)
            table.row = null
          }
          break
        case 'tbl':
          if (table && depth === 0) {
            tables.pop()
            const built = finishTable(table)
            if (built.rows.length) containers[containers.length - 1].push({ type: 'table', table: built })
          }
          break
      }
    }
  })
  return body
}

/** Larguras das colunas e mesclagens verticais (a célula de cima ganha as linhas de baixo). */
function finishTable(build: TableBuild): DocxTable {
  const { table, grid, merges } = build
  const count = Math.max(grid.length, ...table.rows.map((row) => row.reduce((sum, c) => sum + c.span, 0)), 1)
  const widths = Array.from({ length: count }, (_, i) => (grid[i] && grid[i] > 0 ? grid[i] : 0))
  const known = widths.filter((w) => w > 0)
  const fallback = known.length ? known.reduce((a, b) => a + b, 0) / known.length : 1
  const filled = widths.map((w) => (w > 0 ? w : fallback))
  const total = filled.reduce((a, b) => a + b, 0)
  table.columns = filled.map((w) => Math.round((w / total) * 10000) / 10000)
  // Mesclagem vertical: a primeira célula da coluna ganha as linhas; as de baixo somem.
  const rows: DocxCell[][] = []
  const owners = new Map<number, DocxCell>()
  table.rows.forEach((row, r) => {
    const out: DocxCell[] = []
    row.forEach((cell, c) => {
      const merge = merges[r]?.[c]
      if (merge?.cont && owners.has(merge.column)) {
        const owner = owners.get(merge.column)!
        owner.rowSpan++
        owner.blocks.push(...cell.blocks)
        return
      }
      if (merge) owners.set(merge.column, cell)
      out.push(cell)
    })
    rows.push(out)
  })
  table.rows = rows
  return table
}

const SEPARATOR = '\n\n'

/** Agrupa parágrafos inteiros em blocos de ~`target` caracteres; títulos começam bloco novo. Porte de DocxLayout.group. */
export function groupParagraphs(list: DocxParagraph[], target = CHUNK_TARGET): DocxParagraph[][] {
  const groups: DocxParagraph[][] = []
  let current: DocxParagraph[] = []
  let size = 0
  for (const p of list) {
    const heading = p.kind !== 'NORMAL' && p.kind !== 'LIST_ITEM'
    if (current.length && (size + p.text.length > target || heading)) {
      groups.push(current)
      current = []
      size = 0
    }
    current.push(p)
    size += p.text.length + SEPARATOR.length
  }
  if (current.length) groups.push(current)
  return groups
}

/** Texto puro de um bloco (busca e seleção), com o marcador de lista. */
export function plainText(group: DocxParagraph[]): string {
  return group.map((p) => (p.kind === 'LIST_ITEM' ? `•  ${p.text}` : p.text)).join(SEPARATOR)
}
