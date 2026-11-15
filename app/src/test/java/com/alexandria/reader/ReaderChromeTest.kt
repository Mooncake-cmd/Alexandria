package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tela limpa (controles escondidos até tocar) e bloqueio do movimento para os lados. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderChromeTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
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

    private fun all(root: View): List<View> {
        val result = mutableListOf(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) result += all(root.getChildAt(i))
        return result
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            idle()
        }
    }

    private fun gesture(activity: ReaderActivity, points: List<Pair<Float, Float>>) {
        val start = SystemClock.uptimeMillis()
        points.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(start, start + i * 16L, action, x, y, 0)
            activity.dispatchTouchEvent(event)
            event.recycle()
        }
        idle()
    }

    private fun center(view: View): Pair<Float, Float> {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Pair(location[0] + view.width / 2f, location[1] + view.height / 2f)
    }

    private fun menuButton(root: View) = all(root).filterIsInstance<TextView>().first { it.text == "⋮" }

    @Test
    fun cleanScreenHidesControlsUntilATap() {
        val file = File(context.cacheDir, "limpa.txt").apply { writeText((1..40).joinToString("\n") { "Linha $it do livro." }) }
        val controller = launch(add(file, "txt"))
        val activity = controller.get()
        val root = activity.window.decorView
        idle(500)
        assertFalse("o livro abre com a tela limpa", menuButton(root).isShown)

        val list = all(root).filterIsInstance<android.widget.ListView>().single()
        val (x, y) = center(list)
        gesture(activity, listOf(x to y, x to y))
        idle(600) // espera o toque duplo não acontecer
        assertTrue("um toque mostra os controles", menuButton(root).isShown)

        gesture(activity, listOf(x to y, x to y))
        idle(600)
        assertFalse("outro toque esconde", menuButton(root).isShown)

        // Toque duplo (virar página nas bordas, zoom) não mostra os controles.
        val start = SystemClock.uptimeMillis()
        for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 40L to MotionEvent.ACTION_UP, 120L to MotionEvent.ACTION_DOWN, 160L to MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, start + offset, action, x, y, 0).also { activity.dispatchTouchEvent(it) }.recycle()
        }
        idle(600)
        assertFalse(menuButton(root).isShown)
        controller.pause().stop().destroy()
    }

    @Test
    fun alwaysVisibleControlsKeepTheBookBetweenTheBars() {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("clean_screen", false).commit()
        val file = File(context.cacheDir, "fixa.txt").apply { writeText("Texto.\nOutra linha.") }
        val controller = launch(add(file, "txt"))
        val root = controller.get().window.decorView
        idle(500)
        val menu = menuButton(root)
        assertTrue(menu.isShown)
        val list = all(root).filterIsInstance<android.widget.ListView>().single()
        val bar = IntArray(2).also { (menu.parent as View).getLocationInWindow(it) }
        val body = IntArray(2).also { list.getLocationInWindow(it) }
        assertTrue("o texto começa abaixo da barra", body[1] >= bar[1] + (menu.parent as View).height)
        controller.pause().stop().destroy()
    }

    /** Quadrinho de uma página grande, para dar zoom e arrastar. */
    private fun comic(): File {
        val file = File(context.cacheDir, "lados.cbz")
        val page = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("01.png", "02.png")) {
                zip.putNextEntry(ZipEntry(name))
                page.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun sideLockKeepsHorizontalPositionButAllowsVerticalAndZoom() {
        val controller = launch(add(comic(), "cbz"))
        val activity = controller.get()
        val root = activity.window.decorView
        val page = all(root).filterIsInstance<ZoomPageView>().single()
        waitUntil { page.pageRect().width() > 0 && page.width > 0 }

        // Toque duplo no meio: zoom de 2×.
        val (cx, cy) = center(page)
        val start = SystemClock.uptimeMillis()
        for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 40L to MotionEvent.ACTION_UP, 120L to MotionEvent.ACTION_DOWN, 160L to MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, start + offset, action, cx, cy, 0).also { activity.dispatchTouchEvent(it) }.recycle()
        }
        idle(600)
        assertTrue(page.zoom > 1.5f)

        // Sem bloqueio, arrastar para o lado move a página.
        var before = page.pageRect()
        gesture(activity, listOf(cx to cy, cx - 60 to cy, cx - 120 to cy, cx - 160 to cy))
        assertNotEquals(before.left, page.pageRect().left, 0.5f)

        // Com o bloqueio, o lado fica parado e o vertical continua.
        val lock = all(root).filterIsInstance<TextView>().first { it.text.startsWith("⟷") }
        assertTrue(lock.text.contains("Bloquear"))
        lock.performClick()
        idle()
        assertTrue(lock.text.contains("Bloqueado"))
        assertTrue(ReadingPrefs.sideLock(activity))
        before = page.pageRect()
        gesture(activity, listOf(cx to cy, cx + 60 to cy - 40, cx + 120 to cy - 80, cx + 160 to cy - 120))
        val after = page.pageRect()
        assertEquals(before.left, after.left, 0.5f)
        assertNotEquals(before.top, after.top, 0.5f)
        controller.pause().stop().destroy()

        // A escolha vale para os próximos livros.
        val again = launch(add(comic().copyTo(File(context.cacheDir, "outro.cbz"), overwrite = true), "cbz"))
        val text = all(again.get().window.decorView).filterIsInstance<TextView>().first { it.text.startsWith("⟷") }.text
        assertTrue(text.contains("Bloqueado"))
        again.pause().stop().destroy()
    }
}
