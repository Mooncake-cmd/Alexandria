package com.alexandria.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Régua de leitura: uma faixa clara sobre a linha que está sendo lida, com o resto da área
 * levemente escurecido ou, no modo foco, preto. A alça à direita fica escondida e aparece por
 * alguns segundos ao tocar na faixa ([revealHandle]); arrasta-se por ela. Fora da alça, os
 * toques passam para o livro normalmente (rolar, virar página, selecionar texto).
 */
@android.annotation.SuppressLint("ViewConstructor") // criada só pelo código, nunca por layout XML
class ReadingRuler(context: Context, initialFraction: Float, private val onMoved: (Float) -> Unit) : View(context) {
    /** Posição do centro da faixa, como fração da altura (0 = topo, 1 = fim). */
    var fraction = initialFraction.coerceIn(0f, 1f)
        private set

    private var bandHeight = context.dp(46).toFloat()
    private var outside = Comfort.RulerOutside.ESCURECER
    /** Alça visível agora (aparece ao tocar na faixa e some sozinha). */
    var handleShown = false
        private set
    private val hideHandle = Runnable {
        if (!dragging && !alwaysShowHandle()) {
            handleShown = false
            invalidate()
        }
    }
    private val black = Paint().apply { color = Color.BLACK }
    private val handleWidth = context.dp(36).toFloat()
    private val dim = Paint().apply { color = Color.argb(70, 16, 26, 42) }
    private val band = Paint().apply { color = Color.argb(46, 198, 154, 82) }
    private val edge = Paint().apply {
        color = Color.argb(200, 183, 137, 66)
        strokeWidth = context.dp(2).toFloat()
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 183, 137, 66) }
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = context.dp(2).toFloat()
    }
    private val rect = RectF()
    private var dragging = false
    private var grabOffset = 0f

    init {
        contentDescription = "Régua de leitura"
        applyStyle(ComfortStore.load(context))
    }

    /** Espessura, cor, opacidade e escurecimento escolhidos no painel "Conforto visual". */
    fun applyStyle(comfort: Comfort) {
        bandHeight = context.dp(comfort.rulerHeight.dp).toFloat()
        band.color = Comfort.rulerColor(comfort)
        edge.color = Comfort.rulerEdgeColor(comfort)
        handle.color = Comfort.rulerEdgeColor(comfort)
        outside = comfort.rulerOutside
        invalidate()
    }

    /** Com o leitor de tela (exploração por toque), a alça fica sempre à vista. */
    private fun alwaysShowHandle(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        return manager?.isTouchExplorationEnabled == true
    }

    /** Mostra a alça por alguns segundos (toque na faixa, régua recém-ligada). */
    fun revealHandle() {
        handleShown = true
        removeCallbacks(hideHandle)
        postDelayed(hideHandle, HANDLE_SECONDS * 1000L)
        invalidate()
    }

    /** true se o toque (em coordenadas desta view) caiu sobre a faixa da régua. */
    fun hitsBand(y: Float): Boolean {
        val top = bandTop()
        val slop = context.dp(6)
        return y >= top - slop && y <= top + bandHeight + slop
    }

    private fun bandTop(): Float = (fraction * height - bandHeight / 2).coerceIn(0f, (height - bandHeight).coerceAtLeast(0f))

    /** true se o toque (em coordenadas desta view) caiu na alça, e ela está à vista. */
    fun hitsHandle(x: Float, y: Float): Boolean {
        if (!handleShown && !alwaysShowHandle()) return false
        val top = bandTop()
        val slop = context.dp(12)
        return x >= width - handleWidth - slop && y >= top - slop && y <= top + bandHeight + slop
    }

    override fun onDraw(canvas: Canvas) {
        val top = bandTop()
        val bottom = top + bandHeight
        val cover = when (outside) {
            Comfort.RulerOutside.NORMAL -> null
            Comfort.RulerOutside.ESCURECER -> dim
            Comfort.RulerOutside.FOCO -> black
        }
        if (cover != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), top, cover)
            canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), cover)
        }
        canvas.drawRect(0f, top, width.toFloat(), bottom, band)
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, edge)
        if (!handleShown && !alwaysShowHandle()) return
        // Alça: pílula dourada com três riscos.
        rect.set(width - handleWidth, top + context.dp(6), width - context.dp(6).toFloat(), bottom - context.dp(6))
        canvas.drawRoundRect(rect, context.dp(8).toFloat(), context.dp(8).toFloat(), handle)
        val cx = rect.centerX()
        val half = rect.width() / 4
        for (i in -1..1) {
            val y = rect.centerY() + i * context.dp(5)
            canvas.drawLine(cx - half, y, cx + half, y, grip)
        }
    }

    /** Avisa os serviços de acessibilidade quando a régua termina de ser movida. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitsHandle(event.x, event.y)) return false // o toque segue para o livro
                dragging = true
                grabOffset = event.y - fraction * height
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                fraction = ((event.y - grabOffset) / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                revealHandle() // some alguns segundos depois de soltar
                onMoved(fraction)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return dragging
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideHandle)
        super.onDetachedFromWindow()
    }

    companion object {
        /** Quanto tempo a alça fica à vista depois do toque. */
        const val HANDLE_SECONDS = 3
    }
}
