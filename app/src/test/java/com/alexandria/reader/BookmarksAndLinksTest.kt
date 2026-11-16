package com.alexandria.reader

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

/** Links e notas de rodapé dos EPUB; marcadores de página. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookmarksAndLinksTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---------- links ----------

    @Test
    fun linksResolveInsideTheBook() {
        val from = "OEBPS/Text/part0012_split_001.html"
        assertEquals(
            BookLinks.Target(from, "footnote-198"),
            BookLinks.resolve(from, "../Text/part0012_split_001.html#footnote-198")
        )
        assertEquals(BookLinks.Target(from, "n1"), BookLinks.resolve(from, "#n1"))
        assertEquals(BookLinks.Target("OEBPS/notes.xhtml", "nota 2"), BookLinks.resolve(from, "../notes.xhtml#nota%202"))
        assertEquals(BookLinks.Target("OEBPS/Text/cap2.html", null), BookLinks.resolve(from, "cap2.html"))
        assertNull(BookLinks.resolve(from, "https://example.com/#x"))
        assertNull(BookLinks.resolve(from, "mailto:a@b.c"))
        assertNull(BookLinks.resolve(from, "  "))
    }

    @Test
    fun noteMarksLookLikeShortNumbersAndSymbols() {
        for (mark in listOf("1", "12", "[3]", "(4)", "5.", "a", "*", "**", "†", "‡")) assertTrue(mark, BookLinks.looksLikeNoteMark(mark))
        for (text in listOf("Capítulo 1", "Sumário", "12345", "nota", "")) assertFalse(text, BookLinks.looksLikeNoteMark(text))
    }

    // ---------- regras dos marcadores ----------

    @Test
    fun bookmarkNamesCountUpFromTheHighest() {
        assertEquals("Marcador 1", Bookmarks.nextName(emptyList()))
        assertEquals("Marcador 2", Bookmarks.nextName(listOf("Marcador 1")))
        assertEquals("Marcador 4", Bookmarks.nextName(listOf("Marcador 3", "Prefácio", "Marcador 1")))
    }

    @Test
    fun bookmarkIsOnTheScreenThatShowsIt() {
        // Página: só a mesma.
        assertTrue(Bookmarks.onScreen(38, 38, 1))
        assertFalse(Bookmarks.onScreen(37, 38, 1))
        // Texto: da linha do alto até o começo da próxima tela, com folga de 5% de uma tela.
        assertTrue(Bookmarks.onScreen(10_000, 10_000, 2_000))
        assertTrue(Bookmarks.onScreen(9_950, 10_000, 2_000))
        assertTrue(Bookmarks.onScreen(11_800, 10_000, 2_000))
        assertFalse(Bookmarks.onScreen(11_950, 10_000, 2_000))
        assertFalse(Bookmarks.onScreen(9_800, 10_000, 2_000))
    }

    // ---------- banco ----------

    @Test
    fun bookmarksAreSavedRenamedOrderedAndLeaveWithTheBook() {
        LibraryDatabase(context).use { db ->
            val id = db.add("Livro.pdf", "content://livro", "pdf")!!
            val second = db.addBookmark(id, 40)
            val first = db.addBookmark(id, 12)
            assertEquals("Marcador 1", second.name)
            assertEquals("Marcador 2", first.name)
            assertEquals(listOf(12L, 40L), db.bookmarksFor(id).map { it.position })
            db.renameBookmark(first.id, "  Prefácio ")
            assertEquals("Prefácio", db.bookmarksFor(id).first().name)
            db.removeBookmark(second.id)
            assertEquals(listOf("Prefácio"), db.bookmarksFor(id).map { it.name })
            db.remove(id)
            assertTrue(db.bookmarksFor(id).isEmpty())
        }
    }

    @Test
    fun upgradeFromVersion7CreatesBookmarks() {
        val file = context.getDatabasePath("alexandria.db").apply { parentFile?.mkdirs() }
        LibraryDatabase(context).use { it.writableDatabase }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("DROP TABLE bookmarks")
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, last_position) VALUES ('A.epub', 'content://a', 'epub', 0, 1234)")
            old.version = 7
        }
        LibraryDatabase(context).use { db ->
            val item = db.all().single()
            assertEquals(1234, item.position)
            db.addBookmark(item.id, 5)
            assertEquals(1, db.bookmarksFor(item.id).size)
        }
    }

    // ---------- tela de leitura ----------

    private fun all(root: View): List<View> {
        val result = mutableListOf(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) result += all(root.getChildAt(i))
        return result
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            idle()
        }
    }

    @Test
    fun cornerMarksThePageAndTheListTakesBackToIt() {
        val file = File(context.cacheDir, "marcadores.txt").apply {
            writeText((1..600).joinToString("\n") { "Linha $it do livro, com algum texto para ocupar a tela." })
        }
        val id = LibraryDatabase(context).use { it.add(file.name, Uri.fromFile(file).toString(), "txt")!! }
        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup().visible()
        val activity = controller.get()
        val root = activity.window.decorView
        val list = all(root).filterIsInstance<android.widget.ListView>().single()
        waitUntil { list.childCount > 0 }
        idle(300)
        val corner = all(root).filterIsInstance<BookmarkCorner>().single()
        assertFalse(corner.marked)

        // Toque no canto: marca a página e avisa, com "MUDAR".
        corner.performClick()
        idle()
        assertTrue(corner.marked)
        val saved = LibraryDatabase(context).use { it.bookmarksFor(id) }
        assertEquals(listOf("Marcador 1"), saved.map { it.name })
        val snack = all(root).filterIsInstance<TextView>().first { it.text.startsWith("Adicionado marcador") }
        assertTrue(snack.isShown)
        assertTrue(all(root).filterIsInstance<TextView>().any { it.text == "MUDAR" && it.isShown })

        // Lê adiante: a fita some.
        list.setSelectionFromTop(300, 0)
        idle(300)
        activity.onPositionChanged()
        assertFalse(corner.marked)

        // Lista (🔖) → tocar no marcador volta à página e abre a barra dos marcadores.
        all(root).filterIsInstance<TextView>().first { it.text == "🔖" }.performClick()
        idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val row = all(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text == "Marcador 1" }.parent as View
        row.performClick()
        waitUntil { corner.marked }
        assertTrue(corner.marked)
        assertTrue(all(root).filterIsInstance<TextView>().any { it.text == "1 de 1" && it.isShown })

        // ✕ sai do modo; tocar no canto de novo desmarca.
        all(root).filterIsInstance<TextView>().first { it.contentDescription == "Sair dos marcadores" }.performClick()
        idle()
        assertFalse(all(root).filterIsInstance<TextView>().any { it.text == "1 de 1" && it.isShown })
        corner.performClick()
        idle()
        assertFalse(corner.marked)
        assertTrue(LibraryDatabase(context).use { it.bookmarksFor(id) }.isEmpty())
        controller.pause().stop().destroy()
    }

    @Test
    fun arrowsWalkThroughTheBookmarksOfAPageBook() {
        val comic = File(context.cacheDir, "paginas.cbz")
        val page = android.graphics.Bitmap.createBitmap(300, 450, android.graphics.Bitmap.Config.ARGB_8888)
        java.util.zip.ZipOutputStream(comic.outputStream()).use { zip ->
            for (i in 1..6) {
                zip.putNextEntry(java.util.zip.ZipEntry("%02d.png".format(i)))
                page.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
        val id = LibraryDatabase(context).use { db ->
            val id = db.add(comic.name, Uri.fromFile(comic).toString(), "cbz")!!
            db.addBookmark(id, 1)
            db.addBookmark(id, 4)
            id
        }
        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup().visible()
        val activity = controller.get()
        val root = activity.window.decorView
        val viewer = all(root).filterIsInstance<ZoomPageView>().single()
        waitUntil { viewer.width > 0 && viewer.pageRect().width() > 0 }

        all(root).filterIsInstance<TextView>().first { it.text == "🔖" }.performClick()
        idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val labels = all(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.contains("Pág. 2"))
        assertTrue(labels.contains("Pág. 5"))
        (all(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text == "Marcador 1" }.parent as View).performClick()
        idle(300)
        val label = { all(root).filterIsInstance<TextView>().first { it.text.endsWith(" de 2") }.text.toString() }
        assertEquals("1 de 2", label())
        val next = all(root).filterIsInstance<TextView>().first { it.contentDescription == "Próximo marcador" }
        val prev = all(root).filterIsInstance<TextView>().first { it.contentDescription == "Marcador anterior" }
        assertFalse(prev.isEnabled)
        next.performClick()
        idle(300)
        assertEquals("2 de 2", label())
        assertFalse(next.isEnabled)
        assertTrue(all(root).filterIsInstance<BookmarkCorner>().single().marked)
        prev.performClick()
        idle(300)
        assertEquals("1 de 2", label())
        assertNotEquals(View.GONE, all(root).filterIsInstance<BookmarkCorner>().single().visibility)
        controller.pause().stop().destroy()
    }
}
