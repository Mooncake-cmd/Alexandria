/// <reference lib="webworker" />
// DJVU num Worker, pelo DjVu.js (GPL-2.0-or-later, em vendor/djvujs): decodificar uma página
// grande leva tempo, e assim a tela não trava.
// @ts-expect-error biblioteca em JavaScript, sem tipos
import DjVuDocument from '../../vendor/djvujs/src/DjVuDocument'

interface Page {
  getWidth(): number
  getHeight(): number
  getImageData(rotate?: boolean): ImageData
  getRotation?(): number
  getText?(): string
  reset?(): void
}
interface Doc {
  getPagesQuantity(): number
  getPage(n: number): Promise<Page>
}

let doc: Doc | null = null

self.onmessage = async (event: MessageEvent) => {
  const { id, type } = event.data
  try {
    if (type === 'open') {
      doc = new DjVuDocument(event.data.buffer) as Doc
      const first = await doc.getPage(1)
      post(id, { pageCount: doc.getPagesQuantity(), aspect: first.getHeight() / first.getWidth() })
    } else if (type === 'render') {
      const page = await doc!.getPage(event.data.index + 1)
      const image = page.getImageData(true)
      const canvas = new OffscreenCanvas(image.width, image.height)
      canvas.getContext('2d')!.putImageData(image, 0, 0)
      const width = Math.max(1, Math.min(event.data.width, image.width * 2))
      const bitmap = await createImageBitmap(canvas, { resizeWidth: width, resizeHeight: Math.max(1, Math.round((width * image.height) / image.width)), resizeQuality: 'high' })
      page.reset?.()
      post(id, { bitmap, aspect: image.height / image.width }, [bitmap])
    } else if (type === 'text') {
      const page = await doc!.getPage(event.data.index + 1)
      post(id, { text: page.getText?.() ?? '' })
    }
  } catch (e) {
    post(id, { error: e instanceof Error ? e.message : String(e) })
  }
}

function post(id: number, data: object, transfer: Transferable[] = []) {
  ;(self as unknown as DedicatedWorkerGlobalScope).postMessage({ id, ...data }, transfer)
}
