import { displayTitle, resolveShelf } from '../core/library'
import { formatReadingTime } from '../core/readingTime'
import { archive, clearBookTime, hasCustomCover, isArchived, isFinished, removeItem, setCover, setFinished, setShelf, shelves, type LibraryItem } from '../storage/library'
import { customCover } from './covers'
import { confirmDialog, menuDialog, promptDialog, toast } from '../ui/dialogs'
import { statusText } from './BookCard'

/** Menu do livro (clique direito ou "⋯"), com as mesmas opções do toque longo no app. */
export async function itemOptions(item: LibraryItem, extra: { label: string; run: () => void }[] = []) {
  const options: { label: string; run: () => void | Promise<void> }[] = []
  options.push({ label: item.shelf ? `Prateleira: ${item.shelf}…` : 'Mover para prateleira…', run: () => chooseShelf(item) })
  if (!isArchived(item)) {
    options.push({
      label: isFinished(item) ? 'Voltar para "Lendo agora"' : 'Marcar como concluído',
      run: () => setFinished(item.id, !isFinished(item))
    })
  }
  options.push(...extra)
  options.push({ label: 'Trocar capa…', run: () => chooseCover(item) })
  if (await hasCustomCover(item.id)) options.push({ label: 'Restaurar capa original', run: () => setCover(item.id, null, true) })
  if (item.readingSeconds > 0) options.push({ label: `Apagar tempo de leitura (⏱ ${formatReadingTime(item.readingSeconds)})…`, run: () => confirmClearBookTime(item) })
  if (isFinished(item) && !isArchived(item)) options.push({ label: 'Liberar espaço (manter na Estante)…', run: () => confirmFreeSpace(item) })
  options.push({ label: isArchived(item) ? 'Remover da Estante' : 'Remover da biblioteca', run: () => confirmRemove(item) })
  const choice = await menuDialog(displayTitle(item.name), options.map((o) => o.label), statusText(item))
  if (choice !== null) await options[choice].run()
}

async function chooseShelf(item: LibraryItem) {
  const existing = await shelves()
  const options = [...existing.filter((s) => s !== item.shelf).map((s) => `📁 ${s}`), '＋ Nova prateleira…']
  if (item.shelf) options.push('Tirar da prateleira')
  const choice = await menuDialog('Mover para prateleira', options)
  if (choice === null) return
  const label = options[choice]
  if (label === 'Tirar da prateleira') return setShelf(item.id, '')
  if (label.startsWith('📁 ')) return setShelf(item.id, label.slice(3))
  const typed = await promptDialog('Nova prateleira', 'Nome da prateleira', '', existing, 'Criar')
  if (typed) await setShelf(item.id, resolveShelf(typed, existing))
}

export async function confirmClearBookTime(item: LibraryItem) {
  const ok = await confirmDialog(
    'Apagar tempo de leitura?',
    `Apaga ${formatReadingTime(item.readingSeconds)} de leitura de "${displayTitle(item.name)}". O total das estatísticas diminui na mesma medida e os dias de leitura (calendário e sequência) são ajustados. O livro, o progresso, as anotações e o glossário continuam. Não dá para desfazer.`,
    'Apagar',
    true
  )
  if (!ok) return
  const removed = await clearBookTime(item.id)
  toast(`${formatReadingTime(removed)} apagados de "${displayTitle(item.name)}".`)
}

async function confirmFreeSpace(item: LibraryItem) {
  const ok = await confirmDialog(
    'Liberar espaço?',
    `"${displayTitle(item.name)}" continua na Estante com a capa, o nome, as datas, as anotações e o glossário, mas o arquivo guardado no navegador é apagado e o livro não abre mais. Importar o mesmo arquivo de novo traz o livro de volta.`,
    'Liberar espaço'
  )
  if (!ok) return
  await archive(item.id)
  toast(`Arquivo apagado. "${displayTitle(item.name)}" continua na Estante.`)
}

async function confirmRemove(item: LibraryItem) {
  const ok = await confirmDialog(
    isArchived(item) ? 'Remover da Estante?' : 'Remover da biblioteca?',
    `"${displayTitle(item.name)}" sai da biblioteca junto com as anotações e o glossário dele. O tempo de leitura continua nas estatísticas.`,
    'Remover',
    true
  )
  if (ok) await removeItem(item.id)
}

/** Escolhe uma imagem do computador como capa do livro. */
function chooseCover(item: LibraryItem) {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file) return
    const cover = await customCover(file)
    if (!cover) return toast('Não foi possível usar essa imagem como capa.')
    await setCover(item.id, cover, true)
    toast(`Capa de "${displayTitle(item.name)}" trocada.`)
  }
  input.click()
}
