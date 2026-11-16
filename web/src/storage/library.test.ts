import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { Blob as NodeBlob } from 'node:buffer'
import { closeDb, DB_NAME } from './db'
import {
  addBook, addNote, addReadingTime, allItems, allNotes, archive, bookFile, clearBookTime, clearStatistics, coverOf, findItem,
  readingDays, removeItem, renameShelf, saveTerm, setCover, setFinished, setShelf, shelves, termsFor, updateProgress
} from './library'
import { exportBackup, importBackup } from './backup'
import { addBookmark, bookmarksFor, removeBookmark, renameBookmark } from './library'
import { fold } from '../core/text'

const blob = (text: string) => new NodeBlob([text]) as unknown as Blob
const text = async (b: Blob | undefined) => (b ? new TextDecoder().decode(await b.arrayBuffer()) : undefined)

beforeEach(async () => {
  await closeDb()
  await new Promise<void>((resolve) => {
    const r = indexedDB.deleteDatabase(DB_NAME)
    r.onsuccess = r.onerror = r.onblocked = () => resolve()
  })
})

// Mesmos comportamentos de LibraryDatabaseTest e IncomingBooksTest do app.
describe('biblioteca no navegador', () => {
  it('guarda, ordena pelo mais recente e não duplica o mesmo livro', async () => {
    const a = await addBook('Dom Casmurro.epub', 'epub', blob('livro A'), 1000)
    const b = await addBook('Manual.pdf', 'pdf', blob('livro B!'), 2000)
    expect(a.existing).toBe(false)
    expect((await allItems()).map((i) => i.name)).toEqual(['Manual.pdf', 'Dom Casmurro.epub'])
    await updateProgress(a.item.id, 2500, 10, 3000)
    expect((await allItems()).map((i) => i.name)).toEqual(['Dom Casmurro.epub', 'Manual.pdf'])
    const again = await addBook('Dom Casmurro.epub', 'epub', blob('livro A'))
    expect(again.existing).toBe(true)
    expect(again.item.id).toBe(a.item.id)
    expect(again.item.position).toBe(2500)
    expect((await allItems()).length).toBe(2)
    expect(await text(await bookFile(b.item.id))).toBe('livro B!')
  })

  it('liberar espaço mantém na Estante e o arquivo volta ao importar de novo', async () => {
    const { item } = await addBook('Duna.pdf', 'pdf', blob('duna'))
    await setFinished(item.id, true)
    await archive(item.id)
    expect(await bookFile(item.id)).toBeUndefined()
    expect((await findItem(item.id))!.archivedAt).toBeGreaterThan(0)
    const back = await addBook('Duna.pdf', 'pdf', blob('duna'))
    expect(back.restored).toBe(true)
    expect((await findItem(item.id))!.archivedAt).toBe(0)
    expect((await findItem(item.id))!.finishedAt).toBeGreaterThan(0)
    expect(await text(await bookFile(item.id))).toBe('duna')
  })

  it('prateleiras: mover, renomear e juntar', async () => {
    const a = (await addBook('A.txt', 'txt', blob('a'))).item
    const b = (await addBook('B.txt', 'txt', blob('bb'))).item
    await setShelf(a.id, 'Ficção')
    await setShelf(b.id, 'Estudos')
    expect(await shelves()).toEqual(['Estudos', 'Ficção'])
    expect(await renameShelf('Estudos', '  ficção ')).toBe('Ficção')
    expect(await shelves()).toEqual(['Ficção'])
  })

  it('remover apaga arquivo, capas, anotações e glossário', async () => {
    const { item } = await addBook('X.epub', 'epub', blob('x'))
    await setCover(item.id, blob('capa'))
    await addNote(item.id, 'trecho', 'nota', 10)
    await saveTerm(item.id, 'Arrakis', 'planeta', fold)
    await removeItem(item.id)
    expect(await findItem(item.id)).toBeUndefined()
    expect(await coverOf(item.id)).toBeUndefined()
    expect(await allNotes()).toEqual([])
    expect(await termsFor(item.id)).toEqual([])
  })

  it('capa personalizada vence a automática; glossário não duplica termos', async () => {
    const { item } = await addBook('Y.epub', 'epub', blob('y'))
    await setCover(item.id, blob('auto'))
    await setCover(item.id, blob('minha'), true)
    expect(await text(await coverOf(item.id))).toBe('minha')
    await setCover(item.id, null, true)
    expect(await text(await coverOf(item.id))).toBe('auto')
    await saveTerm(item.id, 'Arrakis', 'planeta', fold)
    await saveTerm(item.id, 'arrákis', 'planeta deserto', fold)
    const terms = await termsFor(item.id)
    expect(terms.length).toBe(1)
    expect(terms[0].definition).toBe('planeta deserto')
  })

  it('apagar o tempo de um livro baixa o total e os dias dele', async () => {
    const a = (await addBook('A.epub', 'epub', blob('a'))).item
    const b = (await addBook('B.epub', 'epub', blob('bb'))).item
    await addReadingTime(a.id, '2026-11-10', 300)
    await addReadingTime(a.id, '2026-11-11', 300)
    await addReadingTime(b.id, '2026-11-11', 1200)
    await addReadingTime(b.id, '2026-11-12', 600)
    expect(Object.values(await readingDays()).reduce((x, y) => x + y, 0)).toBe(2400)
    expect(await clearBookTime(a.id)).toBe(600)
    expect((await findItem(a.id))!.readingSeconds).toBe(0)
    expect((await findItem(b.id))!.readingSeconds).toBe(1800)
    expect(await readingDays()).toEqual({ '2026-11-11': 1200, '2026-11-12': 600 })
    await clearStatistics()
    expect(await readingDays()).toEqual({})
    expect((await findItem(b.id))!.readingSeconds).toBe(0)
  })

  it('backup restaura tudo num navegador vazio', async () => {
    const { item } = await addBook('Livro.txt', 'txt', blob('conteúdo'))
    await updateProgress(item.id, 4200)
    await setShelf(item.id, 'Clássicos')
    await setCover(item.id, blob('capa'))
    await addNote(item.id, 'trecho', 'minha nota', 4200)
    await saveTerm(item.id, 'Capitu', 'personagem', fold)
    await addReadingTime(item.id, '2026-11-15', 900)
    const backup = await exportBackup(true)
    // "Outro navegador": apaga tudo.
    await closeDb()
    await new Promise<void>((resolve) => {
      const r = indexedDB.deleteDatabase(DB_NAME)
      r.onsuccess = r.onerror = () => resolve()
    })
    expect(await allItems()).toEqual([])
    const summary = await importBackup(new NodeBlob([await backup.arrayBuffer()]) as unknown as Blob)
    expect(summary).toEqual({ books: 1, notes: 1, withFiles: 1 })
    const [restored] = await allItems()
    expect([restored.name, restored.position, restored.shelf, restored.readingSeconds]).toEqual(['Livro.txt', 4200, 'Clássicos', 900])
    expect(await text(await bookFile(restored.id))).toBe('conteúdo')
    expect(await text(await coverOf(restored.id))).toBe('capa')
    expect((await allNotes())[0].text).toBe('minha nota')
    expect((await termsFor(restored.id))[0].term).toBe('Capitu')
    expect(await readingDays()).toEqual({ '2026-11-15': 900 })
    await expect(importBackup(new NodeBlob(['não é zip']) as unknown as Blob)).rejects.toThrow()
  })
})

describe('marcadores no navegador', () => {
  it('cria com o próximo nome, ordena, renomeia, exclui e sai junto com o livro', async () => {
    const { item } = await addBook('Livro.pdf', 'pdf', blob('pdf'), 1000)
    const second = await addBookmark(item.id, 40)
    const first = await addBookmark(item.id, 12)
    expect([second.name, first.name]).toEqual(['Marcador 1', 'Marcador 2'])
    expect((await bookmarksFor(item.id)).map((b) => b.position)).toEqual([12, 40])
    await renameBookmark(first.id, '  Prefácio ')
    expect((await bookmarksFor(item.id))[0].name).toBe('Prefácio')
    await removeBookmark(second.id)
    expect((await bookmarksFor(item.id)).map((b) => b.name)).toEqual(['Prefácio'])

    // Vão no backup e voltam com ele.
    const backup = await exportBackup(false)
    await removeItem(item.id)
    expect(await bookmarksFor(item.id)).toEqual([])
    await importBackup(backup)
    expect((await bookmarksFor(item.id)).map((b) => b.name)).toEqual(['Prefácio'])
  })
})
