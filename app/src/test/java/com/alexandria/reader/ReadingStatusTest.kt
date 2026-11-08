package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStatusTest {
    private fun item(format: String, position: Int, pageCount: Int = 0, openedAt: Long = 0, finishedAt: Long = 0) =
        LibraryItem(1, "x", "content://x", format, 0, position, pageCount, openedAt, finishedAt)

    @Test
    fun formatsWithoutReader() {
        assertEquals("Leitor em breve", ReadingStatus.describe(item("mobi", 0)))
        assertEquals("Leitor em breve", ReadingStatus.describe(item("cbr", 0)))
    }

    @Test
    fun text() {
        assertEquals("Toque para ler", ReadingStatus.describe(item("txt", 0)))
        assertEquals("42% lido", ReadingStatus.describe(item("txt", 4_250, openedAt = 1)))
        assertEquals("Lido até o fim", ReadingStatus.describe(item("txt", 10_000, openedAt = 1)))
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
        assertEquals("Última página · 200 páginas", ReadingStatus.describe(item("pdf", 199, 200, openedAt = 1)))
    }

    @Test
    fun singlePagePdfIsNeverReportedAsFinishedJustByOpening() {
        assertEquals("Página 1 de 1 · 100%", ReadingStatus.describe(item("pdf", 0, 1, openedAt = 1)))
    }

    @Test
    fun newFormats() {
        assertEquals("Toque para ler · 12 capítulos", ReadingStatus.describe(item("epub", 0, 12)))
        assertEquals("30% lido", ReadingStatus.describe(item("epub", 3_000, 12, openedAt = 1)))
        assertEquals("Página 3 de 40 · 7%", ReadingStatus.describe(item("cbz", 2, 40, openedAt = 1)))
        assertEquals("55% lido", ReadingStatus.describe(item("docx", 5_500, openedAt = 1)))
        assertEquals("Concluído", ReadingStatus.describe(item("pdf", 3, 10, openedAt = 1, finishedAt = 5)))
    }

    @Test
    fun progressBar() {
        assertEquals(0f, ReadingStatus.progress(item("pdf", 0, 10)), 0.001f)
        assertEquals(0.5f, ReadingStatus.progress(item("cbz", 4, 10, openedAt = 1)), 0.001f)
        assertEquals(0.25f, ReadingStatus.progress(item("txt", 2_500)), 0.001f)
        assertEquals(1f, ReadingStatus.progress(item("txt", 100, finishedAt = 1)), 0.001f)
    }

    @Test
    fun noteLocations() {
        assertEquals("Pág. 5", NoteLocation.describe("pdf", 4, 100))
        assertEquals("Cap. 3 · 25%", NoteLocation.describe("epub", 2_500, 10))
        assertEquals("42%", NoteLocation.describe("txt", 4_200, 0))
    }
}
