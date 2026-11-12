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
}
