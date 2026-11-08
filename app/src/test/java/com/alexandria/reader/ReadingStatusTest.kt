package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStatusTest {
    private fun item(format: String, position: Int, pageCount: Int = 0, openedAt: Long = 0) =
        LibraryItem(1, "x", "content://x", format, 0, position, pageCount, openedAt)

    @Test
    fun formatsWithoutReader() {
        assertEquals("Leitor em breve", ReadingStatus.describe(item("epub", 0)))
    }

    @Test
    fun text() {
        assertEquals("Toque para ler", ReadingStatus.describe(item("txt", 0)))
        assertEquals("42% lido", ReadingStatus.describe(item("txt", 4_250, openedAt = 1)))
        assertEquals("Concluído", ReadingStatus.describe(item("txt", 10_000, openedAt = 1)))
    }

    @Test
    fun pdfWithoutKnownPageCount() {
        assertEquals("Toque para ler", ReadingStatus.describe(item("pdf", 0)))
        assertEquals("Parou na página 8", ReadingStatus.describe(item("pdf", 7)))
    }

    @Test
    fun pdfWithPageCount() {
        assertEquals("Página 1 de 200 · 0%", ReadingStatus.describe(item("pdf", 0, 200, openedAt = 1)))
        assertEquals("Página 50 de 200 · 25%", ReadingStatus.describe(item("pdf", 49, 200, openedAt = 1)))
        assertEquals("Concluído · 200 páginas", ReadingStatus.describe(item("pdf", 199, 200, openedAt = 1)))
    }

    @Test
    fun singlePagePdfIsNeverReportedAsFinishedJustByOpening() {
        assertEquals("Página 1 de 1 · 100%", ReadingStatus.describe(item("pdf", 0, 1, openedAt = 1)))
    }
}
