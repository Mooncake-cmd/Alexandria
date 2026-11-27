import { useEffect, useRef, useState } from 'preact/hooks'
import { exportBackup, importBackup, requestPersistence, storageInfo } from '../storage/backup'
import { confirmDialog, shortcutsDialog, toast } from '../ui/dialogs'
import { connectSync, disconnectSync, SyncError, syncSettings, type SyncSettings } from '../storage/sync'
import { saveTelegram, setSyncProvider, syncProvider, telegramSettings, type SyncProvider, type TelegramSettings } from '../cloud/cloud'
import { setPref } from '../storage/library'

const size = (bytes: number) =>
  bytes >= 1e9 ? `${(bytes / 1e9).toFixed(1).replace('.', ',')} GB` : bytes >= 1e6 ? `${(bytes / 1e6).toFixed(1).replace('.', ',')} MB` : `${Math.round(bytes / 1e3)} KB`

const LICENSES: [string, string][] = [
  ['Alexandria — GNU AGPL 3.0 ou posterior', 'AGPL-3.0.txt'],
  ['pdf.js (Mozilla) — Apache 2.0', 'pdfjs-Apache-2.0.txt'],
  ['DjVu.js — GNU GPL 2.0 ou posterior', 'djvujs-GPL-2.0-or-later.txt'],
  ['foliate-js (MOBI/AZW3) — MIT', 'foliate-js-MIT.txt'],
  ['libarchive.js (CBR/RAR) — MIT', 'libarchive.js-MIT.txt'],
  ['fflate (ZIP) — MIT', 'fflate-MIT.txt'],
  ['Preact — MIT', 'preact-MIT.txt'],
  ['GramJS (nuvem pelo Telegram) — MIT', 'gramjs-MIT.txt'],
  ['Fonte Atkinson Hyperlegible — SIL OFL 1.1', 'OFL-1.1-Atkinson.txt']
]

export function Settings() {
  const [info, setInfo] = useState<{ usage: number; quota: number; persisted: boolean } | null>(null)
  const [withBooks, setWithBooks] = useState(true)
  const [busy, setBusy] = useState(false)
  const restore = useRef<HTMLInputElement>(null)
  const refresh = () => storageInfo().then(setInfo).catch(() => setInfo(null))
  useEffect(() => {
    refresh()
  }, [])

  const doExport = async () => {
    setBusy(true)
    try {
      const blob = await exportBackup(withBooks)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      const date = new Date().toISOString().slice(0, 10)
      a.href = url
      a.download = `alexandria-backup-${date}.zip`
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
      toast('Backup gerado. Guarde o arquivo num lugar seguro.')
    } catch (e) {
      console.error(e)
      toast('Não foi possível gerar o backup.')
    } finally {
      setBusy(false)
    }
  }

  const doImport = async (file: File) => {
    const ok = await confirmDialog(
      'Restaurar backup?',
      'Tudo o que está guardado neste navegador (livros, anotações, glossário, tempo de leitura) será substituído pelo conteúdo do backup.',
      'Restaurar',
      true
    )
    if (!ok) return
    setBusy(true)
    try {
      const summary = await importBackup(file)
      toast(`Backup restaurado: ${summary.books} livro(s), ${summary.notes} anotação(ões).`)
      refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Não foi possível restaurar o backup.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <h2>Ajustes</h2>
      <dl class="facts">
        <dt>Versão</dt>
        <dd>{__APP_VERSION__} (web)</dd>
        <dt>Seus dados</dt>
        <dd>Ficam só neste navegador, neste computador. Nada vai para a internet, a não ser que você ligue a nuvem ou a sincronização (abaixo).</dd>
        <dt>Espaço usado</dt>
        <dd>{info ? `${size(info.usage)}${info.quota ? ` de ${size(info.quota)} disponíveis para o site` : ''}` : '—'}</dd>
        <dt>Proteção dos dados</dt>
        <dd>
          {info?.persisted ? (
            'O navegador não vai apagar os dados do site sozinho.'
          ) : (
            <>
              O navegador pode apagar os dados do site se faltar espaço no disco.{' '}
              <button
                type="button"
                class="link"
                onClick={async () => {
                  const granted = await requestPersistence()
                  toast(granted ? 'Pronto: os dados ficam guardados.' : 'O navegador não permitiu agora. Instalar o site (ícone na barra de endereço) costuma liberar.')
                  refresh()
                }}
              >
                Pedir para manter
              </button>
            </>
          )}
        </dd>
        <dt>Licença</dt>
        <dd>Software livre sob a GNU AGPL 3.0 ou posterior.</dd>
        <dt>Teclado</dt>
        <dd>
          <button type="button" class="link-button" onClick={() => shortcutsDialog()}>Ver atalhos de teclado</button> (ou aperte ? em qualquer tela)
        </dd>
        <dt>Código-fonte</dt>
        <dd>
          <a href="https://github.com/Mooncake-cmd/Alexandria" rel="noopener">github.com/Mooncake-cmd/Alexandria</a>
        </dd>
      </dl>

      <h2>Licenças de código aberto</h2>
      <ul class="plain-list licenses">
        {LICENSES.map(([name, file]) => (
          <li key={file}><a href={`${import.meta.env.BASE_URL}licenses/${file}`} target="_blank" rel="noopener">{name}</a></li>
        ))}
      </ul>

      <TelegramSection />
      <SyncSection />

      <h2>Backup</h2>
      <p class="muted">Limpar os dados do navegador apaga a biblioteca. Faça backups de vez em quando.</p>
      <div class="panel">
        <label class="check">
          <input type="checkbox" checked={withBooks} onChange={(e) => setWithBooks((e.target as HTMLInputElement).checked)} />
          Incluir os arquivos dos livros (o backup fica maior)
        </label>
        <div class="row">
          <button type="button" class="primary" disabled={busy} onClick={doExport}>Fazer backup</button>
          <button type="button" class="secondary" disabled={busy} onClick={() => restore.current?.click()}>Restaurar backup…</button>
        </div>
        <input
          ref={restore}
          type="file"
          accept=".zip,application/zip"
          hidden
          onChange={(e) => {
            const target = e.target as HTMLInputElement
            const file = target.files?.[0]
            target.value = ''
            if (file) doImport(file)
          }}
        />
      </div>
    </>
  )
}

/** Sincronização da posição de leitura com o app do celular, por um gist secreto do GitHub. */
function SyncSection() {
  const [settings, setSettings] = useState<SyncSettings | null | undefined>(undefined)
  const [token, setToken] = useState('')
  const [busy, setBusy] = useState(false)
  const load = () => syncSettings().then((s) => setSettings(s ?? null))
  useEffect(() => {
    load()
  }, [])
  const connect = async (e: Event) => {
    e.preventDefault()
    if (!token.trim()) return
    setBusy(true)
    try {
      const login = await connectSync(token)
      setToken('')
      toast(`Conectado como @${login}. A posição de leitura vai sincronizar.`)
    } catch (error) {
      const status = error instanceof SyncError ? error.status : 0
      toast(status === 401 ? 'O GitHub recusou o token. Confira se copiou inteiro.' : status === 403 ? 'O token não tem permissão de gists (Account permissions → Gists → Read and write).' : status ? `O GitHub respondeu com erro (${status}).` : 'Sem conexão com o GitHub. Tente de novo com internet.')
    } finally {
      setBusy(false)
      load()
    }
  }
  const disconnect = async () => {
    if (!(await confirmDialog('Desligar a sincronização?', 'O token é esquecido neste navegador. O gist continua na sua conta do GitHub.', 'Desligar'))) return
    await disconnectSync()
    load()
  }
  if (settings === undefined) return null
  return (
    <>
      <h3>Sincronizar a posição pelo GitHub</h3>
      {settings ? (
        <div class="sync-box">
          <p>Conectado como <strong>@{settings.login}</strong>. Última sincronização: {settings.lastSync ? new Date(settings.lastSync).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : 'ainda não'}.</p>
          {settings.error && <p class="sync-error">⚠ {settings.error} Desligue e conecte de novo com um token novo.</p>}
          <p class="muted small">Ao abrir um livro, se ele foi lido depois em outro aparelho (o celular, outro computador), o site continua de lá, com a opção de voltar. Ao sair do livro, e a cada 3 minutos de leitura, a posição daqui é gravada.</p>
          <button type="button" class="secondary" onClick={disconnect}>Desligar a sincronização</button>
        </div>
      ) : (
        <form class="sync-box" onSubmit={connect}>
          <p>Leia no computador e continue no celular (no app Alexandria) de onde parou, e vice-versa. Os arquivos não viajam: carregue o mesmo livro nos dois. Só a posição vai, num gist secreto da sua conta do GitHub, com um código no lugar do nome do livro.</p>
          <ol class="sync-steps">
            <li>Abra <a href="https://github.com/settings/personal-access-tokens/new" target="_blank" rel="noopener">a página de novo token do GitHub</a> (Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token).</li>
            <li>Dê um nome (ex.: Alexandria) e escolha a validade.</li>
            <li>Em <em>Account permissions</em>, procure <strong>Gists</strong> e escolha <strong>Read and write</strong>. Não precisa de mais nada.</li>
            <li>Clique em <em>Generate token</em>, copie o token (começa com <code>github_pat_</code>) e cole abaixo. Use o mesmo token no app do celular (Ajustes → Sincronizar posição).</li>
          </ol>
          <label class="field">
            <span>Token do GitHub</span>
            <input type="password" autoComplete="off" spellcheck={false} placeholder="github_pat_…" value={token} onInput={(e) => setToken((e.target as HTMLInputElement).value)} />
          </label>
          <p class="muted small">O token fica guardado só neste navegador (não vai para o backup) e só dá acesso aos seus gists.</p>
          <button type="submit" class="primary" disabled={busy || !token.trim()}>{busy ? 'Conectando…' : 'Conectar com o GitHub'}</button>
        </form>
      )}
    </>
  )
}

/**
 * Nuvem pelo Telegram: livros e posição de leitura nas Mensagens salvas da própria pessoa. Login em
 * etapas (número, código, senha de duas etapas) com o api_id/api_hash dela.
 */
function TelegramSection() {
  const [settings, setSettings] = useState<TelegramSettings | null | undefined>(undefined)
  const [provider, setProvider] = useState<SyncProvider | null>(null)
  const [github, setGithub] = useState(false)
  const [step, setStep] = useState<'form' | 'code' | 'password'>('form')
  const [form, setForm] = useState({ apiId: '', apiHash: '', phone: '', code: '', password: '' })
  const [busy, setBusy] = useState(false)
  const login = useRef<import('../cloud/gramApi').TelegramLogin | null>(null)
  const load = async () => {
    setSettings((await telegramSettings()) ?? null)
    setProvider(await syncProvider())
    setGithub(!!(await syncSettings())?.token)
  }
  useEffect(() => {
    load()
  }, [])
  const field = (key: keyof typeof form) => (e: Event) => setForm({ ...form, [key]: (e.target as HTMLInputElement).value })
  const run = async (work: () => Promise<void>) => {
    setBusy(true)
    try {
      await work()
    } catch (e) {
      const { telegramError } = await import('../cloud/gramApi')
      toast(e instanceof Error && !(e as { errorMessage?: string }).errorMessage && !/^[A-Z_]+/.test(e.message) ? e.message : telegramError(e))
    } finally {
      setBusy(false)
    }
  }
  const finish = async () => {
    const { session, name } = await login.current!.finish()
    await saveTelegram({ apiId: Number(form.apiId), apiHash: form.apiHash.trim(), session, name, error: null })
    await setSyncProvider('telegram')
    setForm({ apiId: '', apiHash: '', phone: '', code: '', password: '' })
    setStep('form')
    login.current = null
    toast(`Conectado ao Telegram como ${name}.`)
    load()
  }
  const sendCode = (e: Event) => {
    e.preventDefault()
    run(async () => {
      const apiId = Number(form.apiId)
      if (!Number.isInteger(apiId) || apiId <= 0 || !form.apiHash.trim()) throw new Error('Preencha o api_id (só números) e o api_hash, de my.telegram.org.')
      if (!/^\+?\d{8,15}$/.test(form.phone.replace(/[\s()-]/g, ''))) throw new Error('Digite o número com +55 e DDD, por exemplo +55 11 91234-5678.')
      const { TelegramLogin } = await import('../cloud/gramApi')
      login.current = new TelegramLogin({ apiId, apiHash: form.apiHash.trim() })
      await login.current.sendCode(form.phone)
      setStep('code')
      toast('O Telegram mandou um código para o app do Telegram (ou por SMS).')
    })
  }
  const sendLoginCode = (e: Event) => {
    e.preventDefault()
    run(async () => {
      const result = await login.current!.signIn(form.code)
      if (result === 'password') setStep('password')
      else await finish()
    })
  }
  const sendPassword = (e: Event) => {
    e.preventDefault()
    run(async () => {
      await login.current!.password(form.password)
      await finish()
    })
  }
  const logout = async () => {
    if (!settings || !(await confirmDialog('Sair do Telegram?', 'A sessão do Alexandria é encerrada no Telegram (some da lista de dispositivos) e esquecida neste navegador. Os livros e posições continuam nas suas Mensagens salvas.', 'Sair'))) return
    run(async () => {
      try {
        const { logOut } = await import('../cloud/gramApi')
        await logOut({ apiId: settings.apiId, apiHash: settings.apiHash }, settings.session)
      } catch {
        // Sem internet ou sessão já encerrada: esquece aqui mesmo assim.
      }
      await setPref('telegram', null)
      toast('Sessão do Telegram encerrada.')
      load()
    })
  }
  if (settings === undefined) return null
  const notice = (
    <p class="muted small telegram-notice">
      Esta função usa a <strong>API do Telegram</strong> e faz parte do ecossistema do Telegram; o Alexandria não é um app oficial do Telegram. Contas que entram por apps não oficiais ficam sob observação do Telegram, segundo as regras dele (core.telegram.org/api/obtaining_api_id).
    </p>
  )
  return (
    <>
      <h2>Nuvem e sincronização</h2>
      <h3>☁ Nuvem pelo Telegram (livros e posição)</h3>
      {settings?.session ? (
        <div class="sync-box">
          <p>Conectado ao Telegram como <strong>{settings.name}</strong>. Os livros enviados para a nuvem e as posições de leitura levam a marca #alexandria. Um arquivo de livro que você mandar pelo próprio Telegram também entra na nuvem se a legenda tiver <strong>#alexandria_livro</strong>.</p>
          {settings.error && <p class="sync-error">⚠ {settings.error}</p>}
          <p class="muted small">Cada aba pode ter o seu grupo privado do Telegram (livros, filmes, cursos): escolha em <strong>⋯ Opções da aba → Grupo da nuvem</strong>. A aba principal de livros, sem grupo, usa as suas Mensagens salvas.</p>
          {notice}
          <button type="button" class="secondary" disabled={busy} onClick={logout}>Sair do Telegram</button>
        </div>
      ) : (
        <div class="sync-box">
          <p>Leve livros e a posição de leitura entre o computador e o celular (app Alexandria) pelas suas <strong>Mensagens salvas</strong> do Telegram, com arquivos de até 2 GB.</p>
          {notice}
          {step === 'form' && (
            <form onSubmit={sendCode}>
              <ol class="sync-steps">
                <li>Em <a href="https://my.telegram.org/apps" target="_blank" rel="noopener">my.telegram.org</a>, entre com seu número e abra <em>API development tools</em>.</li>
                <li>Crie o aplicativo (nome: Alexandria Leitor; plataforma: Outro). Copie o <strong>api_id</strong> e o <strong>api_hash</strong>.</li>
                <li>Cole abaixo, digite seu número e peça o código. Cada aparelho entra uma vez.</li>
              </ol>
              <label class="field"><span>api_id</span><input inputMode="numeric" autoComplete="off" value={form.apiId} onInput={field('apiId')} /></label>
              <label class="field"><span>api_hash</span><input type="password" autoComplete="off" spellcheck={false} value={form.apiHash} onInput={field('apiHash')} /></label>
              <label class="field"><span>Número do Telegram</span><input type="tel" autoComplete="tel" placeholder="+55 11 91234-5678" value={form.phone} onInput={field('phone')} /></label>
              <p class="muted small">A sessão fica guardada só neste navegador (não vai para o backup).</p>
              <button type="submit" class="primary" disabled={busy}>{busy ? 'Enviando…' : 'Enviar código'}</button>
            </form>
          )}
          {step === 'code' && (
            <form onSubmit={sendLoginCode}>
              <label class="field"><span>Código que chegou no Telegram</span><input inputMode="numeric" autoComplete="one-time-code" value={form.code} onInput={field('code')} autoFocus /></label>
              <button type="submit" class="primary" disabled={busy || !form.code.trim()}>{busy ? 'Entrando…' : 'Entrar'}</button>{' '}
              <button type="button" class="secondary" disabled={busy} onClick={() => setStep('form')}>Voltar</button>
            </form>
          )}
          {step === 'password' && (
            <form onSubmit={sendPassword}>
              <label class="field"><span>Senha de duas etapas do Telegram</span><input type="password" autoComplete="current-password" value={form.password} onInput={field('password')} autoFocus /></label>
              <button type="submit" class="primary" disabled={busy || !form.password}>{busy ? 'Entrando…' : 'Entrar'}</button>
            </form>
          )}
        </div>
      )}
      {settings?.session && github && (
        <div class="sync-box provider-box" role="radiogroup" aria-label="Sincronizar a posição por">
          <p>Sincronizar a posição de leitura por:</p>
          {(['telegram', 'github'] as SyncProvider[]).map((p) => (
            <label key={p} class="toggle">
              <input type="radio" name="provider" checked={provider === p} onChange={async () => {
                await setSyncProvider(p)
                setProvider(p)
              }} /> {p === 'telegram' ? 'Telegram' : 'GitHub'}
            </label>
          ))}
        </div>
      )}
    </>
  )
}

