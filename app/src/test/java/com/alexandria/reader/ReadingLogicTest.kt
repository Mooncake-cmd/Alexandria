package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLogicTest {
    @Test
    fun durations() {
        assertEquals("45 s", ReadingTime.format(45))
        assertEquals("12 min", ReadingTime.format(12 * 60 + 30))
        assertEquals("2 h 05 min", ReadingTime.format(2 * 3600 + 5 * 60 + 9))
        assertEquals("4:07", ReadingTime.clock(247))
        assertEquals("1:02:03", ReadingTime.clock(3723))
    }

    @Test
    fun calendarDaysRoundTrip() {
        assertEquals(0L, Days.epoch(1970, 1, 1))
        assertEquals(20_401L, Days.epoch(2025, 11, 9))
        for (day in listOf("2024-02-28", "2024-02-29", "2024-03-01", "1999-12-31", "2026-11-09")) {
            assertEquals(day, Days.format(Days.parse(day)!!))
        }
        assertEquals(1L, Days.parse("2024-03-01")!! - Days.parse("2024-02-29")!!)
    }

    @Test
    fun streakCountsConsecutiveDaysOfAtLeastFiveMinutes() {
        val days = mapOf(
            "2026-11-01" to 600L, "2026-11-02" to 900L, "2026-11-03" to 400L, // 3 seguidos
            "2026-11-05" to 120L, // pouco: não conta
            "2026-11-06" to 300L, "2026-11-07" to 1200L, "2026-11-08" to 500L
        )
        val today = Streak.summary(days, "2026-11-08")
        assertEquals(3, today.current)
        assertEquals(3, today.best)
        assertTrue(today.readToday)
        // Hoje ainda sem leitura: a sequência de ontem continua valendo.
        val tomorrow = Streak.summary(days, "2026-11-09")
        assertEquals(3, tomorrow.current)
        assertFalse(tomorrow.readToday)
        // Um dia inteiro sem ler zera.
        assertEquals(0, Streak.summary(days, "2026-11-10").current)
        assertEquals(Streak.Summary(0, 0, false, 0), Streak.summary(emptyMap(), "2026-11-10"))
    }

    @Test
    fun clockPausesWhenIdleAndSplitsDays() {
        val clock = ReadingClock(idleLimitMs = 60_000)
        clock.start(0)
        clock.tick(30_000, "2026-11-08")
        clock.interaction(30_000)
        clock.tick(80_000, "2026-11-08")
        // Sem tocar desde 30 s: conta só até 90 s, mesmo o tick chegando aos 200 s.
        clock.tick(200_000, "2026-11-08")
        assertEquals(90_000L, clock.sessionMs)
        assertTrue(clock.isIdle(200_000))
        clock.interaction(200_000)
        clock.tick(210_500, "2026-11-09") // virou o dia
        assertEquals(mapOf("2026-11-08" to 90L, "2026-11-09" to 10L), clock.drain())
        clock.stop(211_000, "2026-11-09")
        assertEquals(mapOf("2026-11-09" to 1L), clock.drain()) // 500 ms + 500 ms
        assertEquals(emptyMap<String, Long>(), clock.drain())
    }

    private fun term(id: Long, text: String) = GlossaryTerm(id, 1, text, "def", 0)

    @Test
    fun glossaryFindsWholeWordsIgnoringCaseAndAccents() {
        val terms = listOf(term(1, "Arrakis"), term(2, "Casa Atreides"), term(3, "Casa"), term(4, "Fremen"))
        val text = "Em ARRAKIS, a Casa Atreides chegou. A casa velha. Arrakisiano não conta. Os fremen e os Frêmen."
        val found = Glossary.find(text, terms).map { text.substring(it.start, it.end) to it.termId }
        assertEquals(
            listOf("ARRAKIS" to 1L, "Casa Atreides" to 2L, "casa" to 3L, "fremen" to 4L, "Frêmen" to 4L),
            found
        )
    }

    @Test
    fun glossaryMarksHtmlTextButNotTagsOrHead() {
        val html = """<html><head><title>Arrakis</title><style>.arrakis{}</style></head>""" +
            """<body><p class="arrakis">Arrakis &amp; <b>Arrakis</b></p><img alt="Arrakis"/></body></html>"""
        val out = Glossary.markHtml(html, listOf(term(7, "Arrakis")))
        assertEquals(2, Regex("data-term=\"7\"").findAll(out).count())
        assertTrue(out.contains("<title>Arrakis</title>"))
        assertTrue(out.contains("<p class=\"arrakis\">"))
        assertTrue(out.contains("alt=\"Arrakis\""))
        assertTrue(out.contains("<b><span class=\"alx-term\" data-term=\"7\">Arrakis</span></b>"))
        assertEquals(html, Glossary.markHtml(html, emptyList()))
    }
}
