import { useEffect, useRef, useState } from 'preact/hooks'
import { shelfCompare } from '../core/library'
import { isArchived, isFinished, type LibraryItem } from '../storage/library'
import { useLibrary } from '../storage/useLibrary'
import { ACCEPT, importFiles } from '../library/importBooks'
import { BookCard } from '../library/BookCard'
import { itemOptions } from '../library/itemOptions'
import { ensureCovers } from '../library/covers'
import { toast } from '../ui/dialogs'
import { Empty } from './Empty'

/** Prateleiras abertas (como no app, começam fechadas); lembradas enquanto o site está aberto. */
const openShelves = new Set<string>()
let estanteOpen = false

export function openBook(item: LibraryItem) {
  if (isArchived(item)) {
    toast(`O arquivo de "${item.name}" foi apagado para liberar espaço. Importe o mesmo arquivo de novo para ler.`)
    return
  }
  location.hash = `#/ler/${item.id}`
}

export function Library() {
  const { items, loaded } = useLibrary()
  const [, redraw] = useState(0)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (loaded) ensureCovers(items)
  }, [loaded, items])
  const reading = items.filter((i) => !isFinished(i))
  const finished = items.filter((i) => isFinished(i))

  const grouped = (list: LibraryItem[]) => {
    const loose = list.filter((i) => !i.shelf)
    const groups = new Map<string, LibraryItem[]>()
    for (const i of list.filter((i) => i.shelf)) groups.set(i.shelf, [...(groups.get(i.shelf) ?? []), i])
    return (
      <>
        {loose.length > 0 && <div class="grid">{loose.map((i) => card(i))}</div>}
        {[...groups.keys()].sort(shelfCompare).map((name) => {
          const open = openShelves.has(name)
          const books = groups.get(name)!
          return (
            <section key={name} class="shelf">
              <button
                type="button"
                class="shelf-title"
                aria-expanded={open}
                onClick={() => {
                  if (!openShelves.delete(name)) openShelves.add(name)
                  redraw((n) => n + 1)
                }}
              >
                {open ? '▼' : '▶'}  📁 {name} ({books.length})
              </button>
              {open && <div class="grid">{books.map((i) => card(i))}</div>}
            </section>
          )
        })}
      </>
    )
  }

  const card = (item: LibraryItem) => <BookCard key={item.id} item={item} onOpen={() => openBook(item)} onOptions={() => itemOptions(item)} />

  return (
    <>
      <h2>Minha biblioteca</h2>
      <p class="muted">Seus documentos ficam guardados só neste navegador, no seu computador.</p>
      <div class="import-row">
        <button class="primary" type="button" onClick={() => input.current?.click()}>＋ Importar documentos</button>
        <span class="muted small">ou arraste os arquivos para esta janela</span>
      </div>
      <input
        ref={input}
        type="file"
        multiple
        accept={ACCEPT}
        hidden
        onChange={async (e) => {
          const target = e.target as HTMLInputElement
          await importFiles(Array.from(target.files ?? []))
          target.value = ''
        }}
      />
      <p class="label">LEITURA DISPONÍVEL</p>
      <p>PDF · EPUB · MOBI · AZW3 · DJVU · CBZ · CBR · DOCX · TXT</p>

      <h2>Lendo agora</h2>
      <p class="muted small">{reading.length} documento(s)</p>
      {loaded && items.length === 0 ? (
        <Empty icon="▤" title="Sua biblioteca começa aqui" message="Importe um PDF, EPUB, MOBI, AZW3, DJVU, quadrinho CBZ/CBR, DOCX ou TXT para começar a ler." />
      ) : reading.length === 0 ? (
        <p class="muted small">Nenhum livro em andamento. Os concluídos estão na Estante, abaixo.</p>
      ) : (
        grouped(reading)
      )}

      {items.length > 0 && (
        <section class="estante">
          <button
            type="button"
            class="estante-title"
            aria-expanded={estanteOpen}
            onClick={() => {
              estanteOpen = !estanteOpen
              redraw((n) => n + 1)
            }}
          >
            {estanteOpen ? '▼' : '▶'}  🏆 Estante · {finished.length} concluído(s)
          </button>
          {estanteOpen &&
            (finished.length === 0 ? (
              <p class="muted small">Nenhum livro concluído ainda. Ao chegar no fim de um livro, use "Concluir livro".</p>
            ) : (
              grouped(finished)
            ))}
        </section>
      )}
      {items.length > 0 && <p class="muted small hint">Clique num livro para abri-lo. Clique com o botão direito (ou em ⋯) para mover de prateleira, concluir ou remover.</p>}
    </>
  )
}
