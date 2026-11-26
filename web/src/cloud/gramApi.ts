/**
 * Ligação real com o Telegram pela GramJS (MIT), carregada só quando a nuvem é usada.
 * Usa o api_id/api_hash da própria pessoa (regra 2.1 dos termos da API do Telegram) e mexe só nas
 * Mensagens salvas, nas mensagens do Alexandria.
 */
import './nodeShim'
import { Api, TelegramClient } from 'telegram'
import { StringSession } from 'telegram/sessions'
import { CustomFile } from 'telegram/client/uploads'
import { Buffer } from 'buffer'
import type { TgApi, TgMessage } from './telegramCloud'

export interface TelegramCredentials {
  apiId: number
  apiHash: string
}

function makeClient(credentials: TelegramCredentials, session = ''): TelegramClient {
  const client = new TelegramClient(new StringSession(session), credentials.apiId, credentials.apiHash, {
    connectionRetries: 3,
    // Quando o Telegram pede para esperar (FLOOD_WAIT), espera em vez de insistir.
    floodSleepThreshold: 300,
    deviceModel: 'Alexandria Leitor',
    systemVersion: 'Web',
    appVersion: '1.0',
    langCode: 'pt',
    useWSS: true
  })
  // Legendas vão como texto puro (sem markdown): a marca #alexandria_livro tem "_".
  client.setParseMode(undefined)
  return client
}

/** Erro do Telegram com o código (PHONE_CODE_INVALID, PASSWORD_HASH_INVALID, FLOOD_WAIT…). */
export function telegramError(e: unknown): string {
  const code = (e as { errorMessage?: string })?.errorMessage ?? (e instanceof Error ? e.message : String(e))
  if (/PHONE_NUMBER_INVALID/.test(code)) return 'Número inválido. Use o formato internacional, com +55 e DDD.'
  if (/PHONE_CODE_INVALID|PHONE_CODE_EMPTY/.test(code)) return 'Código incorreto.'
  if (/PHONE_CODE_EXPIRED/.test(code)) return 'O código venceu. Peça outro.'
  if (/PASSWORD_HASH_INVALID/.test(code)) return 'Senha de duas etapas incorreta.'
  if (/API_ID_INVALID|API_ID_PUBLISHED_FLOOD/.test(code)) return 'api_id ou api_hash inválidos. Confira em my.telegram.org.'
  if (/FLOOD_WAIT/.test(code)) return 'O Telegram pediu para esperar um pouco antes de tentar de novo.'
  if (/AUTH_KEY_UNREGISTERED|SESSION_REVOKED|USER_DEACTIVATED/.test(code)) return 'A sessão do Telegram foi encerrada. Entre de novo.'
  return `O Telegram respondeu: ${code}`
}

/** Login em etapas: número → código → (senha de duas etapas). */
export class TelegramLogin {
  private client: TelegramClient
  private phone = ''
  private phoneCodeHash = ''

  constructor(private readonly credentials: TelegramCredentials) {
    this.client = makeClient(credentials)
  }

  async sendCode(phone: string) {
    await this.client.connect()
    this.phone = phone.replace(/[^\d+]/g, '')
    const result = await this.client.sendCode(this.credentials, this.phone)
    this.phoneCodeHash = result.phoneCodeHash
  }

  /** 'ok' = entrou; 'password' = a conta tem senha de duas etapas. */
  async signIn(code: string): Promise<'ok' | 'password'> {
    try {
      const result = await this.client.invoke(new Api.auth.SignIn({ phoneNumber: this.phone, phoneCodeHash: this.phoneCodeHash, phoneCode: code.trim() }))
      if (result instanceof Api.auth.AuthorizationSignUpRequired) throw new Error('Este número não tem conta no Telegram.')
      return 'ok'
    } catch (e) {
      if ((e as { errorMessage?: string }).errorMessage === 'SESSION_PASSWORD_NEEDED') return 'password'
      throw e
    }
  }

  async password(password: string) {
    let failure: unknown = null
    await this.client.signInWithPassword(this.credentials, {
      password: async () => password,
      onError: async (e) => {
        failure = e
        return true
      }
    })
    if (failure) throw failure
  }

  /** Sessão para guardar (vale como login neste aparelho) e o nome da conta. */
  async finish(): Promise<{ session: string; name: string }> {
    const me = (await this.client.getMe()) as Api.User
    const name = [me.firstName, me.lastName].filter(Boolean).join(' ') || me.username || 'Telegram'
    const session = (this.client.session as StringSession).save()
    await this.client.disconnect()
    return { session, name }
  }
}

let shared: { key: string; client: TelegramClient } | null = null

/** Cliente conectado com a sessão guardada (um por página). */
export async function connectedClient(credentials: TelegramCredentials, session: string): Promise<TelegramClient> {
  const key = `${credentials.apiId}:${session}`
  if (shared?.key !== key) {
    await shared?.client.disconnect().catch(() => {})
    shared = { key, client: makeClient(credentials, session) }
  }
  if (!shared.client.connected) await shared.client.connect()
  return shared.client
}

export async function logOut(credentials: TelegramCredentials, session: string) {
  const client = await connectedClient(credentials, session)
  try {
    await client.invoke(new Api.auth.LogOut())
  } finally {
    await client.disconnect().catch(() => {})
    shared = null
  }
}

const toNumber = (value: unknown) => (typeof value === 'number' ? value : Number((value as { toString(): string })?.toString() ?? 0))

function toMessage(m: Api.Message): TgMessage {
  const document = m.media instanceof Api.MessageMediaDocument && m.media.document instanceof Api.Document ? m.media.document : null
  const fileName = document?.attributes.find((a): a is Api.DocumentAttributeFilename => a instanceof Api.DocumentAttributeFilename)?.fileName
  return { id: m.id, caption: m.message ?? '', fileName, size: document ? toNumber(document.size) : undefined, date: m.date }
}

/** As operações da nuvem (TgApi) sobre as Mensagens salvas. */
export function gramApi(client: TelegramClient): TgApi {
  return {
    async search(tag) {
      const messages = await client.getMessages('me', { search: tag, limit: 1000 })
      return messages.filter((m) => m instanceof Api.Message).map(toMessage)
    },
    async upload(name, bytes, caption, onProgress) {
      const file = new CustomFile(name, bytes.byteLength, '', Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength))
      // A GramJS, sozinha, trata arquivos acima de 20 MB como caminho no disco (só existe no Node) e falha
      // com "Either one of `buffer` or `filePath` should be specified". Enviando antes, com o limite do
      // buffer no tamanho do arquivo, ela lê da memória. O nome e o tipo vêm do arquivo enviado.
      const uploaded = await client.uploadFile({ file, workers: 1, maxBufferSize: bytes.byteLength + 1, onProgress: (p: number) => onProgress?.(p) })
      const sent = await client.sendFile('me', { file: uploaded, caption, forceDocument: true })
      return toMessage(sent)
    },
    async download(messageId, onProgress) {
      const [message] = await client.getMessages('me', { ids: messageId })
      if (!message) throw new Error('A mensagem do livro não foi encontrada no Telegram.')
      const data = await client.downloadMedia(message, {
        progressCallback: (done: unknown, total: unknown) => onProgress?.(toNumber(total) ? toNumber(done) / toNumber(total) : 0)
      })
      if (!data || typeof data === 'string') throw new Error('O Telegram não devolveu o arquivo.')
      return new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
    },
    async remove(ids) {
      await client.deleteMessages('me', ids, { revoke: true })
    }
  }
}
