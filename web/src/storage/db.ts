/**
 * Banco do site no IndexedDB do navegador (equivalente ao SQLite do app, versão 8). Os arquivos dos
 * livros e as capas ficam como Blobs; nada sai do computador.
 */
export const DB_NAME = 'alexandria'
export const DB_VERSION = 2

export type StoreName = 'items' | 'files' | 'covers' | 'notes' | 'terms' | 'readingDays' | 'readingLog' | 'prefs' | 'bookmarks'
export const STORES: StoreName[] = ['items', 'files', 'covers', 'notes', 'terms', 'readingDays', 'readingLog', 'prefs', 'bookmarks']

let opening: Promise<IDBDatabase> | null = null

export function openDb(): Promise<IDBDatabase> {
  opening ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      // Migrações futuras entram aqui, por versão, sempre preservando os dados (como o onUpgrade do app).
      if (!db.objectStoreNames.contains('items')) db.createObjectStore('items', { keyPath: 'id', autoIncrement: true })
      if (!db.objectStoreNames.contains('files')) db.createObjectStore('files')
      if (!db.objectStoreNames.contains('covers')) db.createObjectStore('covers')
      if (!db.objectStoreNames.contains('notes')) db.createObjectStore('notes', { keyPath: 'id', autoIncrement: true }).createIndex('itemId', 'itemId')
      if (!db.objectStoreNames.contains('terms')) db.createObjectStore('terms', { keyPath: 'id', autoIncrement: true }).createIndex('itemId', 'itemId')
      if (!db.objectStoreNames.contains('readingDays')) db.createObjectStore('readingDays')
      if (!db.objectStoreNames.contains('readingLog')) db.createObjectStore('readingLog').createIndex('itemId', 'itemId')
      if (!db.objectStoreNames.contains('prefs')) db.createObjectStore('prefs')
      // Versão 2: marcadores de página.
      if (!db.objectStoreNames.contains('bookmarks')) db.createObjectStore('bookmarks', { keyPath: 'id', autoIncrement: true }).createIndex('itemId', 'itemId')
    }
    request.onsuccess = () => {
      const db = request.result
      // Outra aba atualizou o banco: fecha esta conexão para não travar a atualização.
      db.onversionchange = () => {
        db.close()
        opening = null
      }
      resolve(db)
    }
    request.onerror = () => {
      opening = null
      reject(request.error)
    }
  })
  return opening
}

/** Fecha e esquece a conexão (testes e restauração de backup). */
export async function closeDb() {
  if (!opening) return
  const db = await opening.catch(() => null)
  db?.close()
  opening = null
}

export function done(request: IDBRequest): Promise<unknown> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

/** Roda `work` numa transação e espera ela terminar (tudo ou nada). */
export async function transaction<T>(stores: StoreName[], mode: IDBTransactionMode, work: (tx: IDBTransaction) => Promise<T> | T): Promise<T> {
  const db = await openDb()
  const tx = db.transaction(stores, mode)
  const finished = new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error ?? new Error('Transação cancelada'))
  })
  let result: T
  try {
    result = await work(tx)
  } catch (e) {
    try {
      tx.abort()
    } catch {
      // já terminou
    }
    throw e
  }
  await finished
  return result
}

export const get = <T>(tx: IDBTransaction, store: StoreName, key: IDBValidKey) => done(tx.objectStore(store).get(key)) as Promise<T | undefined>
export const getAll = <T>(tx: IDBTransaction, store: StoreName) => done(tx.objectStore(store).getAll()) as Promise<T[]>
export const getAllByIndex = <T>(tx: IDBTransaction, store: StoreName, index: string, key: IDBValidKey) =>
  done(tx.objectStore(store).index(index).getAll(key)) as Promise<T[]>
export const getAllKeys = (tx: IDBTransaction, store: StoreName) => done(tx.objectStore(store).getAllKeys()) as Promise<IDBValidKey[]>
export const put = (tx: IDBTransaction, store: StoreName, value: unknown, key?: IDBValidKey) => done(tx.objectStore(store).put(value, key))
export const del = (tx: IDBTransaction, store: StoreName, key: IDBValidKey | IDBKeyRange) => done(tx.objectStore(store).delete(key))
export const clear = (tx: IDBTransaction, store: StoreName) => done(tx.objectStore(store).clear())
