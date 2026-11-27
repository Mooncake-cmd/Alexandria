/** Regras do "continuar assistindo" (as mesmas no app). Tempos em segundos. */

/** Assistido: chegou perto do fim (os créditos não contam). */
export function watched(position: number, duration: number): boolean {
  return duration > 0 && duration - position <= Math.max(30, duration * 0.05)
}

/** Onde retomar: o ponto salvo, se passou do começo e não chegou ao fim; senão, do começo. */
export function resumeAt(position: number, duration: number): number {
  if (position < 10 || (duration > 0 && watched(position, duration))) return 0
  return position
}

export function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`
}
