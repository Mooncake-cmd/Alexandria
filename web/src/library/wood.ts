/**
 * Textura de madeira gerada (repete sem emenda): veios em faixas com ondulação suave. Porte de
 * WoodenShelf.woodTile do app, para o fundo, as laterais e as tábuas da Estante.
 */
function mulberry32(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const mix = (a: number, b: number, t: number) => Math.round(a + (b - a) * t)

const cache = new Map<string, string>()

/** URL (data:) de uma textura de madeira. `horizontal` deita os veios (tábuas). */
export function woodTexture(horizontal: boolean, dark: [number, number, number], light: [number, number, number], seed: number): string {
  const key = `${horizontal}|${dark}|${light}|${seed}`
  const cached = cache.get(key)
  if (cached) return cached
  const across = 256
  const along = 512
  const random = mulberry32(seed)
  // Ruído 1D circular em várias escalas: largura e tom dos veios.
  const noise = new Float32Array(across)
  let amplitude = 1
  for (let period = 64; period >= 2; period /= 2) {
    const points = Array.from({ length: across / period }, () => random())
    for (let i = 0; i < across; i++) {
      const p = Math.floor(i / period)
      const f = (i % period) / period
      const a = points[p]
      const b = points[(p + 1) % points.length]
      const t = f * f * (3 - 2 * f)
      noise[i] += (a + (b - a) * t) * amplitude
    }
    amplitude *= 0.55
  }
  let lo = Infinity
  let hi = -Infinity
  for (const v of noise) {
    lo = Math.min(lo, v)
    hi = Math.max(hi, v)
  }
  for (let i = 0; i < across; i++) noise[i] = (noise[i] - lo) / (hi - lo)
  const phase = random() * 6.28
  const width = horizontal ? along : across
  const height = horizontal ? across : along
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')!
  const image = context.createImageData(width, height)
  for (let y = 0; y < along; y++) {
    const wave = 3.5 * Math.sin(((2 * Math.PI * y) / along) * 2 + phase) + 1.5 * Math.sin(((2 * Math.PI * y) / along) * 5)
    for (let x = 0; x < across; x++) {
      const shifted = (((Math.round(x + wave + 2 * Math.sin(x * 0.07 + y * 0.012)) % across) + across) % across)
      let t = noise[shifted]
      t = t * t * (3 - 2 * t)
      t = Math.min(1, Math.max(0, t + (random() - 0.5) * 0.08))
      const px = horizontal ? y : x
      const py = horizontal ? x : y
      const o = (py * width + px) * 4
      image.data[o] = mix(dark[0], light[0], t)
      image.data[o + 1] = mix(dark[1], light[1], t)
      image.data[o + 2] = mix(dark[2], light[2], t)
      image.data[o + 3] = 255
    }
  }
  context.putImageData(image, 0, 0)
  const url = canvas.toDataURL('image/png')
  cache.set(key, url)
  return url
}

/** As três madeiras da Estante (mesmas cores do app). */
export function shelfWoods() {
  return {
    back: woodTexture(false, [40, 23, 13], [74, 45, 27], 11),
    frame: woodTexture(false, [78, 48, 27], [132, 86, 50], 23),
    board: woodTexture(true, [92, 57, 32], [146, 96, 56], 37)
  }
}

/** Couro da capa gerada (livro sem capa), pela cor do título (como o app). */
export const LEATHERS = ['#601c1a', '#1e2e4a', '#22402e', '#4e301a', '#2c2826', '#584622']

export function leatherOf(title: string): string {
  let hash = 0
  for (let i = 0; i < title.length; i++) hash = (Math.imul(hash, 31) + title.charCodeAt(i)) | 0
  return LEATHERS[Math.abs(hash) % LEATHERS.length]
}
