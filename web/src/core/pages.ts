/** Virar página e páginas com linhas inteiras. Porte de PageTurn (BookViewer.kt) e LinePager.kt. */

/** Fração da largura, em cada lado, que conta como borda. */
export const EDGE = 0.3

/** -1 (borda esquerda: voltar), 1 (borda direita: avançar) ou 0 (meio). */
export function turnDirection(x: number, width: number): -1 | 0 | 1 {
  if (width <= 0) return 0
  if (x < width * EDGE) return -1
  if (x > width * (1 - EDGE)) return 1
  return 0
}

/** Tamanho da página para caber inteira na área, dada a proporção altura/largura. */
export function fitPage(areaWidth: number, areaHeight: number, aspect: number): [number, number] {
  if (areaWidth <= 0 || areaHeight <= 0 || aspect <= 0) return [Math.max(1, areaWidth), Math.max(1, areaHeight)]
  const width = Math.min(areaWidth, areaHeight / aspect)
  return [Math.max(1, Math.trunc(width)), Math.max(1, Math.trunc(width * aspect))]
}

/** Uma linha (ou um bloco inteiro, como uma imagem) na tela: de `top` a `bottom`. */
export interface LineSpan {
  top: number
  bottom: number
}

const EPS = 0.5

/** Linha cortada pela borda de baixo da janela, se houver. */
export function cutAtBottom(lines: LineSpan[], winTop: number, winBottom: number): LineSpan | null {
  return lines.find((l) => l.top < winBottom - EPS && l.bottom > winBottom + EPS && l.top > winTop + EPS) ?? null
}

/** Onde começa a cobertura de baixo: no topo da linha cortada (ou na borda). */
export function maskStart(lines: LineSpan[], winTop: number, winBottom: number): number {
  return cutAtBottom(lines, winTop, winBottom)?.top ?? winBottom
}

/** Quanto rolar para a próxima página: a linha cortada (ou a primeira abaixo) vai para o topo. */
export function nextDelta(lines: LineSpan[], winTop: number, winBottom: number): number {
  const window = winBottom - winTop
  const target = cutAtBottom(lines, winTop, winBottom)?.top ?? lines.find((l) => l.top >= winBottom - EPS)?.top
  if (target === undefined) return window
  const delta = target - winTop
  return delta < window * 0.25 ? window : delta
}

/** Depois de voltar uma janela: a linha cortada no topo sai, e a página começa na seguinte. */
export function afterBackDelta(lines: LineSpan[], winTop: number, winBottom: number): number {
  const window = winBottom - winTop
  const straddling = lines.find((l) => l.top < winTop - EPS && l.bottom > winTop + EPS)
  if (!straddling) return 0
  const delta = straddling.bottom - winTop
  return delta > window * 0.75 ? 0 : delta
}

/** Ao abrir ou pular para um ponto: a linha cortada no topo desce e aparece inteira. */
export function alignDelta(lines: LineSpan[], winTop: number, winBottom: number): number {
  const window = winBottom - winTop
  const straddling = lines.find((l) => l.top < winTop - EPS && l.bottom > winTop + EPS)
  if (!straddling) return 0
  const delta = straddling.top - winTop
  return -delta > window * 0.5 ? 0 : delta
}

/**
 * Fração do livro (0 a 1) → capítulo (ou bloco) e fração dentro dele. A posição salva tem
 * resolução limitada (`resolution`, em fração do livro): um ponto no começo de um capítulo pode ter
 * sido arredondado para um fio antes da divisa. Dentro dessa margem, vale o começo do seguinte.
 * Porte de BookPosition.split.
 */
export function splitPosition(fraction: number, parts: number, resolution: number): [number, number] {
  if (parts <= 0) return [0, 0]
  const raw = Math.min(1, Math.max(0, fraction)) * parts
  let index = Math.floor(raw)
  let inside = raw - index
  const margin = parts * resolution * 0.6
  if (inside > 1 - margin) {
    index += 1
    inside = 0
  } else if (inside < margin) inside = 0
  if (index >= parts) return [parts - 1, 1]
  return [index, inside]
}

/** Resolução de uma posição de 0 a 10000: inteira (posição comum) ou com casas (ponto preciso). */
export const resolutionOf = (position: number) => (Number.isInteger(position) ? 1e-4 : 1e-6)
