import { getPref, setPref } from './library'

/**
 * Sincronização da posição de leitura entre aparelhos (porte de PositionSync do app): um gist
 * secreto na conta do GitHub da própria pessoa guarda, por livro, a posição mais recente. O livro é
 * reconhecido pelo nome e tamanho do arquivo; no gist fica só um código tirado dos dois.
 */
export interface SyncEntry {
  format: string
  position: number
  exact: number
  pageCount: number
  updated: number
  device: string
}

export interface SyncSettings {
  token: string
  gistId: string
  login: string
  lastSync: number
  error?: string | null
}

export const SYNC_FILE = 'alexandria-posicoes.json'
export const SYNC_DESCRIPTION = 'Alexandria: posições de leitura (sincronização entre aparelhos)'
export const DEVICE = 'computador'
const API = 'https://api.github.com'

export async function bookKey(name: string, size: number): Promise<string> {
  const normalized = name.trim().normalize('NFC').toLowerCase().replace(/\s+/g, ' ')
  const data = new TextEncoder().encode(`${normalized}|${size}`)
  // crypto.subtle só existe em páginas seguras (https); fora delas, a mesma conta em JavaScript.
  const digest = globalThis.crypto?.subtle ? new Uint8Array(await crypto.subtle.digest('SHA-256', data)) : sha256(data)
  return Array.from(digest.slice(0, 12), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** SHA-256 (FIPS 180-4) para quando o navegador não oferece crypto.subtle. */
export function sha256(data: Uint8Array): Uint8Array {
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ])
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19])
  const length = data.length
  const padded = new Uint8Array(Math.ceil((length + 9) / 64) * 64)
  padded.set(data)
  padded[length] = 0x80
  const view = new DataView(padded.buffer)
  view.setUint32(padded.length - 8, Math.floor((length * 8) / 2 ** 32))
  view.setUint32(padded.length - 4, (length * 8) >>> 0)
  const w = new Uint32Array(64)
  const rotr = (x: number, n: number) => (x >>> n) | (x << (32 - n))
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4)
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0
    }
    let [a, b, c, d, e, f, g, hh] = h
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) >>> 0
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0
      hh = g
      g = f
      f = e
      e = (d + t1) >>> 0
      d = c
      c = b
      b = a
      a = (t1 + t2) >>> 0
    }
    h[0] = (h[0] + a) >>> 0
    h[1] = (h[1] + b) >>> 0
    h[2] = (h[2] + c) >>> 0
    h[3] = (h[3] + d) >>> 0
    h[4] = (h[4] + e) >>> 0
    h[5] = (h[5] + f) >>> 0
    h[6] = (h[6] + g) >>> 0
    h[7] = (h[7] + hh) >>> 0
  }
  const out = new Uint8Array(32)
  const outView = new DataView(out.buffer)
  h.forEach((v, i) => outView.setUint32(i * 4, v))
  return out
}

export function parseSync(json: string | null | undefined): Record<string, SyncEntry> {
  if (!json) return {}
  try {
    const books = (JSON.parse(json) as { books?: Record<string, Partial<SyncEntry>> }).books ?? {}
    const out: Record<string, SyncEntry> = {}
    for (const [key, b] of Object.entries(books)) {
      out[key] = { format: b.format ?? '', position: b.position ?? 0, exact: b.exact ?? -1, pageCount: b.pageCount ?? 0, updated: b.updated ?? 0, device: b.device ?? '' }
    }
    return out
  } catch {
    return {}
  }
}

export function serializeSync(books: Record<string, SyncEntry>): string {
  const sorted: Record<string, SyncEntry> = {}
  for (const key of Object.keys(books).sort()) sorted[key] = books[key]
  return JSON.stringify({ format: 'alexandria-sync', version: 1, books: sorted }, null, 2)
}

/** Vale a posição mais recente de cada livro. Devolve se mudou algo. */
export function mergeSync(books: Record<string, SyncEntry>, key: string, entry: SyncEntry): boolean {
  const old = books[key]
  if (old && old.updated >= entry.updated) return false
  books[key] = entry
  return true
}

/** A posição do outro aparelho deve ser aplicada ao abrir? Só se for mais recente e outro ponto. */
export function shouldApply(remote: SyncEntry | null | undefined, localUpdated: number, localExact: number, span: number, device = DEVICE): boolean {
  // O nome do aparelho não decide nada (dois computadores podem ter o mesmo): vale a hora e o ponto.
  void device
  if (!remote || remote.updated <= localUpdated) return false
  if (remote.exact < 0) return remote.position >= 0
  return Math.abs(remote.exact - localExact) >= Math.max(1, span / 2)
}

export class SyncError extends Error {
  constructor(readonly status: number) {
    super(`GitHub respondeu ${status}`)
  }
}

/** Cliente da API de gists. `fetcher` é trocado nos testes. */
export class GistClient {
  constructor(private readonly token: string, private readonly fetcher: typeof fetch = (...args) => fetch(...args)) {}

  private async call<T>(method: string, path: string, body?: unknown, keepalive = false): Promise<T> {
    const response = await this.fetcher(`${API}${path}`, {
      method,
      keepalive,
      headers: {
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        Authorization: `Bearer ${this.token}`,
        ...(body ? { 'Content-Type': 'application/json' } : {})
      },
      body: body ? JSON.stringify(body) : undefined
    })
    if (!response.ok) throw new SyncError(response.status)
    return (await response.json()) as T
  }

  async login(): Promise<string> {
    return (await this.call<{ login: string }>('GET', '/user')).login
  }

  async findOrCreate(): Promise<string> {
    for (let page = 1; page <= 5; page++) {
      const list = await this.call<{ id: string; files: Record<string, unknown> }[]>('GET', `/gists?per_page=100&page=${page}`)
      const found = list.find((g) => g.files && SYNC_FILE in g.files)
      if (found) return found.id
      if (list.length < 100) break
    }
    const created = await this.call<{ id: string }>('POST', '/gists', { description: SYNC_DESCRIPTION, public: false, files: { [SYNC_FILE]: { content: serializeSync({}) } } })
    return created.id
  }

  async read(id: string): Promise<Record<string, SyncEntry>> {
    const gist = await this.call<{ files: Record<string, { content?: string }> }>('GET', `/gists/${id}`)
    return parseSync(gist.files?.[SYNC_FILE]?.content)
  }

  async write(id: string, books: Record<string, SyncEntry>, keepalive = false) {
    await this.call('PATCH', `/gists/${id}`, { files: { [SYNC_FILE]: { content: serializeSync(books) } } }, keepalive)
  }

  /** Lê, junta a posição deste aparelho e grava (só se ela for a mais recente). */
  async push(id: string, key: string, entry: SyncEntry, keepalive = false) {
    const books = await this.read(id)
    if (mergeSync(books, key, entry)) await this.write(id, books, keepalive)
  }
}

/** Troca do cliente nos testes. */
export let clientFor = (token: string) => new GistClient(token)
export function setClientFactory(factory: (token: string) => GistClient) {
  clientFor = factory
}

export const syncSettings = () => getPref<SyncSettings>('sync')

export async function connectSync(token: string): Promise<string> {
  const client = clientFor(token.trim())
  const login = await client.login()
  const gistId = await client.findOrCreate()
  await setPref('sync', { token: token.trim(), gistId, login, lastSync: Date.now(), error: null } satisfies SyncSettings)
  return login
}

export const disconnectSync = () => setPref('sync', null)

async function remember(settings: SyncSettings, error: unknown) {
  const status = error instanceof SyncError ? error.status : 0
  // Sem internet não é erro de configuração; token recusado é.
  const message = status === 404 ? 'O gist das posições não foi encontrado.' : status === 401 || status === 403 ? 'O GitHub recusou o token (vencido ou sem permissão de gists).' : null
  await setPref('sync', { ...settings, lastSync: error ? settings.lastSync : Date.now(), error: error ? message ?? settings.error ?? null : null })
}

/** Posição deste livro no gist (null sem sincronização, sem internet ou sem registro). */
export async function pullPosition(name: string, size: number): Promise<SyncEntry | null> {
  const settings = await syncSettings()
  if (!settings?.token || size <= 0) return null
  try {
    const books = await clientFor(settings.token).read(settings.gistId)
    await remember(settings, null)
    return books[await bookKey(name, size)] ?? null
  } catch (e) {
    await remember(settings, e)
    return null
  }
}

/** Grava a posição deste livro (erros de rede são ignorados). */
export async function pushPosition(name: string, size: number, entry: SyncEntry, keepalive = false) {
  const settings = await syncSettings()
  if (!settings?.token || size <= 0) return
  try {
    await clientFor(settings.token).push(settings.gistId, await bookKey(name, size), entry, keepalive)
    await remember(settings, null)
  } catch (e) {
    await remember(settings, e)
  }
}
