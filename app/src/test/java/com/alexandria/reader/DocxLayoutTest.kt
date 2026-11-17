package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/** DOCX mais fiel: figuras com tamanho e lado, tabelas com colunas, mesclas e bordas, alinhamento. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocxLayoutTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main""""

    private fun anchor(id: String, x: Long, cx: Long, vRel: String = "paragraph", y: Long = 0) =
        """<w:r><w:drawing><wp:anchor behindDoc="0"><wp:positionH relativeFrom="page"><wp:posOffset>$x</wp:posOffset></wp:positionH><wp:positionV relativeFrom="$vRel"><wp:posOffset>$y</wp:posOffset></wp:positionV><wp:extent cx="$cx" cy="499024"/><a:graphic><a:blip r:embed="$id"/></a:graphic></wp:anchor></w:drawing></w:r>"""

    private val document get() = """<w:document $ns><w:body>
      <w:p>${anchor("rId1", 767470, 1304218)}${anchor("rId2", 5896355, 1523621)}${anchor("rId3", 0, 7560073, "page", 10096500)}</w:p>
      <w:tbl><w:tblPr><w:tblBorders><w:top w:val="single"/><w:insideV w:val="single"/></w:tblBorders></w:tblPr>
        <w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="1000"/></w:tblGrid>
        <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>Título da tabela</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>Curso</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr>
      </w:tbl>
      <w:p><w:pPr><w:jc w:val="both"/><w:ind w:left="480" w:firstLine="240"/></w:pPr><w:r><w:rPr><w:sz w:val="36"/></w:rPr><w:t>Grande</w:t></w:r><w:r><w:t> normal</w:t></w:r><w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:t>1</w:t></w:r></w:p>
      <w:sectPr><w:pgSz w:w="11910" w:h="16840"/><w:pgMar w:left="1701" w:right="1134"/></w:sectPr>
    </w:body></w:document>"""

    @Test
    fun layoutOfTheDocumentIsKept() {
        val relations = mapOf("rId1" to "word/media/a.png", "rId2" to "word/media/b.png", "rId3" to "word/media/c.png")
        val blocks = DocxReader.blocks(document.toByteArray(), DocxReader.Styles(defaultSize = 24), relations)
        assertEquals(listOf("Para", "Table", "Para"), blocks.map { it.javaClass.simpleName })
        val figures = (blocks[0] as DocxBlock.Para).p.figures
        assertEquals(listOf(DocxFigure.Side.LEFT, DocxFigure.Side.RIGHT, DocxFigure.Side.CENTER), figures.map { it.side })
        assertEquals(listOf(DocxFigure.Place.INLINE, DocxFigure.Place.INLINE, DocxFigure.Place.END), figures.map { it.place })
        assertEquals(listOf(137, 160, 794), figures.map { it.width })
        assertEquals(1f, figures[2].fraction, 0.001f) // mais larga que o texto: limitada à largura toda
        val table = (blocks[1] as DocxBlock.Table).table
        assertTrue(table.borders)
        assertEquals(listOf(0.75f, 0.25f), table.columns)
        assertEquals(listOf(listOf(2 to 1), listOf(1 to 2, 1 to 1), listOf(1 to 1)), table.rows.map { r -> r.map { it.span to it.rowSpan } })
        assertEquals(listOf(0, 0, 1, 1), table.rows.flatten().map { it.column })
        val title = (table.rows[0][0].blocks[0] as DocxBlock.Para).p
        assertEquals(DocxParagraph.Align.CENTER, title.align)
        val p = (blocks[2] as DocxBlock.Para).p
        assertEquals(DocxParagraph.Align.JUSTIFY, p.align)
        assertEquals(2f, p.indent, 0.001f)
        assertEquals(1f, p.firstLine, 0.001f)
        assertEquals(listOf(Triple("Grande", 1.5f, 0), Triple("1", null, 1)), p.styles.map { Triple(p.text.substring(it.start, it.end), it.size, it.vert) })
        // Busca e cópia continuam vendo todo o texto, na ordem.
        assertEquals(listOf("Título da tabela", "Curso", "A", "B", "Grande normal1"), DocxReader.flatten(blocks).map { it.text }.filter { it.isNotEmpty() })
    }

    @Test
    fun chunksPutLogosSideBySideTablesApartAndThePageFooterAtTheEnd() {
        val relations = mapOf("rId1" to "word/media/a.png", "rId2" to "word/media/b.png", "rId3" to "word/media/c.png")
        val blocks = DocxReader.blocks(document.toByteArray(), DocxReader.Styles(defaultSize = 24), relations)
        val chunks = DocxLayout.chunksOf(blocks) { path -> File(context.cacheDir, path.substringAfterLast('/')) }
        assertEquals(listOf("ImageChunk", "TableChunk", "SpannableStringBuilder", "ImageChunk"), chunks.map { it.javaClass.simpleName })
        assertEquals(2, (chunks[0] as ImageChunk).figures.size)
        assertEquals("Título da tabela\nCurso\tA\nB", chunks[1].toString())
        assertEquals("c.png", (chunks[3] as ImageChunk).file.name)
    }

    @Test
    fun styleSizeComesFromTheDocument() {
        val w = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""
        assertEquals(22, DocxReader.styleInfo("""<w:styles $w><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>""".byteInputStream()).defaultSize)
        assertEquals(24, DocxReader.styleInfo("""<w:styles $w><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:rPr><w:sz w:val="24"/></w:rPr></w:style></w:styles>""".byteInputStream()).defaultSize)
    }

    /** Prévia do leitor com um DOCX real (DOCX_PREVIEW=arquivo.docx; a imagem vai ao lado dele). */
    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun preview() {
        val source = File(System.getenv("DOCX_PREVIEW") ?: "")
        assumeTrue(source.isFile)
        context.deleteDatabase("alexandria.db")
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("clean_screen", true).commit()
        val file = source.copyTo(File(context.cacheDir, "modelo.docx"), overwrite = true)
        val id = LibraryDatabase(context).use { it.add(file.name, Uri.fromFile(file).toString(), "docx")!! }
        val activity = Robolectric.buildActivity(ReaderActivity::class.java, Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, id)).setup().visible().get()
        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val root = activity.window.decorView
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        assertTrue(all(root).any { it is ListView })
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(source.parentFile, "app-docx.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
