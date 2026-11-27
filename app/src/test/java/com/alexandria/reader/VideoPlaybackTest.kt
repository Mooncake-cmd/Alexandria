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

    private fun v(id: Int, name: String, date: Long, topic: Int = 0) = CloudVideo(id, name, 10, 600, "video/mp4", date, "", topic, name)

    @Test
    fun lessonOrderAndPlaylistsMatchTheSite() {
        assertEquals(listOf(3, 2, 1), Playlists.lessonOrder(listOf(v(1, "Aula 10.mp4", 1), v(2, "Aula 2.mp4", 2), v(3, "aula 01 - Intro.mp4", 3))).map { it.messageId })
        // O 4 de .mp4 não conta: sem número no nome, vale a ordem de envio.
        assertEquals(listOf(2, 3, 1), Playlists.lessonOrder(listOf(v(1, "Revisão.mp4", 30), v(2, "Introdução.mp4", 10), v(3, "Aula 2.mp4", 20))).map { it.messageId })
        val videos = listOf(v(1, "Filme.mp4", 1), v(2, "Aula 1.mp4", 2, 50), v(3, "Ep 1.mp4", 3, 20), v(4, "Geral.mp4", 4, 1), v(5, "X.mp4", 5, 99), v(6, "Aula 0.mp4", 6, 50))
        val (lists, loose) = Playlists.split(videos, listOf(Playlists.Topic(50, "Python"), Playlists.Topic(1, "General"), Playlists.Topic(20, "Série"), Playlists.Topic(70, "Vazio")))
        assertEquals(listOf("Série" to listOf(3), "Python" to listOf(6, 2), "Vazio" to emptyList<Int>()), lists.map { it.topic.title to it.videos.map { x -> x.messageId } })
        assertEquals(listOf(1, 4, 5), loose.map { it.messageId })
        val course = listOf(v(1, "Aula 1.mp4", 1), v(2, "Aula 2.mp4", 2), v(3, "Aula 3.mp4", 3))
        assertEquals(2, Playlists.next(course, 1)?.messageId)
        assertEquals(null, Playlists.next(course, 3))
        val stopped = mapOf(1 to Pair(590L, 600L), 2 to Pair(120L, 600L))
        assertEquals(Playlists.Progress(1, 3, course[1], true), Playlists.progress(course) { stopped[it.messageId] })
        assertEquals(Playlists.Progress(0, 3, course[0], false), Playlists.progress(course) { null })
    }
}
