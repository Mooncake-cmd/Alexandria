package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

class PageTurnTest {
    @Test
    fun edgesTurnAndMiddleDoesNot() {
        assertEquals(-1, PageTurn.direction(10f, 1000f))
        assertEquals(-1, PageTurn.direction(299f, 1000f))
        assertEquals(0, PageTurn.direction(500f, 1000f))
        assertEquals(1, PageTurn.direction(701f, 1000f))
        assertEquals(0, PageTurn.direction(5f, 0f))
    }

    @Test
    fun wholePageFitsTheArea() {
        // Página retrato numa tela retrato mais alta: limitada pela largura.
        assertEquals(Pair(1080, 1440), PageTurn.fitPage(1080, 2000, 1440f / 1080f))
        // Página muito alta: limitada pela altura, e a largura diminui.
        assertEquals(Pair(500, 1500), PageTurn.fitPage(1080, 1500, 3f))
        // Página paisagem.
        assertEquals(Pair(1000, 500), PageTurn.fitPage(1000, 1600, 0.5f))
    }

    @Test
    fun scrollStepKeepsContext() {
        assertEquals(900, PageTurn.scrollStep(1000, 100))
        assertEquals(500, PageTurn.scrollStep(1000, 900)) // nunca menos que meia tela
    }
}

@RunWith(RobolectricTestRunner::class)
class ReadingModesTest {
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

    private fun resource(name: String): File = File(context.cacheDir, name).also { target ->
        javaClass.getResourceAsStream("/ore/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun requireEngines() {
        val dir = File(System.getProperty("ore.hostEngines") ?: "")
        val present = listOf("eramobi", "eradjvu", "eracomic").all { File(dir, it).canExecute() }
        if (System.getProperty("ore.requireHostEngines") != null) assertTrue(present)
        assumeTrue(present)
    }

    /** Dois toques rápidos no ponto (x, y) da área [target], entregues pela janela da atividade. */
    private fun doubleTap(activity: ReaderActivity, target: View, fractionX: Float) {
        val location = IntArray(2)
        target.getLocationInWindow(location)
        val x = location[0] + target.width * fractionX
        val y = location[1] + target.height / 2f
        val start = SystemClock.uptimeMillis()
        for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 40L to MotionEvent.ACTION_UP, 120L to MotionEvent.ACTION_DOWN, 160L to MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, start + offset, action, x, y, 0)
            activity.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pageLabel(root: View) = all(root).filterIsInstance<TextView>().firstOrNull { it.text.startsWith("Página ") }?.text?.toString()

    @Test
    fun pagedModeTurnsDjvuPagesByDoubleTappingTheEdges() {
        requireEngines()
        val controller = launch(add(resource("amostra.djvu"), "djvu"))
        val activity = controller.get()
        val root = activity.window.decorView
        waitUntil { pageLabel(root) != null }
        assertEquals("Página 1 de 2", pageLabel(root))
        val page = all(root).filterIsInstance<ZoomPageView>().single()

        doubleTap(activity, page, 0.5f) // meio: zoom, não vira
        waitUntil { pageLabel(root)?.contains("%") == true }
        assertTrue(pageLabel(root)!!.startsWith("Página 1 de 2 · 200%"))

        doubleTap(activity, page, 0.9f) // borda direita: avança
        waitUntil { pageLabel(root) == "Página 2 de 2" }
        assertEquals("Página 2 de 2", pageLabel(root))
        doubleTap(activity, page, 0.1f) // borda esquerda: volta
        waitUntil { pageLabel(root) == "Página 1 de 2" }
        assertEquals("Página 1 de 2", pageLabel(root))
        controller.pause().stop().destroy()
    }

    @Test
    fun continuousModeListsAllPages() {
        requireEngines()
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("paged_mode", false).commit()
        val controller = launch(add(resource("quadrinho-rar5.cbr"), "cbr"))
        val root = controller.get().window.decorView
        waitUntil { pageLabel(root) != null }
        val list = all(root).filterIsInstance<ListView>().single()
        assertEquals(3, list.adapter.count)
        assertEquals("Página 1 de 3", pageLabel(root))
        assertTrue(all(root).filterIsInstance<ZoomPageView>().isEmpty())
        controller.pause().stop().destroy()
    }

    @Test
    fun pagedTextTurnsAScreenAtATime() {
        val file = File(context.cacheDir, "longo.txt").apply { writeText((1..4_000).joinToString("\n") { "Linha $it do livro." }) }
        val id = add(file, "txt")
        val controller = launch(id)
        val activity = controller.get()
        val root = activity.window.decorView
        val list = all(root).filterIsInstance<ListView>().single()
        waitUntil { list.adapter.count > 0 }
        shadowOf(Looper.getMainLooper()).idle()
        val before = list.firstVisiblePosition to list.getChildAt(0).top

        doubleTap(activity, list, 0.95f)
        val after = list.firstVisiblePosition to list.getChildAt(0).top
        assertTrue("devia ter avançado: $before → $after", after != before)

        doubleTap(activity, list, 0.05f)
        assertEquals(before, list.firstVisiblePosition to list.getChildAt(0).top)
        controller.pause().stop().destroy()
    }

    /** Linhas na tela: (bloco, linha) → topo, em pixels da lista. */
    private fun lineTops(list: ListView): Map<Pair<Int, Int>, Int> {
        val result = HashMap<Pair<Int, Int>, Int>()
        for (i in 0 until list.childCount) {
            val view = list.getChildAt(i) as? TextView ?: continue
            val layout = view.layout ?: continue
            for (line in 0 until layout.lineCount) result[(list.firstVisiblePosition + i) to line] = view.top + view.totalPaddingTop + layout.getLineTop(line)
        }
        return result
    }

    @Test
    fun pagesEndOnAWholeLineAndTheNextStartsRightAfter() {
        val text = (1..600).joinToString("\n") { "Parágrafo $it com texto suficiente para quebrar em mais de uma linha na tela do leitor." }
        val file = File(context.cacheDir, "linhas.txt").apply { writeText(text) }
        val controller = launch(add(file, "txt"))
        val activity = controller.get()
        val root = activity.window.decorView
        val list = all(root).filterIsInstance<ListView>().single()
        val mask = all(root).filterIsInstance<PageMask>().single()
        waitUntil { list.adapter.count > 0 && list.childCount > 0 }
        shadowOf(Looper.getMainLooper()).idle()
        // O livro começa com a primeira linha inteira, logo abaixo da margem.
        assertEquals(list.paddingTop, lineTops(list)[0 to 0])
        repeat(3) {
            val windowBottom = list.height - list.paddingBottom
            val cover = mask.coverBottom
            assertTrue("a cobertura fica dentro da janela", cover <= windowBottom + 0.5f)
            // A linha que começa onde a cobertura começa (ou a primeira abaixo da janela) abre a próxima página.
            val next = lineTops(list).entries.filter { it.value >= cover - 1 }.minByOrNull { it.value }!!.key
            doubleTap(activity, list, 0.95f)
            val first = lineTops(list).entries.filter { it.value >= list.paddingTop - 1 }.minByOrNull { it.value }!!
            assertEquals("a página começa na linha seguinte", next, first.key)
            assertEquals(list.paddingTop.toFloat(), first.value.toFloat(), 1f)
        }
        controller.pause().stop().destroy()
    }

    @Test
    fun rulerTogglesAndRemembersPosition() {
        val file = File(context.cacheDir, "regua.txt").apply { writeText("Texto curto.\nOutra linha.") }
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("ruler_on", true).putFloat("ruler_y", 0.7f).commit()
        val controller = launch(add(file, "txt"))
        val ruler = all(controller.get().window.decorView).filterIsInstance<ReadingRuler>().single()
        assertEquals(View.VISIBLE, ruler.visibility)
        assertEquals(0.7f, ruler.fraction, 0.001f)
        // A alça começa escondida: o toque no lugar dela ainda vai para o livro.
        assertFalse(ruler.handleShown)
        assertFalse(ruler.hitsHandle(ruler.width - 10f, ruler.height * 0.7f))
        // Um toque rápido na faixa mostra a alça (e o toque segue para o livro).
        val activity = controller.get()
        val location = IntArray(2)
        ruler.getLocationInWindow(location)
        val x = location[0] + ruler.width / 2f
        val y = location[1] + ruler.height * 0.7f
        val down = android.os.SystemClock.uptimeMillis()
        activity.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        activity.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down + 50, android.view.MotionEvent.ACTION_UP, x, y, 0))
        assertTrue(ruler.handleShown)
        // Fora da alça, o toque passa para o livro; na alça, é da régua.
        assertFalse(ruler.hitsHandle(10f, ruler.height * 0.7f))
        assertTrue(ruler.hitsHandle(ruler.width - 10f, ruler.height * 0.7f))
        // Some sozinha depois de alguns segundos.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(ReadingRuler.HANDLE_SECONDS + 1L))
        assertFalse(ruler.handleShown)
        controller.pause().stop().destroy()
    }
}
