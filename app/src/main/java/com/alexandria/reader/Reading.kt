package com.alexandria.reader

/** Formatação de tempo de leitura. Kotlin puro. */
object ReadingTime {
    /**
     * Divide [amount] segundos entre os dias de [available] (segundos disponíveis em cada dia), na
     * proporção de cada um, sem passar do que cada dia tem. O que sobra do arredondamento sai dos
     * dias com mais tempo. Se não houver tempo suficiente, tira tudo o que há.
     */
    fun spreadRemoval(available: Map<String, Long>, amount: Long): Map<String, Long> {
        val days = available.filterValues { it > 0 }
        val total = days.values.sum()
        if (amount <= 0 || total <= 0) return emptyMap()
        if (amount >= total) return days
        val result = HashMap<String, Long>()
        for ((day, seconds) in days) result[day] = seconds * amount / total
        var left = amount - result.values.sum()
        for ((day, seconds) in days.entries.sortedByDescending { it.value }) {
            if (left <= 0) break
            val room = seconds - result.getValue(day)
            val take = minOf(room, left)
            result[day] = result.getValue(day) + take
            left -= take
        }
        return result.filterValues { it > 0 }
    }

    /** "45 s", "12 min", "2 h 05 min". */
    fun format(seconds: Long): String = when {
        seconds < 60 -> "${seconds.coerceAtLeast(0)} s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${String.format(java.util.Locale.ROOT, "%02d", (seconds % 3600) / 60)} min"
    }

    /** Cronômetro: "4:07", "12:35", "1:02:03". */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
    }
}

/**
 * Datas como "aaaa-mm-dd" e contagem em dias corridos (dia 0 = 1970-01-01), sem java.time (que só
 * existe a partir do Android 8). Algoritmo de dias civis de Howard Hinnant.
 */
object Days {
    fun epoch(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    fun parse(text: String): Long? {
        val parts = text.split('-')
        if (parts.size != 3) return null
        val (y, m, d) = parts.map { it.toIntOrNull() ?: return null }
        return epoch(y, m, d)
    }

    fun format(epochDay: Long): String {
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = mp + (if (mp < 10) 3 else -9)
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m, d)
    }

    /** Hoje, no fuso do aparelho. */
    fun today(now: Long = System.currentTimeMillis()): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }
}

/** Sequência de dias de leitura. Kotlin puro. */
object Streak {
    /** Um dia conta para a sequência a partir de 5 minutos de leitura. */
    const val MIN_SECONDS = 300L

    data class Summary(val current: Int, val best: Int, val readToday: Boolean, val todaySeconds: Long)

    fun summary(days: Map<String, Long>, today: String): Summary {
        val read = days.filterValues { it >= MIN_SECONDS }.keys.mapNotNull { Days.parse(it) }.toSortedSet()
        val todayEpoch = Days.parse(today) ?: return Summary(0, 0, false, 0)
        val readToday = todayEpoch in read
        // Sem leitura hoje (ainda), a sequência continua viva se ontem contou.
        var cursor = if (readToday) todayEpoch else todayEpoch - 1
        var current = 0
        while (cursor in read) {
            current++
            cursor--
        }
        var best = 0
        var run = 0
        var previous: Long? = null
        for (day in read) {
            run = if (previous != null && day == previous + 1) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return Summary(current, best, readToday, days[today] ?: 0)
    }
}

/**
 * Relógio de leitura de uma sessão. Conta o tempo com o livro aberto e em primeiro plano, separado
 * por dia (a virada da meia-noite conta no dia certo). Kotlin puro: o horário vem sempre de fora.
 *
 * Para não contar o tempo longe do livro:
 * - **Ritmo de leitura**: aprende quanto tempo o leitor costuma ficar numa página ([pageTurn]) e
 *   pausa depois de 3 vezes esse tempo sem toque (entre [minIdleMs] e [idleLimitMs]).
 * - **Desconto da espera**: depois do último toque, só o tempo normal de uma página ([graceMs])
 *   conta direto. O que passa disso fica "provisório": vale se o leitor tocar de novo antes da pausa
 *   (estava lendo uma página longa) e é descartado se a pausa chegar ou o livro for fechado.
 * - **Pausa manual** ([pause]/[resume]).
 * Sem ritmo conhecido (menos de 3 viradas), pausa em [idleLimitMs] e conta tudo até lá.
 */
class ReadingClock(
    private val idleLimitMs: Long = 5 * 60_000L,
    private val minIdleMs: Long = 60_000L,
    pace: List<Long> = emptyList()
) {
    private var lastTick: Long? = null
    private var lastInteraction = 0L
    private val pending = LinkedHashMap<String, Long>() // milissegundos ainda não salvos, por dia
    private val provisional = LinkedHashMap<String, Long>()
    private val turns = ArrayDeque(pace.filter { it in MIN_TURN_MS..MAX_TURN_MS }.takeLast(SAMPLES))
    private var lastTurn: Long? = null
    /** Milissegundos contados nesta sessão (sem o tempo provisório). */
    var sessionMs = 0L
        private set
    /** Tempo provisório: depois do tempo normal de uma página, à espera de um novo toque. */
    var provisionalMs = 0L
        private set
    /** O leitor pausou o cronômetro (botão ⏱). */
    var paused = false
        private set

    /** O que o cronômetro mostra: o tempo contado e o provisório. */
    val shownMs: Long get() = sessionMs + provisionalMs

    /** Últimos intervalos entre viradas de página (para guardar e continuar no próximo uso). */
    fun pace(): List<Long> = turns.toList()

    /** Tempo típico numa página: a mediana das últimas viradas, ou null se ainda não há o bastante. */
    fun typicalPageMs(): Long? {
        if (turns.size < 3) return null
        val sorted = turns.sorted()
        return sorted[sorted.size / 2]
    }

    fun idleLimit(): Long = typicalPageMs()?.let { (it * 3).coerceIn(minIdleMs, idleLimitMs) } ?: idleLimitMs

    /** Quanto tempo depois do último toque conta direto: o tempo normal de uma página. */
    fun graceMs(): Long = typicalPageMs()?.coerceIn(MIN_GRACE_MS, idleLimit()) ?: idleLimit()

    fun start(now: Long) {
        lastTick = now
        lastInteraction = now
    }

    fun interaction(now: Long) {
        if (paused) return
        // Tocou antes da pausa: o tempo provisório era leitura. Depois da pausa, já foi descartado.
        if (now - lastInteraction <= idleLimit()) commitProvisional() else dropProvisional()
        lastInteraction = now
    }

    /** Uma página (ou uma tela de texto) foi virada: aprende o ritmo de leitura. */
    fun pageTurn(now: Long) {
        interaction(now)
        val previous = lastTurn
        lastTurn = now
        if (previous == null || paused) return
        val interval = now - previous
        if (interval in MIN_TURN_MS..MAX_TURN_MS) {
            turns.addLast(interval)
            while (turns.size > SAMPLES) turns.removeFirst()
        }
    }

    fun isIdle(now: Long) = paused || now - lastInteraction > idleLimit()

    fun tick(now: Long, day: String) {
        val last = lastTick ?: return
        lastTick = now
        if (now <= last || paused) return
        val activeUntil = lastInteraction + idleLimit()
        val directUntil = lastInteraction + graceMs()
        val end = minOf(now, activeUntil)
        val direct = (minOf(end, directUntil) - last).coerceAtLeast(0)
        val extra = (end - maxOf(last, directUntil)).coerceAtLeast(0)
        if (direct > 0) {
            sessionMs += direct
            pending[day] = (pending[day] ?: 0) + direct
        }
        if (extra > 0) {
            provisionalMs += extra
            provisional[day] = (provisional[day] ?: 0) + extra
        }
        if (now >= activeUntil) dropProvisional()
    }

    /** Livro fechado ou tela apagada: o tempo provisório não conta (o leitor não voltou a tocar). */
    fun stop(now: Long, day: String) {
        tick(now, day)
        dropProvisional()
        lastTick = null
    }

    /** Pausa manual: o toque no botão é um sinal de presença, então o tempo até aqui conta. */
    fun pause(now: Long, day: String) {
        if (paused) return
        interaction(now)
        tick(now, day)
        paused = true
    }

    fun resume(now: Long) {
        if (!paused) return
        paused = false
        lastTick = now
        lastInteraction = now
        lastTurn = null
    }

    private fun commitProvisional() {
        for ((day, ms) in provisional) pending[day] = (pending[day] ?: 0) + ms
        sessionMs += provisionalMs
        dropProvisional()
    }

    private fun dropProvisional() {
        provisional.clear()
        provisionalMs = 0
    }

    /** Segundos inteiros prontos para salvar, por dia (o resto fracionário fica para a próxima vez). */
    fun drain(): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        for ((day, ms) in pending) {
            val seconds = ms / 1000
            if (seconds > 0) result[day] = seconds
            pending[day] = ms - seconds * 1000
        }
        pending.entries.removeAll { it.value == 0L }
        return result
    }

    companion object {
        const val SAMPLES = 20
        const val MIN_TURN_MS = 3_000L
        const val MAX_TURN_MS = 10 * 60_000L
        const val MIN_GRACE_MS = 20_000L
    }
}

/** Glossário: encontra os termos definidos pelo leitor no texto. Kotlin puro. */
object Glossary {
    data class Match(val start: Int, val end: Int, val termId: Long)

    /**
     * Ocorrências dos [terms] em [text]: palavras inteiras, sem diferenciar maiúsculas nem acentos
     * ("arrakis" acha "Arrakis"). Termos mais longos têm preferência ("Casa Atreides" antes de "Casa").
     */
    fun find(text: CharSequence, terms: List<GlossaryTerm>): List<Match> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val haystack = TextSearch.fold(text)
        val taken = BooleanArray(haystack.length)
        val result = mutableListOf<Match>()
        for (term in terms.sortedByDescending { it.term.length }) {
            val needle = TextSearch.fold(term.term.trim())
            if (needle.isEmpty()) continue
            var from = haystack.indexOf(needle)
            while (from >= 0) {
                val end = from + needle.length
                val wordStart = from == 0 || !haystack[from - 1].isLetterOrDigit()
                val wordEnd = end == haystack.length || !haystack[end].isLetterOrDigit()
                if (wordStart && wordEnd && (from until end).none { taken[it] }) {
                    for (i in from until end) taken[i] = true
                    result += Match(from, end, term.id)
                }
                from = haystack.indexOf(needle, from + 1)
            }
        }
        return result.sortedBy { it.start }
    }

    private val skipTags = setOf("script", "style", "title", "head")

    /**
     * Marca os termos no HTML de um capítulo com <span class="alx-term" data-term="id">. Só mexe no
     * texto entre as tags, nunca dentro delas, e ignora <head>, <script>, <style> e <title>.
     */
    fun markHtml(html: String, terms: List<GlossaryTerm>): String {
        if (terms.isEmpty()) return html
        val out = StringBuilder(html.length + 256)
        val tag = Regex("<[^>]*>")
        var skipping: String? = null
        var position = 0
        for (match in tag.findAll(html)) {
            val textPart = html.substring(position, match.range.first)
            out.append(if (skipping == null) markText(textPart, terms) else textPart)
            val raw = match.value
            out.append(raw)
            val name = Regex("^</?\\s*([a-zA-Z0-9:-]+)").find(raw)?.groupValues?.get(1)?.lowercase()?.substringAfter(':')
            if (name != null) {
                val closing = raw.startsWith("</")
                if (skipping == null && !closing && name in skipTags && !raw.endsWith("/>")) skipping = name
                else if (skipping != null && closing && name == skipping) skipping = null
            }
            position = match.range.last + 1
        }
        val tail = html.substring(position)
        out.append(if (skipping == null) markText(tail, terms) else tail)
        return out.toString()
    }

    private fun markText(text: String, terms: List<GlossaryTerm>): String {
        if (text.isBlank()) return text
        val matches = find(text, terms)
        if (matches.isEmpty()) return text
        val out = StringBuilder(text.length + matches.size * 48)
        var last = 0
        for (m in matches) {
            out.append(text, last, m.start)
            out.append("<span class=\"alx-term\" data-term=\"").append(m.termId).append("\">")
            out.append(text, m.start, m.end)
            out.append("</span>")
            last = m.end
        }
        out.append(text, last, text.length)
        return out.toString()
    }
}
