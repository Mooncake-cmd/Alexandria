import { useEffect, useState } from 'preact/hooks'
import { SECTIONS } from './sections'
import { Icon } from './ui/icons'
import { DialogHost, shortcutsDialog } from './ui/dialogs'
import { typing } from './ui/shortcuts'
import { Library, openBook } from './screens/Library'
import { Notes } from './screens/Notes'
import { Stats } from './screens/Stats'
import { Settings } from './screens/Settings'
import { Reader } from './screens/Reader'
import { importFiles } from './library/importBooks'

/** Rota pelo endereço: #/notas, #/ler/12… Recarregar a página volta para o mesmo lugar. */
function currentRoute(): { section: string; bookId: number | null; position: number | null } {
  const path = location.hash.replace(/^#\/?/, '')
  // #/ler/12 abre no ponto salvo; #/ler/12/4200 abre numa posição (uma anotação, por exemplo).
  const reading = /^ler\/(\d+)(?:\/(\d+))?$/.exec(path)
  if (reading) return { section: 'ler', bookId: Number(reading[1]), position: reading[2] !== undefined ? Number(reading[2]) : null }
  return { section: SECTIONS.some((s) => s.id === path) ? path : SECTIONS[0].id, bookId: null, position: null }
}

export function App() {
  const [route, setRoute] = useState(currentRoute)
  const [dragging, setDragging] = useState(false)
  useEffect(() => {
    const update = () => setRoute(currentRoute())
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])

  // "?" mostra os atalhos em qualquer tela.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== '?' || e.ctrlKey || e.metaKey || e.altKey || typing(e.target)) return
      if (document.querySelector('.modal')) return
      e.preventDefault()
      shortcutsDialog()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // "Abrir com" do sistema com o site instalado (File Handling, Chrome/Edge): importa e abre.
  useEffect(() => {
    const queue = (window as unknown as { launchQueue?: { setConsumer: (c: (p: { files?: FileSystemFileHandle[] }) => void) => void } }).launchQueue
    queue?.setConsumer(async (params) => {
      if (!params.files?.length) return
      const files = await Promise.all(params.files.map((handle) => handle.getFile()))
      const last = await importFiles(files)
      if (last && files.length === 1) openBook(last)
    })
  }, [])

  // Arrastar arquivos para a janela importa (o "Abrir com" do computador).
  useEffect(() => {
    let depth = 0
    const hasFiles = (e: DragEvent) => !!e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files')
    const enter = (e: DragEvent) => {
      if (!hasFiles(e)) return
      depth++
      setDragging(true)
    }
    const leave = (e: DragEvent) => {
      if (!hasFiles(e)) return
      depth = Math.max(0, depth - 1)
      if (depth === 0) setDragging(false)
    }
    const over = (e: DragEvent) => {
      if (hasFiles(e)) e.preventDefault()
    }
    const drop = async (e: DragEvent) => {
      if (!hasFiles(e)) return
      e.preventDefault()
      depth = 0
      setDragging(false)
      const files = Array.from(e.dataTransfer?.files ?? [])
      const last = await importFiles(files)
      // Um livro só: abre na hora, como no "Abrir com" do app.
      if (last && files.length === 1) openBook(last)
    }
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragleave', leave)
    window.addEventListener('dragover', over)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('dragover', over)
      window.removeEventListener('drop', drop)
    }
  }, [])

  if (route.section === 'ler' && route.bookId !== null) {
    return (
      <>
        <Reader key={`${route.bookId}/${route.position}`} bookId={route.bookId} startAt={route.position} />
        <DialogHost />
      </>
    )
  }

  const section = route.section
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
          {section === 'biblioteca' && <Library />}
          {section === 'notas' && <Notes />}
          {section === 'estatisticas' && <Stats />}
          {section === 'ajustes' && <Settings />}
        </main>
      </div>
      {dragging && <div class="drop-overlay" aria-hidden="true"><div>Solte os arquivos para importar</div></div>}
      <DialogHost />
    </div>
  )
}
