import { useEffect, useRef, useState } from 'preact/hooks'
import { fold } from '../core/text'
import type { GlossaryTerm } from '../core/glossary'
import { addNote, removeNote, removeTerm, saveTerm, updateNote, notesFor, type Note } from '../storage/library'
import { useLibraryData } from '../storage/useLibrary'
import { confirmDialog, promptDialog, toast } from '../ui/dialogs'

export interface SearchHit {
  position: number
  label: string
  excerpt: string
  /** Dados do formato para ir ao resultado (bloco, capítulo e ocorrência, página…). */
  data?: unknown
}

/** O que cada leitor oferece às ferramentas (busca, anotações, glossário). */
export interface ReaderTools {
  itemId: number
  search?: (term: string, isCancelled: () => boolean) => Promise<SearchHit[]>
  showHit?: (hit: SearchHit, term: string) => void
  clearSearch?: () => void
  current: () => number
  describe: (position: number) => string
  goTo: (position: number) => void
  /** Formatos de página anotam a página inteira; os de texto, o trecho selecionado. */
  pageNotes: boolean
  /** Posição precisa (marcadores, "Voltar"): página, ou fração de 0 a 1.000.000 nos textos. */
  exact: () => number
  /** Quanto da posição precisa cabe numa tela (1 = uma página). */
  span: () => number
  goToExact: (position: number) => void
  /** Posição precisa ÷ exactScale = posição de [describe]. */
  exactScale: number
}

export function SearchPanel({ tools, onClose }: { tools: ReaderTools; onClose: () => void }) {
  const [term, setTerm] = useState('')
  const [hits, setHits] = useState<SearchHit[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [current, setCurrent] = useState(-1)
  const run = useRef(0)
  useEffect(() => () => tools.clearSearch?.(), [])
  const search = async (e: Event) => {
    e.preventDefault()
    const id = ++run.current
    if (!term.trim() || !tools.search) return
    setBusy(true)
    setHits(null)
    const found = await tools.search(term, () => id !== run.current)
    if (id !== run.current) return
    setBusy(false)
    setHits(found)
    setCurrent(-1)
    if (found.length) show(found, 0)
  }
  const show = (list: SearchHit[], index: number) => {
    setCurrent(index)
    tools.showHit?.(list[index], term)
  }
  return (
    <aside class="side-panel" aria-label="Buscar no livro">
      <header>
        <h3>Buscar no livro</h3>
        <button type="button" class="close" aria-label="Fechar" onClick={onClose}>×</button>
      </header>
      <form onSubmit={search} class="search-form">
        <input type="search" placeholder="Palavra ou trecho (sem precisar de acentos)" value={term} onInput={(e) => setTerm((e.target as HTMLInputElement).value)} autoFocus />
        <button type="submit" class="primary-small" disabled={!term.trim()}>Buscar</button>
      </form>
      {busy && <p class="muted small">Buscando…</p>}
      {hits && (
        <>
          <div class="search-nav">
            <span class="muted small">{hits.length ? `${current + 1} de ${hits.length} resultado(s)` : 'Nada encontrado.'}</span>
            {hits.length > 1 && (
              <span>
                <button type="button" class="chip" onClick={() => show(hits, (current - 1 + hits.length) % hits.length)} aria-label="Resultado anterior">‹</button>
                <button type="button" class="chip" onClick={() => show(hits, (current + 1) % hits.length)} aria-label="Próximo resultado">›</button>
              </span>
            )}
          </div>
          <ol class="hit-list">
            {hits.slice(0, 500).map((h, i) => (
              <li key={i}>
                <button type="button" class={i === current ? 'active' : ''} onClick={() => show(hits, i)}>
                  <b>{h.label}</b> {h.excerpt}
                </button>
              </li>
            ))}
          </ol>
          {hits.length > 500 && <p class="muted small">Mostrando os 500 primeiros.</p>}
        </>
      )}
    </aside>
  )
}

export function NotesPanel({ tools, terms, onClose }: { tools: ReaderTools; terms: GlossaryTerm[]; onClose: () => void }) {
  const notes = useLibraryData<Note[]>(() => notesFor(tools.itemId).then((n) => n.sort((a, b) => a.position - b.position)), [], [tools.itemId])
  const [tab, setTab] = useState<'notes' | 'terms'>('notes')
  return (
    <aside class="side-panel" aria-label="Anotações e glossário">
      <header>
        <h3>{tab === 'notes' ? 'Anotações' : 'Glossário do livro'}</h3>
        <button type="button" class="close" aria-label="Fechar" onClick={onClose}>×</button>
      </header>
      <div class="tabs">
        <button type="button" class={tab === 'notes' ? 'chip active' : 'chip'} onClick={() => setTab('notes')}>Anotações ({notes.length})</button>
        <button type="button" class={tab === 'terms' ? 'chip active' : 'chip'} onClick={() => setTab('terms')}>Glossário ({terms.length})</button>
      </div>
      {tab === 'notes' ? (
        <>
          {tools.pageNotes && (
            <button type="button" class="secondary-small" onClick={() => annotate(tools, '')}>＋ Anotar esta página</button>
          )}
          {!tools.pageNotes && <p class="muted small">Selecione um trecho e clique em "Anotar".</p>}
          {notes.length === 0 && <p class="muted small">Nenhuma anotação neste livro ainda.</p>}
          <ul class="note-list">
            {notes.map((n) => (
              <li key={n.id}>
                <button type="button" class="note-open" onClick={() => tools.goTo(n.position)}>
                  <span class="note-where">{tools.describe(n.position)}</span>
                  {n.excerpt && <q>{n.excerpt}</q>}
                  {n.text && <span class="note-text">{n.text}</span>}
                </button>
                <div class="note-actions">
                  <button type="button" class="link" onClick={async () => {
                    const text = await promptDialog('Editar anotação', 'Anotação', n.text, [], 'Salvar')
                    if (text !== null) updateNote(n.id, text)
                  }}>Editar</button>
                  <button type="button" class="link" onClick={async () => {
                    if (await confirmDialog('Apagar anotação?', n.excerpt || n.text, 'Apagar', true)) removeNote(n.id)
                  }}>Apagar</button>
                </div>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <>
          <p class="muted small">Selecione uma palavra ou expressão e clique em "Definir termo". Ela fica sublinhada no livro; clicar nela mostra a definição.</p>
          <button type="button" class="secondary-small" onClick={() => defineTerm(tools.itemId, '')}>＋ Novo termo</button>
          <ul class="note-list">
            {[...terms].sort((a, b) => a.term.localeCompare(b.term, 'pt-BR')).map((t) => (
              <li key={t.id}>
                <div class="note-open static"><b>{t.term}</b><span class="note-text">{t.definition}</span></div>
                <div class="note-actions">
                  <button type="button" class="link" onClick={() => defineTerm(tools.itemId, t.term, t.definition)}>Editar</button>
                  <button type="button" class="link" onClick={async () => {
                    if (await confirmDialog('Remover termo?', `"${t.term}" deixa de ficar sublinhado neste livro.`, 'Remover', true)) removeTerm(t.id)
                  }}>Remover</button>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </aside>
  )
}

/** Anota o trecho selecionado (ou a página, nos formatos de página). */
export async function annotate(tools: ReaderTools, excerpt: string) {
  const text = await promptDialog(excerpt ? 'Anotar trecho' : 'Anotar esta página', excerpt ? `“${excerpt.slice(0, 120)}${excerpt.length > 120 ? '…' : ''}”` : 'Anotação', '', [], 'Salvar')
  if (text === null) return
  await addNote(tools.itemId, excerpt, text, tools.current())
  toast('Anotação salva.')
}

/** Define (ou edita) um termo do glossário do livro. */
export async function defineTerm(itemId: number, term: string, definition = '') {
  let name = term.trim().slice(0, 80)
  if (!name) {
    const typed = await promptDialog('Novo termo', 'Palavra ou expressão', '', [], 'Continuar')
    if (!typed) return
    name = typed.trim().slice(0, 80)
  }
  const text = await promptDialog(`Definir "${name}"`, 'Definição', definition, [], 'Salvar')
  if (text === null) return
  await saveTerm(itemId, name, text, fold)
  toast(`"${name}" agora fica sublinhado neste livro.`)
}
