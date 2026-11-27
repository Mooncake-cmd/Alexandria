package com.alexandria.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var database: LibraryDatabase
    private lateinit var content: LinearLayout
    private val ink = Palette.ink
    private val gold = Palette.gold
    private val paper = Palette.paper
    private val muted = Palette.muted

    private var section = SECTION_LIBRARY
    private var refreshOnResume = false
    private var shelfOpen = false
    private val openShelves = mutableSetOf<String>()
    /** Prateleiras abertas dentro da Estante (independentes das de "Lendo agora"). */
    private val openEstante = mutableSetOf<String>()
    /** Livro cuja capa está sendo trocada (aguardando a imagem da galeria). */
    private var pendingCoverId = 0L
    private var scroller: ScrollView? = null
    private var scrollerScreen = ""

    /** Gera capas e conta páginas em segundo plano, um documento por vez. */
    private val background = Executors.newSingleThreadExecutor()
    private val preparing = mutableSetOf<Long>()
    private var destroyed = false
    private val dateFormat = DateFormat.getDateInstance(DateFormat.SHORT, Locale("pt", "BR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = LibraryDatabase(this)
        section = savedInstanceState?.getString(KEY_SECTION) ?: SECTION_LIBRARY
        shelfOpen = savedInstanceState?.getBoolean(KEY_SHELF_OPEN) ?: false
        savedInstanceState?.getStringArrayList(KEY_OPEN_SHELVES)?.let { openShelves += it }
        savedInstanceState?.getStringArrayList(KEY_OPEN_ESTANTE)?.let { openEstante += it }
        pendingCoverId = savedInstanceState?.getLong(KEY_PENDING_COVER) ?: 0L
        showSection(section)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION, section)
        outState.putBoolean(KEY_SHELF_OPEN, shelfOpen)
        outState.putStringArrayList(KEY_OPEN_SHELVES, ArrayList(openShelves))
        outState.putStringArrayList(KEY_OPEN_ESTANTE, ArrayList(openEstante))
        outState.putLong(KEY_PENDING_COVER, pendingCoverId)
    }

    override fun onResume() {
        super.onResume()
        // Ao voltar (do leitor ou de um livro aberto por outro app), atualiza a tela.
        if (refreshOnResume || section != SECTION_SETTINGS) {
            refreshOnResume = false
            showSection(section)
        }
        // Voltou da tela do Android que permite instalar atualizações.
        pendingUpdate?.let { apk ->
            if (AppUpdates.canInstall(this)) {
                pendingUpdate = null
                installUpdate(apk)
            }
        }
        if (pendingUpdate == null && AppUpdates.due(this)) {
            AppUpdates.check(this) { result ->
                result.getOrNull()?.let { if (!destroyed) offerUpdate(it) }
            }
        }
    }

    // ---------- atualização do app ----------

    /** APK baixado esperando a permissão de instalar (Android 8+). */
    private var pendingUpdate: File? = null

    private fun offerUpdate(release: AppRelease) {
        val size = if (release.apkSize > 0) " (${"%.1f".format(release.apkSize / 1e6).replace('.', ',')} MB)" else ""
        val notes = release.notes.ifBlank { "Correções e melhorias." }.take(1500)
        AlertDialog.Builder(this)
            .setTitle("Atualização disponível: ${release.version}")
            .setMessage("Você está na versão ${AppUpdates.installedVersion(this)}.\n\nNovidades:\n$notes\n\nBiblioteca, notas e marcadores continuam como estão.")
            .setNegativeButton("Depois", null)
            .setPositiveButton("Atualizar$size") { _, _ -> downloadUpdate(release) }
            .show()
    }

    private fun downloadUpdate(release: AppRelease) {
        val (dialog, progress) = progressDialog("Baixando a versão ${release.version}…")
        AppUpdates.download(this, release, progress) { result ->
            dialog.dismiss()
            if (destroyed) return@download
            result.onSuccess { installUpdate(it) }
                .onFailure { Toast.makeText(this, it.message ?: "Não foi possível baixar a atualização. Confira a internet.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun installUpdate(apk: File) {
        if (!AppUpdates.canInstall(this)) {
            pendingUpdate = apk
            AlertDialog.Builder(this)
                .setTitle("Permitir atualizações")
                .setMessage("Na primeira vez, o Android pede que você permita que o Alexandria instale atualizações. Na próxima tela, ative \"Permitir desta fonte\" e volte para o app.")
                .setNegativeButton("Cancelar") { _, _ -> pendingUpdate = null }
                .setPositiveButton("Abrir permissão") { _, _ ->
                    try {
                        startActivity(AppUpdates.permissionIntent(this))
                    } catch (e: Exception) {
                        pendingUpdate = null
                        Toast.makeText(this, "Não foi possível abrir a permissão. Procure \"Instalar apps desconhecidos\" nas configurações do Android.", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            return
        }
        try {
            AppUpdates.install(this, apk)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkUpdateNow() {
        Toast.makeText(this, "Procurando atualização…", Toast.LENGTH_SHORT).show()
        AppUpdates.check(this) { result ->
            if (destroyed) return@check
            result.onSuccess { release ->
                if (release != null) offerUpdate(release)
                else Toast.makeText(this, "Você já tem a versão mais recente (${AppUpdates.installedVersion(this)}).", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this, "Sem conexão com o GitHub. Tente de novo com internet.", Toast.LENGTH_LONG).show() }
            if (section == SECTION_SETTINGS) showSettings()
        }
    }

    override fun onDestroy() {
        destroyed = true
        background.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun showSection(name: String) {
        when (name) {
            SECTION_LIBRARY -> showLibrary()
            SECTION_NOTES -> showNotes()
            SECTION_STATS -> showStats()
            SECTION_SETTINGS -> showSettings()
            else -> showLibrary()
        }
    }

    private fun baseScreen(selected: String): LinearLayout {
        section = selected
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(20))
            setBackgroundColor(ink)
        }
        header.addView(label("ALEXANDRIA", 12f, gold, true))
        header.addView(label("Sua biblioteca pessoal", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(3))
        })
        header.addView(label("Um espaço para suas leituras.", 13f, Color.rgb(202, 207, 214)))
        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        // Ao redesenhar a mesma aba (abrir uma prateleira, trocar uma capa), mantém a posição da rolagem.
        val previous = if (scrollerScreen == selected) scroller?.scrollY ?: 0 else 0
        val scroll = ScrollView(this).apply { addView(content) }
        scroller = scroll
        scrollerScreen = selected
        if (previous > 0) scroll.post { scroll.scrollTo(0, previous) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(6), dp(5), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        NAV_ITEMS.forEach { name ->
            val active = name == selected
            val color = if (active) gold else muted
            val icon = getDrawable(NAV_ICONS.getValue(name))?.mutate()?.apply {
                setTint(color)
                setBounds(0, 0, dp(24), dp(24))
            }
            val entry = TextView(this).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                minHeight = dp(56)
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(3)
                setTextColor(color)
                if (active) setTypeface(typeface, Typeface.BOLD)
                contentDescription = name
                setOnClickListener { showSection(name) }
            }
            nav.addView(entry, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        installSystemBarInsets(root, header, nav)
        return root
    }

    private fun sectionTitle(title: String, subtitle: String? = null) {
        content.addView(label(title, 21f, ink, true))
        if (subtitle != null) {
            content.addView(label(subtitle, 13f, muted).apply {
                setPadding(0, dp(4), 0, dp(14))
            })
        } else content.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
    }

    private fun spacer(height: Int) = content.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))

    private fun emptyPanel(icon: String, title: String, message: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(28), dp(18), dp(28))
            background = rounded(Color.WHITE, 18)
            addView(label(icon, 34f, gold, true))
            addView(label(title, 16f, ink, true).apply { setPadding(0, dp(10), 0, dp(5)) })
            addView(label(message, 12f, muted).apply { gravity = Gravity.CENTER })
        }

    // ---------- Biblioteca ----------

    private fun showLibrary() {
        setContentView(baseScreen(SECTION_LIBRARY))
        val tab = AppTabs.current(this)
        if (cloudTab != tab.id) {
            cloudTab = tab.id
            cloudBooks = null
            cloudError = null
            cloudAds = emptyList()
        }
        addTabStrip(tab)
        if (tab.isVideo) return showVideoTab(tab)
        tabTitle(tab, "Seus documentos ficam organizados neste dispositivo." + (tab.chat?.let { " A nuvem desta aba é o grupo \"${it.title}\"." } ?: ""))
        val streak = Streak.summary(database.readingDays(), Days.today())
        if (streak.current > 0 || streak.todaySeconds > 0) {
            val days = if (streak.current == 1) "1 dia seguido" else "${streak.current} dias seguidos"
            val today = if (streak.readToday) "hoje ✓" else "leia ${ReadingTime.format(Streak.MIN_SECONDS)} hoje para manter"
            content.addView(label("🔥 $days · $today", 13f, gold, true).apply {
                setPadding(0, 0, 0, dp(12))
                setOnClickListener { showSection(SECTION_STATS) }
            })
        }

        val importButton = Button(this).apply {
            text = "＋  Importar documentos"
            setTextColor(Color.WHITE)
            background = rounded(ink, 12)
            setAllCaps(false)
            setOnClickListener { chooseDocument() }
        }
        content.addView(importButton, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(label("LEITURA DISPONÍVEL", 10f, muted, true).apply {
            setPadding(0, dp(22), 0, dp(4))
        })
        content.addView(label("PDF  ·  EPUB  ·  MOBI  ·  AZW3  ·  DJVU  ·  CBZ  ·  CBR  ·  DOCX  ·  TXT", 12f, ink))
        spacer(22)

        val everything = database.all()
        // Livros de todas as abas contam como "já está aqui" (o mesmo arquivo não é baixado duas vezes).
        localCloudKeys = everything.filter { it.fileSize > 0 && !it.archived }.associateBy { SyncData.key(it.name, it.fileSize) }
        val items = everything.filter { it.tab == tab.id }
        prepareMissing(items)
        val reading = items.filter { !it.finished }
        val finished = items.filter { it.finished }

        sectionTitle("Lendo agora", "${reading.size} documento(s)")
        if (items.isEmpty()) {
            content.addView(
                emptyPanel(
                    "▤",
                    "Sua biblioteca começa aqui",
                    "Importe um PDF, EPUB, MOBI, AZW3, DJVU, quadrinho CBZ/CBR, DOCX ou TXT para começar a ler."
                ),
                LinearLayout.LayoutParams(-1, -2)
            )
            addCloudSection(items)
            return
        }
        if (reading.isEmpty()) {
            content.addView(label("Nenhum livro em andamento. Os concluídos estão na Estante, abaixo.", 12f, muted))
        } else {
            addGroupedCards(reading)
        }
        addCloudSection(items)

        spacer(18)
        val arrow = if (shelfOpen) "▼" else "▶"
        content.addView(label("$arrow  🏆 Estante · ${finished.size} concluído(s)", 17f, ink, true).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                shelfOpen = !shelfOpen
                showLibrary()
            }
        })
        if (shelfOpen) {
            if (finished.isEmpty()) {
                content.addView(label("Nenhum livro concluído ainda. Ao chegar no fim de um livro, toque em \"Concluir livro\".", 12f, muted))
            } else {
                addWoodenShelf(finished)
            }
        }
        spacer(14)
        content.addView(label("Toque em um item para abri-lo. Mantenha pressionado para mover de prateleira, concluir ou remover.", 11f, muted))
    }

    /** Estante de madeira: livros soltos primeiro, depois cada prateleira com a sua placa. */
    private fun addWoodenShelf(finished: List<LibraryItem>) {
        val sections = mutableListOf(ShelfSection(null, finished.filter { it.shelf.isEmpty() }))
        val groups = finished.filter { it.shelf.isNotEmpty() }.groupBy { it.shelf }
        for (name in groups.keys.sortedWith(Shelves.order)) sections += ShelfSection(name, groups.getValue(name), name in openEstante)
        val shelf = WoodenShelf(
            this,
            sections,
            cover = { BookFiles.loadCover(this, it.id) },
            title = { displayTitle(it.name) },
            status = { statusText(it) },
            onOpen = { openItem(it) },
            onOptions = { itemOptions(it) },
            onToggleShelf = { name ->
                if (!openEstante.remove(name)) openEstante += name
                showLibrary()
            },
            onRenameShelf = { renameShelf(it) }
        )
        content.addView(shelf, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    /** Livros soltos primeiro; os que estão em prateleiras, em blocos recolhíveis por nome. */
    private fun addGroupedCards(items: List<LibraryItem>) {
        items.filter { it.shelf.isEmpty() }.forEach { addCard(it) }
        val groups = items.filter { it.shelf.isNotEmpty() }.groupBy { it.shelf }
        for (name in groups.keys.sortedWith(Shelves.order)) {
            val books = groups.getValue(name)
            val open = name in openShelves
            content.addView(label("${if (open) "▼" else "▶"}  📁 $name (${books.size})", 14f, ink, true).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (!openShelves.remove(name)) openShelves += name
                    showLibrary()
                }
                setOnLongClickListener {
                    renameShelf(name)
                    true
                }
            })
            if (open) books.forEach { addCard(it) }
        }
    }

    private fun addCard(entry: LibraryItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, 14)
            setOnClickListener { openItem(entry) }
            setOnLongClickListener {
                itemOptions(entry)
                true
            }
        }
        card.addView(coverView(entry), LinearLayout.LayoutParams(dp(52), dp(72)).apply { rightMargin = dp(12) })

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(label(displayTitle(entry.name), 15f, ink, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(label(entry.format.uppercase(), 10f, gold, true).apply { setPadding(0, dp(4), 0, 0) })
        val inCloud = cloudBooks?.any { it.key == keyOf(entry) } == true
        info.addView(label(statusText(entry) + if (inCloud) "  ·  ☁" else "", 11f, muted).apply { setPadding(0, dp(3), 0, dp(6)) })
        if (entry.format in Formats.readable) info.addView(progressBar(ReadingStatus.progress(entry)), LinearLayout.LayoutParams(-1, dp(4)))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    /** "Concluído em …", "25% lido" etc., com o tempo de leitura quando houver. */
    private fun statusText(entry: LibraryItem): String {
        val base = when {
            entry.archived -> "Concluído em ${dateFormat.format(Date(entry.finishedAt))} · arquivo removido"
            entry.finished -> "Concluído em ${dateFormat.format(Date(entry.finishedAt))}"
            else -> ReadingStatus.describe(entry)
        }
        return if (entry.readingSeconds >= 60) "$base · ⏱ ${ReadingTime.format(entry.readingSeconds)}" else base
    }

    private fun coverView(entry: LibraryItem): View {
        val cover = BookFiles.loadCover(this, entry.id)
        if (cover != null) {
            return ImageView(this).apply {
                setImageBitmap(cover)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.rgb(232, 226, 214), 6)
                clipToOutline = true
            }
        }
        return FrameLayout(this).apply {
            background = rounded(if (entry.finished) gold else ink, 6)
            addView(label(entry.format.uppercase(), 11f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun progressBar(fraction: Float): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(Color.rgb(232, 226, 214), 2)
        if (fraction > 0f) addView(View(context).apply { background = rounded(gold, 2) }, LinearLayout.LayoutParams(0, -1, fraction))
        if (fraction < 1f) addView(View(context), LinearLayout.LayoutParams(0, -1, 1f - fraction))
    }

    private fun displayTitle(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return if (Formats.detect(name, null) != null && extension.isNotEmpty()) name.dropLast(extension.length + 1) else name
    }

    /** Apaga o tempo lido de um livro só; o total das estatísticas cai na mesma medida. */
    private fun confirmClearBookTime(entry: LibraryItem) {
        AlertDialog.Builder(this)
            .setTitle("Apagar tempo de leitura?")
            .setMessage(
                "Apaga ${ReadingTime.format(entry.readingSeconds)} de leitura de \"${displayTitle(entry.name)}\". " +
                    "O total das estatísticas diminui na mesma medida e os dias de leitura (calendário e sequência) são ajustados. " +
                    "O livro, o progresso, as anotações e o glossário continuam. Não dá para desfazer."
            )
            .setPositiveButton("Apagar") { _, _ ->
                val removed = database.clearBookTime(entry.id)
                Toast.makeText(this, "${ReadingTime.format(removed)} apagados de \"${displayTitle(entry.name)}\".", Toast.LENGTH_SHORT).show()
                showSection(section)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- nuvem pelo Telegram ----------

    /** Livros da nuvem (null = ainda não consultado nesta abertura do app). */
    private var cloudBooks: List<CloudBook>? = null
    private var cloudError: String? = null
    private var cloudLoading = false
    private var localCloudKeys: Map<String, LibraryItem> = emptyMap()

    private fun keyOf(entry: LibraryItem): String? = if (entry.fileSize > 0) SyncData.key(entry.name, entry.fileSize) else null

    private fun refreshCloud() {
        val tab = AppTabs.current(this)
        val (on, chat) = AppTabs.cloudOf(this, tab.id)
        if (cloudLoading || !TelegramCloud.connected(this) || !on) return
        if (chat == null) cloudAds = emptyList()
        cloudLoading = true
        TelegramCloud.books(this, chat) { result ->
            cloudLoading = false
            if (destroyed || cloudTab != tab.id) return@books
            result.onSuccess {
                cloudBooks = it
                cloudError = null
            }.onFailure { cloudError = it.message ?: "Não foi possível falar com o Telegram agora." }
            if (section == SECTION_LIBRARY) showLibrary()
            // Abrir a nuvem de um grupo equivale a abrir a conversa: pede os anúncios oficiais (regra 3.3).
            if (result.isSuccess) TelegramCloud.sponsored(this, chat) { ads ->
                if (destroyed || ads == cloudAds) return@sponsored
                cloudAds = ads
                if (section == SECTION_LIBRARY) showLibrary()
            }
        }
    }

    private var cloudAds: List<SponsoredItem> = emptyList()
    private val seenAds = mutableSetOf<String>()

    /** Lugar da nuvem, para os textos ("nas suas Mensagens salvas" ou "no grupo …"). */
    private fun cloudPlace(): String = AppTabs.current(this).chat?.let { "no grupo \"${it.title}\"" } ?: "nas suas Mensagens salvas"

    /** Aba cujos livros da nuvem estão em [cloudBooks]. */
    private var cloudTab = ""

    /**
     * Anúncio oficial do Telegram, depois de todos os livros da nuvem de um grupo
     * (core.telegram.org/api/sponsored-messages): visualização registrada quando o texto inteiro
     * aparece na tela, clique registrado, e confirmação antes de abrir endereços fora do Telegram.
     */
    private fun addSponsoredCard(ad: SponsoredItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.WHITE, 12)
            addView(label(if (ad.recommended) "RECOMENDADO" else "PATROCINADO", 10f, muted, true))
            addView(label(ad.title, 14f, ink, true).apply { setPadding(0, dp(4), 0, dp(2)) })
            addView(label(ad.message, 13f, ink))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(label(ad.buttonText.ifBlank { "Abrir" }, 14f, gold, true).apply {
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { openSponsored(ad) }
                })
                if (ad.info.isNotBlank()) addView(label("   Sobre o anunciante", 12f, muted).apply {
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle("Sobre o anunciante").setMessage(ad.info).setPositiveButton("Fechar", null).show()
                    }
                })
            })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val check = {
            val rect = android.graphics.Rect()
            if (ad.id !in seenAds && card.isShown && card.getGlobalVisibleRect(rect) && rect.height() >= card.height && card.height > 0) {
                seenAds += ad.id
                AppTabs.current(this).chat?.let { TelegramCloud.sponsoredSeen(this, it, ad) }
            }
        }
        card.post { check() }
        scroller?.viewTreeObserver?.addOnScrollChangedListener { check() }
    }

    private fun openSponsored(ad: SponsoredItem) {
        AppTabs.current(this).chat?.let { TelegramCloud.sponsoredClicked(this, it, ad) }
        val open = {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ad.url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Nenhum app abre este endereço.", Toast.LENGTH_SHORT).show()
            }
        }
        if (SponsoredItem.isTelegramUrl(ad.url)) open()
        else AlertDialog.Builder(this).setTitle("Abrir o endereço?").setMessage(ad.url).setNegativeButton("Cancelar", null).setPositiveButton("Abrir") { _, _ -> open() }.show()
    }

    /** "☁ Na nuvem": livros das Mensagens salvas que ainda não estão neste aparelho. */
    private fun addCloudSection(items: List<LibraryItem>) {
        if (!TelegramCloud.connected(this) || !AppTabs.cloudOf(this, AppTabs.current(this).id).first) return
        spacer(18)
        content.addView(label("☁  Na nuvem", 17f, ink, true).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
        })
        val books = cloudBooks
        if (books == null && cloudError == null) {
            content.addView(label("Procurando seus livros ${cloudPlace()} do Telegram…", 12f, muted))
            refreshCloud()
            return
        }
        cloudError?.let { error ->
            content.addView(label("$error  Toque para tentar de novo.", 12f, muted).apply {
                setOnClickListener {
                    cloudError = null
                    cloudBooks = null
                    showLibrary()
                }
            })
            return
        }
        val missing = books!!.filter { it.key !in localCloudKeys }
        if (missing.isEmpty()) {
            content.addView(label(if (books.isEmpty()) "Nenhum livro na nuvem ainda. Toque e segure um livro e escolha \"Enviar para a nuvem\"." else "Os ${books.size} livro(s) da nuvem já estão neste aparelho.", 12f, muted))
            cloudAds.forEach { addSponsoredCard(it) }
            return
        }
        content.addView(label("Livros ${cloudPlace()} que ainda não estão neste aparelho. Toque para baixar e abrir.", 12f, muted).apply { setPadding(0, 0, 0, dp(8)) })
        for (book in missing) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(10), dp(10))
                background = rounded(Color.WHITE, 12)
                setOnClickListener { downloadFromCloud(book) }
                setOnLongClickListener {
                    cloudBookOptions(book)
                    true
                }
                addView(label("☁  ${displayTitle(book.name)}", 14f, ink, true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label("${book.format.uppercase()} · ${"%.1f".format(book.size / 1e6).replace('.', ',')} MB" + if (book.sender.isNotBlank()) "\n${book.sender}" else "", 11f, muted).apply {
                    setPadding(dp(8), 0, 0, 0)
                    gravity = Gravity.END
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        cloudAds.forEach { addSponsoredCard(it) }
    }

    /** Janela de progresso de uma transferência. */
    private fun progressDialog(title: String): Pair<AlertDialog, (Float) -> Unit> {
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val percent = label("0%", 13f, muted)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(percent)
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).show()
        return Pair(dialog) { f ->
            bar.progress = (f * 100).toInt()
            percent.text = "${(f * 100).toInt()}%"
        }
    }

    private fun uploadToCloud(entry: LibraryItem) {
        val (dialog, progress) = progressDialog("Enviando para a nuvem…")
        background.execute {
            val file = try {
                BookFiles.localCopy(this, entry)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (file == null) {
                    dialog.dismiss()
                    Toast.makeText(this, "Não foi possível ler o arquivo deste livro.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                TelegramCloud.upload(this, AppTabs.cloudOf(this, entry.tab).second, entry.name, entry.format, file, progress) { result ->
                    dialog.dismiss()
                    if (destroyed) return@upload
                    result.onSuccess { existed ->
                        Toast.makeText(this, if (existed) "\"${displayTitle(entry.name)}\" já estava na nuvem." else "\"${displayTitle(entry.name)}\" foi para a nuvem (${cloudPlace().removePrefix("nas suas ").removePrefix("no ")}).", Toast.LENGTH_LONG).show()
                        cloudBooks = null
                        if (section == SECTION_LIBRARY) showLibrary()
                    }.onFailure { Toast.makeText(this, it.message ?: "Não foi possível enviar para a nuvem.", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun downloadFromCloud(book: CloudBook) {
        val (dialog, progress) = progressDialog("Baixando da nuvem…")
        val safe = book.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val target = File(File(cacheDir, "cloud"), safe)
        val tab = AppTabs.current(this)
        TelegramCloud.download(this, tab.chat, book, target, progress) { result ->
            result.onFailure {
                dialog.dismiss()
                if (!destroyed) Toast.makeText(this, it.message ?: "Não foi possível baixar da nuvem.", Toast.LENGTH_LONG).show()
            }.onSuccess { file ->
                background.execute {
                    val received = try {
                        IncomingBooks.receive(this, database, android.net.Uri.fromFile(file), null, tab.id)
                    } catch (e: Exception) {
                        null
                    } finally {
                        file.delete()
                    }
                    runOnUiThread {
                        dialog.dismiss()
                        if (destroyed) return@runOnUiThread
                        if (received == null) {
                            Toast.makeText(this, "O arquivo baixado não pôde ser aberto.", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        showLibrary()
                        openItem(received.item)
                    }
                }
            }
        }
    }

    private fun cloudBookOptions(book: CloudBook) {
        AlertDialog.Builder(this)
            .setTitle(displayTitle(book.name))
            .setItems(arrayOf("Baixar e abrir", "Apagar da nuvem…")) { _, which ->
                if (which == 0) downloadFromCloud(book)
                else AlertDialog.Builder(this)
                    .setTitle("Apagar da nuvem?")
                    .setMessage("\"${displayTitle(book.name)}\" sai da nuvem (${cloudPlace().removePrefix("nas suas ").removePrefix("no ")}). Os aparelhos que já baixaram continuam com o livro.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Apagar") { _, _ ->
                        TelegramCloud.remove(this, AppTabs.current(this).chat, book.key) {
                            cloudBooks = null
                            if (!destroyed && section == SECTION_LIBRARY) showLibrary()
                        }
                    }
                    .show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun itemOptions(entry: LibraryItem) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += (if (entry.shelf.isEmpty()) "Mover para prateleira…" else "Prateleira: ${entry.shelf}…") to { chooseShelf(entry) }
        if (!entry.archived) {
            options += (if (entry.finished) "Voltar para \"Lendo agora\"" else "Marcar como concluído") to {
                database.setFinished(entry.id, !entry.finished)
                showLibrary()
            }
        }
        val others = AppTabs.all(this).filter { !it.isVideo && it.id != entry.tab }
        if (others.isNotEmpty()) options += "Mover para outra aba…" to {
            AlertDialog.Builder(this)
                .setTitle("Mover para a aba")
                .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                    database.setTab(entry.id, others[which].id)
                    Toast.makeText(this, "\"${displayTitle(entry.name)}\" foi para a aba \"${others[which].name}\".", Toast.LENGTH_SHORT).show()
                    showLibrary()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        if (TelegramCloud.connected(this) && !entry.archived && AppTabs.cloudOf(this, entry.tab).first) {
            val inCloud = cloudBooks?.any { it.key == keyOf(entry) } == true
            options += (if (inCloud) "☁ Já está na nuvem (Telegram)" else "☁ Enviar para a nuvem (Telegram)") to {
                if (inCloud) Toast.makeText(this, "Este livro já está ${cloudPlace()}.", Toast.LENGTH_SHORT).show() else uploadToCloud(entry)
            }
        }
        if (entry.finished && !entry.archived) options += "Liberar espaço (manter na Estante)…" to { confirmFreeSpace(entry) }
        options += "Trocar capa…" to { chooseCover(entry) }
        if (entry.readingSeconds > 0) options += "Apagar tempo de leitura (⏱ ${ReadingTime.format(entry.readingSeconds)})…" to { confirmClearBookTime(entry) }
        if (BookFiles.hasCustomCover(this, entry.id)) options += "Restaurar capa original" to {
            BookFiles.removeCustomCover(this, entry.id)
            showLibrary()
        }
        options += (if (entry.archived) "Remover da Estante" else "Remover da biblioteca") to { confirmRemove(entry) }
        // Na Estante de madeira os livros não mostram texto: data, arquivo e tempo aparecem aqui.
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(6))
            addView(label(displayTitle(entry.name), 18f, ink, true))
            addView(label(statusText(entry), 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        }
        AlertDialog.Builder(this)
            .setCustomTitle(heading)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Livro concluído: apaga o arquivo para liberar espaço e mantém capa, nome, datas, tempo,
     * anotações e glossário na Estante.
     */
    private fun confirmFreeSpace(entry: LibraryItem) {
        val owned = BookFiles.isOwnedCopy(this, entry.uri)
        val deleteOriginal = android.widget.CheckBox(this).apply {
            text = "Apagar também o arquivo do celular"
            isChecked = true
            setTextColor(ink)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(label("\"${displayTitle(entry.name)}\" continua na Estante com a capa, o nome, as datas, as anotações e o glossário, mas o arquivo é apagado e o livro não abre mais.", 13f, muted))
            addView(label(
                if (owned) "O arquivo guardado no app será apagado."
                else "Para ler de novo, é só importar ou abrir o arquivo outra vez: ele volta para o mesmo lugar.",
                12f, muted
            ).apply { setPadding(0, dp(8), 0, dp(4)) })
            if (!owned) addView(deleteOriginal)
        }
        AlertDialog.Builder(this)
            .setTitle("Liberar espaço?")
            .setView(body)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Liberar espaço") { _, _ ->
                val wantsOriginal = owned || deleteOriginal.isChecked
                background.execute {
                    val result = IncomingBooks.freeSpace(applicationContext, database, entry, wantsOriginal)
                    runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        val message = when (result) {
                            IncomingBooks.Freed.FILE_DELETED -> "Arquivo apagado. \"${displayTitle(entry.name)}\" continua na Estante."
                            IncomingBooks.Freed.ORIGINAL_KEPT -> "Removido do app; o arquivo continua no celular. O livro segue na Estante."
                            IncomingBooks.Freed.ORIGINAL_NOT_ALLOWED -> "O livro segue na Estante, mas o app não tem permissão para apagar o arquivo do celular (importado antes desta versão). Apague \"${entry.name}\" pelo app Arquivos."
                        }
                        AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show()
                        showLibrary()
                    }
                }
            }
            .show()
    }

    private fun confirmRemove(entry: LibraryItem) {
        val notes = database.notesFor(entry.id).size
        AlertDialog.Builder(this)
            .setTitle("Remover da biblioteca?")
            .setMessage(entry.name + if (notes > 0) "\n\nAs $notes anotação(ões) deste livro também serão apagadas." else "")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ -> removeItem(entry) }
            .show()
    }

    private fun chooseShelf(entry: LibraryItem) {
        val existing = database.shelves()
        val options = mutableListOf<String>()
        options += existing.map { "📁 $it" }
        options += "＋ Nova prateleira…"
        if (entry.shelf.isNotEmpty()) options += "Tirar da prateleira"
        AlertDialog.Builder(this)
            .setTitle("Mover para prateleira")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which < existing.size -> moveToShelf(entry, existing[which])
                    which == existing.size -> askText("Nova prateleira", "Nome da prateleira", "") { typed ->
                        moveToShelf(entry, Shelves.resolve(typed, existing))
                    }
                    else -> moveToShelf(entry, "")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun moveToShelf(entry: LibraryItem, shelf: String) {
        database.setShelf(entry.id, shelf)
        if (shelf.isNotEmpty()) {
            openShelves += shelf
            openEstante += shelf
        }
        showLibrary()
    }

    private fun renameShelf(name: String) {
        askText("Renomear prateleira", "Novo nome", name) { typed ->
            val target = Shelves.resolve(typed, database.shelves() - name)
            database.renameShelf(name, target)
            if (openShelves.remove(name)) openShelves += target
            if (openEstante.remove(name)) openEstante += target
            showLibrary()
        }
    }

    private fun askText(title: String, hint: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            maxLines = 1
            setText(initial)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(FrameLayout(this).apply {
                setPadding(dp(22), dp(8), dp(22), 0)
                addView(input)
            })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) done(text)
            }
            .show()
    }

    /** Capas e total de páginas dos documentos que ainda não passaram por isso. */
    private fun prepareMissing(items: List<LibraryItem>) {
        items.filter { it.format in COVER_FORMATS && it.id !in preparing && !BookFiles.coverAttempted(this, it.id) }
            .forEach { item ->
                preparing += item.id
                background.execute {
                    BookFiles.prepare(applicationContext, database, item)
                    runOnUiThread {
                        if (!destroyed && section == SECTION_LIBRARY) showLibrary()
                    }
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun chooseDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            // Escrita também: permite apagar o arquivo depois, ao liberar espaço de um livro concluído.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            // Alguns aparelhos (TVs, sistemas modificados) não têm o seletor de documentos.
            Toast.makeText(this, "Este aparelho não tem um seletor de arquivos disponível.", Toast.LENGTH_LONG).show()
        }
    }

    /** Abre a galeria (ou o seletor de imagens) para escolher a capa de [entry]. */
    @Suppress("DEPRECATION")
    private fun chooseCover(entry: LibraryItem) {
        pendingCoverId = entry.id
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Escolher capa"), REQUEST_COVER)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Este aparelho não tem uma galeria disponível.", Toast.LENGTH_LONG).show()
        }
    }

    private fun receiveCover(image: Uri) {
        val id = pendingCoverId
        pendingCoverId = 0L
        if (id == 0L) return
        Thread({
            val ok = BookFiles.setCustomCover(this, id, image)
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (!ok) Toast.makeText(this, "Não foi possível usar essa imagem como capa.", Toast.LENGTH_LONG).show()
                if (section == SECTION_LIBRARY) showLibrary()
            }
        }, "capa").start()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_COVER) {
            val image = data?.data
            if (resultCode == RESULT_OK && image != null) receiveCover(image) else pendingCoverId = 0L
            return
        }
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK) return
        val result = data ?: return

        val uris = mutableListOf<Uri>()
        result.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        if (uris.isEmpty()) result.data?.let { uris += it }

        var added = 0
        var rejected = 0
        var restored = 0
        uris.forEach { uri ->
            val (described, size) = IncomingBooks.describe(this, uri)
            val name = queryDisplayName(uri) ?: described
            var format = Formats.detect(name, contentResolver.getType(uri))
            if (format == null) {
                rejected++
                return@forEach
            }
            if (format == "cbr" && isZip(uri)) format = "cbz" // CBR que é ZIP por dentro abre como CBZ
            keepPermission(uri)
            val same = database.findByNameAndSize(name, size)
            when {
                // Livro arquivado na Estante: o arquivo importado de novo o traz de volta, no mesmo lugar.
                same != null && same.archived -> {
                    try {
                        database.relink(same.id, uri.toString(), format, size)
                        restored++
                    } catch (e: Exception) {
                        // Mesmo endereço já em outro registro: segue como "já está na biblioteca".
                    }
                }
                same != null -> Unit
                database.add(name, uri.toString(), format, size, AppTabs.importTab(this)) != null -> added++
            }
        }
        if (restored > 0) {
            Toast.makeText(this, "$restored livro(s) da Estante voltaram a ter arquivo.", Toast.LENGTH_LONG).show()
        }
        showLibrary()

        val message = when {
            added > 0 && rejected > 0 -> "$added adicionado(s). $rejected com formato não reconhecido."
            added > 0 -> "$added documento(s) adicionado(s) à biblioteca"
            rejected > 0 -> "Formato não reconhecido. Os formatos aceitos estão listados na biblioteca."
            else -> "Esse documento já está na biblioteca."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Guarda a permissão (leitura e, se o provedor deu, escrita) para abrir o livro depois. */
    private fun keepPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: SecurityException) {
                // Nem todo provedor oferece permissão persistente; o item entra mesmo assim.
            }
        }
    }

    private fun isZip(uri: Uri): Boolean = try {
        looksLikeZip(BookFiles.header(this, uri, 4))
    } catch (e: Exception) {
        false
    }

    private fun queryDisplayName(uri: Uri): String? {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: Exception) {
            // Cai no fallback abaixo.
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun openItem(entry: LibraryItem, position: Int? = null) {
        if (entry.archived) {
            AlertDialog.Builder(this)
                .setTitle(displayTitle(entry.name))
                .setMessage("O arquivo deste livro foi apagado para liberar espaço. Para ler de novo, importe ou abra o arquivo outra vez: ele volta para o mesmo lugar, com as anotações e o glossário.")
                .setPositiveButton("Importar", { _, _ -> chooseDocument() })
                .setNegativeButton("Fechar", null)
                .show()
            return
        }
        if (entry.format in Formats.readable) {
            refreshOnResume = true
            val intent = Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, entry.id)
            if (position != null) intent.putExtra(ReaderActivity.EXTRA_POSITION, position)
            startActivity(intent)
        } else {
            Toast.makeText(this, "O leitor de ${entry.format.uppercase()} ainda não existe.", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeItem(entry: LibraryItem) {
        try {
            // Libera a permissão persistida: o Android limita quantas ela pode guardar por app.
            contentResolver.releasePersistableUriPermission(
                Uri.parse(entry.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Sem permissão persistida para liberar; segue com a remoção.
        }
        val owned = if (BookFiles.isOwnedCopy(this, entry.uri)) File(Uri.parse(entry.uri).path!!) else null
        database.remove(entry.id)
        background.execute {
            BookFiles.delete(applicationContext, entry.id)
            owned?.delete() // cópia recebida de outro app: o arquivo é do Alexandria
        }
        showLibrary()
    }

    // ---------- Notas ----------

    private fun showNotes() {
        setContentView(baseScreen(SECTION_NOTES))
        val notes = database.allNotes()
        val items = database.all().associateBy { it.id }
        sectionTitle("Minhas anotações", "${notes.size} anotação(ões) em ${notes.map { it.itemId }.distinct().size} livro(s)")
        addGlossaries(items)
        if (notes.isEmpty()) {
            content.addView(
                emptyPanel(
                    "✎",
                    "Nenhuma anotação ainda",
                    "No leitor, selecione um trecho e toque em \"Anotar\". Em PDF e quadrinhos, use o botão 📝 para anotar a página."
                ),
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }
        // Agrupa por livro, na ordem da anotação mais recente de cada um.
        for ((itemId, bookNotes) in notes.groupBy { it.itemId }) {
            val item = items[itemId] ?: continue
            content.addView(label(displayTitle(item.name), 15f, ink, true).apply { setPadding(0, dp(10), 0, dp(6)) })
            bookNotes.sortedBy { it.position }.forEach { note -> addNoteCard(item, note) }
        }
        spacer(8)
        content.addView(label("Toque para abrir o livro no trecho. Mantenha pressionado para remover.", 11f, muted))
    }

    /** Glossários dos livros (termos definidos durante a leitura), recolhidos por livro. */
    private fun addGlossaries(items: Map<Long, LibraryItem>) {
        val terms = database.allTerms().filter { it.itemId in items }
        if (terms.isEmpty()) return
        content.addView(label("GLOSSÁRIOS", 10f, muted, true).apply { setPadding(0, dp(4), 0, dp(6)) })
        for ((itemId, bookTerms) in terms.groupBy { it.itemId }) {
            val item = items.getValue(itemId)
            val open = itemId in openGlossaries
            content.addView(label("${if (open) "▼" else "▶"}  📖 ${displayTitle(item.name)} (${bookTerms.size})", 14f, ink, true).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (!openGlossaries.remove(itemId)) openGlossaries += itemId
                    showNotes()
                }
            })
            if (!open) continue
            bookTerms.forEach { term ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(10), dp(15), dp(10))
                    background = rounded(Color.WHITE, 12)
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remover \"${term.term}\" do glossário?")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Remover") { _, _ ->
                                database.removeTerm(term.id)
                                showNotes()
                            }
                            .show()
                        true
                    }
                }
                card.addView(label(term.term, 14f, ink, true))
                if (term.definition.isNotBlank()) card.addView(label(term.definition, 13f, muted).apply { setPadding(0, dp(3), 0, 0) })
                content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        spacer(14)
    }

    private val openGlossaries = mutableSetOf<Long>()

    private fun addNoteCard(item: LibraryItem, note: Note) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(Color.WHITE, 14)
            setOnClickListener { openItem(item, note.position) }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Remover anotação?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Remover") { _, _ ->
                        database.removeNote(note.id)
                        showNotes()
                    }
                    .show()
                true
            }
        }
        val where = NoteLocation.describe(item.format, note.position, item.pageCount)
        card.addView(label("$where · ${dateFormat.format(Date(note.createdAt))}", 10f, gold, true))
        if (note.excerpt.isNotEmpty()) {
            card.addView(label("“${note.excerpt.take(300)}${if (note.excerpt.length > 300) "…" else ""}”", 13f, ink).apply {
                setTypeface(Typeface.SERIF, Typeface.ITALIC)
                setPadding(0, dp(6), 0, 0)
            })
        }
        if (note.text.isNotEmpty()) card.addView(label(note.text, 13f, muted).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    // ---------- Estatísticas ----------

    private fun showStats() {
        setContentView(baseScreen(SECTION_STATS))
        val items = database.all()
        val notes = database.allNotes()
        sectionTitle("Estatísticas", "Um resumo da sua biblioteca.")
        val readingDays = database.readingDays()
        val today = Days.today()
        val streak = Streak.summary(readingDays, today)
        statTiles(listOf(
            "🔥 ${streak.current}" to (if (streak.current == 1) "dia seguido" else "dias seguidos"),
            "${streak.best}" to "melhor sequência",
            ReadingTime.format(streak.todaySeconds) to "lidos hoje",
            ReadingTime.format(readingDays.values.sum()) to "no total"
        ))
        content.addView(label("Um dia conta para a sequência a partir de ${ReadingTime.format(Streak.MIN_SECONDS)} de leitura. O tempo para de contar se o livro fica aberto 5 minutos sem nenhum toque.", 11f, muted).apply {
            setPadding(0, 0, 0, dp(10))
        })
        addMonthCalendar(readingDays, today)
        if (readingDays.isNotEmpty() || items.any { it.readingSeconds > 0 }) {
            content.addView(label("Limpar estatísticas…", 13f, gold, true).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { confirmClearStatistics() }
            })
        }
        content.addView(label("BIBLIOTECA", 10f, muted, true).apply { setPadding(0, dp(16), 0, dp(6)) })
        val tiles = listOf(
            items.size.toString() to "na biblioteca",
            items.count { !it.finished && (it.openedAt > 0) }.toString() to "em leitura",
            items.count { it.finished }.toString() to "concluídos",
            notes.size.toString() to "anotações"
        )
        statTiles(tiles)
        val reading = items.filter { it.readingSeconds >= 60 }.sortedByDescending { it.readingSeconds }.take(5)
        if (reading.isNotEmpty()) {
            content.addView(label("MAIS TEMPO DE LEITURA", 10f, muted, true).apply { setPadding(0, dp(14), 0, dp(6)) })
            reading.forEach { item ->
                content.addView(label("${displayTitle(item.name)}  ·  ${ReadingTime.format(item.readingSeconds)}", 13f, ink).apply {
                    setPadding(0, dp(2), 0, dp(2))
                    minHeight = dp(40)
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    contentDescription = "${displayTitle(item.name)}, ${ReadingTime.format(item.readingSeconds)}. Toque para apagar o tempo deste livro."
                    setOnClickListener { confirmClearBookTime(item) }
                })
            }
            content.addView(label("Toque num livro para apagar só o tempo dele.", 11f, muted).apply { setPadding(0, dp(2), 0, 0) })
        }
        if (items.isNotEmpty()) {
            content.addView(label("POR FORMATO", 10f, muted, true).apply { setPadding(0, dp(14), 0, dp(6)) })
            items.groupingBy { it.format }.eachCount().entries.sortedByDescending { it.value }.forEach { (format, count) ->
                content.addView(label("${format.uppercase()}  ·  $count", 13f, ink).apply { setPadding(0, dp(2), 0, dp(2)) })
            }
        }
    }

    private fun confirmClearStatistics() {
        AlertDialog.Builder(this)
            .setTitle("Limpar estatísticas?")
            .setMessage("Zera o tempo de leitura (de todos os livros) e a sequência de dias. Livros, progresso, Estante, anotações e glossário não são afetados. Não dá para desfazer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Limpar") { _, _ ->
                database.clearStatistics()
                Toast.makeText(this, "Estatísticas zeradas.", Toast.LENGTH_SHORT).show()
                showStats()
            }
            .show()
    }

    private fun statTiles(tiles: List<Pair<String, String>>) {
        tiles.chunked(2).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, (value, caption) ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = rounded(Color.WHITE, 14)
                    addView(label(value, 26f, ink, true))
                    addView(label(caption, 12f, muted))
                }
                line.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = dp(9) })
            }
            content.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    /** Calendário do mês: dias com leitura (a partir de 5 min) marcados em dourado. */
    private fun addMonthCalendar(days: Map<String, Long>, today: String) {
        val todayEpoch = Days.parse(today) ?: return
        val (year, month) = today.split('-').let { it[0].toInt() to it[1].toInt() }
        val first = Days.epoch(year, month, 1)
        val next = if (month == 12) Days.epoch(year + 1, 1, 1) else Days.epoch(year, month + 1, 1)
        val monthNames = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.WHITE, 14)
        }
        panel.addView(label("${monthNames[month - 1].replaceFirstChar { it.uppercase() }} de $year", 14f, ink, true).apply {
            setPadding(dp(4), 0, 0, dp(8))
        })
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("D", "S", "T", "Q", "Q", "S", "S").forEach { d ->
            header.addView(label(d, 11f, muted, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(header)
        // 1970-01-01 foi quinta-feira: (dia + 4) % 7 dá 0 = domingo.
        val offset = ((first + 4) % 7).toInt()
        var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(offset) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(34), 1f)) }
        var column = offset
        for (day in first until next) {
            val seconds = days[Days.format(day)] ?: 0
            val read = seconds >= Streak.MIN_SECONDS
            val isToday = day == todayEpoch
            row.addView(TextView(this).apply {
                text = (day - first + 1).toString()
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (read) Color.WHITE else if (day > todayEpoch) Color.rgb(190, 190, 190) else ink)
                if (read || isToday) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        if (read) setColor(gold)
                        if (isToday) setStroke(dp(2), ink)
                    }
                }
                contentDescription = "Dia ${day - first + 1}" + if (read) ", leu ${ReadingTime.format(seconds)}" else ""
            }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            column++
            if (column == 7) {
                panel.addView(row)
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                column = 0
            }
        }
        if (column > 0) {
            repeat(7 - column) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(34), 1f)) }
            panel.addView(row)
        }
        content.addView(panel, LinearLayout.LayoutParams(-1, -2))
    }

    // ---------- Ajustes ----------

    private fun showSettings() {
        setContentView(baseScreen(SECTION_SETTINGS))
        sectionTitle("Ajustes", "Sobre o app, armazenamento e licenças.")

        settingsPanel("Sobre o Alexandria") {
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
            addView(label("Versão $version", 13f, ink))
            addView(label("Leitor de PDF, EPUB, MOBI, AZW3, DJVU, CBZ, CBR, DOCX e TXT. Sem anúncios e sem conta: seus livros e anotações ficam só neste aparelho. A internet só é usada para procurar atualizações do app e, se você ligar, pela nuvem do Telegram e pela sincronização pelo GitHub.", 12f, muted).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }

        settingsPanel("Atualizações") {
            val last = AppUpdates.lastCheck(this@MainActivity)
            val lastText = if (last > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(last)) else "ainda não"
            addView(label("Versão instalada: ${AppUpdates.installedVersion(this@MainActivity)}. Última verificação: $lastText.", 13f, ink))
            addView(label("As versões novas são publicadas no GitHub do projeto. O app baixa, confere que é o Alexandria assinado com a mesma chave e abre o instalador do Android, que pede a sua confirmação. Biblioteca, notas e marcadores continuam como estão.", 12f, muted).apply {
                setPadding(0, dp(6), 0, dp(4))
            })
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = "Procurar atualização uma vez por dia"
                setTextColor(ink)
                minHeight = dp(44)
                isChecked = AppUpdates.enabled(this@MainActivity)
                setOnCheckedChangeListener { _, on -> AppUpdates.setEnabled(this@MainActivity, on) }
            })
            addView(Button(this@MainActivity).apply {
                text = "Procurar atualização agora"
                setAllCaps(false)
                setTextColor(Color.WHITE)
                background = rounded(ink, 10)
                setOnClickListener { checkUpdateNow() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }

        settingsPanel("Nuvem pelo Telegram") {
            val tg = TelegramCloud.settings(this@MainActivity)
            if (tg == null) {
                addView(label("Guarde seus livros nas Mensagens salvas da sua própria conta do Telegram e baixe em outro aparelho (celular ou site do Alexandria). A posição de leitura também passa a ir por lá. Para entrar, você usa o seu próprio api_id e api_hash, criados em my.telegram.org.", 12f, muted).apply {
                    setPadding(0, 0, 0, dp(6))
                })
                addView(label(TELEGRAM_NOTICE, 11f, muted).apply { setPadding(0, 0, 0, dp(10)) })
                addView(Button(this@MainActivity).apply {
                    text = "Entrar no Telegram"
                    setAllCaps(false)
                    setTextColor(Color.WHITE)
                    background = rounded(ink, 10)
                    setOnClickListener { askTelegramApi() }
                }, LinearLayout.LayoutParams(-1, dp(48)))
            } else {
                addView(label("Conectado${if (tg.name.isNotBlank()) " como ${tg.name}" else ""}. Os livros enviados para a nuvem levam a etiqueta #alexandria_livro.", 13f, ink))
                if (tg.error != null) addView(label("⚠ ${tg.error}", 12f, Color.rgb(163, 57, 47)).apply { setPadding(0, dp(6), 0, 0) })
                val status = TelegramCloud.positionStatus(this@MainActivity)
                val clock = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                if (status.pushAt > 0 || status.pullAt > 0) {
                    val lines = mutableListOf<String>()
                    if (status.pushAt > 0) lines += if (status.pushError == null) "Posição de leitura gravada na nuvem em ${clock.format(java.util.Date(status.pushAt))}."
                    else "⚠ Não foi possível gravar a posição de leitura (${clock.format(java.util.Date(status.pushAt))}): ${status.pushError}"
                    if (status.pullError != null) lines += "⚠ Não foi possível ler a posição da nuvem (${clock.format(java.util.Date(status.pullAt))}): ${status.pullError}"
                    val bad = status.pushError != null || status.pullError != null
                    addView(label(lines.joinToString("\n"), 12f, if (bad) Color.rgb(163, 57, 47) else muted).apply { setPadding(0, dp(6), 0, 0) })
                }
                addView(label("Para enviar um livro, toque e segure nele na biblioteca e escolha \"Enviar para a nuvem\". Um arquivo de livro mandado pelo próprio Telegram também entra se a legenda tiver #alexandria_livro. Os livros da nuvem que não estão neste aparelho aparecem na biblioteca, em \"Na nuvem\". " + TELEGRAM_NOTICE, 12f, muted).apply {
                    setPadding(0, dp(6), 0, dp(10))
                })
                addView(label("Cada aba (livros, filmes, cursos) pode ter o seu grupo privado do Telegram: na Biblioteca, toque em ⋯ ao lado do nome da aba → Grupo da nuvem. A aba principal de livros, sem grupo, usa as suas Mensagens salvas.", 12f, muted).apply {
                    setPadding(0, 0, 0, dp(10))
                })
                if (PositionSync.settings(this@MainActivity) != null) {
                    addView(label("A posição de leitura vai por:", 13f, ink, true))
                    val group = android.widget.RadioGroup(this@MainActivity).apply { orientation = android.widget.RadioGroup.VERTICAL }
                    val current = PositionSync.provider(this@MainActivity)
                    listOf("telegram" to "Telegram (Mensagens salvas)", "github" to "GitHub (gist secreto)").forEach { (id, text) ->
                        group.addView(android.widget.RadioButton(this@MainActivity).apply {
                            this.text = text
                            setTextColor(ink)
                            minHeight = dp(44)
                            isChecked = current == id
                            setOnClickListener { PositionSync.setProvider(this@MainActivity, id) }
                        })
                    }
                    addView(group)
                }
                addView(Button(this@MainActivity).apply {
                    text = "Sair do Telegram"
                    setAllCaps(false)
                    setTextColor(ink)
                    background = rounded(Color.rgb(236, 233, 226), 10)
                    setOnClickListener {
                        TelegramCloud.logout(this@MainActivity) {
                            cloudBooks = null
                            if (destroyed) return@logout
                            Toast.makeText(this@MainActivity, "Você saiu do Telegram neste aparelho. Os livros continuam nas suas Mensagens salvas.", Toast.LENGTH_LONG).show()
                            if (section == SECTION_SETTINGS) showSettings()
                        }
                    }
                }, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }

        settingsPanel("Sincronizar a posição pelo GitHub") {
            val sync = PositionSync.settings(this@MainActivity)
            if (sync == null) {
                addView(label("Leia no celular e continue no computador (no site do Alexandria) de onde parou, e vice-versa. Os arquivos não viajam: carregue o mesmo livro nos dois. Só a posição vai, num gist secreto da sua conta do GitHub, com um código no lugar do nome do livro.", 12f, muted).apply {
                    setPadding(0, 0, 0, dp(10))
                })
                addView(Button(this@MainActivity).apply {
                    text = "Conectar com o GitHub"
                    setAllCaps(false)
                    setTextColor(Color.WHITE)
                    background = rounded(ink, 10)
                    setOnClickListener { askSyncToken() }
                }, LinearLayout.LayoutParams(-1, dp(48)))
            } else {
                val last = if (sync.lastSync > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(sync.lastSync)) else "ainda não"
                addView(label("Conectado como @${sync.login}. Última sincronização: $last.", 13f, ink))
                if (sync.error != null) addView(label("⚠ ${sync.error} Desconecte e conecte de novo com um token novo.", 12f, Color.rgb(163, 57, 47)).apply { setPadding(0, dp(6), 0, 0) })
                addView(label("Ao abrir um livro, se ele foi lido depois em outro aparelho, o app continua de lá (com a opção de voltar). Ao sair do livro, e a cada 3 minutos de leitura, a posição daqui é gravada.", 12f, muted).apply {
                    setPadding(0, dp(6), 0, dp(10))
                })
                addView(Button(this@MainActivity).apply {
                    text = "Desconectar"
                    setAllCaps(false)
                    setTextColor(ink)
                    background = rounded(Color.rgb(236, 233, 226), 10)
                    setOnClickListener {
                        PositionSync.disconnect(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Sincronização desligada. O gist continua na sua conta do GitHub.", Toast.LENGTH_LONG).show()
                        showSettings()
                    }
                }, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }

        settingsPanel("Conforto visual") {
            addView(label("Cor da página, brilho, luz quente, sobreposição colorida, fonte e espaçamentos, e a régua de leitura. Também dá para abrir durante a leitura, no menu ⋮.", 12f, muted).apply {
                setPadding(0, 0, 0, dp(10))
            })
            addView(Button(this@MainActivity).apply {
                text = "Ajustar conforto visual"
                setAllCaps(false)
                setTextColor(Color.WHITE)
                background = rounded(ink, 10)
                setOnClickListener { ComfortPanel.show(this@MainActivity) }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }

        settingsPanel("Armazenamento") {
            val cache = File(cacheDir, "books")
            addView(label("Cópias e conversões em cache: ${formatSize(folderSize(cache))}", 13f, ink))
            addView(label("EPUB, MOBI, DJVU, quadrinhos e DOCX são copiados (e o MOBI, convertido) na primeira leitura. Liberar o espaço não apaga livros, progresso nem anotações; as cópias são refeitas quando o livro for aberto de novo.", 12f, muted).apply {
                setPadding(0, dp(6), 0, dp(10))
            })
            addView(Button(this@MainActivity).apply {
                text = "Liberar espaço"
                setAllCaps(false)
                setTextColor(Color.WHITE)
                background = rounded(ink, 10)
                setOnClickListener {
                    this@MainActivity.background.execute {
                        cache.deleteRecursively()
                        File(cacheDir, "engines").deleteRecursively()
                        runOnUiThread {
                            if (!destroyed) {
                                Toast.makeText(this@MainActivity, "Espaço liberado.", Toast.LENGTH_SHORT).show()
                                if (section == SECTION_SETTINGS) showSettings()
                            }
                        }
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }

        settingsPanel("Licenças de código aberto") {
            addView(label("PDF, MOBI/AZW3, DJVU e CBR são lidos pelos motores EraPDF, EraMOBI, EraDjVu e EraComic do projeto OpenReadEra (ReadEra), que são software livre. O código-fonte deles acompanha o código do Alexandria.", 12f, muted).apply {
                setPadding(0, 0, 0, dp(8))
            })
            LICENSES.forEach { (title, file) ->
                addView(label("›  $title", 14f, gold, true).apply {
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { showLicense(title, file) }
                })
            }
        }
    }

    // ---------- abas ----------

    /** Fileira de abas no topo da Biblioteca: tocar abre, segurar mostra as opções, "＋" cria. */
    private fun addTabStrip(current: AppTab) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        fun chip(text: String, active: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) = label(text, 14f, if (active) Color.WHITE else ink, active).apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(if (active) ink else Color.WHITE, 20)
            setOnClickListener { onClick() }
            onLong?.let { long -> setOnLongClickListener { long(); true } }
        }
        for (tab in AppTabs.all(this)) {
            row.addView(chip(tab.name, tab.id == current.id, {
                if (tab.id != current.id) {
                    AppTabs.setCurrent(this, tab.id)
                    cloudBooks = null
                    cloudError = null
                    cloudAds = emptyList()
                    videos = null
                    showLibrary()
                }
            }, { tabOptions(tab) }), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        row.addView(chip("＋", false, { newTab() }).apply { contentDescription = "Nova aba" })
        content.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Título da aba com o "⋯" das opções. */
    private fun tabTitle(tab: AppTab, subtitle: String) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(if (tab.isMain && tab.name == "Livros") "Minha biblioteca" else tab.name, 21f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("⋯", 24f, ink, true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                contentDescription = "Opções da aba ${tab.name}"
                setOnClickListener { tabOptions(tab) }
            })
        })
        content.addView(label(subtitle, 13f, muted).apply { setPadding(0, dp(4), 0, dp(14)) })
    }

    private fun newTab() {
        val types = AppTabs.TYPES
        AlertDialog.Builder(this)
            .setTitle("Nova aba: o que ela guarda?")
            .setItems(types.map { "${it.second} — ${it.third}" }.toTypedArray()) { _, which ->
                val type = types[which].first
                val input = EditText(this).apply {
                    setText(types[which].second)
                    selectAll()
                    maxLines = 1
                    hint = "Por exemplo: Filmes da família, Cursos, HQs"
                }
                AlertDialog.Builder(this)
                    .setTitle("Nome da aba")
                    .setView(LinearLayout(this).apply {
                        setPadding(dp(22), 0, dp(22), 0)
                        addView(input, LinearLayout.LayoutParams(-1, -2))
                    })
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Criar") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) return@setPositiveButton
                        val tabs = AppTabs.all(this)
                        val tab = AppTab(AppTabs.newId(name, tabs.map { it.id }), name, type, null)
                        AppTabs.save(this, tabs + tab)
                        AppTabs.setCurrent(this, tab.id)
                        videos = null
                        showLibrary()
                        if (tab.isVideo) {
                            if (TelegramCloud.connected(this)) chooseGroup(tab)
                            else Toast.makeText(this, "Para ver os vídeos, entre no Telegram em Ajustes e depois escolha o grupo da aba.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun tabOptions(tab: AppTab) {
        val where = tab.chat?.let { "grupo \"${it.title}\"" } ?: if (tab.isMain) "nuvem nas Mensagens salvas" else "sem grupo"
        val options = mutableListOf<Pair<String, () -> Unit>>(
            "Renomear" to { renameTab(tab) },
            "Grupo da nuvem…" to { groupOptions(tab) }
        )
        if (!tab.isMain) options += "Apagar aba" to { deleteTab(tab) }
        AlertDialog.Builder(this)
            .setTitle("${tab.name} · ${AppTabs.typeLabel(tab.type)} · $where")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun renameTab(tab: AppTab) {
        val input = EditText(this).apply {
            setText(tab.name)
            selectAll()
            maxLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle("Renomear aba")
            .setView(LinearLayout(this).apply {
                setPadding(dp(22), 0, dp(22), 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    AppTabs.update(this, tab.id) { it.copy(name = name) }
                    showLibrary()
                }
            }
            .show()
    }

    private fun deleteTab(tab: AppTab) {
        AlertDialog.Builder(this)
            .setTitle("Apagar a aba \"${tab.name}\"?")
            .setMessage(if (tab.isVideo) "Os vídeos continuam no grupo do Telegram." else "Os livros dela voltam para a aba principal; nada é apagado.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ ->
                if (!tab.isVideo) database.moveTab(tab.id)
                AppTabs.save(this, AppTabs.all(this).filter { it.id != tab.id })
                AppTabs.setCurrent(this, AppTabs.MAIN)
                cloudBooks = null
                showLibrary()
            }
            .show()
    }

    /** Grupo da nuvem da aba: convidar, trazer das Mensagens salvas, trocar ou largar. */
    private fun groupOptions(tab: AppTab) {
        if (!TelegramCloud.connected(this)) {
            Toast.makeText(this, "Entre no Telegram em Ajustes (Nuvem pelo Telegram) para usar um grupo.", Toast.LENGTH_LONG).show()
            return
        }
        val chat = tab.chat ?: return chooseGroup(tab)
        val options = mutableListOf<Pair<String, () -> Unit>>("Copiar link de convite (para outras contas)" to { shareInviteLink(chat) })
        if (tab.isMain) options += "Trazer livros das Mensagens salvas" to { copySavedBooks(chat) }
        options += "Trocar de grupo" to { chooseGroup(tab) }
        val leave = if (tab.isMain) "Voltar para as Mensagens salvas" else "Deixar a aba sem grupo"
        options += leave to {
            AlertDialog.Builder(this)
                .setTitle("$leave?")
                .setMessage("Os arquivos continuam no grupo do Telegram.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("OK") { _, _ ->
                    AppTabs.update(this, tab.id) { it.copy(chat = null) }
                    cloudBooks = null
                    videos = null
                    showSection(section)
                }
                .show()
        }
        AlertDialog.Builder(this)
            .setTitle("Grupo \"${chat.title}\"")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /** Escolhe o grupo da aba: criar, entrar pelo link ou um que a conta já tem. */
    private fun chooseGroup(tab: AppTab) {
        val options = listOf<Pair<String, () -> Unit>>(
            "Criar grupo privado" to { groupChosen(tab, "Criando o grupo…") { done -> TelegramCloud.createGroup(this, "Alexandria — ${tab.name}", done) } },
            "Entrar com link de convite" to { askInviteLink(tab) },
            "Usar um grupo que já tenho" to { pickExistingGroup(tab) }
        )
        AlertDialog.Builder(this)
            .setTitle("Grupo da aba \"${tab.name}\"")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun groupChosen(tab: AppTab, waiting: String, start: ((Result<CloudChat>) -> Unit) -> Unit) {
        Toast.makeText(this, waiting, Toast.LENGTH_SHORT).show()
        start { result ->
            if (destroyed) return@start
            result.onSuccess { chat ->
                AppTabs.update(this, tab.id) { it.copy(chat = chat) }
                cloudBooks = null
                videos = null
                Toast.makeText(this, "A aba \"${tab.name}\" agora usa o grupo \"${chat.title}\".", Toast.LENGTH_LONG).show()
                showSection(section)
                if (tab.isMain) AlertDialog.Builder(this)
                    .setTitle("Trazer os livros para o grupo?")
                    .setMessage("Os livros e posições que estão nas suas Mensagens salvas são copiados para o grupo (só os que ainda não estão lá). Nada é apagado das Mensagens salvas.")
                    .setNegativeButton("Agora não", null)
                    .setPositiveButton("Trazer") { _, _ -> copySavedBooks(chat) }
                    .show()
            }.onFailure { Toast.makeText(this, it.message ?: "Não foi possível usar o grupo.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun askInviteLink(tab: AppTab) {
        val input = EditText(this).apply {
            hint = "https://t.me/+…"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        (getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString()
            ?.takeIf { it.contains("t.me/") }?.let { input.setText(it.trim()) }
        AlertDialog.Builder(this)
            .setTitle("Entrar com link de convite")
            .setMessage("Cole o link de convite do grupo (gerado nas opções da aba, na conta que criou o grupo).")
            .setView(LinearLayout(this).apply {
                setPadding(dp(22), 0, dp(22), 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                val link = input.text.toString().trim()
                if (link.isNotEmpty()) groupChosen(tab, "Entrando no grupo…") { done -> TelegramCloud.joinGroup(this, link, done) }
            }
            .show()
    }

    private fun pickExistingGroup(tab: AppTab) {
        Toast.makeText(this, "Procurando seus grupos…", Toast.LENGTH_SHORT).show()
        TelegramCloud.groups(this) { result ->
            if (destroyed) return@groups
            result.onSuccess { groups ->
                if (groups.isEmpty()) {
                    Toast.makeText(this, "Esta conta não está em nenhum grupo.", Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                AlertDialog.Builder(this)
                    .setTitle("Escolha o grupo da aba")
                    .setItems(groups.map { it.title }.toTypedArray()) { _, which ->
                        val chat = groups[which]
                        groupChosen(tab, "Usando \"${chat.title}\"…") { done -> done(Result.success(chat)) }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }.onFailure { Toast.makeText(this, it.message ?: "Não foi possível listar os grupos.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun shareInviteLink(chat: CloudChat) {
        TelegramCloud.inviteLink(this, chat) { result ->
            if (destroyed) return@inviteLink
            result.onSuccess { link ->
                (getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Convite do grupo", link))
                AlertDialog.Builder(this)
                    .setTitle("Link de convite")
                    .setMessage("$link\n\nCopiado. Abra o link na outra conta (no Telegram) ou cole nas opções da aba do Alexandria com ela conectada. Não compartilhe: quem entrar vê tudo.")
                    .setPositiveButton("OK", null)
                    .show()
            }.onFailure { Toast.makeText(this, it.message ?: "Não foi possível gerar o link.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun copySavedBooks(chat: CloudChat) {
        Toast.makeText(this, "Trazendo os livros das Mensagens salvas…", Toast.LENGTH_SHORT).show()
        TelegramCloud.copyFromSaved(this, chat) { result ->
            if (destroyed) return@copyFromSaved
            result.onSuccess { n ->
                cloudBooks = null
                Toast.makeText(this, if (n > 0) "$n livro(s) copiado(s) para o grupo." else "Os livros das Mensagens salvas já estavam no grupo.", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this, it.message ?: "Não foi possível copiar os livros.", Toast.LENGTH_LONG).show() }
        }
    }

    // ---------- abas de vídeos (filmes e cursos) ----------

    private var videos: List<CloudVideo>? = null
    private var videosError: String? = null
    private var videosLoading = false

    /** Nesta etapa só a lista dos vídeos do grupo; o player e as playlists por tópicos vêm depois. */
    private fun showVideoTab(tab: AppTab) {
        tabTitle(tab, "Aba de ${AppTabs.typeLabel(tab.type).lowercase()}${tab.chat?.let { " · grupo \"${it.title}\"" } ?: ""}. " +
            if (tab.type == AppTabs.COURSES) "Cada tópico do grupo será um curso, com as aulas em ordem." else "Os tópicos do grupo serão coleções.")
        if (!TelegramCloud.connected(this)) {
            content.addView(label("Entre no Telegram em Ajustes (Nuvem pelo Telegram) e depois escolha o grupo desta aba.", 13f, muted).apply {
                setOnClickListener { showSection(SECTION_SETTINGS) }
            })
            return
        }
        val chat = tab.chat
        if (chat == null) {
            content.addView(label("Esta aba ainda não tem grupo. Crie um grupo privado ou entre num que já existe: tudo o que for enviado lá (por você ou por quem estiver no grupo, mesmo sem o Alexandria) aparece aqui.", 13f, muted).apply {
                setPadding(0, 0, 0, dp(10))
            })
            content.addView(Button(this).apply {
                text = "Escolher o grupo da aba"
                setAllCaps(false)
                setTextColor(Color.WHITE)
                background = rounded(ink, 10)
                setOnClickListener { chooseGroup(tab) }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            return
        }
        val list = videos
        if (list == null && videosError == null) {
            content.addView(label("Procurando os vídeos do grupo…", 12f, muted))
            if (!videosLoading) {
                videosLoading = true
                TelegramCloud.videos(this, chat) { result ->
                    videosLoading = false
                    if (destroyed) return@videos
                    result.onSuccess { videos = it; videosError = null }.onFailure { videosError = it.message ?: "Não foi possível falar com o Telegram agora." }
                    if (section == SECTION_LIBRARY && AppTabs.current(this).id == tab.id) showLibrary()
                }
            }
            return
        }
        videosError?.let { error ->
            content.addView(label("$error  Toque para tentar de novo.", 12f, muted).apply {
                setOnClickListener {
                    videosError = null
                    videos = null
                    showLibrary()
                }
            })
            return
        }
        content.addView(label(
            if (list!!.isEmpty()) "Nenhum vídeo no grupo ainda. Envie pelo Telegram (como vídeo ou como arquivo)."
            else "${list.size} vídeo(s) no grupo. Assistir por aqui, com streaming, chega na próxima atualização.",
            12f, muted
        ).apply { setPadding(0, 0, 0, dp(8)) })
        for (v in list) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(12), dp(10))
                background = rounded(Color.WHITE, 12)
                addView(label("▶  ${v.name.substringBeforeLast('.')}", 14f, ink, true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val size = if (v.size >= 1_000_000_000) "%.1f GB".format(v.size / 1e9) else "${maxOf(1, v.size / 1_000_000)} MB"
                addView(label(listOf(CloudVideo.duration(v.duration), size.replace('.', ','), v.sender).filter { it.isNotBlank() }.joinToString("\n"), 11f, muted).apply {
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    /** Passo 1 do login no Telegram: api_id, api_hash e telefone. */
    private fun askTelegramApi() {
        val steps = """
            1. Abra my.telegram.org (botão abaixo), entre com o seu número e o código que o Telegram envia.
            2. Toque em "API development tools" e preencha: App title "Alexandria Leitor", Short name "alexandrialeitor", Platform "Android". O resto pode ficar em branco.
            3. Copie o api_id (só números) e o api_hash e cole aqui. Eles ficam só neste aparelho; não mande para ninguém.
        """.trimIndent()
        val apiId = EditText(this).apply {
            hint = "api_id"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val apiHash = EditText(this).apply {
            hint = "api_hash"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val phone = EditText(this).apply {
            hint = "Telefone com DDI (ex.: +55 11 91234-5678)"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(label(steps, 13f, ink).apply { setPadding(0, 0, 0, dp(6)) })
            addView(label("›  Abrir my.telegram.org", 14f, gold, true).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://my.telegram.org/apps")))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            addView(apiId, LinearLayout.LayoutParams(-1, -2))
            addView(apiHash, LinearLayout.LayoutParams(-1, -2))
            addView(phone, LinearLayout.LayoutParams(-1, -2))
            addView(label(TELEGRAM_NOTICE, 11f, muted).apply { setPadding(0, dp(8), 0, 0) })
        }
        AlertDialog.Builder(this)
            .setTitle("Entrar no Telegram")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar código") { _, _ ->
                val id = apiId.text.toString().trim().toIntOrNull()
                val hash = apiHash.text.toString().trim()
                val number = phone.text.toString().replace(Regex("[^+0-9]"), "")
                if (id == null || hash.length < 16 || number.length < 8) {
                    Toast.makeText(this, "Preencha o api_id, o api_hash e o telefone com DDI.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "Pedindo o código ao Telegram…", Toast.LENGTH_SHORT).show()
                TelegramCloud.sendCode(this, id, hash, number) { result ->
                    if (destroyed) return@sendCode
                    result.onSuccess { askTelegramCode(id, hash) }
                        .onFailure { Toast.makeText(this, it.message ?: "Não foi possível pedir o código.", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    /** Passo 2: o código que o Telegram mandou (no próprio app do Telegram ou por SMS). */
    private fun askTelegramCode(apiId: Int, apiHash: String) {
        val input = EditText(this).apply {
            hint = "Código"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Código do Telegram")
            .setMessage("O Telegram mandou um código para o seu app do Telegram (ou por SMS). Digite aqui. Nunca passe esse código para outra pessoa.")
            .setView(LinearLayout(this).apply {
                setPadding(dp(22), 0, dp(22), 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            })
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) return@setPositiveButton
                TelegramCloud.signIn(this, code) { result ->
                    if (destroyed) return@signIn
                    result.onSuccess { done -> if (done) finishTelegram(apiId, apiHash) else askTelegramPassword(apiId, apiHash) }
                        .onFailure { Toast.makeText(this, it.message ?: "O código não foi aceito.", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    /** Passo 3 (só para contas com verificação em duas etapas). */
    private fun askTelegramPassword(apiId: Int, apiHash: String) {
        val input = EditText(this).apply {
            hint = "Senha da verificação em duas etapas"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Senha do Telegram")
            .setMessage("Sua conta tem verificação em duas etapas. Digite a senha dela (não fica guardada).")
            .setView(LinearLayout(this).apply {
                setPadding(dp(22), 0, dp(22), 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            })
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                TelegramCloud.password(this, input.text.toString()) { result ->
                    if (destroyed) return@password
                    result.onSuccess { finishTelegram(apiId, apiHash) }
                        .onFailure { Toast.makeText(this, it.message ?: "A senha não foi aceita.", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    private fun finishTelegram(apiId: Int, apiHash: String) {
        TelegramCloud.finish(this, apiId, apiHash) { result ->
            if (destroyed) return@finish
            result.onSuccess { name ->
                cloudBooks = null
                Toast.makeText(this, "Conectado ao Telegram${if (name.isNotBlank()) " como $name" else ""}.", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this, it.message ?: "Não foi possível concluir o login.", Toast.LENGTH_LONG).show() }
            if (section == SECTION_SETTINGS) showSettings()
        }
    }

    /** Passo a passo do token do GitHub e o campo para colar. */
    private fun askSyncToken() {
        val steps = """
            1. No GitHub (pode ser no computador), abra Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token. O botão abaixo abre essa página.
            2. Dê um nome (ex.: Alexandria) e escolha a validade.
            3. Em "Account permissions", procure "Gists" e escolha "Read and write". Não precisa de mais nada.
            4. Toque em "Generate token", copie o token (começa com github_pat_) e cole aqui.

            Use o mesmo token no site do Alexandria (Ajustes → Sincronizar posição). Ele só dá acesso aos seus gists e fica guardado só neste aparelho.
        """.trimIndent()
        val input = EditText(this).apply {
            hint = "github_pat_…"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(label(steps, 13f, ink).apply { setPadding(0, 0, 0, dp(10)) })
            addView(label("›  Abrir a página do token no GitHub", 14f, gold, true).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/settings/personal-access-tokens/new")))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("Conectar com o GitHub")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Conectar") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isEmpty()) return@setPositiveButton
                Toast.makeText(this, "Conectando…", Toast.LENGTH_SHORT).show()
                PositionSync.connect(this, token) { result ->
                    if (destroyed) return@connect
                    result.onSuccess { login ->
                        Toast.makeText(this, "Conectado como @$login. A posição de leitura vai sincronizar.", Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        val message = when ((e as? SyncException)?.code) {
                            401 -> "O GitHub recusou o token. Confira se copiou inteiro."
                            403 -> "O token não tem permissão de gists (Account permissions → Gists → Read and write)."
                            null -> "Sem conexão com o GitHub. Tente de novo com internet."
                            else -> "O GitHub respondeu com erro (${(e as SyncException).code})."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    if (section == SECTION_SETTINGS) showSettings()
                }
            }
            .show()
    }

    private fun settingsPanel(title: String, fill: LinearLayout.() -> Unit) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 14)
            addView(label(title, 15f, ink, true).apply { setPadding(0, 0, 0, dp(8)) })
            fill()
        }
        content.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun showLicense(title: String, file: String) {
        val text = try {
            assets.open("licenses/$file").use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            "Texto não encontrado."
        }
        val view = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ink)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun folderSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale("pt", "BR"), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale("pt", "BR"), "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale("pt", "BR"), "%.0f KB", bytes / 1024.0)
        else -> "$bytes bytes"
    }

    companion object {
        private const val REQUEST_DOCUMENT = 4101
        private const val KEY_SECTION = "section"
        private const val KEY_SHELF_OPEN = "shelf_open"
        private const val KEY_OPEN_SHELVES = "open_shelves"
        private const val KEY_OPEN_ESTANTE = "open_estante"
        private const val KEY_PENDING_COVER = "pending_cover"
        private const val REQUEST_COVER = 4102
        private const val SECTION_LIBRARY = "Biblioteca"
        private const val SECTION_NOTES = "Notas"
        private const val SECTION_STATS = "Estatísticas"
        private const val SECTION_SETTINGS = "Ajustes"
        private val NAV_ITEMS = listOf(SECTION_LIBRARY, SECTION_NOTES, SECTION_STATS, SECTION_SETTINGS)

        /** Livro, caneta pena, ábaco e bússola. */
        private val NAV_ICONS = mapOf(
            SECTION_LIBRARY to R.drawable.ic_tab_library,
            SECTION_NOTES to R.drawable.ic_tab_notes,
            SECTION_STATS to R.drawable.ic_tab_stats,
            SECTION_SETTINGS to R.drawable.ic_tab_settings
        )
        private val LICENSES = listOf(
            "Alexandria — GNU AGPL 3.0 ou posterior" to "AGPL-3.0.txt",
            "EraPDF (MuPDF), EraMOBI e EraDjVu — GNU AGPL 3.0" to "AGPL-3.0.txt",
            "EraComic — GNU GPL 2.0" to "GPL-2.0.txt",
            "libmobi — GNU LGPL 3.0" to "LGPL-3.0.txt",
            "GNU GPL 3.0 (base da LGPL 3.0)" to "GPL-3.0.txt",
            "Common CLI viewer interface — Apache 2.0" to "Apache-2.0.txt",
            "libjpeg-turbo, libpng, libwebp, MiniZip, EasyBMP, FreeType, OpenJPEG" to "terceiros.txt",
            "Fonte Atkinson Hyperlegible — SIL OFL 1.1" to "OFL-1.1-Atkinson.txt",
            "Fontes Liberation (PDFs sem fontes embutidas) — SIL OFL 1.1" to "OFL-1.1-Liberation.txt",
            "GramJS (nuvem pelo Telegram) — MIT" to "gramjs-MIT.txt"
        )

        /** Aviso exigido pelas regras da API do Telegram (item 2.2). */
        const val TELEGRAM_NOTICE = "Esta função usa a API do Telegram e faz parte do ecossistema do Telegram; o Alexandria não é um app oficial do Telegram. Contas que entram por apps não oficiais ficam sob observação do Telegram, segundo as regras dele (core.telegram.org/api/obtaining_api_id)."
        private val COVER_FORMATS = setOf("pdf", "cbz", "cbr", "epub", "mobi", "djvu")
    }
}
