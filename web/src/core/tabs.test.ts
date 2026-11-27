import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { MAIN_TAB, newTabId, normalizeTabs, tabOf } from './tabs'
import { closeDb, DB_NAME } from '../storage/db'
import { addBook, allItems, moveTabItems, setItemTab, setPref } from '../storage/library'
import { getTabs, saveTabs } from '../storage/tabs'
import { isVideoMessage, listVideos, formatDuration, type TgApi } from '../cloud/telegramCloud'
import { Blob as NodeBlob } from 'node:buffer'

const blob = (text: string) => new NodeBlob([text]) as unknown as Blob
const group = { id: '777', accessHash: '1', title: 'Biblioteca' }

beforeEach(async () => {
  await closeDb()
  await new Promise<void>((resolve) => {
    const r = indexedDB.deleteDatabase(DB_NAME)
    r.onsuccess = r.onerror = r.onblocked = () => resolve()
  })
})

describe('abas', () => {
  it('a aba principal sempre existe, vem primeiro e herda o grupo de antes das abas', () => {
    expect(normalizeTabs(null)).toEqual([{ id: MAIN_TAB, name: 'Livros', type: 'livros', chat: null }])
    expect(normalizeTabs(undefined, group)[0].chat).toEqual(group)
    const tabs = normalizeTabs([
      { id: 'filmes', name: 'Filmes', type: 'filmes', chat: group },
      { id: 'livros', name: 'Meus livros', type: 'cursos', chat: null },
      { id: 'filmes', name: 'repetida', type: 'livros', chat: null },
      { id: '', name: 'sem id', type: 'livros', chat: null },
      { id: 'x', name: '  ', type: 'esquisito' as never, chat: { id: '' } as never }
    ])
    expect(tabs.map((t) => [t.id, t.name, t.type, !!t.chat])).toEqual([
      ['livros', 'Meus livros', 'livros', false],
      ['filmes', 'Filmes', 'filmes', true],
      ['x', 'Livros', 'livros', false]
    ])
  })

  it('códigos de aba únicos, sem acento', () => {
    expect(newTabId('Filmes da Família', [])).toBe('filmes-da-familia')
    expect(newTabId('Filmes', ['filmes'])).toBe('filmes-2')
    expect(newTabId('Livros', [])).toBe('livros-2')
    expect(newTabId('!!!', [])).toBe('aba')
  })

  it('livros ficam na aba onde entraram; apagar a aba devolve os livros para a principal', async () => {
    await saveTabs([...(await getTabs()), { id: 'hqs', name: 'HQs', type: 'livros', chat: null }])
    const { item: a } = await addBook('Dom Casmurro.txt', 'txt', blob('a'))
    const { item: b } = await addBook('Watchmen.cbz', 'cbz', blob('bb'), Date.now(), 'hqs')
    expect(tabOf(a)).toBe(MAIN_TAB)
    expect(tabOf(b)).toBe('hqs')
    await setItemTab(a.id, 'hqs')
    expect((await allItems()).every((i) => tabOf(i) === 'hqs')).toBe(true)
    expect(await moveTabItems('hqs')).toBe(2)
    expect((await allItems()).every((i) => tabOf(i) === MAIN_TAB)).toBe(true)
  })

  it('o grupo escolhido antes das abas passa a ser o da aba principal', async () => {
    await setPref('telegram', { apiId: 1, apiHash: 'x', session: 's', name: 'Eu', chat: group })
    expect((await getTabs())[0]).toMatchObject({ id: MAIN_TAB, chat: group })
  })
})

describe('vídeos do grupo', () => {
  it('lista vídeos enviados como vídeo ou como arquivo, do mais novo para o mais antigo', async () => {
    const api: TgApi = {
      search: async () => [],
      upload: async () => { throw new Error('não') },
      download: async () => new Uint8Array(),
      remove: async () => {},
      videos: async () => [
        { id: 1, caption: '', fileName: 'Aula 01.mp4', size: 100, date: 10, mime: 'video/mp4', duration: 754, topic: 5, sender: 'Bia' },
        { id: 2, caption: 'Filme', size: 900, date: 30, mime: 'video/x-matroska' },
        { id: 3, caption: '', fileName: 'apostila.pdf', size: 50, date: 20, mime: 'application/pdf' },
        { id: 4, caption: '', fileName: 'O Filme.MKV', size: 800, date: 20 }
      ]
    }
    const videos = await listVideos(api)
    expect(videos.map((v) => v.name)).toEqual(['Filme', 'O Filme.MKV', 'Aula 01.mp4'])
    expect(videos[2]).toMatchObject({ duration: 754, topic: 5, sender: 'Bia' })
    expect(isVideoMessage({ id: 9, caption: '', fileName: 'x.mp4', size: 0, date: 0 })).toBe(false)
    expect(formatDuration(754)).toBe('12:34')
    expect(formatDuration(3 * 3600 + 5)).toBe('3:00:05')
    expect(formatDuration(0)).toBe('')
  })
})
