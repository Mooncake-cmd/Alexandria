package com.alexandria.reader

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * [position] guarda o ponto de leitura: índice da página (PDF) ou fração rolada de 0 a 10000 (TXT).
 */
data class LibraryItem(
    val id: Long,
    val name: String,
    val uri: String,
    val format: String,
    val addedAt: Long,
    val position: Int
)

class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "alexandria.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE library_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                uri TEXT NOT NULL UNIQUE,
                format TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                last_position INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrações sempre preservam os dados do usuário.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE library_items ADD COLUMN last_position INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Adiciona o documento. Se o mesmo URI já existir, mantém o registro (id, data e posição de
     * leitura) e só atualiza o nome. Retorna true se o documento era novo.
     */
    fun add(name: String, uri: String, format: String): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("uri", uri)
            put("format", format)
            put("added_at", System.currentTimeMillis())
        }
        val id = db.insertWithOnConflict("library_items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id != -1L) return true
        val rename = ContentValues().apply { put("name", name) }
        db.update("library_items", rename, "uri = ?", arrayOf(uri))
        return false
    }

    fun all(): List<LibraryItem> {
        val result = mutableListOf<LibraryItem>()
        readableDatabase.rawQuery("$SELECT ORDER BY added_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toItem()
        }
        return result
    }

    fun find(id: Long): LibraryItem? =
        readableDatabase.rawQuery("$SELECT WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toItem() else null
        }

    fun updatePosition(id: Long, position: Int) {
        val values = ContentValues().apply { put("last_position", position) }
        writableDatabase.update("library_items", values, "id = ?", arrayOf(id.toString()))
    }

    fun remove(id: Long) {
        writableDatabase.delete("library_items", "id = ?", arrayOf(id.toString()))
    }

    private fun Cursor.toItem() = LibraryItem(
        getLong(0), getString(1), getString(2), getString(3), getLong(4), getInt(5)
    )

    private companion object {
        const val SELECT = "SELECT id, name, uri, format, added_at, last_position FROM library_items"
    }
}
