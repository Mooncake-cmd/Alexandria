/**
 * Ações das abas (criar, renomear, apagar e escolher o grupo da nuvem), com os diálogos do site.
 */
import { MAIN_TAB, TAB_TYPES, isVideoTab, newTabId, typeLabel, type AppTab } from '../core/tabs'
import { getTabs, saveTabs, updateTab } from '../storage/tabs'
import { moveTabItems } from '../storage/library'
import { confirmDialog, menuDialog, promptDialog, toast } from '../ui/dialogs'
import { cloudInviteLink, copySavedToGroup, createCloudGroup, joinCloudGroup, myGroups, telegramSettings } from '../cloud/cloud'
import type { CloudChat } from '../cloud/gramApi'

async function explain(e: unknown) {
  const { telegramError } = await import('../cloud/gramApi')
  toast(e instanceof Error && !(e as { errorMessage?: string }).errorMessage ? e.message : telegramError(e))
}

export const goToTab = (id: string) => {
  location.hash = `#/aba/${id}`
}

/** "+ Nova aba": nome e tipo; uma aba de vídeos já pede o grupo. */
export async function newTab() {
  const typeIndex = await menuDialog('Nova aba: o que ela guarda?', TAB_TYPES.map((t) => `${t.label} — ${t.hint}`))
  if (typeIndex === null) return
  const type = TAB_TYPES[typeIndex].type
  const name = (await promptDialog('Nome da aba', 'Por exemplo: Filmes da família, Cursos, HQs', typeLabel(type), [], 'Criar'))?.trim()
  if (!name) return
  const tabs = await getTabs()
  const tab: AppTab = { id: newTabId(name, tabs.map((t) => t.id)), name, type, chat: null }
  await saveTabs([...tabs, tab])
  goToTab(tab.id)
  if (isVideoTab(tab)) {
    if (!(await telegramSettings())?.session) {
      toast('Para ver os vídeos, entre no Telegram em Ajustes e depois escolha o grupo da aba.')
      return
    }
    await chooseGroup(tab)
  }
}

/** Menu "⋯" da aba. */
export async function tabOptions(tab: AppTab) {
  const main = tab.id === MAIN_TAB
  const options = ['Renomear', 'Grupo da nuvem…', ...(main ? [] : ['Apagar aba'])]
  const choice = await menuDialog(tab.name, options, `Aba de ${typeLabel(tab.type).toLowerCase()}${tab.chat ? ` · grupo "${tab.chat.title}"` : main ? ' · nuvem nas Mensagens salvas' : ' · sem grupo'}`)
  if (choice === 0) {
    const name = (await promptDialog('Renomear aba', 'Nome', tab.name))?.trim()
    if (name) await updateTab(tab.id, { name })
  } else if (choice === 1) {
    await groupOptions(tab)
  } else if (choice === 2) {
    const extra = isVideoTab(tab) ? 'Os vídeos continuam no grupo do Telegram.' : 'Os livros dela voltam para a aba principal; nada é apagado.'
    if (!(await confirmDialog(`Apagar a aba "${tab.name}"?`, extra, 'Apagar', true))) return
    if (!isVideoTab(tab)) await moveTabItems(tab.id)
    await saveTabs((await getTabs()).filter((t) => t.id !== tab.id))
    goToTab(MAIN_TAB)
  }
}

/** Grupo da nuvem da aba: criar, entrar, escolher, convidar, trazer das Mensagens salvas ou largar. */
export async function groupOptions(tab: AppTab) {
  if (!(await telegramSettings())?.session) {
    toast('Entre no Telegram em Ajustes (Nuvem pelo Telegram) para usar um grupo.')
    return
  }
  const main = tab.id === MAIN_TAB
  if (!tab.chat) return chooseGroup(tab)
  const leave = main ? 'Voltar para as Mensagens salvas' : 'Deixar a aba sem grupo'
  const options = ['Link de convite (para outras contas)', ...(main ? ['Trazer livros das Mensagens salvas'] : []), 'Trocar de grupo', leave]
  const choice = await menuDialog(`Grupo "${tab.chat.title}"`, options, 'Todas as contas que estiverem no grupo veem e enviam os arquivos desta aba.')
  if (choice === null) return
  const label = options[choice]
  try {
    if (label.startsWith('Link')) {
      const link = await cloudInviteLink(tab.chat)
      await navigator.clipboard?.writeText(link).then(() => toast('Link de convite copiado.'), () => {})
      await promptDialog('Link de convite', 'Abra na outra conta (no Telegram) ou cole nas opções da aba do Alexandria com ela conectada. Não compartilhe: quem entrar vê tudo.', link, [], 'Fechar')
    } else if (label.startsWith('Trazer')) {
      const { books } = await copySavedToGroup(tab.chat)
      toast(books ? `${books} livro(s) copiado(s) para o grupo.` : 'Os livros das Mensagens salvas já estavam no grupo.')
    } else if (label === 'Trocar de grupo') {
      await chooseGroup(tab)
    } else if (await confirmDialog(`${leave}?`, 'Os arquivos continuam no grupo do Telegram.', main ? 'Voltar' : 'Deixar')) {
      await updateTab(tab.id, { chat: null })
    }
  } catch (e) {
    await explain(e)
  }
}

/** Escolhe o grupo da aba (criar novo, entrar pelo link ou um que a conta já tem). */
export async function chooseGroup(tab: AppTab) {
  const options = ['Criar grupo privado', 'Entrar com link de convite', 'Usar um grupo que já tenho']
  const choice = await menuDialog(`Grupo da aba "${tab.name}"`, options, 'Num grupo privado, várias contas suas (e quem você convidar) usam os mesmos arquivos. Quem não usa o Alexandria envia pelo próprio Telegram.')
  if (choice === null) return
  try {
    let chat: CloudChat | null = null
    if (choice === 0) {
      chat = await createCloudGroup(`Alexandria — ${tab.name}`)
    } else if (choice === 1) {
      const link = (await promptDialog('Entrar com link de convite', 'Link (https://t.me/+…)', ''))?.trim()
      if (!link) return
      chat = await joinCloudGroup(link)
    } else {
      const groups = await myGroups()
      if (!groups.length) return toast('Esta conta não está em nenhum grupo.')
      const pick = await menuDialog('Escolha o grupo', groups.map((g) => g.title))
      if (pick === null) return
      chat = groups[pick]
    }
    await updateTab(tab.id, { chat })
    toast(`A aba "${tab.name}" agora usa o grupo "${chat.title}".`)
    if (tab.id === MAIN_TAB && (await confirmDialog('Trazer os livros para o grupo?', 'Os livros e posições que estão nas suas Mensagens salvas são copiados para o grupo (só os que faltam). Nada é apagado.', 'Trazer'))) {
      const { books } = await copySavedToGroup(chat)
      toast(books ? `${books} livro(s) copiado(s) para o grupo.` : 'Os livros das Mensagens salvas já estavam no grupo.')
    }
  } catch (e) {
    await explain(e)
  }
}
