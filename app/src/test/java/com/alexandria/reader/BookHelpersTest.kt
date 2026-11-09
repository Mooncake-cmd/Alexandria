package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookHelpersTest {
    @Test
    fun naturalOrderSortsNumbersByValue() {
        val names = listOf("p10.jpg", "p2.jpg", "P1.jpg", "p02b.jpg", "capa.jpg")
        assertEquals(listOf("capa.jpg", "P1.jpg", "p2.jpg", "p02b.jpg", "p10.jpg"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun comicPagesIgnoreFoldersMetadataAndNonImages() {
        val entries = listOf(
            "cap1/", "cap1/010.png", "cap1/002.JPG", "cap1/Thumbs.db", "__MACOSX/cap1/._002.JPG",
            "cap1/.hidden.png", "info.txt", "cap2/001.webp"
        )
        assertEquals(listOf("cap1/002.JPG", "cap1/010.png", "cap2/001.webp"), ComicArchive.pageNames(entries))
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        assertEquals(listOf(0, 7), TextSearch.findAll("Ação e acao", "AÇÃO"))
        assertEquals(listOf(7), TextSearch.findAll("Era um coração", "coracao"))
        assertEquals(emptyList<Int>(), TextSearch.findAll("texto", "  "))
        assertEquals("acao".length, TextSearch.fold("Ação").length)
    }

    @Test
    fun zipPathsResolveRelativeAndEncodedHrefs() {
        assertEquals("OEBPS/Text/cap%201.xhtml".replace("%20", " "), ZipPaths.resolve("OEBPS/Text", "cap%201.xhtml"))
        assertEquals("OEBPS/Images/a.png", ZipPaths.resolve("OEBPS/Text", "../Images/a.png#x"))
        assertEquals("cap.xhtml", ZipPaths.resolve("", "./cap.xhtml"))
        assertEquals("a+b.xhtml", ZipPaths.resolve("", "a+b.xhtml"))
        assertEquals("OEBPS", ZipPaths.dirOf("OEBPS/content.opf"))
        assertEquals("", ZipPaths.dirOf("content.opf"))
    }

    @Test
    fun epubHtmlGetsPolicyFirstAndStyleLast() {
        val html = """<html><head><script>alert(1)</script><title>x</title></head><body><header>h</header></body></html>"""
        val out = EpubHtml.prepare(html, "body{}")
        val policy = out.indexOf("Content-Security-Policy")
        assertTrue(policy in 0 until out.indexOf("<script>"))
        assertTrue(out.indexOf("<style>body{}</style>") in out.indexOf("<title>") until out.indexOf("</head>"))
        assertTrue(out.contains("<header>h</header>"))
    }

    @Test
    fun epubHtmlWithoutHeadGetsOne() {
        val out = EpubHtml.prepare("<html xmlns=\"x\"><body>oi</body></html>", "p{}")
        assertTrue(out.startsWith("<html xmlns=\"x\"><head><meta http-equiv=\"Content-Security-Policy\""))
        assertTrue(out.endsWith("<body>oi</body></html>"))
    }

    @Test
    fun shelvesReuseExistingNames() {
        assertEquals("Ficção", Shelves.resolve("  ficção  ", listOf("Ficção", "Estudos")))
        assertEquals("Novos   livros".replace(Regex("\\s+"), " "), Shelves.resolve("Novos   livros", listOf("Ficção")))
        assertEquals(listOf("b", "C", "d"), listOf("d", "C", "b").sortedWith(Shelves.order))
    }

    @Test
    fun docxGroupsStartNewChunkAtHeadings() {
        fun p(text: String, kind: DocxParagraph.Kind = DocxParagraph.Kind.NORMAL) = DocxParagraph(text, kind, emptyList())
        val paragraphs = listOf(p("Título", DocxParagraph.Kind.TITLE), p("a".repeat(30)), p("b".repeat(30)), p("Seção", DocxParagraph.Kind.HEADING1), p("item", DocxParagraph.Kind.LIST_ITEM))
        val groups = DocxLayout.group(paragraphs, target = 50)
        assertEquals(listOf(listOf("Título", "a".repeat(30)), listOf("b".repeat(30)), listOf("Seção", "item")), groups.map { g -> g.map { it.text } })
        assertEquals("Seção\n\n•  item", DocxLayout.plain(groups.last()))
    }

    @Test
    fun epubRequestRouting() {
        assertEquals(EpubViewer.Route.PASS, EpubViewer.route("data:application/xhtml+xml;base64,AAAA"))
        assertEquals(EpubViewer.Route.PASS, EpubViewer.route("about:blank"))
        assertEquals(EpubViewer.Route.BOOK, EpubViewer.route("https://book.alexandria/OEBPS/a.png"))
        assertEquals(EpubViewer.Route.BLOCK, EpubViewer.route("https://exemplo.com/x.js"))
        assertEquals(EpubViewer.Route.BLOCK, EpubViewer.route("file:///data/data/x"))
        assertEquals(EpubViewer.Route.BLOCK, EpubViewer.route("content://x/y"))
    }
}
