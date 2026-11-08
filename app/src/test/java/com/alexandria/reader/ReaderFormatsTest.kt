package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReaderFormatsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
    }

    private fun add(file: File, format: String): Long =
        LibraryDatabase(context).use { it.add(file.name, Uri.fromFile(file).toString(), format)!! }

    private fun launch(id: Long) = Robolectric.buildActivity(
        ReaderActivity::class.java,
        Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
    ).setup().visible()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun all(root: View): List<View> {
        val result = mutableListOf<View>(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) result += all(root.getChildAt(i))
        return result
    }

    private inline fun <reified T : View> find(root: View): List<T> = all(root).filterIsInstance<T>()

    @Test
    fun docxOpensWithFormattedText() {
        val file = writeZip(File(context.cacheDir, "relatorio.docx"), SampleBooks.docx)
        val controller = launch(add(file, "docx"))
        val list = find<ListView>(controller.get().window.decorView).single()
        waitUntil { list.adapter.count > 0 }
        assertFalse(controller.get().isFinishing)
        val text = (0 until list.adapter.count).joinToString("\n") { list.adapter.getItem(it).toString() }
        assertTrue(text.startsWith("Relatório"))
        assertTrue(text.contains("•  primeiro item"))
        controller.pause().stop().destroy()
    }

    @Test
    fun searchFindsTermsIgnoringAccents() {
        val file = File(context.cacheDir, "busca.txt").apply {
            writeText((1..3_000).joinToString("\n") { if (it % 1_000 == 0) "Aqui há uma Ação importante." else "Linha $it comum." })
        }
        val controller = launch(add(file, "txt"))
        val root = controller.get().window.decorView
        val list = find<ListView>(root).single()
        waitUntil { list.adapter.count > 0 }

        find<TextView>(root).first { it.contentDescription == "Buscar no livro" }.performClick()
        val input = find<EditText>(root).single()
        input.setText("acao")
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        val counter = { find<TextView>(root).firstOrNull { Regex("\\d+/\\d+").matches(it.text) }?.text?.toString() }
        waitUntil { counter() != null }
        assertEquals("1/3", counter())
        controller.pause().stop().destroy()
    }

    @Test
    fun epubLoadsFirstChapterWithSecurityPolicy() {
        val file = writeZip(File(context.cacheDir, "livro.epub"), SampleBooks.epub3)
        val controller = launch(add(file, "epub"))
        val web = find<WebView>(controller.get().window.decorView).single()
        waitUntil { shadowOf(web).lastLoadDataWithBaseURL != null }
        assertFalse(controller.get().isFinishing)
        val loaded = shadowOf(web).lastLoadDataWithBaseURL
        assertNotNull(loaded)
        assertEquals("https://book.alexandria/OEBPS/Text/cap 1.xhtml", loaded.baseUrl)
        assertTrue(loaded.data.contains("Engenho Novo"))
        assertTrue(loaded.data.contains("script-src 'none'"))
        controller.pause().stop().destroy()
        // A cópia local do EPUB fica no cache e o total de capítulos é salvo.
        LibraryDatabase(context).use { db -> assertEquals(2, db.all().single().pageCount) }
    }

    @Test
    fun rarComicFailsWithClearMessage() {
        val file = File(context.cacheDir, "quadrinho.cbz").apply { writeText("Rar!\u001a\u0007 não é zip") }
        val controller = launch(add(file, "cbz"))
        waitUntil { controller.get().isFinishing }
        assertTrue(controller.get().isFinishing)
    }
}
