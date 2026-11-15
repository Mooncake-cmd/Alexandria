package com.alexandria.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class LinePagerTest {
    /** Linhas de 20 px coladas, a partir de [start]. */
    private fun lines(start: Float, count: Int, height: Float = 20f) =
        (0 until count).map { LineSpan(start + it * height, start + (it + 1) * height) }

    @Test
    fun nextPageStartsAtTheCutLine() {
        // Janela 10..105: a linha 90..110 está cortada.
        val page = lines(10f, 8)
        assertEquals(LineSpan(90f, 110f), LinePager.cutAtBottom(page, 10f, 105f))
        assertEquals(90f, LinePager.maskStart(page, 10f, 105f), 0.01f)
        assertEquals(80f, LinePager.nextDelta(page, 10f, 105f), 0.01f)
    }

    @Test
    fun nothingCutMeansNoCoverAndTheNextLineOpensThePage() {
        val page = lines(10f, 8) // 10..170, janela até 110: a linha 90..110 cabe inteira
        assertEquals(null, LinePager.cutAtBottom(page, 10f, 110f))
        assertEquals(110f, LinePager.maskStart(page, 10f, 110f), 0.01f)
        assertEquals(100f, LinePager.nextDelta(page, 10f, 110f), 0.01f)
    }

    @Test
    fun hugeBlockIsSplitByWholeWindows() {
        val image = listOf(LineSpan(10f, 900f))
        assertEquals(null, LinePager.cutAtBottom(image, 10f, 110f)) // começa no topo: não dá para adiar
        assertEquals(100f, LinePager.nextDelta(image, 10f, 110f), 0.01f)
        // Imagem que começa perto do fim da página vai inteira para a próxima.
        val late = lines(10f, 3) + LineSpan(70f, 400f)
        assertEquals(60f, LinePager.nextDelta(late, 10f, 110f), 0.01f)
    }

    @Test
    fun goingBackAndAligning() {
        // Depois de voltar uma janela, a linha 5..25 ficou cortada no topo (janela começa em 10).
        val back = lines(5f, 6)
        assertEquals(15f, LinePager.afterBackDelta(back, 10f, 110f), 0.01f)
        assertEquals(-5f, LinePager.alignDelta(back, 10f, 110f), 0.01f)
        // Linha já no topo: nada a fazer.
        assertEquals(0f, LinePager.alignDelta(lines(10f, 5), 10f, 110f), 0.01f)
    }
}
