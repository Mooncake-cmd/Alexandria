package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDecodingTest {
    @Test
    fun decodesUtf8AndStripsBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Ação".toByteArray(Charsets.UTF_8)
        assertEquals("Ação", TextDecoding.decode(bytes))
    }

    @Test
    fun fallsBackToWindows1252() {
        val bytes = "Coração e pão".toByteArray(Charset1252)
        assertEquals("Coração e pão", TextDecoding.decode(bytes))
    }

    @Test
    fun decodesUtf16WithBom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Olá".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Olá".toByteArray(Charsets.UTF_16BE)
        assertEquals("Olá", TextDecoding.decode(le))
        assertEquals("Olá", TextDecoding.decode(be))
    }

    @Test
    fun truncatedUtf8KeepsUtf8InsteadOfFallingBack() {
        val full = "abc é".toByteArray(Charsets.UTF_8)
        // Corta no meio do "é" (2 bytes em UTF-8).
        val cut = full.copyOf(full.size - 1)
        assertEquals("abc ", TextDecoding.decode(cut, truncated = true))
    }

    @Test
    fun normalizesLineEndings() {
        assertEquals("a\nb\nc\n", TextDecoding.decode("a\r\nb\rc\n".toByteArray()))
    }

    @Test
    fun emptyInput() {
        assertEquals("", TextDecoding.decode(ByteArray(0)))
    }

    private companion object {
        val Charset1252 = charset("windows-1252")
    }
}
