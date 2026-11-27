/*
 * Streaming de vídeo do Alexandria (carregado pelo service worker do site). O <video> pede trechos
 * (cabeçalho Range) de /__stream/<código>; este código pergunta à página aberta, que busca os
 * pedaços no Telegram, e devolve uma resposta 206 com o trecho. Nada sai do navegador por aqui.
 */
const CHUNK = 1024 * 1024

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin || !url.pathname.includes('/__stream/')) return
  event.respondWith(streamResponse(event, url))
})

async function pageFor(event) {
  const own = event.clientId ? await self.clients.get(event.clientId) : null
  if (own) return own
  const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
  return all.find((c) => c.focused) || all[0] || null
}

function ask(client, message) {
  return new Promise((resolve, reject) => {
    const channel = new MessageChannel()
    const timer = setTimeout(() => reject(new Error('A página não respondeu.')), 90000)
    channel.port1.onmessage = (e) => {
      clearTimeout(timer)
      resolve(e.data)
    }
    client.postMessage(message, [channel.port2])
  })
}

async function streamResponse(event, url) {
  const token = decodeURIComponent(url.pathname.split('/__stream/')[1] || '')
  const client = await pageFor(event)
  if (!client) return new Response('Abra o Alexandria para assistir.', { status: 503 })
  const range = /bytes=(\d*)-(\d*)/.exec(event.request.headers.get('range') || '')
  const start = range && range[1] ? Number(range[1]) : 0
  const askedEnd = range && range[2] ? Number(range[2]) : Infinity
  const end = Math.min(askedEnd, start + CHUNK - 1)
  try {
    const reply = await ask(client, { type: 'alx-stream', token, start, end })
    if (reply.error) return new Response(reply.error, { status: 502 })
    const last = start + reply.bytes.byteLength - 1
    if (start >= reply.size) return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${reply.size}` } })
    return new Response(reply.bytes, {
      status: 206,
      headers: {
        'Content-Type': reply.mime,
        'Content-Length': String(reply.bytes.byteLength),
        'Content-Range': `bytes ${start}-${last}/${reply.size}`,
        'Accept-Ranges': 'bytes',
        'Cache-Control': 'no-store'
      }
    })
  } catch (e) {
    return new Response(String(e && e.message ? e.message : e), { status: 504 })
  }
}
