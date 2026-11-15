import { expect, it } from 'vitest'
import { SECTIONS } from './sections'

it('tem as mesmas abas do app', () => {
  expect(SECTIONS.map((s) => s.label)).toEqual(['Biblioteca', 'Notas', 'Estatísticas', 'Ajustes'])
})
