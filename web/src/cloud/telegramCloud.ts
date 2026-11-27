/**
 * Nuvem do Alexandria no Telegram (a mesma lógica no site e no app): nas Mensagens salvas ou num
 * grupo privado da pessoa (escolhido em Ajustes; a lógica é a mesma, só muda a conversa).
 *
 * - Cada livro é uma mensagem com o arquivo e uma legenda marcada com #alexandria_livro e uma
 *   linha "alexandria:{...}" com o código do livro (nome + tamanho), o nome e o formato.
 * - Um arquivo de livro enviado pelo próprio Telegram, com #alexandria_livro escrito na legenda,
 *   também conta: o código sai do nome e do tamanho do arquivo, como no app.
 * - Num grupo (só de livros), qualquer arquivo de livro conta, mesmo sem a marca: assim entram os
 *   livros mandados por contas sem o Alexandria e os encaminhados (que não levam legenda).
 * - As posições de leitura ficam num arquivinho JSON (o mesmo formato do gist do GitHub), numa
 *   mensagem marcada com #alexandria_posicoes; ao gravar, vai uma nova e as antigas são apagadas.
 *
 * Tudo passa pela interface [TgApi]: a ligação real com o Telegram (GramJS) fica em gramApi.ts e
 * os testes usam uma falsa. Só mexe nas mensagens do Alexandria (com as marcas acima).
 */
import { bookKey, mergeSync, parseSync, serializeSync, type SyncEntry } from '../storage/sync'
import { detectFormat } from '../core/formats'

export const BOOK_TAG = '#alexandria_livro'
export const POSITIONS_TAG = '#alexandria_posicoes'
export const POSITIONS_FILE = 'alexandria-posicoes.json'
/** Maior arquivo que o Telegram aceita numa conta comum. */
export const MAX_FILE_BYTES = 2000 * 1024 * 1024

export interface TgMessage {
  id: number
  caption: string
  fileName?: string
  size?: number
  /** Segundos desde 1970 (como no Telegram). */
  date: number
  /** Quem enviou (num grupo). */
  sender?: string
}

export interface TgApi {
  /** Mensagens da conversa da nuvem que contêm o texto (a marca do Alexandria). */
  search(tag: string): Promise<TgMessage[]>
  /**
   * Num grupo: todo arquivo de livro conta, com ou sem a marca. [files] lista as mensagens com
   * arquivo da conversa.
   */
  anyBook?: boolean
  /** Mensagens com arquivo da conversa, das mais novas para as mais antigas (até [limit]). */
  files?(limit?: number): Promise<TgMessage[]>
  upload(name: string, bytes: Uint8Array, caption: string, onProgress?: (fraction: number) => void): Promise<TgMessage>
  download(messageId: number, onProgress?: (fraction: number) => void): Promise<Uint8Array>
  remove(ids: number[]): Promise<void>
}

export interface CloudBook {
  key: string
  name: string
  size: number
  format: string
  messageId: number
  /** Milissegundos desde 1970. */
  date: number
  /** Quem enviou (num grupo). */
  sender?: string
  /** Enviado pelo Telegram, com a marca escrita à mão na legenda. */
  manual?: boolean
}

export function bookCaption(key: string, name: string, size: number, format: string): string {
  return `${BOOK_TAG}\n📖 ${name}\nalexandria:${JSON.stringify({ k: key, n: name, s: size, f: format })}`
}

export function parseBookCaption(message: TgMessage): CloudBook | null {
  if (!message.caption.includes(BOOK_TAG)) return null
  const line = message.caption.split('\n').find((l) => l.startsWith('alexandria:'))
  if (!line) return null
  try {
    const meta = JSON.parse(line.slice('alexandria:'.length)) as { k?: string; n?: string; s?: number; f?: string }
    if (!meta.k || !meta.n) return null
    return { key: meta.k, name: meta.n, size: meta.s ?? message.size ?? 0, format: meta.f ?? '', messageId: message.id, date: message.date * 1000, sender: message.sender }
  } catch {
    return null
  }
}

/**
 * Livro de uma mensagem: a legenda do Alexandria ou, se a pessoa só escreveu #alexandria_livro na
 * legenda de um arquivo de livro, o nome e o tamanho do arquivo (o mesmo código que o app calcula).
 */
export async function parseBookMessage(message: TgMessage, anyBook = false): Promise<CloudBook | null> {
  const book = parseBookCaption(message)
  if (book || (!anyBook && !message.caption.includes(BOOK_TAG))) return book
  if (message.caption.split('\n').some((l) => l.startsWith('alexandria:'))) return null
  const name = message.fileName?.trim()
  const format = name ? detectFormat(name) : null
  if (!name || !format || !message.size) return null
  return { key: await bookKey(name, message.size), name, size: message.size, format, messageId: message.id, date: message.date * 1000, sender: message.sender, manual: true }
}

/** Mensagens que podem ser livros: as marcadas ou, num grupo, todas com arquivo. */
async function bookMessages(api: TgApi): Promise<TgMessage[]> {
  if (!api.anyBook || !api.files) return api.search(BOOK_TAG)
  const seen = new Set<number>()
  // As marcadas entram também (uma legenda antiga do Alexandria pode estar num arquivo sem nome).
  return [...(await api.files()), ...(await api.search(BOOK_TAG))].filter((m) => !seen.has(m.id) && !!seen.add(m.id))
}

/** Livros da nuvem (o mais recente de cada um, se houver repetidos). */
export async function listCloudBooks(api: TgApi): Promise<CloudBook[]> {
  const books = new Map<string, CloudBook>()
  for (const message of await bookMessages(api)) {
    const book = await parseBookMessage(message, api.anyBook)
    if (!book) continue
    const old = books.get(book.key)
    if (!old || old.date < book.date) books.set(book.key, book)
  }
  return [...books.values()].sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'))
}

/** Envia o livro (se ainda não estiver na nuvem). Devolve o livro da nuvem. */
export async function uploadBook(api: TgApi, name: string, format: string, bytes: Uint8Array, onProgress?: (fraction: number) => void): Promise<{ book: CloudBook; existed: boolean }> {
  if (bytes.byteLength > MAX_FILE_BYTES) throw new Error('Arquivo maior que 2 GB: o Telegram não aceita.')
  const key = await bookKey(name, bytes.byteLength)
  const existing = (await listCloudBooks(api)).find((b) => b.key === key)
  if (existing) return { book: existing, existed: true }
  const message = await api.upload(name, bytes, bookCaption(key, name, bytes.byteLength, format), onProgress)
  return { book: { key, name, size: bytes.byteLength, format, messageId: message.id, date: message.date * 1000 }, existed: false }
}

export async function downloadBook(api: TgApi, book: CloudBook, onProgress?: (fraction: number) => void): Promise<Uint8Array> {
  return api.download(book.messageId, onProgress)
}

/** Apaga o livro da nuvem (só as mensagens dele). */
export async function removeCloudBook(api: TgApi, key: string) {
  const ids: number[] = []
  for (const m of await bookMessages(api)) if ((await parseBookMessage(m, api.anyBook))?.key === key) ids.push(m.id)
  if (ids.length) await api.remove(ids)
}

/**
 * Arquivos de posições na conversa, do mais novo para o mais antigo. A busca por texto do Telegram
 * pode demorar a enxergar uma mensagem recém-enviada; por isso as mensagens com arquivo mais recentes
 * também são olhadas (assim a posição gravada há pouco é encontrada, e as dos outros livros não somem).
 */
async function positionMessages(api: TgApi): Promise<TgMessage[]> {
  const [found, recent] = await Promise.all([api.search(POSITIONS_TAG), api.files ? api.files(100) : Promise.resolve([])])
  const seen = new Set<number>()
  return [...found, ...recent]
    .filter((m) => m.caption.includes(POSITIONS_TAG) && (!m.fileName || m.fileName === POSITIONS_FILE) && !seen.has(m.id) && !!seen.add(m.id))
    .sort((a, b) => b.date - a.date || b.id - a.id)
}

export async function readPositions(api: TgApi): Promise<Record<string, SyncEntry>> {
  const latest = (await positionMessages(api))[0]
  if (!latest) return {}
  return parseSync(new TextDecoder().decode(await api.download(latest.id)))
}

/**
 * Grava a posição deste aparelho: lê o arquivinho, junta (vale a mais recente de cada livro),
 * envia o novo e apaga os antigos. Não faz nada se a posição daqui não for a mais recente.
 */
export async function pushPositionTo(api: TgApi, key: string, entry: SyncEntry): Promise<boolean> {
  const old = await positionMessages(api)
  const books = old[0] ? parseSync(new TextDecoder().decode(await api.download(old[0].id))) : {}
  if (!mergeSync(books, key, entry)) return false
  const caption = `${POSITIONS_TAG}\nPosições de leitura do Alexandria (não apague).`
  await api.upload(POSITIONS_FILE, new TextEncoder().encode(serializeSync(books)), caption)
  if (old.length) await api.remove(old.map((m) => m.id))
  return true
}

export async function pullPositionFrom(api: TgApi, key: string): Promise<SyncEntry | null> {
  return (await readPositions(api))[key] ?? null
}

/** Junta várias posições de uma vez (ao trazer a nuvem das Mensagens salvas para um grupo). */
export async function mergePositionsInto(api: TgApi, incoming: Record<string, SyncEntry>): Promise<boolean> {
  const old = await positionMessages(api)
  const books = old[0] ? parseSync(new TextDecoder().decode(await api.download(old[0].id))) : {}
  let changed = false
  for (const [key, entry] of Object.entries(incoming)) changed = mergeSync(books, key, entry) || changed
  if (!changed) return false
  const caption = `${POSITIONS_TAG}\nPosições de leitura do Alexandria (não apague).`
  await api.upload(POSITIONS_FILE, new TextEncoder().encode(serializeSync(books)), caption)
  if (old.length) await api.remove(old.map((m) => m.id))
  return true
}

/**
 * Copia os livros de uma conversa da nuvem (as Mensagens salvas) para outra (o grupo): encaminha só
 * os que ainda não estão lá, e junta as posições. As mensagens originais ficam onde estavam.
 */
export async function copyCloud(from: TgApi, to: TgApi, forward: (ids: number[]) => Promise<void>): Promise<{ books: number; positions: boolean }> {
  const there = new Set((await listCloudBooks(to)).map((b) => b.key))
  const missing = (await listCloudBooks(from)).filter((b) => !there.has(b.key))
  // Em lotes de 50, um por vez (o Telegram não gosta de rajadas).
  for (let i = 0; i < missing.length; i += 50) await forward(missing.slice(i, i + 50).map((b) => b.messageId))
  const positions = await mergePositionsInto(to, await readPositions(from))
  return { books: missing.length, positions }
}

// ---------- mensagens patrocinadas (regra 3.3 dos termos da API do Telegram) ----------

/** Anúncio oficial do Telegram para a conversa (mostrado no fim da lista "Na nuvem"). */
export interface SponsoredItem {
  /** random_id em base64 (para registrar a visualização e o clique). */
  id: string
  title: string
  message: string
  url: string
  buttonText: string
  recommended: boolean
  sponsorInfo?: string
  additionalInfo?: string
}

/** Endereços do próprio Telegram abrem sem pedir confirmação (como manda a documentação). */
export function isTelegramUrl(url: string): boolean {
  try {
    return /(^|\.)(telegram\.(org|me|dog)|t\.me|te\.?legra\.ph|graph\.org|fragment\.com|telesco\.pe)$/.test(new URL(url).hostname)
  } catch {
    return false
  }
}
