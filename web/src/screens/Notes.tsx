import { Empty } from './Empty'

export function Notes() {
  return (
    <>
      <h2>Notas</h2>
      <Empty icon="✎" title="Nenhuma anotação ainda" message="Selecione um trecho durante a leitura e toque em Anotar. Seus glossários também aparecem aqui." />
    </>
  )
}
