package com.alexandria.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Nuvem pelo Telegram no app, com um motor falso no lugar do WebView: confere o login, o envio e o
 * download em pedaços (pelos códigos de CloudFiles, como a ponte faz) e a posição indo pelo Telegram.
 */
@RunWith(RobolectricTestRunner::class)
class TelegramCloudTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** "Telegram" em memória: as Mensagens salvas viram um mapa. */
    class FakeEngine : CloudEngine {
        val calls = mutableListOf<String>()
        val files = LinkedHashMap<String, Pair<CloudBook, ByteArray>>()
        val positions = LinkedHashMap<String, JSONObject>()
        var session = ""
        var needsPassword = false
        var nextId = 1

        override fun call(method: String, args: JSONObject, onProgress: ((Float) -> Unit)?, done: (Result<String>) -> Unit) {
            calls += method
            val result: Result<String> = runCatching {
                when (method) {
                    "init" -> {
                        session = args.optString("session")
                        "true"
                    }
                    "sendCode" -> {
                        require(args.getInt("apiId") == 12345) { "API_ID_INVALID" }
                        "true"
                    }
                    "signIn" -> {
                        if (args.getString("code") != "11111") throw CloudException("PHONE_CODE_INVALID", "O código não confere.")
                        if (needsPassword) "\"password\"" else "\"ok\""
                    }
                    "password" -> {
                        if (args.getString("password") != "segredo") throw CloudException("PASSWORD_HASH_INVALID", "Senha errada.")
                        "true"
                    }
                    "finish" -> JSONObject().put("session", "SESSAO").put("name", "Leitora").toString()
                    "logout" -> "true"
                    else -> {
                        if (session != "SESSAO") throw CloudException("AUTH_KEY_UNREGISTERED", "Sem sessão.")
                        when (method) {
                            "books" -> JSONArray(files.values.map { it.first.toJson() }).toString()
                            "upload" -> {
                                val name = args.getString("name")
                                val size = args.getLong("size")
                                val key = SyncData.key(name, size)
                                val existed = key in files
                                if (!existed) {
                                    // Lê em pedaços, como o motor de verdade.
                                    val out = java.io.ByteArrayOutputStream()
                                    var offset = 0L
                                    while (offset < size) {
                                        val chunk = CloudFiles.read(args.getString("token"), offset, minOf(4, (size - offset).toInt()))
                                        out.write(chunk)
                                        offset += chunk.size
                                        onProgress?.invoke(offset.toFloat() / size)
                                        if (chunk.isEmpty()) break
                                    }
                                    files[key] = CloudBook(key, name, size, args.getString("format"), nextId++, 1L) to out.toByteArray()
                                }
                                JSONObject().put("existed", existed).toString()
                            }
                            "download" -> {
                                val book = CloudBook.from(args.getJSONObject("book"))
                                val bytes = files[book.key]?.second ?: throw CloudException("", "Livro não está mais na nuvem.")
                                bytes.toList().chunked(3).forEach { CloudFiles.append(args.getString("token"), it.toByteArray()) }
                                JSONObject().put("size", bytes.size).toString()
                            }
                            "remove" -> {
                                files.remove(args.getString("key"))
                                "true"
                            }
                            "pullPosition" -> positions[args.getString("key")]?.toString() ?: "null"
                            "pushPosition" -> {
                                positions[args.getString("key")] = args.getJSONObject("entry")
                                "true"
                            }
                            else -> throw CloudException("", "Método desconhecido: $method")
                        }
                    }
                }
            }
            done(result)
        }
    }

    private lateinit var engine: FakeEngine

    @Before
    fun setUp() {
        engine = FakeEngine()
        TelegramCloud.reset()
        TelegramCloud.engineFactory = { engine }
        context.getSharedPreferences("telegram", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("cloud", Context.MODE_PRIVATE).edit().clear().commit()
        PositionSync.disconnect(context)
    }

    @After
    fun tearDown() {
        TelegramCloud.reset()
        TelegramCloud.engineFactory = { WebViewCloudEngine(it) }
    }

    private fun login(password: Boolean = false) {
        engine.needsPassword = password
        var sent: Result<Unit>? = null
        TelegramCloud.sendCode(context, 12345, "0123456789abcdef0123456789abcdef", "+5511912345678") { sent = it }
        assertTrue(sent!!.isSuccess)
        var signed: Result<Boolean>? = null
        TelegramCloud.signIn(context, "11111") { signed = it }
        assertEquals(!password, signed!!.getOrThrow())
        if (password) {
            var pw: Result<Unit>? = null
            TelegramCloud.password(context, "segredo") { pw = it }
            assertTrue(pw!!.isSuccess)
        }
        var name: String? = null
        TelegramCloud.finish(context, 12345, "0123456789abcdef0123456789abcdef") { name = it.getOrThrow() }
        assertEquals("Leitora", name)
    }

    @Test
    fun loginSavesTheSessionOnlyAtTheEnd() {
        assertFalse(TelegramCloud.connected(context))
        var bad: Result<Boolean>? = null
        TelegramCloud.signIn(context, "99999") { bad = it }
        assertEquals("O código não confere.", bad!!.exceptionOrNull()!!.message)
        assertFalse(TelegramCloud.connected(context))

        login(password = true)
        val s = TelegramCloud.settings(context)!!
        assertEquals(12345, s.apiId)
        assertEquals("SESSAO", s.session)
        assertEquals("Leitora", s.name)
        // Login não manda "init" (ainda não há sessão).
        assertFalse(engine.calls.take(4).contains("init"))
    }

    @Test
    fun uploadAndDownloadGoInChunksThroughTheBridgeTokens() {
        login()
        val bytes = ByteArray(23) { (it * 7).toByte() }
        val file = File(context.cacheDir, "Dom Casmurro.epub").apply { writeBytes(bytes) }
        val progress = mutableListOf<Float>()
        var existed: Boolean? = null
        TelegramCloud.upload(context, "Dom Casmurro.epub", "epub", file, { progress += it }) { existed = it.getOrThrow() }
        assertEquals(false, existed)
        assertEquals(1f, progress.last())
        assertEquals("init", engine.calls[engine.calls.indexOf("upload") - 1])

        // Enviar de novo não duplica.
        TelegramCloud.upload(context, "Dom Casmurro.epub", "epub", file, {}) { existed = it.getOrThrow() }
        assertEquals(true, existed)
        assertEquals(1, engine.files.size)

        var books: List<CloudBook>? = null
        TelegramCloud.books(context) { books = it.getOrThrow() }
        assertEquals(listOf("Dom Casmurro.epub"), books!!.map { it.name })
        assertEquals(SyncData.key("Dom Casmurro.epub", 23), books!![0].key)

        val target = File(File(context.cacheDir, "cloud"), "Dom Casmurro.epub").apply {
            parentFile!!.mkdirs()
            writeText("lixo antigo")
        }
        var downloaded: File? = null
        TelegramCloud.download(context, books!![0], target, {}) { downloaded = it.getOrThrow() }
        assertArrayEquals(bytes, downloaded!!.readBytes())

        // O código do arquivo vale uma vez só.
        engine.files.clear()
        var failed: Result<File>? = null
        TelegramCloud.download(context, books!![0], target, {}) { failed = it }
        assertTrue(failed!!.isFailure)
        assertFalse(target.exists())
    }

    @Test
    fun downloadedBookEntersTheLibrary() {
        login()
        val bytes = "Era uma vez".toByteArray()
        val source = File(context.cacheDir, "Conto.txt").apply { writeBytes(bytes) }
        TelegramCloud.upload(context, "Conto.txt", "txt", source, {}) {}
        source.delete()
        val book = engine.files.values.first().first
        val target = File(File(context.cacheDir, "cloud"), book.name)
        TelegramCloud.download(context, book, target, {}) {}
        val db = LibraryDatabase(context)
        val received = IncomingBooks.receive(context, db, android.net.Uri.fromFile(target), null)
        assertFalse(received.reused)
        assertEquals("Conto.txt", received.item.name)
        assertEquals(bytes.size.toLong(), received.item.fileSize)
        // Mesmo código dos dois lados: o livro baixado é reconhecido como "já está aqui".
        assertEquals(book.key, SyncData.key(received.item.name, received.item.fileSize))
        db.close()
    }

    @Test
    fun positionGoesThroughTelegramWhenItIsTheProvider() {
        assertNull(PositionSync.provider(context))
        login()
        assertEquals("telegram", PositionSync.provider(context))
        val entry = SyncEntry("epub", 4200, 420_031, 365, 1_700_000_000_000, "celular")
        PositionSync.push(context, "Moby Dick.epub", 1234567, entry)
        assertNotNull(engine.positions[SyncData.key("Moby Dick.epub", 1234567)])

        var pulled: SyncEntry? = null
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        PositionSync.pull(activity, "Moby Dick.epub", 1234567) { pulled = it }
        assertEquals(entry, pulled)

        var none: SyncEntry? = entry
        PositionSync.pull(activity, "Outro.epub", 1) { none = it }
        assertNull(none)

        // Com o GitHub também ligado, vale a escolha feita em Ajustes.
        context.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().putString("token", "t").putString("gist", "g").commit()
        assertEquals("telegram", PositionSync.provider(context))
        PositionSync.setProvider(context, "github")
        assertEquals("github", PositionSync.provider(context))
    }

    @Test
    fun endedSessionIsReportedAndLogoutForgetsEverything() {
        login()
        engine.session = ""
        context.getSharedPreferences("telegram", Context.MODE_PRIVATE).edit().putString("session", "VENCIDA").commit()
        var result: Result<List<CloudBook>>? = null
        TelegramCloud.books(context) { result = it }
        assertTrue(result!!.isFailure)
        assertNotNull(TelegramCloud.settings(context)!!.error)

        var out = false
        TelegramCloud.logout(context) { out = true }
        assertTrue(out)
        assertFalse(TelegramCloud.connected(context))
    }
}
