import { useEffect, useState } from 'preact/hooks'
import { displayTitle } from '../core/library'
import { findItem, type LibraryItem } from '../storage/library'

/** Leitor. Os formatos chegam nas próximas etapas do plano (TXT/DOCX, EPUB/MOBI, PDF/quadrinhos/DJVU). */
export function Reader({ bookId }: { bookId: number }) {
  const [item, setItem] = useState<LibraryItem | null | undefined>(undefined)
  useEffect(() => {
    findItem(bookId).then((i) => setItem(i ?? null))
  }, [bookId])
  return (
    <div class="reader-placeholder">
      <a href="#/biblioteca" class="back">‹ Biblioteca</a>
      {item === null && <p>Livro não encontrado na biblioteca.</p>}
      {item && (
        <>
          <h2>{displayTitle(item.name)}</h2>
          <p class="muted">O leitor de {item.format.toUpperCase()} do site ainda está em construção.</p>
        </>
      )}
    </div>
  )
}
