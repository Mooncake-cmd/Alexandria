import { describe, expect, it } from 'vitest'
import { BLOCK, BlockReader } from './blockReader'
import { clock, resumeAt, watched } from '../core/videoProgress'

const file = (size: number) => Uint8Array.from({ length: size }, (_, i) => (i * 7) % 251)

function reader(size: number, maxBlocks = 4, ahead = 1) {
  const data = file(size)
  const asked: number[] = []
  const r = new BlockReader(size, async (index) => {
    asked.push(index)
    return data.slice(index * BLOCK, Math.min(size, (index + 1) * BLOCK))
  }, maxBlocks, ahead)
  return { r, data, asked }
}

describe('leitura por blocos (streaming)', () => {
  it('lê trechos que atravessam blocos e o fim do arquivo', async () => {
    const size = BLOCK * 3 + 1000
    const { r, data } = reader(size)
    expect(await r.read(10, 20)).toEqual(data.slice(10, 30))
    expect(await r.read(BLOCK - 5, 10)).toEqual(data.slice(BLOCK - 5, BLOCK + 5))
    expect(await r.read(size - 100, 1000)).toEqual(data.slice(size - 100))
    expect((await r.read(size, 10)).length).toBe(0)
  })

  it('não baixa o mesmo bloco duas vezes, pede o próximo antes e esquece os antigos', async () => {
    const { r, asked } = reader(BLOCK * 10, 3, 1)
    await Promise.all([r.read(0, 100), r.read(50, 100)])
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(asked).toEqual([0, 1])
    await r.read(BLOCK * 5, 10)
    await new Promise((resolve) => setTimeout(resolve, 0))
    await r.read(0, 10)
    // Com espaço para 3 blocos, o 0 saiu (entraram 5 e 6) e foi pedido de novo.
    expect(asked.filter((i) => i === 0)).toHaveLength(2)
  })

  it('erro num bloco não fica guardado: a próxima leitura tenta de novo', async () => {
    let fail = true
    const r = new BlockReader(BLOCK, async () => {
      if (fail) throw new Error('rede')
      return file(BLOCK)
    })
    await expect(r.read(0, 10)).rejects.toThrow('rede')
    fail = false
    expect((await r.read(0, 10)).length).toBe(10)
  })
})

describe('continuar assistindo', () => {
  it('retoma do ponto salvo, menos no começo e no fim', () => {
    expect(resumeAt(5, 3600)).toBe(0)
    expect(resumeAt(2050, 3600)).toBe(2050)
    expect(resumeAt(3590, 3600)).toBe(0)
    expect(watched(3590, 3600)).toBe(true)
    expect(watched(3000, 3600)).toBe(false)
    // Filme de 2 h: 5% são 6 minutos de créditos.
    expect(watched(7200 - 300, 7200)).toBe(true)
    expect(clock(2050)).toBe('34:10')
    expect(clock(3 * 3600 + 65)).toBe('3:01:05')
  })
})
