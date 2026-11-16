/**
 * Regras dos marcadores de página (as mesmas do app): nome do próximo e se um marcador está na
 * tela. As posições estão na escala precisa de cada leitor: página nos formatos de página; fração
 * de 0 a 1.000.000 do livro nos de texto.
 */
export interface Bookmark {
  id: number
  itemId: number
  name: string
  position: number
  createdAt: number
}

/** Escala da posição precisa nos formatos de texto (0 a 10000 × 100). */
export const EXACT_SCALE = 100

/** "Marcador N", com N uma unidade acima do maior já usado. */
export function nextBookmarkName(existing: string[]): string {
  const used = existing.map((n) => /^Marcador (\d+)$/.exec(n.trim())?.[1]).filter((n): n is string => !!n).map(Number)
  return `Marcador ${(used.length ? Math.max(...used) : 0) + 1}`
}

/**
 * O marcador [mark] está na tela que começa em [top] e mostra [span]? Formatos de página (span 1):
 * a mesma página. Texto: da linha do alto até o começo da próxima tela, com folga de 5% da tela.
 */
export function onScreen(mark: number, top: number, span: number): boolean {
  if (span <= 1) return mark === top
  const slack = Math.max(1, Math.floor(span / 20))
  return mark >= top - slack && mark < top + span - slack
}

export function indexOnScreen(marks: Bookmark[], top: number, span: number): number {
  return marks.findIndex((m) => onScreen(m.position, top, span))
}

/** Ordem do livro (posição, depois o mais antigo). */
export const byPosition = (a: Bookmark, b: Bookmark) => a.position - b.position || a.id - b.id
