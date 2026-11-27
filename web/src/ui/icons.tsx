import library from './icon-library.svg?raw'
import notes from './icon-notes.svg?raw'
import stats from './icon-stats.svg?raw'
import settings from './icon-settings.svg?raw'
import film from './icon-film.svg?raw'
import course from './icon-course.svg?raw'
import plus from './icon-plus.svg?raw'

/** Ícones das abas, os mesmos do app (livro, caneta pena, ábaco e bússola). */
const ICONS = { library, notes, stats, settings, film, course, plus }

export type IconName = keyof typeof ICONS

export function Icon({ name }: { name: IconName }) {
  return <span class="icon" dangerouslySetInnerHTML={{ __html: ICONS[name] }} />
}
