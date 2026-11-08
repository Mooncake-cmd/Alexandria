package com.alexandria.reader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * Integração com os motores do OpenReadEra de verdade (compilados para Linux pelo
 * buildHostEngines), pelo mesmo protocolo de pipes usado no app.
 */
@RunWith(RobolectricTestRunner::class)
class OreEnginesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val engines = mutableListOf<OreEngine>()

    @Before
    fun requireEngines() {
        val dir = File(System.getProperty("ore.hostEngines") ?: "")
        val present = listOf("eramobi", "eradjvu", "eracomic").all { File(dir, it).canExecute() }
        if (System.getProperty("ore.requireHostEngines") != null) {
            assertTrue("Motores para Linux não foram compilados em $dir", present)
        }
        assumeTrue("Motores para Linux indisponíveis neste sistema", present)
    }

    @After
    fun closeEngines() = engines.forEach { it.close() }

    private fun start(name: String): OreEngine {
        val binary = File(System.getProperty("ore.hostEngines"), name)
        val engine = OreEngine.start(binary, folder.newFolder()) { fifo ->
            val result = ProcessBuilder("mkfifo", "-m", "600", fifo.path).start().waitFor()
            check(result == 0) { "mkfifo falhou" }
        }
        engines += engine
        return engine
    }

    private fun sample(name: String): File {
        val target = File(folder.root, name)
        javaClass.getResourceAsStream("/ore/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    @Test
    fun enginesAnswerVersion() {
        for (name in listOf("eramobi", "eradjvu", "eracomic")) {
            val response = start(name).call(OreEngine.CMD_REQ_VERSION)
            assertTrue("$name: código ${response.result}", response.ok)
            assertTrue(response.values.first().string.isNotBlank())
        }
    }

    @Test
    fun mobiBecomesAReadableEpub() {
        val engine = start("eramobi")
        for (name in listOf("windows-1252.mobi", "huffdic.mobi", "obfuscated_fonts.mobi")) {
            val epub = File(folder.root, "$name.epub")
            OreFormats.mobiToEpub(engine, sample(name), epub)
            ZipFile(epub).use { zip ->
                val book = EpubPackage.read(zip)
                assertTrue("$name sem capítulos", book.chapters.isNotEmpty())
                val text = zip.getInputStream(zip.getEntry(book.chapters.first())).use { String(it.readBytes()) }
                assertTrue("$name: capítulo vazio", text.length > 100)
            }
        }
    }

    @Test
    fun brokenMobiFailsCleanly() {
        val engine = start("eramobi")
        val bogus = File(folder.root, "falso.mobi").apply { writeText("isto não é um mobi") }
        val failed = try {
            OreFormats.mobiToEpub(engine, bogus, File(folder.root, "falso.epub"))
            false
        } catch (e: OreException) {
            true
        }
        assertTrue(failed)
        // O motor continua respondendo depois do erro.
        assertTrue(engine.call(OreEngine.CMD_REQ_VERSION).ok)
    }

    @Test
    fun djvuPagesRenderWithTheRightColors() {
        val source = DjvuSource(start("eradjvu"), sample("amostra.djvu"))
        assertEquals(2, source.pageCount)
        val (w, h) = source.pageSize(0)
        assertEquals(400f, w, 0.5f)
        assertEquals(600f, h, 0.5f)
        assertEquals(500f / 300f, source.pageSize(1).let { it.first / it.second }, 0.01f)

        val pixels = source.renderPixels(0, 200, 300)
        fun pixel(x: Int, y: Int): Triple<Int, Int, Int> {
            val i = (y * 200 + x) * 4
            return Triple(pixels[i].toInt() and 0xFF, pixels[i + 1].toInt() and 0xFF, pixels[i + 2].toInt() and 0xFF)
        }
        val left = pixel(50, 150)
        val right = pixel(150, 150)
        assertTrue("esquerda deveria ser vermelha: $left", left.first > 150 && left.third < 100)
        assertTrue("direita deveria ser azul: $right", right.third > 150 && right.first < 100)
        // Página bitonal: centro preto, canto branco.
        val bw = source.renderPixels(1, 250, 150)
        assertTrue((bw[(75 * 250 + 125) * 4].toInt() and 0xFF) < 60)
        assertTrue((bw[(5 * 250 + 5) * 4].toInt() and 0xFF) > 200)
    }

    @Test
    fun rarComicListsAndExtractsPagesInNaturalOrder() {
        val source = RarComicSource(start("eracomic"), sample("quadrinho-rar5.cbr"), folder.newFolder("paginas"))
        assertEquals(3, source.pageCount)
        // Ordem natural (pag1, pag2, pag010), sem o .txt, e cada página idêntica ao original.
        for ((i, name) in listOf("pag1.png", "pag2.png", "pag010.png").withIndex()) {
            val original = javaClass.getResourceAsStream("/ore/$name")!!.use { it.readBytes() }
            assertTrue("página ${i + 1} diferente de $name", original.contentEquals(source.extract(i).readBytes()))
        }
    }
}
