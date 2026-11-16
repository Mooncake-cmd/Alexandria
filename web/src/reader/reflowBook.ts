import { unzlibSync } from 'fflate'
import { ZipArchive } from '../core/zip'
import { readEpub } from '../core/epub'
import { dirOf, resolveZipPath } from '../core/library'

/**
 * Livro de texto que se ajusta à tela (EPUB, ou MOBI/AZW3 lido pelo foliate-js): capítulos que
 * viram documentos prontos para a moldura do leitor, com imagens, estilos e fontes do próprio livro.
 */
export interface ReflowBook {
  title: string | null
  chapterCount: number
  chapterTitles: (string | null)[]
  /** Documento do capítulo, já sem scripts e com os arquivos do livro em endereços locais (blob:). */
  chapter(index: number): Promise<{ html: string; type: 'application/xhtml+xml' | 'text/html' }>
  /** Link interno: capítulo (-1 se o arquivo está fora da ordem de leitura) e o ponto dele. */
  resolveLink(from: number, href: string): Promise<LinkTarget | null>
  /** Documento (sem scripts) do arquivo de um link, para ler uma nota sem abrir o capítulo. */
  linkedDocument(target: LinkTarget): Promise<Document | null>
  cover(): Promise<Blob | null>
  dispose(): void
}

export interface LinkTarget {
  index: number
  fragment: string | null
  /** O elemento de destino num documento do arquivo (null = começo do arquivo). */
  anchor: (doc: Document) => Element | null
  path?: string
}

const decodeFragment = (value: string) => {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

const MIME: Record<string, string> = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml', bmp: 'image/bmp',
  css: 'text/css', ttf: 'font/ttf', otf: 'font/otf', woff: 'font/woff', woff2: 'font/woff2', mp3: 'audio/mpeg', mp4: 'video/mp4'
}
const mimeOf = (path: string) => MIME[path.slice(path.lastIndexOf('.') + 1).toLowerCase()] ?? 'application/octet-stream'

/** Remove o que pode executar código ou carregar coisas de fora (o site é offline e não roda scripts do livro). */
export function sanitize(doc: Document) {
  for (const el of Array.from(doc.querySelectorAll('script, iframe, object, embed, form, meta[http-equiv="refresh"]'))) el.remove()
  for (const el of Array.from(doc.getElementsByTagName('*'))) {
    for (const attr of Array.from(el.attributes)) {
      if (/^on/i.test(attr.name)) el.removeAttribute(attr.name)
      else if (/^\s*javascript:/i.test(attr.value)) el.removeAttribute(attr.name)
    }
  }
}

export function parseChapter(text: string): { doc: Document; xml: boolean } {
  const xml = new DOMParser().parseFromString(text, 'application/xhtml+xml')
  if (!xml.getElementsByTagName('parsererror').length && xml.documentElement) return { doc: xml, xml: true }
  return { doc: new DOMParser().parseFromString(text, 'text/html'), xml: false }
}

export function serialize(doc: Document, xml: boolean): string {
  return xml ? new XMLSerializer().serializeToString(doc) : '<!DOCTYPE html>\n' + doc.documentElement.outerHTML
}

const XLINK = 'http://www.w3.org/1999/xlink'

export async function openEpub(file: Blob): Promise<ReflowBook> {
  const zip = await ZipArchive.open(file)
  const book = await readEpub(zip)
  const urls = new Map<string, string>()
  const blobUrl = async (path: string): Promise<string | null> => {
    if (!zip.has(path)) return null
    let url = urls.get(path)
    if (!url) {
      let bytes: BlobPart = await zip.bytes(path)
      if (path.toLowerCase().endsWith('.css')) bytes = await rewriteCss(new TextDecoder().decode(bytes as Uint8Array), dirOf(path))
      url = URL.createObjectURL(new Blob([bytes], { type: mimeOf(path) }))
      urls.set(path, url)
    }
    return url
  }
  // url(...) dentro de CSS aponta para arquivos do livro (imagens, fontes).
  const rewriteCss = async (css: string, base: string): Promise<string> => {
    const found = [...css.matchAll(/url\(\s*(['"]?)([^'")]+)\1\s*\)/g)]
    let out = css
    for (const m of found) {
      const target = m[2]
      if (/^(data|blob|https?):/i.test(target)) continue
      const url = await blobUrl(resolveZipPath(base, target))
      if (url) out = out.split(m[0]).join(`url("${url}")`)
    }
    return out
  }

  return {
    title: book.title,
    chapterCount: book.chapters.length,
    chapterTitles: book.chapterTitles,
    async chapter(index) {
      const path = book.chapters[index]
      const base = dirOf(path)
      const { doc, xml } = parseChapter(await zip.text(path))
      sanitize(doc)
      const resolve = (href: string) => resolveZipPath(base, href)
      for (const el of Array.from(doc.querySelectorAll('img[src], source[src], video[src], audio[src], input[src]'))) {
        const src = el.getAttribute('src')!
        if (/^(data|blob):/i.test(src)) continue
        const url = await blobUrl(resolve(src))
        if (url) el.setAttribute('src', url)
        else el.removeAttribute('src')
      }
      for (const el of Array.from(doc.getElementsByTagNameNS('*', 'image'))) {
        const href = el.getAttributeNS(XLINK, 'href') ?? el.getAttribute('href')
        if (!href || /^(data|blob):/i.test(href)) continue
        const url = await blobUrl(resolve(href))
        if (url) {
          el.setAttributeNS(XLINK, 'xlink:href', url)
          el.setAttribute('href', url)
        }
      }
      for (const link of Array.from(doc.querySelectorAll('link[href]'))) {
        const rel = (link.getAttribute('rel') ?? '').toLowerCase()
        if (!rel.includes('stylesheet')) {
          link.remove()
          continue
        }
        const url = await blobUrl(resolve(link.getAttribute('href')!))
        if (url) link.setAttribute('href', url)
        else link.remove()
      }
      for (const style of Array.from(doc.querySelectorAll('style'))) style.textContent = await rewriteCss(style.textContent ?? '', base)
      for (const el of Array.from(doc.querySelectorAll('[style*="url("]'))) el.setAttribute('style', await rewriteCss(el.getAttribute('style')!, base))
      return { html: serialize(doc, xml), type: xml ? 'application/xhtml+xml' : 'text/html' }
    },
    async resolveLink(from, href) {
      if (/^[a-z][a-z0-9+.-]*:/i.test(href.trim())) return null // link externo: o site é offline, ignora
      const hash = href.indexOf('#')
      const pathPart = hash >= 0 ? href.slice(0, hash) : href
      const fragment = hash >= 0 && hash < href.length - 1 ? decodeFragment(href.slice(hash + 1)) : null
      const path = pathPart.trim() ? resolveZipPath(dirOf(book.chapters[from]), pathPart) : book.chapters[from]
      if (!zip.has(path)) return null
      return { index: book.chapters.indexOf(path), fragment, path, anchor: (doc) => (fragment ? doc.getElementById(fragment) : null) }
    },
    async linkedDocument(target) {
      if (!target.path || !zip.has(target.path)) return null
      const { doc } = parseChapter(await zip.text(target.path))
      sanitize(doc)
      return doc
    },
    async cover() {
      if (!book.coverPath || !zip.has(book.coverPath)) return null
      return new Blob([await zip.bytes(book.coverPath)], { type: mimeOf(book.coverPath) })
    },
    dispose() {
      urls.forEach((u) => URL.revokeObjectURL(u))
      urls.clear()
    }
  }
}

/** MOBI, AZW e AZW3 (sem DRM), pelo leitor do foliate-js (MIT). */
export async function openMobi(file: Blob): Promise<ReflowBook> {
  const { MOBI, isMOBI } = (await import('foliate-js/mobi.js')) as typeof import('foliate-js/mobi.js')
  if (!(await isMOBI(file))) throw new Error('Este arquivo não é um MOBI/AZW3 válido.')
  const book = await new MOBI({ unzlib: unzlibSync }).open(file)
  const sections = book.sections as { load: () => Promise<string>; linear?: string }[]
  const titles: (string | null)[] = sections.map(() => null)
  const toc = (book.toc ?? []) as { label: string; href: string; subitems?: unknown[] }[]
  for (const entry of flatten(toc)) {
    try {
      const resolved = await book.resolveHref(entry.href)
      if (resolved && titles[resolved.index] == null) titles[resolved.index] = entry.label.trim() || null
    } catch {
      // item do sumário quebrado: ignora
    }
  }
  const loadChapter = async (index: number): Promise<{ html: string; type: 'application/xhtml+xml' | 'text/html' }> => {
    const url = await sections[index].load()
    const text = await (await fetch(url)).text()
    const { doc, xml } = parseChapter(text)
    sanitize(doc)
    return { html: serialize(doc, xml), type: xml ? 'application/xhtml+xml' : 'text/html' }
  }
  return {
    title: (book.metadata?.title as string | undefined)?.trim() || null,
    chapterCount: sections.length,
    chapterTitles: titles,
    chapter: loadChapter,
    async resolveLink(_from, href) {
      try {
        const resolved = (await book.resolveHref(href)) as { index: number; anchor?: (doc: Document) => Element | null } | undefined
        if (!resolved || resolved.index < 0) return null
        return { index: resolved.index, fragment: null, anchor: (doc) => resolved.anchor?.(doc) ?? null }
      } catch {
        return null
      }
    },
    async linkedDocument(target) {
      if (target.index < 0) return null
      const { html, type } = await loadChapter(target.index)
      return new DOMParser().parseFromString(html, type)
    },
    async cover() {
      try {
        return ((await book.getCover?.()) as Blob | null) ?? null
      } catch {
        return null
      }
    },
    dispose() {
      book.destroy?.()
    }
  }
}

function flatten(items: { label: string; href: string; subitems?: unknown[] }[]): { label: string; href: string }[] {
  return items.flatMap((i) => [i, ...flatten((i.subitems ?? []) as { label: string; href: string; subitems?: unknown[] }[])])
}
