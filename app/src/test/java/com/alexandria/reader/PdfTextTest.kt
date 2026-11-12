package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/** Modelo do texto da página (Kotlin puro). */
class PageTextModelTest {
    // Duas linhas: "Ola mundo" (y 0.1–0.2) e "Arrakis" (y 0.3–0.4); cada letra com 0.05 de largura.
    private val page = PageText(
        "Ola mundo".mapIndexed { i, c -> PdfChar(c.toString(), 0.1f + i * 0.05f, 0.1f, 0.15f + i * 0.05f, 0.2f) } +
            listOf(PdfChar("\n", 0.6f, 0.1f, 0.6f, 0.2f)) +
            "Arrakis".mapIndexed { i, c -> PdfChar(c.toString(), 0.1f + i * 0.05f, 0.3f, 0.15f + i * 0.05f, 0.4f) }
    )

    @Test
    fun findsCharsAndWords() {
        assertEquals("Ola mundo\nArrakis", page.string)
        val m = page.charAt(0.37f, 0.15f)!!
        assertEquals("u", page.chars[m].text)
        assertEquals("mundo", page.textOf(page.wordAt(m)!!.first, page.wordAt(m)!!.last))
        assertNull("longe do texto", page.charAt(0.9f, 0.9f))
        // Vão entre linhas: fica com a linha mais perto.
        assertEquals("A", page.chars[page.charAt(0.1f, 0.29f)!!].text)
    }

    @Test
    fun selectionBecomesOneRectPerLine() {
        val from = page.charAt(0.37f, 0.15f)!! // "u" de mundo
        val to = page.charAt(0.22f, 0.35f)!! // segundo "r" de Arrakis
        val rects = page.rects(to, from) // ordem invertida também funciona
        assertEquals(2, rects.size)
        assertEquals(0.35f, rects[0].left, 0.001f)
        assertEquals(0.55f, rects[0].right, 0.001f)
        assertEquals(0.1f, rects[1].left, 0.001f)
        assertEquals(0.25f, rects[1].right, 0.001f)
        assertEquals("undo Arr", page.textOf(from, to))
    }

    @Test
    fun offsetsMapBackToChars() {
        val start = page.string.indexOf("Arrakis")
        val rects = page.rectsForOffsets(start, start + 7)
        assertEquals(1, rects.size)
        assertEquals(0.1f, rects[0].left, 0.001f)
        assertEquals(0.45f, rects[0].right, 0.001f)
    }
}

@RunWith(RobolectricTestRunner::class)
class PdfTextTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        val dir = File(System.getProperty("ore.hostEngines") ?: "")
        val present = File(dir, "erapdf").canExecute()
        if (System.getProperty("ore.requireHostEngines") != null) assertTrue("erapdf para Linux ausente", present)
        assumeTrue(present)
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun sample(): File = File(context.cacheDir, "amostra.pdf").also { t ->
        javaClass.getResourceAsStream("/ore/amostra.pdf")!!.use { i -> t.outputStream().use { i.copyTo(it) } }
    }

    private fun source() = EraPdfSource(OreEngine.start(context, "erapdf"), sample())

    @Test
    fun engineGivesTextWithPositionsAndRendersPages() {
        val pdf = source()
        try {
            assertEquals(2, pdf.pageCount)
            assertEquals(842f / 595f, pdf.aspect(0), 0.001f)
            val text = pdf.text(0)
            assertTrue(text.string, text.string.contains("Paul chegou a Arrakis."))
            assertTrue(text.string.contains("Coração do deserto."))
            // "P" começa a 72 pt de 595 da borda esquerda.
            assertEquals(72f / 595f, text.chars.first { it.text == "P" }.left, 0.01f)
            assertTrue(pdf.text(1).string.contains("Arrakis de novo"))
            val page = pdf.render(0, 297)
            assertEquals(297, page.width)
            assertEquals(420, page.height)
        } finally {
            pdf.close()
        }
    }

    /** Pixels escuros na faixa (em fração da altura) de uma página desenhada. */
    private fun darkPixels(bitmap: android.graphics.Bitmap, top: Float, bottom: Float): Int {
        var dark = 0
        for (y in (bitmap.height * top).toInt() until (bitmap.height * bottom).toInt()) {
            for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c) < 384) dark++
            }
        }
        return dark
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun pdfWithoutEmbeddedFontsUsesBundledFonts() {
        // Times New Roman e Arial citadas mas não embutidas (como nos PDFs do Word): sem as fontes
        // do app o motor procurava as Droid em /system/fonts e a página saía em branco.
        val file = File(context.cacheDir, "sem-fontes.pdf").also { t ->
            javaClass.getResourceAsStream("/ore/sem-fontes.pdf")!!.use { i -> t.outputStream().use { i.copyTo(it) } }
        }
        val fonts = PdfFonts.files(context)
        assertEquals(PdfFonts.KEYS.keys, fonts.keys)
        assertTrue(fonts.values.all { it.length() > 100_000 })
        val pdf = EraPdfSource(OreEngine.start(context, "erapdf"), file, fonts)
        try {
            val page = pdf.render(0, 595)
            // Linha da Times (y = 700 pt de 842, de baixo para cima) e da Arial (y = 600 pt).
            assertTrue("Times não desenhada", darkPixels(page.bitmap, 0.13f, 0.18f) > 500)
            assertTrue("Arial não desenhada", darkPixels(page.bitmap, 0.25f, 0.30f) > 500)
            assertTrue(pdf.text(0).string.contains("Redação sem fonte embutida."))
        } finally {
            pdf.close()
        }
        // Segunda vez: reaproveita os arquivos já copiados.
        val again = PdfFonts.files(context)
        assertEquals(fonts.values.map { it.lastModified() }, again.values.map { it.lastModified() })
    }

    private fun all(root: View): List<View> {
        val result = mutableListOf(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) result += all(root.getChildAt(i))
        return result
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Centro (na janela) da palavra [word] da página 1, na página exibida. */
    private fun wordPoint(page: ZoomPageView, word: String): Pair<Float, Float> {
        val text = source().let { s -> try { s.text(0) } finally { s.close() } }
        val offset = text.string.indexOf(word)
        val r = text.rectsForOffsets(offset, offset + word.length).first()
        val rect = page.pageRect()
        val location = IntArray(2)
        page.getLocationInWindow(location)
        return Pair(location[0] + rect.left + (r.left + r.right) / 2 * rect.width(), location[1] + rect.top + (r.top + r.bottom) / 2 * rect.height())
    }

    private fun press(activity: ReaderActivity, x: Float, y: Float, holdMs: Long) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        activity.dispatchTouchEvent(down)
        down.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(holdMs))
        val up = MotionEvent.obtain(t, t + holdMs, MotionEvent.ACTION_UP, x, y, 0)
        activity.dispatchTouchEvent(up)
        up.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    @Test
    fun longPressSelectsWordsTermsAreTappableAndSearchWorks() {
        val file = sample()
        val id = LibraryDatabase(context).use { db ->
            val id = db.add(file.name, Uri.fromFile(file).toString(), "pdf")!!
            db.saveTerm(id, "Casa Atreides", "família do Paul")
            id
        }
        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup().visible()
        val activity = controller.get()
        val root = activity.window.decorView
        val label = { all(root).filterIsInstance<TextView>().firstOrNull { it.text.startsWith("Página ") }?.text?.toString() }
        waitUntil { label() != null }
        val page = all(root).filterIsInstance<ZoomPageView>().single()
        waitUntil { page.fitWidth > 0 }
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()

        // Toque longo em "Arrakis": seleciona a palavra.
        val (ax, ay) = wordPoint(page, "Arrakis")
        press(activity, ax, ay, ViewConfiguration.getLongPressTimeout() + 200L)
        val viewer = ReaderActivity::class.java.getDeclaredField("viewer").apply { isAccessible = true }.get(activity) as BookViewer
        var selected = ""
        assertTrue("nada selecionado", viewer.selection { text, position ->
            selected = text
            assertEquals(0, position)
        })
        assertEquals("Arrakis", selected)

        // Toque simples num espaço vazio da página desfaz a seleção; no termo do glossário, mostra a definição.
        val rect = page.pageRect()
        val location = IntArray(2)
        page.getLocationInWindow(location)
        press(activity, location[0] + rect.centerX(), location[1] + rect.bottom - rect.height() * 0.1f, 50)
        assertTrue(!viewer.selection { _, _ -> })
        val (tx, ty) = wordPoint(page, "Atreides")
        press(activity, tx, ty, 50)
        val dialog = ShadowDialog.getLatestDialog()
        val shown = all(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue("definição não apareceu: $shown", shown.contains("família do Paul"))
        dialog.dismiss()

        // Busca sem acentos.
        all(root).filterIsInstance<TextView>().first { it.contentDescription == "Buscar no livro" }.performClick()
        val input = all(root).filterIsInstance<EditText>().single()
        input.setText("coracao")
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        val counter = { all(root).filterIsInstance<TextView>().firstOrNull { Regex("\\d+/\\d+").matches(it.text) }?.text?.toString() }
        waitUntil { counter() != null }
        assertEquals("1/1", counter())
        controller.pause().stop().destroy()
    }

    @Test
    fun continuousModeAlsoSelectsText() {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("paged_mode", false).commit()
        val file = sample()
        val id = LibraryDatabase(context).use { it.add(file.name, Uri.fromFile(file).toString(), "pdf")!! }
        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup().visible()
        val activity = controller.get()
        val root = activity.window.decorView
        waitUntil { all(root).filterIsInstance<PageItemView>().any { it.page == 0 && it.height > 0 } }
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        val item = all(root).filterIsInstance<PageItemView>().first { it.page == 0 }
        val text = source().let { s -> try { s.text(0) } finally { s.close() } }
        val offset = text.string.indexOf("deserto")
        val r = text.rectsForOffsets(offset, offset + 7).first()
        val location = IntArray(2)
        item.getLocationInWindow(location)
        press(activity, location[0] + (r.left + r.right) / 2 * item.width, location[1] + (r.top + r.bottom) / 2 * item.height,
            ViewConfiguration.getLongPressTimeout() + 200L)
        val viewer = ReaderActivity::class.java.getDeclaredField("viewer").apply { isAccessible = true }.get(activity) as BookViewer
        var selected = ""
        assertTrue(viewer.selection { t, _ -> selected = t })
        assertEquals("deserto", selected)
        controller.pause().stop().destroy()
    }
}
