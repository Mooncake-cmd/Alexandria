import type { IconName } from './ui/icons'

/** As quatro abas do app, na mesma ordem. */
export const SECTIONS: { id: string; label: string; icon: IconName }[] = [
  { id: 'biblioteca', label: 'Biblioteca', icon: 'library' },
  { id: 'notas', label: 'Notas', icon: 'notes' },
  { id: 'estatisticas', label: 'Estatísticas', icon: 'stats' },
  { id: 'ajustes', label: 'Ajustes', icon: 'settings' }
]
