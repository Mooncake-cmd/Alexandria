package com.alexandria.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Aba da tela inicial (o mesmo modelo do site): tipo e, se quiser nuvem, o grupo privado dela. */
data class AppTab(
    val id: String,
    val name: String,
    /** "livros", "filmes" ou "cursos". */
    val type: String,
    /** Grupo da nuvem. Na aba principal, null = Mensagens salvas; nas outras, sem nuvem. */
    val chat: CloudChat?
) {
    val isVideo: Boolean get() = type != AppTabs.BOOKS
    val isMain: Boolean get() = id == AppTabs.MAIN

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("chat", chat?.toJson() ?: JSONObject.NULL)
}

/**
 * Abas guardadas no aparelho. A aba principal de livros ("livros") sempre existe; as outras a pessoa
 * cria. As regras (conferir a lista, código novo) são Kotlin puro, cobertas por testes.
 */
object AppTabs {
    const val MAIN = "livros"
    const val BOOKS = "livros"
    const val MOVIES = "filmes"
    const val COURSES = "cursos"
    private const val PREFS = "tabs"

    val TYPES = listOf(
        Triple(BOOKS, "Livros", "Livros, quadrinhos e documentos, com leitor e anotações."),
        Triple(MOVIES, "Filmes", "Vídeos do grupo; os tópicos do grupo viram coleções."),
        Triple(COURSES, "Cursos", "Cada tópico do grupo é um curso, com as aulas em ordem.")
    )

    fun typeLabel(type: String) = TYPES.firstOrNull { it.first == type }?.second ?: type

    /** Código novo e único para uma aba, tirado do nome ("Filmes da Família" → "filmes-da-familia"). */
    fun newId(name: String, existing: Collection<String>): String {
        val base = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "aba" }
        var id = base
        var n = 2
        while (id == MAIN || id in existing) id = "$base-${n++}"
        return id
    }

    /** Lista conferida: a principal sempre existe e vem primeiro, sem repetidas. */
    fun normalize(json: String?, legacyChat: CloudChat? = null): List<AppTab> {
        val array = try {
            JSONArray(json ?: "[]")
        } catch (e: org.json.JSONException) {
            JSONArray()
        }
        val seen = mutableSetOf<String>()
        val tabs = mutableListOf<AppTab>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty() || !seen.add(id)) continue
            val raw = o.optString("type")
            val type = if (id != MAIN && (raw == MOVIES || raw == COURSES)) raw else BOOKS
            tabs += AppTab(id, o.optString("name").trim().ifEmpty { typeLabel(type) }, type, CloudChat.from(o.optJSONObject("chat")))
        }
        val main = tabs.firstOrNull { it.isMain } ?: AppTab(MAIN, "Livros", BOOKS, legacyChat)
        return listOf(main) + tabs.filter { !it.isMain }
    }

    fun all(context: Context): List<AppTab> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return normalize(p.getString("list", null), TelegramCloud.settings(context)?.chat)
    }

    fun save(context: Context, tabs: List<AppTab>) {
        val list = normalize(JSONArray(tabs.map { it.toJson() }).toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun byId(context: Context, id: String?): AppTab = all(context).let { tabs -> tabs.firstOrNull { it.id == id } ?: tabs.first() }

    fun update(context: Context, id: String, change: (AppTab) -> AppTab) = save(context, all(context).map { if (it.id == id) change(it).copy(id = it.id) else it })

    /** Aba aberta na tela inicial. */
    fun current(context: Context): AppTab = byId(context, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("current", MAIN))

    fun setCurrent(context: Context, id: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("current", id).apply()

    /** Aba de livros para o que chegar de fora ("Abrir com", importar): a aberta, se for de livros. */
    fun importTab(context: Context): String = current(context).let { if (it.isVideo) MAIN else it.id }

    /** Conversa da nuvem de uma aba de livros: o grupo; a principal sem grupo usa as Mensagens salvas. */
    fun cloudOf(context: Context, tabId: String?): Pair<Boolean, CloudChat?> {
        val tab = byId(context, tabId)
        return Pair(tab.chat != null || tab.isMain, tab.chat)
    }
}
