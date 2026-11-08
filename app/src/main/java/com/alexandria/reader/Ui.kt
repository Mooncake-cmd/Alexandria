package com.alexandria.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.TextView

/** Paleta única do aplicativo, compartilhada por todas as telas. */
object Palette {
    val ink = Color.rgb(22, 34, 52)
    val gold = Color.rgb(183, 137, 66)
    val paper = Color.rgb(245, 242, 235)
    val muted = Color.rgb(111, 119, 128)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
}

fun Context.label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/**
 * A partir do Android 15 (targetSdk 35) as janelas são desenhadas por baixo da barra de status
 * e da barra de navegação. Sem tratamento, o cabeçalho fica atrás do relógio e a navegação
 * inferior fica atrás dos gestos do sistema. Esta função empurra [top] e [bottom] para dentro
 * das áreas seguras, mantendo o fundo colorido de cada um sob as barras do sistema.
 *
 * Em versões anteriores o sistema já reserva essas áreas, então nada é feito.
 */
@Suppress("DEPRECATION")
fun installSystemBarInsets(root: View, top: View, bottom: View) {
    if (Build.VERSION.SDK_INT < 35) return
    val topLeft = top.paddingLeft
    val topTop = top.paddingTop
    val topRight = top.paddingRight
    val topBottom = top.paddingBottom
    val bottomLeft = bottom.paddingLeft
    val bottomTop = bottom.paddingTop
    val bottomRight = bottom.paddingRight
    val bottomBottom = bottom.paddingBottom
    root.setOnApplyWindowInsetsListener { view, insets ->
        view.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, 0)
        top.setPadding(topLeft, topTop + insets.systemWindowInsetTop, topRight, topBottom)
        bottom.setPadding(bottomLeft, bottomTop, bottomRight, bottomBottom + insets.systemWindowInsetBottom)
        insets
    }
}
