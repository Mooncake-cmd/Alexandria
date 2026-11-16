/** Atalhos de teclado do site, mostrados com "?" (e em Ajustes). */
export interface ShortcutGroup {
  title: string
  items: [keys: string, what: string][]
}

export const SHORTCUTS: ShortcutGroup[] = [
  {
    title: 'Em qualquer tela',
    items: [
      ['?', 'Mostrar esta lista de atalhos'],
      ['Esc', 'Fechar janela ou painel'],
      ['Tab / Shift + Tab', 'Ir de um botão a outro']
    ]
  },
  {
    title: 'Biblioteca',
    items: [
      ['Ctrl + O', 'Importar documentos'],
      ['Shift + F10', 'Opções do livro em foco (como o clique direito)']
    ]
  },
  {
    title: 'Leitura',
    items: [
      ['→  ou  Page Down', 'Próxima página'],
      ['←  ou  Page Up', 'Página anterior'],
      ['Espaço / Shift + Espaço', 'Próxima / anterior (modo páginas)'],
      ['↓ / ↑', 'Próxima / anterior (modo páginas); rolar (rolagem contínua)'],
      ['Ctrl + F', 'Buscar no livro'],
      ['Ctrl + +  /  Ctrl + −', 'Letra maior / menor (textos); zoom (PDF, DJVU, quadrinhos)'],
      ['Ctrl + roda do mouse', 'O mesmo que Ctrl + + / −'],
      ['B', 'Marcar ou desmarcar a página'],
      ['M', 'Lista de marcadores'],
      ['N', 'Anotações e glossário'],
      ['S', 'Sumário (EPUB, MOBI)'],
      ['G', 'Ir para a página (PDF, DJVU, quadrinhos)'],
      ['F', 'Tela cheia'],
      ['Esc', 'Mostrar os controles (tela limpa)'],
      ['Alt + ↑ / ↓', 'Mover a régua de leitura']
    ]
  }
]

/** A tecla não deve virar atalho quando se está digitando. */
export function typing(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  return !!el?.closest?.('input, textarea, select, [contenteditable]:not([contenteditable="false"])')
}
