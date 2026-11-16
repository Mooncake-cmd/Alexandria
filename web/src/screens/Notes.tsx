import { displayTitle } from '../core/library'
import { describeNoteLocation } from '../core/formats'
import type { GlossaryTerm } from '../core/glossary'
import { allNotes, allTerms, removeNote, removeTerm, type Note } from '../storage/library'
import { useLibrary, useLibraryData } from '../storage/useLibrary'
import { confirmDialog, toast } from '../ui/dialogs'
import { Empty } from './Empty'

/** Aba Notas: todas as anotações, por livro, e os glossários. Clicar abre o livro no ponto. */
export function Notes() {
  const { items } = useLibrary()
  const notes = useLibraryData<Note[]>(allNotes, [])
  const terms = useLibraryData<GlossaryTerm[]>(allTerms, [])
  const byId = new Map(items.map((i) => [i.id, i]))
  const groups = new Map<number, Note[]>()
  for (const n of notes) groups.set(n.itemId, [...(groups.get(n.itemId) ?? []), n])
  const glossaries = new Map<number, GlossaryTerm[]>()
  for (const t of terms) glossaries.set(t.itemId, [...(glossaries.get(t.itemId) ?? []), t])

  const open = (itemId: number, position: number) => {
    const item = byId.get(itemId)
    if (!item) return
    if (item.archivedAt > 0) return toast('O arquivo deste livro foi apagado para liberar espaço. Importe de novo para ler.')
    location.hash = `#/ler/${itemId}/${position}`
  }

  return (
    <>
      <h2>Minhas anotações</h2>
      <p class="muted">{notes.length} anotação(ões) em {groups.size} livro(s)</p>
      {glossaries.size > 0 && (
        <>
          <p class="label">GLOSSÁRIOS</p>
          {[...glossaries.entries()].map(([itemId, list]) => {
            const item = byId.get(itemId)
            if (!item) return null
            return (
              <details key={itemId} class="glossary">
                <summary>📖 {displayTitle(item.name)} · {list.length} termo(s)</summary>
                <ul class="plain-list">
                  {[...list].sort((a, b) => a.term.localeCompare(b.term, 'pt-BR')).map((t) => (
                    <li key={t.id} class="term-row">
                      <span><b>{t.term}</b> — {t.definition}</span>
                      <button type="button" class="link" onClick={async () => {
                        if (await confirmDialog('Remover termo?', `"${t.term}" deixa de ficar sublinhado em "${displayTitle(item.name)}".`, 'Remover', true)) removeTerm(t.id)
                      }}>Remover</button>
                    </li>
                  ))}
                </ul>
              </details>
            )
          })}
        </>
      )}
      {notes.length === 0 ? (
        <Empty icon="✎" title="Nenhuma anotação ainda" message='No leitor, selecione um trecho e clique em "Anotar". Em PDF, quadrinhos e DJVU, use 📝 → "Anotar esta página".' />
      ) : (
        [...groups.entries()].map(([itemId, list]) => {
          const item = byId.get(itemId)
          if (!item) return null
          return (
            <section key={itemId}>
              <h3 class="book-heading">{displayTitle(item.name)}</h3>
              <div class="grid">
                {[...list].sort((a, b) => a.position - b.position).map((n) => (
                  <article key={n.id} class="note-card">
                    <button type="button" class="note-open" onClick={() => open(itemId, n.position)}>
                      <span class="note-where">{describeNoteLocation(item.format, n.position, item.pageCount)}</span>
                      {n.excerpt && <q>{n.excerpt}</q>}
                      {n.text && <span class="note-text">{n.text}</span>}
                    </button>
                    <button type="button" class="card-more" aria-label="Apagar anotação" title="Apagar" onClick={async () => {
                      if (await confirmDialog('Apagar anotação?', n.excerpt || n.text, 'Apagar', true)) removeNote(n.id)
                    }}>×</button>
                  </article>
                ))}
              </div>
            </section>
          )
        })
      )}
      {notes.length > 0 && <p class="muted small hint">Clique numa anotação para abrir o livro no trecho.</p>}
    </>
  )
}
