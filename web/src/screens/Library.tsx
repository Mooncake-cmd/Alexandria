import { useEffect, useRef, useState } from 'preact/hooks'
import { shelfCompare } from '../core/library'
import { isArchived, isFinished, type LibraryItem } from '../storage/library'
import { useLibrary } from '../storage/useLibrary'
import { ACCEPT, importFiles } from '../library/importBooks'
import { BookCard } from '../library/BookCard'
import { WoodenShelf } from '../library/WoodenShelf'
import { renameShelf, setItemTab } from '../storage/library'
import { promptDialog } from '../ui/dialogs'
import { itemOptions } from '../library/itemOptions'
import { ensureCovers } from '../library/covers'
import { toast } from '../ui/dialogs'
import { Empty } from './Empty'
import { readingDays } from '../storage/library'
import { useLibraryData } from '../storage/useLibrary'
import { STREAK_MIN_SECONDS, streakSummary, today } from '../core/days'
import { formatReadingTime } from '../core/readingTime'
import { displayTitle } from '../core/library'
import { cloudBooks, cloudSponsored, deleteFromCloud, fetchFromCloud, localKeys, onCloudActivity, sendToCloud, sponsoredClicked, sponsoredSeen, telegramSettings, type CloudActivity } from '../cloud/cloud'
import { isTelegramUrl, type CloudBook, type SponsoredItem } from '../cloud/telegramCloud'
import type { CloudChat } from '../cloud/gramApi'
import { MAIN_TAB, isVideoTab, tabOf, type AppTab } from '../core/tabs'
import { getTabs, onTabsChange } from '../storage/tabs'
import { tabOptions } from './tabActions'
import { confirmDialog, menuDialog } from '../ui/dialogs'

/** Prateleiras abertas (como no app, começam fechadas); lembradas enquanto o site está aberto. */
const openShelves = new Set<string>()
/** Prateleiras abertas dentro da Estante (independentes das de "Lendo agora"). */
const openEstante = new Set<string>()
let estanteOpen = false

export function openBook(item: LibraryItem) {
  if (isArchived(item)) {
    toast(`O arquivo de "${item.name}" foi apagado para liberar espaço. Importe o mesmo arquivo de novo para ler.`)
    return
  }
  location.hash = `#/ler/${item.id}`
}

export function Library({ tab }: { tab: AppTab }) {
  const { items: everything, loaded } = useLibrary()
  // Só os livros desta aba (os de antes das abas ficam na principal).
  const items = everything.filter((i) => tabOf(i) === tab.id)
  const [bookTabs, setBookTabs] = useState<AppTab[]>([])
  useEffect(() => {
    const load = () => getTabs().then((t) => setBookTabs(t.filter((x) => !isVideoTab(x))))
    load()
    return onTabsChange(load)
  }, [])
  const [, redraw] = useState(0)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (loaded) ensureCovers(items)
  }, [loaded, items])
  // Ctrl + O importa (como "Abrir" nos programas do computador).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'o' || document.querySelector('.modal')) return
      e.preventDefault()
      input.current?.click()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
  const reading = items.filter((i) => !isFinished(i))
  const finished = items.filter((i) => isFinished(i))

  // ---------- nuvem pelo Telegram ----------
  const [cloudOn, setCloudOn] = useState(false)
  const [cloud, setCloud] = useState<CloudBook[] | null>(null)
  const [cloudError, setCloudError] = useState<string | null>(null)
  const [keys, setKeys] = useState<Map<string, LibraryItem>>(new Map())
  const [activity, setActivity] = useState<CloudActivity | null>(null)
  const [place, setPlace] = useState('nas suas Mensagens salvas')
  const [ads, setAds] = useState<SponsoredItem[]>([])
  useEffect(() => onCloudActivity(setActivity), [])
  const refreshCloud = async () => {
    const s = await telegramSettings()
    // Aba sem grupo (fora a principal, que usa as Mensagens salvas) não tem nuvem.
    const on = !!s?.session && (!!tab.chat || tab.id === MAIN_TAB)
    setCloudOn(on)
    if (!on) return setCloud(null)
    setPlace(tab.chat ? `no grupo "${tab.chat.title}"` : 'nas suas Mensagens salvas')
    try {
      setCloud(await cloudBooks(tab.id))
      setCloudError(null)
      // Abrir a nuvem de um grupo equivale a abrir a conversa: pede os anúncios oficiais (regra 3.3).
      setAds(await cloudSponsored(tab.chat))
    } catch {
      setCloudError('Não foi possível falar com o Telegram agora (sem internet?).')
    }
  }
  useEffect(() => {
    refreshCloud()
  }, [tab.chat?.id])
  useEffect(() => {
    // Livros de todas as abas contam como "já está aqui" (o mesmo arquivo não é baixado duas vezes).
    localKeys(everything).then(setKeys)
  }, [everything])
  const inCloud = new Set((cloud ?? []).map((b) => b.key))
  const keyOf = (item: LibraryItem) => [...keys.entries()].find(([, i]) => i.id === item.id)?.[0]
  const missing = (cloud ?? []).filter((b) => !keys.has(b.key))
  const upload = async (item: LibraryItem) => {
    try {
      const { existed } = await sendToCloud(item, displayTitle(item.name))
      toast(existed ? `"${displayTitle(item.name)}" já estava na nuvem.` : `"${displayTitle(item.name)}" foi para a nuvem (${place.replace(/^n[ao]s? (suas )?/, '')}).`)
      refreshCloud()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Não foi possível enviar para a nuvem.')
    }
  }
  const download = async (book: CloudBook) => {
    try {
      const item = await fetchFromCloud(book, displayTitle(book.name), tab.id)
      toast(`"${displayTitle(book.name)}" baixado da nuvem.`)
      openBook(item)
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Não foi possível baixar da nuvem.')
    }
  }
  const cloudOptions = async (book: CloudBook) => {
    const choice = await menuDialog(displayTitle(book.name), ['Baixar e abrir', 'Apagar da nuvem…'], `${book.format.toUpperCase()} · ${(book.size / 1e6).toFixed(1).replace('.', ',')} MB · na nuvem`)
    if (choice === 0) download(book)
    if (choice === 1 && (await confirmDialog('Apagar da nuvem?', `"${displayTitle(book.name)}" sai da nuvem (${place.replace(/^n[ao]s? (suas )?/, "")}). Os aparelhos que já baixaram continuam com o livro.`, 'Apagar', true))) {
      await deleteFromCloud(book, tab.id)
      refreshCloud()
    }
  }

  const grouped = (list: LibraryItem[]) => {
    const loose = list.filter((i) => !i.shelf)
    const groups = new Map<string, LibraryItem[]>()
    for (const i of list.filter((i) => i.shelf)) groups.set(i.shelf, [...(groups.get(i.shelf) ?? []), i])
    return (
      <>
        {loose.length > 0 && <div class="grid">{loose.map((i) => card(i))}</div>}
        {[...groups.keys()].sort(shelfCompare).map((name) => {
          const open = openShelves.has(name)
          const books = groups.get(name)!
          return (
            <section key={name} class="shelf">
              <button
                type="button"
                class="shelf-title"
                aria-expanded={open}
                onClick={() => {
                  if (!openShelves.delete(name)) openShelves.add(name)
                  redraw((n) => n + 1)
                }}
              >
                {open ? '▼' : '▶'}  📁 {name} ({books.length})
              </button>
              {open && <div class="grid">{books.map((i) => card(i))}</div>}
            </section>
          )
        })}
      </>
    )
  }

  const cloudExtra = (item: LibraryItem) => {
    const others = bookTabs.filter((t) => t.id !== tab.id)
    const move = others.length
      ? [{ label: 'Mover para outra aba…', run: async () => {
          const pick = await menuDialog('Mover para a aba', others.map((t) => t.name), displayTitle(item.name))
          if (pick === null) return
          await setItemTab(item.id, others[pick].id)
          toast(`"${displayTitle(item.name)}" foi para a aba "${others[pick].name}".`)
        } }]
      : []
    if (!cloudOn || isArchived(item)) return move
    const key = keyOf(item)
    if (key && inCloud.has(key)) return [{ label: '☁ Já está na nuvem (Telegram)', run: () => toast(`Este livro já está ${place}.`) }, ...move]
    return [{ label: '☁ Enviar para a nuvem (Telegram)', run: () => upload(item) }, ...move]
  }
  const card = (item: LibraryItem) => {
    const key = keyOf(item)
    return <BookCard key={item.id} item={item} cloud={!!key && inCloud.has(key)} onOpen={() => openBook(item)} onOptions={() => itemOptions(item, cloudExtra(item))} />
  }

  const days = useLibraryData<Record<string, number>>(readingDays, {})
  const streak = streakSummary(days, today())
  return (
    <>
      {activity && (
        <div class="cloud-activity" role="status" aria-live="polite">
          ☁ {activity.label} · {Math.round(activity.fraction * 100)}%
          <div class="bar"><div style={{ width: `${Math.round(activity.fraction * 100)}%` }} /></div>
        </div>
      )}
      <div class="tab-head">
        <h2>{tab.id === MAIN_TAB && tab.name === 'Livros' ? 'Minha biblioteca' : tab.name}</h2>
        <button type="button" class="secondary" onClick={() => tabOptions(tab)} aria-label={`Opções da aba ${tab.name}`}>⋯ Opções da aba</button>
      </div>
      <p class="muted">Seus documentos ficam guardados só neste navegador, no seu computador.{tab.chat ? ` A nuvem desta aba é o grupo "${tab.chat.title}".` : ''}</p>
      {(streak.current > 0 || streak.todaySeconds > 0) && (
        <a class="streak" href="#/estatisticas">
          🔥 {streak.current === 1 ? '1 dia seguido' : `${streak.current} dias seguidos`} · {streak.readToday ? 'hoje ✓' : `leia ${formatReadingTime(STREAK_MIN_SECONDS)} hoje para manter`}
        </a>
      )}
      <div class="import-row">
        <button class="primary" type="button" title="Importar documentos (Ctrl + O)" onClick={() => input.current?.click()}>＋ Importar documentos</button>
        <span class="muted small">ou arraste os arquivos para esta janela</span>
      </div>
      <input
        ref={input}
        type="file"
        multiple
        accept={ACCEPT}
        hidden
        onChange={async (e) => {
          const target = e.target as HTMLInputElement
          await importFiles(Array.from(target.files ?? []), tab.id)
          target.value = ''
        }}
      />
      <p class="label">LEITURA DISPONÍVEL</p>
      <p>PDF · EPUB · MOBI · AZW3 · DJVU · CBZ · CBR · DOCX · TXT</p>

      <h2>Lendo agora</h2>
      <p class="muted small">{reading.length} documento(s)</p>
      {loaded && items.length === 0 ? (
        <Empty icon="▤" title="Sua biblioteca começa aqui" message="Importe um PDF, EPUB, MOBI, AZW3, DJVU, quadrinho CBZ/CBR, DOCX ou TXT para começar a ler." />
      ) : reading.length === 0 ? (
        <p class="muted small">Nenhum livro em andamento. Os concluídos estão na Estante, abaixo.</p>
      ) : (
        grouped(reading)
      )}

      {cloudOn && (
        <section class="cloud-section">
          <h2>☁ Na nuvem</h2>
          {cloudError ? (
            <p class="muted small">{cloudError} <button type="button" class="link" onClick={refreshCloud}>Tentar de novo</button></p>
          ) : cloud === null ? (
            <p class="muted small">Procurando seus livros {place} do Telegram…</p>
          ) : missing.length === 0 ? (
            <p class="muted small">{cloud.length ? `Os ${cloud.length} livro(s) da nuvem já estão neste navegador.` : 'Nenhum livro na nuvem ainda. No menu de um livro (⋯), use "Enviar para a nuvem".'}</p>
          ) : (
            <>
              <p class="muted small">Livros {place} que ainda não estão neste navegador:</p>
              <ul class="cloud-list">
                {missing.map((book) => (
                  <li key={book.key}>
                    <span class="name">☁ {displayTitle(book.name)} <span class="muted small">· {book.format.toUpperCase()} · {(book.size / 1e6).toFixed(1).replace('.', ',')} MB{book.sender ? ` · enviado por ${book.sender}` : ''}</span></span>
                    <span>
                      <button type="button" class="secondary" onClick={() => download(book)}>Baixar e abrir</button>
                      <button type="button" class="card-more" aria-label={`Opções de ${displayTitle(book.name)} na nuvem`} onClick={() => cloudOptions(book)}>⋯</button>
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
          {tab.chat && ads.map((ad) => <SponsoredCard key={ad.id} ad={ad} chat={tab.chat!} />)}
        </section>
      )}

      {items.length > 0 && (
        <section class="estante">
          <button
            type="button"
            class="estante-title"
            aria-expanded={estanteOpen}
            onClick={() => {
              estanteOpen = !estanteOpen
              redraw((n) => n + 1)
            }}
          >
            {estanteOpen ? '▼' : '▶'}  🏆 Estante · {finished.length} concluído(s)
          </button>
          {estanteOpen &&
            (finished.length === 0 ? (
              <p class="muted small">Nenhum livro concluído ainda. Ao chegar no fim de um livro, use "Concluir livro".</p>
            ) : (
              <WoodenShelf
                sections={[
                  { title: null, books: finished.filter((i) => !i.shelf), open: true },
                  ...[...new Set(finished.filter((i) => i.shelf).map((i) => i.shelf))].sort(shelfCompare).map((name) => ({
                    title: name,
                    books: finished.filter((i) => i.shelf === name),
                    open: openEstante.has(name)
                  }))
                ]}
                onOpen={openBook}
                onOptions={(item) => itemOptions(item)}
                onToggleShelf={(name) => {
                  if (!openEstante.delete(name)) openEstante.add(name)
                  redraw((n) => n + 1)
                }}
                onRenameShelf={async (name) => {
                  const typed = await promptDialog('Renomear prateleira', 'Novo nome', name)
                  if (!typed) return
                  const target = await renameShelf(name, typed)
                  if (openEstante.delete(name)) openEstante.add(target)
                  if (openShelves.delete(name)) openShelves.add(target)
                }}
              />
            ))}
        </section>
      )}
      {items.length > 0 && <p class="muted small hint">Clique num livro para abri-lo. Clique com o botão direito (ou em ⋯) para mover de prateleira, concluir, trocar a capa ou remover. Na Estante, o botão direito na placa renomeia a prateleira.</p>}
    </>
  )
}

/**
 * Anúncio oficial do Telegram, mostrado depois de todos os livros da nuvem de um grupo (regra 3.3 dos
 * termos da API e core.telegram.org/api/sponsored-messages): visualização registrada quando o texto
 * inteiro aparece na tela, clique registrado, e confirmação antes de abrir endereços fora do Telegram.
 */
function SponsoredCard({ ad, chat }: { ad: SponsoredItem; chat: CloudChat }) {
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = box.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    let seen = false
    const observer = new IntersectionObserver((entries) => {
      if (!seen && entries.some((e) => e.intersectionRatio >= 0.99)) {
        seen = true
        observer.disconnect()
        sponsoredSeen(ad, chat)
      }
    }, { threshold: [0.99] })
    observer.observe(el)
    return () => observer.disconnect()
  }, [ad.id])
  const open = async () => {
    await sponsoredClicked(ad, chat)
    if (!isTelegramUrl(ad.url) && !(await confirmDialog('Abrir o endereço?', ad.url, 'Abrir'))) return
    window.open(ad.url, '_blank', 'noopener')
  }
  const info = [ad.sponsorInfo, ad.additionalInfo].filter(Boolean).join('\n\n')
  return (
    <div class="sponsored" ref={box}>
      <p class="sponsored-label">{ad.recommended ? 'Recomendado' : 'Patrocinado'}{info && <> · <button type="button" class="link" onClick={() => confirmDialog('Sobre o anunciante', info, 'Fechar')}>Sobre o anunciante</button></>}</p>
      <p class="sponsored-title">{ad.title}</p>
      <p class="sponsored-text">{ad.message}</p>
      <button type="button" class="secondary" onClick={open}>{ad.buttonText || 'Abrir'}</button>
    </div>
  )
}
