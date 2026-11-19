import { describe, expect, it } from 'vitest'
import { indexOnScreen, nextBookmarkName, onScreen, type Bookmark } from './bookmarks'
import { looksLikeNoteMark, noteTitle } from './links'
import { describeNoteLocation } from './formats'
import { splitPosition } from './pages'

// Mesmos casos de BookmarksAndLinksTest do app.
describe('marcadores', () => {
  it('nomeia a partir do maior número já usado', () => {
    expect(nextBookmarkName([])).toBe('Marcador 1')
    expect(nextBookmarkName(['Marcador 1'])).toBe('Marcador 2')
    expect(nextBookmarkName(['Marcador 3', 'Prefácio', 'Marcador 1'])).toBe('Marcador 4')
  })

  it('reconhece o marcador da tela', () => {
    expect(onScreen(38, 38, 1)).toBe(true)
    expect(onScreen(37, 38, 1)).toBe(false)
    expect(onScreen(10_000, 10_000, 2_000)).toBe(true)
    expect(onScreen(9_950, 10_000, 2_000)).toBe(true)
    expect(onScreen(11_800, 10_000, 2_000)).toBe(true)
    expect(onScreen(11_950, 10_000, 2_000)).toBe(false)
    expect(onScreen(9_800, 10_000, 2_000)).toBe(false)
    const marks = [5, 40].map((position, id) => ({ id, itemId: 1, name: '', position, createdAt: 0 }) as Bookmark)
    expect(indexOnScreen(marks, 40, 1)).toBe(1)
    expect(indexOnScreen(marks, 6, 1)).toBe(-1)
  })

  it('marcador no começo de um capítulo mostra esse capítulo, não o anterior', () => {
    expect(describeNoteLocation('epub', (16 / 365) * 10000, 365)).toBe('Cap. 17 · 4%')
  })
})

describe('chamadas de nota', () => {
  it('números curtos e símbolos parecem chamadas de nota', () => {
    for (const mark of ['1', '12', '[3]', '(4)', '5.', 'a', '*', '**', '†', '‡']) expect(looksLikeNoteMark(mark), mark).toBe(true)
    for (const text of ['Capítulo 1', 'Sumário', '12345', 'nota', '']) expect(looksLikeNoteMark(text), text).toBe(false)
    expect(noteTitle('[12]')).toBe('Nota 12')
    expect(noteTitle('5')).toBe('Nota 5')
    expect(noteTitle('Capítulo 2')).toBe('Nota')
  })
})

describe('posição salva → capítulo (BookPosition.split)', () => {
  it('um ponto no começo de um capítulo volta para esse capítulo', () => {
    const exact = Math.trunc((40 / 365) * 1_000_000) // 109589: arredondado para baixo
    expect(splitPosition(exact / 1_000_000, 365, 1e-6)).toEqual([40, 0])
    expect(splitPosition(Math.round((40 / 365) * 10_000) / 10_000, 365, 1e-4)[0]).toBe(40)
    const [index, inside] = splitPosition(40.5 / 365, 365, 1e-6)
    expect(index).toBe(40)
    expect(inside).toBeCloseTo(0.5, 6)
    expect(splitPosition(1, 365, 1e-6)).toEqual([364, 1])
    expect(splitPosition(0, 365, 1e-6)).toEqual([0, 0])
  })
})

