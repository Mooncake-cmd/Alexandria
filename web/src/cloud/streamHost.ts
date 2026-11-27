/**
 * Lado da página do streaming: o service worker (stream-sw.js) pede trechos de /__stream/<código>
 * e esta página responde com os bytes, lidos do Telegram por partes.
 */
import type { VideoSource } from './gramApi'

const streams = new Map<string, VideoSource>()
let listening = false

/** O <video> pode tocar daqui? (o service worker precisa estar controlando a página) */
export const streamingReady = () => typeof navigator !== 'undefined' && !!navigator.serviceWorker?.controller

function listen() {
  if (listening || !navigator.serviceWorker) return
  listening = true
  navigator.serviceWorker.addEventListener('message', async (event: MessageEvent) => {
    const data = event.data as { type?: string; token?: string; start?: number; end?: number }
    if (data?.type !== 'alx-stream' || !event.ports[0]) return
    const port = event.ports[0]
    const source = streams.get(data.token ?? '')
    if (!source) return port.postMessage({ error: 'Vídeo fechado.' })
    try {
      const start = data.start ?? 0
      const bytes = await source.reader.read(start, (data.end ?? start) - start + 1)
      // Cópia própria: o bloco guardado na memória continua inteiro.
      const copy = bytes.slice()
      port.postMessage({ bytes: copy.buffer, size: source.size, mime: playableMime(source) }, [copy.buffer])
    } catch (e) {
      const { telegramError } = await import('./gramApi')
      port.postMessage({ error: telegramError(e) })
    }
  })
}

/** O Chrome toca MKV com H.264/AAC quando chamado de WebM; o tipo real fica para a mensagem de erro. */
function playableMime(source: VideoSource): string {
  if (/matroska|x-mkv/.test(source.mime) || /\.mkv$/i.test(source.name)) return 'video/webm'
  return source.mime || 'video/mp4'
}

/** Endereço que o <video> usa para tocar este vídeo. */
export function streamUrl(source: VideoSource, key: string): string {
  listen()
  const token = `${key}-${source.size}`
  streams.set(token, source)
  return `${import.meta.env.BASE_URL}__stream/${encodeURIComponent(token)}`
}

export function closeStream(source: VideoSource, key: string) {
  streams.delete(`${key}-${source.size}`)
}
