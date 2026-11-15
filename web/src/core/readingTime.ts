/** Formatação de tempo de leitura. Porte de `ReadingTime` (Reading.kt), com os mesmos resultados. */

/** "45 s", "12 min", "2 h 05 min". */
export function formatReadingTime(seconds: number): string {
  if (seconds < 60) return `${Math.max(0, Math.trunc(seconds))} s`
  if (seconds < 3600) return `${Math.trunc(seconds / 60)} min`
  const minutes = Math.trunc((seconds % 3600) / 60)
  return `${Math.trunc(seconds / 3600)} h ${String(minutes).padStart(2, '0')} min`
}

/** Cronômetro: "4:07", "12:35", "1:02:03". */
export function formatClock(seconds: number): string {
  const s = Math.max(0, Math.trunc(seconds))
  const h = Math.trunc(s / 3600)
  const m = Math.trunc((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/**
 * Divide `amount` segundos entre os dias de `available`, na proporção de cada um, sem passar do
 * que cada dia tem. O que sobra do arredondamento sai dos dias com mais tempo.
 */
export function spreadRemoval(available: Record<string, number>, amount: number): Record<string, number> {
  const days = Object.entries(available).filter(([, s]) => s > 0)
  const total = days.reduce((sum, [, s]) => sum + s, 0)
  if (amount <= 0 || total <= 0) return {}
  if (amount >= total) return Object.fromEntries(days)
  const result: Record<string, number> = {}
  for (const [day, seconds] of days) result[day] = Math.floor((seconds * amount) / total)
  let left = amount - Object.values(result).reduce((a, b) => a + b, 0)
  for (const [day, seconds] of [...days].sort((a, b) => b[1] - a[1])) {
    if (left <= 0) break
    const take = Math.min(seconds - result[day], left)
    result[day] += take
    left -= take
  }
  return Object.fromEntries(Object.entries(result).filter(([, s]) => s > 0))
}
