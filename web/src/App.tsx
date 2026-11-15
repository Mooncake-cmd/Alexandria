import { useEffect, useState } from 'preact/hooks'
import { SECTIONS } from './sections'
import { Icon } from './ui/icons'
import { Library } from './screens/Library'
import { Notes } from './screens/Notes'
import { Stats } from './screens/Stats'
import { Settings } from './screens/Settings'

/** Aba atual pelo endereço (#/notas): recarregar a página volta para a mesma aba. */
function currentSection(): string {
  const id = location.hash.replace(/^#\/?/, '')
  return SECTIONS.some((s) => s.id === id) ? id : SECTIONS[0].id
}

export function App() {
  const [section, setSection] = useState(currentSection)
  useEffect(() => {
    const update = () => setSection(currentSection())
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])

  return (
    <div class="shell">
      <nav class="nav" aria-label="Seções">
        <a class="brand" href="#/biblioteca">
          <img src={`${import.meta.env.BASE_URL}icons/icon-192.png`} alt="" width="40" height="40" />
          <span>Alexandria</span>
        </a>
        {SECTIONS.map((s) => (
          <a
            key={s.id}
            href={`#/${s.id}`}
            class={s.id === section ? 'nav-item active' : 'nav-item'}
            aria-current={s.id === section ? 'page' : undefined}
          >
            <Icon name={s.icon} />
            <span>{s.label}</span>
          </a>
        ))}
      </nav>
      <div class="main">
        <header class="header">
          <p class="kicker">ALEXANDRIA</p>
          <h1>Sua biblioteca pessoal</h1>
          <p class="subtitle">Um espaço para suas leituras.</p>
        </header>
        <main class="content">
          <p class="banner" role="note">
            🚧 Versão web em construção. Por enquanto só a base do site está pronta; os leitores chegam nas próximas
            etapas.
          </p>
          {section === 'biblioteca' && <Library />}
          {section === 'notas' && <Notes />}
          {section === 'estatisticas' && <Stats />}
          {section === 'ajustes' && <Settings />}
        </main>
      </div>
    </div>
  )
}
