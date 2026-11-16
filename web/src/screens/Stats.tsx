import { displayTitle } from '../core/library'
import { epochDay, formatDay, parseDay, STREAK_MIN_SECONDS, streakSummary, today } from '../core/days'
import { formatReadingTime } from '../core/readingTime'
import { allNotes, clearStatistics, isFinished, readingDays, type LibraryItem, type Note } from '../storage/library'
import { useLibrary, useLibraryData } from '../storage/useLibrary'
import { confirmClearBookTime } from '../library/itemOptions'
import { confirmDialog, toast } from '../ui/dialogs'

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro']

/** Estatísticas (como a aba do app): sequência, tempo, calendário do mês, livros e formatos. */
export function Stats() {
  const { items } = useLibrary()
  const days = useLibraryData<Record<string, number>>(readingDays, {})
  const notes = useLibraryData<Note[]>(allNotes, [])
  const now = today()
  const streak = streakSummary(days, now)
  const total = Object.values(days).reduce((a, b) => a + b, 0)
  const reading = items.filter((i) => i.readingSeconds >= 60).sort((a, b) => b.readingSeconds - a.readingSeconds).slice(0, 5)
  const formats = new Map<string, number>()
  for (const i of items) formats.set(i.format, (formats.get(i.format) ?? 0) + 1)

  const clearAll = async () => {
    const ok = await confirmDialog(
      'Limpar estatísticas?',
      'Zera o tempo de leitura (de todos os livros) e a sequência de dias. Livros, progresso, Estante, anotações e glossário não são afetados. Não dá para desfazer.',
      'Limpar',
      true
    )
    if (!ok) return
    await clearStatistics()
    toast('Estatísticas zeradas.')
  }

  return (
    <>
      <h2>Estatísticas</h2>
      <p class="muted">Um resumo das suas leituras neste navegador.</p>
      <div class="tiles">
        <Tile value={`🔥 ${streak.current}`} caption={streak.current === 1 ? 'dia seguido' : 'dias seguidos'} />
        <Tile value={String(streak.best)} caption="melhor sequência" />
        <Tile value={formatReadingTime(streak.todaySeconds)} caption="lidos hoje" />
        <Tile value={formatReadingTime(total)} caption="no total" />
      </div>
      <p class="muted small">Um dia conta para a sequência a partir de {formatReadingTime(STREAK_MIN_SECONDS)} de leitura. O tempo para de contar se o livro fica aberto 5 minutos sem nenhuma tecla, clique ou rolagem, ou quando a aba sai da tela.</p>
      <MonthCalendar days={days} todayText={now} />
      {(total > 0 || items.some((i) => i.readingSeconds > 0)) && (
        <button type="button" class="link clear-stats" onClick={clearAll}>Limpar estatísticas…</button>
      )}

      <p class="label">BIBLIOTECA</p>
      <div class="tiles">
        <Tile value={String(items.length)} caption="na biblioteca" />
        <Tile value={String(items.filter((i) => !isFinished(i) && i.openedAt > 0).length)} caption="em leitura" />
        <Tile value={String(items.filter(isFinished).length)} caption="concluídos" />
        <Tile value={String(notes.length)} caption="anotações" />
      </div>
      {reading.length > 0 && (
        <>
          <p class="label">MAIS TEMPO DE LEITURA</p>
          <ul class="plain-list">
            {reading.map((item: LibraryItem) => (
              <li key={item.id}>
                <button type="button" class="list-button" title="Clique para apagar o tempo deste livro" onClick={() => confirmClearBookTime(item)}>
                  {displayTitle(item.name)} · {formatReadingTime(item.readingSeconds)}
                </button>
              </li>
            ))}
          </ul>
          <p class="muted small">Clique num livro para apagar só o tempo dele.</p>
        </>
      )}
      {items.length > 0 && (
        <>
          <p class="label">POR FORMATO</p>
          <ul class="plain-list">
            {[...formats.entries()].sort((a, b) => b[1] - a[1]).map(([format, count]) => <li key={format}>{format.toUpperCase()} · {count}</li>)}
          </ul>
        </>
      )}
    </>
  )
}

function Tile({ value, caption }: { value: string; caption: string }) {
  return (
    <div class="tile">
      <strong>{value}</strong>
      <span>{caption}</span>
    </div>
  )
}

/** Calendário do mês com os dias lidos (como o do app). */
function MonthCalendar({ days, todayText }: { days: Record<string, number>; todayText: string }) {
  const todayEpoch = parseDay(todayText)!
  const [year, month] = todayText.split('-').map(Number)
  const first = epochDay(year, month, 1)
  const next = month === 12 ? epochDay(year + 1, 1, 1) : epochDay(year, month + 1, 1)
  const offset = (((first + 4) % 7) + 7) % 7 // 1970-01-01 foi quinta-feira
  const cells: (number | null)[] = [...Array(offset).fill(null)]
  for (let d = first; d < next; d++) cells.push(d)
  return (
    <div class="calendar" role="group" aria-label={`${MONTHS[month - 1]} de ${year}`}>
      <p class="calendar-title">{MONTHS[month - 1][0].toUpperCase() + MONTHS[month - 1].slice(1)} de {year}</p>
      <div class="calendar-grid">
        {['D', 'S', 'T', 'Q', 'Q', 'S', 'S'].map((d, i) => <span key={`h${i}`} class="calendar-head">{d}</span>)}
        {cells.map((d, i) => {
          if (d === null) return <span key={`e${i}`} />
          const seconds = days[formatDay(d)] ?? 0
          const read = seconds >= STREAK_MIN_SECONDS
          const cls = ['calendar-day', read && 'read', d === todayEpoch && 'today', d > todayEpoch && 'future'].filter(Boolean).join(' ')
          return (
            <span key={d} class={cls} title={read ? `Leu ${formatReadingTime(seconds)}` : undefined} aria-label={`Dia ${d - first + 1}${read ? `, leu ${formatReadingTime(seconds)}` : ''}`}>
              {d - first + 1}
            </span>
          )
        })}
      </div>
    </div>
  )
}
