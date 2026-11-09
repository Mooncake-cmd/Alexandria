package com.alexandria.reader

import android.app.Activity
import kotlin.math.roundToInt

/**
 * Preferências de conforto visual da leitura. Kotlin puro (cores são inteiros ARGB), para os
 * cálculos serem testados sem Android. Valem para todos os livros e são salvas por [ComfortStore].
 */
data class Comfort(
    // 1. Cor da página
    val theme: Theme = Theme.CLARO,
    // 2. Luz e filtro
    val systemBrightness: Boolean = true,
    /** 0 a 100. Abaixo de [DIM_THRESHOLD], a tela fica mais escura que o mínimo do sistema. */
    val brightness: Int = 60,
    /** Filtro de luz quente, 0 (desligado) a 100. */
    val warmth: Int = 0,
    val overlayOn: Boolean = false,
    val overlayColor: Tint = Tint.AZUL,
    /** Opacidade da sobreposição colorida, em %. */
    val overlayOpacity: Int = 15,
    // 3. Tipografia (EPUB, MOBI, DOCX, TXT)
    /** Espaçamento entre linhas, em % do tamanho da letra. */
    val lineSpacing: Int = 140,
    /** Espaçamento entre letras, em centésimos de "em". */
    val letterSpacing: Int = 0,
    val margin: Margin = Margin.MEDIA,
    val lineWidth: LineWidth = LineWidth.LIVRE,
    val justify: Boolean = false,
    val font: Font = Font.ORIGINAL,
    // Régua de leitura
    val rulerHeight: RulerHeight = RulerHeight.MEDIA,
    val rulerColor: Tint = Tint.DOURADO,
    /** Opacidade da faixa da régua, em %. */
    val rulerOpacity: Int = 25,
    val rulerDim: Boolean = true
) {
    enum class Theme(val label: String, val background: Int, val text: Int, val link: Int, val dark: Boolean) {
        CLARO("Claro", 0xFFF5F2EB.toInt(), 0xFF162234.toInt(), 0xFFB78942.toInt(), false),
        SEPIA("Sépia", 0xFFF4ECD8.toInt(), 0xFF5B4636.toInt(), 0xFF9A6A2F.toInt(), false),
        CINZA("Cinza suave", 0xFFDDDDD8.toInt(), 0xFF333333.toInt(), 0xFF8A6A30.toInt(), false),
        ESCURO_SUAVE("Escuro suave", 0xFF2B2B2B.toInt(), 0xFFC8C8C2.toInt(), 0xFFC9A46A.toInt(), true),
        ESCURO("Escuro", 0xFF12161C.toInt(), 0xFFD6D0C4.toInt(), 0xFFC69A52.toInt(), true)
    }

    enum class Tint(val label: String, val rgb: Int) {
        DOURADO("Dourado", 0xC69A52),
        AZUL("Azul", 0x4A90E2),
        VERDE("Verde", 0x6BBF59),
        AMARELO("Amarelo", 0xF5D547),
        ROSA("Rosa", 0xF28FB5),
        LILAS("Lilás", 0xA98BD6),
        TURQUESA("Turquesa", 0x4DC3C3),
        PESSEGO("Pêssego", 0xF6B48F),
        CINZA("Cinza", 0x8C8C8C)
    }

    enum class Margin(val label: String, val dp: Int, val percent: Int) {
        PEQUENA("Pequena", 12, 3), MEDIA("Média", 22, 6), GRANDE("Grande", 40, 11)
    }

    /** Largura máxima da linha, em caracteres (0 = livre, usa a tela toda). */
    enum class LineWidth(val label: String, val chars: Int) {
        LIVRE("Livre", 0), LARGA("~75 caracteres", 75), MEDIA("~65 caracteres", 65), ESTREITA("~55 caracteres", 55)
    }

    enum class Font(val label: String, val css: String?) {
        ORIGINAL("Original do livro", null),
        SERIFADA("Serifada", "Georgia, 'Noto Serif', serif"),
        SEM_SERIFA("Sem serifa", "Roboto, 'Noto Sans', sans-serif"),
        ATKINSON("Atkinson Hyperlegible", "'Atkinson Hyperlegible', sans-serif")
    }

    enum class RulerHeight(val label: String, val dp: Int) {
        FINA("Fina (1 linha)", 30), MEDIA("Média (2 linhas)", 46), GROSSA("Grossa (3 linhas)", 70)
    }

    companion object {
        const val DIM_THRESHOLD = 15
        const val AVERAGE_CHAR_EM = 0.5f

        private fun argb(alpha: Int, rgb: Int) = (alpha.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
        private fun r(color: Int) = (color shr 16) and 0xFF
        private fun g(color: Int) = (color shr 8) and 0xFF
        private fun b(color: Int) = color and 0xFF

        /** Brilho da janela (0,01 a 1), ou -1 para seguir o sistema. */
        fun windowBrightness(c: Comfort): Float =
            if (c.systemBrightness) -1f else (c.brightness.coerceIn(1, 100) / 100f)

        /** Camada preta extra (0 a 1) para escurecer abaixo do mínimo do sistema. */
        fun extraDim(c: Comfort): Float =
            if (c.systemBrightness || c.brightness >= DIM_THRESHOLD) 0f
            else (DIM_THRESHOLD - c.brightness.coerceAtLeast(0)) / DIM_THRESHOLD.toFloat() * 0.55f

        /** Cor (ARGB) do filtro de luz quente: âmbar, até ~32% de opacidade. */
        fun warmColor(c: Comfort): Int = argb((c.warmth.coerceIn(0, 100) * 0.32f * 255 / 100).roundToInt(), 0xFF9329)

        /** Cor (ARGB) da sobreposição colorida, ou 0 (transparente) se desligada. */
        fun overlayColor(c: Comfort): Int =
            if (!c.overlayOn) 0 else argb((c.overlayOpacity.coerceIn(0, 60) * 255 / 100f).roundToInt(), c.overlayColor.rgb)

        /** Cor (ARGB) da faixa da régua. */
        fun rulerColor(c: Comfort): Int = argb((c.rulerOpacity.coerceIn(5, 80) * 255 / 100f).roundToInt(), c.rulerColor.rgb)

        /** Cor (ARGB) da linha de baixo da régua: a mesma cor, mais forte. */
        fun rulerEdgeColor(c: Comfort): Int = argb(210, c.rulerColor.rgb)

        /**
         * Matriz de cor 4x5 (formato do ColorMatrix do Android) para PDF, DJVU e quadrinhos, que
         * não têm "cor de texto". Temas claros multiplicam pela cor do papel (o branco vira papel,
         * o preto continua preto). Temas escuros invertem a luminosidade mantendo o matiz (fotos
         * não ficam com cores trocadas) e levam o branco à cor de fundo e o preto à cor do texto.
         * Retorna null no tema claro padrão (sem filtro).
         */
        fun pageMatrix(theme: Theme): FloatArray? {
            if (theme == Theme.CLARO) return null
            if (!theme.dark) {
                val bg = theme.background
                return floatArrayOf(
                    r(bg) / 255f, 0f, 0f, 0f, 0f,
                    0f, g(bg) / 255f, 0f, 0f, 0f,
                    0f, 0f, b(bg) / 255f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            }
            // Inversão + rotação de matiz de 180°: c' = 255 + c - 2·lum(c) (lum com pesos do Android).
            val lum = floatArrayOf(0.213f, 0.715f, 0.072f)
            val lo = intArrayOf(r(theme.background), g(theme.background), b(theme.background))
            val hi = intArrayOf(r(theme.text), g(theme.text), b(theme.text))
            val m = FloatArray(20)
            for (ch in 0..2) {
                val k = (hi[ch] - lo[ch]) / 255f
                for (i in 0..2) m[ch * 5 + i] = k * ((if (i == ch) 1f else 0f) - 2 * lum[i])
                m[ch * 5 + 4] = lo[ch] + 255f * k
            }
            m[18] = 1f
            return m
        }

        /** Aplica uma matriz 4x5 a uma cor RGB (para os testes e prévias). */
        fun applyMatrix(m: FloatArray, color: Int): Int {
            val c = floatArrayOf(r(color).toFloat(), g(color).toFloat(), b(color).toFloat(), 255f)
            fun ch(row: Int) = (m[row * 5] * c[0] + m[row * 5 + 1] * c[1] + m[row * 5 + 2] * c[2] + m[row * 5 + 3] * c[3] + m[row * 5 + 4])
                .roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }

        /**
         * Recuo de cada lado do texto, em pixels: a margem escolhida ou, se a largura máxima da linha
         * pedir, o que sobrar para a linha ter cerca de N caracteres.
         */
        fun sidePadding(viewWidth: Int, textSizePx: Float, marginPx: Int, lineWidth: LineWidth): Int {
            if (lineWidth.chars == 0 || viewWidth <= 0) return marginPx
            val maxLine = (lineWidth.chars * AVERAGE_CHAR_EM * textSizePx).roundToInt()
            return maxOf(marginPx, (viewWidth - maxLine) / 2)
        }

        private fun css(color: Int) = String.format("#%06X", color and 0xFFFFFF)

        /** CSS de leitura do EPUB/MOBI com tema e tipografia. [fontFaces] declara a Atkinson. */
        fun epubCss(c: Comfort, fontFaces: String): String {
            val t = c.theme
            val align = if (c.justify) "justify" else "left"
            val maxWidth = if (c.lineWidth.chars > 0) "max-width: ${c.lineWidth.chars * AVERAGE_CHAR_EM}em !important;" else ""
            val family = c.font.css?.let { "body, p, div, span, li, td, blockquote, h1, h2, h3, h4, h5, h6 { font-family: $it !important; }" } ?: ""
            val spacing = c.lineSpacing / 100f
            val letters = c.letterSpacing / 100f
            return """
                ${if (c.font == Font.ATKINSON) fontFaces else ""}
                html, body { background: ${css(t.background)} !important; color: ${css(t.text)} !important; }
                body { margin: 0 auto !important; padding: 20px ${c.margin.percent}% 48px !important; $maxWidth
                       line-height: $spacing !important; letter-spacing: ${letters}em !important; word-wrap: break-word; }
                p, li, blockquote, dd, dt, td, span, div { line-height: inherit !important; letter-spacing: inherit !important; }
                p, li, blockquote { text-align: $align !important; }
                $family
                * { color: inherit; background-color: transparent; }
                a { color: ${css(t.link)} !important; }
                img, svg, video { max-width: 100% !important; height: auto !important; }
                pre, table { max-width: 100%; overflow-x: auto; }
            """.trimIndent()
        }
    }
}

/** Salva e lê o [Comfort] nas preferências do leitor. */
object ComfortStore {
    private const val PREFS = "reader"

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    fun load(context: android.content.Context): Comfort {
        val p = context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val d = Comfort()
        // Antes havia só "tema escuro" liga/desliga: vira o tema Escuro.
        val legacyTheme = if (p.getBoolean("night_mode", false)) Comfort.Theme.ESCURO else d.theme
        return Comfort(
            theme = enumOf(p.getString("comfort_theme", null), legacyTheme),
            systemBrightness = p.getBoolean("comfort_system_brightness", d.systemBrightness),
            brightness = p.getInt("comfort_brightness", d.brightness),
            warmth = p.getInt("comfort_warmth", d.warmth),
            overlayOn = p.getBoolean("comfort_overlay_on", d.overlayOn),
            overlayColor = enumOf(p.getString("comfort_overlay_color", null), d.overlayColor),
            overlayOpacity = p.getInt("comfort_overlay_opacity", d.overlayOpacity),
            lineSpacing = p.getInt("comfort_line_spacing", d.lineSpacing),
            letterSpacing = p.getInt("comfort_letter_spacing", d.letterSpacing),
            margin = enumOf(p.getString("comfort_margin", null), d.margin),
            lineWidth = enumOf(p.getString("comfort_line_width", null), d.lineWidth),
            justify = p.getBoolean("comfort_justify", d.justify),
            font = enumOf(p.getString("comfort_font", null), d.font),
            rulerHeight = enumOf(p.getString("ruler_height", null), d.rulerHeight),
            rulerColor = enumOf(p.getString("ruler_color", null), d.rulerColor),
            rulerOpacity = p.getInt("ruler_opacity", d.rulerOpacity),
            rulerDim = p.getBoolean("ruler_dim", d.rulerDim)
        )
    }

    fun save(context: android.content.Context, c: Comfort) {
        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
            .putString("comfort_theme", c.theme.name)
            .putBoolean("comfort_system_brightness", c.systemBrightness)
            .putInt("comfort_brightness", c.brightness)
            .putInt("comfort_warmth", c.warmth)
            .putBoolean("comfort_overlay_on", c.overlayOn)
            .putString("comfort_overlay_color", c.overlayColor.name)
            .putInt("comfort_overlay_opacity", c.overlayOpacity)
            .putInt("comfort_line_spacing", c.lineSpacing)
            .putInt("comfort_letter_spacing", c.letterSpacing)
            .putString("comfort_margin", c.margin.name)
            .putString("comfort_line_width", c.lineWidth.name)
            .putBoolean("comfort_justify", c.justify)
            .putString("comfort_font", c.font.name)
            .putString("ruler_height", c.rulerHeight.name)
            .putString("ruler_color", c.rulerColor.name)
            .putInt("ruler_opacity", c.rulerOpacity)
            .putBoolean("ruler_dim", c.rulerDim)
            .apply()
    }
}
