/**
 * Nuvem do site: escolhe por onde vai a posição de leitura (GitHub ou Telegram) e cuida dos livros
 * na nuvem do Telegram (Mensagens salvas ou um grupo privado). A ligação com o Telegram (GramJS,
 * ~1 MB) só é carregada quando usada.
 */
import { getPref, setPref, addBook, bookFile, type LibraryItem } from '../storage/library'
import { bookKey, DEVICE, pullPosition as pullFromGitHub, pushPosition as pushToGitHub, syncSettings, type SyncEntry } from '../storage/sync'
import { copyCloud, downloadBook, listCloudBooks, pullPositionFrom, pushPositionTo, uploadBook, removeCloudBook, type CloudBook, type SponsoredItem, type TgApi } from './telegramCloud'
import type { CloudChat, CloudTarget } from './gramApi'

export interface TelegramSettings {
  apiId: number
  apiHash: string
  session: string
  name: string
  error?: string | null
  /** Grupo privado onde fica a nuvem; sem ele, as Mensagens salvas. */
  chat?: CloudChat | null
}

export type SyncProvider = 'github' | 'telegram'

export const telegramSettings = () => getPref<TelegramSettings>('telegram')
export const saveTelegram = (s: TelegramSettings) => setPref('telegram', s)

/** Por onde vai a posição: o escolhido em Ajustes, ou o único configurado. */
export async function syncProvider(): Promise<SyncProvider | null> {
  const [chosen, telegram, github] = await Promise.all([getPref<SyncProvider>('syncProvider'), telegramSettings(), syncSettings()])
  if (chosen === 'telegram' && telegram?.session) return 'telegram'
  if (chosen === 'github' && github?.token) return 'github'
  return telegram?.session ? 'telegram' : github?.token ? 'github' : null
}
export const setSyncProvider = (p: SyncProvider) => setPref('syncProvider', p)

/** Troca da ligação com o Telegram nos testes (recebe a conversa: 'me' ou o grupo). */
let apiFactory: ((s: TelegramSettings, target: CloudTarget) => Promise<TgApi>) | null = null
export function setTelegramApiFactory(factory: ((s: TelegramSettings, target: CloudTarget) => Promise<TgApi>) | null) {
  apiFactory = factory
}

const targetOf = (s: TelegramSettings): CloudTarget => s.chat ?? 'me'

async function telegramApi(target?: CloudTarget): Promise<TgApi | null> {
  const s = await telegramSettings()
  if (!s?.session) return null
  if (apiFactory) return apiFactory(s, target ?? targetOf(s))
  const { connectedClient, gramApi } = await import('./gramApi')
  return gramApi(await connectedClient({ apiId: s.apiId, apiHash: s.apiHash }, s.session), target ?? targetOf(s))
}

async function client() {
  const s = await telegramSettings()
  if (!s?.session) throw new Error('Entre no Telegram em Ajustes para usar a nuvem.')
  const gram = await import('./gramApi')
  return { s, gram, client: await gram.connectedClient({ apiId: s.apiId, apiHash: s.apiHash }, s.session) }
}

// ---------- onde fica a nuvem: Mensagens salvas ou grupo privado ----------

export async function useCloudChat(chat: CloudChat | null) {
  const s = await telegramSettings()
  if (s) await saveTelegram({ ...s, chat, error: null })
  sponsoredCache = null
}

export async function createCloudGroup(): Promise<CloudChat> {
  const { gram, client: c } = await client()
  const chat = await gram.createGroup(c, 'Alexandria — biblioteca')
  await useCloudChat(chat)
  return chat
}

export async function joinCloudGroup(link: string): Promise<CloudChat> {
  const { gram, client: c } = await client()
  const chat = await gram.joinGroup(c, link)
  await useCloudChat(chat)
  return chat
}

export async function myGroups(): Promise<CloudChat[]> {
  const { gram, client: c } = await client()
  return gram.listGroups(c)
}

export async function cloudInviteLink(): Promise<string> {
  const { s, gram, client: c } = await client()
  if (!s.chat) throw new Error('A nuvem está nas Mensagens salvas: não há grupo para convidar.')
  return gram.inviteLink(c, s.chat)
}

/** Copia para o grupo os livros e posições que estão nas Mensagens salvas. */
export function copySavedToGroup(): Promise<{ books: number; positions: boolean }> {
  return queued(async () => {
    const { s, gram, client: c } = await client()
    if (!s.chat) throw new Error('Escolha um grupo antes.')
    const chat = s.chat
    const [from, to] = await Promise.all([telegramApi('me'), telegramApi(chat)])
    return copyCloud(from!, to!, (ids) => gram.forwardFromSaved(c, chat, ids))
  })
}

// ---------- mensagens patrocinadas (regra 3.3) ----------

/** A documentação pede para guardar o resultado por 5 minutos. */
let sponsoredCache: { at: number; key: string; items: SponsoredItem[] } | null = null

export async function cloudSponsored(): Promise<SponsoredItem[]> {
  const s = await telegramSettings()
  if (!s?.session || !s.chat || apiFactory) return []
  const key = s.chat.id
  if (sponsoredCache && sponsoredCache.key === key && Date.now() - sponsoredCache.at < 5 * 60_000) return sponsoredCache.items
  try {
    const { gram, client: c } = await client()
    const items = await gram.sponsoredMessages(c, s.chat)
    sponsoredCache = { at: Date.now(), key, items }
    return items
  } catch {
    return []
  }
}

export async function sponsoredSeen(item: SponsoredItem) {
  const s = await telegramSettings()
  if (!s?.chat) return
  const { gram, client: c } = await client()
  await gram.viewSponsored(c, s.chat, item.id).catch(() => {})
}

export async function sponsoredClicked(item: SponsoredItem) {
  const s = await telegramSettings()
  if (!s?.chat) return
  const { gram, client: c } = await client()
  await gram.clickSponsored(c, s.chat, item.id).catch(() => {})
}

/** Erros que mostram que a sessão acabou (ex.: encerrada no Telegram, em "Dispositivos"). */
async function rememberTelegramError(e: unknown) {
  const code = (e as { errorMessage?: string })?.errorMessage ?? ''
  const s = await telegramSettings()
  if (!s) return
  if (/AUTH_KEY_UNREGISTERED|SESSION_REVOKED|USER_DEACTIVATED|AUTH_KEY_INVALID/.test(code)) await saveTelegram({ ...s, error: 'A sessão do Telegram foi encerrada. Entre de novo em Ajustes.' })
  else if (s.chat && /CHANNEL_PRIVATE|CHANNEL_INVALID|PEER_ID_INVALID/.test(code)) await saveTelegram({ ...s, error: 'O grupo da nuvem não está acessível para esta conta (saiu do grupo ou ele foi apagado). Escolha outro lugar em Ajustes.' })
}

// ---------- posição de leitura ----------

/** Pedidos de posição não podem ficar presos: sem resposta em 60 s, a conexão é refeita na próxima vez. */
function limited<T>(work: Promise<T>): Promise<T> {
  let timer: ReturnType<typeof setTimeout>
  return Promise.race([
    work,
    new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        import('./gramApi').then((g) => g.dropConnection()).catch(() => {})
        reject(new Error('O Telegram não respondeu a tempo.'))
      }, 60_000)
    })
  ]).finally(() => clearTimeout(timer))
}

export async function pullPosition(name: string, size: number): Promise<SyncEntry | null> {
  const provider = await syncProvider()
  if (provider === 'github') return pullFromGitHub(name, size)
  if (provider !== 'telegram' || size <= 0) return null
  try {
    const api = await telegramApi()
    return api ? await limited(pullPositionFrom(api, await bookKey(name, size))) : null
  } catch (e) {
    await rememberTelegramError(e)
    return null
  }
}

/** Fila: uma gravação de posição por vez (o Telegram não gosta de rajadas). */
let positionQueue: Promise<unknown> = Promise.resolve()

export function pushPosition(name: string, size: number, entry: SyncEntry, keepalive = false): Promise<void> {
  const run = async () => {
    const provider = await syncProvider()
    if (provider === 'github') return pushToGitHub(name, size, entry, keepalive)
    if (provider !== 'telegram' || size <= 0) return
    try {
      const api = await telegramApi()
      if (api) await limited(pushPositionTo(api, await bookKey(name, size), entry))
    } catch (e) {
      await rememberTelegramError(e)
    }
  }
  const next = positionQueue.then(run, run)
  positionQueue = next.catch(() => {})
  return next
}

export { DEVICE }

// ---------- livros na nuvem ----------

export interface CloudActivity {
  label: string
  fraction: number
}
let activity: CloudActivity | null = null
const listeners = new Set<(a: CloudActivity | null) => void>()
export function onCloudActivity(listener: (a: CloudActivity | null) => void) {
  listeners.add(listener)
  listener(activity)
  return () => listeners.delete(listener)
}
function setActivity(a: CloudActivity | null) {
  activity = a
  listeners.forEach((l) => l(a))
}

/** Uma transferência por vez: enviar vários livros de uma vez parece rajada para o Telegram. */
let transferQueue: Promise<unknown> = Promise.resolve()
function queued<T>(work: () => Promise<T>): Promise<T> {
  const next = transferQueue.then(work, work)
  transferQueue = next.catch(() => {})
  return next
}

export async function cloudBooks(): Promise<CloudBook[]> {
  const api = await telegramApi()
  if (!api) return []
  try {
    return await listCloudBooks(api)
  } catch (e) {
    await rememberTelegramError(e)
    throw e
  }
}

export function sendToCloud(item: LibraryItem, displayName: string): Promise<{ existed: boolean }> {
  return queued(async () => {
    const api = await telegramApi()
    if (!api) throw new Error('Entre no Telegram em Ajustes para usar a nuvem.')
    const file = await bookFile(item.id)
    if (!file) throw new Error('O arquivo deste livro não está guardado neste navegador.')
    setActivity({ label: `Enviando "${displayName}"`, fraction: 0 })
    try {
      const { existed } = await uploadBook(api, item.name, item.format, new Uint8Array(await file.arrayBuffer()), (f) => setActivity({ label: `Enviando "${displayName}"`, fraction: f }))
      return { existed }
    } finally {
      setActivity(null)
    }
  })
}

/** Baixa o livro da nuvem para a biblioteca deste navegador. */
export function fetchFromCloud(book: CloudBook, displayName: string): Promise<LibraryItem> {
  return queued(async () => {
    const api = await telegramApi()
    if (!api) throw new Error('Entre no Telegram em Ajustes para usar a nuvem.')
    setActivity({ label: `Baixando "${displayName}"`, fraction: 0 })
    try {
      const bytes = await downloadBook(api, book, (f) => setActivity({ label: `Baixando "${displayName}"`, fraction: f }))
      const { item } = await addBook(book.name, book.format, new Blob([bytes]))
      return item
    } finally {
      setActivity(null)
    }
  })
}

export async function deleteFromCloud(book: CloudBook) {
  const api = await telegramApi()
  if (api) await removeCloudBook(api, book.key)
}

/** Códigos dos livros deste navegador (para saber o que já está na nuvem e o que falta baixar). */
export async function localKeys(items: LibraryItem[]): Promise<Map<string, LibraryItem>> {
  const map = new Map<string, LibraryItem>()
  for (const item of items) if (item.fileSize > 0) map.set(await bookKey(item.name, item.fileSize), item)
  return map
}
