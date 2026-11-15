import { DEFAULT_COMFORT, sanitizeComfort, type Comfort } from '../core/comfort'
import { getPref, setPref } from '../storage/library'

/** Preferências de leitura (como ReadingPrefs do app), guardadas no banco do site. */
export interface ReadingPrefs {
  /** true = páginas (vira com teclado, roda ou clique duplo nas bordas); false = rolagem contínua. */
  paged: boolean
  textSize: number
  ruler: boolean
  rulerY: number
  cleanScreen: boolean
  sideLock: boolean
  comfort: Comfort
}

export const DEFAULT_TEXT_SIZE = 18
export const MIN_TEXT_SIZE = 12
export const MAX_TEXT_SIZE = 32

/** No computador a tela é larga: o padrão é uma coluna de ~75 caracteres. */
const WEB_DEFAULT_COMFORT: Comfort = { ...DEFAULT_COMFORT, lineWidth: 'LARGA' }

export const DEFAULT_PREFS: ReadingPrefs = {
  paged: true,
  textSize: DEFAULT_TEXT_SIZE,
  ruler: false,
  rulerY: 0.4,
  cleanScreen: true,
  sideLock: false,
  comfort: WEB_DEFAULT_COMFORT
}

export async function loadPrefs(): Promise<ReadingPrefs> {
  const raw = ((await getPref<Partial<ReadingPrefs>>('reading').catch(() => undefined)) ?? {}) as Partial<ReadingPrefs>
  return {
    paged: typeof raw.paged === 'boolean' ? raw.paged : DEFAULT_PREFS.paged,
    textSize: typeof raw.textSize === 'number' ? Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, raw.textSize)) : DEFAULT_TEXT_SIZE,
    ruler: typeof raw.ruler === 'boolean' ? raw.ruler : DEFAULT_PREFS.ruler,
    rulerY: typeof raw.rulerY === 'number' ? Math.min(1, Math.max(0, raw.rulerY)) : DEFAULT_PREFS.rulerY,
    cleanScreen: typeof raw.cleanScreen === 'boolean' ? raw.cleanScreen : DEFAULT_PREFS.cleanScreen,
    sideLock: typeof raw.sideLock === 'boolean' ? raw.sideLock : DEFAULT_PREFS.sideLock,
    comfort: raw.comfort ? sanitizeComfort(raw.comfort) : WEB_DEFAULT_COMFORT
  }
}

export const savePrefs = (prefs: ReadingPrefs) => setPref('reading', prefs)
