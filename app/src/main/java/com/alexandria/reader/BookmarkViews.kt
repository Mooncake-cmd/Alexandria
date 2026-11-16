package com.alexandria.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Canto de cima à direita da página: um botão invisível que marca (ou desmarca) a página. Com a
 * página marcada, desenha a fita azul do marcador.
 */
@SuppressLint("ViewConstructor")
class BookmarkCorner(context: Context, onToggle: () -> Unit) : View(context) {
    var marked = false
        set(value) {
            if (field == value) return
            field = value
            contentDescription = if (value) "Desmarcar esta página" else "Marcar esta página"
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RIBBON }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 0, 0, 0) }
    private val path = Path()

    init {
        contentDescription = "Marcar esta página"
        isClickable = true
        setOnClickListener { onToggle() }
    }

    override fun onDraw(canvas: Canvas) {
        if (!marked) return
        val density = resources.displayMetrics.density
        val w = 18 * density
        val h = 30 * density
        val right = width - 14 * density
        val left = right - w
        path.reset()
        path.moveTo(left, 0f)
        path.lineTo(right, 0f)
        path.lineTo(right, h)
        path.lineTo(left + w / 2, h - w * 0.45f)
        path.lineTo(left, h)
        path.close()
        canvas.save()
        canvas.translate(density, density)
        canvas.drawPath(path, shadow)
        canvas.restore()
        canvas.drawPath(path, paint)
    }

    companion object {
        val RIBBON = Color.rgb(66, 133, 244)
    }
}
