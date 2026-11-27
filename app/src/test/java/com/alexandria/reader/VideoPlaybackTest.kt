package com.alexandria.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors

/** Leitura do vídeo por blocos (streaming) e regras do "continuar assistindo". Kotlin puro. */
class VideoPlaybackTest {
    private val block = BlockCache.BLOCK
    private fun file(size: Int) = ByteArray(size) { ((it * 7) % 251).toByte() }

    @Test
    fun readsAcrossBlocksInBackgroundAndStopsAtTheEnd() {
        val size = block * 3 + 1000
        val data = file(size)
        val asked = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val pool = Executors.newFixedThreadPool(2)
        val cache = BlockCache(size.toLong(), { index, done ->
            asked += index
            pool.execute { done(Result.success(data.copyOfRange(index * block, minOf(size, (index + 1) * block)))) }
        }, maxBlocks = 4, ahead = 1)
        val buffer = ByteArray(64)
        // Uma leitura vai no máximo até o fim do bloco (o player pede de novo a partir dali).
        assertEquals(5, cache.read((block - 5).toLong(), buffer, 0, 64))
        assertArrayEquals(data.copyOfRange(block - 5, block), buffer.copyOfRange(0, 5))
        assertEquals(64, cache.read(block.toLong(), buffer, 0, 64))
        assertArrayEquals(data.copyOfRange(block, block + 64), buffer)
        assertEquals(-1, cache.read(size.toLong(), buffer, 0, 64))
        val tail = ByteArray(1000)
        assertEquals(100, cache.read((size - 100).toLong(), tail, 0, 1000))
        assertArrayEquals(data.copyOfRange(size - 100, size), tail.copyOfRange(0, 100))
        // O mesmo bloco não é baixado duas vezes enquanto está guardado.
        cache.read(10, buffer, 0, 10)
        assertEquals(1, asked.count { it == 0 })
        pool.shutdown()
    }

    @Test
    fun failedBlockIsNotKeptAndTimeoutBecomesError() {
        var fail = true
        val cache = BlockCache(block.toLong(), { _, done -> if (fail) done(Result.failure(IllegalStateException("rede"))) else done(Result.success(file(block))) })
        val buffer = ByteArray(10)
        try {
            cache.read(0, buffer, 0, 10)
            throw AssertionError("devia falhar")
        } catch (e: IOException) {
            assertEquals("rede", e.message)
        }
        fail = false
        assertEquals(10, cache.read(0, buffer, 0, 10))

        val silent = BlockCache(block.toLong(), { _, _ -> })
        try {
            silent.block(0, timeoutMs = 50)
            throw AssertionError("devia falhar")
        } catch (e: IOException) {
            assertEquals("O Telegram não respondeu a tempo.", e.message)
        }
    }

    @Test
    fun resumeRulesMatchTheSite() {
        assertEquals(0, VideoProgress.resumeAt(5, 3600))
        assertEquals(2050, VideoProgress.resumeAt(2050, 3600))
        assertEquals(0, VideoProgress.resumeAt(3590, 3600))
        assertTrue(VideoProgress.watched(7200 - 300, 7200))
        assertFalse(VideoProgress.watched(3000, 3600))
        assertEquals("34:10", VideoProgress.clock(2050))
        assertEquals("3:01:05", VideoProgress.clock(3 * 3600 + 65))
    }
}
