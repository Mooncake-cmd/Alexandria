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
 * Relógio de leitura de uma sessão (porte de ReadingClock do app). Conta com o livro aberto e a aba
 * à vista, separado por dia. Para não contar o tempo longe do livro:
 * - ritmo de leitura: aprende o tempo normal numa página (`pageTurn`) e pausa depois de 3 vezes esse
 *   tempo sem toque (entre `minIdleMs` e `idleLimitMs`);
 * - desconto da espera: depois do último toque, só o tempo normal de uma página conta direto; o resto
 *   fica provisório e só vale se o leitor tocar de novo antes da pausa;
 * - pausa manual (`pause`/`resume`).
 * Sem ritmo conhecido (menos de 3 viradas), pausa em `idleLimitMs` e conta tudo até lá.
 */
export class ReadingClock {
  static readonly SAMPLES = 20
  static readonly MIN_TURN_MS = 3_000
  static readonly MAX_TURN_MS = 10 * 60_000
  static readonly MIN_GRACE_MS = 20_000
  private lastTick: number | null = null
  private lastInteraction = 0
  private pending = new Map<string, number>()
  private provisional = new Map<string, number>()
  private turns: number[]
  private lastTurn: number | null = null
  /** Milissegundos contados nesta sessão (sem o tempo provisório). */
  sessionMs = 0
  /** Tempo provisório: depois do tempo normal de uma página, à espera de um novo toque. */
  provisionalMs = 0
  /** O leitor pausou o cronômetro (botão ⏱). */
  paused = false

  constructor(private readonly idleLimitMs = 5 * 60_000, private readonly minIdleMs = 60_000, pace: number[] = []) {
    this.turns = pace.filter((t) => t >= ReadingClock.MIN_TURN_MS && t <= ReadingClock.MAX_TURN_MS).slice(-ReadingClock.SAMPLES)
  }

  /** O que o cronômetro mostra: o tempo contado e o provisório. */
  get shownMs() {
    return this.sessionMs + this.provisionalMs
  }

  pace(): number[] {
    return [...this.turns]
  }

  typicalPageMs(): number | null {
    if (this.turns.length < 3) return null
    const sorted = [...this.turns].sort((a, b) => a - b)
    return sorted[Math.floor(sorted.length / 2)]
  }

  idleLimit(): number {
    const typical = this.typicalPageMs()
    return typical === null ? this.idleLimitMs : Math.min(this.idleLimitMs, Math.max(this.minIdleMs, typical * 3))
  }

  graceMs(): number {
    const typical = this.typicalPageMs()
    return typical === null ? this.idleLimit() : Math.min(this.idleLimit(), Math.max(ReadingClock.MIN_GRACE_MS, typical))
  }

  start(now: number) {
    this.lastTick = now
    this.lastInteraction = now
  }

  interaction(now: number) {
    if (this.paused) return
    // Tocou antes da pausa: o tempo provisório era leitura. Depois da pausa, já foi descartado.
    if (now - this.lastInteraction <= this.idleLimit()) this.commitProvisional()
    else this.dropProvisional()
    this.lastInteraction = now
  }

  /** Uma página (ou uma tela de texto) foi virada: aprende o ritmo de leitura. */
  pageTurn(now: number) {
    this.interaction(now)
    const previous = this.lastTurn
    this.lastTurn = now
    if (previous === null || this.paused) return
    const interval = now - previous
    if (interval >= ReadingClock.MIN_TURN_MS && interval <= ReadingClock.MAX_TURN_MS) {
      this.turns.push(interval)
      if (this.turns.length > ReadingClock.SAMPLES) this.turns.shift()
    }
  }

  isIdle(now: number) {
    return this.paused || now - this.lastInteraction > this.idleLimit()
  }

  tick(now: number, day: string) {
    const last = this.lastTick
    if (last === null) return
    this.lastTick = now
    if (now <= last || this.paused) return
    const activeUntil = this.lastInteraction + this.idleLimit()
    const directUntil = this.lastInteraction + this.graceMs()
    const end = Math.min(now, activeUntil)
    const direct = Math.max(0, Math.min(end, directUntil) - last)
    const extra = Math.max(0, end - Math.max(last, directUntil))
    if (direct > 0) {
      this.sessionMs += direct
      this.pending.set(day, (this.pending.get(day) ?? 0) + direct)
    }
    if (extra > 0) {
      this.provisionalMs += extra
      this.provisional.set(day, (this.provisional.get(day) ?? 0) + extra)
    }
    if (now >= activeUntil) this.dropProvisional()
  }

  /** Livro fechado ou aba escondida: o tempo provisório não conta. */
  stop(now: number, day: string) {
    this.tick(now, day)
    this.dropProvisional()
    this.lastTick = null
  }

  /** Pausa manual: o clique no botão é sinal de presença, então o tempo até aqui conta. */
  pause(now: number, day: string) {
    if (this.paused) return
    this.interaction(now)
    this.tick(now, day)
    this.paused = true
  }

  resume(now: number) {
    if (!this.paused) return
    this.paused = false
    this.lastTick = now
    this.lastInteraction = now
    this.lastTurn = null
  }

  private commitProvisional() {
    for (const [day, ms] of this.provisional) this.pending.set(day, (this.pending.get(day) ?? 0) + ms)
    this.sessionMs += this.provisionalMs
    this.dropProvisional()
  }

  private dropProvisional() {
    this.provisional.clear()
    this.provisionalMs = 0
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
