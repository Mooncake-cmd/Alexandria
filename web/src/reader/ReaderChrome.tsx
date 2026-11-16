import { useEffect, useRef, useState } from 'preact/hooks'
import type { ComponentChildren, VNode } from 'preact'
import { dimAlpha, overlayColor, warmColor } from '../core/comfort'
import { getPref, setPref, termsFor } from '../storage/library'
import { useLibraryData } from '../storage/useLibrary'
import type { GlossaryTerm } from '../core/glossary'
import { annotate, defineTerm, NotesPanel, SearchPanel, type ReaderTools } from './ToolsPanel'
import { removeTerm } from '../storage/library'
import { confirmDialog } from '../ui/dialogs'
import { menuDialog, toast } from '../ui/dialogs'
import { ComfortPanel } from './ComfortPanel'
import { Ruler } from './Ruler'
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
  children: (api: ChromeApi) => ComponentChildren
}

/**
 * Moldura comum dos leitores: barra de cima, controles de baixo, aviso de fim, camadas de luz e
 * filtro, régua, painel de conforto e tela limpa (as barras ficam por cima do livro e somem; um
 * clique no meio mostra e esconde; Esc mostra; F põe em tela cheia).
 */
export function ReaderChrome({ title, prefs, onPrefs, typography, menu, headerExtra, footer, finished, atEnd, onFinish, overlay, tools, children }: Props) {
  const [shown, setShown] = useState(!prefs.cleanScreen)
  const [panel, setPanelState] = useState<null | 'comfort' | 'search' | 'notes'>(null)
  const setPanel = (v: boolean) => setPanelState(v ? 'comfort' : null)
  const terms = useLibraryData<GlossaryTerm[]>(() => (tools ? termsFor(tools.itemId) : Promise.resolve([])), [], [tools?.itemId])
  const [selection, setSelection] = useState<{ text: string; x: number; y: number } | null>(null)
  const body = useRef<HTMLDivElement>(null)
  const pendingTap = useRef(0)
  const clean = prefs.cleanScreen

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

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.closest?.('input, textarea, [contenteditable], .modal, .comfort-panel')) return
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f' && tools?.search) {
        e.preventDefault()
        setPanelState('search')
        return
      }
      if (e.key === 'Escape' && clean && !shown) setShown(true)
      else if ((e.key === 'f' || e.key === 'F') && !e.ctrlKey && !e.metaKey && !e.altKey) toggleFullscreen()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [clean, shown])

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
        <div class="reader-title"><strong>{title}</strong></div>
        {headerExtra}
        {tools?.search && <button type="button" class="bar-button" aria-label="Buscar no livro" title="Buscar no livro (Ctrl + F)" onClick={() => setPanelState((p) => (p === 'search' ? null : 'search'))}>🔍</button>}
        {tools && <button type="button" class="bar-button" aria-label="Anotações e glossário" title="Anotações e glossário" onClick={() => setPanelState((p) => (p === 'notes' ? null : 'notes'))}>📝</button>}
        <button type="button" class="bar-button" aria-label="Conforto visual" title="Conforto visual" onClick={() => setPanelState((p) => (p === 'comfort' ? null : 'comfort'))}>Aa</button>
        <button type="button" class="bar-button" aria-label="Mais opções" onClick={openMenu}>⋮</button>
      </header>
      <div class="reader-main">
        <div class="reader-body" ref={body} onClick={onBodyClick} onDblClick={() => api.cancelTap()}>
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
          {prefs.ruler && <Ruler comfort={prefs.comfort} fraction={prefs.rulerY} alwaysShowHandle={false} onMove={(y) => onPrefs({ rulerY: y })} />}
          {overlay}
        </div>
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
        {atEnd && !finished && (
          <div class="finish-banner">
            <span>Você chegou ao fim.</span>
            <button type="button" onClick={() => onFinish(true)}>🏁 Concluir livro</button>
          </div>
        )}
        <footer class="reader-foot" hidden={!bars}>{footer}</footer>
      </div>
      <div class="comfort-layer" style={{ background: overlayColor(prefs.comfort) }} aria-hidden="true" />
      <div class="comfort-layer" style={{ background: warmColor(prefs.comfort) }} aria-hidden="true" />
      <div class="comfort-layer" style={{ background: `rgba(0, 0, 0, ${dimAlpha(prefs.comfort)})` }} aria-hidden="true" />
    </div>
  )
}
