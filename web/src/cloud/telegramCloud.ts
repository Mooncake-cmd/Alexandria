/**
 * Nuvem do Alexandria nas Mensagens salvas do Telegram (a mesma lógica no site e no app).
 *
 * - Cada livro é uma mensagem com o arquivo e uma legenda marcada com #alexandria_livro e uma
 *   linha "alexandria:{...}" com o código do livro (nome + tamanho), o nome e o formato.
 * - As posições de leitura ficam num arquivinho JSON (o mesmo formato do gist do GitHub), numa
 *   mensagem marcada com #alexandria_posicoes; ao gravar, vai uma nova e as antigas são apagadas.
 *
 * Tudo passa pela interface [TgApi]: a ligação real com o Telegram (GramJS) fica em gramApi.ts e
 * os testes usam uma falsa. Só mexe nas mensagens do Alexandria (com as marcas acima).
 */
import { bookKey, mergeSync, parseSync, serializeSync, type SyncEntry } from '../storage/sync'

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
}

export interface TgApi {
  /** Mensagens das Mensagens salvas que contêm o texto (a marca do Alexandria). */
  search(tag: string): Promise<TgMessage[]>
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
    return { key: meta.k, name: meta.n, size: meta.s ?? message.size ?? 0, format: meta.f ?? '', messageId: message.id, date: message.date * 1000 }
  } catch {
    return null
  }
}

/** Livros da nuvem (o mais recente de cada um, se houver repetidos). */
export async function listCloudBooks(api: TgApi): Promise<CloudBook[]> {
  const books = new Map<string, CloudBook>()
  for (const message of await api.search(BOOK_TAG)) {
    const book = parseBookCaption(message)
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
  const ids = (await api.search(BOOK_TAG)).filter((m) => parseBookCaption(m)?.key === key).map((m) => m.id)
  if (ids.length) await api.remove(ids)
}

async function positionMessages(api: TgApi): Promise<TgMessage[]> {
  return (await api.search(POSITIONS_TAG)).filter((m) => m.caption.includes(POSITIONS_TAG)).sort((a, b) => b.date - a.date || b.id - a.id)
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
