import { attr, parseXml, walk } from './xml'
import { dirOf, resolveZipPath } from './library'

/** Lugar de onde ler arquivos do livro (um ZIP). */
export interface BookFiles {
  has(path: string): boolean
  text(path: string): Promise<string>
}

/** Estrutura de um EPUB (2 ou 3): título, capítulos na ordem de leitura, sumário e capa. Porte de EpubPackage. */
export interface EpubPackage {
  title: string | null
  chapters: string[]
  chapterTitles: (string | null)[]
  coverPath: string | null
}

const HTML_TYPES = new Set(['application/xhtml+xml', 'text/html', 'application/xml'])

interface ManifestItem {
  href: string
  mediaType: string
  properties: string[]
}

export async function readEpub(zip: BookFiles): Promise<EpubPackage> {
  const open = (path: string) => {
    if (!zip.has(path)) throw new Error(`Faltando ${path}`)
    return zip.text(path)
  }
  const opfPath = rootFile(await open('META-INF/container.xml'))
  if (!opfPath) throw new Error('container.xml sem rootfile')
  const opf = parseOpf(await open(opfPath))
  const baseDir = dirOf(opfPath)
  const manifest = new Map<string, ManifestItem>()
  for (const [id, item] of opf.manifest) manifest.set(id, { ...item, href: resolveZipPath(baseDir, item.href) })

  const chapters = [...new Set(
    opf.spine.map((id) => manifest.get(id)).filter((m): m is ManifestItem => !!m && HTML_TYPES.has(m.mediaType) && zip.has(m.href)).map((m) => m.href)
  )]
  if (!chapters.length) throw new Error('EPUB sem capítulos')

  const coverItem = [...manifest.values()].find((m) => m.properties.includes('cover-image')) ?? (opf.coverId ? manifest.get(opf.coverId) : undefined)
  const coverPath = coverItem && coverItem.mediaType.startsWith('image/') ? coverItem.href : null

  let toc = new Map<string, string>()
  try {
    const nav = [...manifest.values()].find((m) => m.properties.includes('nav'))
    const ncx = [...manifest.values()].find((m) => m.mediaType === 'application/x-dtbncx+xml') ?? (opf.tocId ? manifest.get(opf.tocId) : undefined)
    if (nav && zip.has(nav.href)) toc = parseNav(await open(nav.href), dirOf(nav.href))
    else if (ncx && zip.has(ncx.href)) toc = parseNcx(await open(ncx.href), dirOf(ncx.href))
  } catch {
    // Sem sumário legível: os capítulos ficam sem título.
  }
  const title = opf.title?.trim() || null
  return { title, chapters, chapterTitles: chapters.map((c) => toc.get(c) ?? null), coverPath }
}

export function rootFile(xml: string): string | null {
  for (const e of Array.from(parseXml(xml).getElementsByTagNameNS('*', 'rootfile'))) {
    const path = e.getAttribute('full-path')
    const type = e.getAttribute('media-type')
    if (path && (!type || type === 'application/oebps-package+xml')) return path
  }
  return null
}

export function parseOpf(xml: string) {
  const doc = parseXml(xml)
  let title: string | null = null
  let coverId: string | null = null
  let tocId: string | null = null
  const manifest = new Map<string, ManifestItem>()
  const spine: string[] = []
  walk(doc, {
    start: (e) => {
      switch (e.localName) {
        case 'title':
          if (title === null) title = e.textContent ?? ''
          return true
        case 'meta':
          if (e.getAttribute('name') === 'cover') coverId = e.getAttribute('content')
          break
        case 'item': {
          const id = e.getAttribute('id')
          const href = e.getAttribute('href')
          if (id && href) {
            manifest.set(id, { href, mediaType: e.getAttribute('media-type') ?? '', properties: (e.getAttribute('properties') ?? '').split(' ').filter(Boolean) })
          }
          break
        }
        case 'spine':
          tocId = e.getAttribute('toc')
          break
        case 'itemref': {
          const idref = e.getAttribute('idref')
          if (idref && e.getAttribute('linear') !== 'no') spine.push(idref)
          break
        }
      }
    }
  })
  return { title: title as string | null, manifest, spine, coverId: coverId as string | null, tocId: tocId as string | null }
}

/** EPUB 2: navPoint > navLabel > text, e content@src. O primeiro título de cada arquivo vence. */
export function parseNcx(xml: string, baseDir: string): Map<string, string> {
  const result = new Map<string, string>()
  let label: string | null = null
  walk(parseXml(xml), {
    start: (e) => {
      if (e.localName === 'text') {
        label = (e.textContent ?? '').trim()
        return true
      }
      if (e.localName === 'content') {
        const src = e.getAttribute('src')
        if (src && label) {
          const key = resolveZipPath(baseDir, src)
          if (!result.has(key)) result.set(key, label)
        }
        label = null
      }
    }
  })
  return result
}

/** EPUB 3: links <a href> dentro do <nav epub:type="toc"> (sem epub:type, o primeiro <nav>). */
export function parseNav(xml: string, baseDir: string): Map<string, string> {
  const result = new Map<string, string>()
  const doc = parseXml(xml)
  const navs = Array.from(doc.getElementsByTagNameNS('*', 'nav'))
  const toc = navs.find((n) => {
    const type = attr(n, 'type')
    return type === null || type.split(' ').includes('toc')
  })
  if (!toc) return result
  for (const a of Array.from(toc.getElementsByTagNameNS('*', 'a'))) {
    const href = a.getAttribute('href')
    const label = (a.textContent ?? '').replace(/\s+/g, ' ').trim()
    if (href && label) {
      const key = resolveZipPath(baseDir, href)
      if (!result.has(key)) result.set(key, label)
    }
  }
  return result
}

/** Política dos capítulos: nenhum script, plugin, conexão ou formulário do livro roda. */
export const EPUB_POLICY = "script-src 'none'; object-src 'none'; connect-src 'none'; frame-src 'none'; form-action 'none'"

/** Prepara o HTML do capítulo: política de segurança logo no começo do <head> e o CSS de leitura no fim. Porte de EpubHtml. */
export function prepareEpubHtml(html: string, css: string): string {
  const policy = `<meta http-equiv="Content-Security-Policy" content="${EPUB_POLICY}"/>`
  const style = `<style>${css}</style><meta name="viewport" content="width=device-width, initial-scale=1"/>`
  const open = /<head(\s[^>]*)?>/i.exec(html)
  if (!open) {
    const htmlTag = /<html(\s[^>]*)?>/i.exec(html)
    const at = htmlTag ? htmlTag.index + htmlTag[0].length : 0
    return html.slice(0, at) + `<head>${policy}${style}</head>` + html.slice(at)
  }
  const afterOpen = open.index + open[0].length
  const withPolicy = html.slice(0, afterOpen) + policy + html.slice(afterOpen)
  const close = /<\/head\s*>/i.exec(withPolicy)
  if (!close) return withPolicy + style
  return withPolicy.slice(0, close.index) + style + withPolicy.slice(close.index)
}
