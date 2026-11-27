import { useEffect, useRef, useState } from 'preact/hooks'
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
import { VideoTab } from './screens/VideoTab'
import { MAIN_TAB, isVideoTab, type AppTab } from './core/tabs'
import { getTabs, onTabsChange } from './storage/tabs'
import { newTab } from './screens/tabActions'
import type { IconName } from './ui/icons'

const tabIcon = (tab: AppTab): IconName => (tab.type === 'filmes' ? 'film' : tab.type === 'cursos' ? 'course' : 'library')

/** Abas guardadas, atualizadas quando mudam. */
function useTabs(): AppTab[] {
  const [tabs, setTabs] = useState<AppTab[]>([])
  useEffect(() => {
    const load = () => getTabs().then(setTabs)
    load()
    return onTabsChange(load)
  }, [])
  return tabs
}

/** Rota pelo endereço: #/notas, #/ler/12… Recarregar a página volta para o mesmo lugar. */
function currentRoute(): { section: string; bookId: number | null; position: number | null; tabId: string } {
  const path = location.hash.replace(/^#\/?/, '')
  const tab = /^aba\/([\w-]+)$/.exec(path)
  if (tab) return { section: 'aba', bookId: null, position: null, tabId: tab[1] }
  // #/ler/12 abre no ponto salvo; #/ler/12/4200 abre numa posição (uma anotação, por exemplo).
  const reading = /^ler\/(\d+)(?:\/(\d+))?$/.exec(path)
  if (reading) return { section: 'ler', bookId: Number(reading[1]), position: reading[2] !== undefined ? Number(reading[2]) : null, tabId: MAIN_TAB }
  // A antiga "#/biblioteca" (e o endereço vazio) é a aba principal.
  const known = SECTIONS.some((s) => s.id === path && s.id !== 'biblioteca')
  return { section: known ? path : 'aba', bookId: null, position: null, tabId: MAIN_TAB }
}

export function App() {
  const [route, setRoute] = useState(currentRoute)
  const [dragging, setDragging] = useState(false)
  const tabs = useTabs()
  const routeRef = useRef(route)
  routeRef.current = route
  // Arquivos soltos ou abertos com o Alexandria vão para a aba de livros aberta (ou a principal).
  const importTab = () => {
    const r = routeRef.current
    const tab = tabs.find((t) => t.id === r.tabId)
    return r.section === 'aba' && tab && !isVideoTab(tab) ? tab.id : MAIN_TAB
  }
  const importTabRef = useRef(importTab)
  importTabRef.current = importTab
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
      const last = await importFiles(files, importTabRef.current())
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
      const last = await importFiles(files, importTabRef.current())
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
  const currentTab = tabs.find((t) => t.id === route.tabId) ?? tabs[0]
  const tabLink = (t: AppTab, extra = '') => {
    const active = section === 'aba' && t.id === currentTab?.id
    return (
      <a key={t.id} href={`#/aba/${t.id}`} class={`nav-item nav-tab${extra}${active ? ' active' : ''}`} aria-current={active ? 'page' : undefined}>
        <Icon name={tabIcon(t)} />
        <span>{t.name}</span>
      </a>
    )
  }
  return (
    <div class="shell">
      <nav class="nav" aria-label="Seções">
        <a class="brand" href={`#/aba/${MAIN_TAB}`}>
          <img src={`${import.meta.env.BASE_URL}icons/icon-192.png`} alt="" width="40" height="40" />
          <span>Alexandria</span>
        </a>
        <p class="nav-label">Abas</p>
        {tabs.map((t) => tabLink(t))}
        <button type="button" class="nav-item nav-new" onClick={newTab}>
          <Icon name="plus" />
          <span>Nova aba</span>
        </button>
        {/* Tela estreita: um botão só leva às abas (que ficam numa fileira no topo). */}
        {currentTab && (
          <a href={`#/aba/${currentTab.id}`} class={`nav-item nav-home${section === 'aba' ? ' active' : ''}`}>
            <Icon name="library" />
            <span>Início</span>
          </a>
        )}
        <div class="nav-sep" />
        {SECTIONS.filter((s) => s.id !== 'biblioteca').map((s) => (
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
          {section === 'aba' && currentTab && (
            <>
              <div class="tab-strip" role="tablist" aria-label="Abas">
                {tabs.map((t) => (
                  <a key={t.id} role="tab" href={`#/aba/${t.id}`} aria-selected={t.id === currentTab.id} class={t.id === currentTab.id ? 'chip active' : 'chip'}>{t.name}</a>
                ))}
                <button type="button" class="chip" onClick={newTab} aria-label="Nova aba">＋</button>
              </div>
              {isVideoTab(currentTab) ? <VideoTab key={currentTab.id} tab={currentTab} /> : <Library key={currentTab.id} tab={currentTab} />}
            </>
          )}
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
