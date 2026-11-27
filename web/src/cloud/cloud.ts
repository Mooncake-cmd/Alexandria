/**
 * Nuvem do site: escolhe por onde vai a posição de leitura (GitHub ou Telegram) e cuida dos livros
 * na nuvem do Telegram (Mensagens salvas ou um grupo privado). A ligação com o Telegram (GramJS,
 * ~1 MB) só é carregada quando usada.
 */
import { getPref, setPref, addBook, bookFile, type LibraryItem } from '../storage/library'
import { bookKey, DEVICE, pullPosition as pullFromGitHub, pushPosition as pushToGitHub, syncSettings, type SyncEntry } from '../storage/sync'
import { copyCloud, downloadBook, listCloudBooks, listVideos, pullPositionFrom, pushPositionTo, uploadBook, removeCloudBook, type CloudBook, type CloudVideo, type SponsoredItem, type TgApi } from './telegramCloud'
import type { CloudChat, CloudTarget } from './gramApi'
import { MAIN_TAB, tabOf } from '../core/tabs'
import { tabById } from '../storage/tabs'

export interface TelegramSettings {
  apiId: number
  apiHash: string
  session: string
  name: string
  error?: string | null
  /** Antes das abas: o grupo da nuvem (agora é o grupo da aba principal, em "tabs"). */
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

/** Conversa da nuvem de uma aba: o grupo dela ou, na principal sem grupo, as Mensagens salvas. */
async function targetOfTab(tabId?: string): Promise<CloudTarget | null> {
  const tab = await tabById(tabId)
  return tab.chat ?? (tab.id === MAIN_TAB ? 'me' : null)
}

async function telegramApi(target: CloudTarget | null | undefined): Promise<TgApi | null> {
  const s = await telegramSettings()
  if (!s?.session || !target) return null
  if (apiFactory) return apiFactory(s, target)
  const { connectedClient, gramApi } = await import('./gramApi')
  return gramApi(await connectedClient({ apiId: s.apiId, apiHash: s.apiHash }, s.session), target)
}

const apiOfTab = async (tabId?: string) => telegramApi(await targetOfTab(tabId))

async function client() {
  const s = await telegramSettings()
  if (!s?.session) throw new Error('Entre no Telegram em Ajustes para usar a nuvem.')
  const gram = await import('./gramApi')
  return { s, gram, client: await gram.connectedClient({ apiId: s.apiId, apiHash: s.apiHash }, s.session) }
}

// ---------- onde fica a nuvem: Mensagens salvas ou grupo privado ----------

/** Cria um grupo privado para a nuvem de uma aba (o nome aparece no Telegram). */
export async function createCloudGroup(title = 'Alexandria — biblioteca'): Promise<CloudChat> {
  const { gram, client: c } = await client()
  return gram.createGroup(c, title)
}

export async function joinCloudGroup(link: string): Promise<CloudChat> {
  const { gram, client: c } = await client()
  return gram.joinGroup(c, link)
}

export async function myGroups(): Promise<CloudChat[]> {
  const { gram, client: c } = await client()
  return gram.listGroups(c)
}

export async function cloudInviteLink(chat: CloudChat): Promise<string> {
  const { gram, client: c } = await client()
  return gram.inviteLink(c, chat)
}

/** Copia para o grupo os livros e posições que estão nas Mensagens salvas. */
export function copySavedToGroup(chat: CloudChat): Promise<{ books: number; positions: boolean }> {
  return queued(async () => {
    const { gram, client: c } = await client()
    const [from, to] = await Promise.all([telegramApi('me'), telegramApi(chat)])
    return copyCloud(from!, to!, (ids) => gram.forwardFromSaved(c, chat, ids))
  })
}

// ---------- mensagens patrocinadas (regra 3.3) ----------

/** A documentação pede para guardar o resultado por 5 minutos. */
let sponsoredCache: { at: number; key: string; items: SponsoredItem[] } | null = null

export async function cloudSponsored(chat: CloudChat | null): Promise<SponsoredItem[]> {
  const s = await telegramSettings()
  if (!s?.session || !chat || apiFactory) return []
  const key = chat.id
  if (sponsoredCache && sponsoredCache.key === key && Date.now() - sponsoredCache.at < 5 * 60_000) return sponsoredCache.items
  try {
    const { gram, client: c } = await client()
    const items = await gram.sponsoredMessages(c, chat)
    sponsoredCache = { at: Date.now(), key, items }
    return items
  } catch {
    return []
  }
}

export async function sponsoredSeen(item: SponsoredItem, chat: CloudChat) {
  const { gram, client: c } = await client()
  await gram.viewSponsored(c, chat, item.id).catch(() => {})
}

export async function sponsoredClicked(item: SponsoredItem, chat: CloudChat) {
  const { gram, client: c } = await client()
  await gram.clickSponsored(c, chat, item.id).catch(() => {})
}

/** Erros que mostram que a sessão acabou (ex.: encerrada no Telegram, em "Dispositivos"). */
async function rememberTelegramError(e: unknown) {
  const code = (e as { errorMessage?: string })?.errorMessage ?? ''
  const s = await telegramSettings()
  if (!s) return
  if (/AUTH_KEY_UNREGISTERED|SESSION_REVOKED|USER_DEACTIVATED|AUTH_KEY_INVALID/.test(code)) await saveTelegram({ ...s, error: 'A sessão do Telegram foi encerrada. Entre de novo em Ajustes.' })
  else if (/CHANNEL_PRIVATE|CHANNEL_INVALID/.test(code)) await saveTelegram({ ...s, error: 'O grupo da nuvem de uma aba não está acessível para esta conta (saiu do grupo ou ele foi apagado). Escolha outro grupo nas opções da aba.' })
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

export async function pullPosition(name: string, size: number, tab?: string): Promise<SyncEntry | null> {
  const provider = await syncProvider()
  if (provider === 'github') return pullFromGitHub(name, size)
  if (provider !== 'telegram' || size <= 0) return null
  try {
    const api = await apiOfTab(tab)
    return api ? await limited(pullPositionFrom(api, await bookKey(name, size))) : null
  } catch (e) {
    await rememberTelegramError(e)
    return null
  }
}

/** Fila: uma gravação de posição por vez (o Telegram não gosta de rajadas). */
let positionQueue: Promise<unknown> = Promise.resolve()

export function pushPosition(name: string, size: number, entry: SyncEntry, keepalive = false, tab?: string): Promise<void> {
  const run = async () => {
    const provider = await syncProvider()
    if (provider === 'github') return pushToGitHub(name, size, entry, keepalive)
    if (provider !== 'telegram' || size <= 0) return
    try {
      const api = await apiOfTab(tab)
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

export async function cloudBooks(tab?: string): Promise<CloudBook[]> {
  const api = await apiOfTab(tab)
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
    const api = await apiOfTab(tabOf(item))
    if (!api) throw new Error('Entre no Telegram em Ajustes e escolha o grupo da aba para usar a nuvem.')
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
export function fetchFromCloud(book: CloudBook, displayName: string, tab?: string): Promise<LibraryItem> {
  return queued(async () => {
    const api = await apiOfTab(tab)
    if (!api) throw new Error('Entre no Telegram em Ajustes para usar a nuvem.')
    setActivity({ label: `Baixando "${displayName}"`, fraction: 0 })
    try {
      const bytes = await downloadBook(api, book, (f) => setActivity({ label: `Baixando "${displayName}"`, fraction: f }))
      const { item } = await addBook(book.name, book.format, new Blob([bytes]), Date.now(), tab)
      return item
    } finally {
      setActivity(null)
    }
  })
}

export async function deleteFromCloud(book: CloudBook, tab?: string) {
  const api = await apiOfTab(tab)
  if (api) await removeCloudBook(api, book.key)
}

/** Códigos dos livros deste navegador (para saber o que já está na nuvem e o que falta baixar). */
export async function localKeys(items: LibraryItem[]): Promise<Map<string, LibraryItem>> {
  const map = new Map<string, LibraryItem>()
  for (const item of items) if (item.fileSize > 0) map.set(await bookKey(item.name, item.fileSize), item)
  return map
}

// ---------- vídeos (abas de filmes e cursos) ----------

export async function cloudVideos(tab: string): Promise<CloudVideo[]> {
  const api = await apiOfTab(tab)
  if (!api) return []
  try {
    return await listVideos(api)
  } catch (e) {
    await rememberTelegramError(e)
    throw e
  }
}
