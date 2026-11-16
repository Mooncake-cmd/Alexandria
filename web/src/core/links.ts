/** "1", "[12]", "(3)", "a", "*", "†": texto típico da chamada de uma nota de rodapé. */
export function looksLikeNoteMark(text: string): boolean {
  return /^[[(]?(\d{1,4}|[a-zA-Z]|[*†‡§¶#]{1,3})[\])]?\.?$/.test(text.trim())
}

/** Título da janela da nota: "Nota 5" para a chamada "5" ou "[5]". */
export function noteTitle(mark: string): string {
  return looksLikeNoteMark(mark) ? `Nota ${mark.trim().replace(/^[[(]|[\])]?\.?$/g, '')}` : 'Nota'
}
