package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Cálculos do conforto visual (Kotlin puro). */
class ComfortMathTest {
    private fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    private fun close(a: Int, b: Int) = listOf(rgb(a), rgb(b)).let { (x, y) ->
        kotlin.math.abs(x.first - y.first) <= 2 && kotlin.math.abs(x.second - y.second) <= 2 && kotlin.math.abs(x.third - y.third) <= 2
    }

    @Test
    fun lightThemeHasNoFilterAndSepiaTintsThePaper() {
        assertNull(Comfort.pageMatrix(Comfort.Theme.CLARO))
        val sepia = Comfort.pageMatrix(Comfort.Theme.SEPIA)!!
        assertTrue(close(Comfort.Theme.SEPIA.background, Comfort.applyMatrix(sepia, 0xFFFFFFFF.toInt())))
        assertTrue(close(0xFF000000.toInt(), Comfort.applyMatrix(sepia, 0xFF000000.toInt())))
    }

    @Test
    fun darkThemesInvertLightnessButKeepHue() {
        for (theme in listOf(Comfort.Theme.ESCURO, Comfort.Theme.ESCURO_SUAVE)) {
            val m = Comfort.pageMatrix(theme)!!
            assertTrue("branco vira fundo", close(theme.background, Comfort.applyMatrix(m, 0xFFFFFFFF.toInt())))
            assertTrue("preto vira texto", close(theme.text, Comfort.applyMatrix(m, 0xFF000000.toInt())))
            val (r, g, b) = rgb(Comfort.applyMatrix(m, 0xFFD02020.toInt()))
            assertTrue("vermelho continua avermelhado: $r $g $b", r > g + 30 && r > b + 30)
        }
    }

    @Test
    fun brightnessCanGoBelowTheSystemMinimum() {
        assertEquals(-1f, Comfort.windowBrightness(Comfort()), 0f)
        assertEquals(0f, Comfort.extraDim(Comfort()), 0f)
        val dim = Comfort(systemBrightness = false, brightness = 0)
        assertEquals(0.01f, Comfort.windowBrightness(dim), 0.001f)
        assertEquals(0.55f, Comfort.extraDim(dim), 0.001f)
        assertEquals(0f, Comfort.extraDim(Comfort(systemBrightness = false, brightness = 40)), 0f)
        assertEquals(0.4f, Comfort.windowBrightness(Comfort(systemBrightness = false, brightness = 40)), 0.001f)
    }

    @Test
    fun overlaysAndRulerColors() {
        assertEquals(0, Comfort.warmColor(Comfort()) ushr 24)
        assertEquals(82, Comfort.warmColor(Comfort(warmth = 100)) ushr 24)
        assertEquals(0, Comfort.overlayColor(Comfort(overlayOn = false)))
        val blue = Comfort.overlayColor(Comfort(overlayOn = true, overlayColor = Comfort.Tint.AZUL, overlayOpacity = 20))
        assertEquals(51, blue ushr 24)
        assertEquals(Comfort.Tint.AZUL.rgb, blue and 0xFFFFFF)
        val ruler = Comfort.rulerColor(Comfort(rulerColor = Comfort.Tint.VERDE, rulerOpacity = 40))
        assertEquals(102, ruler ushr 24)
        assertEquals(Comfort.Tint.VERDE.rgb, ruler and 0xFFFFFF)
    }

    @Test
    fun lineWidthLimitsTheTextColumn() {
        assertEquals(40, Comfort.sidePadding(1080, 40f, 40, Comfort.LineWidth.LIVRE))
        // 65 caracteres × 0,5 em × 40 px = 1300 px > 1080: vale a margem.
        assertEquals(40, Comfort.sidePadding(1080, 40f, 40, Comfort.LineWidth.MEDIA))
        // Tablet largo: (2000 − 1300) / 2 = 350.
        assertEquals(350, Comfort.sidePadding(2000, 40f, 40, Comfort.LineWidth.MEDIA))
    }

    @Test
    fun epubCssCarriesThemeAndTypography() {
        val c = Comfort(theme = Comfort.Theme.SEPIA, lineSpacing = 180, letterSpacing = 5, lineWidth = Comfort.LineWidth.ESTREITA,
            justify = true, font = Comfort.Font.ATKINSON, margin = Comfort.Margin.GRANDE)
        val css = Comfort.epubCss(c, "@font-face{x}")
        assertTrue(css.contains("background: #F4ECD8"))
        assertTrue(css.contains("color: #5B4636"))
        assertTrue(css.contains("line-height: 1.8"))
        assertTrue(css.contains("letter-spacing: 0.05em"))
        assertTrue(css.contains("max-width: 27.5em"))
        assertTrue(css.contains("text-align: justify"))
        assertTrue(css.contains("padding: 20px 11%"))
        assertTrue(css.contains("@font-face{x}"))
        assertTrue(css.contains("'Atkinson Hyperlegible'"))
        val plain = Comfort.epubCss(Comfort(), "@font-face{x}")
        assertFalse("sem a fonte escolhida, não carrega a Atkinson", plain.contains("@font-face"))
        assertFalse("fonte original do livro é mantida", plain.contains("font-family"))
        assertTrue(plain.contains("text-align: left"))
    }
}

@RunWith(RobolectricTestRunner::class)
class ComfortTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun add(file: File, format: String): Long =
        LibraryDatabase(context).use { it.add(file.name, Uri.fromFile(file).toString(), format)!! }

    private fun launch(id: Long) = Robolectric.buildActivity(
        ReaderActivity::class.java,
        Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
    ).setup().visible()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun all(root: View): List<View> {
        val result = mutableListOf(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) result += all(root.getChildAt(i))
        return result
    }

    @Test
    fun settingsAreSavedAndOldDarkModeIsMigrated() {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("night_mode", true).commit()
        assertEquals(Comfort.Theme.ESCURO, ComfortStore.load(context).theme)
        val custom = Comfort(theme = Comfort.Theme.CINZA, warmth = 30, overlayOn = true, overlayColor = Comfort.Tint.ROSA,
            font = Comfort.Font.ATKINSON, rulerHeight = Comfort.RulerHeight.GROSSA, rulerColor = Comfort.Tint.AZUL, rulerDim = false)
        ComfortStore.save(context, custom)
        assertEquals(custom, ComfortStore.load(context))
    }

    @Test
    fun textReaderUsesThemeFontAndSpacing() {
        ComfortStore.save(context, Comfort(theme = Comfort.Theme.SEPIA, font = Comfort.Font.ATKINSON, lineSpacing = 180, letterSpacing = 6, warmth = 50))
        val file = File(context.cacheDir, "conforto.txt").apply { writeText("Uma linha de teste.\nOutra linha.") }
        val controller = launch(add(file, "txt"))
        val root = controller.get().window.decorView
        val list = all(root).filterIsInstance<ListView>().single()
        waitUntil { list.childCount > 0 }
        val text = list.getChildAt(0) as TextView
        assertEquals(Comfort.Theme.SEPIA.text, text.currentTextColor)
        assertEquals(1.8f, text.lineSpacingMultiplier, 0.001f)
        assertEquals(0.06f, text.letterSpacing, 0.001f)
        assertTrue(text.typeface != android.graphics.Typeface.SERIF)
        // Luz quente: uma camada âmbar sobre a tela inteira, que não bloqueia toques.
        val warm = all(root).filter { v -> (v.background as? android.graphics.drawable.ColorDrawable)?.color == Comfort.warmColor(ComfortStore.load(context)) }
        assertEquals(1, warm.size)
        assertFalse(warm.single().isClickable)
        controller.pause().stop().destroy()
    }

    @Test
    fun epubGetsThemeCssAndAppFont() {
        ComfortStore.save(context, Comfort(theme = Comfort.Theme.ESCURO_SUAVE, font = Comfort.Font.ATKINSON))
        val file = writeZip(File(context.cacheDir, "conforto.epub"), SampleBooks.epub3)
        val controller = launch(add(file, "epub"))
        val web = all(controller.get().window.decorView).filterIsInstance<WebView>().single()
        waitUntil { shadowOf(web).lastLoadDataWithBaseURL != null }
        val html = shadowOf(web).lastLoadDataWithBaseURL.data
        assertTrue(html.contains("background: #2B2B2B"))
        assertTrue(html.contains("font-family: 'Atkinson Hyperlegible'"))
        assertTrue(html.contains("__alexandria/fonts/atkinson-400-normal.woff2"))
        fun request(url: String) = object : android.webkit.WebResourceRequest {
            override fun getUrl() = Uri.parse(url)
            override fun isForMainFrame() = false
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        val client = shadowOf(web).webViewClient
        val font = client.shouldInterceptRequest(web, request("https://book.alexandria/__alexandria/fonts/atkinson-400-normal.woff2"))!!
        assertEquals(200, font.statusCode)
        assertEquals("wOF2", String(font.data.readBytes(), 0, 4))
        assertEquals(404, client.shouldInterceptRequest(web, request("https://book.alexandria/__alexandria/fonts/../licenses/x.txt"))!!.statusCode)
        controller.pause().stop().destroy()
    }

    @Test
    fun rulerFollowsChosenStyle() {
        ComfortStore.save(context, Comfort(rulerHeight = Comfort.RulerHeight.GROSSA, rulerColor = Comfort.Tint.VERDE, rulerOpacity = 40))
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("ruler_on", true).putFloat("ruler_y", 0.5f).commit()
        val file = File(context.cacheDir, "r.txt").apply { writeText("abc") }
        val controller = launch(add(file, "txt"))
        val ruler = all(controller.get().window.decorView).filterIsInstance<ReadingRuler>().single()
        val middle = ruler.height * 0.5f
        val half = context.resources.displayMetrics.density * 70 / 2
        // Régua grossa (70 dp): a alça cobre a faixa inteira.
        assertTrue(ruler.hitsHandle(ruler.width - 5f, middle - half + 2))
        assertTrue(ruler.hitsHandle(ruler.width - 5f, middle + half - 2))
        controller.pause().stop().destroy()
    }

    @Test
    fun continuousModeZoomsWithDoubleTap() {
        val dir = File(System.getProperty("ore.hostEngines") ?: "")
        val present = File(dir, "eracomic").canExecute()
        if (System.getProperty("ore.requireHostEngines") != null) assertTrue(present)
        assumeTrue(present)
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("paged_mode", false).commit()
        val file = File(context.cacheDir, "z.cbr").also { t -> javaClass.getResourceAsStream("/ore/quadrinho-rar5.cbr")!!.use { i -> t.outputStream().use { i.copyTo(it) } } }
        val controller = launch(add(file, "cbr"))
        val activity = controller.get()
        val root = activity.window.decorView
        val label = { all(root).filterIsInstance<TextView>().firstOrNull { it.text.startsWith("Página ") }?.text?.toString() }
        waitUntil { label() != null }
        val list = all(root).filterIsInstance<ListView>().single()
        val location = IntArray(2)
        list.getLocationInWindow(location)
        val x = location[0] + list.width / 2f
        val y = location[1] + 100f
        val start = SystemClock.uptimeMillis()
        for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 40L to MotionEvent.ACTION_UP, 120L to MotionEvent.ACTION_DOWN, 160L to MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, start + offset, action, x, y, 0)
            activity.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2f, list.scaleX, 0.01f)
        assertTrue(label()!!.endsWith("· 200%"))
        controller.pause().stop().destroy()
    }

    @Test
    fun comfortPanelSavesChoices() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ComfortPanel.show(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val chip = all(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text == "Sépia" }
        chip.performClick()
        assertEquals(Comfort.Theme.SEPIA, ComfortStore.load(context).theme)
        val atkinson = all(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text == "Atkinson Hyperlegible" }
        atkinson.performClick()
        assertEquals(Comfort.Font.ATKINSON, ComfortStore.load(context).font)
        dialog.dismiss()
    }
}
