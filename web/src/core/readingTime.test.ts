import { describe, expect, it } from 'vitest'
import { formatClock, formatReadingTime, spreadRemoval } from './readingTime'

// Mesmos casos dos testes do app (GlossaryAndTimeTest, LibraryDatabaseTest).
describe('tempo de leitura', () => {
  it('formata como o app', () => {
    expect(formatReadingTime(45)).toBe('45 s')
    expect(formatReadingTime(-3)).toBe('0 s')
    expect(formatReadingTime(12 * 60 + 30)).toBe('12 min')
    expect(formatReadingTime(2 * 3600 + 5 * 60)).toBe('2 h 05 min')
  })

  it('cronômetro', () => {
    expect(formatClock(247)).toBe('4:07')
    expect(formatClock(755)).toBe('12:35')
    expect(formatClock(3723)).toBe('1:02:03')
  })

  it('reparte a remoção na proporção e com o total exato', () => {
    expect(spreadRemoval({ a: 300, b: 100 }, 100)).toEqual({ a: 75, b: 25 })
    const parts = spreadRemoval({ a: 7, b: 7, c: 7 }, 10)
    expect(Object.values(parts).reduce((x, y) => x + y, 0)).toBe(10)
    expect(spreadRemoval({ a: 5, b: 0 }, 50)).toEqual({ a: 5 })
    expect(spreadRemoval({}, 50)).toEqual({})
  })
})
