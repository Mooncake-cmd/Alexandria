package com.alexandria.reader

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        LibraryDatabase(context).use { db ->
            val reading = db.add("Dom Casmurro.epub", "content://1", "epub")!!
            val done = db.add("Manual.pdf", "content://2", "pdf")!!
            val shelved = db.add("Quadrinho.cbz", "content://3", "cbz")!!
            db.add("Antigo.mobi", "content://4", "mobi")
            db.updateProgress(reading, 2_500, 10)
            db.setFinished(done, true)
            db.setShelf(shelved, "HQs")
            db.addNote(reading, "Uma noite destas", "Começo famoso", 2_500)
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
    }

    private fun texts(root: View): List<String> {
        val result = mutableListOf<String>()
        if (root is TextView) result += root.text.toString()
        if (root is ViewGroup) for (i in 0 until root.childCount) result += texts(root.getChildAt(i))
        return result
    }

    private fun shelfBooks(root: View): List<ShelfBook> = when (root) {
        is ShelfBook -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { shelfBooks(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun click(root: View, text: String) {
        fun find(view: View): View? {
            if (view is TextView && view.text.toString().startsWith(text)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        (find(root) ?: error("\"$text\" não encontrado")).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun libraryShowsReadingShelvesAndCollapsedEstante() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        var shown = texts(root)
        assertTrue(shown.contains("Dom Casmurro"))
        assertTrue(shown.contains("25% lido"))
        assertTrue(shown.contains("Antigo"))
        assertTrue(shown.any { it.startsWith("▶  📁 HQs (1)") })
        assertFalse("Quadrinho fica dentro da prateleira recolhida", shown.contains("Quadrinho"))
        assertTrue(shown.any { it.contains("Estante · 1 concluído") })
        assertFalse("Estante começa recolhida", shown.contains("Manual"))

        click(root, "▶  📁 HQs")
        click(activity.window.decorView, "▶  🏆 Estante")
        shown = texts(activity.window.decorView)
        assertTrue(shown.contains("Quadrinho"))
        // Na Estante de madeira o livro é desenhado; título e data ficam na descrição (leitor de tela).
        val books = shelfBooks(activity.window.decorView)
        assertEquals(listOf("Manual"), books.map { it.contentDescription.toString().substringBefore(". ") })
        assertTrue(books.single().contentDescription.contains("Concluído em"))
        // Manter pressionado mostra as opções, com a data de conclusão no cabeçalho.
        books.single().performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(texts(dialog.window!!.decorView).any { it.startsWith("Concluído em") })
    }

    @Test
    fun notesAndStatsTabs() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity.window.decorView, "Notas")
        var shown = texts(activity.window.decorView)
        assertTrue(shown.contains("“Uma noite destas”"))
        assertTrue(shown.contains("Começo famoso"))
        assertTrue(shown.any { it.startsWith("Cap. 3 · 25%") })

        click(activity.window.decorView, "Estatísticas")
        shown = texts(activity.window.decorView)
        assertTrue(shown.contains("na biblioteca"))
        assertTrue(shown.contains("4"))
        assertTrue(shown.contains("EPUB  ·  1"))
    }

    @Test
    fun settingsShowLicensesAndCache() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity.window.decorView, "Ajustes")
        val shown = texts(activity.window.decorView)
        assertTrue(shown.any { it.startsWith("Versão ") })
        assertTrue(shown.any { it.startsWith("Cópias e conversões em cache:") })
        assertTrue(shown.contains("›  EraPDF (MuPDF), EraMOBI e EraDjVu — GNU AGPL 3.0"))
        assertTrue(shown.contains("›  Fonte Atkinson Hyperlegible — SIL OFL 1.1"))
        assertTrue(shown.contains("›  EraComic — GNU GPL 2.0"))
        // Todos os textos de licença listados existem nos assets do app.
        for (file in listOf("AGPL-3.0.txt", "GPL-2.0.txt", "LGPL-3.0.txt", "GPL-3.0.txt", "Apache-2.0.txt", "terceiros.txt")) {
            val text = activity.assets.open("licenses/$file").use { String(it.readBytes()) }
            assertTrue("$file vazio", text.length > 1000)
        }
    }
}
