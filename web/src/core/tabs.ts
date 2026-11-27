/**
 * Abas da tela inicial (o mesmo modelo no app): cada aba tem um tipo e, se quiser nuvem, o seu grupo
 * privado do Telegram. A aba principal de livros ("livros") sempre existe; as outras a pessoa cria.
 */
import type { CloudChat } from '../cloud/gramApi'

export type TabType = 'livros' | 'filmes' | 'cursos'

export interface AppTab {
  id: string
  name: string
  type: TabType
  /** Grupo da nuvem da aba. Na aba principal, null = Mensagens salvas; nas outras, sem nuvem. */
  chat: CloudChat | null
}

export const MAIN_TAB = 'livros'

export const TAB_TYPES: { type: TabType; label: string; hint: string }[] = [
  { type: 'livros', label: 'Livros', hint: 'Livros, quadrinhos e documentos, com leitor e anotações.' },
  { type: 'filmes', label: 'Filmes', hint: 'Vídeos do grupo; os tópicos do grupo viram coleções.' },
  { type: 'cursos', label: 'Cursos', hint: 'Cada tópico do grupo é um curso, com as aulas em ordem.' }
]

export const typeLabel = (type: TabType) => TAB_TYPES.find((t) => t.type === type)?.label ?? type
export const isVideoTab = (tab: AppTab) => tab.type !== 'livros'

/** Aba de um livro (livros de antes das abas ficam na principal). */
export const tabOf = (item: { tab?: string }) => item.tab || MAIN_TAB

/** Código novo e único para uma aba, tirado do nome ("Filmes da família" → "filmes-da-familia"). */
export function newTabId(name: string, existing: string[]): string {
  const base =
    name
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 24) || 'aba'
  let id = base
  for (let n = 2; id === MAIN_TAB || existing.includes(id); n++) id = `${base}-${n}`
  return id
}

/**
 * Abas guardadas, conferidas: a principal sempre existe e vem primeiro, sem repetidas. [legacyChat]
 * é o grupo escolhido antes das abas existirem (ele passa a ser o grupo da aba principal).
 */
export function normalizeTabs(raw: unknown, legacyChat: CloudChat | null = null): AppTab[] {
  const list = Array.isArray(raw) ? (raw as Partial<AppTab>[]) : []
  const seen = new Set<string>()
  const tabs: AppTab[] = []
  for (const t of list) {
    if (!t || typeof t.id !== 'string' || !t.id || seen.has(t.id)) continue
    const type: TabType = t.type === 'filmes' || t.type === 'cursos' ? t.type : 'livros'
    seen.add(t.id)
    tabs.push({ id: t.id, name: (t.name ?? '').trim() || typeLabel(type), type: t.id === MAIN_TAB ? 'livros' : type, chat: t.chat?.id ? t.chat : null })
  }
  const mainIndex = tabs.findIndex((t) => t.id === MAIN_TAB)
  const main = mainIndex >= 0 ? tabs.splice(mainIndex, 1)[0] : { id: MAIN_TAB, name: 'Livros', type: 'livros' as const, chat: legacyChat }
  return [main, ...tabs]
}
