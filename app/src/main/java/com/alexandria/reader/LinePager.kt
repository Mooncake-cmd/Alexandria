package com.alexandria.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Uma linha (ou um bloco inteiro, como uma imagem) na tela: de [top] a [bottom], em pixels. */
data class LineSpan(val top: Float, val bottom: Float)

/**
 * Regras do modo páginas dos textos (TXT, DOCX, EPUB): a página termina na última linha que cabe
 * inteira e a próxima começa exatamente na linha seguinte, sem repetir nem cortar. Kotlin puro,
 * coberto por testes. A "janela" é a faixa da tela onde o texto aparece ([winTop] a [winBottom]).
 */
object LinePager {
    private const val EPS = 0.5f

    /** Linha cortada pela borda de baixo da janela, se houver. */
    fun cutAtBottom(lines: List<LineSpan>, winTop: Float, winBottom: Float): LineSpan? =
        lines.firstOrNull { it.top < winBottom - EPS && it.bottom > winBottom + EPS && it.top > winTop + EPS }

    /** Onde começa a cobertura de baixo: no topo da linha cortada (ou na borda, se nada foi cortado). */
    fun maskStart(lines: List<LineSpan>, winTop: Float, winBottom: Float): Float =
        cutAtBottom(lines, winTop, winBottom)?.top ?: winBottom

    /**
     * Quanto rolar para a próxima página: a linha cortada (ou a primeira abaixo da janela) vai para
     * o topo. Um bloco maior que a tela (imagem enorme) é dividido: rola a janela inteira.
     */
    fun nextDelta(lines: List<LineSpan>, winTop: Float, winBottom: Float): Float {
        val window = winBottom - winTop
        val target = cutAtBottom(lines, winTop, winBottom)?.top
            ?: lines.firstOrNull { it.top >= winBottom - EPS }?.top
            ?: return window
        val delta = target - winTop
        return if (delta < window * 0.25f) window else delta
    }

    /**
     * Depois de voltar uma janela inteira: se uma linha ficou cortada no topo, a página começa na
     * linha seguinte (assim termina exatamente antes da que estava no topo).
     */
    fun afterBackDelta(lines: List<LineSpan>, winTop: Float, winBottom: Float): Float {
        val window = winBottom - winTop
        val straddling = lines.firstOrNull { it.top < winTop - EPS && it.bottom > winTop + EPS } ?: return 0f
        val delta = straddling.bottom - winTop
        return if (delta > window * 0.75f) 0f else delta
    }

    /** Ao abrir ou pular para um ponto: a linha cortada no topo desce e aparece inteira. */
    fun alignDelta(lines: List<LineSpan>, winTop: Float, winBottom: Float): Float {
        val window = winBottom - winTop
        val straddling = lines.firstOrNull { it.top < winTop - EPS && it.bottom > winTop + EPS } ?: return 0f
        val delta = straddling.top - winTop
        return if (-delta > window * 0.5f) 0f else delta
    }
}

/**
 * Cobre, com a cor da página, o que fica fora da janela de leitura: a faixa de cima e, embaixo,
 * a linha que não coube inteira. Não recebe toques.
 */
@android.annotation.SuppressLint("ViewConstructor")
class PageMask(context: Context) : View(context) {
    private val paint = Paint()
    private var top = 0f
    private var bottom = Float.MAX_VALUE

    /** Onde começa a cobertura de baixo (para testes e acessibilidade). */
    val coverBottom: Float get() = bottom

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    /** Cobre de 0 a [coverTop] e de [coverBottom] até o fim. */
    fun cover(coverTop: Float, coverBottom: Float) {
        if (coverTop == top && coverBottom == bottom) return
        top = coverTop
        bottom = coverBottom
        invalidate()
    }

    fun clear() = cover(0f, Float.MAX_VALUE)

    override fun onDraw(canvas: Canvas) {
        if (top > 0f) canvas.drawRect(0f, 0f, width.toFloat(), top, paint)
        if (bottom < height) canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), paint)
    }
}
