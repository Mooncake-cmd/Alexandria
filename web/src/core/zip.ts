import { inflateSync } from 'fflate'

/**
 * Leitor de ZIP sob demanda (EPUB, DOCX, CBZ): lê só o índice do arquivo e descompacta cada
 * entrada quando ela é pedida, sem carregar tudo na memória. Funciona com File/Blob do navegador.
 */
export interface ZipEntry {
  name: string
  method: number
  compressedSize: number
  size: number
  offset: number
}

const decoder = new TextDecoder('utf-8')
const cp437 = new TextDecoder('windows-1252') // nomes antigos sem UTF-8 (aproximação do CP437)

export class ZipArchive {
  private constructor(
    private readonly blob: Blob,
    readonly entries: Map<string, ZipEntry>
  ) {}

  static async open(blob: Blob): Promise<ZipArchive> {
    const tailSize = Math.min(blob.size, 65_557)
    const tail = new Uint8Array(await blob.slice(blob.size - tailSize).arrayBuffer())
    let eocd = -1
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 0x05 && tail[i + 3] === 0x06) {
        eocd = i
        break
      }
    }
    if (eocd < 0) throw new Error('Arquivo ZIP inválido')
    const view = new DataView(tail.buffer, tail.byteOffset + eocd)
    const count = view.getUint16(10, true)
    const dirSize = view.getUint32(12, true)
    const dirOffset = view.getUint32(16, true)
    const dir = new Uint8Array(await blob.slice(dirOffset, dirOffset + dirSize).arrayBuffer())
    const d = new DataView(dir.buffer, dir.byteOffset, dir.byteLength)
    const entries = new Map<string, ZipEntry>()
    let p = 0
    for (let k = 0; k < count && p + 46 <= dir.length; k++) {
      if (d.getUint32(p, true) !== 0x02014b50) break
      const flags = d.getUint16(p + 8, true)
      const method = d.getUint16(p + 10, true)
      const compressedSize = d.getUint32(p + 20, true)
      const size = d.getUint32(p + 24, true)
      const nameLength = d.getUint16(p + 28, true)
      const extraLength = d.getUint16(p + 30, true)
      const commentLength = d.getUint16(p + 32, true)
      const offset = d.getUint32(p + 42, true)
      const rawName = dir.subarray(p + 46, p + 46 + nameLength)
      const name = (flags & 0x800 ? decoder : utf8OrLegacy(rawName)).decode(rawName)
      entries.set(name, { name, method, compressedSize, size, offset })
      p += 46 + nameLength + extraLength + commentLength
    }
    return new ZipArchive(blob, entries)
  }

  has(name: string) {
    return this.entries.has(name)
  }

  names(): string[] {
    return [...this.entries.keys()]
  }

  async bytes(name: string): Promise<Uint8Array> {
    const entry = this.entries.get(name)
    if (!entry) throw new Error(`Faltando ${name}`)
    const header = new DataView(await this.blob.slice(entry.offset, entry.offset + 30).arrayBuffer())
    if (header.getUint32(0, true) !== 0x04034b50) throw new Error(`Entrada danificada: ${name}`)
    const start = entry.offset + 30 + header.getUint16(26, true) + header.getUint16(28, true)
    const data = new Uint8Array(await this.blob.slice(start, start + entry.compressedSize).arrayBuffer())
    if (entry.method === 0) return data
    if (entry.method === 8) return inflateSync(data, { out: new Uint8Array(entry.size) })
    throw new Error(`Compressão não suportada (${entry.method}) em ${name}`)
  }

  async text(name: string): Promise<string> {
    return decoder.decode(await this.bytes(name))
  }
}

function utf8OrLegacy(raw: Uint8Array): TextDecoder {
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(raw)
    return decoder
  } catch {
    return cp437
  }
}
