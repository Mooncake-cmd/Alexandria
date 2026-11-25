import { strFromU8, strToU8, unzipSync, zipSync, type Zippable } from 'fflate'
import { clear, closeDb, get, getAll, getAllKeys, put, transaction, type StoreName } from './db'
import { notifyLibraryChanged } from './library'

/**
 * Backup da biblioteca num arquivo .zip: dados (livros, posições, prateleiras, anotações,
 * glossário, marcadores, tempo de leitura, preferências), capas e, se pedido, os próprios livros.
 */
const DATA_STORES: StoreName[] = ['items', 'notes', 'terms', 'readingDays', 'readingLog', 'prefs', 'bookmarks']
const BLOB_STORES: StoreName[] = ['covers', 'files']
export const BACKUP_FORMAT = 'alexandria-backup'
export const BACKUP_VERSION = 1

interface Dump {
  format: string
  version: number
  createdAt: number
  stores: Record<string, { keys: IDBValidKey[]; values: unknown[] }>
}

export async function exportBackup(includeBooks: boolean, now = Date.now()): Promise<Blob> {
  const stores = [...DATA_STORES, 'covers', ...(includeBooks ? (['files'] as StoreName[]) : [])] as StoreName[]
  const raw = await transaction(stores, 'readonly', async (tx) => {
    const out: Record<string, { keys: IDBValidKey[]; values: unknown[] }> = {}
    for (const store of stores) out[store] = { keys: await getAllKeys(tx, store), values: await getAll(tx, store) }
    // O token da sincronização (GitHub) não vai para o backup: o arquivo pode ser compartilhado.
    const prefs = out.prefs
    if (prefs) {
      const keep = prefs.keys.map((k, i) => (k === 'sync' ? -1 : i)).filter((i) => i >= 0)
      out.prefs = { keys: keep.map((i) => prefs.keys[i]), values: keep.map((i) => prefs.values[i]) }
    }
    return out
  })
  const files: Zippable = {}
  const dump: Dump = { format: BACKUP_FORMAT, version: BACKUP_VERSION, createdAt: now, stores: {} }
  for (const store of stores) {
    const { keys, values } = raw[store]
    if (BLOB_STORES.includes(store)) {
      // Blobs vão como arquivos; o JSON guarda a chave e o tipo de cada um.
      const meta: unknown[] = []
      for (let i = 0; i < keys.length; i++) {
        const blob = values[i] as Blob
        const path = `${store}/${i}`
        files[path] = [new Uint8Array(await blob.arrayBuffer()), { level: store === 'files' ? 0 : 6 }]
        meta.push({ path, type: blob.type })
      }
      dump.stores[store] = { keys, values: meta }
    } else {
      dump.stores[store] = { keys, values }
    }
  }
  files['alexandria.json'] = strToU8(JSON.stringify(dump))
  return new Blob([zipSync(files)], { type: 'application/zip' })
}

export interface RestoreSummary {
  books: number
  notes: number
  withFiles: number
}

/** Substitui tudo o que está no navegador pelo conteúdo do backup. */
export async function importBackup(file: Blob): Promise<RestoreSummary> {
  const entries = unzipSync(new Uint8Array(await file.arrayBuffer()))
  const json = entries['alexandria.json']
  if (!json) throw new Error('Este arquivo não é um backup do Alexandria.')
  const dump = JSON.parse(strFromU8(json)) as Dump
  if (dump.format !== BACKUP_FORMAT) throw new Error('Este arquivo não é um backup do Alexandria.')
  if (dump.version > BACKUP_VERSION) throw new Error('Backup feito numa versão mais nova do Alexandria. Atualize a página e tente de novo.')
  const all: StoreName[] = [...DATA_STORES, ...BLOB_STORES]
  await transaction(all, 'readwrite', async (tx) => {
    // A sincronização configurada neste navegador continua depois de restaurar.
    const sync = await get(tx, 'prefs', 'sync')
    for (const store of all) await clear(tx, store)
    if (sync) await put(tx, 'prefs', sync, 'sync')
    for (const [store, { keys, values }] of Object.entries(dump.stores)) {
      if (!all.includes(store as StoreName)) continue
      for (let i = 0; i < keys.length; i++) {
        if (BLOB_STORES.includes(store as StoreName)) {
          const meta = values[i] as { path: string; type: string }
          const bytes = entries[meta.path]
          if (bytes) await put(tx, store as StoreName, new Blob([bytes], { type: meta.type }), keys[i])
        } else if (store === 'items' || store === 'notes' || store === 'terms' || store === 'bookmarks') {
          await put(tx, store, values[i]) // chave dentro do objeto (id)
        } else {
          await put(tx, store as StoreName, values[i], keys[i])
        }
      }
    }
  })
  await closeDb()
  notifyLibraryChanged()
  return {
    books: dump.stores.items?.keys.length ?? 0,
    notes: dump.stores.notes?.keys.length ?? 0,
    withFiles: dump.stores.files?.keys.length ?? 0
  }
}

/** Espaço usado pelo site e se o navegador promete não apagar os dados sozinho. */
export async function storageInfo(): Promise<{ usage: number; quota: number; persisted: boolean }> {
  const estimate = (await navigator.storage?.estimate?.()) ?? {}
  const persisted = (await navigator.storage?.persisted?.()) ?? false
  return { usage: estimate.usage ?? 0, quota: estimate.quota ?? 0, persisted }
}

/** Pede ao navegador para não apagar os dados do site sozinho (ex.: com pouco espaço em disco). */
export async function requestPersistence(): Promise<boolean> {
  return (await navigator.storage?.persist?.()) ?? false
}
