package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.ListView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TextReaderTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        // Celulares ficam em "touch mode"; o Robolectric começa fora dele, como um aparelho com teclado.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
    }

    private fun addText(name: String, content: String): Long {
        val file = File(context.cacheDir, name).apply { writeText(content) }
        LibraryDatabase(context).use { db ->
            db.add(name, Uri.fromFile(file).toString(), "txt")
            return db.all().first().id
        }
    }

    /** Espera a thread de leitura terminar e entrega o resultado à thread principal. */
    private fun waitForLoad(list: ListView) {
        val deadline = System.currentTimeMillis() + 10_000
        while (list.adapter.count == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Índice do bloco na linha de leitura, logo abaixo do padding do topo. */
    private fun readingIndex(list: ListView): Int {
        var slot = 0
        while (slot < list.childCount - 1 && list.getChildAt(slot).bottom <= list.paddingTop) slot++
        return list.firstVisiblePosition + slot
    }

    private fun findList(activity: ReaderActivity): ListView {
        return findListIn(activity.window.decorView) ?: error("ListView não encontrada")
    }

    private fun findListIn(view: android.view.View): ListView? {
        if (view is ListView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) findListIn(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun longTextIsSplitIntoChunksAndProgressIsSaved() {
        val text = (1..5_000).joinToString("\n") { "Linha $it do texto de teste, com acentuação." }
        val id = addText("grande.txt", text)

        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup()
        val list = findList(controller.get())
        waitForLoad(list)
        assertTrue("esperava vários blocos, veio ${list.adapter.count}", list.adapter.count > 50)
        assertEquals(text, (0 until list.adapter.count).joinToString("\n") { list.adapter.getItem(it) as String })

        controller.pause().stop().destroy()
        LibraryDatabase(context).use { db ->
            assertTrue(db.find(id)!!.openedAt > 0)
        }
    }

    @Test
    fun readingPositionIsRestoredWhenReopening() {
        val text = (1..5_000).joinToString("\n") { "Linha $it do texto de teste." }
        val id = addText("posicao.txt", text)
        val intent = Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)

        val first = Robolectric.buildActivity(ReaderActivity::class.java, intent).setup().visible()
        val list = findList(first.get())
        waitForLoad(list)
        shadowOf(Looper.getMainLooper()).idle()
        val count = list.adapter.count
        val target = count / 2
        list.setSelectionFromTop(target, 0)
        shadowOf(Looper.getMainLooper()).idle()
        first.pause().stop().destroy()

        val saved = LibraryDatabase(context).use { it.find(id)!!.position }
        val expected = target * 10_000 / count
        assertTrue("fração salva $saved, esperada perto de $expected", kotlin.math.abs(saved - expected) <= 10_000 / count)

        val second = Robolectric.buildActivity(ReaderActivity::class.java, intent).setup().visible()
        val reopened = findList(second.get())
        waitForLoad(reopened)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(target, readingIndex(reopened))
        // Também no meio de um bloco: restaura a mesma fração (com margem de 1 pixel de arredondamento).
        val child = reopened.getChildAt(target - reopened.firstVisiblePosition)
        var remaining = child.height / 2
        while (remaining > 0) {
            val delta = minOf(remaining, reopened.height - reopened.paddingTop - reopened.paddingBottom - 2)
            reopened.scrollListBy(delta)
            remaining -= delta
        }
        shadowOf(Looper.getMainLooper()).idle()
        second.pause().stop().destroy()
        val middle = LibraryDatabase(context).use { it.find(id)!!.position }
        assertEquals((target + 0.5) / count * 10_000, middle.toDouble(), 2.0)

        val third = Robolectric.buildActivity(ReaderActivity::class.java, intent).setup().visible()
        val again = findList(third.get())
        waitForLoad(again)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(target, readingIndex(again))
        third.pause().stop().destroy()
        val afterReopen = LibraryDatabase(context).use { it.find(id)!!.position }
        assertEquals(middle.toDouble(), afterReopen.toDouble(), 2.0)
    }

    @Test
    fun emptyFileClosesTheReader() {
        val id = addText("vazio.txt", "")
        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup()
        val deadline = System.currentTimeMillis() + 10_000
        while (!controller.get().isFinishing && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun unknownItemClosesTheReader() {
        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, 999L)
        ).setup()
        assertTrue(controller.get().isFinishing)
    }
}
