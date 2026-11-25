import { useEffect, useRef, useState } from 'preact/hooks'
import type { ComponentChildren, VNode } from 'preact'
import { dimAlpha, overlayColor, warmColor } from '../core/comfort'
import { addBookmark, bookmarksFor, getPref, removeBookmark, renameBookmark, setPref, termsFor } from '../storage/library'
import { indexOnScreen, onScreen, type Bookmark } from '../core/bookmarks'
import { BookmarkBar, BookmarkCorner, BookmarkList } from './Bookmarks'
import type { NoteContent } from './notes'
import { noteDialog, promptDialog } from '../ui/dialogs'
import { DEVICE, pullPosition, pushPosition, shouldApply, type SyncEntry } from '../storage/sync'
import { useLibraryData } from '../storage/useLibrary'
import type { GlossaryTerm } from '../core/glossary'
import { annotate, defineTerm, NotesPanel, SearchPanel, type ReaderTools } from './ToolsPanel'
import { removeTerm } from '../storage/library'
import { confirmDialog } from '../ui/dialogs'
import { menuDialog, toast } from '../ui/dialogs'
import { ComfortPanel } from './ComfortPanel'
import { Ruler } from './Ruler'
import { useReadingClock } from './useReadingClock'
import type { ReadingPrefs } from './prefs'

export interface ChromeApi {
  /** Um clique simples no livro (fora de links e seleções): mostra/esconde os controles. */
  tap: () => void
  /** Clique duplo: cancela o clique simples que estava esperando. */
  cancelTap: () => void
  /** Termos do glossário deste livro (para sublinhar) e o que fazer ao clicar num deles. */
  terms: GlossaryTerm[]
  onTerm: (termId: number) => void
  /** Seleção dentro de um iframe (EPUB): texto e retângulo na tela, ou null. */
  selection: (text: string | null, rect?: DOMRect) => void
  /** Tecla, clique ou rolagem dentro de um iframe: o cronômetro continua contando. */
  interaction: () => void
  /** Nota de rodapé (EPUB/MOBI) numa janela; [goTo] mostra a nota no lugar. */
  note: (title: string, note: NoteContent, goTo: (() => void) | null) => void
  /** O livro pulou por um link: oferece "↩ Voltar" para [returnTo] (posição precisa). */
  jump: (returnTo: number) => void
}

interface Props {
  title: string
  prefs: ReadingPrefs
  onPrefs: (change: Partial<ReadingPrefs>) => void
  /** Tipografia vale para os formatos de texto (não para PDF, quadrinhos e DJVU). */
  typography: boolean
  /** Opções do formato (sumário, ir para a página…), antes das comuns. */
  menu: [string, () => void][]
  headerExtra?: VNode | null
  footer: VNode
  finished: boolean
  atEnd: boolean
  onFinish: (done: boolean) => void
  overlay?: VNode | null
  tools?: ReaderTools
  /** Tempo de leitura do livro antes desta sessão. */
  readingSeconds: number
  /** Atalhos próprios do formato (tecla minúscula → ação): S sumário, G ir para a página. */
  keys?: Record<string, () => void>
  /** Livro para a sincronização da posição entre aparelhos (nome e tamanho do arquivo). */
  sync?: { name: string; size: number; format: string; openedAt: number; pageCount: number }
  children: (api: ChromeApi) => ComponentChildren
}

/**
 * Moldura comum dos leitores: barra de cima, controles de baixo, aviso de fim, camadas de luz e
 * filtro, régua, painel de conforto e tela limpa (as barras ficam por cima do livro e somem; um
 * clique no meio mostra e esconde; Esc mostra; F põe em tela cheia).
 */
export function ReaderChrome({ title, prefs, onPrefs, typography, menu, headerExtra, footer, finished, atEnd, onFinish, overlay, tools, readingSeconds, keys = {}, sync, children }: Props) {
  const timer = useReadingClock(tools?.itemId ?? -1, readingSeconds)
  const [shown, setShown] = useState(!prefs.cleanScreen)
  const [panel, setPanelState] = useState<null | 'comfort' | 'search' | 'notes'>(null)
  const setPanel = (v: boolean) => setPanelState(v ? 'comfort' : null)
  const terms = useLibraryData<GlossaryTerm[]>(() => (tools ? termsFor(tools.itemId) : Promise.resolve([])), [], [tools?.itemId])
  const [selection, setSelection] = useState<{ text: string; x: number; y: number } | null>(null)
  const body = useRef<HTMLDivElement>(null)
  const pendingTap = useRef(0)
  const clean = prefs.cleanScreen

  // ---------- marcadores ----------
  const marks = useLibraryData<Bookmark[]>(() => (tools ? bookmarksFor(tools.itemId) : Promise.resolve([])), [], [tools?.itemId])
  const [markMode, setMarkMode] = useState(false)
  const [markId, setMarkId] = useState<number | null>(null)
  const [listOpen, setListOpen] = useState(false)
  const [snack, setSnack] = useState<{ text: string; action?: { label: string; run: () => void } } | null>(null)
  const snackTimer = useRef(0)
  const [returnTo, setReturnTo] = useState<number | null>(null)
  const here = tools ? indexOnScreen(marks, tools.exact(), tools.span()) : -1
  const markIndex = marks.findIndex((m) => m.id === markId)
  // ---------- sincronização da posição entre aparelhos ----------
  const toolsRef = useRef(tools)
  toolsRef.current = tools
  const syncState = useRef<{ pulled: boolean; remote: SyncEntry | null; applied: boolean; lastPushed: number | null }>({ pulled: !sync, remote: null, applied: false, lastPushed: null })
  /** Livro pronto e posição do outro aparelho em mãos: continua de lá, se for mais recente. */
  const applyRemote = () => {
    const st = syncState.current
    const t = toolsRef.current
    const remote = st.remote
    if (!remote || st.applied || !t || !sync || !(t.ready?.() ?? true)) return
    st.applied = true
    if (remote.format !== sync.format) return
    const here = t.exact()
    if (!shouldApply(remote, sync.openedAt, here, t.span())) return
    setReturnTo(here)
    if (t.exactScale > 1 && remote.exact >= 0 && Math.abs(remote.exact / t.exactScale - remote.position) <= 1) t.goToExact(remote.exact)
    else t.goTo(remote.position)
    lastTurn.current = null
    showSnack(`Continuando de onde você parou no ${remote.device}.`, { label: 'VOLTAR', run: () => {
      setReturnTo(null)
      toolsRef.current?.goToExact(here)
    } })
  }
  /** Grava a posição deste aparelho (se mudou desde a última vez). */
  const push = (keepalive = false) => {
    const st = syncState.current
    const t = toolsRef.current
    if (!sync || !st.pulled || !t || !(t.ready?.() ?? true)) return
    const exact = t.exact()
    if (exact === st.lastPushed) return
    st.lastPushed = exact
    pushPosition(sync.name, sync.size, { format: sync.format, position: Math.round(t.current()), exact, pageCount: sync.pageCount, updated: Date.now(), device: DEVICE }, keepalive)
  }
  useEffect(() => {
    if (!sync || sync.size <= 0) {
      syncState.current.pulled = true
      return
    }
    let alive = true
    pullPosition(sync.name, sync.size).then((entry) => {
      if (!alive) return
      syncState.current.pulled = true
      syncState.current.remote = entry
      applyRemote()
    })
    const timer = setInterval(() => push(), 180_000)
    const onHide = () => document.hidden && push(true)
    const onLeave = () => push(true)
    document.addEventListener('visibilitychange', onHide)
    window.addEventListener('pagehide', onLeave)
    return () => {
      alive = false
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onHide)
      window.removeEventListener('pagehide', onLeave)
      push(true)
    }
  }, [])
  useEffect(() => {
    if (syncState.current.remote && !syncState.current.applied) applyRemote()
  })

  // Virar a página (andar meia tela ou mais) ensina o ritmo de leitura ao cronômetro.
  const lastTurn = useRef<number | null>(null)
  useEffect(() => {
    if (!tools) return
    const exact = tools.exact()
    const last = lastTurn.current
    if (last === null) {
      lastTurn.current = exact
      return
    }
    if (Math.abs(exact - last) >= Math.max(1, tools.span() * 0.6)) {
      lastTurn.current = exact
      const wasPaused = timer.paused
      timer.pageTurn()
      if (wasPaused) toast('Cronômetro retomado.')
    }
  })
  // Andando pelo livro com a barra aberta, "1 de 3" segue o marcador da tela.
  useEffect(() => {
    if (markMode && here >= 0 && marks[here].id !== markId) setMarkId(marks[here].id)
    if (markMode && !marks.length) setMarkMode(false)
  })

  const showSnack = (text: string, action?: { label: string; run: () => void }) => {
    clearTimeout(snackTimer.current)
    setSnack({ text, action })
    snackTimer.current = window.setTimeout(() => setSnack(null), 4500)
  }
  const renameMark = async (mark: Bookmark, reopen = false) => {
    const name = await promptDialog('Nome do marcador', 'Nome', mark.name)
    if (name) await renameBookmark(mark.id, name)
    if (reopen) setListOpen(true)
  }
  /** Clique no canto (ou tecla B): marca a página da tela, ou desmarca se ela já tem marcador. */
  const toggleBookmark = async () => {
    if (!tools) return
    const top = tools.exact()
    const span = tools.span()
    const on = marks.filter((m) => onScreen(m.position, top, span))
    if (on.length) {
      for (const m of on) await removeBookmark(m.id)
      showSnack(`Marcador "${on[0].name}" removido`)
    } else {
      const mark = await addBookmark(tools.itemId, top)
      showSnack(`Adicionado marcador "${mark.name}"`, { label: 'MUDAR', run: () => renameMark(mark) })
    }
  }
  const openMark = (index: number) => {
    const mark = marks[index]
    if (!mark || !tools) return
    setListOpen(false)
    setSnack(null)
    setMarkId(mark.id)
    setMarkMode(true)
    tools.goToExact(mark.position)
  }
  const prevMark = () => (markIndex >= 0 ? markIndex - 1 : marks.map((m) => m.position < (tools?.exact() ?? 0)).lastIndexOf(true))
  const nextMark = () => {
    if (markIndex >= 0) return markIndex + 1 < marks.length ? markIndex + 1 : -1
    const top = tools?.exact() ?? 0
    return marks.findIndex((m) => m.position > top)
  }
  const markOptions = async (mark: Bookmark) => {
    setListOpen(false)
    const choice = await menuDialog(mark.name, ['Editar nome', 'Excluir'])
    if (choice === 0) return renameMark(mark, true)
    if (choice === 1) {
      await removeBookmark(mark.id)
      toast(`"${mark.name}" excluído.`)
    }
    setListOpen(true)
  }
  const goBack = () => {
    if (returnTo === null || !tools) return
    setReturnTo(null)
    tools.goToExact(returnTo)
  }

  useEffect(() => {
    if (!clean) return
    getPref<boolean>('cleanHint').then((seen) => {
      if (seen) return
      toast('Clique no meio da tela para mostrar os controles. Esc também mostra.')
      setPref('cleanHint', true)
    })
  }, [])

  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen?.()
    else document.documentElement.requestFullscreen?.().catch(() => toast('O navegador não permitiu a tela cheia.'))
  }

  // Atalhos (lista completa com "?"). A função muda a cada desenho; o ouvinte é um só.
  const onKey = (e: KeyboardEvent) => {
    const target = e.target as HTMLElement
    if (target.closest?.('input, textarea, select, [contenteditable], .modal, .comfort-panel') || document.querySelector('.modal')) return
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f' && tools?.search) {
      e.preventDefault()
      setPanelState('search')
      return
    }
    if (e.key === 'Escape') {
      if (panel) setPanelState(null)
      else if (clean && !shown) setShown(true)
      return
    }
    if (e.ctrlKey || e.metaKey || e.altKey || e.key.length !== 1) return
    const action: Record<string, (() => void) | undefined> = {
      b: tools ? toggleBookmark : undefined,
      m: tools ? () => setListOpen(true) : undefined,
      n: tools ? () => setPanelState((p) => (p === 'notes' ? null : 'notes')) : undefined,
      f: toggleFullscreen,
      ...keys
    }
    const run = action[e.key.toLowerCase()]
    if (run) {
      e.preventDefault()
      run()
    }
  }
  const keyHandler = useRef(onKey)
  keyHandler.current = onKey
  useEffect(() => {
    const listener = (e: KeyboardEvent) => keyHandler.current(e)
    window.addEventListener('keydown', listener)
    return () => window.removeEventListener('keydown', listener)
  }, [])

  const api: ChromeApi = {
    tap: () => {
      if (!clean) return
      // Espera para saber se não é um clique duplo (virar a página, zoom).
      clearTimeout(pendingTap.current)
      pendingTap.current = window.setTimeout(() => setShown((v) => !v), 260)
    },
    cancelTap: () => clearTimeout(pendingTap.current),
    terms,
    onTerm: (termId) => showTerm(termId),
    note: async (title, note, goTo) => {
      clearTimeout(pendingTap.current)
      const go = await noteDialog(title, note.html, goTo ? 'Ir para a nota' : null)
      if (go && goTo) goTo()
    },
    jump: (position) => {
      clearTimeout(pendingTap.current)
      setReturnTo(position)
    },
    interaction: timer.interaction,
    selection: (text, rect) => {
      const box = body.current?.getBoundingClientRect()
      if (!text || !rect || !box) return setSelection(null)
      setSelection({ text, x: rect.left + rect.width / 2 - box.left, y: rect.bottom - box.top })
    }
  }

  const showTerm = async (termId: number) => {
    clearTimeout(pendingTap.current)
    const term = terms.find((t) => t.id === termId)
    if (!term || !tools) return
    const choice = await menuDialog(term.term, ['Editar definição', 'Remover termo'], term.definition || '(sem definição)')
    if (choice === 0) defineTerm(tools.itemId, term.term, term.definition)
    else if (choice === 1 && (await confirmDialog('Remover termo?', `"${term.term}" deixa de ficar sublinhado neste livro.`, 'Remover', true))) removeTerm(term.id)
  }

  // Seleção com o mouse no texto do livro (fora de iframes): barra com Copiar, Definir termo e Anotar.
  useEffect(() => {
    const onChange = () => {
      const sel = window.getSelection()
      const box = body.current
      // Seleção vazia aqui fecha a barra (a do iframe do EPUB é avisada pelo próprio leitor).
      if (!sel || sel.isCollapsed || !box || !sel.rangeCount) {
        if (!box?.querySelector('iframe')) setSelection(null)
        return
      }
      const range = sel.getRangeAt(0)
      if (!box.contains(range.commonAncestorContainer)) return
      const text = sel.toString().trim()
      if (!text) return setSelection(null)
      const rect = range.getBoundingClientRect()
      const b = box.getBoundingClientRect()
      setSelection({ text, x: rect.left + rect.width / 2 - b.left, y: rect.bottom - b.top })
    }
    document.addEventListener('selectionchange', onChange)
    return () => document.removeEventListener('selectionchange', onChange)
  }, [])
  const onBodyClick = (e: MouseEvent) => {
    const target = e.target as HTMLElement
    if (target.closest('a, button, input, .ruler-handle')) return
    if (!(window.getSelection()?.isCollapsed ?? true)) return
    api.tap()
  }

  const toggleRuler = (on: boolean) => {
    onPrefs({ ruler: on })
    if (on) toast('Passe o mouse na alça à direita da régua e arraste para mover (ou Alt + setas). Espessura, cor e modo foco: Conforto visual.')
  }
  const toggleFocus = () => {
    const focus = prefs.comfort.rulerOutside === 'FOCO' && prefs.ruler
    onPrefs({ ruler: true, comfort: { ...prefs.comfort, rulerOutside: focus ? 'ESCURECER' : 'FOCO' } })
    if (!focus) toast('Modo foco: só o texto sob a régua fica visível.')
  }
  const openMenu = async () => {
    const focus = prefs.comfort.rulerOutside === 'FOCO' && prefs.ruler
    const actions: [string, () => void][] = [
      ['Conforto visual…', () => setPanel(true)],
      ...menu,
      ...(tools?.search ? ([['Buscar no livro…', () => setPanelState('search')]] as [string, () => void][]) : []),
      ...(tools ? ([['Anotações e glossário…', () => setPanelState('notes')]] as [string, () => void][]) : []),
      [prefs.ruler ? 'Esconder régua de leitura' : 'Mostrar régua de leitura', () => toggleRuler(!prefs.ruler)],
      [focus ? 'Desligar modo foco' : 'Modo foco (só o texto da régua)', toggleFocus],
      [clean ? 'Controles sempre visíveis' : 'Tela limpa (clique para mostrar os controles)', () => {
        onPrefs({ cleanScreen: !clean })
        setShown(true)
      }],
      [document.fullscreenElement ? 'Sair da tela cheia (F)' : 'Tela cheia (F)', toggleFullscreen],
      [finished ? 'Voltar para "Lendo agora"' : 'Concluir livro', () => onFinish(!finished)]
    ]
    const choice = await menuDialog('Opções de leitura', actions.map((a) => a[0]))
    if (choice !== null) actions[choice][1]()
  }

  const bars = !clean || shown
  return (
    <div class={clean ? 'reader clean' : 'reader'}>
      <header class="reader-bar" hidden={!bars}>
        <a href="#/biblioteca" class="bar-button" aria-label="Voltar para a biblioteca">‹</a>
        <div class="reader-title">
          <strong>{title}</strong>
          <button type="button" class="reader-timer" title={timer.paused ? 'Retomar o cronômetro' : 'Pausar o cronômetro'} onClick={() => {
            const paused = timer.toggle()
            toast(paused ? 'Cronômetro pausado. Clique no ⏱ ou vire a página para retomar.' : 'Cronômetro retomado.')
          }}>{timer.label}</button>
        </div>
        {headerExtra}
        {tools?.search && <button type="button" class="bar-button" aria-label="Buscar no livro" title="Buscar no livro (Ctrl + F)" onClick={() => setPanelState((p) => (p === 'search' ? null : 'search'))}>🔍</button>}
        {tools && <button type="button" class="bar-button" aria-label="Marcadores" title="Marcadores (M)" onClick={() => setListOpen(true)}>🔖</button>}
        {tools && <button type="button" class="bar-button" aria-label="Anotações e glossário" title="Anotações e glossário (N)" onClick={() => setPanelState((p) => (p === 'notes' ? null : 'notes'))}>📝</button>}
        <button type="button" class="bar-button" aria-label="Conforto visual" title="Conforto visual" onClick={() => setPanelState((p) => (p === 'comfort' ? null : 'comfort'))}>Aa</button>
        <button type="button" class="bar-button" aria-label="Mais opções" onClick={openMenu}>⋮</button>
      </header>
      <div class="reader-main">
        <main class="reader-body" ref={body} onClick={onBodyClick} onDblClick={() => api.cancelTap()}>
          {children(api)}
          {selection && tools && (
            <div
              class="selection-bar"
              style={{
                left: `${Math.min(Math.max(selection.x, 140), (body.current?.clientWidth ?? 400) - 140)}px`,
                top: `${Math.min(Math.max(selection.y, 4), (body.current?.clientHeight ?? 400) - 56)}px`
              }}
              onMouseDown={(e) => e.preventDefault()}
            >
              <button type="button" onClick={() => {
                navigator.clipboard?.writeText(selection.text).then(() => toast('Copiado.'))
                setSelection(null)
              }}>Copiar</button>
              <button type="button" onClick={() => {
                const text = selection.text
                setSelection(null)
                defineTerm(tools.itemId, text)
              }}>Definir termo</button>
              <button type="button" onClick={() => {
                const text = selection.text
                setSelection(null)
                annotate(tools, text)
              }}>Anotar</button>
            </div>
          )}
          {tools && <BookmarkCorner marked={here >= 0} onToggle={toggleBookmark} />}
          {prefs.ruler && <Ruler comfort={prefs.comfort} fraction={prefs.rulerY} alwaysShowHandle={false} onMove={(y) => onPrefs({ rulerY: y })} />}
          {overlay}
        </main>
        {panel === 'search' && tools && <SearchPanel tools={tools} onClose={() => setPanelState(null)} />}
        {panel === 'notes' && tools && <NotesPanel tools={tools} terms={terms} onClose={() => setPanelState(null)} />}
        {panel === 'comfort' && (
          <ComfortPanel
            comfort={prefs.comfort}
            ruler={prefs.ruler}
            typography={typography}
            onChange={(comfort) => onPrefs({ comfort })}
            onRuler={toggleRuler}
            onClose={() => setPanel(false)}
          />
        )}
      </div>
      <div class="reader-bottom">
        {returnTo !== null && (
          <div class="return-chip">
            <button type="button" onClick={goBack}>↩ Voltar</button>
            <button type="button" class="dismiss" aria-label="Dispensar o Voltar" onClick={() => setReturnTo(null)}>✕</button>
          </div>
        )}
        {snack && (
          <div class="snack" role="status">
            <span>{snack.text}</span>
            {snack.action && <button type="button" onClick={() => {
              setSnack(null)
              snack.action!.run()
            }}>{snack.action.label}</button>}
          </div>
        )}
        {atEnd && !finished && (
          <div class="finish-banner">
            <span>Você chegou ao fim.</span>
            <button type="button" onClick={() => onFinish(true)}>🏁 Concluir livro</button>
          </div>
        )}
        {markMode && (
          <BookmarkBar
            label={markIndex >= 0 ? `${markIndex + 1} de ${marks.length}` : `${marks.length} marcador(es)`}
            canPrev={prevMark() >= 0}
            canNext={nextMark() >= 0}
            onExit={() => setMarkMode(false)}
            onList={() => setListOpen(true)}
            onStep={(d) => {
              const target = d < 0 ? prevMark() : nextMark()
              if (target >= 0) openMark(target)
            }}
          />
        )}
        <footer class="reader-foot" hidden={!bars}>{footer}</footer>
      </div>
      {listOpen && tools && (
        <BookmarkList
          marks={marks}
          describe={(p) => tools.describe(p / tools.exactScale)}
          onOpen={openMark}
          onOptions={markOptions}
          onClose={() => setListOpen(false)}
        />
      )}
      <div class="comfort-layer" style={{ background: overlayColor(prefs.comfort) }} aria-hidden="true" />
      <div class="comfort-layer" style={{ background: warmColor(prefs.comfort) }} aria-hidden="true" />
      <div class="comfort-layer" style={{ background: `rgba(0, 0, 0, ${dimAlpha(prefs.comfort)})` }} aria-hidden="true" />
    </div>
  )
}
