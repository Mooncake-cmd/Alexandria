/**
 * Fonte de páginas (PDF, quadrinhos, DJVU), como o PageSource do app: quantas páginas, a
 * proporção de cada uma e o desenho de uma página numa largura. O PDF também entrega o texto.
 */
export interface PageSource {
  pageCount: number
  /** Altura ÷ largura da página (estimada até a página ser lida). */
  aspect(index: number): number
  /** Desenha a página com `width` pixels de largura. */
  render(index: number, width: number): Promise<HTMLCanvasElement | ImageBitmap>
  /** Camada de texto selecionável do PDF, sobre a página desenhada com a largura dada. */
  textLayer?(index: number, container: HTMLElement, width: number): Promise<void>
  /** Texto puro de uma página (busca). */
  pageText?(index: number): Promise<string>
  close(): void
}

/** Proporção A4 enquanto a página ainda não foi lida. */
export const DEFAULT_ASPECT = 1.414

/** Limite de pixels por página desenhada (zoom alto em páginas grandes). */
export const MAX_PIXELS = 16_000_000

export function clampWidth(width: number, aspect: number): number {
  const max = Math.sqrt(MAX_PIXELS / Math.max(0.1, aspect))
  return Math.max(1, Math.min(Math.round(width), Math.floor(max)))
}
