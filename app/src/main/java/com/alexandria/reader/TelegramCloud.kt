package com.alexandria.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Livro guardado na nuvem (Mensagens salvas ou grupo privado do Telegram). */
data class CloudBook(
    val key: String,
    val name: String,
    val size: Long,
    val format: String,
    val messageId: Int,
    val date: Long,
    /** Quem enviou (num grupo). */
    val sender: String = "",
    /** Mandado pelo próprio Telegram, com #alexandria_livro escrito na legenda. */
    val manual: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("name", name).put("size", size).put("format", format).put("messageId", messageId).put("date", date)
        .apply {
            if (sender.isNotEmpty()) put("sender", sender)
            if (manual) put("manual", true)
        }

    companion object {
        fun from(o: JSONObject) = CloudBook(
            o.getString("key"), o.getString("name"), o.optLong("size"), o.optString("format"), o.getInt("messageId"), o.optLong("date"),
            o.optString("sender"), o.optBoolean("manual")
        )
    }
}

/** Grupo privado onde fica a nuvem (o access_hash é preciso para falar com ele). */
data class CloudChat(val id: String, val accessHash: String, val title: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("accessHash", accessHash).put("title", title)

    companion object {
        fun from(o: JSONObject?) = o?.let { CloudChat(it.optString("id"), it.optString("accessHash"), it.optString("title")) }?.takeIf { it.id.isNotEmpty() }
    }
}

/** Anúncio oficial do Telegram para o grupo da nuvem (regra 3.3 dos termos da API). */
data class SponsoredItem(
    val id: String,
    val title: String,
    val message: String,
    val url: String,
    val buttonText: String,
    val recommended: Boolean,
    val info: String
) {
    companion object {
        fun from(o: JSONObject) = SponsoredItem(
            o.getString("id"), o.optString("title"), o.optString("message"), o.optString("url"), o.optString("buttonText"),
            o.optBoolean("recommended"), listOf(o.optString("sponsorInfo"), o.optString("additionalInfo")).filter { it.isNotBlank() }.joinToString("\n\n")
        )

        /** Endereços do próprio Telegram abrem sem confirmação (como pede a documentação). */
        fun isTelegramUrl(url: String): Boolean {
            val host = try {
                android.net.Uri.parse(url).host ?: return false
            } catch (e: Exception) {
                return false
            }
            return Regex("(^|\\.)(telegram\\.(org|me|dog)|t\\.me|te\\.?legra\\.ph|graph\\.org|fragment\\.com|telesco\\.pe)$").containsMatchIn(host)
        }
    }
}

/** Falha do Telegram: [code] é o código dele (PHONE_CODE_INVALID, FLOOD_WAIT_…) e [message], em português. */
class CloudException(val code: String, message: String) : Exception(message)

/**
 * Quem fala com o Telegram. O de verdade ([WebViewCloudEngine]) roda o motor do site (GramJS) num
 * WebView escondido; os testes usam um falso. [call] devolve o resultado em JSON.
 */
interface CloudEngine {
    fun call(method: String, args: JSONObject, onProgress: ((Float) -> Unit)?, done: (Result<String>) -> Unit)
}

/** Arquivos que o motor lê (envio) ou grava (download) em pedaços, por um código de uso único. */
object CloudFiles {
    private val reads = ConcurrentHashMap<String, File>()
    private val writes = ConcurrentHashMap<String, File>()
    private val counter = AtomicInteger()

    fun forReading(file: File): String = "r${counter.incrementAndGet()}".also { reads[it] = file }
    fun forWriting(file: File): String = "w${counter.incrementAndGet()}".also { writes[it] = file }
    fun release(token: String) {
        reads.remove(token)
        writes.remove(token)
    }

    fun read(token: String, offset: Long, length: Int): ByteArray {
        val file = reads[token] ?: return ByteArray(0)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = raf.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            return if (read == length) buffer else buffer.copyOf(read)
        }
    }

    fun append(token: String, bytes: ByteArray): Boolean {
        val file = writes[token] ?: return false
        FileOutputStream(file, true).use { it.write(bytes) }
        return true
    }
}

/**
 * Motor de verdade: um WebView escondido com o motor da nuvem (assets/cloud, gerado do código do site
 * por `npm run build:android-cloud`). Só a página do próprio app é carregada nele; a GramJS abre a
 * conexão segura (wss) com os servidores do Telegram.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class WebViewCloudEngine(context: Context) : CloudEngine {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready = false
    private val waiting = mutableListOf<() -> Unit>()
    private val pending = ConcurrentHashMap<String, Pair<((Float) -> Unit)?, (Result<String>) -> Unit>>()
    private val ids = AtomicInteger()

    private inner class Bridge {
        @JavascriptInterface
        fun ready() = main.post {
            ready = true
            waiting.forEach { it() }
            waiting.clear()
        }

        @JavascriptInterface
        fun reply(id: String, json: String) {
            val callback = pending.remove(id) ?: return
            main.post { callback.second(Result.success(json)) }
        }

        @JavascriptInterface
        fun fail(id: String, code: String, message: String) {
            val callback = pending.remove(id) ?: return
            main.post { callback.second(Result.failure(CloudException(code, message))) }
        }

        @JavascriptInterface
        fun progress(id: String, fraction: Double) {
            val callback = pending[id]?.first ?: return
            main.post { callback(fraction.toFloat().coerceIn(0f, 1f)) }
        }

        @JavascriptInterface
        fun read(token: String, offset: Double, length: Int): String =
            Base64.encodeToString(CloudFiles.read(token, offset.toLong(), length), Base64.NO_WRAP)

        @JavascriptInterface
        fun write(token: String, base64: String): Boolean = CloudFiles.append(token, Base64.decode(base64, Base64.NO_WRAP))
    }

    private fun ensureWebView() {
        if (web != null) return
        web = WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // a GramJS guarda dados temporários no localStorage
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "AndroidCloud")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host != HOST) return null
                    val name = url.path?.trimStart('/')?.takeIf { it.matches(Regex("[a-z]+\\.(html|js)")) }
                        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    val type = if (name.endsWith(".js")) "text/javascript" else "text/html"
                    return WebResourceResponse(type, "utf-8", app.assets.open("cloud/$name"))
                }

                // Nenhuma outra página abre neste WebView: a ponte com o app fica só com o motor.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            loadUrl("https://$HOST/engine.html")
        }
    }

    override fun call(method: String, args: JSONObject, onProgress: ((Float) -> Unit)?, done: (Result<String>) -> Unit) {
        main.post {
            ensureWebView()
            val send = {
                val id = "c${ids.incrementAndGet()}"
                pending[id] = Pair(onProgress, done)
                web?.evaluateJavascript("alxCloud.call(${JSONObject.quote(id)},${JSONObject.quote(method)},${JSONObject.quote(args.toString())})", null)
                Unit
            }
            if (ready) send() else waiting += send
        }
    }

    companion object {
        const val HOST = "alexandria.cloud"
    }
}

/**
 * Nuvem pelo Telegram no app: login com o api_id/api_hash da própria pessoa, livros e posições nas
 * Mensagens salvas (a mesma lógica e o mesmo formato do site). Tudo é assíncrono e responde na
 * thread principal.
 */
object TelegramCloud {
    private const val PREFS = "telegram"

    /** Troca do motor nos testes. */
    @Volatile
    var engineFactory: (Context) -> CloudEngine = { WebViewCloudEngine(it) }
    private var engine: CloudEngine? = null

    data class Settings(val apiId: Int, val apiHash: String, val session: String, val name: String, val error: String?, val chat: CloudChat? = null)

    fun settings(context: Context): Settings? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val session = p.getString("session", null) ?: return null
        val chat = p.getString("chat", null)?.let { CloudChat.from(try { JSONObject(it) } catch (e: org.json.JSONException) { null }) }
        return Settings(p.getInt("api_id", 0), p.getString("api_hash", "") ?: "", session, p.getString("name", "") ?: "", p.getString("error", null), chat)
    }

    /** Onde fica a nuvem: o grupo privado ou, com null, as Mensagens salvas. */
    fun useChat(context: Context, chat: CloudChat?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (chat == null) remove("chat") else putString("chat", chat.toJson().toString())
        }.remove("error").apply()
        sponsoredCache = null
    }

    fun connected(context: Context) = settings(context) != null

    private fun engine(context: Context): CloudEngine = engine ?: engineFactory(context).also { engine = it }

    /** Esquece o motor (testes, ou depois de sair). */
    fun reset() {
        engine = null
    }

    private fun call(context: Context, method: String, args: JSONObject = JSONObject(), onProgress: ((Float) -> Unit)? = null, done: (Result<String>) -> Unit) {
        val s = settings(context)
        val engine = engine(context)
        val app = context.applicationContext
        val init = JSONObject().put("apiId", s?.apiId ?: 0).put("apiHash", s?.apiHash ?: "").put("session", s?.session ?: "")
            .put("chat", s?.chat?.toJson() ?: JSONObject.NULL)
        val needsSession = method !in setOf("sendCode", "signIn", "password", "finish")
        if (!needsSession) return engine.call(method, args, onProgress, done)
        engine.call("init", init, null) {
            engine.call(method, args, onProgress) { result ->
                result.exceptionOrNull()?.let { e ->
                    if (e is CloudException && e.code.matches(Regex("AUTH_KEY_UNREGISTERED|SESSION_REVOKED|USER_DEACTIVATED|AUTH_KEY_INVALID"))) markError(app)
                    if (e is CloudException && s?.chat != null && e.code.matches(Regex("CHANNEL_PRIVATE|CHANNEL_INVALID|PEER_ID_INVALID"))) {
                        markError(app, "O grupo da nuvem não está acessível para esta conta (saiu do grupo ou ele foi apagado). Escolha outro lugar em Ajustes.")
                    }
                }
                done(result)
            }
        }
    }

    private fun markError(context: Context, message: String = "A sessão do Telegram foi encerrada. Entre de novo.") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("error", message).apply()
    }

    // ---------- login ----------

    fun sendCode(context: Context, apiId: Int, apiHash: String, phone: String, done: (Result<Unit>) -> Unit) =
        call(context, "sendCode", JSONObject().put("apiId", apiId).put("apiHash", apiHash).put("phone", phone)) { done(it.map { }) }

    /** true = entrou; false = a conta pede a senha de duas etapas. */
    fun signIn(context: Context, code: String, done: (Result<Boolean>) -> Unit) =
        call(context, "signIn", JSONObject().put("code", code)) { r -> done(r.map { JSONObject("{\"v\":$it}").optString("v") == "ok" }) }

    fun password(context: Context, password: String, done: (Result<Unit>) -> Unit) =
        call(context, "password", JSONObject().put("password", password)) { done(it.map { }) }

    /** Conclui o login: guarda a sessão (vale como login neste aparelho) e devolve o nome da conta. */
    fun finish(context: Context, apiId: Int, apiHash: String, done: (Result<String>) -> Unit) {
        val app = context.applicationContext
        call(context, "finish") { r ->
            done(r.map { json ->
                val o = JSONObject(json)
                val name = o.optString("name")
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("api_id", apiId).putString("api_hash", apiHash).putString("session", o.getString("session"))
                    .putString("name", name).remove("error").apply()
                name
            })
        }
    }

    /** Encerra a sessão no Telegram (some de "Dispositivos") e esquece aqui, mesmo sem internet. */
    fun logout(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        call(context, "logout") {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            done()
        }
    }

    // ---------- livros ----------

    fun books(context: Context, done: (Result<List<CloudBook>>) -> Unit) =
        call(context, "books") { r -> done(r.map { json -> JSONArray(json).let { a -> (0 until a.length()).map { CloudBook.from(a.getJSONObject(it)) } } }) }

    /** Envia o arquivo do livro (se ainda não estiver na nuvem). true = já estava lá. */
    fun upload(context: Context, name: String, format: String, file: File, onProgress: (Float) -> Unit, done: (Result<Boolean>) -> Unit) {
        val token = CloudFiles.forReading(file)
        val args = JSONObject().put("name", name).put("format", format).put("size", file.length()).put("token", token)
        call(context, "upload", args, onProgress) { r ->
            CloudFiles.release(token)
            done(r.map { JSONObject(it).optBoolean("existed") })
        }
    }

    /** Baixa o livro para [target] (que é apagado antes). */
    fun download(context: Context, book: CloudBook, target: File, onProgress: (Float) -> Unit, done: (Result<File>) -> Unit) {
        target.parentFile?.mkdirs()
        target.delete()
        val token = CloudFiles.forWriting(target)
        call(context, "download", JSONObject().put("book", book.toJson()).put("token", token), onProgress) { r ->
            CloudFiles.release(token)
            if (r.isFailure) target.delete()
            done(r.map { target })
        }
    }

    fun remove(context: Context, key: String, done: (Result<Unit>) -> Unit) =
        call(context, "remove", JSONObject().put("key", key)) { done(it.map { }) }

    // ---------- posição ----------

    fun pullPosition(context: Context, key: String, done: (SyncEntry?) -> Unit) {
        val app = context.applicationContext
        call(context, "pullPosition", JSONObject().put("key", key)) { r ->
            remember(app, "pull", r.exceptionOrNull())
            done(r.getOrNull()?.takeIf { it != "null" }?.let { json ->
                SyncData.parse("{\"books\":{\"k\":$json}}")["k"]
            })
        }
    }

    fun pushPosition(context: Context, key: String, entry: SyncEntry) {
        val app = context.applicationContext
        val o = JSONObject().put("format", entry.format).put("position", entry.position).put("exact", entry.exact)
            .put("pageCount", entry.pageCount).put("updated", entry.updated).put("device", entry.device)
        call(context, "pushPosition", JSONObject().put("key", key).put("entry", o)) { r -> remember(app, "push", r.exceptionOrNull()) }
    }

    /** Última gravação/leitura da posição, para Ajustes mostrar se a nuvem está respondendo. */
    data class PositionStatus(val pushAt: Long, val pushError: String?, val pullAt: Long, val pullError: String?)

    fun positionStatus(context: Context): PositionStatus {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PositionStatus(p.getLong("push_at", 0), p.getString("push_error", null), p.getLong("pull_at", 0), p.getString("pull_error", null))
    }

    private fun remember(context: Context, what: String, error: Throwable?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("session", null) == null) return
        p.edit().putLong("${what}_at", System.currentTimeMillis()).apply {
            if (error == null) remove("${what}_error") else putString("${what}_error", error.message ?: "erro desconhecido")
        }.apply()
    }

    // ---------- grupo privado ----------

    private fun chatResult(r: Result<String>): Result<CloudChat> = r.mapCatching { CloudChat.from(JSONObject(it)) ?: throw IllegalStateException("O Telegram não devolveu o grupo.") }

    /** Cria o grupo privado da nuvem e passa a usá-lo. */
    fun createGroup(context: Context, done: (Result<CloudChat>) -> Unit) {
        val app = context.applicationContext
        call(context, "createGroup") { r -> done(chatResult(r).onSuccess { useChat(app, it) }) }
    }

    /** Entra no grupo pelo link de convite (ou o encontra, se a conta já está nele) e passa a usá-lo. */
    fun joinGroup(context: Context, link: String, done: (Result<CloudChat>) -> Unit) {
        val app = context.applicationContext
        call(context, "joinGroup", JSONObject().put("link", link)) { r -> done(chatResult(r).onSuccess { useChat(app, it) }) }
    }

    /** Grupos desta conta, para escolher um que já existe. */
    fun groups(context: Context, done: (Result<List<CloudChat>>) -> Unit) =
        call(context, "groups") { r -> done(r.map { json -> JSONArray(json).let { a -> (0 until a.length()).mapNotNull { CloudChat.from(a.getJSONObject(it)) } } }) }

    fun inviteLink(context: Context, done: (Result<String>) -> Unit) =
        call(context, "inviteLink") { r -> done(r.map { JSONObject("{\"v\":$it}").getString("v") }) }

    /** Copia para o grupo os livros e posições das Mensagens salvas. Devolve quantos livros foram. */
    fun copyFromSaved(context: Context, done: (Result<Int>) -> Unit) =
        call(context, "copyFromSaved") { r -> done(r.map { JSONObject(it).optInt("books") }) }

    // ---------- mensagens patrocinadas ----------

    /** A documentação pede para guardar o resultado por 5 minutos. */
    private var sponsoredCache: Triple<Long, String, List<SponsoredItem>>? = null

    /** Anúncios do grupo da nuvem (nas Mensagens salvas, nunca há). Erros viram lista vazia. */
    fun sponsored(context: Context, done: (List<SponsoredItem>) -> Unit) {
        val chat = settings(context)?.chat ?: return done(emptyList())
        sponsoredCache?.let { (at, id, items) -> if (id == chat.id && System.currentTimeMillis() - at < 5 * 60_000) return done(items) }
        call(context, "sponsored") { r ->
            val items = r.mapCatching { json -> JSONArray(json).let { a -> (0 until a.length()).map { SponsoredItem.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
            if (r.isSuccess) sponsoredCache = Triple(System.currentTimeMillis(), chat.id, items)
            done(items)
        }
    }

    fun sponsoredSeen(context: Context, item: SponsoredItem) = call(context, "viewSponsored", JSONObject().put("id", item.id)) {}
    fun sponsoredClicked(context: Context, item: SponsoredItem) = call(context, "clickSponsored", JSONObject().put("id", item.id)) {}
}
