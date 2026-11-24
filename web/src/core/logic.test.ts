import { describe, expect, it } from 'vitest'
import { ReadingClock, epochDay, formatDay, parseDay, streakSummary } from './days'
import { findTerms, markHtml, type GlossaryTerm } from './glossary'
import { chunkText, decodeText, findAll, fold, naturalCompare } from './text'
import { describeNoteLocation, describeStatus, detectFormat, progressOf, READABLE, SUPPORTED } from './formats'
import { comicPageNames, dirOf, looksLikeZip, resolveShelf, resolveZipPath, shelfCompare } from './library'
import { afterBackDelta, alignDelta, cutAtBottom, fitPage, maskStart, nextDelta, turnDirection, type LineSpan } from './pages'

// Mesmos casos dos testes do app: ReadingLogicTest, TextDecodingTest, TextChunkerTest, FormatsTest,
// BookHelpersTest, ReadingStatusTest, PageTurnTest e LinePagerTest.

describe('datas e sequência (ReadingLogicTest)', () => {
  it('dias corridos ida e volta', () => {
    expect(epochDay(1970, 1, 1)).toBe(0)
    expect(epochDay(2025, 11, 9)).toBe(20401)
    for (const day of ['2024-02-28', '2024-02-29', '2024-03-01', '1999-12-31', '2026-11-09']) {
      expect(formatDay(parseDay(day)!)).toBe(day)
    }
    expect(parseDay('2024-03-01')! - parseDay('2024-02-29')!).toBe(1)
    expect(parseDay('2024-xx-01')).toBeNull()
  })

  it('sequência conta dias seguidos de pelo menos 5 minutos', () => {
    const days = {
      '2026-11-01': 600, '2026-11-02': 900, '2026-11-03': 400,
      '2026-11-05': 120,
      '2026-11-06': 300, '2026-11-07': 1200, '2026-11-08': 500
    }
    const today = streakSummary(days, '2026-11-08')
    expect([today.current, today.best, today.readToday]).toEqual([3, 3, true])
    const tomorrow = streakSummary(days, '2026-11-09')
    expect([tomorrow.current, tomorrow.readToday]).toEqual([3, false])
    expect(streakSummary(days, '2026-11-10').current).toBe(0)
    expect(streakSummary({}, '2026-11-10')).toEqual({ current: 0, best: 0, readToday: false, todaySeconds: 0 })
  })

  it('cronômetro pausa parado e separa os dias', () => {
    const clock = new ReadingClock(60_000)
    clock.start(0)
    clock.tick(30_000, '2026-11-08')
    clock.interaction(30_000)
    clock.tick(80_000, '2026-11-08')
    clock.tick(200_000, '2026-11-08')
    expect(clock.sessionMs).toBe(90_000)
    expect(clock.isIdle(200_000)).toBe(true)
    clock.interaction(200_000)
    clock.tick(210_500, '2026-11-09')
    expect(clock.drain()).toEqual({ '2026-11-08': 90, '2026-11-09': 10 })
    clock.stop(211_000, '2026-11-09')
    expect(clock.drain()).toEqual({ '2026-11-09': 1 })
    expect(clock.drain()).toEqual({})
  })
})

describe('glossário', () => {
  const term = (id: number, text: string): GlossaryTerm => ({ id, itemId: 1, term: text, definition: 'def', createdAt: 0 })

  it('acha palavras inteiras sem maiúsculas nem acentos', () => {
    const terms = [term(1, 'Arrakis'), term(2, 'Casa Atreides'), term(3, 'Casa'), term(4, 'Fremen')]
    const text = 'Em ARRAKIS, a Casa Atreides chegou. A casa velha. Arrakisiano não conta. Os fremen e os Frêmen.'
    const found = findTerms(text, terms).map((m) => [text.slice(m.start, m.end), m.termId])
    expect(found).toEqual([['ARRAKIS', 1], ['Casa Atreides', 2], ['casa', 3], ['fremen', 4], ['Frêmen', 4]])
  })

  it('marca o texto do HTML, mas não as tags nem o head', () => {
    const html = '<html><head><title>Arrakis</title><style>.arrakis{}</style></head>' +
      '<body><p class="arrakis">Arrakis &amp; <b>Arrakis</b></p><img alt="Arrakis"/></body></html>'
    const out = markHtml(html, [term(7, 'Arrakis')])
    expect(out.match(/data-term="7"/g)?.length).toBe(2)
    expect(out).toContain('<title>Arrakis</title>')
    expect(out).toContain('<p class="arrakis">')
    expect(out).toContain('alt="Arrakis"')
    expect(out).toContain('<b><span class="alx-term" data-term="7">Arrakis</span></b>')
    expect(markHtml(html, [])).toBe(html)
  })
})

describe('texto (TextDecodingTest, TextChunkerTest, BookHelpersTest)', () => {
  const utf8 = (s: string) => new TextEncoder().encode(s)

  it('decodifica UTF-8, Windows-1252, UTF-16 e normaliza quebras', () => {
    expect(decodeText(new Uint8Array([0xef, 0xbb, 0xbf, ...utf8('Ação')]))).toBe('Ação')
    // "Coração e pão" em Windows-1252
    const w1252 = new Uint8Array([0x43, 0x6f, 0x72, 0x61, 0xe7, 0xe3, 0x6f, 0x20, 0x65, 0x20, 0x70, 0xe3, 0x6f])
    expect(decodeText(w1252)).toBe('Coração e pão')
    const le = new Uint8Array([0xff, 0xfe, 0x4f, 0, 0x6c, 0, 0xe1, 0])
    const be = new Uint8Array([0xfe, 0xff, 0, 0x4f, 0, 0x6c, 0, 0xe1])
    expect(decodeText(le)).toBe('Olá')
    expect(decodeText(be)).toBe('Olá')
    const full = utf8('abc é')
    expect(decodeText(full.subarray(0, full.length - 1), true)).toBe('abc ')
    expect(decodeText(utf8('a\r\nb\rc\n'))).toBe('a\nb\nc\n')
    expect(decodeText(new Uint8Array(0))).toBe('')
  })

  it('divide em blocos que reconstroem o texto', () => {
    expect(chunkText('')).toEqual([])
    expect(chunkText('uma linha\noutra')).toEqual(['uma linha\noutra'])
    const text = Array.from({ length: 500 }, (_, k) => ((k + 1) % 7 === 0 ? '' : `Linha ${k + 1} com algum texto.`)).join('\n')
    const chunks = chunkText(text, 200, 400)
    expect(chunks.length).toBeGreaterThan(1)
    expect(chunks.join('\n')).toBe(text)
    expect(chunks.every((c) => c.length <= 200)).toBe(true)
    expect(chunkText('\n\nfim\n', 2, 4).join('\n')).toBe('\n\nfim\n')
    const line = Array(300).fill('palavra').join(' ')
    const parts = chunkText(`antes\n${line}\ndepois`, 50, 100)
    expect(parts[0]).toBe('antes')
    expect(parts.at(-1)).toBe('depois')
    const middle = parts.slice(1, -1)
    expect(middle.every((c) => c.length <= 100)).toBe(true)
    expect(middle.join('')).toBe(line)
    expect(middle.slice(0, -1).every((c) => c.endsWith(' '))).toBe(true)
    expect(chunkText('x'.repeat(250), 50, 100).map((c) => c.length)).toEqual([100, 100, 50])
  })

  it('ordem natural, busca sem acento, quadrinhos, caminhos e prateleiras', () => {
    expect(['p10.jpg', 'p2.jpg', 'P1.jpg', 'p02b.jpg', 'capa.jpg'].sort(naturalCompare))
      .toEqual(['capa.jpg', 'P1.jpg', 'p2.jpg', 'p02b.jpg', 'p10.jpg'])
    const entries = ['cap1/', 'cap1/010.png', 'cap1/002.JPG', 'cap1/Thumbs.db', '__MACOSX/cap1/._002.JPG', 'cap1/.hidden.png', 'info.txt', 'cap2/001.webp']
    expect(comicPageNames(entries)).toEqual(['cap1/002.JPG', 'cap1/010.png', 'cap2/001.webp'])
    expect(findAll('Ação e acao', 'AÇÃO')).toEqual([0, 7])
    expect(findAll('Era um coração', 'coracao')).toEqual([7])
    expect(findAll('texto', '  ')).toEqual([])
    expect(fold('Ação').length).toBe(4)
    expect(resolveZipPath('OEBPS/Text', 'cap%201.xhtml')).toBe('OEBPS/Text/cap 1.xhtml')
    expect(resolveZipPath('OEBPS/Text', '../Images/a.png#x')).toBe('OEBPS/Images/a.png')
    expect(resolveZipPath('', './cap.xhtml')).toBe('cap.xhtml')
    expect(resolveZipPath('', 'a+b.xhtml')).toBe('a+b.xhtml')
    expect(dirOf('OEBPS/content.opf')).toBe('OEBPS')
    expect(dirOf('content.opf')).toBe('')
    expect(resolveShelf('  ficção  ', ['Ficção', 'Estudos'])).toBe('Ficção')
    expect(resolveShelf('Novos   livros', ['Ficção'])).toBe('Novos livros')
    expect(['d', 'C', 'b'].sort(shelfCompare)).toEqual(['b', 'C', 'd'])
  })
})

describe('formatos e progresso (FormatsTest, ReadingStatusTest)', () => {
  it('detecta formatos', () => {
    expect(detectFormat('Livro.PDF')).toBe('pdf')
    expect(detectFormat('notas.txt', 'application/octet-stream')).toBe('txt')
    expect(detectFormat('a.b.c.epub')).toBe('epub')
    expect(detectFormat('quadrinho.cbz', 'application/zip')).toBe('cbz')
    expect(detectFormat('documento', 'application/pdf')).toBe('pdf')
    expect(detectFormat('arquivo.log', 'text/plain')).toBe('txt')
    expect(detectFormat('scan', 'image/x-djvu')).toBe('djvu')
    expect(detectFormat('foto.jpg', 'image/jpeg')).toBeNull()
    expect(detectFormat('sem-extensao')).toBeNull()
    expect(detectFormat('')).toBeNull()
    expect(detectFormat('livro.azw3')).toBe('mobi')
    expect(detectFormat('scan.djv')).toBe('djvu')
    expect(SUPPORTED.every((f) => READABLE.has(f))).toBe(true)
    expect(looksLikeZip(new Uint8Array([0x50, 0x4b, 3, 4]))).toBe(true)
    expect(looksLikeZip(new TextEncoder().encode('Rar!'))).toBe(false)
    expect(looksLikeZip(new Uint8Array(0))).toBe(false)
  })

  const item = (format: string, position: number, pageCount = 0, openedAt = 0, finishedAt = 0) => ({ format, position, pageCount, openedAt, finishedAt })

  it('descreve o progresso como o app', () => {
    expect(describeStatus(item('xyz', 0))).toBe('Leitor em breve')
    expect(describeStatus(item('mobi', 0, 30))).toBe('Toque para ler · 30 capítulos')
    expect(describeStatus(item('djvu', 1, 8, 1))).toBe('Página 2 de 8 · 25%')
    expect(describeStatus(item('cbr', 0, 20))).toBe('Toque para ler · 20 páginas')
    expect(describeStatus(item('txt', 0))).toBe('Toque para ler')
    expect(describeStatus(item('txt', 4250, 0, 1))).toBe('42% lido')
    expect(describeStatus(item('txt', 10000, 0, 1))).toBe('Lido até o fim')
    expect(describeStatus(item('pdf', 0))).toBe('Toque para ler')
    expect(describeStatus(item('pdf', 7))).toBe('Parou na página 8')
    expect(describeStatus(item('pdf', 0, 200, 1))).toBe('Página 1 de 200 · 0%')
    expect(describeStatus(item('pdf', 49, 200, 1))).toBe('Página 50 de 200 · 25%')
    expect(describeStatus(item('pdf', 199, 200, 1))).toBe('Última página · 200 páginas')
    expect(describeStatus(item('pdf', 0, 1, 1))).toBe('Página 1 de 1 · 100%')
    expect(describeStatus(item('epub', 0, 12))).toBe('Toque para ler · 12 capítulos')
    expect(describeStatus(item('epub', 3000, 12, 1))).toBe('30% lido')
    expect(describeStatus(item('cbz', 2, 40, 1))).toBe('Página 3 de 40 · 7%')
    expect(describeStatus(item('docx', 5500, 0, 1))).toBe('55% lido')
    expect(describeStatus(item('pdf', 3, 10, 1, 5))).toBe('Concluído')
    expect(progressOf(item('pdf', 0, 10))).toBeCloseTo(0)
    expect(progressOf(item('cbz', 4, 10, 1))).toBeCloseTo(0.5)
    expect(progressOf(item('txt', 2500))).toBeCloseTo(0.25)
    expect(progressOf(item('txt', 100, 0, 0, 1))).toBeCloseTo(1)
    expect(describeNoteLocation('pdf', 4, 100)).toBe('Pág. 5')
    expect(describeNoteLocation('epub', 2500, 10)).toBe('Cap. 3 · 25%')
    expect(describeNoteLocation('txt', 4200, 0)).toBe('42%')
  })
})

describe('páginas (PageTurnTest, LinePagerTest)', () => {
  it('bordas e página inteira', () => {
    expect(turnDirection(10, 100)).toBe(-1)
    expect(turnDirection(50, 100)).toBe(0)
    expect(turnDirection(90, 100)).toBe(1)
    expect(turnDirection(10, 0)).toBe(0)
    expect(fitPage(1000, 1000, 1.5)).toEqual([666, 1000])
    expect(fitPage(400, 1000, 1.5)).toEqual([400, 600])
  })

  const lines = (start: number, count: number, height = 20): LineSpan[] =>
    Array.from({ length: count }, (_, k) => ({ top: start + k * height, bottom: start + (k + 1) * height }))

  it('a próxima página começa na linha cortada', () => {
    const page = lines(10, 8)
    expect(cutAtBottom(page, 10, 105)).toEqual({ top: 90, bottom: 110 })
    expect(maskStart(page, 10, 105)).toBe(90)
    expect(nextDelta(page, 10, 105)).toBe(80)
    expect(cutAtBottom(page, 10, 110)).toBeNull()
    expect(maskStart(page, 10, 110)).toBe(110)
    expect(nextDelta(page, 10, 110)).toBe(100)
  })

  it('bloco enorme e imagem no fim', () => {
    expect(cutAtBottom([{ top: 10, bottom: 900 }], 10, 110)).toBeNull()
    expect(nextDelta([{ top: 10, bottom: 900 }], 10, 110)).toBe(100)
    expect(nextDelta([...lines(10, 3), { top: 70, bottom: 400 }], 10, 110)).toBe(60)
  })

  it('voltar e alinhar', () => {
    const back = lines(5, 6)
    expect(afterBackDelta(back, 10, 110)).toBe(15)
    expect(alignDelta(back, 10, 110)).toBe(-5)
    expect(alignDelta(lines(10, 5), 10, 110)).toBe(0)
  })
})

describe('cronômetro pelo ritmo de leitura (ReadingLogicTest)', () => {
  const day = '2026-11-24'
  it('aprende o ritmo e pausa antes', () => {
    const clock = new ReadingClock(undefined, undefined, [40_000, 50_000, 60_000, 1_000])
    expect(clock.typicalPageMs()).toBe(50_000)
    expect(clock.idleLimit()).toBe(150_000)
    expect(clock.graceMs()).toBe(50_000)
    expect(new ReadingClock().idleLimit()).toBe(300_000)
    expect(new ReadingClock().graceMs()).toBe(300_000)
    expect(new ReadingClock(undefined, undefined, [5_000, 5_000, 5_000]).idleLimit()).toBe(60_000)
  })

  it('o tempo longe não conta, a não ser que o leitor volte antes da pausa', () => {
    const away = new ReadingClock(undefined, undefined, [50_000, 50_000, 50_000])
    away.start(0)
    for (let t = 1; t <= 400; t++) away.tick(t * 1000, day)
    expect(away.isIdle(400_000)).toBe(true)
    expect(away.sessionMs).toBe(50_000)
    expect(away.provisionalMs).toBe(0)
    expect(away.drain()).toEqual({ [day]: 50 })

    const slow = new ReadingClock(undefined, undefined, [50_000, 50_000, 50_000])
    slow.start(0)
    for (let t = 1; t <= 120; t++) slow.tick(t * 1000, day)
    expect([slow.sessionMs, slow.provisionalMs, slow.shownMs]).toEqual([50_000, 70_000, 120_000])
    slow.pageTurn(120_000)
    expect(slow.sessionMs).toBe(120_000)
    expect(slow.drain()).toEqual({ [day]: 120 })

    const closed = new ReadingClock(undefined, undefined, [50_000, 50_000, 50_000])
    closed.start(0)
    for (let t = 1; t <= 90; t++) closed.tick(t * 1000, day)
    closed.stop(90_000, day)
    expect(closed.sessionMs).toBe(50_000)
  })

  it('pausa manual para até retomar', () => {
    const clock = new ReadingClock()
    clock.start(0)
    clock.tick(30_000, day)
    clock.pause(30_000, day)
    expect(clock.paused && clock.isIdle(31_000)).toBe(true)
    clock.interaction(40_000)
    for (let t = 31; t <= 200; t++) clock.tick(t * 1000, day)
    expect(clock.sessionMs).toBe(30_000)
    clock.resume(200_000)
    clock.tick(210_000, day)
    expect(clock.sessionMs).toBe(40_000)
    clock.pageTurn(215_000)
    expect(clock.typicalPageMs()).toBeNull()
  })
})

