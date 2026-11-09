package com.alexandria.reader

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import kotlin.math.floor
import kotlin.math.roundToInt

/** Conteúdo de texto já dividido em blocos para a lista do leitor. */
class TextContent(val chunks: List<CharSequence>, val truncated: Boolean = false)

/**
 * Bloco que é uma imagem (figura de um DOCX). Como texto ele é vazio, então busca e seleção o
 * ignoram; a lista mostra a imagem, decodificada sob demanda a partir de [file].
 */
class ImageChunk(val file: java.io.File) : CharSequence by "" {
    override fun toString() = ""
}

/**
 * Visualizador de texto corrido (TXT e DOCX). O texto fica em blocos numa lista, que mede e
 * desenha só o que aparece na tela. A posição é uma fração de 0 a 10000 do texto inteiro.
 */
class TextViewer(host: ReaderHost, private val load: (LibraryItem) -> TextContent) : BookViewer(host) {
    private var chunks: List<CharSequence> = emptyList()
    private var textSizeSp = ReadingPrefs.textSize(context)
    private var comfort = ComfortStore.load(context)
    private var ready = false
    /** Bloco, início e fim do resultado de busca destacado. */
    private var highlight: Triple<Int, Int, Int>? = null

    private val adapter = ChunkAdapter()
    private val list = ListView(context).apply {
        adapter = this@TextViewer.adapter
        divider = null
        dividerHeight = 0
        selector = ColorDrawable(Color.TRANSPARENT)
        isVerticalFadingEdgeEnabled = false
        clipToPadding = false
        setPadding(0, context.dp(16), 0, context.dp(24))
        setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (ready) {
                    updateProgressLabel()
                    host.onPositionChanged()
                }
            }
        })
    }
    private val progressLabel = context.label("Carregando…", 12f, Palette.muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, context.dp(10), 0, context.dp(10))
    }

    override val body: View = list
    override val footer: View = footerBar().apply {
        addView(barButton("A-", Palette.ink, "Diminuir letra") { changeTextSize(-1f) })
        addView(progressLabel, LinearLayout.LayoutParams(0, -2, 1f))
        addView(barButton("A+", Palette.ink, "Aumentar letra") { changeTextSize(1f) })
    }
    override val loaded get() = ready
    override val searchable = true

    init {
        applyColors()
    }

    override fun open(item: LibraryItem, start: Int) {
        host.worker.execute {
            val content = try {
                load(item)
            } catch (e: SecurityException) {
                onUi { host.fail("Sem permissão para abrir este arquivo. Remova-o e importe de novo.") }
                return@execute
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            onUi {
                when {
                    content == null -> host.fail("Não foi possível abrir este documento.")
                    content.chunks.isEmpty() -> host.fail("Este documento está vazio.")
                    else -> {
                        chunks = content.chunks
                        adapter.notifyDataSetChanged()
                        ready = true
                        if (content.truncated) {
                            Toast.makeText(context, "Arquivo muito grande: exibindo só o começo.", Toast.LENGTH_LONG).show()
                        }
                        restoreFraction(start)
                    }
                }
            }
        }
    }

    override fun position(): Int = textFraction()
    override fun goTo(position: Int) = restoreFraction(position)
    override fun atEnd() = ready && !list.canScrollVertically(1)
    override fun describe(position: Int) = "${position / 100}%"

    override fun menuActions(): List<Pair<String, () -> Unit>> = emptyList()

    // ---------- modo páginas ----------

    private val paged = ReadingPrefs.paged(context)
    private val edgeTaps = EdgeTaps(context, list, { paged }) { turnPage(it) }

    override fun dispatchTouch(event: android.view.MotionEvent): Boolean? = if (paged && ready) edgeTaps.dispatch(event) else null

    /** "Vira a página": rola uma tela, deixando uma linha e meia da tela anterior como contexto. */
    private fun turnPage(direction: Int) {
        val visible = list.height - list.paddingTop - list.paddingBottom
        val line = (textSizeSp * context.resources.displayMetrics.scaledDensity * 1.35f).toInt()
        val step = PageTurn.scrollStep(visible, (line * 1.5f).toInt())
        if (!list.canScrollVertically(direction)) return
        scrollByAny(step * direction)
        flash(list)
    }

    // ---------- aparência ----------

    private inner class ChunkAdapter : BaseAdapter() {
        override fun getCount() = chunks.size
        override fun getItem(position: Int) = chunks[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (chunks[position] is ImageChunk) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val chunk = chunks[position]
            if (chunk is ImageChunk) return imageView(chunk, convertView as? ImageView)
            val view = convertView as? TextView ?: TextView(context).apply { setTextIsSelectable(true) }
            styleText(view, parent.width)
            val mark = highlight
            view.text = if (mark != null && mark.first == position) {
                SpannableString(chunk).apply {
                    setSpan(BackgroundColorSpan(HIGHLIGHT), mark.second, mark.third, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                chunk
            }
            return view
        }
    }

    // ---------- imagens (DOCX) ----------

    /** Bitmaps decodificados recentemente, limitados a ~1/8 da memória do app. */
    private val images = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
    private val decoding = mutableSetOf<String>()

    private fun imageView(chunk: ImageChunk, recycled: ImageView?): ImageView {
        val view = recycled ?: ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            contentDescription = "Imagem do documento"
        }
        val key = chunk.file.path
        view.tag = key
        val cached = images.get(key)
        if (cached != null) {
            view.minimumHeight = 0
            view.setImageBitmap(cached)
        } else {
            view.setImageDrawable(null)
            // Reserva espaço enquanto decodifica, para a rolagem não "pular".
            view.minimumHeight = context.resources.displayMetrics.heightPixels / 2
            decode(chunk)
        }
        return view
    }

    private fun decode(chunk: ImageChunk) {
        val key = chunk.file.path
        if (!decoding.add(key)) return
        val width = context.resources.displayMetrics.widthPixels
        host.worker.execute {
            val bitmap = try {
                BookFiles.decodeScaled({ chunk.file.inputStream() }, width, PageSource.MAX_PIXELS / 2)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            onUi {
                decoding.remove(key)
                if (bitmap != null) {
                    images.put(key, bitmap)
                    for (i in 0 until list.childCount) {
                        val child = list.getChildAt(i) as? ImageView ?: continue
                        if (child.tag == key) {
                            child.minimumHeight = 0
                            child.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }
    }

    /** Cor, fonte, espaçamentos, margens, largura da linha e alinhamento do painel "Conforto visual". */
    private fun styleText(view: TextView, listWidth: Int) {
        val c = comfort
        view.textSize = textSizeSp
        view.setTextColor(c.theme.text)
        view.setLinkTextColor(c.theme.link)
        view.typeface = ReaderFonts.forText(context, c.font)
        view.setLineSpacing(0f, c.lineSpacing / 100f)
        view.letterSpacing = c.letterSpacing / 100f
        val textPx = textSizeSp * context.resources.displayMetrics.scaledDensity
        val side = Comfort.sidePadding(listWidth, textPx, context.dp(c.margin.dp), c.lineWidth)
        view.setPadding(side, 0, side, 0)
        setJustified(view, c.justify)
    }

    /** Texto justificado existe a partir do Android 8; antes disso fica sempre à esquerda. */
    private fun setJustified(view: TextView, justify: Boolean) {
        when {
            android.os.Build.VERSION.SDK_INT >= 29 -> view.justificationMode =
                if (justify) android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD else android.graphics.text.LineBreaker.JUSTIFICATION_MODE_NONE
            android.os.Build.VERSION.SDK_INT >= 26 -> setJustifiedLegacy(view, justify)
        }
    }

    @android.annotation.SuppressLint("WrongConstant") // Android 8 e 9: as constantes ainda ficavam em Layout (mesmos valores)
    @android.annotation.TargetApi(26)
    private fun setJustifiedLegacy(view: TextView, justify: Boolean) {
        view.justificationMode = if (justify) android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
    }

    private fun applyColors() {
        val background = comfort.theme.background
        list.setBackgroundColor(background)
        list.cacheColorHint = background
    }

    override fun applyComfort(comfort: Comfort) {
        val fraction = if (ready) textFraction() else 0
        this.comfort = comfort
        applyColors()
        adapter.notifyDataSetChanged()
        if (ready) restoreFraction(fraction)
    }

    private fun changeTextSize(delta: Float) {
        val fraction = textFraction()
        textSizeSp = (textSizeSp + delta).coerceIn(12f, 32f)
        ReadingPrefs.setTextSize(context, textSizeSp)
        adapter.notifyDataSetChanged()
        if (ready) restoreFraction(fraction)
    }

    // ---------- posição ----------

    /**
     * Posição de leitura como fração de 0 a 10000: bloco visível no topo mais quanto dele já
     * passou. Não depende do tamanho da fonte nem da rotação, e é o mesmo formato da versão 0.2.
     */
    private fun textFraction(): Int {
        val count = chunks.size
        if (count == 0 || list.childCount == 0) return 0
        if (!list.canScrollVertically(1)) return 10_000
        val slot = readingSlot()
        val child = list.getChildAt(slot)
        val passed = ((list.paddingTop - child.top).toDouble() / child.height.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return ((list.firstVisiblePosition + slot + passed) / count * 10_000).roundToInt().coerceIn(0, 10_000)
    }

    /**
     * Filho da lista que está na linha de leitura (logo abaixo do padding do topo). Como o padding
     * não corta o conteúdo, o bloco anterior pode aparecer nele e ser o primeiro filho visível.
     */
    private fun readingSlot(): Int {
        var slot = 0
        while (slot < list.childCount - 1 && list.getChildAt(slot).bottom <= list.paddingTop) slot++
        return slot
    }

    /** Leva a lista até [fraction]: posiciona o bloco e, depois do layout, rola dentro dele. */
    private fun restoreFraction(fraction: Int) {
        val count = chunks.size
        if (count == 0) return
        // O epsilon evita que 5000/10000 de 76 blocos vire 37,999… e caia no bloco anterior.
        val exact = fraction.coerceIn(0, 10_000).toDouble() * count / 10_000 + 1e-6
        val index = floor(exact).toInt().coerceIn(0, count - 1)
        val passed = (exact - index).coerceIn(0.0, 1.0)
        scrollToChunk(index) { child -> (passed * child.height).toInt() }
    }

    /**
     * Posiciona o bloco [index] no topo; quando o layout termina (e a altura dele é conhecida),
     * rola mais [extra] pixels dentro dele.
     */
    private fun scrollToChunk(index: Int, extra: (View) -> Int) {
        // O deslocamento já é medido a partir do padding do topo.
        list.setSelectionFromTop(index, 0)
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var layouts = 0
            override fun onGlobalLayout() {
                val child = list.getChildAt(index - list.firstVisiblePosition)
                val placed = child != null && index >= list.firstVisiblePosition
                if (!placed && ++layouts < MAX_RESTORE_LAYOUTS) return
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (placed) {
                    val pixels = extra(child!!)
                    if (pixels > 0) scrollBy(pixels)
                }
                list.post {
                    updateProgressLabel()
                    host.onPositionChanged()
                }
            }
        })
        list.requestLayout()
    }

    /** Rola para baixo (positivo) ou para cima (negativo), em passos que o ListView aceita. */
    private fun scrollByAny(pixels: Int) {
        val step = (list.height - list.paddingTop - list.paddingBottom - 2).coerceAtLeast(1)
        var remaining = pixels
        val direction = if (pixels > 0) 1 else -1
        while (remaining != 0 && list.canScrollVertically(direction)) {
            val delta = if (direction > 0) minOf(step, remaining) else maxOf(-step, remaining)
            list.scrollListBy(delta)
            remaining -= delta
        }
    }

    /** O ListView limita cada rolagem à própria altura; blocos maiores que a tela pedem vários passos. */
    private fun scrollBy(pixels: Int) {
        val step = (list.height - list.paddingTop - list.paddingBottom - 2).coerceAtLeast(1)
        var remaining = pixels
        while (remaining > 0 && list.canScrollVertically(1)) {
            val delta = minOf(step, remaining)
            list.scrollListBy(delta)
            remaining -= delta
        }
    }

    private fun updateProgressLabel() {
        progressLabel.text = "${textFraction() / 100}% lido"
    }

    // ---------- seleção e busca ----------

    override fun selection(done: (excerpt: String, position: Int) -> Unit): Boolean {
        for (slot in 0 until list.childCount) {
            val view = list.getChildAt(slot) as? TextView ?: continue
            if (!view.hasSelection()) continue
            val start = minOf(view.selectionStart, view.selectionEnd)
            val end = maxOf(view.selectionStart, view.selectionEnd)
            val chunk = list.firstVisiblePosition + slot
            val length = view.text.length.coerceAtLeast(1)
            val position = ((chunk + start.toDouble() / length) / chunks.size * 10_000).roundToInt().coerceIn(0, 10_000)
            done(view.text.subSequence(start, end).toString(), position)
            return true
        }
        return false
    }

    override fun search(term: String, done: (List<SearchHit>) -> Unit) {
        val snapshot = chunks
        host.worker.execute {
            val hits = mutableListOf<SearchHit>()
            for ((index, chunk) in snapshot.withIndex()) {
                for (offset in TextSearch.findAll(chunk, term)) {
                    val position = ((index + offset.toDouble() / chunk.length.coerceAtLeast(1)) / snapshot.size * 10_000).roundToInt()
                    hits += SearchHit(position, excerpt(chunk, offset, term.trim().length), index, offset, term.trim().length)
                    if (hits.size >= MAX_HITS) break
                }
                if (hits.size >= MAX_HITS) break
            }
            onUi { done(hits) }
        }
    }

    override fun showHit(hit: SearchHit, term: String) {
        highlight = Triple(hit.chunk, hit.offset, (hit.offset + hit.length).coerceAtMost(chunks[hit.chunk].length))
        adapter.notifyDataSetChanged()
        scrollToChunk(hit.chunk) { child ->
            val layout = (child as? TextView)?.layout ?: return@scrollToChunk 0
            // Deixa umas linhas de contexto acima do trecho encontrado.
            (layout.getLineTop(layout.getLineForOffset(hit.offset)) - context.dp(48)).coerceAtLeast(0)
        }
    }

    override fun clearSearch() {
        highlight = null
        adapter.notifyDataSetChanged()
    }

    companion object {
        private const val MAX_RESTORE_LAYOUTS = 5
        private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
        private const val MAX_HITS = 500
        private val HIGHLIGHT = Color.argb(140, 198, 154, 82)

        fun excerpt(text: CharSequence, offset: Int, length: Int): String {
            val start = (offset - 40).coerceAtLeast(0)
            val end = (offset + length + 60).coerceAtMost(text.length)
            val body = text.subSequence(start, end).toString().replace(Regex("\\s+"), " ").trim()
            return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
        }

        fun txt(host: ReaderHost) = TextViewer(host) { item -> readTxt(host, Uri.parse(item.uri)) }

        fun docx(host: ReaderHost) = TextViewer(host) { item ->
            val mediaDir = BookFiles.docxMediaDir(host.activity, item.id)
            val chunks = ZipFile(BookFiles.localCopy(host.activity, item)).use { zip ->
                DocxLayout.chunks(DocxReader.read(zip)) { path -> BookFiles.extractEntry(zip, path, mediaDir) }
            }
            TextContent(chunks)
        }

        /** Roda na thread de fundo. */
        private fun readTxt(host: ReaderHost, uri: Uri): TextContent {
            val input = host.activity.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Sem arquivo")
            val buffer = ByteArrayOutputStream()
            var truncated = false
            input.use { stream ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    if (buffer.size() + read > MAX_TEXT_BYTES) {
                        buffer.write(chunk, 0, MAX_TEXT_BYTES - buffer.size())
                        truncated = true
                        break
                    }
                    buffer.write(chunk, 0, read)
                }
            }
            val text = TextDecoding.decode(buffer.toByteArray(), truncated)
            return TextContent(TextChunker.chunk(text), truncated)
        }
    }
}

/** Monta os blocos de um DOCX: agrupa parágrafos e aplica títulos, listas e estilos. */
object DocxLayout {
    private const val SEPARATOR = "\n\n"

    /** Agrupa parágrafos inteiros em blocos de cerca de [target] caracteres (um parágrafo nunca é partido). */
    fun group(paragraphs: List<DocxParagraph>, target: Int = TextChunker.TARGET_CHARS): List<List<DocxParagraph>> {
        val groups = mutableListOf<List<DocxParagraph>>()
        var current = mutableListOf<DocxParagraph>()
        var size = 0
        for (paragraph in paragraphs) {
            // Títulos começam bloco novo, para não ficarem colados no fim do bloco anterior.
            val heading = paragraph.kind != DocxParagraph.Kind.NORMAL && paragraph.kind != DocxParagraph.Kind.LIST_ITEM
            if (current.isNotEmpty() && (size + paragraph.text.length > target || heading)) {
                groups += current
                current = mutableListOf()
                size = 0
            }
            current += paragraph
            size += paragraph.text.length + SEPARATOR.length
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /**
     * Blocos prontos para a lista: texto formatado agrupado e cada imagem num bloco próprio, na
     * ordem do documento. [extract] grava a imagem (caminho no ZIP) num arquivo e o devolve.
     */
    fun chunks(paragraphs: List<DocxParagraph>, extract: (String) -> java.io.File?): List<CharSequence> {
        val result = mutableListOf<CharSequence>()
        val text = mutableListOf<DocxParagraph>()
        fun flush() {
            if (text.isNotEmpty()) group(text).forEach { result += styled(it) }
            text.clear()
        }
        for (paragraph in paragraphs) {
            if (paragraph.text.isNotBlank()) text += paragraph.copy(images = emptyList())
            if (paragraph.images.isNotEmpty()) {
                flush()
                paragraph.images.mapNotNull(extract).forEach { result += ImageChunk(it) }
            }
        }
        flush()
        return result
    }

    /** Texto de um bloco, sem estilos: o mesmo que [styled] mostra. */
    fun plain(group: List<DocxParagraph>): String = group.joinToString(SEPARATOR) { prefix(it) + it.text }

    private fun prefix(paragraph: DocxParagraph) = if (paragraph.kind == DocxParagraph.Kind.LIST_ITEM) "•  " else ""

    fun styled(group: List<DocxParagraph>): CharSequence {
        val out = SpannableStringBuilder()
        for ((i, paragraph) in group.withIndex()) {
            if (i > 0) out.append(SEPARATOR)
            val start = out.length
            out.append(prefix(paragraph))
            val textStart = out.length
            out.append(paragraph.text)
            val end = out.length
            val size = when (paragraph.kind) {
                DocxParagraph.Kind.TITLE -> 1.6f
                DocxParagraph.Kind.HEADING1 -> 1.4f
                DocxParagraph.Kind.HEADING2 -> 1.2f
                DocxParagraph.Kind.HEADING3 -> 1.1f
                else -> 1f
            }
            if (size != 1f) {
                out.setSpan(RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (range in paragraph.styles) {
                val s = (textStart + range.start).coerceAtMost(end)
                val e = (textStart + range.end).coerceAtMost(end)
                if (s >= e) continue
                val style = when {
                    range.bold && range.italic -> Typeface.BOLD_ITALIC
                    range.bold -> Typeface.BOLD
                    range.italic -> Typeface.ITALIC
                    else -> null
                }
                if (style != null) out.setSpan(StyleSpan(style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (range.underline) out.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }
}
