import { useEffect, useRef, useState } from 'preact/hooks'
import { exportBackup, importBackup, requestPersistence, storageInfo } from '../storage/backup'
import { confirmDialog, shortcutsDialog, toast } from '../ui/dialogs'

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
        <dd>Ficam só neste navegador, neste computador. Nada é enviado para a internet.</dd>
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
