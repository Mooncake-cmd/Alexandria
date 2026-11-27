import { useEffect, useState } from 'preact/hooks'
import type { AppTab } from '../core/tabs'
import { typeLabel } from '../core/tabs'
import { cloudTopics, cloudVideosCached, createCloudTopic, enableCloudTopics, renameCloudTopic, telegramSettings, videoPositions } from '../cloud/cloud'
import { formatDuration, type CloudVideo } from '../cloud/telegramCloud'
import { groupOptions, tabOptions } from './tabActions'
import { bookKey, type SyncEntry } from '../storage/sync'
import { clock, resumeAt, watched } from '../core/videoProgress'
import { courseProgress, playlists, type Topic } from '../core/playlists'
import { confirmDialog, menuDialog, promptDialog, toast } from '../ui/dialogs'

const size = (bytes: number) => (bytes >= 1e9 ? `${(bytes / 1e9).toFixed(1).replace('.', ',')} GB` : `${Math.max(1, Math.round(bytes / 1e6))} MB`)

/**
 * Aba de filmes ou cursos: os vídeos do grupo da aba, separados pelos tópicos do grupo (cursos ou
 * coleções), com "continuar assistindo" e o progresso de cada curso.
 */
export function VideoTab({ tab }: { tab: AppTab }) {
  const [state, setState] = useState<'loading' | 'login' | 'nogroup' | 'error' | 'ok'>('loading')
  const [videos, setVideos] = useState<CloudVideo[]>([])
  const [topics, setTopics] = useState<{ forum: boolean; topics: Topic[] }>({ forum: false, topics: [] })
  const [error, setError] = useState('')
  const [open, setOpen] = useState<number | null>(null)
  /** Onde cada vídeo parou (pela chave nome+tamanho). */
  const [progress, setProgress] = useState<Map<number, SyncEntry>>(new Map())
  const courses = tab.type === 'cursos'

  const load = async () => {
    if (!(await telegramSettings())?.session) return setState('login')
    if (!tab.chat) return setState('nogroup')
    setState('loading')
    try {
      const [list, found] = await Promise.all([cloudVideosCached(tab.id), cloudTopics(tab.id).catch(() => ({ forum: false, topics: [] }))])
      setVideos(list)
      setTopics(found)
      setState('ok')
      const positions = await videoPositions(tab.id)
      const map = new Map<number, SyncEntry>()
      for (const v of list) {
        const entry = positions[await bookKey(v.keyName, v.size)]
        if (entry) map.set(v.messageId, entry)
      }
      setProgress(map)
    } catch (e) {
      const { telegramError } = await import('../cloud/gramApi')
      setError(telegramError(e))
      setState('error')
    }
  }
  useEffect(() => {
    load()
  }, [tab.id, tab.chat?.id])

  const explain = async (e: unknown) => {
    const { telegramError } = await import('../cloud/gramApi')
    toast(e instanceof Error && !(e as { errorMessage?: string }).errorMessage ? e.message : telegramError(e))
  }

  /** "＋ Novo curso" / "＋ Nova coleção": liga os tópicos no grupo, se preciso, e cria um. */
  const newTopic = async () => {
    try {
      if (!topics.forum) {
        const ok = await confirmDialog(
          'Ativar tópicos no grupo?',
          `Cada ${courses ? 'curso' : 'coleção'} é um tópico do grupo "${tab.chat?.title}". Quem envia pelo Telegram escolhe o tópico na hora de mandar o vídeo. Só o dono ou um administrador do grupo pode ativar.`,
          'Ativar'
        )
        if (!ok) return
        await enableCloudTopics(tab.id)
      }
      const title = (await promptDialog(courses ? 'Novo curso' : 'Nova coleção', 'Nome (vira um tópico do grupo)', ''))?.trim()
      if (!title) return
      await createCloudTopic(tab.id, title)
      toast(`"${title}" criado. Envie os vídeos dentro desse tópico no Telegram.`)
      load()
    } catch (e) {
      await explain(e)
    }
  }

  const topicMenu = async (topic: Topic) => {
    const choice = await menuDialog(topic.title, ['Renomear'])
    if (choice !== 0) return
    const title = (await promptDialog('Renomear', 'Nome', topic.title))?.trim()
    if (!title || title === topic.title) return
    try {
      await renameCloudTopic(tab.id, topic.id, title)
      load()
    } catch (e) {
      await explain(e)
    }
  }

  const stoppedAt = (v: CloudVideo) => {
    const p = progress.get(v.messageId)
    return p ? { position: p.position, duration: p.pageCount || v.duration } : null
  }
  const status = (v: CloudVideo) => {
    const p = stoppedAt(v)
    if (!p) return { label: '', fraction: 0, resume: false, done: false }
    if (watched(p.position, p.duration)) return { label: '✓ Assistido', fraction: 1, resume: false, done: true }
    const at = resumeAt(p.position, p.duration)
    return { label: at ? `Parou em ${clock(at)}` : '', fraction: p.duration ? p.position / p.duration : 0, resume: at > 0, done: false }
  }
  const row = (v: CloudVideo, number?: number) => {
    const s = status(v)
    return (
      <li key={v.messageId}>
        <a class="video-row" href={`#/video/${tab.id}/${v.messageId}`}>
          <span class="name">{s.done ? '✓' : '▶'} {number !== undefined ? `${number}. ` : ''}{v.name.replace(/\.[^.]+$/, '')}</span>
          <span class="muted small">{[s.label, formatDuration(v.duration), size(v.size), v.sender].filter(Boolean).join(' · ')}</span>
          {s.fraction > 0 && s.fraction < 1 && <span class="video-bar"><span style={{ width: `${Math.round(s.fraction * 100)}%` }} /></span>}
        </a>
      </li>
    )
  }

  const { lists, loose } = playlists(videos, topics.topics)
  const going = videos.filter((v) => status(v).resume).sort((a, b) => (progress.get(b.messageId)?.updated ?? 0) - (progress.get(a.messageId)?.updated ?? 0))

  return (
    <>
      <div class="tab-head">
        <h2>{tab.name}</h2>
        <button type="button" class="secondary" onClick={() => tabOptions(tab)} aria-label={`Opções da aba ${tab.name}`}>⋯ Opções da aba</button>
      </div>
      <p class="muted">
        Aba de {typeLabel(tab.type).toLowerCase()}{tab.chat ? <> · grupo <strong>{tab.chat.title}</strong></> : ''}.{' '}
        {courses ? 'Cada tópico do grupo é um curso, com as aulas em ordem.' : 'Os tópicos do grupo são coleções.'}
      </p>
      {state === 'loading' && <p class="muted small">Procurando os vídeos do grupo…</p>}
      {state === 'login' && <p class="muted">Entre no Telegram em <a href="#/ajustes">Ajustes</a> (Nuvem pelo Telegram) e depois escolha o grupo desta aba.</p>}
      {state === 'nogroup' && (
        <div class="sync-box">
          <p>Esta aba ainda não tem grupo. Crie um grupo privado ou entre num que já existe: tudo o que for enviado lá (por você ou por quem estiver no grupo, mesmo sem o Alexandria) aparece aqui.</p>
          <button type="button" class="primary" onClick={() => groupOptions(tab)}>Escolher o grupo da aba</button>
        </div>
      )}
      {state === 'error' && <p class="muted small">{error} <button type="button" class="link" onClick={load}>Tentar de novo</button></p>}
      {state === 'ok' && (
        <>
          {going.length > 0 && (
            <>
              <h3>Continuar assistindo</h3>
              <ul class="cloud-list video-list">{going.slice(0, 6).map((v) => row(v))}</ul>
            </>
          )}
          <div class="tab-head playlists-head">
            <h3>{courses ? 'Cursos' : 'Coleções'}</h3>
            <button type="button" class="secondary" onClick={newTopic}>＋ {courses ? 'Novo curso' : 'Nova coleção'}</button>
          </div>
          {lists.length === 0 && (
            <p class="muted small">
              {courses
                ? 'Nenhum curso ainda. Cada curso é um tópico do grupo: crie aqui ou no Telegram (com os tópicos ativados) e envie as aulas dentro dele.'
                : 'Nenhuma coleção. Os tópicos do grupo viram coleções (por exemplo, Séries, Animações).'}
            </p>
          )}
          {lists.map(({ topic, videos: list }) => {
            const p = courseProgress(list, stoppedAt)
            const expanded = open === topic.id
            return (
              <section key={topic.id} class="course">
                <div class="course-head">
                  <button type="button" class="course-title" aria-expanded={expanded} onClick={() => setOpen(expanded ? null : topic.id)}>
                    <strong>{expanded ? '▼' : '▶'} {topic.title}</strong>
                    <span class="muted small">
                      {list.length} {courses ? (list.length === 1 ? 'aula' : 'aulas') : 'vídeo(s)'}
                      {courses && list.length > 0 ? ` · ${p.done} assistida(s)` : ''}
                    </span>
                    {courses && list.length > 0 && <span class="video-bar"><span style={{ width: `${Math.round((p.done / list.length) * 100)}%` }} /></span>}
                  </button>
                  {courses && p.next && (
                    <a class="primary course-go" href={`#/video/${tab.id}/${p.next.messageId}`}>{p.started ? 'Continuar' : 'Começar'}</a>
                  )}
                  <button type="button" class="card-more" aria-label={`Opções de ${topic.title}`} onClick={() => topicMenu(topic)}>⋯</button>
                </div>
                {expanded && (
                  list.length ? <ul class="cloud-list video-list">{list.map((v, i) => row(v, courses ? i + 1 : undefined))}</ul>
                    : <p class="muted small">Nenhum vídeo neste tópico ainda. No Telegram, abra o tópico "{topic.title}" e envie os vídeos lá.</p>
                )}
              </section>
            )
          })}
          <h3>{lists.length ? 'Vídeos soltos' : 'Vídeos'}</h3>
          <p class="muted small">{loose.length ? `${loose.length} vídeo(s) fora de ${courses ? 'cursos' : 'coleções'}. Toque para assistir.` : videos.length ? 'Todos os vídeos estão em tópicos.' : 'Nenhum vídeo no grupo ainda. Envie pelo Telegram (como vídeo ou como arquivo).'}</p>
          <ul class="cloud-list video-list">{loose.map((v) => row(v))}</ul>
        </>
      )}
    </>
  )
}
