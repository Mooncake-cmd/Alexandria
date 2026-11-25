import { useEffect, useRef, useState } from 'preact/hooks'
import { displayTitle } from '../core/library'
import { THEMES } from '../core/comfort'
import { bookFile, findItem, setFinished, startOf, updateProgress, type LibraryItem } from '../storage/library'
import { loadPrefs, savePrefs, MAX_TEXT_SIZE, MIN_TEXT_SIZE, type ReadingPrefs } from '../reader/prefs'
import { loadDocx, loadTxt, type TextContent } from '../reader/textContent'
import { TextReader, type TextReaderHandle } from '../reader/TextReader'
import { EpubReader, type EpubReaderHandle } from '../reader/EpubReader'
import { openEpub, openMobi, type ReflowBook } from '../reader/reflowBook'
import { coverOf, setCover, updatePageCount } from '../storage/library'
import { scaleCover } from '../library/covers'
import { PageReader, type PageReaderHandle } from '../reader/PageReader'
import type { PageSource } from '../reader/pageSource'
import { promptDialog } from '../ui/dialogs'
import { menuDialog, toast } from '../ui/dialogs'
import { ReaderChrome } from '../reader/ReaderChrome'
import type { ReaderTools } from '../reader/ToolsPanel'
import { describeNoteLocation } from '../core/formats'
import { EXACT_SCALE } from '../core/bookmarks'

type State =
  | { status: 'loading' }
  | { status: 'error'; message: string; detail?: string }
  | { status: 'pending'; item: LibraryItem }
  | { status: 'text'; item: LibraryItem; content: TextContent; prefs: ReadingPrefs }
  | { status: 'reflow'; item: LibraryItem; book: ReflowBook; prefs: ReadingPrefs }
  | { status: 'pages'; item: LibraryItem; source: PageSource; prefs: ReadingPrefs }

/** Formatos com leitor no site até agora (os demais chegam nas próximas etapas do plano). */
const TEXT_FORMATS = new Set(['txt', 'docx'])
const REFLOW_FORMATS = new Set(['epub', 'mobi'])
const PAGE_FORMATS = new Set(['pdf', 'cbz', 'cbr', 'djvu'])

/** Abre PDF, quadrinho ou DJVU; PDF com senha pede a senha (o app ainda não abre esses). */
async function openPages(format: string, file: Blob): Promise<PageSource | null> {
  if (format === 'pdf') {
    const { openPdf, isPasswordError } = await import('../reader/pdfSource')
    let password: string | undefined
    for (;;) {
      try {
        return await openPdf(file, password)
      } catch (e) {
        if (!isPasswordError(e)) throw e
        const typed = await promptDialog(password === undefined ? 'PDF protegido' : 'Senha incorreta', 'Senha do PDF', '', [], 'Abrir')
        if (typed === null) return null
        password = typed
      }
    }
  }
  if (format === 'djvu') return (await import('../reader/djvuSource')).openDjvu(file)
  const comics = await import('../reader/comicSource')
  return format === 'cbr' ? comics.openCbr(file) : comics.openCbz(file)
}

export function Reader({ bookId, startAt = null }: { bookId: number; startAt?: number | null }) {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    let alive = true
    let content: TextContent | null = null
    let reflow: ReflowBook | null = null
    let pages: PageSource | null = null
    ;(async () => {
      const found = await findItem(bookId)
      if (!found) return setState({ status: 'error', message: 'Documento não encontrado na biblioteca.' })
      // Uma anotação leva ao ponto dela (o ponto preciso salvo é de outra leitura).
      const item = startAt !== null ? { ...found, position: startAt, exactPosition: -1 } : found
      if (!TEXT_FORMATS.has(item.format) && !REFLOW_FORMATS.has(item.format) && !PAGE_FORMATS.has(item.format)) return setState({ status: 'pending', item })
      const file = await bookFile(bookId)
      if (!file) return setState({ status: 'error', message: 'O arquivo deste livro não está guardado neste navegador. Importe-o de novo.' })
      if (PAGE_FORMATS.has(item.format)) {
        try {
          const [source, prefs] = await Promise.all([openPages(item.format, file), loadPrefs()])
          if (!source) return void (location.hash = '#/biblioteca')
          pages = source
          if (!alive) return source.close()
          setState({ status: 'pages', item, source, prefs })
        } catch (e) {
          console.error(e)
          setState({ status: 'error', message: `Não foi possível abrir este ${item.format.toUpperCase()}. O arquivo pode estar danificado.`, detail: errorDetail(e) })
        }
        return
      }
      if (REFLOW_FORMATS.has(item.format)) {
        try {
          const [book, prefs] = await Promise.all([item.format === 'epub' ? openEpub(file) : openMobi(file), loadPrefs()])
          reflow = book
          if (!alive) return book.dispose()
          if (item.pageCount !== book.chapterCount) updatePageCount(item.id, book.chapterCount)
          // Capa que faltava (livro aberto antes de a biblioteca gerar).
          if (!(await coverOf(item.id))) book.cover().then((c) => c && scaleCover(c)).then((c) => c && setCover(item.id, c))
          setState({ status: 'reflow', item, book, prefs })
        } catch (e) {
          console.error(e)
          setState({ status: 'error', message: item.format === 'mobi' ? 'Não foi possível abrir este MOBI/AZW3. Livros com DRM (comprados na Amazon) não abrem.' : 'Não foi possível abrir este EPUB.', detail: errorDetail(e) })
        }
        return
      }
      try {
        const [loaded, prefs] = await Promise.all([item.format === 'docx' ? loadDocx(file) : loadTxt(file), loadPrefs()])
        content = loaded
        if (!alive) return loaded.dispose()
        if (!loaded.chunks.length) return setState({ status: 'error', message: 'Este documento está vazio.' })
        if (loaded.truncated) toast('Arquivo muito grande: exibindo só o começo.')
        setState({ status: 'text', item, content: loaded, prefs })
      } catch (e) {
        console.error(e)
        setState({ status: 'error', message: 'Não foi possível abrir este documento.', detail: errorDetail(e) })
      }
    })()
    return () => {
      alive = false
      content?.dispose()
      reflow?.dispose()
      pages?.close()
    }
  }, [bookId])

  if (state.status === 'loading') return <div class="reader-message">Carregando…</div>
  if (state.status === 'error') return <ReaderMessage text={state.message} detail={state.detail} />
  if (state.status === 'pending') return <ReaderMessage title={displayTitle(state.item.name)} text={`O leitor de ${state.item.format.toUpperCase()} do site ainda está em construção.`} />
  if (state.status === 'pages') return <PageBookView item={state.item} source={state.source} initialPrefs={state.prefs} />
  if (state.status === 'reflow') return <ReflowBookView item={state.item} book={state.book} initialPrefs={state.prefs} />
  return <TextBook item={state.item} content={state.content} initialPrefs={state.prefs} />
}

/** Mensagem técnica curta do erro, com o navegador: ajuda a descobrir por que um livro não abriu. */
function errorDetail(e: unknown): string {
  const message = e instanceof Error ? `${e.name}: ${e.message}` : String(e)
  const browser = /(Edg|OPR|Firefox|Chrome|Version)\/(\d+)/.exec(navigator.userAgent)
  const name = browser ? `${{ Edg: 'Edge', OPR: 'Opera', Version: 'Safari' }[browser[1]] ?? browser[1]} ${browser[2]}` : navigator.userAgent.slice(0, 60)
  return `${message.slice(0, 200)} · ${name}`
}

function ReaderMessage({ title, text, detail }: { title?: string; text: string; detail?: string }) {
  return (
    <div class="reader-message">
      <a href="#/biblioteca" class="back">‹ Biblioteca</a>
      {title && <h2>{title}</h2>}
      <p class="muted">{text}</p>
      {detail && <p class="muted small">Detalhe técnico: <code>{detail}</code></p>}
    </div>
  )
}

/** Estado comum dos leitores: preferências, concluir e salvar o ponto de leitura. */
function useBook(initial: LibraryItem, initialPrefs: ReadingPrefs, pageCount: number, current: () => number, currentExact: () => number = () => -1) {
  const [item, setItem] = useState(initial)
  const [prefs, setPrefs] = useState(initialPrefs)
  const saved = useRef(initial.position)
  const changePrefs = (change: Partial<ReadingPrefs>) => {
    setPrefs((old) => {
      const next = { ...old, ...change }
      savePrefs(next)
      return next
    })
  }
  const finish = async (done: boolean) => {
    await setFinished(item.id, done)
    setItem({ ...item, finishedAt: done ? Date.now() : 0 })
    toast(done ? `"${displayTitle(item.name)}" foi para a Estante.` : `"${displayTitle(item.name)}" voltou para "Lendo agora".`)
  }
  // Salva ao abrir, de vez em quando (sem gravar a cada pixel) e ao sair do livro ou fechar a aba.
  const currentRef = useRef(current)
  currentRef.current = current
  const exactRef = useRef(currentExact)
  exactRef.current = currentExact
  useEffect(() => {
    updateProgress(item.id, initial.position, pageCount, Date.now(), initial.exactPosition ?? -1)
    const save = () => {
      saved.current = currentRef.current()
      updateProgress(item.id, saved.current, pageCount, Date.now(), exactRef.current())
    }
    window.addEventListener('pagehide', save)
    document.addEventListener('visibilitychange', save)
    return () => {
      save()
      window.removeEventListener('pagehide', save)
      document.removeEventListener('visibilitychange', save)
    }
  }, [])
  const savePosition = (position: number) => {
    if (position === saved.current) return
    saved.current = position
    updateProgress(item.id, position, pageCount, Date.now(), exactRef.current())
  }
  return { item, prefs, changePrefs, finish, savePosition }
}

/** Salva a posição pouco depois que ela para de mudar. */
function useDebouncedSave(value: number, save: (v: number) => void, delay = 800) {
  useEffect(() => {
    const timer = setTimeout(() => save(value), delay)
    return () => clearTimeout(timer)
  }, [value])
}

async function chooseTheme(prefs: ReadingPrefs, changePrefs: (c: Partial<ReadingPrefs>) => void) {
  const themes = Object.entries(THEMES)
  const choice = await menuDialog('Cor da página', themes.map(([key, t]) => (key === prefs.comfort.theme ? `▸ ${t.label}` : t.label)))
  if (choice !== null) changePrefs({ comfort: { ...prefs.comfort, theme: themes[choice][0] as keyof typeof THEMES } })
}

/** Leitor de TXT/DOCX. */
function TextBook({ item: initial, content, initialPrefs }: { item: LibraryItem; content: TextContent; initialPrefs: ReadingPrefs }) {
  const handle = useRef<TextReaderHandle | null>(null)
  const [position, setPosition] = useState({ fraction: initial.position, atEnd: false })
  const start = startOf(initial, EXACT_SCALE)
  const { item, prefs, changePrefs, finish, savePosition } = useBook(initial, initialPrefs, 0, () => handle.current?.fraction() ?? position.fraction, () => handle.current?.exact() ?? -1)
  useDebouncedSave(position.fraction, savePosition)
  const textSize = (delta: number) => changePrefs({ textSize: Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, prefs.textSize + delta)) })
  const tools: ReaderTools = {
    itemId: item.id,
    search: async (term) => handle.current?.search(term) ?? [],
    showHit: (hit, term) => handle.current?.showHit(hit, term),
    clearSearch: () => handle.current?.clearSearch(),
    current: () => handle.current?.fraction() ?? position.fraction,
    describe: (p) => describeNoteLocation(item.format, p, 0),
    goTo: (p) => handle.current?.goTo(p),
    pageNotes: false,
    exact: () => handle.current?.exact() ?? position.fraction * EXACT_SCALE,
    span: () => handle.current?.span() ?? 1,
    goToExact: (p) => handle.current?.goToExact(p),
    exactScale: EXACT_SCALE,
    ready: () => handle.current?.ready() ?? false
  }
  return (
    <ReaderChrome
      title={item.name}
      prefs={prefs}
      onPrefs={changePrefs}
      typography
      tools={tools}
      sync={{ name: initial.name, size: initial.fileSize, format: initial.format, openedAt: initial.openedAt, pageCount: 0 }}
      readingSeconds={initial.readingSeconds}
      menu={[
        [prefs.paged ? 'Modo: rolagem contínua' : 'Modo: páginas (setas, roda do mouse ou clique duplo na borda)', () => changePrefs({ paged: !prefs.paged })],
        ['Letra menor', () => textSize(-1)],
        ['Letra maior', () => textSize(1)],
        ['Cor da página…', () => chooseTheme(prefs, changePrefs)]
      ]}
      footer={
        <>
          <button type="button" class="foot-button" aria-label="Diminuir letra" onClick={() => textSize(-1)}>A-</button>
          <span class="muted small">{position.atEnd ? 'Fim' : `${Math.trunc(position.fraction / 100)}% lido`}</span>
          <button type="button" class="foot-button" aria-label="Aumentar letra" onClick={() => textSize(1)}>A+</button>
        </>
      }
      finished={item.finishedAt > 0}
      atEnd={position.atEnd}
      onFinish={finish}
    >
      {(api) => (
        <TextReader
          terms={api.terms}
          onTerm={api.onTerm}
          content={content}
          paged={prefs.paged}
          textSize={prefs.textSize}
          comfort={prefs.comfort}
          start={start}
          onPosition={(fraction, atEnd) => setPosition({ fraction, atEnd })}
          onTextSize={(size) => changePrefs({ textSize: size })}
          handle={(h) => (handle.current = h)}
        />
      )}
    </ReaderChrome>
  )
}

/** Leitor de EPUB/MOBI. */
function ReflowBookView({ item: initial, book, initialPrefs }: { item: LibraryItem; book: ReflowBook; initialPrefs: ReadingPrefs }) {
  const handle = useRef<EpubReaderHandle | null>(null)
  const [info, setInfo] = useState({ position: initial.position, chapter: 0, atEnd: false })
  const start = startOf(initial, EXACT_SCALE)
  const { item, prefs, changePrefs, finish, savePosition } = useBook(initial, initialPrefs, book.chapterCount, () => handle.current?.position() ?? info.position, () => handle.current?.exact() ?? -1)
  useDebouncedSave(info.position, savePosition)
  const textSize = (delta: number) => changePrefs({ textSize: Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, prefs.textSize + delta)) })
  const contents = async () => {
    const names = book.chapterTitles.map((t, i) => (i === info.chapter ? '▸ ' : '') + (t ?? `Capítulo ${i + 1}`))
    const choice = await menuDialog('Sumário', names)
    if (choice !== null) handle.current?.goToChapter(choice)
  }
  const title = book.chapterTitles[info.chapter]
  const tools: ReaderTools = {
    itemId: item.id,
    search: (term, isCancelled) => handle.current?.search(term, isCancelled) ?? Promise.resolve([]),
    showHit: (hit, term) => handle.current?.showHit(hit, term),
    clearSearch: () => handle.current?.clearSearch(),
    current: () => handle.current?.position() ?? info.position,
    describe: (p) => describeNoteLocation(item.format, p, book.chapterCount),
    goTo: (p) => handle.current?.goTo(p),
    pageNotes: false,
    exact: () => handle.current?.exact() ?? info.position * EXACT_SCALE,
    span: () => handle.current?.span() ?? 1,
    goToExact: (p) => handle.current?.goToExact(p),
    exactScale: EXACT_SCALE,
    ready: () => handle.current?.ready() ?? false
  }
  return (
    <ReaderChrome
      title={book.title ?? item.name}
      prefs={prefs}
      onPrefs={changePrefs}
      typography
      tools={tools}
      sync={{ name: initial.name, size: initial.fileSize, format: initial.format, openedAt: initial.openedAt, pageCount: book.chapterCount }}
      readingSeconds={initial.readingSeconds}
      menu={[
        ['Sumário', contents],
        [prefs.paged ? 'Modo: rolagem contínua' : 'Modo: páginas (setas, roda do mouse ou clique duplo na borda)', () => changePrefs({ paged: !prefs.paged })],
        ['Letra menor', () => textSize(-1)],
        ['Letra maior', () => textSize(1)],
        ['Cor da página…', () => chooseTheme(prefs, changePrefs)]
      ]}
      headerExtra={<button type="button" class="bar-button" aria-label="Sumário" title="Sumário (S)" onClick={contents}>☰</button>}
      keys={{ s: contents }}
      footer={
        <>
          <button type="button" class="foot-button" disabled={info.chapter <= 0} aria-label="Capítulo anterior" onClick={() => handle.current?.turnChapter(-1)}>‹ Cap.</button>
          <span class="muted small foot-label">{title ? <><strong>{title}</strong><br /></> : null}Cap. {info.chapter + 1} de {book.chapterCount} · {Math.trunc(info.position / 100)}%</span>
          <button type="button" class="foot-button" disabled={info.chapter >= book.chapterCount - 1} aria-label="Próximo capítulo" onClick={() => handle.current?.turnChapter(1)}>Cap. ›</button>
        </>
      }
      finished={item.finishedAt > 0}
      atEnd={info.atEnd}
      onFinish={finish}
    >
      {(api) => (
        <EpubReader
          book={book}
          paged={prefs.paged}
          textSize={prefs.textSize}
          comfort={prefs.comfort}
          start={start}
          onPosition={(i) => setInfo({ position: i.position, chapter: i.chapter, atEnd: i.atEnd })}
          onTextSize={(size) => changePrefs({ textSize: size })}
          handle={(h) => (handle.current = h)}
          onTap={api.tap}
          onDoubleTap={api.cancelTap}
          onInteraction={api.interaction}
          terms={api.terms}
          onTerm={api.onTerm}
          onSelection={api.selection}
          onNote={api.note}
          onJump={api.jump}
        />
      )}
    </ReaderChrome>
  )
}

/** Leitor de PDF, quadrinhos e DJVU. */
function PageBookView({ item: initial, source, initialPrefs }: { item: LibraryItem; source: PageSource; initialPrefs: ReadingPrefs }) {
  const handle = useRef<PageReaderHandle | null>(null)
  const [info, setInfo] = useState({ page: Math.min(source.pageCount - 1, initial.position), atEnd: false, zoom: 1 })
  const { item, prefs, changePrefs, finish, savePosition } = useBook(initial, initialPrefs, source.pageCount, () => handle.current?.current() ?? info.page)
  useDebouncedSave(info.page, savePosition, 600)
  const askPage = async () => {
    const typed = await promptDialog('Ir para a página', `Página (1 a ${source.pageCount})`, String(info.page + 1), [], 'Ir')
    const number = typed ? Number.parseInt(typed, 10) : NaN
    if (Number.isFinite(number)) handle.current?.goTo(number - 1)
  }
  const toggleLock = () => {
    const locked = !prefs.sideLock
    changePrefs({ sideLock: locked })
    toast(locked ? 'Movimento para os lados bloqueado. Zoom e rolagem para cima e para baixo continuam.' : 'Movimento para os lados liberado.')
  }
  const zoomText = info.zoom > 1.05 ? ` · ${Math.round(info.zoom * 100)}%` : ''
  const tools: ReaderTools = {
    itemId: item.id,
    search: source.pageText ? (term, isCancelled) => handle.current?.search?.(term, isCancelled) ?? Promise.resolve([]) : undefined,
    showHit: (hit, term) => handle.current?.showHit(hit, term),
    clearSearch: () => handle.current?.clearSearch(),
    current: () => handle.current?.current() ?? info.page,
    describe: (p) => describeNoteLocation(item.format, p, source.pageCount),
    goTo: (p) => handle.current?.goTo(p),
    pageNotes: true,
    exact: () => handle.current?.current() ?? info.page,
    span: () => 1,
    goToExact: (p) => handle.current?.goTo(p),
    exactScale: 1,
    ready: () => !!handle.current
  }
  return (
    <ReaderChrome
      title={item.name}
      prefs={prefs}
      onPrefs={changePrefs}
      typography={false}
      tools={tools}
      sync={{ name: initial.name, size: initial.fileSize, format: initial.format, openedAt: initial.openedAt, pageCount: source.pageCount }}
      readingSeconds={initial.readingSeconds}
      keys={{ g: askPage }}
      menu={[
        ['Ir para a página…', askPage],
        [prefs.paged ? 'Modo: rolagem contínua' : 'Modo: páginas (página inteira na tela)', () => changePrefs({ paged: !prefs.paged })],
        ['Cor da página…', () => chooseTheme(prefs, changePrefs)],
        [prefs.sideLock ? 'Liberar movimento para os lados' : 'Bloquear movimento para os lados', toggleLock]
      ]}
      footer={
        <>
          <button type="button" class="foot-button" disabled={info.page <= 0} onClick={() => handle.current?.goTo(info.page - 1)}>‹ Anterior</button>
          <button type="button" class="page-label" title="Ir para a página (G)" onClick={askPage}>Página {info.page + 1} de {source.pageCount}{zoomText}</button>
          <button type="button" class="foot-button" disabled={info.page >= source.pageCount - 1} onClick={() => handle.current?.goTo(info.page + 1)}>Próxima ›</button>
        </>
      }
      overlay={
        <button
          type="button"
          class={prefs.sideLock ? 'lock-button locked' : 'lock-button'}
          aria-label={prefs.sideLock ? 'Desbloquear o movimento para os lados' : 'Bloquear o movimento para os lados'}
          onClick={toggleLock}
        >
          <span class="arrow">⟷</span>
          {prefs.sideLock ? 'Bloqueado' : 'Bloquear'}
        </button>
      }
      finished={item.finishedAt > 0}
      atEnd={info.atEnd}
      onFinish={finish}
    >
      {(api) => (
        <PageReader
          terms={api.terms}
          onTerm={api.onTerm}
          source={source}
          paged={prefs.paged}
          sideLock={prefs.sideLock}
          comfort={prefs.comfort}
          start={Math.min(source.pageCount - 1, initial.position)}
          onPage={(page, atEnd, zoom) => setInfo({ page, atEnd, zoom })}
          handle={(h) => (handle.current = h)}
        />
      )}
    </ReaderChrome>
  )
}
