import { useEffect, useRef, useState } from 'preact/hooks'
import type { ComponentChildren } from 'preact'

/**
 * Diálogos e avisos do site (confirmar, digitar um nome, escolher numa lista, aviso rápido),
 * no lugar dos AlertDialog e Toast do app. Uma fila simples: um diálogo por vez.
 */
type Dialog =
  | { kind: 'confirm'; title: string; message: string; ok: string; danger?: boolean; resolve: (v: boolean) => void }
  | { kind: 'prompt'; title: string; label: string; initial: string; suggestions: string[]; ok: string; resolve: (v: string | null) => void }
  | { kind: 'menu'; title: string; subtitle?: string; options: string[]; resolve: (v: number | null) => void }

let push: ((d: Dialog) => void) | null = null
let toastPush: ((text: string) => void) | null = null

export const confirmDialog = (title: string, message: string, ok = 'OK', danger = false) =>
  new Promise<boolean>((resolve) => push?.({ kind: 'confirm', title, message, ok, danger, resolve }))
export const promptDialog = (title: string, label: string, initial = '', suggestions: string[] = [], ok = 'Salvar') =>
  new Promise<string | null>((resolve) => push?.({ kind: 'prompt', title, label, initial, suggestions, ok, resolve }))
export const menuDialog = (title: string, options: string[], subtitle?: string) =>
  new Promise<number | null>((resolve) => push?.({ kind: 'menu', title, subtitle, options, resolve }))
export const toast = (text: string) => toastPush?.(text)

export function DialogHost() {
  const [queue, setQueue] = useState<Dialog[]>([])
  const [toasts, setToasts] = useState<{ id: number; text: string }[]>([])
  useEffect(() => {
    push = (d) => setQueue((q) => [...q, d])
    toastPush = (text) => {
      const id = Date.now() + Math.random()
      setToasts((t) => [...t, { id, text }])
      setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4500)
    }
    return () => {
      push = null
      toastPush = null
    }
  }, [])
  const current = queue[0]
  const close = () => setQueue((q) => q.slice(1))
  return (
    <>
      {current && <DialogView dialog={current} close={close} />}
      <div class="toasts" role="status" aria-live="polite">
        {toasts.map((t) => <div key={t.id} class="toast">{t.text}</div>)}
      </div>
    </>
  )
}

function Modal({ title, subtitle, children, onCancel }: { title: string; subtitle?: string; children: ComponentChildren; onCancel: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ref.current?.querySelector<HTMLElement>('input, button.primary-action, button')?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onCancel()
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      previous?.focus?.()
    }
  }, [])
  return (
    <div class="modal-backdrop" onClick={(e) => e.target === e.currentTarget && onCancel()}>
      <div class="modal" role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <h3>{title}</h3>
        {subtitle && <p class="muted small">{subtitle}</p>}
        {children}
      </div>
    </div>
  )
}

function DialogView({ dialog, close }: { dialog: Dialog; close: () => void }) {
  const [value, setValue] = useState(dialog.kind === 'prompt' ? dialog.initial : '')
  if (dialog.kind === 'confirm') {
    const answer = (v: boolean) => {
      close()
      dialog.resolve(v)
    }
    return (
      <Modal title={dialog.title} onCancel={() => answer(false)}>
        <p class="modal-text">{dialog.message}</p>
        <div class="modal-actions">
          <button type="button" onClick={() => answer(false)}>Cancelar</button>
          <button type="button" class={dialog.danger ? 'primary-action danger' : 'primary-action'} onClick={() => answer(true)}>{dialog.ok}</button>
        </div>
      </Modal>
    )
  }
  if (dialog.kind === 'prompt') {
    const answer = (v: string | null) => {
      close()
      dialog.resolve(v)
    }
    return (
      <Modal title={dialog.title} onCancel={() => answer(null)}>
        <form onSubmit={(e) => { e.preventDefault(); if (value.trim()) answer(value.trim()) }}>
          <label class="field">
            <span>{dialog.label}</span>
            <input value={value} onInput={(e) => setValue((e.target as HTMLInputElement).value)} list="dialog-suggestions" autoFocus />
          </label>
          <datalist id="dialog-suggestions">{dialog.suggestions.map((s) => <option key={s} value={s} />)}</datalist>
          <div class="modal-actions">
            <button type="button" onClick={() => answer(null)}>Cancelar</button>
            <button type="submit" class="primary-action" disabled={!value.trim()}>{dialog.ok}</button>
          </div>
        </form>
      </Modal>
    )
  }
  const answer = (v: number | null) => {
    close()
    dialog.resolve(v)
  }
  return (
    <Modal title={dialog.title} subtitle={dialog.subtitle} onCancel={() => answer(null)}>
      <div class="menu-list">
        {dialog.options.map((o, i) => <button type="button" key={o} onClick={() => answer(i)}>{o}</button>)}
      </div>
      <div class="modal-actions">
        <button type="button" onClick={() => answer(null)}>Cancelar</button>
      </div>
    </Modal>
  )
}
