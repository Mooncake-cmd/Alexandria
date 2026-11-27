import { describe, expect, it } from 'vitest'
import { bookKey, type SyncEntry } from '../storage/sync'
import { BOOK_TAG, copyCloud, isTelegramUrl, listCloudBooks, parseBookMessage, pullPositionFrom, pushPositionTo, removeCloudBook, uploadBook, type TgApi, type TgMessage } from './telegramCloud'
import { inviteHash } from './gramApi'

/** Uma conversa de mentira (Mensagens salvas ou, com [group], o grupo). */
function fakeChat(sender?: string, group = false) {
  const messages: (TgMessage & { bytes: Uint8Array })[] = []
  let id = 0
  let clock = 1_700_000_000
  const api: TgApi = {
    anyBook: group,
    async files() {
      return messages.filter((m) => m.fileName).map(({ bytes: _b, ...m }) => m)
    },
    async search(tag) {
      return messages.filter((m) => m.caption.includes(tag)).map(({ bytes: _b, ...m }) => m)
    },
    async upload(name, bytes, caption) {
      const m = { id: ++id, caption, fileName: name, size: bytes.byteLength, date: ++clock, sender, bytes: bytes.slice() }
      messages.push(m)
      const { bytes: _b, ...meta } = m
      return meta
    },
    async download(messageId) {
      const m = messages.find((x) => x.id === messageId)
      if (!m) throw new Error('não achou')
      return m.bytes
    },
    async remove(ids) {
      for (const i of ids) messages.splice(messages.findIndex((m) => m.id === i), 1)
    }
  }
  /** Arquivo mandado pelo próprio Telegram, com a legenda escrita à mão. */
  const manual = (fileName: string, caption: string, size = 10) => {
    messages.push({ id: ++id, caption, fileName, size, date: ++clock, sender, bytes: new Uint8Array(size) })
  }
  return { api, messages, manual }
}

const entry = (updated: number, position = 10): SyncEntry => ({ format: 'epub', position, exact: position * 100, pageCount: 30, updated, device: 'celular' })

describe('arquivos com #alexandria_livro escrito à mão', () => {
  it('entram na nuvem com o mesmo código que o app calcula', async () => {
    const chat = fakeChat('Ana')
    chat.manual('Dom Casmurro.epub', 'meu livro #alexandria_livro', 4321)
    chat.manual('boleto.pdf', 'pagar até sexta', 99)
    chat.manual('foto.jpg', '#alexandria_livro', 50)
    const books = await listCloudBooks(chat.api)
    expect(books.map((b) => b.name)).toEqual(['Dom Casmurro.epub'])
    expect(books[0]).toMatchObject({ key: await bookKey('Dom Casmurro.epub', 4321), format: 'epub', size: 4321, manual: true, sender: 'Ana' })
  })

  it('formatos alternativos viram o formato do leitor e enviar de novo pelo app não duplica', async () => {
    const chat = fakeChat()
    chat.manual('Duna.azw3', '#alexandria_livro', 8)
    expect((await listCloudBooks(chat.api))[0].format).toBe('mobi')
    const { existed } = await uploadBook(chat.api, 'Duna.azw3', 'mobi', new Uint8Array(8))
    expect(existed).toBe(true)
    expect(chat.messages).toHaveLength(1)
    await removeCloudBook(chat.api, await bookKey('Duna.azw3', 8))
    expect(chat.messages).toHaveLength(0)
  })

  it('legenda do Alexandria estragada não vira livro pelo nome do arquivo', async () => {
    const chat = fakeChat()
    chat.manual('Livro.epub', `${BOOK_TAG}\nalexandria:{quebrado`)
    expect(await parseBookMessage(chat.messages[0])).toBeNull()
  })
})

describe('nuvem num grupo privado', () => {
  it('no grupo, qualquer arquivo de livro conta, mesmo sem a marca (encaminhado ou de conta sem o Alexandria)', async () => {
    const group = fakeChat('Bia', true)
    group.manual('Iracema.epub', '', 77)
    group.manual('Os Sertões.pdf', 'olha esse', 88)
    group.manual('foto.jpg', '', 5)
    await uploadBook(group.api, 'Moby Dick.epub', 'epub', new Uint8Array(9))
    await pushPositionTo(group.api, 'k', entry(1))
    const books = await listCloudBooks(group.api)
    expect(books.map((b) => b.name)).toEqual(['Iracema.epub', 'Moby Dick.epub', 'Os Sertões.pdf'])
    expect(books.find((b) => b.name === 'Iracema.epub')).toMatchObject({ manual: true, sender: 'Bia', key: await bookKey('Iracema.epub', 77) })
    // O mesmo livro mandado pelo app não duplica; apagar tira o arquivo sem marca.
    expect((await uploadBook(group.api, 'Iracema.epub', 'epub', new Uint8Array(77))).existed).toBe(true)
    await removeCloudBook(group.api, await bookKey('Iracema.epub', 77))
    expect((await listCloudBooks(group.api)).map((b) => b.name)).toEqual(['Moby Dick.epub', 'Os Sertões.pdf'])
  })

  it('nas Mensagens salvas a marca continua obrigatória', async () => {
    const saved = fakeChat()
    saved.manual('Iracema.epub', '', 77)
    expect(await listCloudBooks(saved.api)).toEqual([])
  })

  it('copia das Mensagens salvas só o que falta e junta as posições', async () => {
    const saved = fakeChat()
    const group = fakeChat(undefined, true)
    await uploadBook(saved.api, 'A.epub', 'epub', new Uint8Array([1]))
    await uploadBook(saved.api, 'B.pdf', 'pdf', new Uint8Array([1, 2]))
    await uploadBook(group.api, 'A.epub', 'epub', new Uint8Array([1]))
    const kA = await bookKey('A.epub', 1)
    const kB = await bookKey('B.pdf', 2)
    await pushPositionTo(saved.api, kA, entry(2000, 50))
    await pushPositionTo(saved.api, kB, entry(1000, 7))
    await pushPositionTo(group.api, kA, entry(3000, 80))

    const forwarded: number[][] = []
    const result = await copyCloud(saved.api, group.api, async (ids) => {
      forwarded.push(ids)
      // Encaminhar leva a mensagem com a legenda para o grupo.
      for (const i of ids) {
        const m = saved.messages.find((x) => x.id === i)!
        await group.api.upload(m.fileName!, m.bytes, m.caption)
      }
    })
    expect(result).toEqual({ books: 1, positions: true })
    expect(forwarded).toHaveLength(1)
    expect((await listCloudBooks(group.api)).map((b) => b.name)).toEqual(['A.epub', 'B.pdf'])
    // No grupo vale a posição mais recente de cada livro.
    expect((await pullPositionFrom(group.api, kA))?.position).toBe(80)
    expect((await pullPositionFrom(group.api, kB))?.position).toBe(7)
    // As Mensagens salvas ficam como estavam.
    expect(await listCloudBooks(saved.api)).toHaveLength(2)

    // Copiar de novo não repete nada.
    expect(await copyCloud(saved.api, group.api, async () => { throw new Error('não devia encaminhar') })).toEqual({ books: 0, positions: false })
  })

  it('reconhece links de convite', () => {
    expect(inviteHash('https://t.me/+AbCdEf123_-xyz')).toBe('AbCdEf123_-xyz')
    expect(inviteHash('t.me/joinchat/AbCdEf123456')).toBe('AbCdEf123456')
    expect(inviteHash('  +AbCdEf123456  ')).toBe('AbCdEf123456')
    expect(inviteHash('https://t.me/alexandria')).toBeNull()
    expect(inviteHash('oi')).toBeNull()
  })

  it('anúncios: endereços do Telegram abrem direto, os outros pedem confirmação', () => {
    expect(isTelegramUrl('https://t.me/canal')).toBe(true)
    expect(isTelegramUrl('https://telegram.org/blog')).toBe(true)
    expect(isTelegramUrl('https://fragment.com/x')).toBe(true)
    expect(isTelegramUrl('https://exemplo.com/t.me')).toBe(false)
    expect(isTelegramUrl('https://faket.me')).toBe(false)
    expect(isTelegramUrl('não é url')).toBe(false)
  })
})
