package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Cria um ZIP de teste com os arquivos dados (nome → conteúdo). */
fun writeZip(file: File, entries: Map<String, String>): File {
    ZipOutputStream(FileOutputStream(file)).use { zip ->
        for ((name, body) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return file
}

object SampleBooks {
    val epub3 = mapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""",
        "OEBPS/content.opf" to """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Dom Casmurro</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="Text/cap%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/cap2.xhtml" media-type="application/xhtml+xml"/>
                <item id="extra" href="Text/notas.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="Images/capa.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="css" href="style.css" media-type="text/css"/>
              </manifest>
              <spine>
                <itemref idref="c1"/><itemref idref="missing"/><itemref idref="c2"/><itemref idref="extra" linear="no"/>
              </spine>
            </package>""",
        "OEBPS/nav.xhtml" to """<?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <nav epub:type="landmarks"><ol><li><a href="Text/cap2.xhtml">Errado</a></li></ol></nav>
              <nav epub:type="toc"><ol>
                <li><a href="Text/cap%201.xhtml"><span>I.</span> Do título</a></li>
                <li><a href="Text/cap2.xhtml#s1">II. Do livro</a></li>
              </ol></nav>
            </body></html>""",
        "OEBPS/Text/cap 1.xhtml" to "<html><body><p>Uma noite destas, vindo da cidade para o Engenho Novo…</p></body></html>",
        "OEBPS/Text/cap2.xhtml" to "<html><body><p>Agora que expliquei o título, passo a escrever o livro.</p></body></html>",
        "OEBPS/Text/notas.xhtml" to "<html><body>notas</body></html>",
        "OEBPS/Images/capa.jpg" to "fake",
        "OEBPS/style.css" to "p{}"
    )

    val docx = mapOf(
        "[Content_Types].xml" to "<Types/>",
        "word/styles.xml" to """<?xml version="1.0"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Ttulo1"><w:name w:val="heading 1"/></w:style>
              <w:style w:type="paragraph" w:styleId="Ttulo"><w:name w:val="Title"/></w:style>
            </w:styles>""",
        "word/document.xml" to """<?xml version="1.0"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:p><w:pPr><w:pStyle w:val="Ttulo"/></w:pPr><w:r><w:t>Relatório</w:t></w:r></w:p>
              <w:p><w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr><w:r><w:t>Introdução</w:t></w:r></w:p>
              <w:p>
                <w:r><w:t xml:space="preserve">Texto </w:t></w:r>
                <w:r><w:rPr><w:b/></w:rPr><w:t>negrito</w:t></w:r>
                <w:r><w:rPr><w:b w:val="0"/><w:i/></w:rPr><w:t xml:space="preserve"> e itálico</w:t></w:r>
                <w:r><w:tab/><w:t>fim</w:t></w:r>
              </w:p>
              <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>primeiro item</w:t></w:r></w:p>
              <w:p></w:p>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>célula</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
              <w:p><w:r><w:t>antes</w:t></w:r><w:r><w:pict><w:txbxContent><w:p><w:r><w:t>caixa</w:t></w:r></w:p></w:txbxContent></w:pict></w:r><w:r><w:t>depois</w:t></w:r></w:p>
            </w:body></w:document>"""
    )
}

@RunWith(RobolectricTestRunner::class)
class ZipFormatsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun epub3ReadsSpineTitlesAndCover() {
        val book = ZipFile(writeZip(folder.newFile("a.epub"), SampleBooks.epub3)).use { EpubPackage.read(it) }
        assertEquals("Dom Casmurro", book.title)
        // Sem o item inexistente e sem o capítulo fora da leitura linear.
        assertEquals(listOf("OEBPS/Text/cap 1.xhtml", "OEBPS/Text/cap2.xhtml"), book.chapters)
        assertEquals(listOf("I. Do título", "II. Do livro"), book.chapterTitles)
        assertEquals("OEBPS/Images/capa.jpg", book.coverPath)
    }

    @Test
    fun epub2UsesNcxAndMetaCover() {
        val entries = SampleBooks.epub3.toMutableMap()
        entries.remove("OEBPS/nav.xhtml")
        entries["OEBPS/content.opf"] = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title> </dc:title><meta name="cover" content="img"/></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="Text/cap%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="Images/capa.jpg" media-type="image/jpeg"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/></spine>
            </package>"""
        entries["OEBPS/toc.ncx"] = """<?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
              <navPoint id="n1"><navLabel><text>Capítulo Um</text></navLabel><content src="Text/cap%201.xhtml"/></navPoint>
            </navMap></ncx>"""
        val book = ZipFile(writeZip(folder.newFile("b.epub"), entries)).use { EpubPackage.read(it) }
        assertNull(book.title)
        assertEquals(listOf("Capítulo Um"), book.chapterTitles)
        assertEquals("OEBPS/Images/capa.jpg", book.coverPath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun epubWithoutContainerFails() {
        ZipFile(writeZip(folder.newFile("c.epub"), mapOf("a.txt" to "x"))).use { EpubPackage.read(it) }
    }

    @Test
    fun docxParagraphsHeadingsListsAndStyles() {
        val paragraphs = ZipFile(writeZip(folder.newFile("d.docx"), SampleBooks.docx)).use { DocxReader.read(it) }
        assertEquals(
            listOf("Relatório", "Introdução", "Texto negrito e itálico\tfim", "primeiro item", "célula", "antes\ncaixa\ndepois"),
            paragraphs.map { it.text }
        )
        assertEquals(DocxParagraph.Kind.TITLE, paragraphs[0].kind)
        assertEquals(DocxParagraph.Kind.HEADING1, paragraphs[1].kind)
        assertEquals(DocxParagraph.Kind.LIST_ITEM, paragraphs[3].kind)
        val styles = paragraphs[2].styles
        assertEquals(2, styles.size)
        assertEquals("negrito", paragraphs[2].text.substring(styles[0].start, styles[0].end))
        assertTrue(styles[0].bold && !styles[0].italic)
        assertTrue(!styles[1].bold && styles[1].italic)
    }

    @Test
    fun comicSourceCountsPages() {
        val zip = writeZip(folder.newFile("e.cbz"), mapOf("02.png" to "x", "01.png" to "x", "leia.txt" to "x"))
        val source = ComicSource(ZipFile(zip))
        assertEquals(2, source.pageCount)
        source.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun comicWithoutImagesFails() {
        ComicSource(ZipFile(writeZip(folder.newFile("f.cbz"), mapOf("leia.txt" to "x"))))
    }
}
