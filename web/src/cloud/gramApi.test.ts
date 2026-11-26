import { describe, expect, it } from 'vitest'
import { Api, type TelegramClient } from 'telegram'
import { uploadFile } from 'telegram/client/uploads'
import { gramApi } from './gramApi'

/**
 * Envio de arquivos grandes (> 20 MB) pela GramJS no navegador: usa a função de envio de verdade, com
 * a conexão trocada por uma falsa que só conta os pedaços.
 */
describe('gramApi.upload', () => {
  function fakeClient() {
    const parts: number[] = []
    let sent: { file: unknown; caption?: string; forceDocument?: boolean } | null = null
    const client = {
      session: { dcId: 1 },
      async getSender() {
        return {
          isConnected: () => true,
          async send(request: { filePart: number }) {
            parts.push(request.filePart)
            return true
          }
        }
      },
      uploadFile(params: Parameters<TelegramClient['uploadFile']>[0]) {
        return uploadFile(client as unknown as TelegramClient, params)
      },
      async sendFile(_peer: string, params: { file: unknown; caption?: string; forceDocument?: boolean }) {
        sent = params
        return new Api.Message({ id: 7, message: params.caption ?? '', date: 1_700_000_000, peerId: new Api.PeerUser({ userId: 1 as never }) })
      }
    }
    return { client, parts, sent: () => sent }
  }

  it('envia da memória um livro de 25 MB, com o nome do arquivo', async () => {
    const { client, parts, sent } = fakeClient()
    const bytes = new Uint8Array(25 * 1024 * 1024)
    const progress: number[] = []
    const message = await gramApi(client as unknown as TelegramClient).upload('X-Men.cbr', bytes, 'legenda', (p) => progress.push(p))
    expect(message.id).toBe(7)
    expect(parts.length).toBeGreaterThan(1)
    const file = sent()!.file as Api.InputFileBig
    expect(file).toBeInstanceOf(Api.InputFileBig)
    expect(file.name).toBe('X-Men.cbr')
    expect(file.parts).toBe(parts.length)
    expect(sent()!.forceDocument).toBe(true)
    expect(progress.at(-1)).toBeCloseTo(1)
  })

  it('e um pequeno também', async () => {
    const { client, sent } = fakeClient()
    await gramApi(client as unknown as TelegramClient).upload('Conto.txt', new Uint8Array(1000), 'legenda')
    expect(sent()!.file).toBeInstanceOf(Api.InputFile)
  })
})
