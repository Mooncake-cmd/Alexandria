package com.alexandria.reader

import android.media.MediaDataSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Playlists pelos tópicos do grupo (as mesmas regras do site): cada tópico é um curso (aba Cursos)
 * ou uma coleção (aba Filmes); os vídeos fora de tópicos ficam soltos.
 */
object Playlists {
    data class Topic(val id: Int, val title: String)
    data class Playlist(val topic: Topic, val videos: List<CloudVideo>)

    /** O tópico "Geral" de todo grupo com tópicos. */
    const val GENERAL = 1

    /** Tem número no nome (sem contar a extensão: o 4 de .mp4 não vale). */
    private fun hasNumber(name: String) = Regex("\\d").containsMatchIn(name.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), ""))

    /** Compara nomes com os números pelo valor ("Aula 2" antes de "Aula 10"), sem diferenciar maiúsculas. */
    private val collator = java.text.Collator.getInstance(java.util.Locale("pt", "BR")).apply { strength = java.text.Collator.PRIMARY }

    private val natural = Comparator<String> { a, b ->
        val pa = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val pb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
            else collator.compare(x, y)
            if (c != 0) return@Comparator c
        }
        pa.size.compareTo(pb.size)
    }

    /** Aulas numeradas seguem o número; sem número em todos, a ordem de envio (o mais antigo primeiro). */
    fun lessonOrder(videos: List<CloudVideo>): List<CloudVideo> {
        val byName = videos.isNotEmpty() && videos.all { hasNumber(it.name) }
        return if (byName) videos.sortedWith(compareBy(natural) { v: CloudVideo -> v.name }.thenBy { it.date })
        else videos.sortedWith(compareBy<CloudVideo> { it.date }.thenBy { it.messageId })
    }

    /** Vídeos por tópico (na ordem de criação dos tópicos) e os soltos. */
    fun split(videos: List<CloudVideo>, topics: List<Topic>): Pair<List<Playlist>, List<CloudVideo>> {
        val known = topics.filter { it.id != GENERAL }.associateBy { it.id }
        val (inTopic, loose) = videos.partition { it.topic != 0 && it.topic in known }
        val groups = inTopic.groupBy { it.topic }
        return Pair(known.values.sortedBy { it.id }.map { Playlist(it, lessonOrder(groups[it.id].orEmpty())) }, loose)
    }

    fun next(list: List<CloudVideo>, messageId: Int): CloudVideo? {
        val i = list.indexOfFirst { it.messageId == messageId }
        return if (i >= 0 && i + 1 < list.size) list[i + 1] else null
    }

    /** Aulas assistidas, a próxima a assistir e se o curso já foi começado. */
    data class Progress(val done: Int, val total: Int, val next: CloudVideo?, val started: Boolean)

    fun progress(list: List<CloudVideo>, stoppedAt: (CloudVideo) -> Pair<Long, Long>?): Progress {
        var done = 0
        var next: CloudVideo? = null
        var started = false
        for (v in list) {
            val p = stoppedAt(v)
            if (p != null) started = true
            if (p != null && VideoProgress.watched(p.first, if (p.second > 0) p.second else v.duration.toLong())) done++ else if (next == null) next = v
        }
        return Progress(done, list.size, next, started)
    }
}

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
