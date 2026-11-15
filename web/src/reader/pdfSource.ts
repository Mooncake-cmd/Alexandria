import * as pdfjs from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { clampWidth, DEFAULT_ASPECT, type PageSource } from './pageSource'

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl

const vendor = `${import.meta.env.BASE_URL}vendor/pdfjs/`

/** PDF pelo pdf.js (Mozilla, Apache-2.0): desenho das páginas e texto selecionável. */
export async function openPdf(file: Blob, password?: string): Promise<PageSource> {
  const data = new Uint8Array(await file.arrayBuffer())
  const task = pdfjs.getDocument({
    data,
    password,
    // Fontes padrão (inclusive Liberation, para PDFs sem fontes embutidas), mapas e decodificadores locais.
    standardFontDataUrl: `${vendor}standard_fonts/`,
    cMapUrl: `${vendor}cmaps/`,
    cMapPacked: true,
    wasmUrl: `${vendor}wasm/`,
    enableXfa: false
  })
  const doc = await task.promise
  const aspects = new Map<number, number>()
  const pages = new Map<number, pdfjs.PDFPageProxy>()
  const page = async (index: number) => {
    let p = pages.get(index)
    if (!p) {
      p = await doc.getPage(index + 1)
      pages.set(index, p)
      const viewport = p.getViewport({ scale: 1 })
      aspects.set(index, viewport.height / viewport.width)
    }
    return p
  }
  // A primeira página dá a proporção padrão das outras (quase sempre iguais).
  await page(0)
  const first = aspects.get(0) ?? DEFAULT_ASPECT
  return {
    pageCount: doc.numPages,
    aspect: (index) => aspects.get(index) ?? first,
    async render(index, width) {
      const p = await page(index)
      const base = p.getViewport({ scale: 1 })
      const w = clampWidth(width, base.height / base.width)
      const viewport = p.getViewport({ scale: w / base.width })
      const canvas = document.createElement('canvas')
      canvas.width = Math.round(viewport.width)
      canvas.height = Math.round(viewport.height)
      const context = canvas.getContext('2d')!
      context.fillStyle = '#fff'
      context.fillRect(0, 0, canvas.width, canvas.height)
      await p.render({ canvasContext: context, canvas, viewport }).promise
      return canvas
    },
    async textLayer(index, container, width) {
      const p = await page(index)
      const base = p.getViewport({ scale: 1 })
      const viewport = p.getViewport({ scale: width / base.width })
      container.replaceChildren()
      container.style.setProperty('--total-scale-factor', String(viewport.scale))
      container.style.setProperty('--scale-factor', String(viewport.scale))
      await new pdfjs.TextLayer({ textContentSource: p.streamTextContent(), container, viewport }).render()
    },
    async pageText(index) {
      const content = await (await page(index)).getTextContent()
      return content.items.map((i) => ('str' in i ? i.str + (i.hasEOL ? '\n' : '') : '')).join('')
    },
    close() {
      task.destroy()
    }
  }
}

export const isPasswordError = (e: unknown) => e instanceof Error && e.name === 'PasswordException'

/** Capa: a primeira página, pequena. */
export async function pdfCover(file: Blob): Promise<{ cover: Blob | null; pages: number }> {
  const source = await openPdf(file)
  try {
    const canvas = (await source.render(0, 240)) as HTMLCanvasElement
    const cover = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.82))
    return { cover, pages: source.pageCount }
  } finally {
    source.close()
  }
}
