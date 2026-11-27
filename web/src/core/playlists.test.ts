import { describe, expect, it } from 'vitest'
import { courseProgress, lessonOrder, nextInPlaylist, playlists } from './playlists'
import type { CloudVideo } from '../cloud/telegramCloud'

const v = (messageId: number, name: string, date: number, topic?: number, duration = 600): CloudVideo => ({ messageId, name, keyName: name, size: 10, duration, mime: 'video/mp4', date, topic })

describe('playlists pelos tópicos', () => {
  it('aulas numeradas seguem o número (2 antes de 10); sem número, a ordem de envio', () => {
    expect(lessonOrder([v(1, 'Aula 10.mp4', 1), v(2, 'Aula 2.mp4', 2), v(3, 'aula 01 - Intro.mp4', 3)]).map((x) => x.messageId)).toEqual([3, 2, 1])
    expect(lessonOrder([v(1, 'Revisão.mp4', 30), v(2, 'Introdução.mp4', 10), v(3, 'Aula 2.mp4', 20)]).map((x) => x.messageId)).toEqual([2, 3, 1])
  })

  it('separa por tópico, na ordem de criação; "Geral" e tópicos desconhecidos ficam soltos', () => {
    const videos = [v(1, 'Filme.mp4', 1), v(2, 'Aula 1.mp4', 2, 50), v(3, 'Ep 1.mp4', 3, 20), v(4, 'Geral.mp4', 4, 1), v(5, 'X.mp4', 5, 99), v(6, 'Aula 0.mp4', 6, 50)]
    const { lists, loose } = playlists(videos, [{ id: 50, title: 'Python' }, { id: 1, title: 'General' }, { id: 20, title: 'Série' }, { id: 70, title: 'Vazio' }])
    expect(lists.map((l) => [l.topic.title, l.videos.map((x) => x.messageId)])).toEqual([['Série', [3]], ['Python', [6, 2]], ['Vazio', []]])
    expect(loose.map((x) => x.messageId)).toEqual([1, 4, 5])
  })

  it('próxima aula e progresso do curso', () => {
    const list = [v(1, 'Aula 1.mp4', 1), v(2, 'Aula 2.mp4', 2), v(3, 'Aula 3.mp4', 3)]
    expect(nextInPlaylist(list, 1)?.messageId).toBe(2)
    expect(nextInPlaylist(list, 3)).toBeNull()
    expect(nextInPlaylist(list, 9)).toBeNull()
    const stopped = new Map([[1, { position: 590, duration: 600 }], [2, { position: 120, duration: 600 }]])
    expect(courseProgress(list, (x) => stopped.get(x.messageId) ?? null)).toMatchObject({ done: 1, total: 3, started: true, next: { messageId: 2 } })
    expect(courseProgress(list, () => null)).toMatchObject({ done: 0, started: false, next: { messageId: 1 } })
  })
})
