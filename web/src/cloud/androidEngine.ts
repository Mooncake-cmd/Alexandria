/**
 * Motor da nuvem pelo Telegram dentro do app Android: roda num WebView escondido, com a mesma lógica
 * do site (telegramCloud.ts + GramJS), e conversa com o Kotlin pela ponte `AndroidCloud`.
 *
 * Kotlin → JS: `alxCloud.call(id, método, argumentosJson)`.
 * JS → Kotlin: `AndroidCloud.reply(id, json)`, `AndroidCloud.fail(id, código, mensagem)`,
 * `AndroidCloud.progress(id, fração)`. Arquivos passam em pedaços (base64): `read(token, início,
 * tamanho)` para enviar e `write(token, base64)` para baixar.
 */
import { copyCloud, downloadBook, listCloudBooks, pullPositionFrom, pushPositionTo, removeCloudBook, uploadBook, type TgApi } from './telegramCloud'
import type { CloudChat, CloudTarget } from './gramApi'
import type { SyncEntry } from '../storage/sync'

interface Bridge {
  reply(id: string, json: string): void
  fail(id: string, code: string, message: string): void
  progress(id: string, fraction: number): void
  read(token: string, offset: number, length: number): string
  write(token: string, base64: string): boolean
  ready(): void
}

declare global {
  interface Window {
    AndroidCloud?: Bridge
    alxCloud?: { call: (id: string, method: string, args: string) => void }
    /** Só nos testes: troca a ligação com o Telegram por uma falsa (uma por conversa: 'me' ou o id do grupo). */
    __alxTestApi?: TgApi
    __alxTestApis?: Record<string, TgApi>
  }
}

const CHUNK = 512 * 1024

function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(binary)
}

function fromBase64(text: string): Uint8Array {
  const binary = atob(text)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}

interface Settings {
  apiId: number
  apiHash: string
  session: string
  chat: CloudChat | null
}

let settings: Settings | null = null
let login: import('./gramApi').TelegramLogin | null = null

const target = (): CloudTarget => settings?.chat ?? 'me'

async function client() {
  if (!settings?.session) throw Object.assign(new Error('Sem sessão do Telegram'), { errorMessage: 'NO_SESSION' })
  const gram = await import('./gramApi')
  return { gram, client: await gram.connectedClient({ apiId: settings.apiId, apiHash: settings.apiHash }, settings.session) }
}

async function api(where: CloudTarget = target()): Promise<TgApi> {
  const fake = window.__alxTestApis?.[where === 'me' ? 'me' : where.id] ?? window.__alxTestApi
  if (fake) return fake
  const { gram, client: c } = await client()
  return gram.gramApi(c, where)
}

function chatArg(value: unknown): CloudChat {
  const c = value as Partial<CloudChat> | null
  if (!c?.id || !c.accessHash) throw new Error('Grupo inválido.')
  return { id: String(c.id), accessHash: String(c.accessHash), title: String(c.title ?? 'Grupo') }
}

type Args = Record<string, unknown>

const methods: Record<string, (args: Args, id: string, bridge: Bridge) => Promise<unknown>> = {
  async init(args) {
    settings = { apiId: Number(args.apiId), apiHash: String(args.apiHash ?? ''), session: String(args.session ?? ''), chat: args.chat ? chatArg(args.chat) : null }
    return true
  },
  async sendCode(args) {
    const { TelegramLogin } = await import('./gramApi')
    login = new TelegramLogin({ apiId: Number(args.apiId), apiHash: String(args.apiHash) })
    await login.sendCode(String(args.phone))
    return true
  },
  async signIn(args) {
    if (!login) throw new Error('Peça o código de novo.')
    return login.signIn(String(args.code))
  },
  async password(args) {
    if (!login) throw new Error('Peça o código de novo.')
    await login.password(String(args.password))
    return true
  },
  async finish() {
    if (!login) throw new Error('Peça o código de novo.')
    const result = await login.finish()
    login = null
    return result
  },
  async logout() {
    if (!settings?.session) return true
    const { logOut } = await import('./gramApi')
    await logOut({ apiId: settings.apiId, apiHash: settings.apiHash }, settings.session)
    settings = null
    return true
  },
  async books() {
    return listCloudBooks(await api())
  },
  async upload(args, id, bridge) {
    const size = Number(args.size)
    const bytes = new Uint8Array(size)
    for (let offset = 0; offset < size; offset += CHUNK) bytes.set(fromBase64(bridge.read(String(args.token), offset, Math.min(CHUNK, size - offset))), offset)
    const { book, existed } = await uploadBook(await api(), String(args.name), String(args.format), bytes, (f) => bridge.progress(id, f))
    return { book, existed }
  },
  async download(args, id, bridge) {
    const bytes = await downloadBook(await api(), args.book as never, (f) => bridge.progress(id, f * 0.95))
    for (let offset = 0; offset < bytes.length; offset += CHUNK) {
      if (!bridge.write(String(args.token), toBase64(bytes.subarray(offset, offset + CHUNK)))) throw new Error('Não foi possível gravar o arquivo no aparelho.')
    }
    return { size: bytes.length }
  },
  async remove(args) {
    await removeCloudBook(await api(), String(args.key))
    return true
  },
  async pullPosition(args) {
    return pullPositionFrom(await api(), String(args.key))
  },
  async pushPosition(args) {
    return pushPositionTo(await api(), String(args.key), args.entry as SyncEntry)
  },
  // ---------- grupo privado ----------
  async createGroup() {
    const { gram, client: c } = await client()
    return gram.createGroup(c, 'Alexandria — biblioteca')
  },
  async joinGroup(args) {
    const { gram, client: c } = await client()
    return gram.joinGroup(c, String(args.link ?? ''))
  },
  async groups() {
    const { gram, client: c } = await client()
    return gram.listGroups(c)
  },
  async inviteLink() {
    if (!settings?.chat) throw new Error('A nuvem está nas Mensagens salvas: não há grupo para convidar.')
    const { gram, client: c } = await client()
    return gram.inviteLink(c, settings.chat)
  },
  async copyFromSaved() {
    const chat = settings?.chat
    if (!chat) throw new Error('Escolha um grupo antes.')
    const forward = window.__alxTestApis
      ? async (ids: number[]) => {
          const from = window.__alxTestApis!.me
          for (const id of ids) {
            const m = (await from.search('#alexandria')).find((x) => x.id === id)!
            await window.__alxTestApis![chat.id].upload(m.fileName ?? 'livro', await from.download(id), m.caption)
          }
        }
      : async (ids: number[]) => {
          const { gram, client: c } = await client()
          await gram.forwardFromSaved(c, chat, ids)
        }
    return copyCloud(await api('me'), await api(chat), forward)
  },
  // ---------- mensagens patrocinadas (regra 3.3) ----------
  async sponsored() {
    if (!settings?.chat || window.__alxTestApis || window.__alxTestApi) return []
    const { gram, client: c } = await client()
    return gram.sponsoredMessages(c, settings.chat)
  },
  async viewSponsored(args) {
    if (!settings?.chat) return true
    const { gram, client: c } = await client()
    await gram.viewSponsored(c, settings.chat, String(args.id))
    return true
  },
  async clickSponsored(args) {
    if (!settings?.chat) return true
    const { gram, client: c } = await client()
    await gram.clickSponsored(c, settings.chat, String(args.id))
    return true
  }
}

/** Uma chamada por vez: o Telegram não gosta de rajadas, e as transferências usam muita memória. */
let queue: Promise<unknown> = Promise.resolve()

/** Transferências podem demorar; o resto, se passar disto sem resposta, falha e libera a fila. */
const SLOW = new Set(['upload', 'download', 'copyFromSaved', 'books'])
const TIMEOUT_MS = 60_000
const SLOW_TIMEOUT_MS = 30 * 60_000

function withTimeout<T>(work: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout>
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      // A conexão provavelmente morreu: a próxima chamada conecta de novo.
      import('./gramApi').then((g) => g.dropConnection()).catch(() => {})
      reject(Object.assign(new Error('O Telegram não respondeu a tempo. Tente de novo.'), { errorMessage: 'TIMEOUT' }))
    }, ms)
  })
  return Promise.race([work, timeout]).finally(() => clearTimeout(timer))
}

window.alxCloud = {
  call(id, method, argsJson) {
    const bridge = window.AndroidCloud
    if (!bridge) return
    const run = async () => {
      try {
        const handler = methods[method]
        if (!handler) throw new Error(`Método desconhecido: ${method}`)
        const result = await withTimeout(handler(JSON.parse(argsJson || '{}') as Args, id, bridge), SLOW.has(method) ? SLOW_TIMEOUT_MS : TIMEOUT_MS)
        bridge.reply(id, JSON.stringify(result ?? null))
      } catch (e) {
        const code = (e as { errorMessage?: string })?.errorMessage ?? ''
        let message = e instanceof Error ? e.message : String(e)
        if (code && code !== 'TIMEOUT') {
          try {
            message = (await import('./gramApi')).telegramError(e)
          } catch {
            // mantém a mensagem original
          }
        }
        bridge.fail(id, code, message)
      }
    }
    const next = queue.then(run, run)
    queue = next.catch(() => {})
  }
}

window.AndroidCloud?.ready()
