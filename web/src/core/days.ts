/** Datas "aaaa-mm-dd", sequência de dias e cronômetro de leitura. Porte de Reading.kt. */

/** Dias corridos desde 1970-01-01 (algoritmo de dias civis de Howard Hinnant). */
export function epochDay(year: number, month: number, day: number): number {
  const y = month <= 2 ? year - 1 : year
  const era = Math.trunc((y >= 0 ? y : y - 399) / 400)
  const yoe = y - era * 400
  const doy = Math.trunc((153 * (month + (month > 2 ? -3 : 9)) + 2) / 5) + day - 1
  const doe = yoe * 365 + Math.trunc(yoe / 4) - Math.trunc(yoe / 100) + doy
  return era * 146097 + doe - 719468
}

export function parseDay(text: string): number | null {
  const parts = text.split('-')
  if (parts.length !== 3) return null
  const [y, m, d] = parts.map((p) => (/^-?\d+$/.test(p) ? Number(p) : NaN))
  if ([y, m, d].some(Number.isNaN)) return null
  return epochDay(y, m, d)
}

const pad = (n: number, size = 2) => String(n).padStart(size, '0')

export function formatDay(epoch: number): string {
  const z = epoch + 719468
  const era = Math.trunc((z >= 0 ? z : z - 146096) / 146097)
  const doe = z - era * 146097
  const yoe = Math.trunc((doe - Math.trunc(doe / 1460) + Math.trunc(doe / 36524) - Math.trunc(doe / 146096)) / 365)
  const doy = doe - (365 * yoe + Math.trunc(yoe / 4) - Math.trunc(yoe / 100))
  const mp = Math.trunc((5 * doy + 2) / 153)
  const d = doy - Math.trunc((153 * mp + 2) / 5) + 1
  const m = mp + (mp < 10 ? 3 : -9)
  const y = yoe + era * 400 + (m <= 2 ? 1 : 0)
  return `${pad(y, 4)}-${pad(m)}-${pad(d)}`
}

/** Hoje, no fuso do computador. */
export function today(now: number = Date.now()): string {
  const c = new Date(now)
  return `${pad(c.getFullYear(), 4)}-${pad(c.getMonth() + 1)}-${pad(c.getDate())}`
}

/** Um dia conta para a sequência a partir de 5 minutos de leitura. */
export const STREAK_MIN_SECONDS = 300

export interface StreakSummary {
  current: number
  best: number
  readToday: boolean
  todaySeconds: number
}

export function streakSummary(days: Record<string, number>, todayText: string): StreakSummary {
  const read = new Set<number>()
  for (const [day, seconds] of Object.entries(days)) {
    const epoch = seconds >= STREAK_MIN_SECONDS ? parseDay(day) : null
    if (epoch !== null) read.add(epoch)
  }
  const todayEpoch = parseDay(todayText)
  if (todayEpoch === null) return { current: 0, best: 0, readToday: false, todaySeconds: 0 }
  const readToday = read.has(todayEpoch)
  // Sem leitura hoje (ainda), a sequência continua viva se ontem contou.
  let cursor = readToday ? todayEpoch : todayEpoch - 1
  let current = 0
  while (read.has(cursor)) {
    current++
    cursor--
  }
  let best = 0
  let run = 0
  let previous: number | null = null
  for (const day of [...read].sort((a, b) => a - b)) {
    run = previous !== null && day === previous + 1 ? run + 1 : 1
    best = Math.max(best, run)
    previous = day
  }
  return { current, best, readToday, todaySeconds: days[todayText] ?? 0 }
}

/**
 * Relógio de uma sessão de leitura: conta com o livro aberto e para depois de `idleLimitMs` sem
 * interação. Separa o tempo por dia (virada da meia-noite). O horário sempre vem de fora.
 */
export class ReadingClock {
  private lastTick: number | null = null
  private lastInteraction = 0
  private pending = new Map<string, number>()
  /** Milissegundos contados nesta sessão. */
  sessionMs = 0

  constructor(private readonly idleLimitMs = 5 * 60_000) {}

  start(now: number) {
    this.lastTick = now
    this.lastInteraction = now
  }

  interaction(now: number) {
    this.lastInteraction = now
  }

  isIdle(now: number) {
    return now - this.lastInteraction > this.idleLimitMs
  }

  tick(now: number, day: string) {
    const last = this.lastTick
    if (last === null) return
    this.lastTick = now
    if (now <= last) return
    const activeUntil = this.lastInteraction + this.idleLimitMs
    const counted = Math.max(0, Math.min(now, activeUntil) - last)
    if (counted === 0) return
    this.sessionMs += counted
    this.pending.set(day, (this.pending.get(day) ?? 0) + counted)
  }

  stop(now: number, day: string) {
    this.tick(now, day)
    this.lastTick = null
  }

  /** Segundos inteiros prontos para salvar, por dia (a fração fica para a próxima vez). */
  drain(): Record<string, number> {
    const result: Record<string, number> = {}
    for (const [day, ms] of this.pending) {
      const seconds = Math.floor(ms / 1000)
      if (seconds > 0) result[day] = seconds
      const rest = ms - seconds * 1000
      if (rest === 0) this.pending.delete(day)
      else this.pending.set(day, rest)
    }
    return result
  }
}
