package com.alexandria.reader

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
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
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
class IncomingBooksTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        BookFiles.libraryDir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
        BookFiles.libraryDir(context).deleteRecursively()
    }

    private fun downloaded(name: String, content: String = "conteúdo do livro $name"): File =
        File(context.cacheDir, "downloads").apply { mkdirs() }.let { dir -> File(dir, name).apply { writeText(content) } }

    @Test
    fun upgradeToVersion6KeepsEverything() {
        val file = context.getDatabasePath("alexandria.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE library_items (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, uri TEXT NOT NULL UNIQUE,
                format TEXT NOT NULL, added_at INTEGER NOT NULL, last_position INTEGER NOT NULL DEFAULT 0, page_count INTEGER NOT NULL DEFAULT 0,
                opened_at INTEGER NOT NULL DEFAULT 0, finished_at INTEGER NOT NULL DEFAULT 0, shelf TEXT NOT NULL DEFAULT '',
                reading_seconds INTEGER NOT NULL DEFAULT 0)""")
            old.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL, excerpt TEXT NOT NULL, text TEXT NOT NULL, position INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE reading_days (day TEXT PRIMARY KEY, seconds INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE terms (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL, term TEXT NOT NULL, folded TEXT NOT NULL, definition TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(item_id, folded))")
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, reading_seconds, finished_at) VALUES ('Duna.pdf', 'content://d', 'pdf', 1, 3600, 5)")
            old.version = 5
        }
        LibraryDatabase(context).use { db ->
            val item = db.all().single()
            assertEquals(3600L, item.readingSeconds)
            assertTrue(item.finished)
            assertEquals(0L, item.fileSize)
            assertFalse(item.archived)
        }
    }

    @Test
    fun receivingTheSameBookTwiceReusesIt() {
        val source = downloaded("Duna.pdf")
        LibraryDatabase(context).use { db ->
            val first = IncomingBooks.receive(context, db, Uri.fromFile(source), "application/pdf")
            assertFalse(first.reused)
            assertEquals("pdf", first.item.format)
            assertEquals(source.length(), first.item.fileSize)
            assertTrue(BookFiles.isOwnedCopy(context, first.item.uri))
            assertTrue(File(Uri.parse(first.item.uri).path!!).readText().contains("Duna.pdf"))
            db.updateProgress(first.item.id, 41, 300)

            val again = IncomingBooks.receive(context, db, Uri.fromFile(source), "application/pdf")
            assertTrue(again.reused)
            assertEquals(first.item.id, again.item.id)
            assertEquals(41, again.item.position)
            assertEquals(1, db.all().size)
            assertEquals("a segunda cópia é descartada", 1, BookFiles.libraryDir(context).listFiles()!!.size)
        }
    }

    @Test
    fun unsupportedFilesAreRejected() {
        val source = downloaded("planilha.xlsx")
        LibraryDatabase(context).use { db ->
            val failed = try {
                IncomingBooks.receive(context, db, Uri.fromFile(source), "application/vnd.ms-excel")
                false
            } catch (e: IncomingBooks.UnsupportedFormat) {
                true
            }
            assertTrue(failed)
            assertTrue(db.all().isEmpty())
        }
    }

    @Test
    fun freeingSpaceKeepsTheBookOnTheShelfAndReopeningRestoresIt() {
        val source = downloaded("Duna.epub")
        LibraryDatabase(context).use { db ->
            val item = IncomingBooks.receive(context, db, Uri.fromFile(source), "application/epub+zip").item
            db.setFinished(item.id, true)
            db.addNote(item.id, "Uma frase", "nota", 1200)
            db.saveTerm(item.id, "Arrakis", "planeta")
            val copy = File(Uri.parse(item.uri).path!!)

            val result = IncomingBooks.freeSpace(context, db, db.find(item.id)!!, deleteOriginal = true)
            assertEquals(IncomingBooks.Freed.FILE_DELETED, result)
            assertFalse("a cópia do app foi apagada", copy.exists())
            val archived = db.find(item.id)!!
            assertTrue(archived.archived)
            assertTrue(archived.finished)
            assertEquals("Duna.epub", archived.name)
            assertEquals(1, db.notesFor(item.id).size)

            // Abrir o mesmo arquivo de novo devolve o livro ao mesmo registro.
            val back = IncomingBooks.receive(context, db, Uri.fromFile(source), "application/epub+zip")
            assertTrue(back.reused)
            assertEquals(item.id, back.item.id)
            assertFalse(back.item.archived)
            assertEquals(listOf("Arrakis"), db.termsFor(item.id).map { it.term })
            assertEquals(1, db.all().size)
        }
    }

    @Test
    fun freeingSpaceWithoutDeletingTheOriginalKeepsThePhoneFile() {
        val original = downloaded("Manual.pdf")
        LibraryDatabase(context).use { db ->
            val id = db.add(original.name, Uri.fromFile(original).toString(), "pdf", original.length())!!
            db.setFinished(id, true)
            assertEquals(IncomingBooks.Freed.ORIGINAL_KEPT, IncomingBooks.freeSpace(context, db, db.find(id)!!, deleteOriginal = false))
            assertTrue(original.exists())
            assertTrue(db.find(id)!!.archived)
        }
    }

    @Test
    fun clearingStatisticsKeepsBooksNotesAndGlossary() {
        LibraryDatabase(context).use { db ->
            val id = db.add("Duna.pdf", "content://1", "pdf")!!
            db.addReadingTime(id, "2026-11-08", 900)
            db.updateProgress(id, 10, 300)
            db.addNote(id, "trecho", "", 10)
            db.saveTerm(id, "Arrakis", "planeta")
            db.clearStatistics()
            assertEquals(emptyMap<String, Long>(), db.readingDays())
            val item = db.find(id)!!
            assertEquals(0L, item.readingSeconds)
            assertEquals(10, item.position)
            assertEquals(1, db.notesFor(id).size)
            assertEquals(1, db.termsFor(id).size)
        }
    }

    @Test
    fun appIsOfferedToOpenAndShareBooksButNotPlainText() {
        val pm = context.packageManager
        val ours = ComponentName(context, OpenActivity::class.java)
        fun offers(action: String, type: String): Boolean {
            val intent = Intent(action).setDataAndType(Uri.parse("content://x/arquivo"), type)
            if (action == Intent.ACTION_SEND) intent.setDataAndType(null, type).type = type
            return pm.queryIntentActivities(intent, 0).any { it.activityInfo.name == ours.className }
        }
        for (type in listOf("application/pdf", "application/epub+zip", "application/x-mobipocket-ebook", "image/vnd.djvu",
            "application/vnd.comicbook+zip", "application/vnd.comicbook-rar",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            assertTrue("Abrir com: $type", offers(Intent.ACTION_VIEW, type))
            assertTrue("Compartilhar: $type", offers(Intent.ACTION_SEND, type))
        }
        assertFalse("TXT ficou de fora", offers(Intent.ACTION_VIEW, "text/plain"))
    }

    @Test
    fun openWithAddsTheBookAndOpensTheReader() {
        val source = downloaded("Duna.pdf")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(source), "application/pdf")
        val controller = Robolectric.buildActivity(OpenActivity::class.java, intent).setup()
        val deadline = System.currentTimeMillis() + 10_000
        while (!controller.get().isFinishing && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val started = shadowOf(controller.get()).nextStartedActivity
        assertNotNull(started)
        assertEquals(ReaderActivity::class.java.name, started.component!!.className)
        val id = started.getLongExtra(ReaderActivity.EXTRA_ITEM_ID, -1)
        LibraryDatabase(context).use { db -> assertEquals("Duna.pdf", db.find(id)!!.name) }

        // Compartilhar o mesmo arquivo reabre o mesmo livro.
        val share = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(source))
        val second = Robolectric.buildActivity(OpenActivity::class.java, share).setup()
        val deadline2 = System.currentTimeMillis() + 10_000
        while (!second.get().isFinishing && System.currentTimeMillis() < deadline2) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(id, shadowOf(second.get()).nextStartedActivity.getLongExtra(ReaderActivity.EXTRA_ITEM_ID, -1))
        LibraryDatabase(context).use { db -> assertEquals(1, db.all().size) }
    }

    private fun all(root: View): List<View> {
        val result = mutableListOf(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) result += all(root.getChildAt(i))
        return result
    }

    @Test
    fun archivedBookShowsOnTheShelfAndStatisticsCanBeCleared() {
        LibraryDatabase(context).use { db ->
            val id = db.add("Duna.pdf", "content://1", "pdf")!!
            db.setFinished(id, true)
            db.archive(id)
            db.addReadingTime(id, Days.today(), 600)
        }
        context.getSharedPreferences("main", Context.MODE_PRIVATE).edit().clear().commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        all(activity.window.decorView).filterIsInstance<TextView>().first { it.text.startsWith("▶  🏆 Estante") }.performClick()
        var shown = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(shown.toString(), shown.any { it.endsWith("· arquivo removido · ⏱ 10 min") })

        all(activity.window.decorView).filterIsInstance<TextView>().first { it.text == "Estatísticas" }.performClick()
        all(activity.window.decorView).filterIsInstance<TextView>().first { it.text == "Limpar estatísticas…" }.performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        shown = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(shown.contains("🔥 0"))
        LibraryDatabase(context).use { db ->
            assertEquals(1, db.all().size)
            assertEquals(0L, db.all().single().readingSeconds)
        }
    }
}
