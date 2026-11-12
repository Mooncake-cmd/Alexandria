package com.alexandria.reader

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/** Um caractere da página com sua caixa, em frações da página (0 a 1, origem no canto de cima). */
data class PdfChar(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** Retângulo em frações da página. */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Texto de uma página de PDF, caractere a caractere com posição (vem do EraPDF/MuPDF). Kotlin puro:
 * encontra o caractere sob o dedo, expande para a palavra e transforma trechos em retângulos por linha.
 */
class PageText(val chars: List<PdfChar>) {
    /** Texto da página. Cada caractere pode ter mais de um char Kotlin; [starts] liga um ao outro. */
    val string: String
    private val starts: IntArray

    init {
        val builder = StringBuilder()
        starts = IntArray(chars.size + 1)
        chars.forEachIndexed { i, c ->
            starts[i] = builder.length
            builder.append(c.text)
        }
        starts[chars.size] = builder.length
        string = builder.toString()
    }

    val isEmpty get() = chars.none { it.text.isNotBlank() }

    /** Índice do caractere (na lista) que começa no deslocamento [offset] do [string]. */
    fun charIndexOf(offset: Int): Int {
        var lo = 0
        var hi = chars.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun offsetOf(charIndex: Int): Int = starts[charIndex.coerceIn(0, chars.size)]

    /**
     * Caractere sob o ponto (x, y) da página. Se o ponto cair num vão, vale o mais próximo da mesma
     * linha; longe de qualquer texto (mais de [maxDistance]), null.
     */
    fun charAt(x: Float, y: Float, maxDistance: Float = 0.04f): Int? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        chars.forEachIndexed { i, c ->
            if (c.text.isBlank() && c.text != " ") return@forEachIndexed
            if (x >= c.left && x <= c.right && y >= c.top && y <= c.bottom) return i
            // Distância com peso maior na vertical: prefere a mesma linha.
            val dx = if (x < c.left) c.left - x else if (x > c.right) x - c.right else 0f
            val dy = if (y < c.top) c.top - y else if (y > c.bottom) y - c.bottom else 0f
            val d = sqrt(dx * dx + (dy * 3) * (dy * 3))
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return if (best >= 0 && bestDistance <= maxDistance) best else null
    }

    /** Palavra (letras e dígitos) que contém o caractere [index], como intervalo [início, fim) de caracteres. */
    fun wordAt(index: Int): IntRange? {
        fun isWord(i: Int) = chars[i].text.any { it.isLetterOrDigit() }
        if (index !in chars.indices || !isWord(index)) return null
        var start = index
        var end = index
        while (start > 0 && isWord(start - 1) && sameLine(start - 1, start)) start--
        while (end < chars.size - 1 && isWord(end + 1) && sameLine(end, end + 1)) end++
        return start..end
    }

    private fun sameLine(a: Int, b: Int): Boolean {
        val ca = chars[a]
        val cb = chars[b]
        val overlap = minOf(ca.bottom, cb.bottom) - maxOf(ca.top, cb.top)
        return overlap > 0.5f * minOf(ca.bottom - ca.top, cb.bottom - cb.top)
    }

    /** Texto dos caracteres [from]..[to] (inclusive, em qualquer ordem). */
    fun textOf(from: Int, to: Int): String {
        val a = minOf(from, to).coerceIn(0, chars.size - 1)
        val b = maxOf(from, to).coerceIn(0, chars.size - 1)
        return string.substring(starts[a], starts[b + 1]).replace(Regex("\\s+"), " ").trim()
    }

    /** Retângulos (um por linha) que cobrem os caracteres [from]..[to] inclusive. */
    fun rects(from: Int, to: Int): List<PageRect> {
        if (chars.isEmpty()) return emptyList()
        val a = minOf(from, to).coerceIn(0, chars.size - 1)
        val b = maxOf(from, to).coerceIn(0, chars.size - 1)
        val result = mutableListOf<PageRect>()
        var current: PageRect? = null
        var previous = -1
        for (i in a..b) {
            val c = chars[i]
            if (c.text == "\n" || c.right <= c.left) continue
            val cur = current
            current = if (cur == null || previous < 0 || !sameLine(previous, i)) {
                if (cur != null) result += cur
                PageRect(c.left, c.top, c.right, c.bottom)
            } else {
                PageRect(minOf(cur.left, c.left), minOf(cur.top, c.top), maxOf(cur.right, c.right), maxOf(cur.bottom, c.bottom))
            }
            previous = i
        }
        current?.let { result += it }
        return result
    }

    /** Retângulos de um trecho dado por deslocamentos [start, end) no [string] (busca, glossário). */
    fun rectsForOffsets(start: Int, end: Int): List<PageRect> {
        if (end <= start || chars.isEmpty()) return emptyList()
        return rects(charIndexOf(start), charIndexOf(end - 1))
    }

    companion object {
        /** Monta a página a partir da resposta do EraPDF: (esquerda, topo, direita, base, texto) repetidos. */
        fun fromEngine(values: List<OreEngine.Value>): PageText {
            val chars = ArrayList<PdfChar>(values.size / 5)
            var i = 0
            while (i + 4 < values.size) {
                chars += PdfChar(values[i + 4].string, values[i].float, values[i + 1].float, values[i + 2].float, values[i + 3].float)
                i += 5
            }
            return PageText(chars)
        }
    }
}

/**
 * PDF pelo motor EraPDF (MuPDF, o mesmo do ReadEra): desenha as páginas e entrega o texto com
 * posição, o que permite selecionar, sublinhar termos do glossário e buscar.
 */
class EraPdfSource(private val engine: OreEngine, file: File, fonts: Map<Int, File> = emptyMap()) : PageSource {
    override val pageCount: Int
    private val sizes = HashMap<Int, Pair<Float, Float>>()
    private val texts = object : LinkedHashMap<Int, PageText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>?) = size > 40
    }

    init {
        val response = try {
            // Fontes para PDFs que não embutem as suas (Times, Arial, Courier...). Sem isso o motor
            // procura as fontes Droid em /system/fonts, que não existem nos Androids atuais, e o
            // texto some. Falhar aqui não impede de abrir: só piora o caso dessas fontes.
            if (fonts.isNotEmpty()) {
                val args = fonts.entries.sortedBy { it.key }.flatMap { (key, path) ->
                    listOf(OreEngine.Arg.int(key), OreEngine.Arg.string(path.path))
                }
                engine.call(OreEngine.CMD_REQ_SET_FONT_CONFIG, *args.toTypedArray())
            }
            engine.call(
                OreEngine.CMD_REQ_OPEN,
                OreEngine.Arg.string(""),
                OreEngine.Arg.int(OreEngine.FORMAT_PDF),
                OreEngine.Arg.string(file.path),
                OreEngine.Arg.int(0),
                OreEngine.Arg.string("") // senha (o motor exige o campo, mesmo vazio)
            )
        } catch (e: Exception) {
            engine.close()
            throw e
        }
        if (response.result == RES_PASSWORD_NEEDED || response.result == RES_PASSWORD_WRONG) {
            engine.close()
            throw PdfPasswordException()
        }
        if (!response.ok) {
            engine.close()
            throw OreException("Abertura do PDF falhou (código ${response.result})")
        }
        pageCount = response.values.firstOrNull()?.int ?: 0
    }

    /** Tamanho da página em pontos. */
    fun pageSize(index: Int): Pair<Float, Float> = sizes.getOrPut(index) {
        val values = engine.call(OreEngine.CMD_REQ_PAGE_INFO, OreEngine.Arg.int(index)).requireOk("Página do PDF")
            .values.firstOrNull()?.floats() ?: FloatArray(0)
        if (values.size < 2 || values[0] <= 0f || values[1] <= 0f) throw OreException("Página ${index + 1} sem tamanho")
        values[0] to values[1]
    }

    override fun aspect(index: Int): Float = pageSize(index).let { (w, h) -> h / w }

    override fun render(index: Int, width: Int): RenderedPage {
        val (pw, ph) = pageSize(index)
        var w = width.coerceAtLeast(1)
        var h = (ph * w / pw).toInt().coerceAtLeast(1)
        val pixels = w.toLong() * h
        if (pixels > PageSource.MAX_PIXELS) {
            val scale = sqrt(PageSource.MAX_PIXELS.toDouble() / pixels)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }
        val response = engine.call(
            OreEngine.CMD_REQ_PAGE_RENDER,
            OreEngine.Arg.int(index),
            OreEngine.Arg.int(w),
            OreEngine.Arg.int(h),
            OreEngine.Arg.floats(w / pw, 0f, 0f, h / ph, 0f, 0f), // pontos da página → pixels
            OreEngine.Arg.int(0)
        ).requireOk("Desenho da página do PDF")
        val data = response.values.firstOrNull()?.bytes ?: throw OreException("Página sem pixels")
        if (data.size != w * h * 4) throw OreException("Tamanho de página inesperado")
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        return RenderedPage(bitmap, w, h)
    }

    override fun text(index: Int): PageText = texts.getOrPut(index) {
        val response = engine.call(OreEngine.CMD_REQ_PAGE_TEXT, OreEngine.Arg.int(index)).requireOk("Texto da página")
        PageText.fromEngine(response.values)
    }

    override fun close() = engine.close()

    companion object {
        const val RES_PASSWORD_WRONG = 251
        const val RES_PASSWORD_NEEDED = 252
    }
}

class PdfPasswordException : Exception("PDF protegido por senha")

/** Utilitário: distância simples entre pontos, para achar a alça de seleção mais perto. */
internal fun distance(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))

internal fun near(a: Float, b: Float, tolerance: Float) = abs(a - b) <= tolerance

/**
 * Fontes Liberation (SIL OFL 1.1), com as mesmas medidas de Times New Roman, Arial e Courier New,
 * usadas pelo EraPDF no lugar das fontes que o PDF cita mas não traz embutidas. Ficam no APK e são
 * copiadas uma vez para a pasta do app, porque o motor (outro processo) precisa de um caminho.
 */
object PdfFonts {
    private const val ASSET_DIR = "fonts/pdf"

    /** Chave do motor (SET_FONT_CONFIG) → arquivo: sem serifa 124–127, serifada 128–131, mono 132–135. */
    val KEYS: Map<Int, String> = linkedMapOf(
        124 to "LiberationSans-Regular.ttf", 125 to "LiberationSans-Italic.ttf",
        126 to "LiberationSans-Bold.ttf", 127 to "LiberationSans-BoldItalic.ttf",
        128 to "LiberationSerif-Regular.ttf", 129 to "LiberationSerif-Italic.ttf",
        130 to "LiberationSerif-Bold.ttf", 131 to "LiberationSerif-BoldItalic.ttf",
        132 to "LiberationMono-Regular.ttf", 133 to "LiberationMono-Italic.ttf",
        134 to "LiberationMono-Bold.ttf", 135 to "LiberationMono-BoldItalic.ttf"
    )

    private val lock = Any()

    /** Caminhos das fontes já copiadas; as que não puderem ser copiadas ficam de fora. */
    fun files(context: Context): Map<Int, File> = synchronized(lock) {
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val result = LinkedHashMap<Int, File>()
        for ((key, name) in KEYS) {
            val target = File(dir, name)
            try {
                if (!target.isFile || target.length() == 0L) {
                    val partial = File(dir, "$name.part")
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        partial.outputStream().use { input.copyTo(it) }
                    }
                    if (!partial.renameTo(target)) continue
                }
                result[key] = target
            } catch (e: java.io.IOException) {
                // Sem essa fonte o motor usa a padrão dele: o PDF abre do mesmo jeito.
            }
        }
        result
    }
}
