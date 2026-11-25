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
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${normalized}|${size}`)))
  return Array.from(digest.slice(0, 12), (b) => b.toString(16).padStart(2, '0')).join('')
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
