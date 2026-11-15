/** Conforto visual: temas, luz, sobreposição, tipografia e régua. Porte de Comfort.kt (cores em 0xRRGGBB). */

export const THEMES = {
  CLARO: { label: 'Claro', background: 0xf5f2eb, text: 0x162234, link: 0xb78942, dark: false },
  SEPIA: { label: 'Sépia', background: 0xf4ecd8, text: 0x5b4636, link: 0x9a6a2f, dark: false },
  CINZA: { label: 'Cinza suave', background: 0xddddd8, text: 0x333333, link: 0x8a6a30, dark: false },
  ESCURO_SUAVE: { label: 'Escuro suave', background: 0x2b2b2b, text: 0xc8c8c2, link: 0xc9a46a, dark: true },
  ESCURO: { label: 'Escuro', background: 0x12161c, text: 0xd6d0c4, link: 0xc69a52, dark: true }
} as const
export type Theme = keyof typeof THEMES

export const TINTS = {
  DOURADO: { label: 'Dourado', rgb: 0xc69a52 },
  AZUL: { label: 'Azul', rgb: 0x4a90e2 },
  VERDE: { label: 'Verde', rgb: 0x6bbf59 },
  AMARELO: { label: 'Amarelo', rgb: 0xf5d547 },
  ROSA: { label: 'Rosa', rgb: 0xf28fb5 },
  LILAS: { label: 'Lilás', rgb: 0xa98bd6 },
  TURQUESA: { label: 'Turquesa', rgb: 0x4dc3c3 },
  PESSEGO: { label: 'Pêssego', rgb: 0xf6b48f },
  CINZA: { label: 'Cinza', rgb: 0x8c8c8c }
} as const
export type Tint = keyof typeof TINTS

export const MARGINS = {
  PEQUENA: { label: 'Pequena', px: 12, percent: 3 },
  MEDIA: { label: 'Média', px: 22, percent: 6 },
  GRANDE: { label: 'Grande', px: 40, percent: 11 }
} as const
export type Margin = keyof typeof MARGINS

/** Largura máxima da linha, em caracteres (0 = livre). */
export const LINE_WIDTHS = {
  LIVRE: { label: 'Livre', chars: 0 },
  LARGA: { label: '~75 caracteres', chars: 75 },
  MEDIA: { label: '~65 caracteres', chars: 65 },
  ESTREITA: { label: '~55 caracteres', chars: 55 }
} as const
export type LineWidth = keyof typeof LINE_WIDTHS

export const FONTS = {
  ORIGINAL: { label: 'Original do livro', css: null },
  SERIFADA: { label: 'Serifada', css: "Georgia, 'Noto Serif', serif" },
  SEM_SERIFA: { label: 'Sem serifa', css: "system-ui, 'Segoe UI', Roboto, 'Noto Sans', sans-serif" },
  ATKINSON: { label: 'Atkinson Hyperlegible', css: "'Atkinson Hyperlegible', sans-serif" }
} as const
export type Font = keyof typeof FONTS

export const RULER_OUTSIDE = { NORMAL: 'Normal', ESCURECER: 'Escurecer', FOCO: 'Preto (modo foco)' } as const
export type RulerOutside = keyof typeof RULER_OUTSIDE

export const RULER_HEIGHTS = {
  FINA: { label: 'Fina (1 linha)', px: 30 },
  MEDIA: { label: 'Média (2 linhas)', px: 46 },
  GROSSA: { label: 'Grossa (3 linhas)', px: 70 }
} as const
export type RulerHeight = keyof typeof RULER_HEIGHTS

export interface Comfort {
  theme: Theme
  /** Sem controle de brilho no navegador: false liga o escurecimento pelo `brightness`. */
  systemBrightness: boolean
  brightness: number
  warmth: number
  overlayOn: boolean
  overlayColor: Tint
  overlayOpacity: number
  lineSpacing: number
  letterSpacing: number
  margin: Margin
  lineWidth: LineWidth
  justify: boolean
  font: Font
  rulerHeight: RulerHeight
  rulerColor: Tint
  rulerOpacity: number
  rulerOutside: RulerOutside
}

export const DEFAULT_COMFORT: Comfort = {
  theme: 'CLARO',
  systemBrightness: true,
  brightness: 60,
  warmth: 0,
  overlayOn: false,
  overlayColor: 'AZUL',
  overlayOpacity: 15,
  lineSpacing: 140,
  letterSpacing: 0,
  margin: 'MEDIA',
  lineWidth: 'LIVRE',
  justify: false,
  font: 'ORIGINAL',
  rulerHeight: 'MEDIA',
  rulerColor: 'DOURADO',
  rulerOpacity: 25,
  rulerOutside: 'ESCURECER'
}

export const AVERAGE_CHAR_EM = 0.5
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))

export const hex = (rgb: number) => `#${(rgb & 0xffffff).toString(16).toUpperCase().padStart(6, '0')}`
export const rgba = (rgb: number, alpha: number) =>
  `rgba(${(rgb >> 16) & 255}, ${(rgb >> 8) & 255}, ${rgb & 255}, ${Math.round(clamp(alpha, 0, 1) * 1000) / 1000})`

/**
 * Escurecimento da tela (0 a 0,8). O navegador não muda o brilho do monitor: o "brilho do app"
 * é uma camada preta por cima, como o escurecimento extra do app abaixo do mínimo do sistema.
 */
export function dimAlpha(c: Comfort): number {
  return c.systemBrightness ? 0 : ((100 - clamp(c.brightness, 0, 100)) / 100) * 0.8
}

/** Opacidade (0 a 255) do filtro de luz quente (âmbar, até ~32%). */
export const warmAlpha = (c: Comfort) => Math.round((clamp(c.warmth, 0, 100) * 0.32 * 255) / 100)
export const warmColor = (c: Comfort) => rgba(0xff9329, warmAlpha(c) / 255)

/** Opacidade (0 a 255) da sobreposição colorida (0 se desligada). */
export const overlayAlpha = (c: Comfort) => (c.overlayOn ? Math.round((clamp(c.overlayOpacity, 0, 60) * 255) / 100) : 0)
export const overlayColor = (c: Comfort) => rgba(TINTS[c.overlayColor].rgb, overlayAlpha(c) / 255)

export const rulerAlpha = (c: Comfort) => Math.round((clamp(c.rulerOpacity, 5, 80) * 255) / 100)
export const rulerColor = (c: Comfort) => rgba(TINTS[c.rulerColor].rgb, rulerAlpha(c) / 255)
export const rulerEdgeColor = (c: Comfort) => rgba(TINTS[c.rulerColor].rgb, 210 / 255)

const r = (c: number) => (c >> 16) & 255
const g = (c: number) => (c >> 8) & 255
const b = (c: number) => c & 255

/**
 * Matriz de cor 4x5 para PDF, DJVU e quadrinhos (vira um filtro SVG feColorMatrix). Temas claros
 * multiplicam pela cor do papel; os escuros invertem a luminosidade mantendo o matiz. null no tema claro.
 */
export function pageMatrix(theme: Theme): number[] | null {
  if (theme === 'CLARO') return null
  const t = THEMES[theme]
  if (!t.dark) {
    const bg = t.background
    return [r(bg) / 255, 0, 0, 0, 0, 0, g(bg) / 255, 0, 0, 0, 0, 0, b(bg) / 255, 0, 0, 0, 0, 0, 1, 0]
  }
  const lum = [0.213, 0.715, 0.072]
  const lo = [r(t.background), g(t.background), b(t.background)]
  const hi = [r(t.text), g(t.text), b(t.text)]
  const m = new Array<number>(20).fill(0)
  for (let ch = 0; ch < 3; ch++) {
    const k = (hi[ch] - lo[ch]) / 255
    for (let i = 0; i < 3; i++) m[ch * 5 + i] = k * ((i === ch ? 1 : 0) - 2 * lum[i])
    m[ch * 5 + 4] = lo[ch] + 255 * k
  }
  m[18] = 1
  return m
}

/** Aplica a matriz a uma cor (testes e prévias). Valores em 0..255, como no Android. */
export function applyMatrix(m: number[], color: number): number {
  const c = [r(color), g(color), b(color), 255]
  const ch = (row: number) => clamp(Math.round(m[row * 5] * c[0] + m[row * 5 + 1] * c[1] + m[row * 5 + 2] * c[2] + m[row * 5 + 3] * c[3] + m[row * 5 + 4]), 0, 255)
  return (ch(0) << 16) | (ch(1) << 8) | ch(2)
}

/** A mesma matriz no formato do SVG (deslocamento em 0..1 em vez de 0..255). */
export function svgMatrixValues(m: number[]): string {
  return m.map((v, i) => (i % 5 === 4 ? v / 255 : v)).map((v) => +v.toFixed(5)).join(' ')
}

/** Recuo de cada lado do texto: a margem ou o que sobra para a linha ter ~N caracteres. */
export function sidePadding(viewWidth: number, textSizePx: number, marginPx: number, lineWidth: LineWidth): number {
  const chars = LINE_WIDTHS[lineWidth].chars
  if (chars === 0 || viewWidth <= 0) return marginPx
  const maxLine = Math.round(chars * AVERAGE_CHAR_EM * textSizePx)
  return Math.max(marginPx, Math.trunc((viewWidth - maxLine) / 2))
}

/** CSS de leitura do EPUB/MOBI com tema e tipografia. `fontFaces` declara a Atkinson. */
export function epubCss(c: Comfort, fontFaces: string): string {
  const t = THEMES[c.theme]
  const align = c.justify ? 'justify' : 'left'
  const chars = LINE_WIDTHS[c.lineWidth].chars
  const maxWidth = chars > 0 ? `max-width: ${chars * AVERAGE_CHAR_EM}em !important;` : ''
  const fontCss = FONTS[c.font].css
  const family = fontCss ? `body, p, div, span, li, td, blockquote, h1, h2, h3, h4, h5, h6 { font-family: ${fontCss} !important; }` : ''
  return `
${c.font === 'ATKINSON' ? fontFaces : ''}
html, body { background: ${hex(t.background)} !important; color: ${hex(t.text)} !important; }
body { margin: 0 auto !important; padding: 20px ${MARGINS[c.margin].percent}% 48px !important; ${maxWidth}
       line-height: ${c.lineSpacing / 100} !important; letter-spacing: ${c.letterSpacing / 100}em !important; word-wrap: break-word; }
p, li, blockquote, dd, dt, td, span, div { line-height: inherit !important; letter-spacing: inherit !important; }
p, li, blockquote { text-align: ${align} !important; }
${family}
* { color: inherit; background-color: transparent; }
a { color: ${hex(t.link)} !important; }
html, body, p, span, div, li { -webkit-user-select: text !important; user-select: text !important; }
.alx-term { text-decoration: underline dotted ${hex(t.link)} !important; text-decoration-thickness: 2px !important; text-underline-offset: 3px; }
img, svg, video { max-width: 100% !important; height: auto !important; }
pre, table { max-width: 100%; overflow-x: auto; }
`.trim()
}

/** Lê um conforto salvo, ignorando campos desconhecidos ou inválidos (como o ComfortStore). */
export function sanitizeComfort(raw: unknown): Comfort {
  const value = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const pick = <K extends keyof Comfort>(key: K, valid: (v: unknown) => boolean): Comfort[K] =>
    valid(value[key]) ? (value[key] as Comfort[K]) : DEFAULT_COMFORT[key]
  const inEnum = (e: object) => (v: unknown) => typeof v === 'string' && v in e
  const num = (v: unknown) => typeof v === 'number' && Number.isFinite(v)
  const bool = (v: unknown) => typeof v === 'boolean'
  return {
    theme: pick('theme', inEnum(THEMES)),
    systemBrightness: pick('systemBrightness', bool),
    brightness: pick('brightness', num),
    warmth: pick('warmth', num),
    overlayOn: pick('overlayOn', bool),
    overlayColor: pick('overlayColor', inEnum(TINTS)),
    overlayOpacity: pick('overlayOpacity', num),
    lineSpacing: pick('lineSpacing', num),
    letterSpacing: pick('letterSpacing', num),
    margin: pick('margin', inEnum(MARGINS)),
    lineWidth: pick('lineWidth', inEnum(LINE_WIDTHS)),
    justify: pick('justify', bool),
    font: pick('font', inEnum(FONTS)),
    rulerHeight: pick('rulerHeight', inEnum(RULER_HEIGHTS)),
    rulerColor: pick('rulerColor', inEnum(TINTS)),
    rulerOpacity: pick('rulerOpacity', num),
    rulerOutside: pick('rulerOutside', inEnum(RULER_OUTSIDE))
  }
}
