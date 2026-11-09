package com.alexandria.reader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Painel "Conforto visual": cor da página, luz e filtro, tipografia e régua. Cada mudança é salva
 * e entregue a [onChange] na hora, para a página por trás mostrar o efeito enquanto se ajusta.
 */
object ComfortPanel {
    fun show(
        activity: Activity,
        rulerOn: Boolean? = null,
        onRulerToggle: ((Boolean) -> Unit)? = null,
        onChange: (Comfort) -> Unit = {}
    ) {
        var comfort = ComfortStore.load(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(16))
        }
        lateinit var rebuild: () -> Unit

        fun update(next: Comfort, redraw: Boolean = false) {
            comfort = next
            ComfortStore.save(activity, next)
            onChange(next)
            if (redraw) rebuild()
        }

        rebuild = {
            content.removeAllViews()
            val c = comfort

            section(activity, content, "Cor da página", "Vale para todos os formatos, inclusive PDF, DJVU e quadrinhos.")
            chips(activity, content, Comfort.Theme.entries, c.theme, { it.label }, swatch = { it.background to it.text }) {
                update(c.copy(theme = it), redraw = true)
            }

            section(activity, content, "Luz e filtro", null)
            switch(activity, content, "Usar o brilho do sistema", c.systemBrightness) { update(comfort.copy(systemBrightness = it), redraw = true) }
            if (!c.systemBrightness) {
                slider(activity, content, "Brilho", 0, 100, c.brightness, { v ->
                    if (v < Comfort.DIM_THRESHOLD) "$v% (abaixo do mínimo do sistema)" else "$v%"
                }) { update(comfort.copy(brightness = it)) }
            }
            slider(activity, content, "Luz quente", 0, 100, c.warmth, { if (it == 0) "desligada" else "$it%" }) {
                update(comfort.copy(warmth = it))
            }
            switch(activity, content, "Sobreposição colorida", c.overlayOn) { update(comfort.copy(overlayOn = it), redraw = true) }
            if (c.overlayOn) {
                colors(activity, content, c.overlayColor) { update(comfort.copy(overlayColor = it), redraw = true) }
                slider(activity, content, "Opacidade da cor", 5, 40, c.overlayOpacity, { "$it%" }) { update(comfort.copy(overlayOpacity = it)) }
                note(activity, content, "Algumas pessoas acham mais confortável ler com um tom de cor. Os estudos sobre isso são inconclusivos: use se for bom para você.")
            }

            section(activity, content, "Tipografia", "EPUB, MOBI, DOCX e TXT. Em PDF, DJVU e quadrinhos o layout é fixo.")
            label(activity, content, "Fonte")
            chips(activity, content, Comfort.Font.entries, c.font, { it.label }, typeface = { fontPreview(activity, it) }) {
                update(c.copy(font = it), redraw = true)
            }
            slider(activity, content, "Espaçamento entre linhas", 110, 220, c.lineSpacing, { String.format(java.util.Locale("pt", "BR"), "%.2fx", it / 100f) }, step = 5) {
                update(comfort.copy(lineSpacing = it))
            }
            slider(activity, content, "Espaçamento entre letras", 0, 15, c.letterSpacing, { if (it == 0) "normal" else "+$it%" }) {
                update(comfort.copy(letterSpacing = it))
            }
            label(activity, content, "Margens")
            chips(activity, content, Comfort.Margin.entries, c.margin, { it.label }) { update(c.copy(margin = it), redraw = true) }
            label(activity, content, "Largura máxima da linha")
            chips(activity, content, Comfort.LineWidth.entries, c.lineWidth, { it.label }) { update(c.copy(lineWidth = it), redraw = true) }
            label(activity, content, "Alinhamento")
            chips(activity, content, listOf(false, true), c.justify, { if (it) "Justificado" else "À esquerda" }) {
                update(c.copy(justify = it), redraw = true)
            }

            section(activity, content, "Régua de leitura", null)
            if (rulerOn != null && onRulerToggle != null) {
                switch(activity, content, "Mostrar a régua", currentRuler ?: rulerOn) {
                    currentRuler = it
                    onRulerToggle(it)
                }
            }
            label(activity, content, "Espessura")
            chips(activity, content, Comfort.RulerHeight.entries, c.rulerHeight, { it.label }) { update(c.copy(rulerHeight = it), redraw = true) }
            label(activity, content, "Cor")
            colors(activity, content, c.rulerColor) { update(comfort.copy(rulerColor = it), redraw = true) }
            slider(activity, content, "Opacidade", 10, 60, c.rulerOpacity, { "$it%" }) { update(comfort.copy(rulerOpacity = it)) }
            switch(activity, content, "Escurecer o resto da página", c.rulerDim) { update(comfort.copy(rulerDim = it)) }

            content.addView(TextView(activity).apply {
                text = "Restaurar o padrão"
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = activity.dp(48)
                setTextColor(Palette.gold)
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { update(Comfort(), redraw = true) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(16) })
        }
        currentRuler = null
        rebuild()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Conforto visual")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Fechar", null)
            .create()
        dialog.show()
        // Sem escurecer o fundo e encostado embaixo: a página continua visível para ver o efeito.
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.62f).toInt())
        }
    }

    private var currentRuler: Boolean? = null

    private fun fontPreview(activity: Activity, font: Comfort.Font): Typeface = when (font) {
        Comfort.Font.ORIGINAL, Comfort.Font.SERIFADA -> Typeface.SERIF
        Comfort.Font.SEM_SERIFA -> Typeface.SANS_SERIF
        Comfort.Font.ATKINSON -> ReaderFonts.atkinson(activity) ?: Typeface.SANS_SERIF
    }

    private fun section(activity: Activity, parent: LinearLayout, title: String, subtitle: String?) {
        parent.addView(activity.label(title, 16f, Palette.ink, true).apply { setPadding(0, activity.dp(18), 0, activity.dp(2)) })
        if (subtitle != null) parent.addView(activity.label(subtitle, 11f, Palette.muted).apply { setPadding(0, 0, 0, activity.dp(6)) })
    }

    private fun label(activity: Activity, parent: LinearLayout, text: String) {
        parent.addView(activity.label(text, 13f, Palette.ink).apply { setPadding(0, activity.dp(8), 0, activity.dp(4)) })
    }

    private fun note(activity: Activity, parent: LinearLayout, text: String) {
        parent.addView(activity.label(text, 11f, Palette.muted).apply { setPadding(0, activity.dp(2), 0, activity.dp(4)) })
    }

    private fun <T> chips(
        activity: Activity,
        parent: LinearLayout,
        options: List<T>,
        selected: T,
        name: (T) -> String,
        swatch: ((T) -> Pair<Int, Int>)? = null,
        typeface: ((T) -> Typeface)? = null,
        onPick: (T) -> Unit
    ) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (option in options) {
            val active = option == selected
            val colors = swatch?.invoke(option)
            row.addView(TextView(activity).apply {
                text = name(option)
                textSize = 13f
                gravity = Gravity.CENTER
                minHeight = activity.dp(40)
                setPadding(activity.dp(14), 0, activity.dp(14), 0)
                typeface?.invoke(option)?.let { setTypeface(it) }
                setTextColor(colors?.second ?: if (active) Color.WHITE else Palette.ink)
                background = GradientDrawable().apply {
                    cornerRadius = activity.dp(20).toFloat()
                    setColor(colors?.first ?: if (active) Palette.ink else Color.WHITE)
                    setStroke(activity.dp(if (active) 3 else 1), if (active) Palette.gold else Color.rgb(210, 205, 195))
                }
                contentDescription = name(option) + if (active) " (selecionado)" else ""
                setOnClickListener { onPick(option) }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = activity.dp(8) })
        }
        parent.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun colors(activity: Activity, parent: LinearLayout, selected: Comfort.Tint, onPick: (Comfort.Tint) -> Unit) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, activity.dp(4), 0, activity.dp(4))
        }
        for (tint in Comfort.Tint.entries) {
            val active = tint == selected
            row.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF000000.toInt() or tint.rgb)
                    setStroke(activity.dp(if (active) 3 else 1), if (active) Palette.ink else Color.WHITE)
                }
                contentDescription = tint.label + if (active) " (selecionada)" else ""
                setOnClickListener { onPick(tint) }
            }, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)).apply { rightMargin = activity.dp(10) })
        }
        parent.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun slider(
        activity: Activity,
        parent: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        step: Int = 1,
        onValue: (Int) -> Unit
    ) {
        val caption = activity.label("$title: ${format(value)}", 13f, Palette.ink).apply { setPadding(0, activity.dp(8), 0, 0) }
        parent.addView(caption)
        parent.addView(SeekBar(activity).apply {
            this.max = (max - min) / step
            progress = ((value.coerceIn(min, max) - min) / step)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = min + progress * step
                    caption.text = "$title: ${format(v)}"
                    if (fromUser) onValue(v)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, activity.dp(40)))
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(activity: Activity, parent: LinearLayout, title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        parent.addView(Switch(activity).apply {
            text = title
            textSize = 13f
            setTextColor(Palette.ink)
            isChecked = checked
            minHeight = activity.dp(44)
            setOnCheckedChangeListener { _, on -> onToggle(on) }
        }, LinearLayout.LayoutParams(-1, -2))
    }
}

/** Fontes de leitura empacotadas no app. */
object ReaderFonts {
    private var regular: Typeface? = null
    private var tried = false

    /** Atkinson Hyperlegible (Braille Institute, licença SIL OFL 1.1), ou null se não carregar. */
    fun atkinson(context: android.content.Context): Typeface? {
        if (!tried) {
            tried = true
            regular = try {
                Typeface.createFromAsset(context.assets, "fonts/atkinson-regular.ttf")
            } catch (e: Exception) {
                null
            }
        }
        return regular
    }

    /** Fonte do texto (TXT/DOCX) para a escolha feita no painel. */
    fun forText(context: android.content.Context, font: Comfort.Font): Typeface = when (font) {
        Comfort.Font.ORIGINAL, Comfort.Font.SERIFADA -> Typeface.SERIF
        Comfort.Font.SEM_SERIFA -> Typeface.SANS_SERIF
        Comfort.Font.ATKINSON -> atkinson(context) ?: Typeface.SANS_SERIF
    }

    /** @font-face da Atkinson para o EPUB, servida de dentro do app pelo endereço interno do livro. */
    fun epubFontFaces(base: String): String = listOf(
        Triple("400-normal", 400, "normal"), Triple("700-normal", 700, "normal"),
        Triple("400-italic", 400, "italic"), Triple("700-italic", 700, "italic")
    ).joinToString("\n") { (file, weight, style) ->
        "@font-face { font-family: 'Atkinson Hyperlegible'; src: url('${base}__alexandria/fonts/atkinson-$file.woff2') format('woff2'); font-weight: $weight; font-style: $style; }"
    }
}
