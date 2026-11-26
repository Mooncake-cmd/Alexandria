import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { Blob as NodeBlob } from 'node:buffer'
import { closeDb, DB_NAME } from '../storage/db'
import { addBook, bookFile, getPref } from '../storage/library'
import { bookKey } from '../storage/sync'
import { BOOK_TAG, POSITIONS_TAG, bookCaption, listCloudBooks, parseBookCaption, pullPositionFrom, pushPositionTo, removeCloudBook, uploadBook, type TgApi, type TgMessage } from './telegramCloud'
import { cloudBooks, fetchFromCloud, pullPosition, pushPosition, saveTelegram, sendToCloud, setSyncProvider, setTelegramApiFactory, syncProvider } from './cloud'
import { exportBackup } from '../storage/backup'
import { strFromU8, unzipSync } from 'fflate'

/** Mensagens salvas de mentira. */
function fakeSaved() {
  const messages: (TgMessage & { bytes: Uint8Array })[] = []
  let id = 0
  let clock = 1_700_000_000
  const calls: string[] = []
  const api: TgApi = {
    async search(tag) {
      calls.push(`search ${tag}`)
      return messages.filter((m) => m.caption.includes(tag)).map(({ bytes: _b, ...m }) => m)
    },
    async upload(name, bytes, caption, onProgress) {
      calls.push(`upload ${name}`)
      onProgress?.(0.5)
      onProgress?.(1)
      const m = { id: ++id, caption, fileName: name, size: bytes.byteLength, date: ++clock, bytes: bytes.slice() }
      messages.push(m)
      const { bytes: _b, ...meta } = m
      return meta
    },
    async download(messageId) {
      calls.push(`download ${messageId}`)
      const m = messages.find((x) => x.id === messageId)
      if (!m) throw new Error('não achou')
      return m.bytes
    },
    async remove(ids) {
      calls.push(`remove ${ids.join(',')}`)
      for (const i of ids) messages.splice(messages.findIndex((m) => m.id === i), 1)
    }
  }
  return { api, messages, calls }
}

const blob = (text: string) => new NodeBlob([text]) as unknown as Blob

beforeEach(async () => {
  await closeDb()
  await new Promise<void>((resolve) => {
    const r = indexedDB.deleteDatabase(DB_NAME)
    r.onsuccess = r.onerror = r.onblocked = () => resolve()
  })
  setTelegramApiFactory(null)
})

describe('nuvem nas Mensagens salvas', () => {
  it('legenda do livro vai e volta; mensagens que não são do Alexandria são ignoradas', () => {
    const caption = bookCaption('abc', 'Moby Dick.epub', 123, 'epub')
    expect(caption.startsWith(BOOK_TAG)).toBe(true)
    expect(parseBookCaption({ id: 7, caption, date: 10 })).toEqual({ key: 'abc', name: 'Moby Dick.epub', size: 123, format: 'epub', messageId: 7, date: 10_000 })
    expect(parseBookCaption({ id: 8, caption: 'foto da viagem #alexandria_livro', date: 1 })).toBeNull()
    expect(parseBookCaption({ id: 9, caption: 'qualquer coisa', date: 1 })).toBeNull()
  })

  it('envia uma vez só, lista, baixa igual e apaga só o livro', async () => {
    const { api, messages } = fakeSaved()
    const bytes = new TextEncoder().encode('conteúdo do livro')
    const first = await uploadBook(api, 'Moby Dick.epub', 'epub', bytes)
    expect(first.existed).toBe(false)
    expect((await uploadBook(api, 'Moby Dick.epub', 'epub', bytes)).existed).toBe(true)
    expect(messages.length).toBe(1)
    await uploadBook(api, 'Dom Casmurro.txt', 'txt', new TextEncoder().encode('outro'))
    const list = await listCloudBooks(api)
    expect(list.map((b) => b.name)).toEqual(['Dom Casmurro.txt', 'Moby Dick.epub'])
    expect(list[1].key).toBe(await bookKey('Moby Dick.epub', bytes.byteLength))
    expect(new TextDecoder().decode(await api.download(list[1].messageId))).toBe('conteúdo do livro')
    await removeCloudBook(api, list[0].key)
    expect((await listCloudBooks(api)).map((b) => b.name)).toEqual(['Moby Dick.epub'])
  })

  it('posições: junta pela mais recente e deixa só um arquivinho de controle', async () => {
    const { api, messages } = fakeSaved()
    await pushPositionTo(api, 'k1', { format: 'epub', position: 10, exact: 1000, pageCount: 365, updated: 100, device: 'celular' })
    await pushPositionTo(api, 'k2', { format: 'pdf', position: 3, exact: 3, pageCount: 90, updated: 200, device: 'computador' })
    expect(await pushPositionTo(api, 'k1', { format: 'epub', position: 5, exact: 500, pageCount: 365, updated: 50, device: 'computador' })).toBe(false)
    expect(messages.filter((m) => m.caption.includes(POSITIONS_TAG)).length).toBe(1)
    expect((await pullPositionFrom(api, 'k1'))?.position).toBe(10)
    expect((await pullPositionFrom(api, 'k2'))?.device).toBe('computador')
    expect(await pullPositionFrom(api, 'nada')).toBeNull()
  })

  it('site: envia da biblioteca, baixa em outro "aparelho", posição pelo Telegram; sessão fora do backup', async () => {
    const saved = fakeSaved()
    setTelegramApiFactory(async () => saved.api)
    await saveTelegram({ apiId: 123, apiHash: 'hash', session: 'sessao-secreta', name: 'Leitora' })
    expect(await syncProvider()).toBe('telegram')

    const { item } = await addBook('Moby Dick.epub', 'epub', blob('livro inteiro'), 1000)
    expect((await sendToCloud(item, 'Moby Dick')).existed).toBe(false)
    const [cloudBook] = await cloudBooks()
    expect(cloudBook.name).toBe('Moby Dick.epub')

    await pushPosition('Moby Dick.epub', item.fileSize, { format: 'epub', position: 438, exact: 43_836, pageCount: 365, updated: 5000, device: 'computador' })
    expect((await pullPosition('Moby Dick.epub', item.fileSize))?.exact).toBe(43_836)

    // "Outro aparelho": biblioteca vazia, baixa da nuvem.
    await closeDb()
    await new Promise<void>((resolve) => {
      const r = indexedDB.deleteDatabase(DB_NAME)
      r.onsuccess = r.onerror = r.onblocked = () => resolve()
    })
    await saveTelegram({ apiId: 123, apiHash: 'hash', session: 'outra-sessao', name: 'Leitora' })
    const fetched = await fetchFromCloud(cloudBook, 'Moby Dick')
    expect(fetched.name).toBe('Moby Dick.epub')
    expect(new TextDecoder().decode(await (await bookFile(fetched.id))!.arrayBuffer())).toBe('livro inteiro')

    const backup = unzipSync(new Uint8Array(await (await exportBackup(false)).arrayBuffer()))
    expect(strFromU8(backup['alexandria.json'])).not.toContain('outra-sessao')
  })

  it('com GitHub e Telegram, vale o escolhido em Ajustes', async () => {
    await saveTelegram({ apiId: 1, apiHash: 'h', session: 's', name: 'n' })
    const { setPref } = await import('../storage/library')
    await setPref('sync', { token: 't', gistId: 'g', login: 'l', lastSync: 0 })
    await setSyncProvider('github')
    expect(await syncProvider()).toBe('github')
    await setSyncProvider('telegram')
    expect(await syncProvider()).toBe('telegram')
    expect(await getPref('syncProvider')).toBe('telegram')
  })
})
