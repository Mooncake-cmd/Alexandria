package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun emptyTextHasNoChunks() {
        assertEquals(emptyList<String>(), TextChunker.chunk(""))
    }

    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("uma linha\noutra"), TextChunker.chunk("uma linha\noutra"))
    }

    @Test
    fun joiningChunksRebuildsTheText() {
        val text = (1..500).joinToString("\n") { i -> if (i % 7 == 0) "" else "Linha $i com algum texto." }
        val chunks = TextChunker.chunk(text, target = 200, max = 400)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString("\n"))
        assertTrue(chunks.all { it.length <= 200 })
    }

    @Test
    fun blankLinesArePreserved() {
        val text = "\n\nfim\n"
        assertEquals(text, TextChunker.chunk(text, target = 2, max = 4).joinToString("\n"))
    }

    @Test
    fun longLinesAreSplitNearSpaces() {
        val line = (1..300).joinToString(" ") { "palavra" }
        val chunks = TextChunker.chunk("antes\n$line\ndepois", target = 50, max = 100)
        assertEquals("antes", chunks.first())
        assertEquals("depois", chunks.last())
        val middle = chunks.subList(1, chunks.size - 1)
        assertTrue(middle.all { it.length <= 100 })
        assertEquals(line, middle.joinToString(""))
        assertTrue(middle.dropLast(1).all { it.endsWith(" ") })
    }

    @Test
    fun longLineWithoutSpacesIsCutAtTheLimit() {
        val line = "x".repeat(250)
        assertEquals(listOf(100, 100, 50), TextChunker.chunk(line, target = 50, max = 100).map { it.length })
    }
}
