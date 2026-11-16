package com.alexandria.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Gera imagens da Estante (SHELF_PREVIEW=pasta com capas; as imagens são gravadas nela). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ShelfPreviewTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun save(view: View, file: File) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun preview() {
        val dir = File(System.getenv("SHELF_PREVIEW") ?: "")
        assumeTrue(dir.isDirectory)
        context.deleteDatabase("alexandria.db")
        LibraryDatabase(context).use { db ->
            val reading = db.add("Dom Quixote.epub", "content://0", "epub")!!
            db.updateProgress(reading, 4_000, 10)
            val books = listOf(
                "Viagem ao Centro da Terra.epub" to "verne", "Iracema.epub" to "iracema", "Os Lusíadas.pdf" to "lusiadas",
                "Dom Casmurro.txt" to null, "O Alienista.epub" to "alienista", "Memórias Póstumas de Brás Cubas.docx" to null,
                "A Ilha do Tesouro.mobi" to "tesouro", "Relatório de atividades.pdf" to "doc-1"
            )
            books.forEachIndexed { i, (name, cover) ->
                val id = db.add(name, "content://${i + 1}", name.substringAfterLast('.'))!!
                db.setFinished(id, true)
                if (cover != null) File(dir, "$cover.jpg").copyTo(BookFiles.coverFile(context, id), overwrite = true)
                if (i >= 6) db.setShelf(id, "Aventura")
            }
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val header = find(activity.window.decorView) { it is TextView && it.text.startsWith("▶  🏆 Estante") }!!
        header.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        save(find(activity.window.decorView) { it is WoodenShelf }!!, File(dir, "estante-fechada.png"))
        find(activity.window.decorView) { it is ShelfPlate }!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val shelf = find(root) { it is WoodenShelf }!!
        val scroll = find(root) { it is ScrollView } as ScrollView
        val estante = find(root) { it is TextView && it.text.startsWith("▼  🏆 Estante") }!!
        scroll.scrollTo(0, estante.top - 20)
        shadowOf(Looper.getMainLooper()).idle()
        save(root, File(dir, "tela.png"))
        save(shelf, File(dir, "estante-inteira.png"))
        val book = find(shelf) { it is ShelfBook && it.item.name.startsWith("Iracema") }!!
        book.pivotX = 0f
        book.pivotY = book.height / 2f
        book.rotationY = -22f
        save(shelf, File(dir, "estante-toque.png"))
    }

    @Test
    fun focusPreview() {
        val dir = File(System.getenv("SHELF_PREVIEW") ?: "")
        assumeTrue(dir.isDirectory)
        context.deleteDatabase("alexandria.db")
        ComfortStore.save(context, Comfort(rulerOutside = Comfort.RulerOutside.FOCO))
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("ruler_on", true).putFloat("ruler_y", 0.35f).commit()
        val text = File(context.cacheDir, "Dom Casmurro.txt").apply {
            writeText((1..12).joinToString("\n\n") {
                "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central um rapaz aqui do bairro, que eu conheço de vista e de chapéu. Cumprimentou-me, sentou-se ao pé de mim, falou da lua e dos ministros, e acabou recitando-me versos."
            })
        }
        val id = LibraryDatabase(context).use { it.add(text.name, android.net.Uri.fromFile(text).toString(), "txt")!! }
        val activity = Robolectric.buildActivity(
            ReaderActivity::class.java,
            android.content.Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup().visible().get()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val root = activity.window.decorView
        save(root, File(dir, "foco.png"))
        (find(root) { it is ReadingRuler } as ReadingRuler).revealHandle()
        save(root, File(dir, "foco-alca.png"))
    }

    @Test
    fun bookmarkPreview() {
        val dir = File(System.getenv("SHELF_PREVIEW") ?: "")
        assumeTrue(dir.isDirectory)
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val text = File(context.cacheDir, "Dom Casmurro.txt").apply {
            writeText((1..40).joinToString("\n\n") {
                "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central um rapaz aqui do bairro, que eu conheço de vista e de chapéu. Cumprimentou-me, sentou-se ao pé de mim, falou da lua e dos ministros, e acabou recitando-me versos."
            })
        }
        val id = LibraryDatabase(context).use { it.add(text.name, android.net.Uri.fromFile(text).toString(), "txt")!! }
        val activity = Robolectric.buildActivity(
            ReaderActivity::class.java,
            android.content.Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup().visible().get()
        fun settle(ms: Long) {
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        settle(3_000)
        val root = activity.window.decorView
        find(root) { it is BookmarkCorner }!!.performClick()
        settle(300)
        save(root, File(dir, "marcador-adicionado.png"))
        find(root) { it is TextView && it.text == "🔖" }!!.performClick()
        settle(300)
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        save(dialog.window!!.decorView, File(dir, "marcador-lista.png"))
        (find(dialog.window!!.decorView) { it is TextView && it.text == "Marcador 1" }!!.parent as View).performClick()
        settle(1_000)
        save(root, File(dir, "marcador-barra.png"))
    }

    @Test
    fun chromePreview() {
        val dir = File(System.getenv("SHELF_PREVIEW") ?: "")
        assumeTrue(dir.isDirectory)
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val comic = File(context.cacheDir, "Quadrinho de exemplo.cbz")
        java.util.zip.ZipOutputStream(comic.outputStream()).use { zip ->
            for (name in listOf("lusiadas.png", "verne.png")) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                File(dir, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val id = LibraryDatabase(context).use { it.add(comic.name, android.net.Uri.fromFile(comic).toString(), "cbz")!! }
        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            android.content.Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)
        ).setup().visible()
        val activity = controller.get()
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val root = activity.window.decorView
        save(root, File(dir, "limpa.png"))
        val page = find(root) { it is ZoomPageView }!!
        val location = IntArray(2)
        page.getLocationInWindow(location)
        val x = location[0] + page.width / 2f
        val y = location[1] + page.height / 2f
        val t = android.os.SystemClock.uptimeMillis()
        activity.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        activity.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        save(root, File(dir, "controles.png"))
        controller.pause().stop().destroy()
    }
}
