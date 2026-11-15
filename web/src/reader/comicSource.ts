import { ZipArchive } from '../core/zip'
import { comicPageNames, looksLikeZip } from '../core/library'
import { clampWidth, DEFAULT_ASPECT, type PageSource } from './pageSource'

const TYPES: Record<string, string> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp' }
const typeOf = (name: string) => TYPES[name.slice(name.lastIndexOf('.') + 1).toLowerCase()] ?? 'application/octet-stream'

/** Quadrinho a partir de uma lista de páginas (nome → como ler os bytes). */
function imageSource(names: string[], load: (name: string) => Promise<Blob>, close: () => void): PageSource {
  if (!names.length) throw new Error('Nenhuma imagem neste quadrinho.')
  const aspects = new Map<number, number>()
  let first = DEFAULT_ASPECT
  const bitmaps = new Map<number, ImageBitmap>()
  const bitmap = async (index: number) => {
    let b = bitmaps.get(index)
    if (!b) {
      b = await createImageBitmap(await load(names[index]))
      aspects.set(index, b.height / b.width)
      if (index === 0) first = b.height / b.width
      bitmaps.set(index, b)
      // Guarda só as páginas perto da atual.
      for (const key of bitmaps.keys()) if (Math.abs(key - index) > 3) {
        bitmaps.get(key)?.close()
        bitmaps.delete(key)
      }
    }
    return b
  }
  return {
    pageCount: names.length,
    aspect: (index) => aspects.get(index) ?? first,
    async render(index, width) {
      const b = await bitmap(index)
      const w = clampWidth(Math.min(width, b.width * 2), b.height / b.width)
      const canvas = document.createElement('canvas')
      canvas.width = w
      canvas.height = Math.max(1, Math.round((w * b.height) / b.width))
      const context = canvas.getContext('2d')!
      context.imageSmoothingQuality = 'high'
      context.drawImage(b, 0, 0, canvas.width, canvas.height)
      return canvas
    },
    close() {
      bitmaps.forEach((b) => b.close())
      bitmaps.clear()
      close()
    }
  }
}

export async function openCbz(file: Blob): Promise<PageSource> {
  const zip = await ZipArchive.open(file)
  const names = comicPageNames(zip.names())
  return imageSource(names, async (name) => new Blob([await zip.bytes(name)], { type: typeOf(name) }), () => {})
}

/** CBR: RAR 4/5 pelo libarchive.js (WebAssembly); CBR que é ZIP por dentro abre como CBZ. */
export async function openCbr(file: Blob): Promise<PageSource> {
  if (looksLikeZip(new Uint8Array(await file.slice(0, 4).arrayBuffer()))) return openCbz(file)
  const { Archive } = await import('libarchive.js')
  Archive.init({ workerUrl: `${import.meta.env.BASE_URL}vendor/libarchive/worker-bundle.js` })
  const archive = await Archive.open(file instanceof File ? file : new File([file], 'livro.cbr'))
  const entries = (await archive.getFilesArray()) as { file: { name: string; extract: () => Promise<File> }; path: string }[]
  const byName = new Map(entries.map((e) => [`${e.path}${e.file.name}`, e.file]))
  const names = comicPageNames([...byName.keys()])
  return imageSource(
    names,
    async (name) => {
      const extracted = await byName.get(name)!.extract()
      return new Blob([extracted], { type: typeOf(name) })
    },
    () => {
      archive.close?.()
    }
  )
}

/** Capa: a primeira página, pequena. */
export async function comicCover(source: PageSource): Promise<Blob | null> {
  const canvas = (await source.render(0, 240)) as HTMLCanvasElement
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.82))
}
