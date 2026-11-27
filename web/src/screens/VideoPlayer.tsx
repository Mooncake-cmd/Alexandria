import { useEffect, useRef, useState } from 'preact/hooks'
import { cachedTopics, cachedVideos, openVideo, pushVideoPosition, videoPositions } from '../cloud/cloud'
import { lessonOrder, nextInPlaylist, GENERAL_TOPIC } from '../core/playlists'
import { tabById } from '../storage/tabs'
import type { CloudVideo } from '../cloud/telegramCloud'
import { closeStream, streamingReady, streamUrl } from '../cloud/streamHost'
import { bookKey } from '../storage/sync'
import { clock, resumeAt, watched } from '../core/videoProgress'
import { DEVICE } from '../storage/sync'
import type { VideoSource } from '../cloud/gramApi'

/**
 * Player das abas de filmes e cursos: o vídeo toca enquanto é baixado do grupo (streaming), e o
 * ponto onde parou vai para o grupo da aba, para continuar no celular ou em outro computador.
 */
export function VideoPlayer({ tabId, messageId }: { tabId: string; messageId: number }) {
  const [source, setSource] = useState<VideoSource | null>(null)
  const [src, setSrc] = useState('')
  const [error, setError] = useState('')
  const [snack, setSnack] = useState<{ at: number } | null>(null)
  /** Curso deste vídeo (as aulas em ordem) e a contagem para a próxima aula. */
  const [course, setCourse] = useState<{ title: string; list: CloudVideo[]; auto: boolean } | null>(null)
  const [countdown, setCountdown] = useState<{ next: CloudVideo; seconds: number } | null>(null)
  const video = useRef<HTMLVideoElement>(null)
  const saved = useRef({ last: -1, resumed: false })

  useEffect(() => {
    let alive = true
    let opened: VideoSource | null = null
    ;(async () => {
      try {
        if (!streamingReady()) {
          setError('Para assistir, recarregue esta página uma vez (o navegador precisa ativar o player do Alexandria).')
          return
        }
        opened = await openVideo(tabId, messageId)
        if (!alive) return
        setSource(opened)
        setSrc(streamUrl(opened, `${tabId}-${messageId}`))
      } catch (e) {
        const { telegramError } = await import('../cloud/gramApi')
        if (alive) setError(e instanceof Error && !(e as { errorMessage?: string }).errorMessage ? e.message : telegramError(e))
      }
    })()
    return () => {
      alive = false
      save(true)
      if (opened) closeStream(opened, `${tabId}-${messageId}`)
    }
  }, [tabId, messageId])

  useEffect(() => {
    ;(async () => {
      const list = cachedVideos(tabId)
      const me = list?.find((v) => v.messageId === messageId)
      if (!list || !me?.topic || me.topic === GENERAL_TOPIC) return
      const topic = cachedTopics(tabId).find((t) => t.id === me.topic)
      if (!topic) return
      const tab = await tabById(tabId)
      setCourse({ title: topic.title, list: lessonOrder(list.filter((v) => v.topic === me.topic)), auto: tab.type === 'cursos' })
    })()
  }, [tabId, messageId])

  // Contagem para a próxima aula (só nos cursos): 5 s, com "Cancelar".
  useEffect(() => {
    if (!countdown) return
    if (countdown.seconds <= 0) {
      location.hash = `#/video/${tabId}/${countdown.next.messageId}`
      return
    }
    const timer = setTimeout(() => setCountdown({ ...countdown, seconds: countdown.seconds - 1 }), 1000)
    return () => clearTimeout(timer)
  }, [countdown])

  const onEnded = () => {
    save(true)
    const next = course ? nextInPlaylist(course.list, messageId) : null
    if (next && course?.auto) setCountdown({ next, seconds: 5 })
  }

  /** Grava onde parou (a cada 15 s tocando, ao pausar, ao sair e no fim). */
  const save = (force = false) => {
    const v = video.current
    if (!v || !source || !v.duration || !isFinite(v.duration)) return
    const at = Math.floor(v.currentTime)
    if (!force && Math.abs(at - saved.current.last) < 15) return
    if (at === saved.current.last) return
    saved.current.last = at
    pushVideoPosition(tabId, source.keyName, source.size, {
      format: 'video', position: at, exact: Math.round(v.currentTime * 1000), pageCount: Math.round(v.duration), updated: Date.now(), device: DEVICE
    })
  }

  const onMetadata = async () => {
    const v = video.current
    if (!v || !source || saved.current.resumed) return
    saved.current.resumed = true
    const entry = (await videoPositions(tabId))[await bookKey(source.keyName, source.size)]
    const at = entry ? resumeAt(entry.position, v.duration) : 0
    if (at > 0) {
      v.currentTime = at
      saved.current.last = at
      setSnack({ at })
      setTimeout(() => setSnack(null), 8000)
    }
  }

  const onError = () => {
    const kind = source && /mkv|matroska/i.test(source.mime + source.name) ? 'MKV' : source?.mime.replace('video/', '').toUpperCase() || 'este'
    setError(`O navegador não conseguiu tocar este vídeo (formato ${kind}). Os navegadores tocam bem MP4 com vídeo H.264 e áudio AAC; MKV com áudio AC3/DTS ou vídeo HEVC costuma falhar no computador, mas funcionar no app do celular.`)
  }

  return (
    <div class="video-page">
      <div class="video-top">
        <button type="button" class="secondary" onClick={() => history.length > 1 ? history.back() : (location.hash = `#/aba/${tabId}`)}>← Voltar</button>
        <div class="video-titles">
          <h2>{source ? source.name.replace(/\.[^.]+$/, '') : 'Vídeo'}</h2>
          {course && (
            <p class="muted small">
              {course.title} · aula {course.list.findIndex((v) => v.messageId === messageId) + 1} de {course.list.length}
              {(() => {
                const next = nextInPlaylist(course.list, messageId)
                return next ? <> · <a class="link" href={`#/video/${tabId}/${next.messageId}`}>Próxima aula ›</a></> : null
              })()}
            </p>
          )}
        </div>
      </div>
      {error ? (
        <p class="sync-error">{error}</p>
      ) : (
        <div class="video-frame">
          {src ? (
            <video
              ref={video}
              src={src}
              controls
              autoPlay
              playsInline
              preload="metadata"
              onLoadedMetadata={onMetadata}
              onTimeUpdate={() => save()}
              onPause={() => save(true)}
              onEnded={onEnded}
              onError={onError}
            />
          ) : (
            <p class="muted">Abrindo o vídeo…</p>
          )}
          {countdown && (
            <div class="next-overlay" role="status">
              <p>Próxima aula em {countdown.seconds} s</p>
              <strong>{countdown.next.name.replace(/\.[^.]+$/, '')}</strong>
              <div class="button-row">
                <a class="primary" href={`#/video/${tabId}/${countdown.next.messageId}`}>Assistir agora</a>
                <button type="button" class="secondary" onClick={() => setCountdown(null)}>Cancelar</button>
              </div>
            </div>
          )}
          {snack && (
            <div class="snack" role="status">
              Continuando de {clock(snack.at)}{' '}
              <button type="button" class="link" onClick={() => {
                if (video.current) video.current.currentTime = 0
                setSnack(null)
              }}>Voltar ao início</button>
            </div>
          )}
        </div>
      )}
      {source && video.current && watched(video.current.currentTime, video.current.duration) && <p class="muted small">✓ Assistido</p>}
    </div>
  )
}
