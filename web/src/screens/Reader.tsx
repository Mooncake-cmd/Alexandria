import { useEffect, useRef, useState } from 'preact/hooks'
import { displayTitle } from '../core/library'
import { THEMES } from '../core/comfort'
import { bookFile, findItem, isFinished, setFinished, updateProgress, type LibraryItem } from '../storage/library'
import { loadPrefs, savePrefs, MAX_TEXT_SIZE, MIN_TEXT_SIZE, type ReadingPrefs } from '../reader/prefs'
import { loadDocx, loadTxt, type TextContent } from '../reader/textContent'
import { TextReader, type TextReaderHandle } from '../reader/TextReader'
import { menuDialog, toast } from '../ui/dialogs'

type State =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'pending'; item: LibraryItem }
  | { status: 'text'; item: LibraryItem; content: TextContent; prefs: ReadingPrefs }

/** Formatos com leitor no site até agora (os demais chegam nas próximas etapas do plano). */
const TEXT_FORMATS = new Set(['txt', 'docx'])

export function Reader({ bookId }: { bookId: number }) {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    let alive = true
    let content: TextContent | null = null
    ;(async () => {
      const item = await findItem(bookId)
      if (!item) return setState({ status: 'error', message: 'Documento não encontrado na biblioteca.' })
      if (!TEXT_FORMATS.has(item.format)) return setState({ status: 'pending', item })
      const file = await bookFile(bookId)
      if (!file) return setState({ status: 'error', message: 'O arquivo deste livro não está guardado neste navegador. Importe-o de novo.' })
      try {
        const [loaded, prefs] = await Promise.all([item.format === 'docx' ? loadDocx(file) : loadTxt(file), loadPrefs()])
        content = loaded
        if (!alive) return loaded.dispose()
        if (!loaded.chunks.length) return setState({ status: 'error', message: 'Este documento está vazio.' })
        if (loaded.truncated) toast('Arquivo muito grande: exibindo só o começo.')
        setState({ status: 'text', item, content: loaded, prefs })
      } catch (e) {
        console.error(e)
        setState({ status: 'error', message: 'Não foi possível abrir este documento.' })
      }
    })()
    return () => {
      alive = false
      content?.dispose()
    }
  }, [bookId])

  if (state.status === 'loading') return <div class="reader-message">Carregando…</div>
  if (state.status === 'error') return <ReaderMessage text={state.message} />
  if (state.status === 'pending') return <ReaderMessage title={displayTitle(state.item.name)} text={`O leitor de ${state.item.format.toUpperCase()} do site ainda está em construção.`} />
  return <TextBook item={state.item} content={state.content} initialPrefs={state.prefs} />
}

function ReaderMessage({ title, text }: { title?: string; text: string }) {
  return (
    <div class="reader-message">
      <a href="#/biblioteca" class="back">‹ Biblioteca</a>
      {title && <h2>{title}</h2>}
      <p class="muted">{text}</p>
    </div>
  )
}

/** Moldura do leitor de TXT/DOCX: barra de cima, texto, aviso de fim e controles de baixo. */
function TextBook({ item: initial, content, initialPrefs }: { item: LibraryItem; content: TextContent; initialPrefs: ReadingPrefs }) {
  const [item, setItem] = useState(initial)
  const [prefs, setPrefs] = useState(initialPrefs)
  const [position, setPosition] = useState({ fraction: initial.position, atEnd: false })
  const handle = useRef<TextReaderHandle | null>(null)
  const saved = useRef(initial.position)

  const changePrefs = (change: Partial<ReadingPrefs>) => {
    const next = { ...prefs, ...change }
    setPrefs(next)
    savePrefs(next)
  }

  // Salva o ponto de leitura (sem gravar a cada pixel) e ao sair do livro ou fechar a aba.
  useEffect(() => {
    updateProgress(item.id, initial.position)
    const save = () => {
      const fraction = handle.current?.fraction() ?? saved.current
      saved.current = fraction
      updateProgress(item.id, fraction)
    }
    window.addEventListener('pagehide', save)
    document.addEventListener('visibilitychange', save)
    return () => {
      save()
      window.removeEventListener('pagehide', save)
      document.removeEventListener('visibilitychange', save)
    }
  }, [])
  useEffect(() => {
    const timer = setTimeout(() => {
      if (position.fraction !== saved.current) {
        saved.current = position.fraction
        updateProgress(item.id, position.fraction)
      }
    }, 800)
    return () => clearTimeout(timer)
  }, [position.fraction])

  const textSize = (delta: number) => changePrefs({ textSize: Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, prefs.textSize + delta)) })

  const finish = async (done: boolean) => {
    await setFinished(item.id, done)
    setItem({ ...item, finishedAt: done ? Date.now() : 0 })
    toast(done ? `"${displayTitle(item.name)}" foi para a Estante.` : `"${displayTitle(item.name)}" voltou para "Lendo agora".`)
  }

  const menu = async () => {
    const actions: [string, () => void][] = [
      [prefs.paged ? 'Modo: rolagem contínua' : 'Modo: páginas (setas, roda do mouse ou clique duplo na borda)', () => changePrefs({ paged: !prefs.paged })],
      ['Letra menor', () => textSize(-1)],
      ['Letra maior', () => textSize(1)],
      ['Cor da página…', async () => {
        const themes = Object.entries(THEMES)
        const choice = await menuDialog('Cor da página', themes.map(([key, t]) => (key === prefs.comfort.theme ? `▸ ${t.label}` : t.label)))
        if (choice !== null) changePrefs({ comfort: { ...prefs.comfort, theme: themes[choice][0] as keyof typeof THEMES } })
      }],
      [isFinished(item) ? 'Voltar para "Lendo agora"' : 'Concluir livro', () => finish(!isFinished(item))]
    ]
    const choice = await menuDialog('Opções de leitura', actions.map((a) => a[0]))
    if (choice !== null) actions[choice][1]()
  }

  const percent = Math.trunc(position.fraction / 100)
  return (
    <div class="reader" data-theme-dark={THEMES[prefs.comfort.theme].dark ? 'true' : undefined}>
      <header class="reader-bar">
        <a href="#/biblioteca" class="bar-button" aria-label="Voltar para a biblioteca">‹</a>
        <div class="reader-title">
          <strong>{item.name}</strong>
        </div>
        <button type="button" class="bar-button" aria-label="Mais opções" onClick={menu}>⋮</button>
      </header>
      <div class="reader-body">
        <TextReader
          content={content}
          paged={prefs.paged}
          textSize={prefs.textSize}
          comfort={prefs.comfort}
          start={initial.position}
          onPosition={(fraction, atEnd) => setPosition({ fraction, atEnd })}
          onTextSize={(size) => changePrefs({ textSize: size })}
          handle={(h) => (handle.current = h)}
        />
      </div>
      {position.atEnd && !isFinished(item) && (
        <div class="finish-banner">
          <span>Você chegou ao fim.</span>
          <button type="button" onClick={() => finish(true)}>🏁 Concluir livro</button>
        </div>
      )}
      <footer class="reader-foot">
        <button type="button" class="foot-button" aria-label="Diminuir letra" onClick={() => textSize(-1)}>A-</button>
        <span class="muted small">{position.atEnd ? 'Fim' : `${percent}% lido`}</span>
        <button type="button" class="foot-button" aria-label="Aumentar letra" onClick={() => textSize(1)}>A+</button>
      </footer>
    </div>
  )
}
