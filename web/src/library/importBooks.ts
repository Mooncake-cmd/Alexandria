import { detectFormat } from '../core/formats'
import { looksLikeZip } from '../core/library'
import { addBook, type LibraryItem } from '../storage/library'
import { toast } from '../ui/dialogs'

/** Extensões que o seletor de arquivos mostra. */
export const ACCEPT = '.pdf,.epub,.mobi,.azw,.azw3,.djvu,.djv,.cbz,.cbr,.docx,.txt'

/**
 * Importa arquivos escolhidos ou arrastados para a janela. Formato pelo nome e pelo tipo; CBR que
 * é ZIP por dentro entra como CBZ. O mesmo livro não duplica. Devolve o último livro importado.
 */
export async function importFiles(files: File[]): Promise<LibraryItem | null> {
  let added = 0
  let existing = 0
  let restored = 0
  let rejected = 0
  let last: LibraryItem | null = null
  for (const file of files) {
    let format = detectFormat(file.name, file.type)
    if (!format) {
      rejected++
      continue
    }
    if (format === 'cbr' && looksLikeZip(new Uint8Array(await file.slice(0, 4).arrayBuffer()))) format = 'cbz'
    try {
      const result = await addBook(file.name, format, file)
      last = result.item
      if (result.restored) restored++
      else if (result.existing) existing++
      else added++
    } catch (e) {
      console.error(e)
      toast(`Não foi possível guardar "${file.name}". O navegador pode estar sem espaço.`)
    }
  }
  const parts: string[] = []
  if (added) parts.push(added === 1 ? '1 livro adicionado' : `${added} livros adicionados`)
  if (existing) parts.push(existing === 1 ? '1 já estava na biblioteca' : `${existing} já estavam na biblioteca`)
  if (restored) parts.push(restored === 1 ? '1 livro da Estante voltou a ter arquivo' : `${restored} livros da Estante voltaram a ter arquivo`)
  if (rejected) parts.push(rejected === 1 ? '1 arquivo em formato não suportado' : `${rejected} arquivos em formato não suportado`)
  if (parts.length) toast(parts.join(' · ') + '.')
  return last
}
