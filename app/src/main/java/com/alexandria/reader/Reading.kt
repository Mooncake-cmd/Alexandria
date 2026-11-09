package com.alexandria.reader

/** Formatação de tempo de leitura. Kotlin puro. */
object ReadingTime {
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
 * Relógio de leitura de uma sessão. Conta o tempo com o livro aberto e em primeiro plano, mas para
 * de contar quando passa [idleLimitMs] sem nenhum toque ou troca de página (celular largado aberto).
 * O tempo é separado por dia, para a virada da meia-noite contar no dia certo. Kotlin puro: o
 * horário é sempre passado de fora.
 */
class ReadingClock(private val idleLimitMs: Long = 5 * 60_000L) {
    private var lastTick: Long? = null
    private var lastInteraction = 0L
    private val pending = LinkedHashMap<String, Long>() // milissegundos ainda não salvos, por dia
    /** Milissegundos contados nesta sessão. */
    var sessionMs = 0L
        private set

    fun start(now: Long) {
        lastTick = now
        lastInteraction = now
    }

    fun interaction(now: Long) {
        lastInteraction = now
    }

    fun isIdle(now: Long) = now - lastInteraction > idleLimitMs

    fun tick(now: Long, day: String) {
        val last = lastTick ?: return
        lastTick = now
        if (now <= last) return
        val activeUntil = lastInteraction + idleLimitMs
        val counted = (minOf(now, activeUntil) - last).coerceAtLeast(0)
        if (counted == 0L) return
        sessionMs += counted
        pending[day] = (pending[day] ?: 0) + counted
    }

    fun stop(now: Long, day: String) {
        tick(now, day)
        lastTick = null
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
