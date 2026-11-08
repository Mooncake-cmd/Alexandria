package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    @Test
    fun detectsByExtensionIgnoringCase() {
        assertEquals("pdf", Formats.detect("Livro.PDF", null))
        assertEquals("txt", Formats.detect("notas.txt", "application/octet-stream"))
        assertEquals("epub", Formats.detect("a.b.c.epub", null))
    }

    @Test
    fun extensionWinsOverMime() {
        assertEquals("cbz", Formats.detect("quadrinho.cbz", "application/zip"))
    }

    @Test
    fun fallsBackToMimeWhenExtensionIsMissingOrUnknown() {
        assertEquals("pdf", Formats.detect("documento", "application/pdf"))
        assertEquals("txt", Formats.detect("arquivo.log", "text/plain"))
        assertEquals("djvu", Formats.detect("scan", "image/x-djvu"))
    }

    @Test
    fun returnsNullForUnknownFiles() {
        assertNull(Formats.detect("foto.jpg", "image/jpeg"))
        assertNull(Formats.detect("sem-extensao", null))
        assertNull(Formats.detect("", null))
    }

    @Test
    fun readableFormatsAreSupported() {
        assertTrue(Formats.supported.containsAll(Formats.readable))
    }
}
