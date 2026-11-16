import { byPosition, nextBookmarkName, type Bookmark } from '../core/bookmarks'
import { clear, del, get, getAll, getAllByIndex, getAllKeys, put, transaction, type StoreName } from './db'
import { spreadRemoval } from '../core/readingTime'
import { resolveShelf, shelfCompare } from '../core/library'
import type { GlossaryTerm } from '../core/glossary'

/** Livro da biblioteca. Mesmos campos do LibraryItem do app (sem o uri: o arquivo fica no navegador). */
export interface LibraryItem {
  id: number
  name: string
  format: string
  addedAt: number
  /** Página (PDF, DJVU, quadrinhos) ou fração de 0 a 10000 (textos). */
  position: number
  pageCount: number
  openedAt: number
  finishedAt: number
  shelf: string
  readingSeconds: number
  fileSize: number
  /** Quando o arquivo foi apagado para liberar espaço (0 = ainda tem). Capa e nome continuam na Estante. */
  archivedAt: number
}

export interface Note {
  id: number
  itemId: number
  excerpt: string
  text: string
  position: number
  createdAt: number
}

export const isFinished = (item: LibraryItem) => item.finishedAt > 0
export const isArchived = (item: LibraryItem) => item.archivedAt > 0

/** Avisa as telas que a biblioteca mudou (nesta aba e nas outras). */
const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('alexandria') : null
const listeners = new Set<() => void>()
export function onLibraryChange(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
function changed() {
  listeners.forEach((l) => l())
  channel?.postMessage('changed')
}
channel?.addEventListener('message', () => listeners.forEach((l) => l()))

const touch = (item: LibraryItem) => Math.max(item.openedAt, item.addedAt)

/** Todos os livros, do lido mais recentemente ao mais antigo (como o app). */
export async function allItems(): Promise<LibraryItem[]> {
  const items = await transaction(['items'], 'readonly', (tx) => getAll<LibraryItem>(tx, 'items'))
  return items.sort((a, b) => touch(b) - touch(a) || b.id - a.id)
}

export const findItem = (id: number) => transaction(['items'], 'readonly', (tx) => get<LibraryItem>(tx, 'items', id))

export const bookFile = (id: number) => transaction(['files'], 'readonly', (tx) => get<Blob>(tx, 'files', id))

export interface AddResult {
  item: LibraryItem
  /** O mesmo livro (nome e tamanho) já estava na biblioteca. */
  existing: boolean
  /** Era um livro da Estante sem arquivo, que voltou a ter. */
  restored: boolean
}

/**
 * Guarda um arquivo na biblioteca. O mesmo livro (mesmo nome e tamanho) não é duplicado: volta o
 * que já existe e, se estava sem arquivo (espaço liberado), o arquivo volta para ele.
 */
export async function addBook(name: string, format: string, file: Blob, now = Date.now()): Promise<AddResult> {
  const result = await transaction(['items', 'files'], 'readwrite', async (tx) => {
    const items = await getAll<LibraryItem>(tx, 'items')
    const same = file.size > 0 ? items.filter((i) => i.name === name && i.fileSize === file.size).sort((a, b) => a.id - b.id)[0] : undefined
    if (same) {
      const restored = isArchived(same)
      const item = { ...same, format, archivedAt: 0 }
      await put(tx, 'items', item)
      await put(tx, 'files', file, item.id)
      return { item, existing: true, restored }
    }
    const fresh: Omit<LibraryItem, 'id'> = {
      name, format, addedAt: now, position: 0, pageCount: 0, openedAt: 0, finishedAt: 0, shelf: '', readingSeconds: 0, fileSize: file.size, archivedAt: 0
    }
    const id = (await put(tx, 'items', fresh)) as number
    await put(tx, 'files', file, id)
    return { item: { ...fresh, id }, existing: false, restored: false }
  })
  changed()
  return result
}

async function update(id: number, change: (item: LibraryItem) => Partial<LibraryItem>) {
  await transaction(['items'], 'readwrite', async (tx) => {
    const item = await get<LibraryItem>(tx, 'items', id)
    if (item) await put(tx, 'items', { ...item, ...change(item) })
  })
  changed()
}

export const updateProgress = (id: number, position: number, pageCount = 0, now = Date.now()) =>
  update(id, () => ({ position, openedAt: now, ...(pageCount > 0 ? { pageCount } : {}) }))
export const updatePageCount = (id: number, pageCount: number) => update(id, () => ({ pageCount }))
export const setFinished = (id: number, finished: boolean, now = Date.now()) => update(id, () => ({ finishedAt: finished ? now : 0 }))
export const setShelf = (id: number, shelf: string) => update(id, () => ({ shelf }))

export async function shelves(): Promise<string[]> {
  return [...new Set((await allItems()).map((i) => i.shelf).filter(Boolean))].sort(shelfCompare)
}

/** Renomeia uma prateleira; se o nome novo já existe (ignorando maiúsculas), junta as duas. */
export async function renameShelf(from: string, typed: string): Promise<string> {
  const target = resolveShelf(typed, (await shelves()).filter((s) => s !== from))
  await transaction(['items'], 'readwrite', async (tx) => {
    for (const item of await getAll<LibraryItem>(tx, 'items')) if (item.shelf === from) await put(tx, 'items', { ...item, shelf: target })
  })
  changed()
  return target
}

/** Liberar espaço de um livro concluído: apaga o arquivo e mantém o livro (capa, nome, datas…) na Estante. */
export async function archive(id: number, now = Date.now()) {
  await transaction(['items', 'files'], 'readwrite', async (tx) => {
    const item = await get<LibraryItem>(tx, 'items', id)
    if (!item) return
    await del(tx, 'files', id)
    await put(tx, 'items', { ...item, archivedAt: now })
  })
  changed()
}

/** Remove o livro, o arquivo, as capas, as anotações e o glossário. O tempo lido continua no total. */
export async function removeItem(id: number) {
  await transaction(['items', 'files', 'covers', 'notes', 'terms', 'readingLog', 'bookmarks'], 'readwrite', async (tx) => {
    await del(tx, 'items', id)
    await del(tx, 'files', id)
    await del(tx, 'covers', `${id}`)
    await del(tx, 'covers', `${id}.custom`)
    for (const n of await getAllByIndex<Note>(tx, 'notes', 'itemId', id)) await del(tx, 'notes', n.id)
    for (const t of await getAllByIndex<GlossaryTerm>(tx, 'terms', 'itemId', id)) await del(tx, 'terms', t.id)
    for (const key of await logKeysOf(tx, id)) await del(tx, 'readingLog', key)
    for (const b of await getAllByIndex<Bookmark>(tx, 'bookmarks', 'itemId', id)) await del(tx, 'bookmarks', b.id)
  })
  changed()
}

// ---------- tempo de leitura ----------

interface LogRow { itemId: number; day: string; seconds: number }
const logKey = (itemId: number, day: string) => [itemId, day]

async function logKeysOf(tx: IDBTransaction, itemId: number): Promise<IDBValidKey[]> {
  return (await getAllByIndex<LogRow>(tx, 'readingLog', 'itemId', itemId)).map((r) => logKey(r.itemId, r.day))
}

export async function addReadingTime(itemId: number, day: string, seconds: number) {
  if (seconds <= 0) return
  await transaction(['items', 'readingDays', 'readingLog'], 'readwrite', async (tx) => {
    const item = await get<LibraryItem>(tx, 'items', itemId)
    if (item) await put(tx, 'items', { ...item, readingSeconds: item.readingSeconds + seconds })
    await put(tx, 'readingDays', ((await get<number>(tx, 'readingDays', day)) ?? 0) + seconds, day)
    const row = await get<LogRow>(tx, 'readingLog', logKey(itemId, day))
    await put(tx, 'readingLog', { itemId, day, seconds: (row?.seconds ?? 0) + seconds }, logKey(itemId, day))
  })
  changed()
}

export async function readingDays(): Promise<Record<string, number>> {
  return transaction(['readingDays'], 'readonly', async (tx) => {
    const keys = (await getAllKeys(tx, 'readingDays')) as string[]
    const values = await getAll<number>(tx, 'readingDays')
    return Object.fromEntries(keys.map((k, i) => [k, values[i]]))
  })
}

/** Zera tempo e sequência, sem mexer em livros, progresso, Estante, anotações e glossário. */
export async function clearStatistics() {
  await transaction(['items', 'readingDays', 'readingLog'], 'readwrite', async (tx) => {
    await clear(tx, 'readingDays')
    await clear(tx, 'readingLog')
    for (const item of await getAll<LibraryItem>(tx, 'items')) if (item.readingSeconds) await put(tx, 'items', { ...item, readingSeconds: 0 })
  })
  changed()
}

/**
 * Apaga o tempo de um livro e desconta dos dias em que ele foi lido (total, calendário e
 * sequência). Tempo sem dia registrado sai dos dias sem livro conhecido, na proporção. Porte de clearBookTime.
 */
export async function clearBookTime(id: number): Promise<number> {
  const removed = await transaction(['items', 'readingDays', 'readingLog'], 'readwrite', async (tx) => {
    const item = await get<LibraryItem>(tx, 'items', id)
    const total = item?.readingSeconds ?? 0
    const rows = await getAllByIndex<LogRow>(tx, 'readingLog', 'itemId', id)
    const days = new Map<string, number>()
    for (const key of (await getAllKeys(tx, 'readingDays')) as string[]) days.set(key, (await get<number>(tx, 'readingDays', key)) ?? 0)
    for (const row of rows) {
      days.set(row.day, Math.max(0, (days.get(row.day) ?? 0) - row.seconds))
      await del(tx, 'readingLog', logKey(row.itemId, row.day))
    }
    const legacy = Math.max(0, total - rows.reduce((s, r) => s + r.seconds, 0))
    if (legacy > 0) {
      const logged = new Map<string, number>()
      for (const row of await getAll<LogRow>(tx, 'readingLog')) logged.set(row.day, (logged.get(row.day) ?? 0) + row.seconds)
      const unattributed = Object.fromEntries([...days].map(([day, s]) => [day, s - (logged.get(day) ?? 0)]))
      for (const [day, s] of Object.entries(spreadRemoval(unattributed, legacy))) days.set(day, Math.max(0, (days.get(day) ?? 0) - s))
    }
    for (const [day, seconds] of days) {
      if (seconds <= 0) await del(tx, 'readingDays', day)
      else await put(tx, 'readingDays', seconds, day)
    }
    if (item) await put(tx, 'items', { ...item, readingSeconds: 0 })
    return total
  })
  changed()
  return removed
}

// ---------- anotações e glossário ----------

export const notesFor = (itemId: number) => transaction(['notes'], 'readonly', (tx) => getAllByIndex<Note>(tx, 'notes', 'itemId', itemId))
export async function allNotes(): Promise<Note[]> {
  const notes = await transaction(['notes'], 'readonly', (tx) => getAll<Note>(tx, 'notes'))
  return notes.sort((a, b) => b.createdAt - a.createdAt || b.id - a.id)
}
export async function addNote(itemId: number, excerpt: string, text: string, position: number, now = Date.now()): Promise<number> {
  const id = await transaction(['notes'], 'readwrite', (tx) => put(tx, 'notes', { itemId, excerpt, text, position, createdAt: now }))
  changed()
  return id as number
}
export async function updateNote(id: number, text: string) {
  await transaction(['notes'], 'readwrite', async (tx) => {
    const note = await get<Note>(tx, 'notes', id)
    if (note) await put(tx, 'notes', { ...note, text })
  })
  changed()
}
export async function removeNote(id: number) {
  await transaction(['notes'], 'readwrite', (tx) => del(tx, 'notes', id))
  changed()
}

// ---------- marcadores ----------

export async function bookmarksFor(itemId: number): Promise<Bookmark[]> {
  const marks = await transaction(['bookmarks'], 'readonly', (tx) => getAllByIndex<Bookmark>(tx, 'bookmarks', 'itemId', itemId))
  return marks.sort(byPosition)
}
/** Cria um marcador com o próximo nome livre ("Marcador 1", "Marcador 2"…). */
export async function addBookmark(itemId: number, position: number, now = Date.now()): Promise<Bookmark> {
  const mark = await transaction(['bookmarks'], 'readwrite', async (tx) => {
    const names = (await getAllByIndex<Bookmark>(tx, 'bookmarks', 'itemId', itemId)).map((b) => b.name)
    const value = { itemId, name: nextBookmarkName(names), position, createdAt: now }
    const id = (await put(tx, 'bookmarks', value)) as number
    return { id, ...value }
  })
  changed()
  return mark
}
export async function renameBookmark(id: number, name: string) {
  await transaction(['bookmarks'], 'readwrite', async (tx) => {
    const mark = await get<Bookmark>(tx, 'bookmarks', id)
    if (mark && name.trim()) await put(tx, 'bookmarks', { ...mark, name: name.trim() })
  })
  changed()
}
export async function removeBookmark(id: number) {
  await transaction(['bookmarks'], 'readwrite', (tx) => del(tx, 'bookmarks', id))
  changed()
}

export const termsFor = (itemId: number) => transaction(['terms'], 'readonly', (tx) => getAllByIndex<GlossaryTerm>(tx, 'terms', 'itemId', itemId))
export const allTerms = () => transaction(['terms'], 'readonly', (tx) => getAll<GlossaryTerm>(tx, 'terms'))

/** Salva um termo; o mesmo termo (sem diferenciar maiúsculas e acentos) no mesmo livro é atualizado. */
export async function saveTerm(itemId: number, term: string, definition: string, fold: (s: string) => string, now = Date.now()): Promise<number> {
  const id = await transaction(['terms'], 'readwrite', async (tx) => {
    const key = fold(term.trim())
    const same = (await getAllByIndex<GlossaryTerm>(tx, 'terms', 'itemId', itemId)).find((t) => fold(t.term.trim()) === key)
    if (same) {
      await put(tx, 'terms', { ...same, term: term.trim(), definition })
      return same.id
    }
    return (await put(tx, 'terms', { itemId, term: term.trim(), definition, createdAt: now })) as number
  })
  changed()
  return id
}
export async function removeTerm(id: number) {
  await transaction(['terms'], 'readwrite', (tx) => del(tx, 'terms', id))
  changed()
}

// ---------- capas e preferências ----------

/** Capa: a personalizada (galeria) vence a automática. */
export async function coverOf(id: number): Promise<Blob | undefined> {
  return transaction(['covers'], 'readonly', async (tx) => (await get<Blob>(tx, 'covers', `${id}.custom`)) ?? get<Blob>(tx, 'covers', `${id}`))
}
export const hasCustomCover = async (id: number) => !!(await transaction(['covers'], 'readonly', (tx) => get<Blob>(tx, 'covers', `${id}.custom`)))
export async function setCover(id: number, blob: Blob | null, custom = false) {
  const key = custom ? `${id}.custom` : `${id}`
  await transaction(['covers'], 'readwrite', (tx) => (blob ? put(tx, 'covers', blob, key) : del(tx, 'covers', key)))
  changed()
}

export const getPref = <T>(key: string) => transaction(['prefs'], 'readonly', (tx) => get<T>(tx, 'prefs', key))
export const setPref = (key: string, value: unknown) => transaction(['prefs'], 'readwrite', (tx) => put(tx, 'prefs', value, key))

/** Todas as lojas, para o backup. */
export const ALL_STORES: StoreName[] = ['items', 'files', 'covers', 'notes', 'terms', 'readingDays', 'readingLog', 'prefs', 'bookmarks']
export { changed as notifyLibraryChanged }
