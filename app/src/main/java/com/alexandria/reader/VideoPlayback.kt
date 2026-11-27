package com.alexandria.reader

import android.media.MediaDataSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Regras do "continuar assistindo" (as mesmas do site). Tempos em segundos. */
object VideoProgress {
    /** Assistido: chegou perto do fim (os créditos não contam). */
    fun watched(position: Long, duration: Long): Boolean = duration > 0 && duration - position <= maxOf(30L, duration / 20)

    /** Onde retomar: o ponto salvo, se passou do começo e não chegou ao fim; senão, do começo. */
    fun resumeAt(position: Long, duration: Long): Long = if (position < 10 || (duration > 0 && watched(position, duration))) 0 else position

    fun clock(seconds: Long): String {
        val s = maxOf(0L, seconds)
        val h = s / 3600
        val m = s % 3600 / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }
}

/**
 * Leitura de um vídeo do grupo por blocos de 512 KB (o tamanho que o Telegram entrega por pedido):
 * os blocos lidos ficam numa memória limitada, pedidos do mesmo bloco esperam o mesmo download e os
 * próximos blocos já são pedidos antes. [fetch] busca um bloco (assíncrono); [read] espera por ele.
 */
class BlockCache(
    val size: Long,
    private val fetch: (index: Int, done: (Result<ByteArray>) -> Unit) -> Unit,
    private val maxBlocks: Int = 32,
    private val ahead: Int = 3
) {
    private val lock = Object()
    private val cache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > maxBlocks
    }
    private val pending = HashMap<Int, MutableList<(Result<ByteArray>) -> Unit>>()
    val blocks: Int get() = ((size + BLOCK - 1) / BLOCK).toInt()

    fun request(index: Int, done: (Result<ByteArray>) -> Unit) {
        val start: Boolean
        synchronized(lock) {
            cache[index]?.let { hit ->
                done(Result.success(hit))
                return
            }
            val waiting = pending[index]
            start = waiting == null
            if (waiting != null) waiting += done else pending[index] = mutableListOf(done)
        }
        if (!start) return
        fetch(index) { result ->
            val callbacks = synchronized(lock) {
                result.getOrNull()?.let { cache[index] = it }
                pending.remove(index).orEmpty()
            }
            callbacks.forEach { it(result) }
        }
    }

    /** Espera o bloco (no máximo [timeoutMs]). */
    fun block(index: Int, timeoutMs: Long = 60_000): ByteArray {
        val latch = CountDownLatch(1)
        var result: Result<ByteArray>? = null
        request(index) {
            result = it
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("O Telegram não respondeu a tempo.")
        return result!!.getOrElse { throw IOException(it.message, it) }
    }

    /** Copia bytes a partir de [position] (no máximo até o fim do bloco). -1 no fim do arquivo. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (length <= 0) return 0
        val index = (position / BLOCK).toInt()
        val data = block(index)
        for (i in index + 1..minOf(blocks - 1, index + ahead)) request(i) {}
        val from = (position - index.toLong() * BLOCK).toInt()
        val count = minOf(length, data.size - from)
        if (count <= 0) return -1
        System.arraycopy(data, from, buffer, offset, count)
        return count
    }

    companion object {
        const val BLOCK = 512 * 1024
    }
}

/** O vídeo do grupo como fonte do MediaPlayer do Android (lido por partes enquanto toca). */
class TelegramDataSource(private val cache: BlockCache) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = cache.read(position, buffer, offset, size)
    override fun getSize(): Long = cache.size
    override fun close() = Unit
}
