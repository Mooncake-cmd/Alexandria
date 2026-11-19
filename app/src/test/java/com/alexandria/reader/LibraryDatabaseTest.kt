package com.alexandria.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LibraryDatabaseTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase("alexandria.db")
    }

    @Test
    fun upgradeFromVersion2KeepsItemsAndProgress() {
        // Recria o banco exatamente como a versão 0.2.0 o deixava.
        val file = context.getDatabasePath("alexandria.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                """
                CREATE TABLE library_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    uri TEXT NOT NULL UNIQUE,
                    format TEXT NOT NULL,
                    added_at INTEGER NOT NULL,
                    last_position INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, last_position) VALUES ('Livro', 'content://a', 'pdf', 10, 41)")
            old.version = 2
        }

        database = LibraryDatabase(context)
        val items = database.all()
        assertEquals(1, items.size)
        assertEquals("Livro", items[0].name)
        assertEquals(41, items[0].position)
        assertEquals(0, items[0].pageCount)
        assertEquals(0L, items[0].openedAt)
        assertFalse(items[0].finished)
        assertEquals("", items[0].shelf)
        // A tabela de anotações também foi criada na migração.
        database.addNote(items[0].id, "trecho", "nota", 3)
        assertEquals(1, database.notesFor(items[0].id).size)
    }

    @Test
    fun notesAreOrderedByPositionAndDeletedWithTheBook() {
        database = LibraryDatabase(context)
        val id = database.add("Livro", "content://n", "txt")!!
        val other = database.add("Outro", "content://o", "txt")!!
        database.addNote(id, "  fim  ", "", 9_000)
        val first = database.addNote(id, "começo", "minha nota", 100)
        database.addNote(other, "outro livro", "", 50)
        assertEquals(listOf("começo", "fim"), database.notesFor(id).map { it.excerpt })
        assertEquals(3, database.allNotes().size)

        database.updateNote(first, " editada ")
        assertEquals("editada", database.notesFor(id).first().text)

        database.remove(id)
        assertEquals(listOf("outro livro"), database.allNotes().map { it.excerpt })
    }

    @Test
    fun finishedAndShelves() {
        database = LibraryDatabase(context)
        val a = database.add("A", "content://a", "pdf")!!
        val b = database.add("B", "content://b", "pdf")!!
        database.setFinished(a, true)
        assertTrue(database.find(a)!!.finished)
        database.setFinished(a, false)
        assertFalse(database.find(a)!!.finished)

        database.setShelf(a, " Ficção ")
        database.setShelf(b, "estudos")
        assertEquals(listOf("estudos", "Ficção"), database.shelves())
        database.renameShelf("Ficção", "Romances")
        assertEquals("Romances", database.find(a)!!.shelf)
        database.setShelf(b, "")
        assertEquals(listOf("Romances"), database.shelves())
    }

    @Test
    fun upgradeFromVersion3KeepsProgress() {
        val file = context.getDatabasePath("alexandria.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                """
                CREATE TABLE library_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    uri TEXT NOT NULL UNIQUE,
                    format TEXT NOT NULL,
                    added_at INTEGER NOT NULL,
                    last_position INTEGER NOT NULL DEFAULT 0,
                    page_count INTEGER NOT NULL DEFAULT 0,
                    opened_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, last_position, page_count, opened_at) VALUES ('Livro', 'content://a', 'pdf', 10, 41, 300, 99)")
            old.version = 3
        }
        database = LibraryDatabase(context)
        val item = database.all().single()
        assertEquals(41, item.position)
        assertEquals(300, item.pageCount)
        assertEquals(99L, item.openedAt)
        assertFalse(item.finished)
    }

    @Test
    fun addingTheSameUriTwiceOnlyRenames() {
        database = LibraryDatabase(context)
        assertNotNull(database.add("Antigo", "content://x", "txt"))
        val id = database.all().single().id
        database.updateProgress(id, 5_000)
        assertNull(database.add("Novo", "content://x", "txt"))
        val item = database.all().single()
        assertEquals("Novo", item.name)
        assertEquals(5_000, item.position)
    }

    @Test
    fun updateProgressStoresPageCountAndMovesItemToTop() {
        database = LibraryDatabase(context)
        database.add("Primeiro", "content://1", "pdf")
        Thread.sleep(5)
        database.add("Segundo", "content://2", "pdf")
        assertEquals("Segundo", database.all().first().name)

        val first = database.all().first { it.name == "Primeiro" }
        Thread.sleep(5)
        database.updateProgress(first.id, 3, pageCount = 120)
        val updated = database.all()
        assertEquals("Primeiro", updated.first().name)
        assertEquals(120, updated.first().pageCount)
        assertEquals(3, updated.first().position)

        // Sem pageCount, o total já salvo é mantido.
        database.updateProgress(first.id, 4)
        assertEquals(120, database.find(first.id)!!.pageCount)
    }

    @Test
    fun removeDeletesTheItem() {
        database = LibraryDatabase(context)
        database.add("Livro", "content://r", "pdf")
        val id = database.all().single().id
        assertNotNull(database.find(id))
        database.remove(id)
        assertTrue(database.all().isEmpty())
    }

    @Test
    fun clearingOneBookTimeLowersTotalAndItsDays() {
        database = LibraryDatabase(context)
        val a = database.add("A.epub", "content://a", "epub")!!
        val b = database.add("B.epub", "content://b", "epub")!!
        database.addReadingTime(a, "2026-11-10", 300)
        database.addReadingTime(a, "2026-11-11", 300)
        database.addReadingTime(b, "2026-11-11", 1_200)
        database.addReadingTime(b, "2026-11-12", 600)
        assertEquals(2_400L, database.readingDays().values.sum())

        assertEquals(600L, database.clearBookTime(a))
        assertEquals(0L, database.find(a)!!.readingSeconds)
        assertEquals(1_800L, database.find(b)!!.readingSeconds)
        // O dia só do livro A some; o dia dividido fica só com o tempo do B.
        assertEquals(mapOf("2026-11-11" to 1_200L, "2026-11-12" to 600L), database.readingDays())
        assertEquals(1_800L, database.readingDays().values.sum())
    }

    @Test
    fun timeFromBeforeTheLogIsRemovedFromTheTotalToo() {
        // Banco da 0.14 (versão 6): o tempo por dia existe, mas não se sabe de qual livro.
        val file = context.getDatabasePath("alexandria.db").apply { parentFile?.mkdirs() }
        LibraryDatabase(context).use { it.writableDatabase }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("DROP TABLE reading_log")
            old.execSQL("DROP TABLE bookmarks")
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, reading_seconds) VALUES ('A.pdf', 'content://a', 'pdf', 0, 600)")
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, reading_seconds) VALUES ('B.pdf', 'content://b', 'pdf', 0, 1800)")
            old.execSQL("INSERT INTO reading_days (day, seconds) VALUES ('2026-11-01', 1200), ('2026-11-02', 1200)")
            old.version = 6
        }
        database = LibraryDatabase(context)
        val a = database.all().first { it.name == "A.pdf" }
        // Depois da atualização, um tempo novo do livro A já fica registrado no dia.
        database.addReadingTime(a.id, "2026-11-03", 120)
        assertEquals(2_520L, database.readingDays().values.sum())

        assertEquals(720L, database.clearBookTime(a.id))
        // Total cai exatamente 12 min: 2 min do dia registrado e 10 min repartidos entre os dias antigos.
        assertEquals(1_800L, database.readingDays().values.sum())
        assertEquals(setOf("2026-11-01", "2026-11-02"), database.readingDays().keys)
        assertEquals(1_800L, database.all().first { it.name == "B.pdf" }.readingSeconds)
    }

    @Test
    fun spreadRemovalIsProportionalAndExact() {
        assertEquals(mapOf("a" to 75L, "b" to 25L), ReadingTime.spreadRemoval(mapOf("a" to 300L, "b" to 100L), 100))
        assertEquals(10L, ReadingTime.spreadRemoval(mapOf("a" to 7L, "b" to 7L, "c" to 7L), 10).values.sum())
        assertEquals(mapOf("a" to 5L), ReadingTime.spreadRemoval(mapOf("a" to 5L, "b" to 0L), 50))
        assertEquals(emptyMap<String, Long>(), ReadingTime.spreadRemoval(emptyMap(), 50))
    }

    @Test
    fun exactPositionIsSavedAndSurvivesTheUpgrade() {
        // Banco da 0.16 (versão 8): sem a posição precisa.
        val file = context.getDatabasePath("alexandria.db").apply { parentFile?.mkdirs() }
        LibraryDatabase(context).use { it.writableDatabase }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, last_position) VALUES ('Moby.epub', 'content://m', 'epub', 0, 438)")
            old.version = 8
        }
        database = LibraryDatabase(context)
        val item = database.all().single()
        assertEquals(-1L, item.exactPosition)
        // Começo do capítulo 17 de 365: 438 arredondado cairia no fim do capítulo 16; o preciso não.
        database.updateProgress(item.id, 438, 365, 43_836)
        val saved = database.find(item.id)!!
        assertEquals(438, saved.position)
        assertEquals(43_836L, saved.exactPosition)
        assertEquals(16, (saved.exactPosition.toDouble() * 365 / 1_000_000 + 1e-9).toInt())
        assertEquals(15, (saved.position.toDouble() * 365 / 10_000 + 1e-6).toInt())
    }
}

