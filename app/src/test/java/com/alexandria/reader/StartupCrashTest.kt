package com.alexandria.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Abrir o app vindo de uma versão antiga (banco v9, nuvem do Telegram com grupo, sem abas). */
@RunWith(RobolectricTestRunner::class)
class StartupCrashTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun oldDatabase() {
        context.deleteDatabase("alexandria.db")
        val file = context.getDatabasePath("alexandria.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE library_items (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, uri TEXT NOT NULL UNIQUE, format TEXT NOT NULL,
                added_at INTEGER NOT NULL, last_position INTEGER NOT NULL DEFAULT 0, page_count INTEGER NOT NULL DEFAULT 0, opened_at INTEGER NOT NULL DEFAULT 0,
                finished_at INTEGER NOT NULL DEFAULT 0, shelf TEXT NOT NULL DEFAULT '', reading_seconds INTEGER NOT NULL DEFAULT 0, file_size INTEGER NOT NULL DEFAULT 0,
                archived_at INTEGER NOT NULL DEFAULT 0, exact_position INTEGER NOT NULL DEFAULT -1)""")
            old.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL, excerpt TEXT NOT NULL, text TEXT NOT NULL, position INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE reading_days (day TEXT PRIMARY KEY, seconds INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE terms (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL, term TEXT NOT NULL, folded TEXT NOT NULL, definition TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(item_id, folded))")
            old.execSQL("CREATE TABLE reading_log (item_id INTEGER NOT NULL, day TEXT NOT NULL, seconds INTEGER NOT NULL, PRIMARY KEY (item_id, day))")
            old.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            old.execSQL("INSERT INTO library_items (name, uri, format, added_at, last_position, file_size) VALUES ('Moby.epub', 'content://m', 'epub', 0, 438, 1234)")
            old.version = 9
        }
    }

    private fun open() {
        Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun fromOldVersionWithTelegramGroup() {
        oldDatabase()
        for (n in listOf("tabs", "telegram", "updates")) context.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("telegram", Context.MODE_PRIVATE).edit().putInt("api_id", 1).putString("api_hash", "x").putString("session", "s")
            .putString("chat", CloudChat("777", "1", "Grupo").toJson().toString()).commit()
        open()
    }

    @Test
    fun withAVideoTabOpen() {
        oldDatabase()
        context.getSharedPreferences("telegram", Context.MODE_PRIVATE).edit().putInt("api_id", 1).putString("api_hash", "x").putString("session", "s").commit()
        AppTabs.save(context, AppTabs.all(context) + AppTab("filmes", "Filmes", AppTabs.MOVIES, CloudChat("888", "1", "F")))
        AppTabs.setCurrent(context, "filmes")
        open()
    }

    @Test
    fun withoutTelegram() {
        oldDatabase()
        for (n in listOf("tabs", "telegram")) context.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit()
        open()
    }
}
