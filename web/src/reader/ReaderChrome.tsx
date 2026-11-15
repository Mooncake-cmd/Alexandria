import { useEffect, useRef, useState } from 'preact/hooks'
import type { ComponentChildren, VNode } from 'preact'
import { dimAlpha, overlayColor, warmColor } from '../core/comfort'
import { getPref, setPref } from '../storage/library'
import { menuDialog, toast } from '../ui/dialogs'
import { ComfortPanel } from './ComfortPanel'
import { Ruler } from './Ruler'
import type { ReadingPrefs } from './prefs'

export interface ChromeApi {
  /** Um clique simples no livro (fora de links e seleções): mostra/esconde os controles. */
  tap: () => void
  /** Clique duplo: cancela o clique simples que estava esperando. */
  cancelTap: () => void
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
  children: (api: ChromeApi) => ComponentChildren
}

/**
 * Moldura comum dos leitores: barra de cima, controles de baixo, aviso de fim, camadas de luz e
 * filtro, régua, painel de conforto e tela limpa (as barras ficam por cima do livro e somem; um
 * clique no meio mostra e esconde; Esc mostra; F põe em tela cheia).
 */
export function ReaderChrome({ title, prefs, onPrefs, typography, menu, headerExtra, footer, finished, atEnd, onFinish, overlay, children }: Props) {
  const [shown, setShown] = useState(!prefs.cleanScreen)
  const [panel, setPanel] = useState(false)
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
    cancelTap: () => clearTimeout(pendingTap.current)
  }
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
        <button type="button" class="bar-button" aria-label="Conforto visual" title="Conforto visual" onClick={() => setPanel((v) => !v)}>Aa</button>
        <button type="button" class="bar-button" aria-label="Mais opções" onClick={openMenu}>⋮</button>
      </header>
      <div class="reader-main">
        <div class="reader-body" onClick={onBodyClick} onDblClick={() => api.cancelTap()}>
          {children(api)}
          {prefs.ruler && <Ruler comfort={prefs.comfort} fraction={prefs.rulerY} alwaysShowHandle={false} onMove={(y) => onPrefs({ rulerY: y })} />}
          {overlay}
        </div>
        {panel && (
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
