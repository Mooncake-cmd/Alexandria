import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { closeDb, DB_NAME } from './db'
import { bookKey, connectSync, GistClient, mergeSync, parseSync, pullPosition, pushPosition, serializeSync, setClientFactory, shouldApply, SYNC_FILE, syncSettings, type SyncEntry } from './sync'
import { exportBackup } from './backup'
import { strFromU8, unzipSync } from 'fflate'

/** GitHub de mentira (como o FakeGitHub dos testes do app). */
function fakeGitHub(validToken = 'github_pat_ok') {
  const gists = new Map<string, { id: string; public: boolean; files: Record<string, { content: string }> }>()
  const calls: string[] = []
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input).replace('https://api.github.com', '')
    const method = init?.method ?? 'GET'
    calls.push(`${method} ${url.split('?')[0]}`)
    const auth = (init?.headers as Record<string, string>)?.Authorization
    const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status })
    if (auth !== `Bearer ${validToken}`) return json(401, { message: 'Bad credentials' })
    if (method === 'GET' && url === '/user') return json(200, { login: 'leitora' })
    if (method === 'GET' && url.startsWith('/gists?')) return json(200, [...gists.values()].map((g) => ({ id: g.id, files: Object.fromEntries(Object.keys(g.files).map((f) => [f, { filename: f }])) })))
    if (method === 'POST' && url === '/gists') {
      const body = JSON.parse(String(init!.body))
      const gist = { id: `g${gists.size + 1}`, public: body.public, files: body.files }
      gists.set(gist.id, gist)
      return json(201, gist)
    }
    const id = url.replace('/gists/', '')
    const gist = gists.get(id)
    if (!gist) return json(404, {})
    if (method === 'GET') return json(200, gist)
    if (method === 'PATCH') {
      Object.assign(gist.files, JSON.parse(String(init!.body)).files)
      return json(200, gist)
    }
    return json(404, {})
  }) as typeof fetch
  return { gists, calls, fetcher, content: () => parseSync([...gists.values()][0].files[SYNC_FILE].content) }
}

beforeEach(async () => {
  await closeDb()
  await new Promise<void>((resolve) => {
    const r = indexedDB.deleteDatabase(DB_NAME)
    r.onsuccess = r.onerror = r.onblocked = () => resolve()
  })
})

// Mesmos casos de PositionSyncTest do app.
describe('sincronização da posição', () => {
  it('o código do livro é o mesmo do app e esconde o nome', async () => {
    expect(await bookKey('  Moby Dick.EPUB ', 1_234_567)).toBe('aa55f5ee8e81a304cbcd6932')
    expect(await bookKey('Memórias Póstumas.epub', 98_765)).toBe('49b69d275333095ce62c4373')
    expect(await bookKey('Moby Dick.epub', 1_234_568)).not.toBe('aa55f5ee8e81a304cbcd6932')
  })

  it('vale a posição mais recente de cada livro; o arquivo vai e volta igual', () => {
    const books: Record<string, SyncEntry> = { a: { format: 'epub', position: 10, exact: 1000, pageCount: 365, updated: 100, device: 'celular' } }
    expect(mergeSync(books, 'a', { ...books.a, position: 5, updated: 50 })).toBe(false)
    expect(mergeSync(books, 'a', { ...books.a, position: 20, updated: 200, device: 'computador' })).toBe(true)
    expect(parseSync(serializeSync(books))).toEqual(books)
    expect(parseSync('não é json')).toEqual({})
  })

  it('aplica a posição do outro aparelho só se for mais recente e outro ponto', () => {
    const remote: SyncEntry = { format: 'epub', position: 438, exact: 43_836, pageCount: 365, updated: 2000, device: 'celular' }
    expect(shouldApply(remote, 1000, 10_000, 300)).toBe(true)
    expect(shouldApply(remote, 3000, 10_000, 300)).toBe(false)
    expect(shouldApply(remote, 1000, 43_900, 300)).toBe(false)
    expect(shouldApply(null, 0, 0, 1)).toBe(false)
  })

  it('conecta, acha ou cria o gist secreto, grava e lê; token fora do backup', async () => {
    const github = fakeGitHub()
    setClientFactory((token) => new GistClient(token, github.fetcher))
    expect(await connectSync(' github_pat_ok ')).toBe('leitora')
    expect(github.gists.size).toBe(1)
    expect([...github.gists.values()][0].public).toBe(false)
    const settings = (await syncSettings())!
    expect(settings.login).toBe('leitora')

    await pushPosition('Moby Dick.epub', 1_234_567, { format: 'epub', position: 438, exact: 43_836, pageCount: 365, updated: 2000, device: 'computador' })
    await pushPosition('Moby Dick.epub', 1_234_567, { format: 'epub', position: 100, exact: 10_000, pageCount: 365, updated: 1000, device: 'celular' }) // mais antiga
    expect(github.content()['aa55f5ee8e81a304cbcd6932'].position).toBe(438)
    expect((await pullPosition('Moby Dick.epub', 1_234_567))?.exact).toBe(43_836)
    expect(await pullPosition('Outro.epub', 10)).toBeNull()

    // Conectar de novo não cria outro gist.
    await connectSync('github_pat_ok')
    expect(github.gists.size).toBe(1)

    const backup = unzipSync(new Uint8Array(await (await exportBackup(false)).arrayBuffer()))
    expect(strFromU8(backup['alexandria.json'])).not.toContain('github_pat_ok')
  })

  it('token recusado fica registrado; sem sincronização nada é enviado', async () => {
    const github = fakeGitHub()
    setClientFactory((token) => new GistClient(token, github.fetcher))
    await pushPosition('a.pdf', 10, { format: 'pdf', position: 1, exact: 1, pageCount: 9, updated: 1, device: 'computador' })
    expect(github.calls).toEqual([])
    await expect(connectSync('errado')).rejects.toMatchObject({ status: 401 })
    await connectSync('github_pat_ok')
    setClientFactory((token) => new GistClient(token === 'github_pat_ok' ? 'vencido' : token, github.fetcher))
    expect(await pullPosition('a.pdf', 10)).toBeNull()
    expect((await syncSettings())!.error).toContain('recusou o token')
  })
})
