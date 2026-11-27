import { useEffect, useState } from 'preact/hooks'
import type { AppTab } from '../core/tabs'
import { typeLabel } from '../core/tabs'
import { cloudVideos, telegramSettings } from '../cloud/cloud'
import { formatDuration, type CloudVideo } from '../cloud/telegramCloud'
import { groupOptions, tabOptions } from './tabActions'

const size = (bytes: number) => (bytes >= 1e9 ? `${(bytes / 1e9).toFixed(1).replace('.', ',')} GB` : `${Math.max(1, Math.round(bytes / 1e6))} MB`)

/**
 * Aba de filmes ou cursos: os vídeos do grupo da aba. Nesta etapa só a lista (o player com streaming
 * e as playlists por tópicos vêm nas próximas).
 */
export function VideoTab({ tab }: { tab: AppTab }) {
  const [state, setState] = useState<'loading' | 'login' | 'nogroup' | 'error' | 'ok'>('loading')
  const [videos, setVideos] = useState<CloudVideo[]>([])
  const [error, setError] = useState('')
  const load = async () => {
    if (!(await telegramSettings())?.session) return setState('login')
    if (!tab.chat) return setState('nogroup')
    setState('loading')
    try {
      setVideos(await cloudVideos(tab.id))
      setState('ok')
    } catch (e) {
      const { telegramError } = await import('../cloud/gramApi')
      setError(telegramError(e))
      setState('error')
    }
  }
  useEffect(() => {
    load()
  }, [tab.id, tab.chat?.id])
  return (
    <>
      <div class="tab-head">
        <h2>{tab.name}</h2>
        <button type="button" class="secondary" onClick={() => tabOptions(tab)} aria-label={`Opções da aba ${tab.name}`}>⋯ Opções da aba</button>
      </div>
      <p class="muted">
        Aba de {typeLabel(tab.type).toLowerCase()}{tab.chat ? <> · grupo <strong>{tab.chat.title}</strong></> : ''}.{' '}
        {tab.type === 'cursos' ? 'Cada tópico do grupo será um curso, com as aulas em ordem.' : 'Os tópicos do grupo serão coleções.'}
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
          <p class="muted small">{videos.length ? `${videos.length} vídeo(s) no grupo. Assistir por aqui, com streaming, chega na próxima atualização.` : 'Nenhum vídeo no grupo ainda. Envie pelo Telegram (como vídeo ou como arquivo).'}</p>
          <ul class="cloud-list video-list">
            {videos.map((v) => (
              <li key={v.messageId}>
                <span class="name">▶ {v.name.replace(/\.[^.]+$/, '')}</span>
                <span class="muted small">{[formatDuration(v.duration), size(v.size), v.sender].filter(Boolean).join(' · ')}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  )
}
