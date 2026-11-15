import { parseXml, walk } from './xml'
import { resolveZipPath } from './library'
import type { BookFiles } from './epub'
import { CHUNK_TARGET } from './text'

/** Parágrafo de DOCX convertido em texto, com trechos em negrito/itálico/sublinhado. Porte de DocxParagraph. */
export type DocxKind = 'TITLE' | 'HEADING1' | 'HEADING2' | 'HEADING3' | 'LIST_ITEM' | 'NORMAL'

export interface StyleRange {
  start: number
  end: number
  bold: boolean
  italic: boolean
  underline: boolean
}

export interface DocxParagraph {
  text: string
  kind: DocxKind
  styles: StyleRange[]
  /** Imagens do parágrafo, como caminhos no ZIP (ex.: "word/media/image1.png"). */
  images: string[]
}

const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'

export async function readDocx(zip: BookFiles): Promise<DocxParagraph[]> {
  const safe = async <T>(path: string, parse: (xml: string) => T, fallback: T): Promise<T> => {
    if (!zip.has(path)) return fallback
    try {
      return parse(await zip.text(path))
    } catch {
      return fallback
    }
  }
  const styles = await safe('word/styles.xml', headingLevels, new Map<string, number>())
  const relations = await safe('word/_rels/document.xml.rels', (x) => relationships(x, 'word'), new Map<string, string>())
  if (!zip.has('word/document.xml')) throw new Error('DOCX sem word/document.xml')
  return paragraphs(await zip.text('word/document.xml'), styles, relations)
    .map((p) => ({ ...p, images: p.images.filter((i) => zip.has(i)) }))
    .filter((p) => p.text.trim() || p.images.length)
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
  const result = new Map<string, number>()
  let styleId: string | null = null
  walk(parseXml(xml), {
    start: (e) => {
      if (e.namespaceURI !== W) return
      if (e.localName === 'style') styleId = e.getAttributeNS(W, 'styleId')
      else if (e.localName === 'name' && styleId) {
        const name = (e.getAttributeNS(W, 'val') ?? '').toLowerCase()
        if (name === 'title') result.set(styleId, 0)
        else if (name.startsWith('heading ')) {
          const level = Number(name.slice(8))
          if (Number.isInteger(level)) result.set(styleId, level)
        }
      } else if (e.localName === 'outlineLvl' && styleId) {
        const level = Number(e.getAttributeNS(W, 'val'))
        if (Number.isInteger(level) && !result.has(styleId)) result.set(styleId, level + 1)
      }
    }
  })
  return result
}

const OFF = new Set(['0', 'false', 'off', 'none'])

export function paragraphs(xml: string, levels = new Map<string, number>(), relations = new Map<string, string>()): DocxParagraph[] {
  const result: DocxParagraph[] = []
  const images: string[] = []
  let text = ''
  let ranges: StyleRange[] = []
  let level: number | null = null
  let list = false
  let depth = 0 // caixas de texto põem parágrafos dentro de parágrafos: só o mais externo conta
  let inRun = false
  let inRunProps = false
  let bold = false
  let italic = false
  let underline = false
  let runStart = 0
  const flag = (e: Element) => {
    const value = e.getAttributeNS(W, 'val')
    return value === null || !OFF.has(value)
  }

  walk(parseXml(xml), {
    start: (e) => {
      if (depth > 0 && (e.localName === 'blip' || e.localName === 'imagedata')) {
        const id = e.getAttributeNS(R, 'embed') ?? e.getAttributeNS(R, 'id')
        const path = id ? relations.get(id) : undefined
        if (path && !images.includes(path)) images.push(path)
        return
      }
      if (e.namespaceURI !== W) return
      switch (e.localName) {
        case 'p':
          if (depth === 0) {
            text = ''
            ranges = []
            images.length = 0
            level = null
            list = false
          } else if (text && !text.endsWith('\n')) text += '\n'
          depth++
          break
        case 'pStyle': {
          const id = e.getAttributeNS(W, 'val')
          if (id) {
            const heading = /^heading(\d)$/i.exec(id)
            level = levels.get(id) ?? (heading ? Number(heading[1]) : id.toLowerCase() === 'title' ? 0 : null)
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
          bold = italic = underline = false
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
      if (e.namespaceURI !== W) return
      switch (e.localName) {
        case 'rPr':
          inRunProps = false
          break
        case 'r':
          inRun = false
          if (text.length > runStart && (bold || italic || underline)) ranges.push({ start: runStart, end: text.length, bold, italic, underline })
          break
        case 'p': {
          depth--
          if (depth > 0) {
            text += '\n'
            break
          }
          const kind: DocxKind =
            level === 0 ? 'TITLE' : level === 1 ? 'HEADING1' : level === 2 ? 'HEADING2' : level !== null && level >= 3 ? 'HEADING3' : list ? 'LIST_ITEM' : 'NORMAL'
          if (text.trim() || images.length) result.push({ text: text.trim() ? text : '', kind, styles: [...ranges], images: [...images] })
          break
        }
      }
    }
  })
  return result
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
