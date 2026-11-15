import { Empty } from './Empty'

export function Library() {
  return (
    <>
      <h2>Minha biblioteca</h2>
      <p class="muted">Seus documentos ficam guardados só neste navegador, no seu computador.</p>
      <button class="primary" type="button" disabled title="Chega na Etapa 2">＋ Importar documentos</button>
      <p class="label">LEITURA DISPONÍVEL</p>
      <p>PDF · EPUB · MOBI · AZW3 · DJVU · CBZ · CBR · DOCX · TXT</p>
      <h2>Lendo agora</h2>
      <Empty
        icon="▤"
        title="Sua biblioteca começa aqui"
        message="Importe um PDF, EPUB, MOBI, AZW3, DJVU, quadrinho CBZ/CBR, DOCX ou TXT para começar a ler."
      />
    </>
  )
}
