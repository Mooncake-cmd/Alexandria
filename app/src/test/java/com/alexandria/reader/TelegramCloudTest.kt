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
        var chat: JSONObject? = null
        val ads = JSONArray()
        val videoList = JSONArray()
        var needsPassword = false
        var nextId = 1

        override fun call(method: String, args: JSONObject, onProgress: ((Float) -> Unit)?, done: (Result<String>) -> Unit) {
            calls += method
            val result: Result<String> = runCatching {
                when (method) {
                    "init" -> {
                        session = args.optString("session")
                        chat = args.optJSONObject("chat")
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
                            "createGroup" -> JSONObject().put("id", "777").put("accessHash", "-42").put("title", "Alexandria — biblioteca").toString()
                            "joinGroup" -> {
                                if (!args.getString("link").contains("t.me/+")) throw CloudException("INVITE_HASH_INVALID", "O link de convite não vale mais.")
                                JSONObject().put("id", "888").put("accessHash", "9").put("title", "Livros da família").toString()
                            }
                            "groups" -> JSONArray().put(JSONObject().put("id", "999").put("accessHash", "1").put("title", "Leituras")).toString()
                            "inviteLink" -> if (chat == null) throw CloudException("", "Sem grupo.") else JSONObject.quote("https://t.me/+abcDEF123")
                            "copyFromSaved" -> JSONObject().put("books", 2).put("positions", true).toString()
                            "sponsored" -> ads.toString()
                            "videos" -> videoList.toString()
                            "positions" -> JSONObject().apply { positions.forEach { (k, v) -> put(k, v) } }.toString()
                            "viewSponsored", "clickSponsored" -> "true"
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
        context.getSharedPreferences("tabs", Context.MODE_PRIVATE).edit().clear().commit()
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
        TelegramCloud.upload(context, null, "Dom Casmurro.epub", "epub", file, { progress += it }) { existed = it.getOrThrow() }
        assertEquals(false, existed)
        assertEquals(1f, progress.last())
        assertEquals("init", engine.calls[engine.calls.indexOf("upload") - 1])

        // Enviar de novo não duplica.
        TelegramCloud.upload(context, null, "Dom Casmurro.epub", "epub", file, {}) { existed = it.getOrThrow() }
        assertEquals(true, existed)
        assertEquals(1, engine.files.size)

        var books: List<CloudBook>? = null
        TelegramCloud.books(context, null) { books = it.getOrThrow() }
        assertEquals(listOf("Dom Casmurro.epub"), books!!.map { it.name })
        assertEquals(SyncData.key("Dom Casmurro.epub", 23), books!![0].key)

        val target = File(File(context.cacheDir, "cloud"), "Dom Casmurro.epub").apply {
            parentFile!!.mkdirs()
            writeText("lixo antigo")
        }
        var downloaded: File? = null
        TelegramCloud.download(context, null, books!![0], target, {}) { downloaded = it.getOrThrow() }
        assertArrayEquals(bytes, downloaded!!.readBytes())

        // O código do arquivo vale uma vez só.
        engine.files.clear()
        var failed: Result<File>? = null
        TelegramCloud.download(context, null, books!![0], target, {}) { failed = it }
        assertTrue(failed!!.isFailure)
        assertFalse(target.exists())
    }

    @Test
    fun downloadedBookEntersTheLibrary() {
        login()
        val bytes = "Era uma vez".toByteArray()
        val source = File(context.cacheDir, "Conto.txt").apply { writeBytes(bytes) }
        TelegramCloud.upload(context, null, "Conto.txt", "txt", source, {}) {}
        source.delete()
        val book = engine.files.values.first().first
        val target = File(File(context.cacheDir, "cloud"), book.name)
        TelegramCloud.download(context, null, book, target, {}) {}
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
        TelegramCloud.books(context, null) { result = it }
        assertTrue(result!!.isFailure)
        assertNotNull(TelegramCloud.settings(context)!!.error)

        var out = false
        TelegramCloud.logout(context) { out = true }
        assertTrue(out)
        assertFalse(TelegramCloud.connected(context))
    }

    @Test
    fun eachCallGoesToTheChosenGroup() {
        login()
        var created: CloudChat? = null
        TelegramCloud.createGroup(context, "Alexandria — Filmes") { created = it.getOrThrow() }
        assertEquals(CloudChat("777", "-42", "Alexandria — biblioteca"), created)

        // A chamada leva o grupo no "init"; sem grupo, vai para as Mensagens salvas.
        TelegramCloud.books(context, created) {}
        assertEquals("777", engine.chat!!.getString("id"))
        assertEquals("-42", engine.chat!!.getString("accessHash"))
        TelegramCloud.books(context, null) {}
        assertNull(engine.chat)

        var link: String? = null
        TelegramCloud.inviteLink(context, created!!) { link = it.getOrThrow() }
        assertEquals("https://t.me/+abcDEF123", link)
        var copied: Int? = null
        TelegramCloud.copyFromSaved(context, created!!) { copied = it.getOrThrow() }
        assertEquals(2, copied)

        var bad: Result<CloudChat>? = null
        TelegramCloud.joinGroup(context, "oi") { bad = it }
        assertEquals("O link de convite não vale mais.", bad!!.exceptionOrNull()!!.message)
        var joined: CloudChat? = null
        TelegramCloud.joinGroup(context, "https://t.me/+xyz") { joined = it.getOrThrow() }
        assertEquals("Livros da família", joined!!.title)

        var groups: List<CloudChat>? = null
        TelegramCloud.groups(context) { groups = it.getOrThrow() }
        assertEquals(listOf("Leituras"), groups!!.map { it.title })
    }

    @Test
    fun readingPositionGoesToTheGroupOfTheBooksTab() {
        login()
        val group = CloudChat("888", "9", "HQs")
        AppTabs.save(context, AppTabs.all(context) + AppTab("hqs", "HQs", AppTabs.BOOKS, group))
        val entry = SyncEntry("cbz", 3, -1, 20, 1_700_000_000_000, "celular")
        PositionSync.push(context, "Watchmen.cbz", 99, entry, "hqs")
        assertEquals("888", engine.chat!!.getString("id"))
        PositionSync.push(context, "Dom Casmurro.txt", 50, entry)
        assertNull(engine.chat)
        // Aba sem grupo (fora a principal) não tem nuvem: nada é enviado.
        AppTabs.save(context, AppTabs.all(context) + AppTab("local", "Local", AppTabs.BOOKS, null))
        val before = engine.calls.size
        PositionSync.push(context, "Outro.txt", 1, entry, "local")
        assertEquals(before, engine.calls.size)
    }

    @Test
    fun handTaggedBooksAndSendersComeFromTheEngine() {
        val book = CloudBook.from(JSONObject().put("key", "k").put("name", "Iracema.epub").put("size", 5).put("format", "epub").put("messageId", 3).put("date", 1).put("sender", "Ana").put("manual", true))
        assertEquals("Ana", book.sender)
        assertTrue(book.manual)
        assertEquals(book, CloudBook.from(book.toJson()))
    }

    @Test
    fun sponsoredMessagesOnlyInGroupsAndCachedForFiveMinutes() {
        login()
        var ads: List<SponsoredItem>? = null
        TelegramCloud.sponsored(context, null) { ads = it }
        assertEquals(emptyList<SponsoredItem>(), ads)
        assertFalse(engine.calls.contains("sponsored"))

        val group = CloudChat("777", "1", "Filmes")
        engine.ads.put(JSONObject().put("id", "cmFuZG9t").put("title", "Canal de livros").put("message", "Leia mais").put("url", "https://t.me/livros")
            .put("buttonText", "Ver canal").put("recommended", false).put("sponsorInfo", "Anunciante X"))
        TelegramCloud.sponsored(context, group) { ads = it }
        assertEquals(listOf("Canal de livros"), ads!!.map { it.title })
        assertEquals("Anunciante X", ads!![0].info)
        TelegramCloud.sponsored(context, group) {}
        assertEquals(1, engine.calls.count { it == "sponsored" })

        assertTrue(SponsoredItem.isTelegramUrl("https://t.me/livros"))
        assertTrue(SponsoredItem.isTelegramUrl("https://fragment.com/x"))
        assertFalse(SponsoredItem.isTelegramUrl("https://exemplo.com/t.me"))
        assertFalse(SponsoredItem.isTelegramUrl("https://faket.me"))
    }

    @Test
    fun videosOfTheTabGroup() {
        login()
        engine.videoList.put(JSONObject().put("messageId", 5).put("name", "Aula 01.mp4").put("size", 120_000_000).put("duration", 754).put("mime", "video/mp4").put("date", 1).put("sender", "Bia").put("topic", 3))
        var list: List<CloudVideo>? = null
        TelegramCloud.videos(context, CloudChat("777", "1", "Cursos")) { list = it.getOrThrow() }
        assertEquals("777", engine.chat!!.getString("id"))
        assertEquals(CloudVideo(5, "Aula 01.mp4", 120_000_000, 754, "video/mp4", 1, "Bia", 3), list!!.single())
        assertEquals("12:34", CloudVideo.duration(754))
        assertEquals("1:00:05", CloudVideo.duration(3605))
        assertEquals("", CloudVideo.duration(0))
    }

    @Test
    fun positionStatusShowsWhetherTheCloudAnswered() {
        login()
        val entry = SyncEntry("epub", 21, 2100, 30, 1_700_000_000_000, "celular")
        TelegramCloud.pushPosition(context, null, "k", entry)
        val ok = TelegramCloud.positionStatus(context)
        assertTrue(ok.pushAt > 0)
        assertNull(ok.pushError)

        // Sessão recusada: o erro aparece em Ajustes.
        context.getSharedPreferences("telegram", Context.MODE_PRIVATE).edit().putString("session", "VENCIDA").commit()
        TelegramCloud.pushPosition(context, null, "k", entry)
        assertEquals("Sem sessão.", TelegramCloud.positionStatus(context).pushError)
        var pulled: SyncEntry? = entry
        TelegramCloud.pullPosition(context, null, "k") { pulled = it }
        assertNull(pulled)
        assertEquals("Sem sessão.", TelegramCloud.positionStatus(context).pullError)
    }
}
