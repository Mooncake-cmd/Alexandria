package com.alexandria.reader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tela de leitura. Monta a moldura comum (barra de título, busca, anotações, aviso de fim de
 * livro) e entrega o conteúdo a um [BookViewer] do formato. Salva o ponto de leitura ao sair.
 */
class ReaderActivity : Activity(), ReaderHost {
    private lateinit var database: LibraryDatabase
    private var current: LibraryItem? = null
    private var viewer: BookViewer? = null
    override val worker: ExecutorService = Executors.newSingleThreadExecutor()
    override val activity: Activity get() = this
    override var closed = false
        private set

    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchInfo: TextView
    private lateinit var finishBanner: LinearLayout
    private var hits: List<SearchHit> = emptyList()
    private var hitIndex = -1
    private var searchTerm = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = LibraryDatabase(this)
        val item = database.find(intent.getLongExtra(EXTRA_ITEM_ID, -1L))
        if (item == null) {
            fail("Documento não encontrado na biblioteca.")
            return
        }
        val created = when (item.format) {
            "pdf" -> PageViewer.pdf(this)
            "cbz", "cbr" -> PageViewer.comic(this)
            "djvu" -> PageViewer.djvu(this)
            "mobi" -> EpubViewer(this) { BookFiles.mobiAsEpub(this, it) }
            "txt" -> TextViewer.txt(this)
            "docx" -> TextViewer.docx(this)
            "epub" -> EpubViewer(this)
            else -> null
        }
        if (created == null) {
            fail("O leitor de ${item.format.uppercase()} ainda não existe.")
            return
        }
        current = item
        viewer = created
        setContentView(frame(item, created))
        // Uma anotação aberta pela aba Notas leva direto ao ponto dela.
        val start = intent.getIntExtra(EXTRA_POSITION, -1).takeIf { it >= 0 } ?: item.position
        created.open(item, start)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        closed = true
        viewer?.close()
        worker.shutdown()
        database.close()
        super.onDestroy()
    }

    override fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun saveProgress() {
        val item = current ?: return
        val view = viewer ?: return
        if (view.loaded) database.updateProgress(item.id, view.position(), view.pageCount())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        viewer?.dispatchTouch(ev) ?: super.dispatchTouchEvent(ev)

    // ---------- moldura ----------

    private fun barButton(text: String, description: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(Color.WHITE)
            contentDescription = description
            setOnClickListener { onClick(it) }
        }

    private fun frame(item: LibraryItem, viewer: BookViewer): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.paper)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.ink)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(barButton("‹", "Voltar") { finish() })
        bar.addView(
            label(item.name, 15f, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(8), 0)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        if (viewer.searchable) bar.addView(barButton("🔍", "Buscar no livro") { toggleSearch() })
        bar.addView(barButton("📝", "Anotações") { showNotes() })
        bar.addView(barButton("⋮", "Mais opções") { showMenu(it) })
        root.addView(bar)

        searchBar = buildSearchBar()
        root.addView(searchBar)
        root.addView(viewer.body, LinearLayout.LayoutParams(-1, 0, 1f))

        finishBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(250, 243, 228))
            setPadding(dp(16), dp(4), dp(8), dp(4))
            visibility = View.GONE
            addView(label("Você chegou ao fim.", 13f, Palette.ink), LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@ReaderActivity).apply {
                text = "🏁 Concluir livro"
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(Palette.gold)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { markFinished(true) }
            })
        }
        root.addView(finishBanner)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(viewer.footer)
        }
        root.addView(footer)
        installSystemBarInsets(root, bar, footer)
        return root
    }

    override fun onPositionChanged() {
        val view = viewer ?: return
        val item = current ?: return
        val show = view.loaded && view.atEnd() && !item.finished
        finishBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showMenu(anchor: View) {
        val view = viewer ?: return
        val item = current ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += view.menuActions()
        actions += if (item.finished) {
            "Voltar para \"Lendo agora\"" to { markFinished(false) }
        } else {
            "Concluir livro" to { markFinished(true) }
        }
        val popup = PopupMenu(this, anchor)
        actions.forEachIndexed { index, (title, _) -> popup.menu.add(Menu.NONE, index, index, title) }
        popup.setOnMenuItemClickListener { menuItem ->
            actions.getOrNull(menuItem.itemId)?.second?.invoke()
            true
        }
        popup.show()
    }

    /** Concluir move o livro para a Estante, como o botão "Finalizar" do leitor web. */
    private fun markFinished(finished: Boolean) {
        val item = current ?: return
        saveProgress()
        database.setFinished(item.id, finished)
        current = database.find(item.id)
        onPositionChanged()
        if (finished) {
            Toast.makeText(this, "\"${item.name}\" concluído e movido para a Estante.", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "De volta para \"Lendo agora\".", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- busca ----------

    private fun buildSearchBar(): LinearLayout {
        searchInput = EditText(this).apply {
            hint = "Buscar no livro"
            textSize = 14f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    runSearch()
                    true
                } else {
                    false
                }
            }
        }
        searchInfo = label("", 12f, Palette.muted).apply {
            gravity = Gravity.CENTER
            minWidth = dp(56)
            setOnClickListener { showHitList() }
        }
        fun small(text: String, description: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(48)
            setTextColor(Palette.ink)
            contentDescription = description
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), 0, dp(4), 0)
            visibility = View.GONE
            addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(searchInfo)
            addView(small("◀", "Resultado anterior") { stepHit(-1) })
            addView(small("▶", "Próximo resultado") { stepHit(1) })
            addView(small("✕", "Fechar busca") { toggleSearch() })
        }
    }

    private fun toggleSearch() {
        val input = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (searchBar.visibility == View.VISIBLE) {
            searchBar.visibility = View.GONE
            input.hideSoftInputFromWindow(searchInput.windowToken, 0)
            viewer?.clearSearch()
            hits = emptyList()
            hitIndex = -1
            searchInfo.text = ""
        } else {
            searchBar.visibility = View.VISIBLE
            searchInput.requestFocus()
            input.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun runSearch() {
        val view = viewer ?: return
        val term = searchInput.text.toString().trim()
        if (term.isEmpty() || !view.loaded) return
        searchTerm = term
        searchInfo.text = "…"
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
        view.search(term) { found ->
            hits = found
            if (found.isEmpty()) {
                hitIndex = -1
                searchInfo.text = "0"
                view.clearSearch()
                Toast.makeText(this, "Nada encontrado para \"$term\".", Toast.LENGTH_SHORT).show()
            } else {
                showHit(0)
            }
        }
    }

    private fun stepHit(delta: Int) {
        if (hits.isEmpty()) return
        showHit((hitIndex + delta + hits.size) % hits.size)
    }

    private fun showHit(index: Int) {
        hitIndex = index
        searchInfo.text = "${index + 1}/${hits.size}"
        viewer?.showHit(hits[index], searchTerm)
    }

    private fun showHitList() {
        if (hits.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("${hits.size} resultado(s)")
            .setItems(hits.map { it.label }.toTypedArray()) { _, which -> showHit(which) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ---------- anotações ----------

    /** Ao selecionar texto do livro, o menu da seleção ganha a opção "Anotar". */
    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        val view = viewer ?: return
        if (view.annotatesPages || currentFocus == searchInput) return
        mode.menu.add(Menu.NONE, MENU_ANNOTATE, 0, "Anotar").setOnMenuItemClickListener {
            // A seleção do EPUB é lida de forma assíncrona: o menu só fecha depois da leitura.
            val found = view.selection { excerpt, position ->
                mode.finish()
                if (excerpt.isNotEmpty()) editNote(null, excerpt, position)
                else Toast.makeText(this, "Selecione um trecho do livro para anotar.", Toast.LENGTH_SHORT).show()
            }
            if (!found) {
                mode.finish()
                Toast.makeText(this, "Selecione um trecho do livro para anotar.", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun showNotes() {
        val item = current ?: return
        val view = viewer ?: return
        val notes = database.notesFor(item.id)
        val labels = notes.map { note ->
            buildString {
                append(view.describe(note.position))
                if (note.excerpt.isNotEmpty()) append(" — “").append(note.excerpt.take(120)).append(if (note.excerpt.length > 120) "…”" else "”")
                if (note.text.isNotEmpty()) append("\n").append(note.text)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (notes.isEmpty()) "Anotações" else "Anotações (${notes.size})")
            .setNegativeButton("Fechar", null)
        if (notes.isEmpty()) {
            builder.setMessage(
                if (view.annotatesPages) "Nenhuma anotação ainda. Use \"Anotar esta página\"."
                else "Nenhuma anotação ainda. Selecione um trecho do texto e toque em \"Anotar\"."
            )
        } else {
            builder.setItems(labels.toTypedArray()) { _, which -> view.goTo(notes[which].position) }
        }
        if (view.annotatesPages) {
            builder.setPositiveButton("Anotar esta página") { _, _ -> if (view.loaded) editNote(null, "", view.position()) }
        }
        val dialog = builder.show()
        dialog.listView?.setOnItemLongClickListener { _, _, which, _ ->
            dialog.dismiss()
            noteOptions(notes[which])
            true
        }
    }

    private fun noteOptions(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(viewer?.describe(note.position) ?: "Anotação")
            .setItems(arrayOf("Ir para o trecho", "Editar nota", "Remover")) { _, which ->
                when (which) {
                    0 -> viewer?.goTo(note.position)
                    1 -> editNote(note, note.excerpt, note.position)
                    2 -> {
                        database.removeNote(note.id)
                        Toast.makeText(this, "Anotação removida.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Cria ([existing] nulo) ou edita uma anotação. */
    private fun editNote(existing: Note?, excerpt: String, position: Int) {
        val item = current ?: return
        val input = EditText(this).apply {
            hint = "Sua nota sobre esse trecho (opcional)"
            minLines = 3
            gravity = Gravity.TOP
            setText(existing?.text ?: "")
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            if (excerpt.isNotEmpty()) {
                addView(label("“${excerpt.take(400)}${if (excerpt.length > 400) "…" else ""}”", 13f, Palette.muted).apply {
                    setTypeface(typeface, android.graphics.Typeface.ITALIC)
                    setPadding(0, 0, 0, dp(8))
                })
            }
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val title = if (existing != null) "Editar anotação" else "Salvar anotação · ${viewer?.describe(position) ?: ""}"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val text = input.text.toString()
                if (existing != null) {
                    database.updateNote(existing.id, text)
                } else {
                    if (excerpt.isEmpty() && text.isBlank()) {
                        Toast.makeText(this, "Escreva uma nota para esta página.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    database.addNote(item.id, excerpt, text, position)
                }
                Toast.makeText(this, "Anotação salva.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_POSITION = "position"
        private const val MENU_ANNOTATE = 0x414e
    }
}
