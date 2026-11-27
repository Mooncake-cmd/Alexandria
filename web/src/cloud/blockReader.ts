/**
 * Leitura de um arquivo grande do Telegram por partes, para o streaming de vídeo: o arquivo é visto
 * como blocos de 512 KB (o tamanho que o Telegram entrega por pedido). Os blocos lidos ficam numa
 * memória limitada (os mais antigos saem), pedidos repetidos do mesmo bloco esperam o mesmo download,
 * e os próximos blocos já são pedidos antes de o player precisar deles.
 */
export const BLOCK = 512 * 1024

export class BlockReader {
  private cache = new Map<number, Uint8Array>()
  private pending = new Map<number, Promise<Uint8Array>>()

  constructor(
    readonly size: number,
    private readonly fetchBlock: (index: number) => Promise<Uint8Array>,
    private readonly maxBlocks = 48,
    private readonly ahead = 3
  ) {}

  get blocks() {
    return Math.ceil(this.size / BLOCK)
  }

  private block(index: number): Promise<Uint8Array> {
    const hit = this.cache.get(index)
    if (hit) {
      // Mais recente no fim da fila (sai por último).
      this.cache.delete(index)
      this.cache.set(index, hit)
      return Promise.resolve(hit)
    }
    const waiting = this.pending.get(index)
    if (waiting) return waiting
    const work = this.fetchBlock(index).then(
      (bytes) => {
        this.pending.delete(index)
        this.cache.set(index, bytes)
        while (this.cache.size > this.maxBlocks) this.cache.delete(this.cache.keys().next().value as number)
        return bytes
      },
      (e) => {
        this.pending.delete(index)
        throw e
      }
    )
    this.pending.set(index, work)
    return work
  }

  /** Bytes de [offset] até [offset + length) (menos, no fim do arquivo). */
  async read(offset: number, length: number): Promise<Uint8Array> {
    const end = Math.min(this.size, offset + length)
    if (offset >= end) return new Uint8Array(0)
    const first = Math.floor(offset / BLOCK)
    const last = Math.floor((end - 1) / BLOCK)
    const parts = await Promise.all(Array.from({ length: last - first + 1 }, (_, i) => this.block(first + i)))
    for (let i = last + 1; i <= Math.min(this.blocks - 1, last + this.ahead); i++) this.block(i).catch(() => {})
    const out = new Uint8Array(end - offset)
    let written = 0
    parts.forEach((bytes, i) => {
      const blockStart = (first + i) * BLOCK
      const from = Math.max(0, offset - blockStart)
      const to = Math.min(bytes.length, end - blockStart)
      if (to > from) {
        out.set(bytes.subarray(from, to), written)
        written += to - from
      }
    })
    return written === out.length ? out : out.subarray(0, written)
  }
}
