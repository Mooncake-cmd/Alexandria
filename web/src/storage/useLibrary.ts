import { useEffect, useState } from 'preact/hooks'
import { allItems, onLibraryChange, type LibraryItem } from './library'

/** Lista de livros sempre atualizada (recarrega quando a biblioteca muda, nesta aba ou em outra). */
export function useLibrary(): { items: LibraryItem[]; loaded: boolean } {
  const [state, setState] = useState<{ items: LibraryItem[]; loaded: boolean }>({ items: [], loaded: false })
  useEffect(() => {
    let alive = true
    const load = () => allItems().then((items) => alive && setState({ items, loaded: true }))
    load()
    const off = onLibraryChange(load)
    return () => {
      alive = false
      off()
    }
  }, [])
  return state
}

/** Recarrega `load` quando a biblioteca muda. */
export function useLibraryData<T>(load: () => Promise<T>, initial: T, deps: unknown[] = []): T {
  const [value, setValue] = useState<T>(initial)
  useEffect(() => {
    let alive = true
    const run = () => load().then((v) => alive && setValue(v))
    run()
    const off = onLibraryChange(run)
    return () => {
      alive = false
      off()
    }
  }, deps)
  return value
}
