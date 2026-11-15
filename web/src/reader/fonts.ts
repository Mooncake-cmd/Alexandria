/** @font-face da Atkinson Hyperlegible (SIL OFL 1.1), servida pelo próprio site. */
export function atkinsonFontFaces(): string {
  const base = `${location.origin}${import.meta.env.BASE_URL}fonts/`
  const face = (file: string, weight: number, style: string) =>
    `@font-face { font-family: 'Atkinson Hyperlegible'; src: url('${base}atkinson-${file}.woff2') format('woff2'); font-weight: ${weight}; font-style: ${style}; }`
  return [face('400-normal', 400, 'normal'), face('400-italic', 400, 'italic'), face('700-normal', 700, 'normal'), face('700-italic', 700, 'italic')].join('\n')
}
