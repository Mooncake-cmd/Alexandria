import { expect, it } from 'vitest'
import { mergeLines } from './lines'

it('junta caixas da mesma linha e mantém linhas diferentes separadas', () => {
  const merged = mergeLines([
    { top: 10, bottom: 30 }, { top: 12, bottom: 29 }, // mesma linha (negrito)
    { top: 40, bottom: 60 },
    { top: 35, bottom: 75 } // imagem alta que cobre a linha 40..60
  ])
  expect(merged).toEqual([{ top: 10, bottom: 30 }, { top: 35, bottom: 75 }])
})
