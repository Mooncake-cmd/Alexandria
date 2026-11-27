package com.alexandria.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Abas da tela inicial: lista conferida, códigos novos, livros por aba e migração do grupo antigo. */
@RunWith(RobolectricTestRunner::class)
class AppTabsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val group = CloudChat("777", "1", "Biblioteca")

    @Before
    fun setUp() {
        for (name in listOf("tabs", "telegram")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("alexandria.db")
    }

    @Test
    fun mainTabAlwaysFirstAndInheritsTheOldGroup() {
        assertEquals(listOf(AppTab("livros", "Livros", "livros", null)), AppTabs.normalize(null))
        assertEquals(group, AppTabs.normalize("lixo", group)[0].chat)
        val json = JSONArray()
            .put(JSONObject().put("id", "filmes").put("name", "Filmes").put("type", "filmes").put("chat", group.toJson()))
            .put(JSONObject().put("id", "livros").put("name", "Meus livros").put("type", "cursos"))
            .put(JSONObject().put("id", "filmes").put("name", "repetida").put("type", "livros"))
            .put(JSONObject().put("id", "").put("name", "sem id"))
            .put(JSONObject().put("id", "x").put("name", " ").put("type", "esquisito"))
        val tabs = AppTabs.normalize(json.toString())
        assertEquals(listOf("livros", "filmes", "x"), tabs.map { it.id })
        assertEquals(listOf("Meus livros", "Filmes", "Livros"), tabs.map { it.name })
        assertEquals(listOf("livros", "filmes", "livros"), tabs.map { it.type })
        assertEquals(group, tabs[1].chat)
    }

    @Test
    fun newIdsAreUniqueWithoutAccents() {
        assertEquals("filmes-da-familia", AppTabs.newId("Filmes da Família", emptyList()))
        assertEquals("filmes-2", AppTabs.newId("Filmes", listOf("filmes")))
        assertEquals("livros-2", AppTabs.newId("Livros", emptyList()))
        assertEquals("aba", AppTabs.newId("!!!", emptyList()))
    }

    @Test
    fun storeSavesCurrentTabAndMigratesTheOldGroup() {
        context.getSharedPreferences("telegram", Context.MODE_PRIVATE).edit().putString("session", "s").putString("chat", group.toJson().toString()).commit()
        assertEquals(group, AppTabs.all(context)[0].chat)
        AppTabs.save(context, AppTabs.all(context) + AppTab("cursos", "Cursos", AppTabs.COURSES, null))
        AppTabs.setCurrent(context, "cursos")
        assertEquals("cursos", AppTabs.current(context).id)
        // Aba de vídeos aberta: o que chegar de fora vai para a aba principal de livros.
        assertEquals(AppTabs.MAIN, AppTabs.importTab(context))
        AppTabs.update(context, AppTabs.MAIN) { it.copy(chat = null, name = "Biblioteca") }
        assertNull(AppTabs.all(context)[0].chat)
        assertEquals("Biblioteca", AppTabs.all(context)[0].name)
        // Aba que não existe mais: volta para a principal.
        AppTabs.setCurrent(context, "sumiu")
        assertEquals(AppTabs.MAIN, AppTabs.current(context).id)
        assertEquals(Pair(true, null), AppTabs.cloudOf(context, AppTabs.MAIN))
        assertEquals(Pair(false, null), AppTabs.cloudOf(context, "cursos"))
    }

    @Test
    fun booksBelongToTabsAndComeBackWhenATabIsDeleted() {
        LibraryDatabase(context).use { db ->
            val a = db.add("Dom Casmurro.txt", "content://a", "txt")!!
            val b = db.add("Watchmen.cbz", "content://b", "cbz", 10, "hqs")!!
            assertEquals(AppTabs.MAIN, db.find(a)!!.tab)
            assertEquals("hqs", db.find(b)!!.tab)
            db.setTab(a, "hqs")
            assertEquals(2, db.moveTab("hqs"))
            assertEquals(listOf(AppTabs.MAIN, AppTabs.MAIN), db.all().map { it.tab })
        }
    }
}
