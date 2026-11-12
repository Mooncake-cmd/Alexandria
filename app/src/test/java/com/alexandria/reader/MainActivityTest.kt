package com.alexandria.reader

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.deleteDatabase("alexandria.db")
        LibraryDatabase(context).use { db ->
            val reading = db.add("Dom Casmurro.epub", "content://1", "epub")!!
            val done = db.add("Manual.pdf", "content://2", "pdf")!!
            val shelved = db.add("Quadrinho.cbz", "content://3", "cbz")!!
            db.add("Antigo.mobi", "content://4", "mobi")
            db.updateProgress(reading, 2_500, 10)
            db.setFinished(done, true)
            db.setShelf(shelved, "HQs")
            db.addNote(reading, "Uma noite destas", "Começo famoso", 2_500)
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase("alexandria.db")
    }

    private fun texts(root: View): List<String> {
        val result = mutableListOf<String>()
        if (root is TextView) result += root.text.toString()
        if (root is ViewGroup) for (i in 0 until root.childCount) result += texts(root.getChildAt(i))
        return result
    }

    private fun shelfBooks(root: View): List<ShelfBook> = when (root) {
        is ShelfBook -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { shelfBooks(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun click(root: View, text: String) {
        fun find(view: View): View? {
            if (view is TextView && view.text.toString().startsWith(text)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        (find(root) ?: error("\"$text\" não encontrado")).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun libraryShowsReadingShelvesAndCollapsedEstante() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        var shown = texts(root)
        assertTrue(shown.contains("Dom Casmurro"))
        assertTrue(shown.contains("25% lido"))
        assertTrue(shown.contains("Antigo"))
        assertTrue(shown.any { it.startsWith("▶  📁 HQs (1)") })
        assertFalse("Quadrinho fica dentro da prateleira recolhida", shown.contains("Quadrinho"))
        assertTrue(shown.any { it.contains("Estante · 1 concluído") })
        assertFalse("Estante começa recolhida", shown.contains("Manual"))

        click(root, "▶  📁 HQs")
        click(activity.window.decorView, "▶  🏆 Estante")
        shown = texts(activity.window.decorView)
        assertTrue(shown.contains("Quadrinho"))
        // Na Estante de madeira o livro é desenhado; título e data ficam na descrição (leitor de tela).
        val books = shelfBooks(activity.window.decorView)
        assertEquals(listOf("Manual"), books.map { it.contentDescription.toString().substringBefore(". ") })
        assertTrue(books.single().contentDescription.contains("Concluído em"))
        // Manter pressionado mostra as opções, com a data de conclusão no cabeçalho.
        books.single().performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(texts(dialog.window!!.decorView).any { it.startsWith("Concluído em") })
    }

    private fun <T : View> all(root: View, type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        if (type.isInstance(root)) result += type.cast(root)!!
        if (root is ViewGroup) for (i in 0 until root.childCount) result += all(root.getChildAt(i), type)
        return result
    }

    @Test
    fun estanteShelvesOpenAndCloseByThePlate() {
        LibraryDatabase(context).use { db ->
            val saga = db.add("Saga.cbz", "content://9", "cbz")!!
            db.setFinished(saga, true)
            db.setShelf(saga, "Sagas")
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity.window.decorView, "▶  🏆 Estante")
        var plate = all(activity.window.decorView, ShelfPlate::class.java).single()
        assertEquals("Sagas", plate.name)
        assertFalse("prateleira da Estante começa fechada", plate.open)
        assertEquals(listOf("Manual.pdf"), shelfBooks(activity.window.decorView).map { it.item.name })

        plate.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        plate = all(activity.window.decorView, ShelfPlate::class.java).single()
        assertTrue(plate.open)
        assertEquals(listOf("Manual.pdf", "Saga.cbz"), shelfBooks(activity.window.decorView).map { it.item.name })

        // Manter a placa pressionada renomeia a prateleira.
        plate.performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertEquals("Renomear prateleira", org.robolectric.Shadows.shadowOf(dialog).title)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun coverCanBeChangedFromTheGalleryAndRestored() {
        // Foto deitada (1000×500): a capa sai recortada no centro, em pé (2:3).
        val photo = java.io.File(context.cacheDir, "foto.png")
        android.graphics.Bitmap.createBitmap(1000, 500, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(200, 40, 40))
            photo.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity.window.decorView, "▶  🏆 Estante")
        val manual = shelfBooks(activity.window.decorView).single()

        fun chooseOption(text: String) {
            manual.let { shelfBooks(activity.window.decorView).single().performLongClick() }
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
            val list = dialog.listView
            val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == text }
            list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
            shadowOf(Looper.getMainLooper()).idle()
        }

        chooseOption("Trocar capa…")
        val started = shadowOf(activity).nextStartedActivityForResult
        val picker = started.intent.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals("image/*", picker.type)
        shadowOf(activity).receiveResult(started.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(android.net.Uri.fromFile(photo)))
        val id = manual.item.id
        val custom = BookFiles.customCoverFile(context, id)
        val deadline = System.currentTimeMillis() + 10_000
        while (!custom.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(custom.exists())
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(custom.path, bounds)
        assertEquals(BookFiles.CUSTOM_COVER_WIDTH, bounds.outWidth)
        assertEquals(BookFiles.CUSTOM_COVER_HEIGHT, bounds.outHeight)
        assertEquals(BookFiles.CUSTOM_COVER_WIDTH, BookFiles.loadCover(context, id)!!.width)

        chooseOption("Restaurar capa original")
        assertFalse(custom.exists())
    }

    @Test
    fun notesAndStatsTabs() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity.window.decorView, "Notas")
        var shown = texts(activity.window.decorView)
        assertTrue(shown.contains("“Uma noite destas”"))
        assertTrue(shown.contains("Começo famoso"))
        assertTrue(shown.any { it.startsWith("Cap. 3 · 25%") })

        click(activity.window.decorView, "Estatísticas")
        shown = texts(activity.window.decorView)
        assertTrue(shown.contains("na biblioteca"))
        assertTrue(shown.contains("4"))
        assertTrue(shown.contains("EPUB  ·  1"))
    }

    @Test
    fun settingsShowLicensesAndCache() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity.window.decorView, "Ajustes")
        val shown = texts(activity.window.decorView)
        assertTrue(shown.any { it.startsWith("Versão ") })
        assertTrue(shown.any { it.startsWith("Cópias e conversões em cache:") })
        assertTrue(shown.contains("›  EraPDF (MuPDF), EraMOBI e EraDjVu — GNU AGPL 3.0"))
        assertTrue(shown.contains("›  Fonte Atkinson Hyperlegible — SIL OFL 1.1"))
        assertTrue(shown.contains("›  EraComic — GNU GPL 2.0"))
        // Todos os textos de licença listados existem nos assets do app.
        for (file in listOf("AGPL-3.0.txt", "GPL-2.0.txt", "LGPL-3.0.txt", "GPL-3.0.txt", "Apache-2.0.txt", "terceiros.txt")) {
            val text = activity.assets.open("licenses/$file").use { String(it.readBytes()) }
            assertTrue("$file vazio", text.length > 1000)
        }
    }
}
