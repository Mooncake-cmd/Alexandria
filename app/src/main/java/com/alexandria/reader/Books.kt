package com.alexandria.reader

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URLDecoder
import java.text.Normalizer
import java.util.zip.ZipFile

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
    val images: List<String> = emptyList()
) {
    enum class Kind { TITLE, HEADING1, HEADING2, HEADING3, LIST_ITEM, NORMAL }

    data class StyleRange(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean, val underline: Boolean)
}

/**
 * Leitor de DOCX (Office Open XML) para texto formatado: títulos, listas, negrito, itálico e
 * sublinhado. Tabelas viram uma linha por célula. Imagens, cores e fontes são ignoradas.
 */
object DocxReader {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun read(zip: ZipFile): List<DocxParagraph> {
        val styles = zip.getEntry("word/styles.xml")?.let { entry ->
            try {
                zip.getInputStream(entry).use { headingLevels(it) }
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
        val relations = zip.getEntry("word/_rels/document.xml.rels")?.let { entry ->
            try {
                zip.getInputStream(entry).use { relationships(it, "word") }
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
        val document = zip.getEntry("word/document.xml") ?: throw IllegalArgumentException("DOCX sem word/document.xml")
        return zip.getInputStream(document).use { paragraphs(it, styles, relations) }
            .map { p -> p.copy(images = p.images.filter { zip.getEntry(it) != null }) }
            .filter { it.text.isNotBlank() || it.images.isNotEmpty() }
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
    internal fun headingLevels(input: InputStream): Map<String, Int> {
        val parser = newParser(input)
        val result = HashMap<String, Int>()
        var styleId: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "style" -> styleId = parser.getAttributeValue(W, "styleId")
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
        return result
    }

    internal fun paragraphs(
        input: InputStream,
        headingLevels: Map<String, Int> = emptyMap(),
        relations: Map<String, String> = emptyMap()
    ): List<DocxParagraph> {
        val images = mutableListOf<String>()
        val parser = newParser(input)
        val result = mutableListOf<DocxParagraph>()
        val text = StringBuilder()
        val ranges = mutableListOf<DocxParagraph.StyleRange>()
        var level: Int? = null
        var list = false
        // Caixas de texto põem parágrafos dentro de parágrafos: só o mais externo vira um parágrafo.
        var paragraphDepth = 0
        var inRun = false
        var inRunProps = false
        var bold = false
        var italic = false
        var underline = false
        var runStart = 0

        fun flag(): Boolean {
            val value = parser.getAttributeValue(W, "val") ?: return true
            return value !in setOf("0", "false", "off", "none")
        }

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && paragraphDepth > 0 && (parser.name == "blip" || parser.name == "imagedata")) {
                // Figura do Word (DrawingML "blip" ou VML "imagedata"), citada pelo id da relação.
                val id = parser.getAttributeValue(R, "embed") ?: parser.getAttributeValue(R, "id")
                relations[id]?.let { if (it !in images) images += it }
                continue
            }
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.namespace == W) when (parser.name) {
                    "p" -> {
                        if (paragraphDepth == 0) {
                            text.setLength(0)
                            ranges.clear()
                            images.clear()
                            level = null
                            list = false
                        } else if (text.isNotEmpty() && text.last() != '\n') {
                            text.append('\n') // texto de uma caixa de texto começa em linha própria
                        }
                        paragraphDepth++
                    }
                    "pStyle" -> parser.getAttributeValue(W, "val")?.let { id ->
                        level = headingLevels[id] ?: Regex("(?i)^heading(\\d)$").find(id)?.groupValues?.get(1)?.toIntOrNull()
                            ?: if (id.equals("Title", true)) 0 else null
                    }
                    "outlineLvl" -> if (level == null) level = parser.getAttributeValue(W, "val")?.toIntOrNull()?.plus(1)
                    "numPr" -> list = true
                    "r" -> {
                        inRun = true
                        bold = false
                        italic = false
                        underline = false
                        runStart = text.length
                    }
                    "rPr" -> inRunProps = inRun
                    "b" -> if (inRunProps) bold = flag()
                    "i" -> if (inRunProps) italic = flag()
                    "u" -> if (inRunProps) underline = flag()
                    "t" -> if (paragraphDepth > 0) text.append(parser.nextText())
                    "tab" -> if (inRun) text.append('\t')
                    "br", "cr" -> if (inRun) text.append('\n')
                }
                XmlPullParser.END_TAG -> if (parser.namespace == W) when (parser.name) {
                    "rPr" -> inRunProps = false
                    "r" -> {
                        inRun = false
                        if (text.length > runStart && (bold || italic || underline)) {
                            ranges += DocxParagraph.StyleRange(runStart, text.length, bold, italic, underline)
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
                            result += DocxParagraph(if (text.isBlank()) "" else text.toString(), kind, ranges.toList(), images.toList())
                        }
                    }
                }
            }
        }
        return result
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
