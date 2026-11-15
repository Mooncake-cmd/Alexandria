import { describe, expect, it } from 'vitest'
import {
  applyMatrix, DEFAULT_COMFORT, dimAlpha, epubCss, overlayAlpha, pageMatrix, rulerAlpha, sanitizeComfort, sidePadding,
  svgMatrixValues, THEMES, TINTS, warmAlpha, type Comfort
} from './comfort'

// Mesmos casos de ComfortMathTest do app (o brilho do site é só escurecimento, ver dimAlpha).
const c = (over: Partial<Comfort> = {}): Comfort => ({ ...DEFAULT_COMFORT, ...over })
const rgb = (x: number) => [(x >> 16) & 255, (x >> 8) & 255, x & 255]
const close = (a: number, b: number) => rgb(a).every((v, i) => Math.abs(v - rgb(b)[i]) <= 2)

describe('conforto visual', () => {
  it('tema claro sem filtro; sépia tinge o papel', () => {
    expect(pageMatrix('CLARO')).toBeNull()
    const sepia = pageMatrix('SEPIA')!
    expect(close(THEMES.SEPIA.background, applyMatrix(sepia, 0xffffff))).toBe(true)
    expect(close(0x000000, applyMatrix(sepia, 0x000000))).toBe(true)
  })

  it('temas escuros invertem a luz mantendo o matiz', () => {
    for (const theme of ['ESCURO', 'ESCURO_SUAVE'] as const) {
      const m = pageMatrix(theme)!
      expect(close(THEMES[theme].background, applyMatrix(m, 0xffffff))).toBe(true)
      expect(close(THEMES[theme].text, applyMatrix(m, 0x000000))).toBe(true)
      const [r, g, b] = rgb(applyMatrix(m, 0xd02020))
      expect(r > g + 30 && r > b + 30).toBe(true)
    }
    expect(svgMatrixValues(pageMatrix('SEPIA')!).split(' ').length).toBe(20)
  })

  it('brilho do app escurece a tela', () => {
    expect(dimAlpha(c())).toBe(0)
    expect(dimAlpha(c({ systemBrightness: false, brightness: 0 }))).toBeCloseTo(0.8)
    expect(dimAlpha(c({ systemBrightness: false, brightness: 100 }))).toBe(0)
  })

  it('cores da luz quente, sobreposição e régua', () => {
    expect(warmAlpha(c())).toBe(0)
    expect(warmAlpha(c({ warmth: 100 }))).toBe(82)
    expect(overlayAlpha(c({ overlayOn: false }))).toBe(0)
    expect(overlayAlpha(c({ overlayOn: true, overlayColor: 'AZUL', overlayOpacity: 20 }))).toBe(51)
    expect(rulerAlpha(c({ rulerColor: 'VERDE', rulerOpacity: 40 }))).toBe(102)
    expect(TINTS.VERDE.rgb).toBe(0x6bbf59)
  })

  it('largura da linha limita a coluna de texto', () => {
    expect(sidePadding(1080, 40, 40, 'LIVRE')).toBe(40)
    expect(sidePadding(1080, 40, 40, 'MEDIA')).toBe(40)
    expect(sidePadding(2000, 40, 40, 'MEDIA')).toBe(350)
  })

  it('CSS do EPUB leva tema e tipografia', () => {
    const css = epubCss(c({ theme: 'SEPIA', lineSpacing: 180, letterSpacing: 5, lineWidth: 'ESTREITA', justify: true, font: 'ATKINSON', margin: 'GRANDE' }), '@font-face{x}')
    for (const part of ['background: #F4ECD8', 'color: #5B4636', 'line-height: 1.8', 'letter-spacing: 0.05em', 'max-width: 27.5em', 'text-align: justify', 'padding: 20px 11%', '@font-face{x}', "'Atkinson Hyperlegible'"]) {
      expect(css).toContain(part)
    }
    const plain = epubCss(c(), '@font-face{x}')
    expect(plain).not.toContain('@font-face')
    expect(plain).not.toContain('font-family')
    expect(plain).toContain('text-align: left')
  })

  it('preferências salvas inválidas voltam ao padrão', () => {
    expect(sanitizeComfort(null)).toEqual(DEFAULT_COMFORT)
    const read = sanitizeComfort({ theme: 'SEPIA', brightness: 'muito', font: 'COMIC_SANS', justify: true })
    expect(read.theme).toBe('SEPIA')
    expect(read.brightness).toBe(DEFAULT_COMFORT.brightness)
    expect(read.font).toBe('ORIGINAL')
    expect(read.justify).toBe(true)
  })
})
