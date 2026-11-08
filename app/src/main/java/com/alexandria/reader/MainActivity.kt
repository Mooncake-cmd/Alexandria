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
        showSection(section)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION, section)
        outState.putBoolean(KEY_SHELF_OPEN, shelfOpen)
        outState.putStringArrayList(KEY_OPEN_SHELVES, ArrayList(openShelves))
    }

    override fun onResume() {
        super.onResume()
        // Ao voltar do leitor, atualiza progresso, Estante e anotações.
        if (refreshOnResume) {
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
            else -> showPlaceholder(name)
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
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(6), dp(5), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        NAV_ITEMS.forEach { name ->
            val active = name == selected
            val entry = TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setTextColor(if (active) gold else muted)
                if (active) setTypeface(typeface, Typeface.BOLD)
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
        content.addView(label("PDF  ·  EPUB  ·  CBZ  ·  DOCX  ·  TXT", 12f, ink))
        content.addView(label("EM BREVE", 10f, muted, true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        content.addView(label("MOBI  ·  DJVU  ·  CBR (RAR)", 12f, ink))
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
                    "Importe um PDF, EPUB, quadrinho CBZ, DOCX ou TXT para começar a ler. Os outros formatos ficam guardados até o leitor deles ficar pronto."
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
                addGroupedCards(finished)
            }
        }
        spacer(14)
        content.addView(label("Toque em um item para abri-lo. Mantenha pressionado para mover de prateleira, concluir ou remover.", 11f, muted))
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
        val status = if (entry.finished) "Concluído em ${dateFormat.format(Date(entry.finishedAt))}" else ReadingStatus.describe(entry)
        info.addView(label(status, 11f, muted).apply { setPadding(0, dp(3), 0, dp(6)) })
        if (entry.format in Formats.readable) info.addView(progressBar(ReadingStatus.progress(entry)), LinearLayout.LayoutParams(-1, dp(4)))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
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
        val options = listOf(
            (if (entry.shelf.isEmpty()) "Mover para prateleira…" else "Prateleira: ${entry.shelf}…") to { chooseShelf(entry) },
            (if (entry.finished) "Voltar para \"Lendo agora\"" else "Marcar como concluído") to {
                database.setFinished(entry.id, !entry.finished)
                showLibrary()
            },
            "Remover da biblioteca" to { confirmRemove(entry) }
        )
        AlertDialog.Builder(this)
            .setTitle(displayTitle(entry.name))
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancelar", null)
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
        if (shelf.isNotEmpty()) openShelves += shelf
        showLibrary()
    }

    private fun renameShelf(name: String) {
        askText("Renomear prateleira", "Novo nome", name) { typed ->
            val target = Shelves.resolve(typed, database.shelves() - name)
            database.renameShelf(name, target)
            if (openShelves.remove(name)) openShelves += target
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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            // Alguns aparelhos (TVs, sistemas modificados) não têm o seletor de documentos.
            Toast.makeText(this, "Este aparelho não tem um seletor de arquivos disponível.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK) return
        val result = data ?: return

        val uris = mutableListOf<Uri>()
        result.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        if (uris.isEmpty()) result.data?.let { uris += it }

        var added = 0
        var rejected = 0
        uris.forEach { uri ->
            val name = queryDisplayName(uri) ?: "Documento importado"
            var format = Formats.detect(name, contentResolver.getType(uri))
            if (format == null) {
                rejected++
                return@forEach
            }
            if (format == "cbr" && isZip(uri)) format = "cbz" // CBR que é ZIP por dentro abre como CBZ
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Nem todo provedor oferece permissão persistente; o item entra mesmo assim.
            }
            if (database.add(name, uri.toString(), format) != null) added++
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
        if (entry.format in Formats.readable) {
            refreshOnResume = true
            val intent = Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, entry.id)
            if (position != null) intent.putExtra(ReaderActivity.EXTRA_POSITION, position)
            startActivity(intent)
        } else {
            val detail = if (entry.format == "cbr") "Este CBR está em formato RAR, que ainda não é suportado." else "O leitor de ${entry.format.uppercase()} ainda não existe."
            Toast.makeText(this, "$detail O arquivo continua guardado na biblioteca.", Toast.LENGTH_LONG).show()
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
        database.remove(entry.id)
        background.execute { BookFiles.delete(applicationContext, entry.id) }
        showLibrary()
    }

    // ---------- Notas ----------

    private fun showNotes() {
        setContentView(baseScreen(SECTION_NOTES))
        val notes = database.allNotes()
        val items = database.all().associateBy { it.id }
        sectionTitle("Minhas anotações", "${notes.size} anotação(ões) em ${notes.map { it.itemId }.distinct().size} livro(s)")
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
        val tiles = listOf(
            items.size.toString() to "na biblioteca",
            items.count { !it.finished && (it.openedAt > 0) }.toString() to "em leitura",
            items.count { it.finished }.toString() to "concluídos",
            notes.size.toString() to "anotações"
        )
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
        if (items.isNotEmpty()) {
            content.addView(label("POR FORMATO", 10f, muted, true).apply { setPadding(0, dp(14), 0, dp(6)) })
            items.groupingBy { it.format }.eachCount().entries.sortedByDescending { it.value }.forEach { (format, count) ->
                content.addView(label("${format.uppercase()}  ·  $count", 13f, ink).apply { setPadding(0, dp(2), 0, dp(2)) })
            }
        }
    }

    private fun showPlaceholder(name: String) {
        setContentView(baseScreen(name))
        sectionTitle(name, "Esta área será desenvolvida em uma próxima etapa.")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(22))
            background = rounded(Color.WHITE, 16)
        }
        panel.addView(label("Em construção", 17f, ink, true))
        panel.addView(label("A estrutura inicial do Alexandria está sendo preparada. Esta seção receberá seus recursos durante as próximas etapas.", 13f, muted).apply {
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(panel)
    }

    companion object {
        private const val REQUEST_DOCUMENT = 4101
        private const val KEY_SECTION = "section"
        private const val KEY_SHELF_OPEN = "shelf_open"
        private const val KEY_OPEN_SHELVES = "open_shelves"
        private const val SECTION_LIBRARY = "Biblioteca"
        private const val SECTION_NOTES = "Notas"
        private const val SECTION_STATS = "Estatísticas"
        private val NAV_ITEMS = listOf(SECTION_LIBRARY, SECTION_NOTES, SECTION_STATS, "Ajustes")
        private val COVER_FORMATS = setOf("pdf", "cbz", "epub")
    }
}
