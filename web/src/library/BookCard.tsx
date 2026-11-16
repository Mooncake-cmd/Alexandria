import { useEffect, useState } from 'preact/hooks'
import { displayTitle } from '../core/library'
import { describeStatus, progressOf, READABLE } from '../core/formats'
import { formatReadingTime } from '../core/readingTime'
import { coverOf, isArchived, isFinished, onLibraryChange, type LibraryItem } from '../storage/library'

const dateFormat = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' })

/** "Concluído em …", "25% lido" etc., com o tempo de leitura quando houver. Porte de statusText. */
export function statusText(item: LibraryItem): string {
  let base: string
  if (isArchived(item)) base = `Concluído em ${dateFormat.format(item.finishedAt || item.archivedAt)} · arquivo removido`
  else if (isFinished(item)) base = `Concluído em ${dateFormat.format(item.finishedAt)}`
  else base = describeStatus(item)
  return item.readingSeconds >= 60 ? `${base} · ⏱ ${formatReadingTime(item.readingSeconds)}` : base
}

/** URL temporária da capa do livro (personalizada ou automática), ou null. */
export function useCover(id: number): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    let current: string | null = null
    let alive = true
    const load = () =>
      coverOf(id).then((blob) => {
        if (!alive) return
        if (current) URL.revokeObjectURL(current)
        current = blob ? URL.createObjectURL(blob) : null
        setUrl(current)
      })
    load()
    const off = onLibraryChange(load)
    return () => {
      alive = false
      off()
      if (current) URL.revokeObjectURL(current)
    }
  }, [id])
  return url
}

export function Cover({ item, class: className = 'cover' }: { item: LibraryItem; class?: string }) {
  const url = useCover(item.id)
  if (url) return <img class={className} src={url} alt="" loading="lazy" />
  return (
    <div class={`${className} cover-badge${isFinished(item) ? ' finished' : ''}`} aria-hidden="true">
      {item.format.toUpperCase()}
    </div>
  )
}

export function BookCard({ item, onOpen, onOptions }: { item: LibraryItem; onOpen: () => void; onOptions: (e: MouseEvent) => void }) {
  const progress = progressOf(item)
  return (
    <article
      class="card"
      onContextMenu={(e) => {
        e.preventDefault()
        onOptions(e)
      }}
    >
      <button type="button" class="card-main" onClick={onOpen} aria-label={`${displayTitle(item.name)}. ${statusText(item)}. Abrir`}>
        <Cover item={item} />
        <div class="card-info">
          <h3 class="card-title">{displayTitle(item.name)}</h3>
          <p class="card-format">{item.format.toUpperCase()}</p>
          <p class="card-status">{statusText(item)}</p>
          {READABLE.has(item.format) && (
            <div class="progress" role="progressbar" aria-label="Progresso de leitura" aria-valuenow={Math.round(progress * 100)} aria-valuemin={0} aria-valuemax={100}>
              <div style={{ width: `${progress * 100}%` }} />
            </div>
          )}
        </div>
      </button>
      <button type="button" class="card-more" aria-label={`Opções de ${displayTitle(item.name)}`} title="Opções (ou clique com o botão direito)" onClick={(e) => onOptions(e as unknown as MouseEvent)}>
        ⋯
      </button>
    </article>
  )
}
