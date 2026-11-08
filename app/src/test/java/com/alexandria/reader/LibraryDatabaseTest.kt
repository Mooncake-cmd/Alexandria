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
}
