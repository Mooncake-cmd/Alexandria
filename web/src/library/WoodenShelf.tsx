import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { displayTitle } from '../core/library'
import type { LibraryItem } from '../storage/library'
import { statusText, useCover } from './BookCard'
import { leatherOf, shelfWoods } from './wood'

/** Um grupo da Estante: os livros soltos (sem nome) ou uma prateleira com nome. */
export interface ShelfSection {
  title: string | null
  books: LibraryItem[]
  open: boolean
}

/** Largura de cada "vaga" de livro na tábua; o livro ocupa 70% dela (como o app). */
const CELL = 168

/**
 * Estante de madeira (como a do app): a prateleira de cima com a planta e o globo e, abaixo, os
 * livros concluídos em pé, de capa para a frente, com lombada, páginas e sombra. Passar o mouse
 * (ou focar com o teclado) gira o livro um pouco em 3D; clique abre; botão direito mostra as opções.
 * Cada prateleira com nome tem uma placa de latão: clique abre/fecha, botão direito renomeia.
 */
export function WoodenShelf({ sections, onOpen, onOptions, onToggleShelf, onRenameShelf }: {
  sections: ShelfSection[]
  onOpen: (item: LibraryItem) => void
  onOptions: (item: LibraryItem) => void
  onToggleShelf: (name: string) => void
  onRenameShelf: (name: string) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(900)
  const woods = useMemo(shelfWoods, [])
  useEffect(() => {
    const el = box.current!
    const observer = new ResizeObserver(() => setWidth(el.clientWidth))
    observer.observe(el)
    setWidth(el.clientWidth)
    return () => observer.disconnect()
  }, [])
  const inner = Math.max(300, width - 24)
  const columns = Math.max(3, Math.floor(inner / CELL))
  const cell = inner / columns
  const bookWidth = Math.round(cell * 0.7)
  const bookHeight = Math.round(bookWidth * 1.48)

  const rows = (books: LibraryItem[]) => {
    const out: LibraryItem[][] = []
    for (let i = 0; i < books.length; i += columns) out.push(books.slice(i, i + columns))
    return out
  }

  return (
    <div
      ref={box}
      class="wood-shelf"
      style={{ '--wood-back': `url(${woods.back})`, '--wood-frame': `url(${woods.frame})`, '--wood-board': `url(${woods.board})` } as Record<string, string>}
    >
      <div class="wood-crown" />
      <div class="wood-inner">
        <div class="wood-row wood-decor" style={{ height: `${Math.round(bookHeight * 0.92) + 22}px` }}>
          <img class="decor-plant" src={`${import.meta.env.BASE_URL}shelf/planta.webp`} alt="" />
          <img class="decor-globe" src={`${import.meta.env.BASE_URL}shelf/globo.webp`} alt="" />
        </div>
        <div class="wood-board" />
        {sections.filter((s) => s.books.length).map((section) => (
          <div key={section.title ?? '__soltos'} class="wood-section">
            {section.title !== null && (
              <div class="wood-plate-row">
                <button
                  type="button"
                  class="wood-plate"
                  aria-expanded={section.open}
                  title="Clique para abrir ou fechar; botão direito para renomear"
                  onClick={() => onToggleShelf(section.title!)}
                  onContextMenu={(e) => {
                    e.preventDefault()
                    onRenameShelf(section.title!)
                  }}
                >
                  <span class="plate-arrow" aria-hidden="true">{section.open ? '▼' : '▶'}</span>
                  {section.title} · {section.books.length}
                </button>
              </div>
            )}
            {section.open &&
              rows(section.books).map((row, r) => (
                <div key={r}>
                  <div class="wood-row" style={{ gridTemplateColumns: `repeat(${columns}, 1fr)`, height: `${bookHeight + 22}px` }}>
                    {row.map((item) => (
                      <ShelfBook key={item.id} item={item} width={bookWidth} height={bookHeight} onOpen={() => onOpen(item)} onOptions={() => onOptions(item)} />
                    ))}
                  </div>
                  <div class="wood-board" />
                </div>
              ))}
          </div>
        ))}
      </div>
      <div class="wood-plinth" />
    </div>
  )
}

function ShelfBook({ item, width, height, onOpen, onOptions }: { item: LibraryItem; width: number; height: number; onOpen: () => void; onOptions: () => void }) {
  const cover = useCover(item.id)
  const title = displayTitle(item.name)
  return (
    <div class="shelf-slot">
      <button
        type="button"
        class="shelf-book"
        style={{ width: `${width}px`, height: `${height}px` }}
        aria-label={`${title}. ${statusText(item)}`}
        title={`${title}\n${statusText(item)}`}
        onClick={onOpen}
        onContextMenu={(e) => {
          e.preventDefault()
          onOptions()
        }}
      >
        <span class="book-pages" aria-hidden="true" />
        <span class="book-cover" aria-hidden="true">
          {cover ? <img src={cover} alt="" /> : <Leather title={title} format={item.format} width={width} />}
        </span>
        <span class="book-spine" aria-hidden="true" />
        <span class="book-light" aria-hidden="true" />
      </button>
    </div>
  )
}

/** Capa de couro com o título em dourado, para livros sem capa (TXT, DOCX…). */
function Leather({ title, format, width }: { title: string; format: string; width: number }) {
  const upper = title.toUpperCase()
  const longest = upper.split(/\s+/).reduce((a, b) => (b.length > a.length ? b : a), '')
  // Letra menor até a palavra mais longa caber inteira na linha (sem "MEMÓRI-AS").
  const size = Math.max(width * 0.075, Math.min(width * 0.125, (width * 0.7) / Math.max(1, longest.length * 0.66)))
  return (
    <span class="leather" style={{ background: `linear-gradient(135deg, color-mix(in srgb, ${leatherOf(title)} 88%, white), color-mix(in srgb, ${leatherOf(title)} 70%, black))` }}>
      <span class="leather-frame" />
      <span class="leather-title" style={{ fontSize: `${size}px` }}>{upper}</span>
      <span class="leather-ornament">— ◆ —</span>
      <span class="leather-format">{format.toUpperCase()}</span>
    </span>
  )
}
