import { bookFile, coverOf, getPref, setCover, setPref, updatePageCount, type LibraryItem } from '../storage/library'

/** Largura das capas guardadas (como o app). */
const COVER_WIDTH = 240

/** Reduz uma imagem para a largura da capa e guarda como JPEG. */
export async function scaleCover(blob: Blob): Promise<Blob | null> {
  try {
    const bitmap = await createImageBitmap(blob)
    const width = Math.min(COVER_WIDTH, bitmap.width)
    const height = Math.max(1, Math.round((bitmap.height * width) / bitmap.width))
    const canvas = new OffscreenCanvas(width, height)
    canvas.getContext('2d')!.drawImage(bitmap, 0, 0, width, height)
    bitmap.close()
    return await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.82 })
  } catch {
    return null
  }
}

/** Geradores de capa por formato (os de PDF, quadrinhos e DJVU chegam com os leitores deles). */
const makers: Record<string, (file: Blob, item: LibraryItem) => Promise<Blob | null>> = {
  epub: async (file, item) => {
    const { openEpub } = await import('../reader/reflowBook')
    const book = await openEpub(file)
    try {
      await updatePageCount(item.id, book.chapterCount)
      return await book.cover()
    } finally {
      book.dispose()
    }
  },
  pdf: async (file, item) => {
    const { pdfCover } = await import('../reader/pdfSource')
    const { cover, pages } = await pdfCover(file)
    await updatePageCount(item.id, pages)
    return cover
  },
  cbz: async (file, item) => comic(file, item, false),
  cbr: async (file, item) => comic(file, item, true),
  djvu: async (file, item) => {
    const { openDjvu } = await import('../reader/djvuSource')
    const source = await openDjvu(file)
    try {
      await updatePageCount(item.id, source.pageCount)
      const { comicCover } = await import('../reader/comicSource')
      return await comicCover({ ...source, render: async (i, w) => bitmapCanvas(await source.render(i, w)) })
    } finally {
      source.close()
    }
  },
  mobi: async (file, item) => {
    const { openMobi } = await import('../reader/reflowBook')
    const book = await openMobi(file)
    try {
      await updatePageCount(item.id, book.chapterCount)
      return await book.cover()
    } finally {
      book.dispose()
    }
  }
}

export function registerCoverMaker(format: string, maker: (file: Blob, item: LibraryItem) => Promise<Blob | null>) {
  makers[format] = maker
}

let running = false

/** Gera, em segundo plano, as capas que faltam (uma tentativa por livro, como o app). */
export async function ensureCovers(items: LibraryItem[]) {
  if (running) return
  running = true
  try {
    const attempted = new Set<number>((await getPref<number[]>('coverAttempted')) ?? [])
    for (const item of items) {
      const make = makers[item.format]
      if (!make || attempted.has(item.id) || item.archivedAt > 0) continue
      attempted.add(item.id)
      await setPref('coverAttempted', [...attempted])
      if (await coverOf(item.id)) continue
      const file = await bookFile(item.id)
      if (!file) continue
      try {
        const raw = await make(file, item)
        const cover = raw ? await scaleCover(raw) : null
        if (cover) await setCover(item.id, cover)
      } catch (e) {
        console.warn('Capa não gerada', item.name, e)
      }
    }
  } finally {
    running = false
  }
}

async function comic(file: Blob, item: LibraryItem, rar: boolean): Promise<Blob | null> {
  const { openCbz, openCbr, comicCover } = await import('../reader/comicSource')
  const source = rar ? await openCbr(file) : await openCbz(file)
  try {
    await updatePageCount(item.id, source.pageCount)
    return await comicCover(source)
  } finally {
    source.close()
  }
}

function bitmapCanvas(drawn: HTMLCanvasElement | ImageBitmap): HTMLCanvasElement {
  if (drawn instanceof HTMLCanvasElement) return drawn
  const canvas = document.createElement('canvas')
  canvas.width = drawn.width
  canvas.height = drawn.height
  canvas.getContext('2d')!.drawImage(drawn, 0, 0)
  return canvas
}

/** Tamanho da capa escolhida pelo leitor: a proporção (2:3) dos livros na Estante. */
export const CUSTOM_COVER = { width: 400, height: 600 }

/**
 * Prepara uma imagem escolhida pelo leitor como capa: endireitada pela orientação da foto (o
 * navegador já aplica o EXIF), cortada no centro em 2:3 e reduzida. null se não der para ler.
 */
export async function customCover(file: Blob): Promise<Blob | null> {
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    return null
  }
  const { width, height } = CUSTOM_COVER
  const scale = Math.max(width / bitmap.width, height / bitmap.height)
  const cropW = Math.min(bitmap.width, width / scale)
  const cropH = Math.min(bitmap.height, height / scale)
  const canvas = new OffscreenCanvas(width, height)
  const context = canvas.getContext('2d')!
  context.fillStyle = '#fff'
  context.fillRect(0, 0, width, height)
  context.imageSmoothingQuality = 'high'
  context.drawImage(bitmap, (bitmap.width - cropW) / 2, (bitmap.height - cropH) / 2, cropW, cropH, 0, 0, width, height)
  bitmap.close()
  return canvas.convertToBlob({ type: 'image/jpeg', quality: 0.86 })
}
