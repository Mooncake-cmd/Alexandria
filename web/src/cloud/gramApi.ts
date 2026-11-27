/**
 * Ligação real com o Telegram pela GramJS (MIT), carregada só quando a nuvem é usada.
 * Usa o api_id/api_hash da própria pessoa (regra 2.1 dos termos da API do Telegram) e mexe só na
 * conversa da nuvem (Mensagens salvas ou o grupo privado escolhido), nas mensagens do Alexandria.
 */
import './nodeShim'
import { Api, TelegramClient } from 'telegram'
import { StringSession } from 'telegram/sessions'
import { CustomFile } from 'telegram/client/uploads'
import { Buffer } from 'buffer'
import bigInt from 'big-integer'
import { videoKeyName, type SponsoredItem, type TgApi, type TgMessage } from './telegramCloud'
import { BLOCK, BlockReader } from './blockReader'
import type { Topic } from '../core/playlists'

/** Grupo privado da nuvem (o access_hash é preciso para falar com ele sem procurar de novo). */
export interface CloudChat {
  id: string
  accessHash: string
  title: string
}

/** Conversa da nuvem: as Mensagens salvas ou um grupo. */
export type CloudTarget = 'me' | CloudChat

export function peerOf(target: CloudTarget): 'me' | Api.InputPeerChannel {
  return target === 'me' ? 'me' : new Api.InputPeerChannel({ channelId: bigInt(target.id), accessHash: bigInt(target.accessHash) })
}

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
  if (/INVITE_HASH_EXPIRED|INVITE_HASH_INVALID|INVITE_HASH_EMPTY/.test(code)) return 'O link de convite não vale mais. Peça um novo no aparelho que criou o grupo.'
  if (/USER_ALREADY_PARTICIPANT/.test(code)) return 'Esta conta já está no grupo.'
  if (/CHANNELS_TOO_MUCH/.test(code)) return 'Esta conta já está em grupos e canais demais (limite do Telegram).'
  if (/CHAT_WRITE_FORBIDDEN|CHAT_SEND_MEDIA_FORBIDDEN|CHAT_SEND_DOCS_FORBIDDEN/.test(code)) return 'Esta conta não tem permissão para enviar arquivos no grupo.'
  if (/CHANNEL_PRIVATE|CHANNEL_INVALID|PEER_ID_INVALID/.test(code)) return 'O grupo da nuvem não está acessível para esta conta (saiu do grupo ou ele foi apagado).'
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

/**
 * Esquece a conexão atual (a próxima chamada conecta de novo). Usado quando um pedido fica sem
 * resposta: depois de uma queda de rede a GramJS às vezes espera para sempre numa conexão morta.
 */
export async function dropConnection() {
  const old = shared
  shared = null
  await old?.client.disconnect().catch(() => {})
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

function senderName(m: Api.Message): string | undefined {
  const s = (m as unknown as { _sender?: unknown })._sender
  if (s instanceof Api.User) return [s.firstName, s.lastName].filter(Boolean).join(' ') || s.username || undefined
  return undefined
}

function toMessage(m: Api.Message): TgMessage {
  const document = m.media instanceof Api.MessageMediaDocument && m.media.document instanceof Api.Document ? m.media.document : null
  const fileName = document?.attributes.find((a): a is Api.DocumentAttributeFilename => a instanceof Api.DocumentAttributeFilename)?.fileName
  const video = document?.attributes.find((a): a is Api.DocumentAttributeVideo => a instanceof Api.DocumentAttributeVideo)
  // Num grupo com tópicos, a mensagem responde à primeira mensagem do tópico (o id do tópico).
  const reply = m.replyTo instanceof Api.MessageReplyHeader && m.replyTo.forumTopic ? m.replyTo.replyToTopId ?? m.replyTo.replyToMsgId : undefined
  return {
    id: m.id,
    caption: m.message ?? '',
    fileName,
    size: document ? toNumber(document.size) : undefined,
    date: m.date,
    sender: senderName(m),
    mime: document?.mimeType,
    duration: video ? toNumber(video.duration) : undefined,
    topic: reply
  }
}

/** As operações da nuvem (TgApi) sobre a conversa escolhida (Mensagens salvas, por padrão). */
export function gramApi(client: TelegramClient, target: CloudTarget = 'me'): TgApi {
  const peer = peerOf(target)
  return {
    // Num grupo, qualquer arquivo de livro conta (mesmo sem a marca).
    anyBook: target !== 'me',
    async search(tag) {
      const messages = await client.getMessages(peer, { search: tag, limit: 1000 })
      return messages.filter((m) => m instanceof Api.Message).map(toMessage)
    },
    async files(limit = 3000) {
      const messages = await client.getMessages(peer, { filter: new Api.InputMessagesFilterDocument(), limit })
      return messages.filter((m) => m instanceof Api.Message).map(toMessage)
    },
    async videos(limit = 3000) {
      // Vídeos enviados como vídeo e os enviados como arquivo (filmes em MKV, por exemplo).
      const [asVideo, asFile] = await Promise.all([
        client.getMessages(peer, { filter: new Api.InputMessagesFilterVideo(), limit }),
        client.getMessages(peer, { filter: new Api.InputMessagesFilterDocument(), limit })
      ])
      const seen = new Set<number>()
      return [...asVideo, ...asFile].filter((m) => m instanceof Api.Message && !seen.has(m.id) && !!seen.add(m.id)).map(toMessage)
    },
    async upload(name, bytes, caption, onProgress) {
      const file = new CustomFile(name, bytes.byteLength, '', Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength))
      // A GramJS, sozinha, trata arquivos acima de 20 MB como caminho no disco (só existe no Node) e falha
      // com "Either one of `buffer` or `filePath` should be specified". Enviando antes, com o limite do
      // buffer no tamanho do arquivo, ela lê da memória. O nome e o tipo vêm do arquivo enviado.
      const uploaded = await client.uploadFile({ file, workers: 1, maxBufferSize: bytes.byteLength + 1, onProgress: (p: number) => onProgress?.(p) })
      const sent = await client.sendFile(peer, { file: uploaded, caption, forceDocument: true })
      return toMessage(sent)
    },
    async download(messageId, onProgress) {
      const [message] = await client.getMessages(peer, { ids: messageId })
      if (!message) throw new Error('A mensagem do livro não foi encontrada no Telegram.')
      const data = await client.downloadMedia(message, {
        progressCallback: (done: unknown, total: unknown) => onProgress?.(toNumber(total) ? toNumber(done) / toNumber(total) : 0)
      })
      if (!data || typeof data === 'string') throw new Error('O Telegram não devolveu o arquivo.')
      return new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
    },
    async remove(ids) {
      await client.deleteMessages(peer, ids, { revoke: true })
    }
  }
}

// ---------- grupo privado da nuvem ----------

function chatOf(c: Api.TypeChat | undefined): CloudChat {
  if (!(c instanceof Api.Channel)) throw new Error('O Telegram não devolveu o grupo.')
  if (!c.megagroup) throw new Error('Isto é um canal, não um grupo. Use um grupo privado (todos os membros podem enviar arquivos).')
  return { id: c.id.toString(), accessHash: (c.accessHash ?? bigInt.zero).toString(), title: c.title }
}

/** Cria o grupo privado da nuvem (só esta conta dentro; as outras entram pelo link). */
export async function createGroup(client: TelegramClient, title: string): Promise<CloudChat> {
  const result = await client.invoke(new Api.channels.CreateChannel({ megagroup: true, title, about: 'Biblioteca do Alexandria: livros e posições de leitura.' }))
  return chatOf('chats' in result ? result.chats[0] : undefined)
}

/** Link de convite do grupo, para as outras contas entrarem. */
export async function inviteLink(client: TelegramClient, chat: CloudChat): Promise<string> {
  const invite = await client.invoke(new Api.messages.ExportChatInvite({ peer: peerOf(chat) }))
  if (!(invite instanceof Api.ChatInviteExported)) throw new Error('O Telegram não devolveu o link.')
  return invite.link
}

/** O código de um link de convite (t.me/+código, t.me/joinchat/código ou só o código). */
export function inviteHash(link: string): string | null {
  const text = link.trim()
  const match = text.match(/(?:t\.me|telegram\.me|telegram\.dog)\/(?:\+|joinchat\/)([\w-]+)/i) ?? text.match(/^\+?([\w-]{10,})$/)
  return match?.[1] ?? null
}

/** Entra no grupo pelo link de convite (ou só o encontra, se a conta já estiver nele). */
export async function joinGroup(client: TelegramClient, link: string): Promise<CloudChat> {
  const hash = inviteHash(link)
  if (!hash) throw new Error('Isto não parece um link de convite do Telegram (t.me/+…).')
  const check = await client.invoke(new Api.messages.CheckChatInvite({ hash }))
  if (check instanceof Api.ChatInviteAlready) return chatOf(check.chat)
  const result = await client.invoke(new Api.messages.ImportChatInvite({ hash }))
  return chatOf('chats' in result ? result.chats[0] : undefined)
}

/** Grupos desta conta (para escolher um que já existe). */
export async function listGroups(client: TelegramClient): Promise<CloudChat[]> {
  const dialogs = await client.getDialogs({ limit: 300 })
  return dialogs.map((d) => d.entity).filter((e): e is Api.Channel => e instanceof Api.Channel && !!e.megagroup && !e.left).map(chatOf)
}

/** Encaminha mensagens das Mensagens salvas para o grupo (a legenda vai junto). */
export async function forwardFromSaved(client: TelegramClient, chat: CloudChat, ids: number[]) {
  await client.forwardMessages(peerOf(chat), { messages: ids, fromPeer: 'me' })
}

// ---------- mensagens patrocinadas ----------

const b64 = (bytes: Buffer) => Buffer.from(bytes).toString('base64')

/** Anúncios oficiais para a conversa (num grupo privado, em geral não vem nenhum). */
export async function sponsoredMessages(client: TelegramClient, target: CloudTarget): Promise<SponsoredItem[]> {
  if (target === 'me') return []
  const result = await client.invoke(new Api.messages.GetSponsoredMessages({ peer: peerOf(target) }))
  if (!(result instanceof Api.messages.SponsoredMessages)) return []
  return result.messages.map((m) => ({
    id: b64(m.randomId),
    title: m.title,
    message: m.message,
    url: m.url,
    buttonText: m.buttonText,
    recommended: !!m.recommended,
    sponsorInfo: m.sponsorInfo,
    additionalInfo: m.additionalInfo
  }))
}

export async function viewSponsored(client: TelegramClient, target: CloudTarget, id: string) {
  if (target !== 'me') await client.invoke(new Api.messages.ViewSponsoredMessage({ peer: peerOf(target), randomId: Buffer.from(id, 'base64') }))
}

export async function clickSponsored(client: TelegramClient, target: CloudTarget, id: string) {
  if (target !== 'me') await client.invoke(new Api.messages.ClickSponsoredMessage({ peer: peerOf(target), randomId: Buffer.from(id, 'base64') }))
}

// ---------- vídeo por partes (streaming) ----------

export interface VideoSource {
  messageId: number
  name: string
  keyName: string
  size: number
  mime: string
  reader: BlockReader
}

function documentOf(m: Api.Message | undefined): Api.Document {
  const doc = m?.media instanceof Api.MessageMediaDocument && m.media.document instanceof Api.Document ? m.media.document : null
  if (!doc) throw new Error('O vídeo não foi encontrado no grupo (pode ter sido apagado).')
  return doc
}

/**
 * Abre um vídeo do grupo para tocar enquanto baixa: cada bloco de 512 KB é pedido ao Telegram só quando
 * o player precisa (ou um pouco antes). A referência do arquivo vence depois de um tempo; aí a mensagem
 * é lida de novo e o pedido, repetido.
 */
export async function videoSource(client: TelegramClient, target: CloudTarget, messageId: number): Promise<VideoSource> {
  const peer = peerOf(target)
  let doc = documentOf((await client.getMessages(peer, { ids: messageId }))[0])
  const location = () => new Api.InputDocumentFileLocation({ id: doc.id, accessHash: doc.accessHash, fileReference: doc.fileReference, thumbSize: '' })
  const size = toNumber(doc.size)
  const fetchBlock = async (index: number, retry = true): Promise<Uint8Array> => {
    try {
      for await (const chunk of client.iterDownload({ file: location(), offset: bigInt(index * BLOCK), limit: 1, requestSize: BLOCK, chunkSize: BLOCK, dcId: doc.dcId, fileSize: bigInt(size) })) {
        return new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength)
      }
      return new Uint8Array(0)
    } catch (e) {
      if (retry && /FILE_REFERENCE/.test((e as { errorMessage?: string }).errorMessage ?? '')) {
        doc = documentOf((await client.getMessages(peer, { ids: messageId }))[0])
        return fetchBlock(index, false)
      }
      throw e
    }
  }
  const fileName = doc.attributes.find((a): a is Api.DocumentAttributeFilename => a instanceof Api.DocumentAttributeFilename)?.fileName
  return { messageId, name: fileName ?? `Vídeo ${messageId}`, keyName: videoKeyName(fileName, messageId), size, mime: doc.mimeType || 'video/mp4', reader: new BlockReader(size, (i) => fetchBlock(i)) }
}

// ---------- tópicos do grupo (playlists) ----------

/** Tópicos do grupo (vazio se o grupo não usa tópicos). */
export async function listTopics(client: TelegramClient, chat: CloudChat): Promise<{ forum: boolean; topics: Topic[] }> {
  const entity = await client.getEntity(peerOf(chat))
  const forum = entity instanceof Api.Channel && !!entity.forum
  if (!forum) return { forum, topics: [] }
  const topics: Topic[] = []
  let offsetDate = 0
  let offsetId = 0
  let offsetTopic = 0
  for (let page = 0; page < 10; page++) {
    const result = await client.invoke(new Api.channels.GetForumTopics({ channel: peerOf(chat), offsetDate, offsetId, offsetTopic, limit: 100 }))
    const found = result.topics.filter((t): t is Api.ForumTopic => t instanceof Api.ForumTopic)
    topics.push(...found.map((t) => ({ id: t.id, title: t.title })))
    if (found.length < 100) break
    const last = found[found.length - 1]
    offsetDate = last.date
    offsetId = last.topMessage
    offsetTopic = last.id
  }
  return { forum, topics }
}

/** Liga os tópicos no grupo (só dono ou administrador). */
export async function enableTopics(client: TelegramClient, chat: CloudChat) {
  await client.invoke(new Api.channels.ToggleForum({ channel: peerOf(chat), enabled: true }))
}

/** Cria um tópico (um curso ou uma coleção). Devolve o tópico criado. */
export async function createTopic(client: TelegramClient, chat: CloudChat, title: string): Promise<Topic> {
  const updates = await client.invoke(new Api.channels.CreateForumTopic({ channel: peerOf(chat), title, randomId: bigInt(Math.floor(Math.random() * 2 ** 52)) }))
  const list = 'updates' in updates ? updates.updates : []
  for (const u of list) {
    const m = u instanceof Api.UpdateNewChannelMessage ? u.message : null
    if (m instanceof Api.MessageService && m.action instanceof Api.MessageActionTopicCreate) return { id: m.id, title }
  }
  // Sem a mensagem de criação na resposta: procura pelo nome.
  const found = (await listTopics(client, chat)).topics.filter((t) => t.title === title).sort((a, b) => b.id - a.id)[0]
  if (!found) throw new Error('O tópico foi criado, mas não apareceu ainda. Atualize a aba.')
  return found
}

export async function renameTopic(client: TelegramClient, chat: CloudChat, topicId: number, title: string) {
  await client.invoke(new Api.channels.EditForumTopic({ channel: peerOf(chat), topicId, title }))
}
