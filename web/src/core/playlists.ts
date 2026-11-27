/**
 * Playlists pelos tópicos do grupo (as mesmas regras no app): cada tópico é um curso (aba Cursos) ou
 * uma coleção (aba Filmes). Vídeos fora de tópicos (no "Geral") ficam soltos.
 */
import type { CloudVideo } from '../cloud/telegramCloud'
import { watched } from './videoProgress'

export interface Topic {
  id: number
  title: string
}

export interface Playlist {
  topic: Topic
  videos: CloudVideo[]
}

/** O tópico "Geral" de todo grupo com tópicos (as mensagens dele não indicam tópico). */
export const GENERAL_TOPIC = 1

/** Tem número no nome (sem contar a extensão: o 4 de .mp4 não vale). */
const hasNumber = (name: string) => /\d/.test(name.replace(/\.[a-z0-9]{2,4}$/i, ''))

/**
 * Ordem das aulas: se todos os nomes têm número ("Aula 01", "02 - Introdução"), pela ordem dos números
 * (2 antes de 10); senão, pela ordem de envio (o mais antigo primeiro).
 */
export function lessonOrder(videos: CloudVideo[]): CloudVideo[] {
  const byName = videos.length > 0 && videos.every((v) => hasNumber(v.name))
  return [...videos].sort((a, b) =>
    byName ? a.name.localeCompare(b.name, 'pt-BR', { numeric: true, sensitivity: 'base' }) || a.date - b.date : a.date - b.date || a.messageId - b.messageId
  )
}

/** Separa os vídeos pelos tópicos (na ordem em que os tópicos foram criados) e os soltos. */
export function playlists(videos: CloudVideo[], topics: Topic[]): { lists: Playlist[]; loose: CloudVideo[] } {
  const known = new Map(topics.filter((t) => t.id !== GENERAL_TOPIC).map((t) => [t.id, t]))
  const groups = new Map<number, CloudVideo[]>()
  const loose: CloudVideo[] = []
  for (const v of videos) {
    if (v.topic && known.has(v.topic)) groups.set(v.topic, [...(groups.get(v.topic) ?? []), v])
    else loose.push(v)
  }
  const lists = [...known.values()].sort((a, b) => a.id - b.id).map((topic) => ({ topic, videos: lessonOrder(groups.get(topic.id) ?? []) }))
  return { lists, loose }
}

/** A próxima aula da playlist depois desta (null se for a última ou se o vídeo não está numa). */
export function nextInPlaylist(list: CloudVideo[], messageId: number): CloudVideo | null {
  const i = list.findIndex((v) => v.messageId === messageId)
  return i >= 0 && i + 1 < list.length ? list[i + 1] : null
}

/** Quantas aulas foram assistidas e qual continuar (a primeira não assistida). */
export function courseProgress(list: CloudVideo[], stoppedAt: (v: CloudVideo) => { position: number; duration: number } | null) {
  let done = 0
  let next: CloudVideo | null = null
  for (const v of list) {
    const p = stoppedAt(v)
    if (p && watched(p.position, p.duration || v.duration)) done++
    else if (!next) next = v
  }
  return { done, total: list.length, next, started: list.some((v) => stoppedAt(v)) }
}
