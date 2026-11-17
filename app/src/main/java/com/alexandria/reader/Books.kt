package com.alexandria.reader

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URLDecoder
import java.text.Normalizer
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * Ordem "natural" de nomes de arquivo: pagina2 antes de pagina10, sem diferenciar maiúsculas.
 * É como a maioria dos leitores de quadrinhos ordena as páginas de um CBZ.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}

/** Busca de texto que ignora maiúsculas e acentos ("acao" encontra "Ação"). */
object TextSearch {
    /** Mesma quantidade de caracteres que [text], para os índices continuarem valendo no original. */
    fun fold(text: CharSequence): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            val base = if (c.code < 128) c else Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
            out.append(base.lowercaseChar())
        }
        return out.toString()
    }

    /** Posições de todas as ocorrências de [term] em [text]. */
    fun findAll(text: CharSequence, term: String): List<Int> {
        val needle = fold(term.trim())
        if (needle.isEmpty()) return emptyList()
        val haystack = fold(text)
        val result = mutableListOf<Int>()
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            result += from
            from = haystack.indexOf(needle, from + needle.length)
        }
        return result
    }
}

/** Páginas de um quadrinho CBZ: as imagens do ZIP, em ordem natural. */
object ComicArchive {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun pageNames(entryNames: List<String>): List<String> = entryNames
        .filter { name ->
            val file = name.substringAfterLast('/')
            !name.endsWith("/") &&
                !name.startsWith("__MACOSX/") &&
                !file.startsWith(".") &&
                file.substringAfterLast('.', "").lowercase() in imageExtensions
        }
        .sortedWith(NaturalOrder)

    fun pageNames(zip: ZipFile): List<String> = pageNames(zip.entries().toList().map { it.name })
}

/** Um ZIP começa com "PK". Muitos .cbr são, na verdade, ZIPs com a extensão trocada. */
fun looksLikeZip(header: ByteArray): Boolean =
    header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()

/** Caminhos dentro de um ZIP: resolve "../", tira "./" e decodifica %20 e afins. */
object ZipPaths {
    fun resolve(baseDir: String, href: String): String {
        val clean = decode(href.substringBefore('#').substringBefore('?'))
        val parts = ArrayDeque<String>()
        if (!clean.startsWith("/")) baseDir.split('/').filter { it.isNotEmpty() }.forEach { parts.addLast(it) }
        for (part in clean.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    fun dirOf(path: String): String = if ('/' in path) path.substringBeforeLast('/') else ""

    private fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }
}

private fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    setInput(input, null)
}

/** Estrutura de um EPUB (2 ou 3): título, capítulos na ordem de leitura, sumário e capa. */
class EpubPackage(
    val title: String?,
    /** Caminhos (dentro do ZIP) dos capítulos, na ordem de leitura. */
    val chapters: List<String>,
    /** Título de cada capítulo, quando o sumário do livro tem um. Mesmo tamanho de [chapters]. */
    val chapterTitles: List<String?>,
    val coverPath: String?
) {
    companion object {
        private val htmlTypes = setOf("application/xhtml+xml", "text/html", "application/xml")

        /** Lê o EPUB. Lança [IllegalArgumentException] se a estrutura obrigatória estiver faltando. */
        fun read(zip: ZipFile): EpubPackage {
            fun open(path: String): InputStream =
                zip.getEntry(path)?.let { zip.getInputStream(it) } ?: throw IllegalArgumentException("Faltando $path")

            val opfPath = open("META-INF/container.xml").use { rootFile(it) }
                ?: throw IllegalArgumentException("container.xml sem rootfile")
            val opf = open(opfPath).use { parseOpf(it) }
            val baseDir = ZipPaths.dirOf(opfPath)

            val manifest = opf.manifest.mapValues { (_, item) -> item.copy(href = ZipPaths.resolve(baseDir, item.href)) }
            val chapters = opf.spine.mapNotNull { manifest[it] }
                .filter { it.mediaType in htmlTypes && zip.getEntry(it.href) != null }
                .map { it.href }
                .distinct()
            if (chapters.isEmpty()) throw IllegalArgumentException("EPUB sem capítulos")

            val cover = (manifest.values.firstOrNull { "cover-image" in it.properties }
                ?: opf.coverId?.let { manifest[it] })
                ?.takeIf { it.mediaType.startsWith("image/") }
                ?.href

            // Sumário: EPUB 3 usa o "nav"; EPUB 2 usa o NCX. Qualquer erro aqui só deixa sem títulos.
            val toc: Map<String, String> = try {
                val nav = manifest.values.firstOrNull { "nav" in it.properties }
                val ncx = manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                    ?: opf.tocId?.let { manifest[it] }
                when {
                    nav != null && zip.getEntry(nav.href) != null ->
                        open(nav.href).use { parseNav(it, ZipPaths.dirOf(nav.href)) }
                    ncx != null && zip.getEntry(ncx.href) != null ->
                        open(ncx.href).use { parseNcx(it, ZipPaths.dirOf(ncx.href)) }
                    else -> emptyMap()
                }
            } catch (e: Exception) {
                emptyMap()
            }

            return EpubPackage(opf.title?.trim()?.ifEmpty { null }, chapters, chapters.map { toc[it] }, cover)
        }

        internal fun rootFile(input: InputStream): String? {
            val parser = newParser(input)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val path = parser.getAttributeValue(null, "full-path")
                    val type = parser.getAttributeValue(null, "media-type")
                    if (path != null && (type == null || type == "application/oebps-package+xml")) return path
                }
            }
            return null
        }

        internal data class ManifestItem(val href: String, val mediaType: String, val properties: List<String>)

        internal class Opf(
            val title: String?,
            val manifest: Map<String, ManifestItem>,
            val spine: List<String>,
            val coverId: String?,
            val tocId: String?
        )

        internal fun parseOpf(input: InputStream): Opf {
            val parser = newParser(input)
            var title: String? = null
            var coverId: String? = null
            var tocId: String? = null
            val manifest = LinkedHashMap<String, ManifestItem>()
            val spine = mutableListOf<String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "title" -> if (title == null) title = parser.nextText()
                    "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                        coverId = parser.getAttributeValue(null, "content")
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) {
                            manifest[id] = ManifestItem(
                                href,
                                parser.getAttributeValue(null, "media-type") ?: "",
                                (parser.getAttributeValue(null, "properties") ?: "").split(' ').filter { it.isNotEmpty() }
                            )
                        }
                    }
                    "spine" -> tocId = parser.getAttributeValue(null, "toc")
                    "itemref" -> {
                        val linear = parser.getAttributeValue(null, "linear")
                        parser.getAttributeValue(null, "idref")?.let { if (linear != "no") spine += it }
                    }
                }
            }
            return Opf(title, manifest, spine, coverId, tocId)
        }

        /** EPUB 2: navPoint > navLabel > text, e content@src. Primeiro título de cada arquivo vence. */
        internal fun parseNcx(input: InputStream, baseDir: String): Map<String, String> {
            val parser = newParser(input)
            val result = LinkedHashMap<String, String>()
            var label: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "text" -> label = parser.nextText().trim()
                    "content" -> {
                        val src = parser.getAttributeValue(null, "src")
                        val text = label
                        if (src != null && !text.isNullOrEmpty()) result.getOrPut(ZipPaths.resolve(baseDir, src)) { text }
                        label = null
                    }
                }
            }
            return result
        }

        /** EPUB 3: links <a href> dentro do <nav epub:type="toc">. */
        internal fun parseNav(input: InputStream, baseDir: String): Map<String, String> {
            val parser = newParser(input)
            val result = LinkedHashMap<String, String>()
            var navDepth = -1
            var inToc = false
            var href: String? = null
            val text = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "nav" -> if (!inToc) {
                            val type = parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                            // Sem epub:type, o primeiro <nav> do documento é tratado como sumário.
                            if (type == null || "toc" in type.split(' ')) {
                                inToc = true
                                navDepth = parser.depth
                            }
                        }
                        "a" -> if (inToc) {
                            href = parser.getAttributeValue(null, "href")
                            text.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> if (href != null) text.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "a" -> {
                            val target = href
                            val label = text.toString().replace(Regex("\\s+"), " ").trim()
                            if (target != null && label.isNotEmpty()) result.getOrPut(ZipPaths.resolve(baseDir, target)) { label }
                            href = null
                        }
                        "nav" -> if (inToc && parser.depth == navDepth) return result
                    }
                }
            }
            return result
        }
    }
}

/** Parágrafo de um DOCX já convertido para texto, com os trechos em negrito/itálico/sublinhado. */
data class DocxParagraph(
    val text: String,
    val kind: Kind,
    val styles: List<StyleRange>,
    /** Imagens do parágrafo, como caminhos dentro do ZIP (ex.: "word/media/image1.png"). */
    val images: List<String> = emptyList(),
    /** As mesmas imagens com tamanho e lado (figuras flutuantes, como logos de cabeçalho). */
    val figures: List<DocxFigure> = emptyList(),
    val align: Align? = null,
    /** Recuo e recuo da primeira linha, em "em" (tamanho da letra do documento). */
    val indent: Float = 0f,
    val firstLine: Float = 0f
) {
    enum class Kind { TITLE, HEADING1, HEADING2, HEADING3, LIST_ITEM, NORMAL }
    enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

    data class StyleRange(
        val start: Int,
        val end: Int,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean = false,
        /** Tamanho da letra em relação ao padrão do documento (null = igual). */
        val size: Float? = null,
        /** 1 = sobrescrito, -1 = subscrito, 0 = normal. */
        val vert: Int = 0
    )
}

/**
 * Figura de um DOCX: largura em pixels do Word (96 por polegada) e em fração da largura do texto
 * da página (para caber igual numa tela pequena), lado (figuras flutuantes) e se fica presa ao topo
 * ou ao pé da página (cabeçalho e rodapé desenhados no corpo).
 */
data class DocxFigure(
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
    val fraction: Float = 0f,
    val side: Side? = null,
    val place: Place = Place.INLINE
) {
    enum class Side { LEFT, CENTER, RIGHT }
    enum class Place { INLINE, START, END }
}

/** Célula de tabela: blocos, colunas e linhas ocupadas e a coluna onde começa. */
class DocxCell(val blocks: MutableList<DocxBlock> = mutableListOf(), var span: Int = 1, var rowSpan: Int = 1, var column: Int = 0)

/** Tabela de DOCX: largura das colunas (frações que somam 1), linhas e se tem bordas. */
class DocxTable(var columns: List<Float> = emptyList(), var rows: List<List<DocxCell>> = emptyList(), var borders: Boolean = false)

sealed class DocxBlock {
    data class Para(val p: DocxParagraph) : DocxBlock()
    class Table(val table: DocxTable) : DocxBlock()
}

/**
 * Leitor de DOCX (Office Open XML): títulos, listas, negrito, itálico, sublinhado, tachado,
 * tamanho da letra, alinhamento, recuos, figuras (com tamanho e lado) e tabelas (colunas, células
 * mescladas e bordas). Cores e fontes do documento ficam por conta do Conforto visual.
 */
object DocxReader {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val EMU_PER_PX = 9525f
    private const val TWIPS_PER_EM = 240f
    private val OFF = setOf("0", "false", "off", "none")

    /** Níveis de título e tamanho padrão da letra (meios-pontos: 24 = 12 pt). */
    data class Styles(val levels: Map<String, Int> = emptyMap(), val defaultSize: Int = 22)

    /** Parágrafos em sequência (tabelas viram um parágrafo por célula): busca e testes. */
    fun read(zip: ZipFile): List<DocxParagraph> = flatten(blocks(zip))

    fun blocks(zip: ZipFile): List<DocxBlock> {
        val styles = zip.getEntry("word/styles.xml")?.let { entry ->
            try {
                zip.getInputStream(entry).use { styleInfo(it) }
            } catch (e: Exception) {
                Styles()
            }
        } ?: Styles()
        val relations = zip.getEntry("word/_rels/document.xml.rels")?.let { entry ->
            try {
                zip.getInputStream(entry).use { relationships(it, "word") }
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
        val document = zip.getEntry("word/document.xml") ?: throw IllegalArgumentException("DOCX sem word/document.xml")
        val bytes = zip.getInputStream(document).use { it.readBytes() }
        return keep(blocks(bytes, styles, relations)) { zip.getEntry(it) != null }
    }

    private fun keep(list: List<DocxBlock>, exists: (String) -> Boolean): List<DocxBlock> = list.mapNotNull { block ->
        when (block) {
            is DocxBlock.Table -> {
                block.table.rows.forEach { row -> row.forEach { cell -> val kept = keep(cell.blocks, exists); cell.blocks.clear(); cell.blocks += kept } }
                block
            }
            is DocxBlock.Para -> {
                val p = block.p.copy(images = block.p.images.filter(exists), figures = block.p.figures.filter { exists(it.path) })
                if (p.text.isNotBlank() || p.images.isNotEmpty()) DocxBlock.Para(p) else null
            }
        }
    }

    fun flatten(list: List<DocxBlock>): List<DocxParagraph> = list.flatMap { block ->
        when (block) {
            is DocxBlock.Para -> listOf(block.p)
            is DocxBlock.Table -> block.table.rows.flatMap { row -> row.flatMap { flatten(it.blocks) } }
        }
    }

    /** Id da relação → caminho no ZIP (as imagens ficam em word/media e são citadas por id). */
    internal fun relationships(input: InputStream, baseDir: String): Map<String, String> {
        val parser = newParser(input)
        val result = HashMap<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id") ?: continue
                val target = parser.getAttributeValue(null, "Target") ?: continue
                if (parser.getAttributeValue(null, "TargetMode") == "External") continue
                result[id] = ZipPaths.resolve(baseDir, target)
            }
        }
        return result
    }

    /**
     * Mapeia o id de estilo para o nível de título. O id muda com o idioma do Word ("Ttulo1" no
     * Word em português), mas o nome interno dos estilos padrão é sempre "heading 1", "Title"...
     */
    internal fun headingLevels(input: InputStream): Map<String, Int> = styleInfo(input).levels

    /** Níveis de título e tamanho padrão da letra (docDefaults, ou o estilo padrão de parágrafo). */
    internal fun styleInfo(input: InputStream): Styles {
        val parser = newParser(input)
        val result = HashMap<String, Int>()
        var styleId: String? = null
        var inDefaults = false
        var inDefaultStyle = false
        var defaultSize = 20 // sem nada declarado, o Word usa 10 pt
        var styleSize: Int? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.namespace == W) {
                if (parser.name == "docDefaults") inDefaults = false
                if (parser.name == "style") inDefaultStyle = false
                continue
            }
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "docDefaults" -> inDefaults = true
                "style" -> {
                    styleId = parser.getAttributeValue(W, "styleId")
                    inDefaultStyle = parser.getAttributeValue(W, "type") == "paragraph" && parser.getAttributeValue(W, "default") in setOf("1", "true", "on")
                }
                "sz" -> parser.getAttributeValue(W, "val")?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    if (inDefaults) defaultSize = it else if (inDefaultStyle) styleSize = it
                }
                "name" -> {
                    val name = parser.getAttributeValue(W, "val")?.lowercase() ?: continue
                    val id = styleId ?: continue
                    when {
                        name == "title" -> result[id] = 0
                        name.startsWith("heading ") -> name.removePrefix("heading ").toIntOrNull()?.let { result[id] = it }
                    }
                }
                "outlineLvl" -> {
                    val id = styleId ?: continue
                    parser.getAttributeValue(W, "val")?.toIntOrNull()?.let { level -> result.getOrPut(id) { level + 1 } }
                }
            }
        }
        return Styles(result, styleSize ?: defaultSize)
    }

    /** Compatível com a versão anterior: só os parágrafos (tabelas viram um parágrafo por célula). */
    internal fun paragraphs(
        input: InputStream,
        headingLevels: Map<String, Int> = emptyMap(),
        relations: Map<String, String> = emptyMap()
    ): List<DocxParagraph> = flatten(blocks(input.readBytes(), Styles(headingLevels), relations))

    private class Drawing(val anchor: Boolean, val behind: Boolean) {
        var cx = 0L
        var cy = 0L
        var hRelative = "column"
        var hAlign: String? = null
        var hOffset: Long? = null
        var vRelative = "paragraph"
        var vOffset: Long? = null
        var axis: Char? = null
    }

    private class TableBuild {
        val table = DocxTable()
        val grid = mutableListOf<Int>()
        var row: MutableList<DocxCell>? = null
        val rows = mutableListOf<List<DocxCell>>()
        /** Por linha: para cada célula, a coluna onde começa e se continua uma mesclagem vertical. */
        val merges = mutableListOf<List<Pair<Int, Boolean>>>()
        var rowMerges = mutableListOf<Pair<Int, Boolean>>()
        var cell: DocxCell? = null
        var vMerge: String? = null
    }

    private fun clampEm(value: Float, max: Float) = (value.coerceIn(-max, max) * 100).roundToInt() / 100f

    internal fun blocks(xml: ByteArray, styles: Styles, relations: Map<String, String>): List<DocxBlock> {
        // Tamanho da página (fica no fim do documento): diz de que lado fica uma figura flutuante.
        val head = String(xml, Charsets.UTF_8)
        fun attr(tag: String, name: String) = Regex("<w:$tag\\b[^>]*\\bw:$name=\"(\\d+)\"").find(head)?.groupValues?.get(1)?.toLongOrNull()
        val pageWidth = (attr("pgSz", "w") ?: 11906L) * 635
        val pageHeight = (attr("pgSz", "h") ?: 16838L) * 635
        val marginLeft = (attr("pgMar", "left") ?: 1440L) * 635
        val marginRight = (attr("pgMar", "right") ?: 1440L) * 635
        val textWidth = (pageWidth - marginLeft - marginRight).coerceAtLeast(pageWidth / 2)

        val parser = newParser(java.io.ByteArrayInputStream(xml))
        val body = mutableListOf<DocxBlock>()
        val containers = ArrayDeque<MutableList<DocxBlock>>().apply { addLast(body) }
        val tables = ArrayDeque<TableBuild>()
        val images = mutableListOf<String>()
        val figures = mutableListOf<DocxFigure>()
        val text = StringBuilder()
        val ranges = mutableListOf<DocxParagraph.StyleRange>()
        var level: Int? = null
        var list = false
        var align: DocxParagraph.Align? = null
        var indent = 0f
        var firstLine = 0f
        // Caixas de texto põem parágrafos dentro de parágrafos: só o mais externo vira um parágrafo.
        var paragraphDepth = 0
        var inPPr = false
        var inRun = false
        var inRunProps = false
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var size: Float? = null
        var vert = 0
        var runStart = 0
        var drawing: Drawing? = null
        var inTcPr = false
        var inTblBorders = false

        fun flag(): Boolean {
            val value = parser.getAttributeValue(W, "val") ?: return true
            return value !in OFF
        }
        fun twips(vararg names: String): Float {
            for (name in names) parser.getAttributeValue(W, name)?.takeIf { it.isNotEmpty() }?.let { return it.toFloatOrNull() ?: 0f }
            return 0f
        }
        fun figureOf(path: String): DocxFigure {
            val d = drawing
            val width = if (d != null && d.cx > 0) (d.cx / EMU_PER_PX).roundToInt() else 0
            val height = if (d != null && d.cy > 0) (d.cy / EMU_PER_PX).roundToInt() else 0
            val fraction = if (d != null && d.cx > 0) (d.cx.toFloat() / textWidth).coerceAtMost(1f) else 0f
            if (d == null || !d.anchor) return DocxFigure(path, width, height, fraction)
            val alignH = d.hAlign?.lowercase()
            val side = when {
                alignH == "left" || alignH == "inside" -> DocxFigure.Side.LEFT
                alignH == "right" || alignH == "outside" -> DocxFigure.Side.RIGHT
                alignH == "center" -> DocxFigure.Side.CENTER
                d.hOffset != null -> {
                    val origin = if (d.hRelative == "page") 0L else marginLeft
                    val middle = (origin + d.hOffset!! + d.cx / 2f) / pageWidth
                    if (middle < 0.4f) DocxFigure.Side.LEFT else if (middle > 0.6f) DocxFigure.Side.RIGHT else DocxFigure.Side.CENTER
                }
                else -> DocxFigure.Side.CENTER
            }
            var place = DocxFigure.Place.INLINE
            val vOffset = d.vOffset
            if ((d.vRelative == "page" || d.vRelative == "margin") && vOffset != null) {
                val top = vOffset.toFloat() / pageHeight
                if (top > 0.75f) place = DocxFigure.Place.END else if (top < 0.12f) place = DocxFigure.Place.START
            }
            return DocxFigure(path, width, height, fraction, side, place)
        }

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val event = parser.eventType
            if (event == XmlPullParser.START_TAG && parser.namespace == WP) {
                when (parser.name) {
                    "anchor", "inline" -> drawing = Drawing(parser.name == "anchor", parser.getAttributeValue(null, "behindDoc") == "1")
                    "extent" -> drawing?.let {
                        it.cx = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: 0L
                        it.cy = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: 0L
                    }
                    "positionH", "positionV" -> drawing?.let {
                        it.axis = if (parser.name == "positionH") 'h' else 'v'
                        val relative = parser.getAttributeValue(null, "relativeFrom") ?: ""
                        if (it.axis == 'h') it.hRelative = relative else it.vRelative = relative
                    }
                    "align" -> drawing?.let { if (it.axis == 'h') it.hAlign = parser.nextText().trim() }
                    "posOffset" -> drawing?.let {
                        val value = parser.nextText().trim().toLongOrNull() ?: 0L
                        if (it.axis == 'h') it.hOffset = value else if (it.axis == 'v') it.vOffset = value
                    }
                }
                continue
            }
            if (event == XmlPullParser.END_TAG && parser.namespace == WP) {
                when (parser.name) {
                    "anchor", "inline" -> drawing = null
                    "positionH", "positionV" -> drawing?.axis = null
                }
                continue
            }
            if (event == XmlPullParser.START_TAG && paragraphDepth > 0 && (parser.name == "blip" || parser.name == "imagedata")) {
                // Figura do Word (DrawingML "blip" ou VML "imagedata"), citada pelo id da relação.
                val id = parser.getAttributeValue(R, "embed") ?: parser.getAttributeValue(R, "id")
                val path = relations[id]
                // Fundo atrás do texto do tamanho da página (marca d'água): fica de fora.
                val d = drawing
                val background = d != null && d.behind && d.cx > pageWidth / 2
                if (path != null && path !in images && !background) {
                    images += path
                    figures += figureOf(path)
                }
                continue
            }
            when (event) {
                XmlPullParser.START_TAG -> if (parser.namespace == W) {
                    val table = tables.lastOrNull()
                    when (parser.name) {
                        "tbl" -> if (paragraphDepth == 0) tables.addLast(TableBuild())
                        "gridCol" -> if (table != null && paragraphDepth == 0) table.grid += twips("w").roundToInt()
                        "tblBorders" -> inTblBorders = true
                        "tcPr" -> inTcPr = true
                        "top", "bottom", "left", "right", "start", "end", "insideH", "insideV" ->
                            if (table != null && (inTblBorders || inTcPr) && (parser.getAttributeValue(W, "val") ?: "nil") !in setOf("nil", "none")) table.table.borders = true
                        "tr" -> if (table != null && paragraphDepth == 0) {
                            table.row = mutableListOf()
                            table.rowMerges = mutableListOf()
                        }
                        "tc" -> if (table != null && paragraphDepth == 0) {
                            val cell = DocxCell()
                            table.cell = cell
                            table.vMerge = null
                            containers.addLast(cell.blocks)
                        }
                        "gridSpan" -> if (table?.cell != null && inTcPr) table.cell!!.span = (parser.getAttributeValue(W, "val")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                        "vMerge" -> if (table?.cell != null && inTcPr) table.vMerge = if (parser.getAttributeValue(W, "val") == "restart") "restart" else "continue"
                        "p" -> {
                            if (paragraphDepth == 0) {
                                text.setLength(0)
                                ranges.clear()
                                images.clear()
                                figures.clear()
                                level = null
                                list = false
                                align = null
                                indent = 0f
                                firstLine = 0f
                            } else if (text.isNotEmpty() && text.last() != '\n') {
                                text.append('\n') // texto de uma caixa de texto começa em linha própria
                            }
                            paragraphDepth++
                        }
                        "pPr" -> inPPr = paragraphDepth == 1
                        "jc" -> if (inPPr) align = when (parser.getAttributeValue(W, "val")) {
                            "center" -> DocxParagraph.Align.CENTER
                            "right", "end" -> DocxParagraph.Align.RIGHT
                            "both", "distribute" -> DocxParagraph.Align.JUSTIFY
                            else -> DocxParagraph.Align.LEFT
                        }
                        "ind" -> if (inPPr) {
                            indent = clampEm(twips("left", "start") / TWIPS_PER_EM, 8f)
                            val hanging = twips("hanging")
                            firstLine = clampEm((if (hanging != 0f) -hanging else twips("firstLine")) / TWIPS_PER_EM, 4f)
                        }
                        "pStyle" -> parser.getAttributeValue(W, "val")?.let { id ->
                            level = styles.levels[id] ?: Regex("(?i)^heading(\\d)$").find(id)?.groupValues?.get(1)?.toIntOrNull()
                                ?: if (id.equals("Title", true)) 0 else null
                        }
                        "outlineLvl" -> if (level == null) level = parser.getAttributeValue(W, "val")?.toIntOrNull()?.plus(1)
                        "numPr" -> list = true
                        "r" -> {
                            inRun = true
                            bold = false
                            italic = false
                            underline = false
                            strike = false
                            size = null
                            vert = 0
                            runStart = text.length
                        }
                        "rPr" -> inRunProps = inRun
                        "b" -> if (inRunProps) bold = flag()
                        "i" -> if (inRunProps) italic = flag()
                        "u" -> if (inRunProps) underline = flag()
                        "strike", "dstrike" -> if (inRunProps) strike = flag()
                        "sz" -> if (inRunProps) parser.getAttributeValue(W, "val")?.toFloatOrNull()?.takeIf { it > 0 && styles.defaultSize > 0 }?.let {
                            size = ((it / styles.defaultSize) * 100).roundToInt() / 100f
                            size = size!!.coerceIn(0.6f, 3f)
                        }
                        "vertAlign" -> if (inRunProps) vert = when (parser.getAttributeValue(W, "val")) {
                            "superscript" -> 1
                            "subscript" -> -1
                            else -> 0
                        }
                        "t" -> if (paragraphDepth > 0) text.append(parser.nextText())
                        "tab" -> if (inRun) text.append('\t')
                        "br", "cr" -> if (inRun) text.append('\n')
                    }
                }
                XmlPullParser.END_TAG -> if (parser.namespace == W) {
                    val table = tables.lastOrNull()
                    when (parser.name) {
                        "pPr" -> inPPr = false
                        "tblBorders" -> inTblBorders = false
                        "tcPr" -> inTcPr = false
                        "rPr" -> inRunProps = false
                        "r" -> {
                            inRun = false
                            val sized = size?.let { kotlin.math.abs(it - 1f) > 0.04f } == true
                            if (text.length > runStart && (bold || italic || underline || strike || sized || vert != 0)) {
                                ranges += DocxParagraph.StyleRange(runStart, text.length, bold, italic, underline, strike, if (sized) size else null, vert)
                            }
                        }
                        "p" -> {
                            paragraphDepth--
                            if (paragraphDepth > 0) {
                                text.append('\n')
                                continue
                            }
                            val kind = when {
                                level == 0 -> DocxParagraph.Kind.TITLE
                                level == 1 -> DocxParagraph.Kind.HEADING1
                                level == 2 -> DocxParagraph.Kind.HEADING2
                                level != null && level!! >= 3 -> DocxParagraph.Kind.HEADING3
                                list -> DocxParagraph.Kind.LIST_ITEM
                                else -> DocxParagraph.Kind.NORMAL
                            }
                            if (text.isNotBlank() || images.isNotEmpty()) {
                                containers.last() += DocxBlock.Para(
                                    DocxParagraph(
                                        if (text.isBlank()) "" else text.toString(), kind, ranges.toList(), images.toList(),
                                        figures.toList(), align, indent, firstLine
                                    )
                                )
                            }
                        }
                        "tc" -> if (table?.cell != null && paragraphDepth == 0) {
                            containers.removeLast()
                            val cell = table.cell!!
                            val start = table.row.orEmpty().sumOf { it.span }
                            cell.column = start
                            table.rowMerges += start to (table.vMerge == "continue")
                            table.row?.add(cell)
                            table.cell = null
                        }
                        "tr" -> if (table?.row != null && paragraphDepth == 0) {
                            table.rows += table.row!!
                            table.merges += table.rowMerges
                            table.row = null
                        }
                        "tbl" -> if (table != null && paragraphDepth == 0) {
                            tables.removeLast()
                            val built = finishTable(table)
                            if (built.rows.isNotEmpty()) containers.last() += DocxBlock.Table(built)
                        }
                    }
                }
            }
        }
        return body
    }

    /** Larguras das colunas e mesclagens verticais (a célula de cima ganha as linhas de baixo). */
    private fun finishTable(build: TableBuild): DocxTable {
        val table = build.table
        val count = maxOf(build.grid.size, build.rows.maxOfOrNull { row -> row.sumOf { it.span } } ?: 0, 1)
        val widths = (0 until count).map { build.grid.getOrNull(it)?.takeIf { w -> w > 0 } ?: 0 }
        val known = widths.filter { it > 0 }
        val fallback = if (known.isEmpty()) 1f else known.average().toFloat()
        val filled = widths.map { if (it > 0) it.toFloat() else fallback }
        val total = filled.sum()
        table.columns = filled.map { it / total }
        val owners = HashMap<Int, DocxCell>()
        table.rows = build.rows.mapIndexed { r, row ->
            row.filterIndexed { c, cell ->
                val (column, cont) = build.merges.getOrNull(r)?.getOrNull(c) ?: (cell.column to false)
                val owner = owners[column]
                if (cont && owner != null) {
                    owner.rowSpan++
                    owner.blocks += cell.blocks
                    false
                } else {
                    owners[column] = cell
                    true
                }
            }
        }
        return table
    }
}

/** Preparação do HTML de um capítulo de EPUB para o leitor. Kotlin puro, coberto por testes. */
object EpubHtml {
    const val POLICY = "script-src 'none'; object-src 'none'; connect-src 'none'; frame-src 'none'; form-action 'none'"
    private val headOpen = Regex("(?i)<head(\\s[^>]*)?>")
    private val headClose = Regex("(?i)</head\\s*>")

    fun prepare(html: String, css: String): String {
        val policy = "<meta http-equiv=\"Content-Security-Policy\" content=\"$POLICY\"/>"
        val style = "<style>$css</style><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
        val open = headOpen.find(html)
        if (open == null) {
            // Sem <head>: cria um logo depois de <html>, ou no começo do documento.
            val htmlTag = Regex("(?i)<html(\\s[^>]*)?>").find(html)
            val at = htmlTag?.range?.last?.plus(1) ?: 0
            return html.substring(0, at) + "<head>$policy$style</head>" + html.substring(at)
        }
        val withPolicy = html.substring(0, open.range.last + 1) + policy + html.substring(open.range.last + 1)
        val close = headClose.find(withPolicy) ?: return withPolicy + style
        return withPolicy.substring(0, close.range.first) + style + withPolicy.substring(close.range.first)
    }
}
