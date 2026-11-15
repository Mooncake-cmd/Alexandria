import { useEffect, useRef, useState } from 'preact/hooks'
import { RULER_HEIGHTS, rulerColor, rulerEdgeColor, type Comfort } from '../core/comfort'

/** Quanto tempo a alça fica à vista depois de aparecer. */
const HANDLE_MS = 3000

/**
 * Régua de leitura: faixa sobre a linha lida; fora dela, nada, escurecer ou preto (modo foco).
 * A alça à direita aparece ao passar o mouse sobre ela (ou por 3 s ao ligar) e é arrastada com o
 * mouse; Alt + ↑/↓ também move. O resto da régua não recebe cliques: o livro continua normal.
 */
export function Ruler({ comfort, fraction, alwaysShowHandle, onMove }: {
  comfort: Comfort
  fraction: number
  alwaysShowHandle: boolean
  onMove: (fraction: number) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const [shown, setShown] = useState(true)
  const [value, setValue] = useState(fraction)
  const dragging = useRef<{ offset: number } | null>(null)
  const hide = useRef<number>(0)

  const reveal = () => {
    setShown(true)
    clearTimeout(hide.current)
    hide.current = window.setTimeout(() => !dragging.current && setShown(false), HANDLE_MS)
  }
  useEffect(() => {
    reveal()
    return () => clearTimeout(hide.current)
  }, [])
  useEffect(() => setValue(fraction), [fraction])

  // Alt + ↑/↓ move a régua (um pouco de cada vez).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.altKey || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return
      e.preventDefault()
      const next = Math.min(1, Math.max(0, value + (e.key === 'ArrowUp' ? -0.02 : 0.02)))
      setValue(next)
      onMove(next)
      reveal()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [value])

  const height = RULER_HEIGHTS[comfort.rulerHeight].px
  const total = box.current?.clientHeight ?? 0
  const top = Math.min(Math.max(0, value * total - height / 2), Math.max(0, total - height))
  const outside = comfort.rulerOutside === 'FOCO' ? '#000' : comfort.rulerOutside === 'ESCURECER' ? 'rgba(16, 26, 42, 0.27)' : 'transparent'

  const startDrag = (e: PointerEvent) => {
    e.preventDefault()
    const el = box.current!
    ;(e.target as Element).setPointerCapture(e.pointerId)
    dragging.current = { offset: e.clientY - (el.getBoundingClientRect().top + value * el.clientHeight) }
    setShown(true)
  }
  const drag = (e: PointerEvent) => {
    if (!dragging.current) return
    const el = box.current!
    const r = el.getBoundingClientRect()
    setValue(Math.min(1, Math.max(0, (e.clientY - dragging.current.offset - r.top) / Math.max(1, r.height))))
  }
  const endDrag = () => {
    if (!dragging.current) return
    dragging.current = null
    onMove(value)
    reveal()
  }

  return (
    <div ref={box} class="ruler" aria-hidden={!alwaysShowHandle && !shown}>
      <div class="ruler-outside" style={{ top: 0, height: `${top}px`, background: outside }} />
      <div class="ruler-band" style={{ top: `${top}px`, height: `${height}px`, background: rulerColor(comfort), borderBottom: `2px solid ${rulerEdgeColor(comfort)}` }} />
      <div class="ruler-outside" style={{ top: `${top + height}px`, bottom: 0, background: outside }} />
      <button
        type="button"
        class={alwaysShowHandle || shown ? 'ruler-handle shown' : 'ruler-handle'}
        style={{ top: `${top + 4}px`, height: `${Math.max(24, height - 8)}px`, background: rulerEdgeColor(comfort) }}
        aria-label="Alça da régua de leitura: arraste para mover (ou Alt + setas para cima e para baixo)"
        onPointerEnter={reveal}
        onPointerDown={startDrag}
        onPointerMove={drag}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
      >
        <span /><span /><span />
      </button>
    </div>
  )
}
