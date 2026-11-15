import { DEFAULT_ASPECT, type PageSource } from './pageSource'

/** DJVU: as páginas são decodificadas num Worker (DjVu.js) e chegam prontas para desenhar. */
export async function openDjvu(file: Blob): Promise<PageSource> {
  const worker = new Worker(new URL('./djvu.worker.ts', import.meta.url), { type: 'module' })
  let next = 0
  const waiting = new Map<number, (data: Record<string, unknown>) => void>()
  worker.onmessage = (e) => {
    waiting.get(e.data.id)?.(e.data)
    waiting.delete(e.data.id)
  }
  const call = (message: object, transfer: Transferable[] = []) =>
    new Promise<Record<string, unknown>>((resolve, reject) => {
      const id = ++next
      waiting.set(id, (data) => (data.error ? reject(new Error(String(data.error))) : resolve(data)))
      worker.postMessage({ id, ...message }, transfer)
    })
  const buffer = await file.arrayBuffer()
  let opened: Record<string, unknown>
  try {
    opened = await call({ type: 'open', buffer }, [buffer])
  } catch (e) {
    worker.terminate()
    throw e
  }
  const first = Number(opened.aspect) || DEFAULT_ASPECT
  const aspects = new Map<number, number>([[0, first]])
  return {
    pageCount: Number(opened.pageCount) || 1,
    aspect: (index) => aspects.get(index) ?? first,
    async render(index, width) {
      const data = await call({ type: 'render', index, width: Math.round(width) })
      aspects.set(index, Number(data.aspect))
      return data.bitmap as ImageBitmap
    },
    async pageText(index) {
      return String((await call({ type: 'text', index })).text ?? '')
    },
    close() {
      worker.terminate()
    }
  }
}
