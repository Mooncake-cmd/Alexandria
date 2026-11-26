package com.alexandria.reader

import android.app.Activity
import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.Executors

/**
 * Ponto de leitura de um livro, como fica guardado no gist: formato, posição (a comum e a precisa),
 * total de páginas/capítulos, quando foi salvo e em qual aparelho.
 */
data class SyncEntry(
    val format: String,
    val position: Int,
    val exact: Long,
    val pageCount: Int,
    val updated: Long,
    val device: String
)

/**
 * Formato do arquivo de sincronização (o mesmo do site). Kotlin puro, coberto por testes.
 * O livro é reconhecido pelo nome e pelo tamanho do arquivo, como na importação; no gist fica só
 * um código tirado dos dois (o nome do livro não sai do aparelho).
 */
object SyncData {
    const val FILE = "alexandria-posicoes.json"
    const val DESCRIPTION = "Alexandria: posições de leitura (sincronização entre aparelhos)"

    fun key(name: String, size: Long): String {
        val normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase().replace(Regex("\\s+"), " ")
        val digest = MessageDigest.getInstance("SHA-256").digest("$normalized|$size".toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun parse(json: String?): MutableMap<String, SyncEntry> {
        val result = LinkedHashMap<String, SyncEntry>()
        if (json.isNullOrBlank()) return result
        val books = try {
            JSONObject(json).optJSONObject("books")
        } catch (e: org.json.JSONException) {
            null
        } ?: return result
        for (key in books.keys()) {
            val b = books.optJSONObject(key) ?: continue
            result[key] = SyncEntry(
                b.optString("format"), b.optInt("position"), b.optLong("exact", -1), b.optInt("pageCount"),
                b.optLong("updated"), b.optString("device")
            )
        }
        return result
    }

    fun serialize(books: Map<String, SyncEntry>): String {
        val out = JSONObject()
        for ((key, e) in books.toSortedMap()) {
            out.put(key, JSONObject().apply {
                put("format", e.format)
                put("position", e.position)
                put("exact", e.exact)
                put("pageCount", e.pageCount)
                put("updated", e.updated)
                put("device", e.device)
            })
        }
        return JSONObject().put("format", "alexandria-sync").put("version", 1).put("books", out).toString(2)
    }

    /** Junta a posição deste aparelho às que já estão no gist: vale a mais recente de cada livro. */
    fun merge(books: MutableMap<String, SyncEntry>, key: String, entry: SyncEntry): Boolean {
        val old = books[key]
        if (old != null && old.updated >= entry.updated) return false
        books[key] = entry
        return true
    }

    /**
     * A posição do outro aparelho deve ser aplicada ao abrir o livro? Só se for mais recente que a
     * última leitura aqui e de fato outro ponto (não a mesma tela).
     */
    fun shouldApply(remote: SyncEntry?, localUpdated: Long, localExact: Long, span: Long, device: String): Boolean {
        // O nome do aparelho não decide nada (dois computadores podem ter o mesmo): vale a hora e o ponto.
        if (remote == null || remote.updated <= localUpdated) return false
        val remoteExact = if (remote.exact >= 0) remote.exact else return remote.position >= 0
        return kotlin.math.abs(remoteExact - localExact) >= maxOf(1L, span / 2)
    }
}

/** Erro da API do GitHub (401: token inválido ou sem permissão de gists). */
class SyncException(val code: Int, message: String) : Exception(message)

/**
 * Cliente da API de gists do GitHub. [transport] faz a requisição (método, endereço, corpo) e devolve
 * o código e o corpo da resposta; nos testes é trocado por um falso.
 */
class GistClient(
    private val token: String,
    private val transport: (method: String, url: String, body: String?, token: String) -> Pair<Int, String> = ::http
) {
    private fun call(method: String, path: String, body: String? = null): String {
        val (code, text) = transport(method, "https://api.github.com$path", body, token)
        if (code !in 200..299) throw SyncException(code, "GitHub respondeu $code")
        return text
    }

    /** Login da conta do token (confere se o token funciona). */
    fun login(): String = JSONObject(call("GET", "/user")).getString("login")

    /** O gist das posições: o que já existe na conta ou um novo, secreto. */
    fun findOrCreate(): String {
        for (page in 1..5) {
            val list = org.json.JSONArray(call("GET", "/gists?per_page=100&page=$page"))
            for (i in 0 until list.length()) {
                val gist = list.getJSONObject(i)
                if (gist.optJSONObject("files")?.has(SyncData.FILE) == true) return gist.getString("id")
            }
            if (list.length() < 100) break
        }
        val body = JSONObject()
            .put("description", SyncData.DESCRIPTION)
            .put("public", false)
            .put("files", JSONObject().put(SyncData.FILE, JSONObject().put("content", SyncData.serialize(emptyMap()))))
        return JSONObject(call("POST", "/gists", body.toString())).getString("id")
    }

    fun read(id: String): MutableMap<String, SyncEntry> {
        val file = JSONObject(call("GET", "/gists/$id")).optJSONObject("files")?.optJSONObject(SyncData.FILE)
        return SyncData.parse(file?.optString("content"))
    }

    fun write(id: String, books: Map<String, SyncEntry>) {
        val body = JSONObject().put("files", JSONObject().put(SyncData.FILE, JSONObject().put("content", SyncData.serialize(books))))
        call("PATCH", "/gists/$id", body.toString())
    }

    /** Lê, junta a posição deste aparelho e grava (só se ela for a mais recente). */
    fun push(id: String, key: String, entry: SyncEntry) {
        val books = read(id)
        if (SyncData.merge(books, key, entry)) write(id, books)
    }

    companion object {
        fun http(method: String, url: String, body: String?, token: String): Pair<Int, String> {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                // O HttpURLConnection do Android (OkHttp) aceita PATCH, que a API de gists usa para gravar.
                connection.requestMethod = method
                connection.connectTimeout = 8_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("User-Agent", "Alexandria")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                return Pair(code, text)
            } finally {
                connection.disconnect()
            }
        }
    }
}

/**
 * Sincronização da posição de leitura entre aparelhos pelo GitHub da própria pessoa (um gist
 * secreto). Só liga se um token for configurado em Ajustes; sem internet, tudo funciona igual.
 */
object PositionSync {
    private const val PREFS = "sync"
    private val executor = Executors.newSingleThreadExecutor()
    /** Troca do cliente nos testes. */
    @Volatile
    var clientFactory: (String) -> GistClient = { GistClient(it) }

    data class Settings(val token: String, val gistId: String, val login: String, val lastSync: Long, val error: String?)

    fun settings(context: Context): Settings? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString("token", null) ?: return null
        val gist = p.getString("gist", null) ?: return null
        return Settings(token, gist, p.getString("login", "") ?: "", p.getLong("last_sync", 0), p.getString("error", null))
    }

    fun device(): String = "celular (${android.os.Build.MODEL ?: "Android"})"

    /** Confere o token, acha (ou cria) o gist e guarda tudo. Roda em segundo plano. */
    fun connect(context: Activity, token: String, done: (Result<String>) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching {
                val client = clientFactory(token.trim())
                val login = client.login()
                val gist = client.findOrCreate()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("token", token.trim()).putString("gist", gist).putString("login", login)
                    .putLong("last_sync", System.currentTimeMillis()).remove("error").apply()
                login
            }
            context.runOnUiThread { done(result) }
        }
    }

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Por onde vai a posição: "telegram", "github" ou null. Vale o escolhido em Ajustes; sem escolha,
     * o único configurado (com os dois, o Telegram).
     */
    fun provider(context: Context): String? {
        val chosen = context.getSharedPreferences("cloud", Context.MODE_PRIVATE).getString("provider", null)
        val telegram = TelegramCloud.connected(context)
        val github = settings(context) != null
        return when {
            chosen == "telegram" && telegram -> "telegram"
            chosen == "github" && github -> "github"
            telegram -> "telegram"
            github -> "github"
            else -> null
        }
    }

    fun setProvider(context: Context, provider: String) {
        context.getSharedPreferences("cloud", Context.MODE_PRIVATE).edit().putString("provider", provider).apply()
    }

    /** Posição deste livro no gist ou no Telegram (null sem sincronização, sem internet ou sem registro). */
    fun pull(context: Activity, name: String, size: Long, done: (SyncEntry?) -> Unit) {
        if (provider(context) == "telegram") return TelegramCloud.pullPosition(context, SyncData.key(name, size), done)
        val s = settings(context) ?: return done(null)
        val app = context.applicationContext
        executor.execute {
            val entry = try {
                val books = clientFactory(s.token).read(s.gistId)
                ok(app)
                books[SyncData.key(name, size)]
            } catch (e: Exception) {
                fail(app, e)
                null
            }
            context.runOnUiThread { done(entry) }
        }
    }

    /** Grava a posição deste livro (em segundo plano; erros de rede são ignorados). */
    fun push(context: Context, name: String, size: Long, entry: SyncEntry) {
        if (provider(context) == "telegram") return TelegramCloud.pushPosition(context, SyncData.key(name, size), entry)
        val s = settings(context) ?: return
        val app = context.applicationContext
        executor.execute {
            try {
                clientFactory(s.token).push(s.gistId, SyncData.key(name, size), entry)
                ok(app)
            } catch (e: Exception) {
                fail(app, e)
            }
        }
    }

    /** Espera as gravações pendentes (testes). */
    fun drain() {
        executor.submit {}.get()
    }

    private fun ok(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_sync", System.currentTimeMillis()).remove("error").apply()
    }

    private fun fail(context: Context, e: Exception) {
        // Sem internet não é erro de configuração; token recusado é.
        if (e is SyncException && (e.code == 401 || e.code == 403 || e.code == 404)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("error", if (e.code == 404) "O gist das posições não foi encontrado." else "O GitHub recusou o token (vencido ou sem permissão de gists).").apply()
        }
    }
}
