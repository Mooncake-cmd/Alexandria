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
        sectionTitle("Minha biblioteca", "Seus documentos ficam organizados neste dispositivo.")
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

        val items = database.all()
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
            return
        }
        if (reading.isEmpty()) {
            content.addView(label("Nenhum livro em andamento. Os concluídos estão na Estante, abaixo.", 12f, muted))
        } else {
            addGroupedCards(reading)
        }

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
        info.addView(label(statusText(entry), 11f, muted).apply { setPadding(0, dp(3), 0, dp(6)) })
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

    private fun itemOptions(entry: LibraryItem) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += (if (entry.shelf.isEmpty()) "Mover para prateleira…" else "Prateleira: ${entry.shelf}…") to { chooseShelf(entry) }
        if (!entry.archived) {
            options += (if (entry.finished) "Voltar para \"Lendo agora\"" else "Marcar como concluído") to {
                database.setFinished(entry.id, !entry.finished)
                showLibrary()
            }
        }
        if (entry.finished && !entry.archived) options += "Liberar espaço (manter na Estante)…" to { confirmFreeSpace(entry) }
        options += "Trocar capa…" to { chooseCover(entry) }
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
                database.add(name, uri.toString(), format, size) != null -> added++
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
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
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
            addView(label("Leitor de PDF, EPUB, MOBI, AZW3, DJVU, CBZ, CBR, DOCX e TXT. Sem anúncios, sem conta e sem acesso à internet: seus livros e anotações ficam só neste aparelho.", 12f, muted).apply {
                setPadding(0, dp(6), 0, 0)
            })
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
            "EraPDF (MuPDF), EraMOBI e EraDjVu — GNU AGPL 3.0" to "AGPL-3.0.txt",
            "EraComic — GNU GPL 2.0" to "GPL-2.0.txt",
            "libmobi — GNU LGPL 3.0" to "LGPL-3.0.txt",
            "GNU GPL 3.0 (base da LGPL 3.0)" to "GPL-3.0.txt",
            "Common CLI viewer interface — Apache 2.0" to "Apache-2.0.txt",
            "libjpeg-turbo, libpng, libwebp, MiniZip, EasyBMP, FreeType, OpenJPEG" to "terceiros.txt",
            "Fonte Atkinson Hyperlegible — SIL OFL 1.1" to "OFL-1.1-Atkinson.txt",
            "Fontes Liberation (PDFs sem fontes embutidas) — SIL OFL 1.1" to "OFL-1.1-Liberation.txt"
        )
        private val COVER_FORMATS = setOf("pdf", "cbz", "cbr", "epub", "mobi", "djvu")
    }
}
