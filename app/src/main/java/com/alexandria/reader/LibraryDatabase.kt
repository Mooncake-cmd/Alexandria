package com.alexandria.reader

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * [position] guarda o ponto de leitura. Formatos paginados (PDF, CBZ) usam o índice da página;
 * os de fluxo contínuo (TXT, DOCX, EPUB) usam uma fração de 0 a 10000 do documento inteiro.
 * [pageCount] é o total de páginas (PDF/CBZ) ou de capítulos (EPUB); 0 enquanto não é conhecido.
 * [openedAt] é a última vez em que o leitor salvou progresso (0 se nunca foi lido).
 * [finishedAt] é quando o livro foi marcado como concluído (0 se não foi): ele vai para a Estante.
 * [shelf] é o nome da prateleira ("" quando o livro está solto na biblioteca).
 */
data class LibraryItem(
    val id: Long,
    val name: String,
    val uri: String,
    val format: String,
    val addedAt: Long,
    val position: Int,
    val pageCount: Int = 0,
    val openedAt: Long = 0,
    val finishedAt: Long = 0,
    val shelf: String = "",
    /** Tempo total de leitura deste livro, em segundos. */
    val readingSeconds: Long = 0
) {
    val finished: Boolean get() = finishedAt > 0
}

/**
 * Termo do glossário de um livro: uma palavra ou expressão ("Arrakis", "Casa Atreides") com a
 * definição que o leitor escreveu. As ocorrências no texto ficam sublinhadas e mostram a definição.
 */
data class GlossaryTerm(val id: Long, val itemId: Long, val term: String, val definition: String, val createdAt: Long)

/**
 * Anotação feita durante a leitura. [excerpt] é o trecho selecionado (vazio numa anotação de
 * página de PDF/CBZ, onde não há texto para selecionar) e [position] usa as mesmas unidades de
 * [LibraryItem.position], para o leitor conseguir voltar ao ponto exato.
 */
data class Note(
    val id: Long,
    val itemId: Long,
    val excerpt: String,
    val text: String,
    val position: Int,
    val createdAt: Long
)

class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "alexandria.db", null, 5) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE library_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                uri TEXT NOT NULL UNIQUE,
                format TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                last_position INTEGER NOT NULL DEFAULT 0,
                page_count INTEGER NOT NULL DEFAULT 0,
                opened_at INTEGER NOT NULL DEFAULT 0,
                finished_at INTEGER NOT NULL DEFAULT 0,
                shelf TEXT NOT NULL DEFAULT '',
                reading_seconds INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        createNotesTable(db)
        createReadingTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrações sempre preservam os dados do usuário.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE library_items ADD COLUMN last_position INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE library_items ADD COLUMN page_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE library_items ADD COLUMN opened_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE library_items ADD COLUMN finished_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE library_items ADD COLUMN shelf TEXT NOT NULL DEFAULT ''")
            createNotesTable(db)
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE library_items ADD COLUMN reading_seconds INTEGER NOT NULL DEFAULT 0")
            createReadingTables(db)
        }
    }

    /** Tempo lido por dia (para a sequência de dias) e glossário de termos por livro. */
    private fun createReadingTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reading_days (day TEXT PRIMARY KEY, seconds INTEGER NOT NULL)")
        db.execSQL("""
            CREATE TABLE terms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id INTEGER NOT NULL REFERENCES library_items(id) ON DELETE CASCADE,
                term TEXT NOT NULL,
                folded TEXT NOT NULL,
                definition TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(item_id, folded)
            )
        """.trimIndent())
    }

    private fun createNotesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id INTEGER NOT NULL REFERENCES library_items(id) ON DELETE CASCADE,
                excerpt TEXT NOT NULL,
                text TEXT NOT NULL,
                position INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX notes_item ON notes(item_id)")
    }

    /**
     * Adiciona o documento. Se o mesmo URI já existir, mantém o registro (id, data e posição de
     * leitura) e só atualiza o nome. Retorna o id do novo documento, ou null se ele já existia.
     */
    fun add(name: String, uri: String, format: String): Long? {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("uri", uri)
            put("format", format)
            put("added_at", System.currentTimeMillis())
        }
        val id = db.insertWithOnConflict("library_items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id != -1L) return id
        val rename = ContentValues().apply { put("name", name) }
        db.update("library_items", rename, "uri = ?", arrayOf(uri))
        return null
    }

    /** Mais recentes primeiro: o que foi lido ou importado por último aparece no topo. */
    fun all(): List<LibraryItem> {
        val result = mutableListOf<LibraryItem>()
        readableDatabase.rawQuery("$SELECT ORDER BY MAX(opened_at, added_at) DESC, id DESC", null).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toItem()
        }
        return result
    }

    fun find(id: Long): LibraryItem? =
        readableDatabase.rawQuery("$SELECT WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toItem() else null
        }

    /** Salva o ponto de leitura. [pageCount] só é gravado quando conhecido (maior que 0). */
    fun updateProgress(id: Long, position: Int, pageCount: Int = 0) {
        val values = ContentValues().apply {
            put("last_position", position)
            put("opened_at", System.currentTimeMillis())
            if (pageCount > 0) put("page_count", pageCount)
        }
        update(id, values)
    }

    /** Grava o total de páginas/capítulos descoberto na importação, sem mexer na data de leitura. */
    fun updatePageCount(id: Long, pageCount: Int) {
        if (pageCount > 0) update(id, ContentValues().apply { put("page_count", pageCount) })
    }

    /** Marca como concluído (vai para a Estante) ou devolve para "Lendo agora". */
    fun setFinished(id: Long, finished: Boolean) {
        update(id, ContentValues().apply { put("finished_at", if (finished) System.currentTimeMillis() else 0L) })
    }

    fun setShelf(id: Long, shelf: String) {
        update(id, ContentValues().apply { put("shelf", shelf.trim()) })
    }

    /** Renomeia uma prateleira em todos os livros que estão nela. */
    fun renameShelf(from: String, to: String) {
        val values = ContentValues().apply { put("shelf", to.trim()) }
        writableDatabase.update("library_items", values, "shelf = ?", arrayOf(from))
    }

    /** Nomes de prateleira em uso, em ordem alfabética. */
    fun shelves(): List<String> = all().map { it.shelf }.filter { it.isNotEmpty() }.distinct().sortedWith(Shelves.order)

    /** Remove o documento e, junto, as anotações dele. */
    fun remove(id: Long) {
        writableDatabase.delete("library_items", "id = ?", arrayOf(id.toString()))
    }

    private fun update(id: Long, values: ContentValues) {
        writableDatabase.update("library_items", values, "id = ?", arrayOf(id.toString()))
    }

    // ---------- tempo de leitura ----------

    /** Soma [seconds] ao tempo do livro e ao do dia [day] ("aaaa-mm-dd"). */
    fun addReadingTime(itemId: Long, day: String, seconds: Long) {
        if (seconds <= 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE library_items SET reading_seconds = reading_seconds + ? WHERE id = ?", arrayOf<Any>(seconds, itemId))
            db.execSQL("INSERT OR IGNORE INTO reading_days (day, seconds) VALUES (?, 0)", arrayOf<Any>(day))
            db.execSQL("UPDATE reading_days SET seconds = seconds + ? WHERE day = ?", arrayOf<Any>(seconds, day))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Segundos lidos por dia ("aaaa-mm-dd" → segundos). */
    fun readingDays(): Map<String, Long> {
        val result = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT day, seconds FROM reading_days", null).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = c.getLong(1)
        }
        return result
    }

    // ---------- glossário ----------

    /** Cria o termo ou, se ele já existe neste livro (sem diferenciar maiúsculas e acentos), troca a definição. */
    fun saveTerm(itemId: Long, term: String, definition: String): Long {
        val clean = term.trim().replace(Regex("\\s+"), " ")
        val values = ContentValues().apply {
            put("item_id", itemId)
            put("term", clean)
            put("folded", TextSearch.fold(clean))
            put("definition", definition.trim())
            put("created_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        val id = db.insertWithOnConflict("terms", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id != -1L) return id
        val update = ContentValues().apply {
            put("term", clean)
            put("definition", definition.trim())
        }
        db.update("terms", update, "item_id = ? AND folded = ?", arrayOf(itemId.toString(), TextSearch.fold(clean)))
        return termsFor(itemId).first { TextSearch.fold(it.term) == TextSearch.fold(clean) }.id
    }

    fun removeTerm(id: Long) {
        writableDatabase.delete("terms", "id = ?", arrayOf(id.toString()))
    }

    /** Termos de um livro, em ordem alfabética. */
    fun termsFor(itemId: Long): List<GlossaryTerm> = queryTerms("WHERE item_id = ? ORDER BY folded", arrayOf(itemId.toString()))

    fun allTerms(): List<GlossaryTerm> = queryTerms("ORDER BY item_id, folded", null)

    private fun queryTerms(clause: String, args: Array<String>?): List<GlossaryTerm> {
        val result = mutableListOf<GlossaryTerm>()
        readableDatabase.rawQuery("SELECT id, item_id, term, definition, created_at FROM terms $clause", args).use { c ->
            while (c.moveToNext()) result += GlossaryTerm(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4))
        }
        return result
    }

    // ---------- anotações ----------

    fun addNote(itemId: Long, excerpt: String, text: String, position: Int): Long {
        val values = ContentValues().apply {
            put("item_id", itemId)
            put("excerpt", excerpt.trim())
            put("text", text.trim())
            put("position", position)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("notes", null, values)
    }

    fun updateNote(id: Long, text: String) {
        val values = ContentValues().apply { put("text", text.trim()) }
        writableDatabase.update("notes", values, "id = ?", arrayOf(id.toString()))
    }

    fun removeNote(id: Long) {
        writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
    }

    /** Anotações de um livro, na ordem em que aparecem no texto. */
    fun notesFor(itemId: Long): List<Note> =
        queryNotes("WHERE item_id = ? ORDER BY position, id", arrayOf(itemId.toString()))

    /** Todas as anotações, das mais recentes para as mais antigas. */
    fun allNotes(): List<Note> = queryNotes("ORDER BY created_at DESC, id DESC", null)

    private fun queryNotes(clause: String, args: Array<String>?): List<Note> {
        val result = mutableListOf<Note>()
        readableDatabase.rawQuery("SELECT id, item_id, excerpt, text, position, created_at FROM notes $clause", args).use { c ->
            while (c.moveToNext()) result += Note(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5))
        }
        return result
    }

    private fun Cursor.toItem() = LibraryItem(
        getLong(0), getString(1), getString(2), getString(3), getLong(4), getInt(5), getInt(6), getLong(7),
        getLong(8), getString(9), getLong(10)
    )

    private companion object {
        const val SELECT = "SELECT id, name, uri, format, added_at, last_position, page_count, opened_at, " +
            "finished_at, shelf, reading_seconds FROM library_items"
    }
}

/** Regras das prateleiras. Kotlin puro, coberto por testes. */
object Shelves {
    val order: Comparator<String> = compareBy<String> { it.lowercase() }.thenBy { it }

    /**
     * Reaproveita uma prateleira existente quando o nome digitado só difere em maiúsculas ou
     * espaços, para não criar "Ficção" e "ficção " como duas prateleiras.
     */
    fun resolve(typed: String, existing: List<String>): String {
        val clean = typed.trim().replace(Regex("\\s+"), " ")
        return existing.firstOrNull { it.trim().equals(clean, ignoreCase = true) } ?: clean
    }
}
