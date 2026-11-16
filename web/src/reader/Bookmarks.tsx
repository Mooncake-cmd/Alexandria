import { Modal } from '../ui/dialogs'
import type { Bookmark } from '../core/bookmarks'

/**
 * Canto de cima à direita da página: botão invisível que marca (ou desmarca) a página. Com a
 * página marcada, mostra a fita azul do marcador.
 */
export function BookmarkCorner({ marked, onToggle }: { marked: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      class={marked ? 'bookmark-corner marked' : 'bookmark-corner'}
      aria-label={marked ? 'Desmarcar esta página (B)' : 'Marcar esta página (B)'}
      title={marked ? 'Desmarcar esta página (B)' : 'Marcar esta página (B)'}
      aria-pressed={marked}
      onClick={(e) => {
        e.stopPropagation()
        onToggle()
      }}
    >
      {marked && (
        <svg viewBox="0 0 18 30" width="18" height="30" aria-hidden="true">
          <path d="M0 0H18V30L9 21.9L0 30Z" />
        </svg>
      )}
    </button>
  )
}

/** Lista dos marcadores do livro: clicar leva à página; ⋮ edita o nome ou exclui. */
export function BookmarkList({ marks, describe, onOpen, onOptions, onClose }: {
  marks: Bookmark[]
  describe: (position: number) => string
  onOpen: (index: number) => void
  onOptions: (mark: Bookmark) => void
  onClose: () => void
}) {
  return (
    <Modal title={marks.length ? `Marcadores (${marks.length})` : 'Marcadores'} onCancel={onClose}>
      {marks.length === 0 ? (
        <p class="modal-text">Nenhum marcador ainda. Clique no canto de cima, à direita da página (ou aperte B), para marcá-la. Clique de novo no canto para desmarcar.</p>
      ) : (
        <ul class="bookmark-list">
          {marks.map((mark, index) => (
            <li key={mark.id}>
              <button type="button" class="bookmark-open" onClick={() => onOpen(index)}>
                <span class="bookmark-name">{mark.name}</span>
                <span class="muted small">{describe(mark.position)}</span>
              </button>
              <button type="button" class="bookmark-more" aria-label={`Opções de ${mark.name}`} onClick={() => onOptions(mark)}>⋮</button>
            </li>
          ))}
        </ul>
      )}
      <div class="modal-actions">
        <button type="button" class="primary-action" onClick={onClose}>Fechar</button>
      </div>
    </Modal>
  )
}

/** Barra dos marcadores: ✕ sai, ☰ volta à lista, "1 de 3" e as setas vão de um marcador a outro. */
export function BookmarkBar({ label, canPrev, canNext, onExit, onList, onStep }: {
  label: string
  canPrev: boolean
  canNext: boolean
  onExit: () => void
  onList: () => void
  onStep: (direction: 1 | -1) => void
}) {
  return (
    <div class="bookmark-bar" role="toolbar" aria-label="Marcadores">
      <button type="button" class="mark-action" aria-label="Sair dos marcadores" title="Sair dos marcadores" onClick={onExit}>✕</button>
      <button type="button" class="mark-action" aria-label="Lista de marcadores" title="Lista de marcadores" onClick={onList}>☰</button>
      <span class="mark-label" aria-live="polite">{label}</span>
      <button type="button" class="mark-step" aria-label="Marcador anterior" title="Marcador anterior" disabled={!canPrev} onClick={() => onStep(-1)}>‹</button>
      <button type="button" class="mark-step" aria-label="Próximo marcador" title="Próximo marcador" disabled={!canNext} onClick={() => onStep(1)}>›</button>
    </div>
  )
}
