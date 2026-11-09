package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class GlossaryAndTimeTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun all(root: View): List<View> {
        val result = mutableListOf(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) result += all(root.getChildAt(i))
        return result
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun launch(id: Long) = Robolectric.buildActivity(
        ReaderActivity::class.java,
        Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
    ).setup().visible()

    @Test
    fun upgradeToVersion5KeepsDataAndAddsTables() {
        val file = context.getDatabasePath("alexandria.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE library_items (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, uri TEXT NOT NULL UNIQUE,
                format TEXT NOT NULL, added_at INTEGER NOT NULL, last_position INTEGER NOT NULL DEFAULT 0, page_count INTEGER NOT NULL DEFAULT 0,
                opened_at INTEGER NOT NULL DEFAULT 0, finished_at INTEGER NOT NULL DEFAULT 0, shelf TEXT NOT NULL DEFAULT '')""")
            old.execSQL("""CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL REFERENCES library_items(id) ON DELETE CASCADE,
                excerpt TEXT NOT NULL, text TEXT NOT NULL, position INTEGER NOT NULL, created_at INTEGER NOT NULL)""")
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, last_position, shelf) VALUES ('Duna', 'content://d', 'epub', 1, 4200, 'Ficção')")
            old.version = 4
        }
        LibraryDatabase(context).use { db ->
            val item = db.all().single()
            assertEquals(4200, item.position)
            assertEquals("Ficção", item.shelf)
            assertEquals(0L, item.readingSeconds)
            db.addReadingTime(item.id, "2026-11-08", 90)
            db.addReadingTime(item.id, "2026-11-08", 30)
            assertEquals(120L, db.find(item.id)!!.readingSeconds)
            assertEquals(mapOf("2026-11-08" to 120L), db.readingDays())
        }
    }

    @Test
    fun termsAreUniquePerBookIgnoringCaseAndAccents() {
        LibraryDatabase(context).use { db ->
            val duna = db.add("Duna.epub", "content://1", "epub")!!
            val other = db.add("Outro.txt", "content://2", "txt")!!
            db.saveTerm(duna, "  Arrakis ", "planeta")
            db.saveTerm(duna, "ARRÁKIS", "planeta do deserto") // mesmo termo: troca a definição
            db.saveTerm(other, "Arrakis", "outro livro, outro glossário")
            assertEquals(listOf("ARRÁKIS" to "planeta do deserto"), db.termsFor(duna).map { it.term to it.definition })
            assertEquals(2, db.allTerms().size)
            db.remove(duna) // apagar o livro apaga o glossário dele
            assertEquals(listOf("Arrakis"), db.allTerms().map { it.term })
        }
    }

    // Gráficos nativos: medida de texto de verdade (no modo simulado, toda letra tem 1 px e
    // "qual letra está neste ponto" responde sempre 0).
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test
    fun textIsSelectableAndDefinedTermsAreUnderlinedAndTappable() {
        val file = File(context.cacheDir, "duna.txt").apply {
            writeText("Paul chegou a Arrakis.\nNa casa nova, ouviu falar dos Fremen.\nArrakis era quente.")
        }
        val id = LibraryDatabase(context).use { db ->
            val id = db.add(file.name, Uri.fromFile(file).toString(), "txt")!!
            db.saveTerm(id, "Arrakis", "Planeta do deserto")
            id
        }
        val controller = launch(id)
        val activity = controller.get()
        val list = all(activity.window.decorView).filterIsInstance<ListView>().single()
        waitUntil { list.childCount > 0 }
        shadowOf(Looper.getMainLooper()).idle()
        val text = list.getChildAt(0) as TextView
        assertTrue("o texto do livro precisa ser selecionável", text.isTextSelectable)
        val spans = (text.text as Spanned).let { s -> s.getSpans(0, s.length, GlossarySpan::class.java).map { s.subSequence(s.getSpanStart(it), s.getSpanEnd(it)).toString() } }
        assertEquals(listOf("Arrakis", "Arrakis"), spans)

        // Toque simples sobre a palavra: abre a definição.
        val spanned = text.text as Spanned
        val start = spanned.toString().indexOf("Arrakis")
        val layout = text.layout
        val line = layout.getLineForOffset(start)
        val location = IntArray(2)
        text.getLocationOnScreen(location)
        val x = location[0] + text.totalPaddingLeft + (layout.getPrimaryHorizontal(start) + layout.getPrimaryHorizontal(start + 3)) / 2
        val y = location[1] + text.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        val t = SystemClock.uptimeMillis()
        for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 50L to MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t + offset, action, x, y, 0)
            activity.dispatchTouchEvent(e)
            e.recycle()
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        val dialog = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val shown = all(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue("definição não apareceu: $shown", shown.contains("Planeta do deserto"))
        assertTrue(shown.contains("Arrakis"))
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun epubMarksTermsAndKeepsTextSelectable() {
        val file = writeZip(File(context.cacheDir, "g.epub"), SampleBooks.epub3)
        val id = LibraryDatabase(context).use { db ->
            val id = db.add(file.name, Uri.fromFile(file).toString(), "epub")!!
            db.saveTerm(id, "Engenho Novo", "bairro do Rio")
            id
        }
        val controller = launch(id)
        val web = all(controller.get().window.decorView).filterIsInstance<WebView>().single()
        waitUntil { shadowOf(web).lastLoadDataWithBaseURL != null }
        val html = shadowOf(web).lastLoadDataWithBaseURL.data
        assertTrue(html.contains("<span class=\"alx-term\" data-term=\""))
        assertTrue(html.contains(">Engenho Novo</span>"))
        assertTrue(html.contains("user-select: text !important"))
        assertTrue(html.contains(".alx-term { text-decoration: underline dotted"))
        controller.pause().stop().destroy()
    }

    @Test
    fun readingTimeIsCountedForTheBookAndTheDay() {
        val file = File(context.cacheDir, "tempo.txt").apply { writeText("Texto.") }
        val id = LibraryDatabase(context).use { it.add(file.name, Uri.fromFile(file).toString(), "txt")!! }
        val controller = launch(id)
        val timer = all(controller.get().window.decorView).filterIsInstance<TextView>().first { it.text.startsWith("⏱") }
        Thread.sleep(1_600)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(timer.text.toString(), timer.text.contains("neste livro"))
        controller.pause()
        LibraryDatabase(context).use { db ->
            assertTrue(db.find(id)!!.readingSeconds >= 1)
            assertTrue((db.readingDays()[Days.today()] ?: 0) >= 1)
        }
        controller.stop().destroy()
    }

    @Test
    fun streakAppearsInLibraryAndStatistics() {
        val today = Days.parse(Days.today())!!
        LibraryDatabase(context).use { db ->
            val id = db.add("Duna.epub", "content://1", "epub")!!
            db.addReadingTime(id, Days.format(today - 1), 900)
            db.addReadingTime(id, Days.format(today), 400)
            db.saveTerm(id, "Arrakis", "Planeta do deserto")
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        var shown = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(shown.toString(), shown.contains("🔥 2 dias seguidos · hoje ✓"))
        assertTrue(shown.any { it.contains("⏱ 21 min") })

        all(activity.window.decorView).filterIsInstance<TextView>().first { it.text == "Estatísticas" }.performClick()
        shown = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(shown.contains("🔥 2"))
        assertTrue(shown.contains("6 min")) // hoje
        assertTrue(shown.contains("21 min")) // total

        all(activity.window.decorView).filterIsInstance<TextView>().first { it.text == "Notas" }.performClick()
        all(activity.window.decorView).filterIsInstance<TextView>().first { it.text.startsWith("▶  📖 Duna") }.performClick()
        shown = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(shown.contains("Arrakis"))
        assertTrue(shown.contains("Planeta do deserto"))
    }
}
