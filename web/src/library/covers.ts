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
