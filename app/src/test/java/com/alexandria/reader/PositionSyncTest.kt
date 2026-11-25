package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Sincronização da posição de leitura pelo gist do GitHub (com um GitHub falso, em memória). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PositionSyncTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** GitHub de mentira: um usuário, gists em memória, token conferido. */
    class FakeGitHub(private val validToken: String = "github_pat_ok") {
        val gists = LinkedHashMap<String, JSONObject>()
        val calls = mutableListOf<String>()

        fun transport(method: String, url: String, body: String?, token: String): Pair<Int, String> {
            val path = url.removePrefix("https://api.github.com")
            calls += "$method ${path.substringBefore('?')}"
            if (token != validToken) return Pair(401, """{"message":"Bad credentials"}""")
            return when {
                method == "GET" && path == "/user" -> Pair(200, """{"login":"leitora"}""")
                method == "GET" && path.startsWith("/gists?") -> Pair(200, JSONArray(gists.values.map { g ->
                    JSONObject().put("id", g.getString("id")).put("files", JSONObject().apply {
                        for (name in g.getJSONObject("files").keys()) put(name, JSONObject().put("filename", name))
                    })
                }).toString())
                method == "POST" && path == "/gists" -> {
                    val id = "g${gists.size + 1}"
                    val gist = JSONObject(body!!).put("id", id)
                    gists[id] = gist
                    Pair(201, gist.toString())
                }
                method == "GET" && path.startsWith("/gists/") -> gists[path.removePrefix("/gists/")]?.let { Pair(200, it.toString()) } ?: Pair(404, "{}")
                method == "PATCH" && path.startsWith("/gists/") -> {
                    val gist = gists[path.removePrefix("/gists/")] ?: return Pair(404, "{}")
                    val files = JSONObject(body!!).getJSONObject("files")
                    for (name in files.keys()) gist.getJSONObject("files").put(name, files.getJSONObject(name))
                    Pair(200, gist.toString())
                }
                else -> Pair(404, "{}")
            }
        }

        fun content(): Map<String, SyncEntry> =
            SyncData.parse(gists.values.first().getJSONObject("files").getJSONObject(SyncData.FILE).getString("content"))
    }

    private lateinit var github: FakeGitHub

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        PositionSync.disconnect(context)
        github = FakeGitHub()
        PositionSync.clientFactory = { token -> GistClient(token) { m, u, b, t -> github.transport(m, u, b, t) } }
    }

    @After
    fun tearDown() {
        PositionSync.disconnect(context)
        PositionSync.clientFactory = { GistClient(it) }
        context.deleteDatabase("alexandria.db")
    }

    @Test
    fun keyIsTheSameForTheSameFileAndHidesTheName() {
        val key = SyncData.key("Moby Dick.epub", 1_234_567)
        assertEquals(key, SyncData.key("  moby  dick.EPUB ", 1_234_567))
        assertNotEquals(key, SyncData.key("Moby Dick.epub", 1_234_568))
        assertEquals(24, key.length)
        assertFalse(key.contains("moby"))
        // Os mesmos códigos que o site calcula (o livro precisa ser reconhecido nos dois).
        assertEquals("aa55f5ee8e81a304cbcd6932", key)
        assertEquals("49b69d275333095ce62c4373", SyncData.key("Memórias Póstumas.epub", 98_765))
    }

    @Test
    fun mergeKeepsTheNewestPerBookAndRoundTrips() {
        val books = mutableMapOf("a" to SyncEntry("epub", 10, 1_000, 365, 100, "celular"))
        assertFalse(SyncData.merge(books, "a", SyncEntry("epub", 5, 500, 365, 50, "computador")))
        assertTrue(SyncData.merge(books, "a", SyncEntry("epub", 20, 2_000, 365, 200, "computador")))
        assertTrue(SyncData.merge(books, "b", SyncEntry("pdf", 3, 3, 120, 10, "celular")))
        assertEquals(books, SyncData.parse(SyncData.serialize(books)))
        assertTrue(SyncData.parse("não é json").isEmpty())
    }

    @Test
    fun remoteIsAppliedOnlyWhenNewerAndElsewhere() {
        val remote = SyncEntry("epub", 438, 43_836, 365, 2_000, "computador")
        assertTrue(SyncData.shouldApply(remote, localUpdated = 1_000, localExact = 10_000, span = 300, device = "celular"))
        assertFalse(SyncData.shouldApply(remote, localUpdated = 3_000, localExact = 10_000, span = 300, device = "celular"))
        // Mesma tela: nada a fazer.
        assertFalse(SyncData.shouldApply(remote, localUpdated = 1_000, localExact = 43_900, span = 300, device = "celular"))
        assertFalse(SyncData.shouldApply(null, 0, 0, 1, "celular"))
    }

    @Test
    fun clientFindsOrCreatesTheGistAndMergesPushes() {
        val client = GistClient("github_pat_ok") { m, u, b, t -> github.transport(m, u, b, t) }
        assertEquals("leitora", client.login())
        val id = client.findOrCreate()
        assertEquals(id, client.findOrCreate()) // não cria outro
        assertEquals(1, github.gists.size)
        assertEquals(false, github.gists[id]!!.getBoolean("public"))
        client.push(id, "a", SyncEntry("epub", 10, 1_000, 365, 100, "celular"))
        client.push(id, "a", SyncEntry("epub", 5, 500, 365, 50, "computador")) // mais antiga: não grava
        client.push(id, "b", SyncEntry("pdf", 3, 3, 120, 10, "computador"))
        assertEquals(setOf("a", "b"), github.content().keys)
        assertEquals(10, github.content()["a"]!!.position)
        try {
            GistClient("errado") { m, u, b, t -> github.transport(m, u, b, t) }.login()
            throw AssertionError("deveria falhar")
        } catch (e: SyncException) {
            assertEquals(401, e.code)
        }
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            idle()
        }
    }

    private fun all(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { all(root.getChildAt(it)) } else emptyList()

    @Test
    fun openingContinuesFromTheOtherDeviceAndLeavingSavesHere() {
        // Conecta (como em Ajustes).
        var connected: Result<String>? = null
        val settings = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        PositionSync.connect(settings, "github_pat_ok") { connected = it }
        waitUntil { connected != null }
        assertEquals("leitora", connected!!.getOrThrow())
        assertNotNull(PositionSync.settings(context))

        val file = File(context.cacheDir, "sincronia.txt").apply {
            writeText((1..800).joinToString("\n") { "Linha $it do livro, com algum texto para ocupar a tela." })
        }
        val id = LibraryDatabase(context).use { it.add(file.name, Uri.fromFile(file).toString(), "txt", file.length())!! }
        // O computador leu até a metade depois da última leitura aqui.
        GistClient("github_pat_ok") { m, u, b, t -> github.transport(m, u, b, t) }.let { client ->
            client.push(PositionSync.settings(context)!!.gistId, SyncData.key(file.name, file.length()),
                SyncEntry("txt", 5_000, 500_000, 0, System.currentTimeMillis(), "computador"))
        }

        val controller = Robolectric.buildActivity(ReaderActivity::class.java, Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)).setup().visible()
        val root = controller.get().window.decorView
        waitUntil { all(root).filterIsInstance<TextView>().any { it.text.startsWith("Continuando de onde você parou no computador") && it.isShown } }
        assertTrue(all(root).filterIsInstance<TextView>().any { it.text.startsWith("Continuando de onde você parou no computador") && it.isShown })
        idle(800)
        val list = all(root).filterIsInstance<android.widget.ListView>().single()
        assertTrue("foi para a metade do livro", list.firstVisiblePosition > 0)
        assertTrue("oferece voltar", all(root).filterIsInstance<TextView>().any { it.text == "↩ Voltar" && it.isShown })

        // Ao sair, a posição daqui vai para o gist, como "celular".
        controller.pause()
        PositionSync.drain()
        val saved = github.content()[SyncData.key(file.name, file.length())]!!
        assertTrue(saved.device.startsWith("celular"))
        assertEquals(5_000.0, saved.position.toDouble(), 150.0)
        controller.stop().destroy()
    }

    @Test
    fun withoutTokenNothingIsSent() {
        assertNull(PositionSync.settings(context))
        val file = File(context.cacheDir, "sem-sync.txt").apply { writeText("Texto.\n".repeat(50)) }
        val id = LibraryDatabase(context).use { it.add(file.name, Uri.fromFile(file).toString(), "txt", file.length())!! }
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)).setup().visible()
        idle(500)
        controller.pause().stop().destroy()
        PositionSync.drain()
        assertTrue(github.calls.isEmpty())
    }
}
