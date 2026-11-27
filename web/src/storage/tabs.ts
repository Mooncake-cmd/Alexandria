import { getPref, setPref } from './library'
import { MAIN_TAB, normalizeTabs, type AppTab } from '../core/tabs'
import type { CloudChat } from '../cloud/gramApi'

/** Abas guardadas neste navegador (o grupo escolhido antes das abas vira o da aba principal). */
export async function getTabs(): Promise<AppTab[]> {
  const [raw, telegram] = await Promise.all([getPref<AppTab[]>('tabs'), getPref<{ chat?: CloudChat | null }>('telegram')])
  return normalizeTabs(raw, telegram?.chat ?? null)
}

const listeners = new Set<() => void>()
export function onTabsChange(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export async function saveTabs(tabs: AppTab[]) {
  await setPref('tabs', normalizeTabs(tabs))
  listeners.forEach((l) => l())
}

export async function tabById(id: string | undefined): Promise<AppTab> {
  const tabs = await getTabs()
  return tabs.find((t) => t.id === (id || MAIN_TAB)) ?? tabs[0]
}

export async function updateTab(id: string, change: Partial<AppTab>) {
  await saveTabs((await getTabs()).map((t) => (t.id === id ? { ...t, ...change, id: t.id } : t)))
}
