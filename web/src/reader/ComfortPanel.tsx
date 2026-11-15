import type { ComponentChildren } from 'preact'
import {
  DEFAULT_COMFORT, FONTS, LINE_WIDTHS, MARGINS, RULER_HEIGHTS, RULER_OUTSIDE, THEMES, TINTS, hex, type Comfort
} from '../core/comfort'

/**
 * Painel "Conforto visual" (como o do app): cor da página, luz e filtro, tipografia e régua, com
 * efeito ao vivo enquanto se ajusta. Fica ao lado do livro, que continua visível.
 */
export function ComfortPanel({ comfort, ruler, typography, onChange, onRuler, onClose }: {
  comfort: Comfort
  ruler: boolean
  /** A tipografia só vale para EPUB, MOBI, DOCX e TXT. */
  typography: boolean
  onChange: (c: Comfort) => void
  onRuler: (on: boolean) => void
  onClose: () => void
}) {
  const set = <K extends keyof Comfort>(key: K, value: Comfort[K]) => onChange({ ...comfort, [key]: value })
  return (
    <aside class="comfort-panel" aria-label="Conforto visual">
      <header>
        <h3>Conforto visual</h3>
        <button type="button" class="close" aria-label="Fechar" onClick={onClose}>×</button>
      </header>

      <Section title="Cor da página">
        <div class="swatches">
          {Object.entries(THEMES).map(([key, t]) => (
            <button
              type="button"
              key={key}
              class={key === comfort.theme ? 'swatch active' : 'swatch'}
              style={{ background: hex(t.background), color: hex(t.text) }}
              aria-pressed={key === comfort.theme}
              onClick={() => set('theme', key as Comfort['theme'])}
            >
              Aa<small>{t.label}</small>
            </button>
          ))}
        </div>
      </Section>

      <Section title="Luz e filtro">
        <Toggle label="Escurecer a tela (brilho do app)" checked={!comfort.systemBrightness} onChange={(v) => set('systemBrightness', !v)} />
        {!comfort.systemBrightness && <Slider label="Brilho" min={0} max={100} value={comfort.brightness} format={(v) => `${v}%`} onChange={(v) => set('brightness', v)} />}
        <Slider label="Luz quente" min={0} max={100} value={comfort.warmth} format={(v) => (v ? `${v}%` : 'Desligada')} onChange={(v) => set('warmth', v)} />
        <Toggle label="Sobreposição colorida" checked={comfort.overlayOn} onChange={(v) => set('overlayOn', v)} />
        {comfort.overlayOn && (
          <>
            <Colors value={comfort.overlayColor} onChange={(v) => set('overlayColor', v)} />
            <Slider label="Opacidade" min={5} max={60} value={comfort.overlayOpacity} format={(v) => `${v}%`} onChange={(v) => set('overlayOpacity', v)} />
          </>
        )}
        <p class="muted small">O navegador não controla o brilho do monitor: o brilho do app escurece a página por cima.</p>
      </Section>

      {typography && (
        <Section title="Tipografia (EPUB, MOBI, DOCX e TXT)">
          <Chips label="Fonte" options={Object.entries(FONTS).map(([k, f]) => [k, f.label])} value={comfort.font} onChange={(v) => set('font', v as Comfort['font'])} />
          <Slider label="Espaço entre linhas" min={100} max={220} step={10} value={comfort.lineSpacing} format={(v) => `${(v / 100).toFixed(1).replace('.', ',')}×`} onChange={(v) => set('lineSpacing', v)} />
          <Slider label="Espaço entre letras" min={0} max={15} value={comfort.letterSpacing} format={(v) => (v ? `+${v}` : 'Normal')} onChange={(v) => set('letterSpacing', v)} />
          <Chips label="Margens" options={Object.entries(MARGINS).map(([k, m]) => [k, m.label])} value={comfort.margin} onChange={(v) => set('margin', v as Comfort['margin'])} />
          <Chips label="Largura da linha" options={Object.entries(LINE_WIDTHS).map(([k, w]) => [k, w.label])} value={comfort.lineWidth} onChange={(v) => set('lineWidth', v as Comfort['lineWidth'])} />
          <Chips label="Alinhamento" options={[['left', 'À esquerda'], ['justify', 'Justificado']]} value={comfort.justify ? 'justify' : 'left'} onChange={(v) => set('justify', v === 'justify')} />
        </Section>
      )}

      <Section title="Régua de leitura">
        <Toggle label="Mostrar a régua" checked={ruler} onChange={onRuler} />
        <Chips label="Espessura" options={Object.entries(RULER_HEIGHTS).map(([k, h]) => [k, h.label])} value={comfort.rulerHeight} onChange={(v) => set('rulerHeight', v as Comfort['rulerHeight'])} />
        <Colors value={comfort.rulerColor} onChange={(v) => set('rulerColor', v)} />
        <Slider label="Opacidade" min={10} max={60} value={comfort.rulerOpacity} format={(v) => `${v}%`} onChange={(v) => set('rulerOpacity', v)} />
        <Chips label="Fora da régua" options={Object.entries(RULER_OUTSIDE)} value={comfort.rulerOutside} onChange={(v) => set('rulerOutside', v as Comfort['rulerOutside'])} />
      </Section>

      <button type="button" class="link restore" onClick={() => onChange({ ...DEFAULT_COMFORT, lineWidth: 'LARGA' })}>Restaurar o padrão</button>
    </aside>
  )
}

function Section({ title, children }: { title: string; children: ComponentChildren }) {
  return (
    <section>
      <h4>{title}</h4>
      {children}
    </section>
  )
}

function Toggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label class="toggle">
      <span>{label}</span>
      <input type="checkbox" role="switch" checked={checked} onChange={(e) => onChange((e.target as HTMLInputElement).checked)} />
    </label>
  )
}

function Slider({ label, min, max, step = 1, value, format, onChange }: { label: string; min: number; max: number; step?: number; value: number; format: (v: number) => string; onChange: (v: number) => void }) {
  return (
    <label class="slider">
      <span>{label} <b>{format(value)}</b></span>
      <input type="range" min={min} max={max} step={step} value={value} onInput={(e) => onChange(Number((e.target as HTMLInputElement).value))} />
    </label>
  )
}

function Chips({ label, options, value, onChange }: { label: string; options: [string, string][]; value: string; onChange: (v: string) => void }) {
  return (
    <div class="chips" role="group" aria-label={label}>
      <span class="chips-label">{label}</span>
      <div>
        {options.map(([key, text]) => (
          <button type="button" key={key} class={key === value ? 'chip active' : 'chip'} aria-pressed={key === value} onClick={() => onChange(key)}>{text}</button>
        ))}
      </div>
    </div>
  )
}

function Colors({ value, onChange }: { value: keyof typeof TINTS; onChange: (v: keyof typeof TINTS) => void }) {
  return (
    <div class="colors" role="group" aria-label="Cor">
      {Object.entries(TINTS).map(([key, t]) => (
        <button
          type="button"
          key={key}
          class={key === value ? 'color active' : 'color'}
          style={{ background: hex(t.rgb) }}
          aria-label={t.label}
          aria-pressed={key === value}
          title={t.label}
          onClick={() => onChange(key as keyof typeof TINTS)}
        />
      ))}
    </div>
  )
}
