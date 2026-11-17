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
import kotlin.math.roundToLong

/** Conteúdo de texto já dividido em blocos para a lista do leitor. */
class TextContent(val chunks: List<CharSequence>, val truncated: Boolean = false)

/**
 * Bloco que é uma imagem (figura de um DOCX). Como texto ele é vazio, então busca e seleção o
 * ignoram; a lista mostra a imagem, decodificada sob demanda a partir de [file].
 */
/** Sublinhado de um termo do glossário; guarda o id para mostrar a definição ao tocar. */
class GlossarySpan(val termId: Long) : android.text.style.UnderlineSpan()

/**
 * Figuras de um DOCX lado a lado (como as logos de um cabeçalho): cada uma com a largura em fração
 * da largura do texto e o lado da página. Uma figura sem tamanho ocupa a largura toda.
 */
class ImageChunk(val figures: List<PlacedFigure>) : CharSequence by "" {
    constructor(file: java.io.File) : this(listOf(PlacedFigure(file)))
    val file: java.io.File get() = figures.first().file
    override fun toString() = ""
}

/** Figura pronta para mostrar: arquivo, largura (fração do texto), proporção altura/largura e lado. */
data class PlacedFigure(val file: java.io.File, val fraction: Float = 0f, val aspect: Float = 0f, val side: DocxFigure.Side? = null)

/** Tabela de DOCX como bloco da lista; o texto (células separadas por tabulação) serve para a busca. */
class TableChunk(val table: DocxTable, private val plain: String, val files: Map<String, java.io.File>) : CharSequence by plain {
    override fun toString() = plain
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

    /** Cobre a linha cortada no fim da página (modo páginas). */
    private val mask = PageMask(context)
    override val body: View = android.widget.FrameLayout(context).apply {
        addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(mask, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    override val footer: View = footerBar().apply {
        addView(barButton("A-", Palette.ink, "Diminuir letra") { changeTextSize(-1f) })
        addView(progressLabel, LinearLayout.LayoutParams(0, -2, 1f))
        addView(barButton("A+", Palette.ink, "Aumentar letra") { changeTextSize(1f) })
    }
    override val loaded get() = ready
    override val searchable = true

    init {
        applyColors()
        if (ReadingPrefs.paged(context)) {
            // No modo páginas o texto fica só dentro da janela (as margens não mostram pedaços de linha).
            list.clipToPadding = true
            list.viewTreeObserver.addOnPreDrawListener {
                updateMask()
                true
            }
        } else {
            mask.visibility = View.GONE
        }
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

    override fun dispatchTouch(event: android.view.MotionEvent): Boolean? {
        if (!ready) return null
        termTaps.onTouchEvent(event)
        return if (paged) edgeTaps.dispatch(event) else null
    }

    // ---------- glossário ----------

    private var glossary: List<GlossaryTerm> = emptyList()
    private var onTermTap: (GlossaryTerm) -> Unit = {}

    override fun setGlossary(terms: List<GlossaryTerm>, onTap: (GlossaryTerm) -> Unit) {
        glossary = terms
        onTermTap = onTap
        adapter.notifyDataSetChanged()
    }

    /** Toque simples numa palavra sublinhada mostra a definição (não atrapalha rolar nem selecionar). */
    private val termTaps = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
            termAt(e.rawX, e.rawY)?.let { id -> glossary.firstOrNull { it.id == id }?.let(onTermTap) }
            return false
        }
    })

    /** Id do termo sob o ponto da tela, se houver. */
    private fun termAt(screenX: Float, screenY: Float): Long? {
        if (glossary.isEmpty()) return null
        val location = IntArray(2)
        for (i in 0 until list.childCount) {
            val view = list.getChildAt(i) as? TextView ?: continue
            view.getLocationOnScreen(location)
            val x = screenX - location[0] - view.totalPaddingLeft
            val y = screenY - location[1] - view.totalPaddingTop
            if (y < 0 || y > view.height) continue
            val layout = view.layout ?: return null
            val line = layout.getLineForVertical(y.toInt())
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
            val offset = layout.getOffsetForHorizontal(line, x)
            val text = view.text as? Spanned ?: return null
            return text.getSpans(offset, offset, GlossarySpan::class.java).firstOrNull()?.termId
        }
        return null
    }

    // ---------- páginas com linhas inteiras ----------

    /** Faixa da tela onde o texto aparece no modo páginas (dentro das margens da lista). */
    private fun windowTop() = list.paddingTop.toFloat()
    private fun windowBottom() = (list.height - list.paddingBottom).toFloat()

    /** Linha [line] do bloco [position] da lista (imagem: linha 0 = o bloco inteiro). */
    private data class LineRef(val position: Int, val line: Int)

    /** Linhas visíveis (e um pouco além), em pixels da lista. Imagens contam como um bloco. */
    private fun visibleLines(refs: MutableList<LineRef>? = null): List<LineSpan> {
        val lines = ArrayList<LineSpan>()
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.bottom < -list.height || child.top > list.height * 2) continue
            val position = list.firstVisiblePosition + i
            val view = child as? TextView
            val layout = view?.layout
            if (view == null || layout == null || view.text.isEmpty()) {
                if (child.height > 0) {
                    lines += LineSpan(child.top.toFloat(), child.bottom.toFloat())
                    refs?.add(LineRef(position, 0))
                }
                continue
            }
            val base = child.top + view.totalPaddingTop
            for (line in 0 until layout.lineCount) {
                val top = base + layout.getLineTop(line)
                val height = layout.getLineBottom(line) - layout.getLineTop(line)
                // O espaço extra entre linhas fica embaixo de cada linha: não conta como texto.
                val glyphs = ((height - view.lineSpacingExtra) / view.lineSpacingMultiplier.coerceAtLeast(0.1f)).coerceIn(1f, height.toFloat())
                lines += LineSpan(top.toFloat(), top + glyphs)
                refs?.add(LineRef(position, line))
            }
        }
        return lines
    }

    /** Topo atual da linha [ref], se ela está na tela. */
    private fun lineTop(ref: LineRef): Float? {
        val child = list.getChildAt(ref.position - list.firstVisiblePosition) ?: return null
        val view = child as? TextView
        val layout = view?.layout ?: return child.top.toFloat()
        if (ref.line >= layout.lineCount) return null
        return (child.top + view.totalPaddingTop + layout.getLineTop(ref.line)).toFloat()
    }

    /**
     * Na última página o texto não sobe até o topo (acabou o livro): as linhas já lidas que
     * continuam na tela ficam cobertas até [readCover], enquanto a lista estiver nesta posição.
     */
    private var readCover = 0f
    private var readCoverAnchor: Pair<Int, Int>? = null

    private fun anchor(): Pair<Int, Int>? = list.getChildAt(0)?.let { list.firstVisiblePosition to it.top }

    private fun updateMask() {
        if (!ready || list.height == 0) return
        val top = windowTop()
        val bottom = windowBottom()
        if (readCoverAnchor != null && readCoverAnchor != anchor()) {
            readCover = 0f
            readCoverAnchor = null
        }
        mask.cover(readCover, LinePager.maskStart(visibleLines(), top, bottom))
    }

    /** Ao abrir ou pular para um ponto: começa com a linha inteira no topo. */
    private fun alignToLine() {
        if (!paged || !ready) return
        val delta = LinePager.alignDelta(visibleLines(), windowTop(), windowBottom()).roundToInt()
        if (delta != 0) scrollByAny(delta)
    }

    /** Vira a página: a próxima começa na linha seguinte à última inteira desta. */
    private fun turnPage(direction: Int) {
        val top = windowTop()
        val bottom = windowBottom()
        readCover = 0f
        readCoverAnchor = null
        if (direction > 0) {
            if (atEnd()) return
            val refs = ArrayList<LineRef>()
            val lines = visibleLines(refs)
            val delta = LinePager.nextDelta(lines, top, bottom)
            // A linha que deve abrir a próxima página (a cortada, ou a primeira abaixo da janela).
            val target = lines.indexOfFirst { it.top >= top + delta - 0.5f }.takeIf { it >= 0 }?.let { refs[it] }
            scrollByAny(delta.roundToInt())
            // Perto do fim a lista não sobe tudo: cobre as linhas já lidas acima da que abre a página.
            val now = target?.let { lineTop(it) }
            if (now != null && now > top + 1) {
                readCover = now
                readCoverAnchor = anchor()
            }
        } else {
            if (!list.canScrollVertically(-1)) return
            scrollByAny(-(bottom - top).roundToInt())
            val delta = LinePager.afterBackDelta(visibleLines(), top, bottom).roundToInt()
            if (delta != 0) scrollByAny(delta)
        }
        updateMask()
        flash(list)
    }

    // ---------- aparência ----------

    private inner class ChunkAdapter : BaseAdapter() {
        override fun getCount() = chunks.size
        override fun getItem(position: Int) = chunks[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (chunks[position]) {
            is ImageChunk -> 1
            is TableChunk -> 2
            else -> 0
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val chunk = chunks[position]
            if (chunk is ImageChunk) return figuresView(chunk, parent.width)
            if (chunk is TableChunk) return tableView(chunk, parent.width)
            val view = convertView as? TextView ?: TextView(context).apply { setTextIsSelectable(true) }
            styleText(view, parent.width)
            val mark = highlight
            val matches = Glossary.find(chunk, glossary)
            view.text = if ((mark != null && mark.first == position) || matches.isNotEmpty()) {
                SpannableString(chunk).apply {
                    for (m in matches) setSpan(GlossarySpan(m.termId), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (mark != null && mark.first == position) {
                        setSpan(BackgroundColorSpan(HIGHLIGHT), mark.second, mark.third, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
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

    /** Largura útil do texto (sem as margens do Conforto visual). */
    private fun textWidth(listWidth: Int): Pair<Int, Int> {
        val width = if (listWidth > 0) listWidth else context.resources.displayMetrics.widthPixels
        val textPx = textSizeSp * context.resources.displayMetrics.scaledDensity
        val side = Comfort.sidePadding(width, textPx, context.dp(comfort.margin.dp), comfort.lineWidth)
        return Pair(side, (width - 2 * side).coerceAtLeast(context.dp(120)))
    }

    /** Figuras lado a lado: as da esquerda, as do centro e as da direita, cada uma no seu tamanho. */
    private fun figuresView(chunk: ImageChunk, listWidth: Int): View {
        val (side, width) = textWidth(listWidth)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(side, context.dp(6), side, context.dp(6))
        }
        val groups = listOf(DocxFigure.Side.LEFT, DocxFigure.Side.CENTER, DocxFigure.Side.RIGHT).map { which ->
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = when (which) {
                    DocxFigure.Side.LEFT -> Gravity.START
                    DocxFigure.Side.CENTER -> Gravity.CENTER_HORIZONTAL
                    DocxFigure.Side.RIGHT -> Gravity.END
                }
            }
        }
        val single = chunk.figures.size == 1
        for (figure in chunk.figures) {
            val w = when {
                figure.fraction > 0f -> (figure.fraction * width).roundToInt()
                single -> width
                else -> width / 3
            }.coerceIn(context.dp(16), width)
            val view = imageView(figure, w)
            val group = groups[when (figure.side ?: if (single) DocxFigure.Side.CENTER else DocxFigure.Side.LEFT) {
                DocxFigure.Side.LEFT -> 0
                DocxFigure.Side.CENTER -> 1
                DocxFigure.Side.RIGHT -> 2
            }]
            group.addView(view, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (group.childCount > 0) leftMargin = context.dp(8)
            })
        }
        row.addView(groups[0], LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(groups[1], LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(groups[2], LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun imageView(figure: PlacedFigure, width: Int): ImageView {
        val view = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Imagem do documento"
        }
        val key = figure.file.path
        view.tag = key
        val cached = images.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
        } else {
            // Reserva o espaço da figura enquanto decodifica, para a rolagem não "pular".
            view.minimumHeight = if (figure.aspect > 0f) (width * figure.aspect).roundToInt() else context.resources.displayMetrics.heightPixels / 3
            decode(figure.file)
        }
        return view
    }

    /** Tabela com as colunas do documento, células mescladas e bordas (uma linha só entre células). */
    private fun tableView(chunk: TableChunk, listWidth: Int): View {
        val (side, width) = textWidth(listWidth)
        val table = chunk.table
        val line = Color.argb(170, Color.red(comfort.theme.text), Color.green(comfort.theme.text), Color.blue(comfort.theme.text))
        val stroke = context.dp(1).coerceAtLeast(1)
        val grid = android.widget.GridLayout(context).apply {
            columnCount = table.columns.size.coerceAtLeast(1)
            useDefaultMargins = false
            if (table.borders) {
                // A tabela desenha só a borda de cima e a da esquerda; cada célula, a da direita e a de baixo.
                background = android.graphics.drawable.InsetDrawable(border(line, stroke), 0, 0, -stroke, -stroke)
                setPadding(stroke, stroke, 0, 0)
            }
        }
        // Linhas ocupadas por mesclagens verticais: as células seguintes começam depois delas.
        table.rows.forEachIndexed { r, row ->
            for (cell in row) {
                val span = cell.span.coerceIn(1, grid.columnCount - cell.column.coerceAtMost(grid.columnCount - 1))
                val column = cell.column.coerceIn(0, grid.columnCount - 1)
                val weight = table.columns.drop(column).take(span).sum().coerceAtLeast(0.01f)
                val params = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(r, cell.rowSpan.coerceAtLeast(1), android.widget.GridLayout.FILL),
                    android.widget.GridLayout.spec(column, span, android.widget.GridLayout.FILL, weight)
                ).apply { this.width = 0 }
                grid.addView(cellView(cell, chunk.files, (width * weight).roundToInt(), line, stroke, table.borders), params)
            }
        }
        return android.widget.FrameLayout(context).apply {
            setPadding(side, context.dp(6), side, context.dp(10))
            addView(grid, android.widget.FrameLayout.LayoutParams(width, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun border(color: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(stroke, color)
    }

    private fun cellView(cell: DocxCell, files: Map<String, java.io.File>, cellWidth: Int, line: Int, stroke: Int, borders: Boolean): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // O fundo vem antes do espaço interno: trocar o fundo zera o espaço.
            if (borders) background = android.graphics.drawable.InsetDrawable(border(line, stroke), -stroke, -stroke, 0, 0)
            setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))
        }
        val textParagraphs = mutableListOf<DocxParagraph>()
        fun flushText() {
            if (textParagraphs.isEmpty()) return
            box.addView(TextView(context).apply {
                setTextIsSelectable(true)
                textSize = textSizeSp * 0.92f
                setTextColor(comfort.theme.text)
                typeface = ReaderFonts.forText(context, comfort.font)
                setLineSpacing(0f, (comfort.lineSpacing / 100f).coerceAtMost(1.4f))
                text = DocxLayout.styled(textParagraphs.toList(), "\n", maxIndent = 1.5f)
            })
            textParagraphs.clear()
        }
        for (paragraph in DocxReader.flatten(cell.blocks)) {
            val figures = paragraph.figures.mapNotNull { f -> files[f.path]?.let { PlacedFigure(it, f.fraction, if (f.width > 0) f.height.toFloat() / f.width else 0f, f.side) } }
            if (figures.isNotEmpty()) {
                flushText()
                for (figure in figures) {
                    val w = if (figure.fraction > 0f) (figure.fraction * cellWidth * 1.6f).roundToInt().coerceAtMost(cellWidth) else cellWidth
                    box.addView(imageView(figure, w), LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        gravity = when (paragraph.align) {
                            DocxParagraph.Align.CENTER -> Gravity.CENTER_HORIZONTAL
                            DocxParagraph.Align.RIGHT -> Gravity.END
                            else -> Gravity.START
                        }
                    })
                }
            }
            if (paragraph.text.isNotBlank()) textParagraphs += paragraph
        }
        flushText()
        return box
    }

    private fun decode(file: java.io.File) {
        val key = file.path
        if (!decoding.add(key)) return
        val width = context.resources.displayMetrics.widthPixels
        host.worker.execute {
            val bitmap = try {
                BookFiles.decodeScaled({ file.inputStream() }, width, PageSource.MAX_PIXELS / 2)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            onUi {
                decoding.remove(key)
                if (bitmap != null) {
                    images.put(key, bitmap)
                    fun update(view: View) {
                        if (view is ImageView && view.tag == key) {
                            view.minimumHeight = 0
                            view.setImageBitmap(bitmap)
                        } else if (view is ViewGroup) {
                            for (i in 0 until view.childCount) update(view.getChildAt(i))
                        }
                    }
                    for (i in 0 until list.childCount) update(list.getChildAt(i))
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
        mask.setColor(background)
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
    private fun textFraction(): Int = (topFraction() * 10_000).roundToInt().coerceIn(0, 10_000)

    /** Fração do texto (0 a 1) na linha de leitura, no alto da tela. No fim do livro, 1. */
    private fun topFraction(): Double {
        val count = chunks.size
        if (count == 0 || list.childCount == 0) return 0.0
        if (!list.canScrollVertically(1)) return 1.0
        val slot = readingSlot()
        val child = list.getChildAt(slot)
        val passed = ((list.paddingTop - child.top).toDouble() / child.height.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return ((list.firstVisiblePosition + slot + passed) / count).coerceIn(0.0, 1.0)
    }

    /** Fração do texto no pé da área de leitura (para saber quanto cabe numa tela). */
    private fun bottomFraction(): Double {
        val count = chunks.size
        if (count == 0 || list.childCount == 0) return 0.0
        val y = list.height - list.paddingBottom
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.bottom >= y) {
                val passed = ((y - child.top).toDouble() / child.height.coerceAtLeast(1)).coerceIn(0.0, 1.0)
                return ((list.firstVisiblePosition + i + passed) / count).coerceIn(0.0, 1.0)
            }
        }
        return ((list.lastVisiblePosition + 1).toDouble() / count).coerceIn(0.0, 1.0)
    }

    override val exactScale = EXACT_SCALE
    override fun exactPosition(): Long = (topFraction() * 10_000L * EXACT_SCALE).roundToLong()
    override fun goToExact(position: Long) = restoreExact(position.toDouble() / (10_000.0 * EXACT_SCALE))
    override fun screenSpan(): Long {
        if (!ready || list.childCount == 0) return 1
        // Do alto da tela até o pé, medido sem o atalho "fim do livro = 1".
        val count = chunks.size
        val slot = readingSlot()
        val child = list.getChildAt(slot)
        val passed = ((list.paddingTop - child.top).toDouble() / child.height.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val top = (list.firstVisiblePosition + slot + passed) / count
        return ((bottomFraction() - top) * 10_000L * EXACT_SCALE).roundToLong().coerceAtLeast(1)
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
    private fun restoreFraction(fraction: Int) = restoreExact(fraction.coerceIn(0, 10_000) / 10_000.0)

    /** Leva a lista até a fração [fraction] (0 a 1) do texto. */
    private fun restoreExact(fraction: Double) {
        val count = chunks.size
        if (count == 0) return
        // O epsilon evita que 5000/10000 de 76 blocos vire 37,999… e caia no bloco anterior.
        val exact = fraction.coerceIn(0.0, 1.0) * count + 1e-6
        val index = floor(exact).toInt().coerceIn(0, count - 1)
        val passed = (exact - index).coerceIn(0.0, 1.0)
        scrollToChunk(index) { child -> (passed * child.height).toInt() }
    }

    private fun scrollToChunk(index: Int, extra: (View) -> Int) {
        // O deslocamento já é medido a partir do padding do topo.
        list.setSelectionFromTop(index, 0)
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var layouts = 0
            private var fixes = 0
            override fun onGlobalLayout() {
                val child = list.getChildAt(index - list.firstVisiblePosition)
                val placed = child != null && index >= list.firstVisiblePosition
                if (!placed && ++layouts < MAX_RESTORE_LAYOUTS) return
                // Pedido antes da primeira medida, o ListView ignora a margem de cima: posiciona de novo.
                if (placed && child!!.top != list.paddingTop && ++fixes <= 2) {
                    list.setSelectionFromTop(index, 0)
                    return
                }
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (placed) {
                    val pixels = extra(child!!)
                    if (pixels > 0) scrollBy(pixels)
                }
                list.post {
                    alignToLine()
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
                DocxLayout.chunksOf(DocxReader.blocks(zip)) { path -> BookFiles.extractEntry(zip, path, mediaDir) }
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
     * Blocos prontos para a lista, na ordem do documento: texto formatado agrupado, figuras (lado a
     * lado, com o tamanho delas) e tabelas. [extract] grava a imagem (caminho no ZIP) num arquivo.
     */
    fun chunks(paragraphs: List<DocxParagraph>, extract: (String) -> java.io.File?): List<CharSequence> =
        chunksOf(paragraphs.map { DocxBlock.Para(it) }, extract)

    fun chunksOf(blocks: List<DocxBlock>, extract: (String) -> java.io.File?): List<CharSequence> {
        val files = HashMap<String, java.io.File?>()
        fun file(path: String) = files.getOrPut(path) { extract(path) }
        fun placed(figures: List<DocxFigure>) = figures.mapNotNull { f ->
            file(f.path)?.let { PlacedFigure(it, f.fraction, if (f.width > 0) f.height.toFloat() / f.width else 0f, f.side) }
        }
        // Figuras presas ao topo ou ao pé da página (cabeçalho e rodapé desenhados) vão para o começo ou o fim.
        val atStart = mutableListOf<DocxFigure>()
        val atEnd = mutableListOf<DocxFigure>()
        val list = blocks.map { block ->
            if (block !is DocxBlock.Para || block.p.figures.none { it.place != DocxFigure.Place.INLINE }) block
            else {
                atStart += block.p.figures.filter { it.place == DocxFigure.Place.START }
                atEnd += block.p.figures.filter { it.place == DocxFigure.Place.END }
                DocxBlock.Para(block.p.copy(figures = block.p.figures.filter { it.place == DocxFigure.Place.INLINE }))
            }
        }
        val result = mutableListOf<CharSequence>()
        val text = mutableListOf<DocxParagraph>()
        fun flush() {
            if (text.isNotEmpty()) group(text).forEach { result += styled(it) }
            text.clear()
        }
        fun figuresChunk(figures: List<DocxFigure>) {
            val ready = placed(figures)
            if (ready.isNotEmpty()) {
                flush()
                result += ImageChunk(ready)
            }
        }
        figuresChunk(atStart.map { it.copy(side = it.side ?: DocxFigure.Side.CENTER) })
        for (block in list) {
            when (block) {
                is DocxBlock.Table -> {
                    flush()
                    val used = HashMap<String, java.io.File>()
                    for (p in DocxReader.flatten(listOf(block))) for (f in p.figures) file(f.path)?.let { used[f.path] = it }
                    result += TableChunk(block.table, tableText(block.table), used)
                }
                is DocxBlock.Para -> {
                    val p = block.p
                    // Figuras flutuantes (logos) antes do texto; as que estão no meio do texto, depois.
                    val figures = p.figures.ifEmpty { p.images.map { DocxFigure(it) } }
                    figuresChunk(figures.filter { it.side != null })
                    if (p.text.isNotBlank()) text += p.copy(images = emptyList(), figures = emptyList())
                    val inline = figures.filter { it.side == null }
                    if (inline.isNotEmpty()) {
                        val side = when (p.align) {
                            DocxParagraph.Align.RIGHT -> DocxFigure.Side.RIGHT
                            DocxParagraph.Align.LEFT, DocxParagraph.Align.JUSTIFY -> if (inline.size > 1 || inline.any { it.fraction in 0.01f..0.6f }) DocxFigure.Side.LEFT else null
                            else -> null
                        }
                        figuresChunk(inline.map { it.copy(side = side) })
                    }
                }
            }
        }
        figuresChunk(atEnd.map { it.copy(side = it.side ?: DocxFigure.Side.CENTER) })
        flush()
        return result
    }

    /** Texto da tabela para a busca: células separadas por tabulação, linhas por quebra de linha. */
    fun tableText(table: DocxTable): String = table.rows.joinToString("\n") { row ->
        row.joinToString("\t") { cell -> DocxReader.flatten(cell.blocks).joinToString(" ") { prefix(it) + it.text }.trim() }
    }

    /** Texto de um bloco, sem estilos: o mesmo que [styled] mostra. */
    fun plain(group: List<DocxParagraph>): String = group.joinToString(SEPARATOR) { prefix(it) + it.text }

    private fun prefix(paragraph: DocxParagraph) = if (paragraph.kind == DocxParagraph.Kind.LIST_ITEM) "•  " else ""

    /**
     * Texto formatado de um bloco. Recuos ficam limitados a [maxIndent] "em": numa tela de celular,
     * o recuo de vários centímetros do Word (às vezes usado para centralizar à mão) espremeria o texto.
     */
    fun styled(group: List<DocxParagraph>, separator: String = SEPARATOR, maxIndent: Float = 4f): CharSequence {
        val out = SpannableStringBuilder()
        val em = 18f * android.content.res.Resources.getSystem().displayMetrics.scaledDensity
        for ((i, paragraph) in group.withIndex()) {
            if (i > 0) out.append(separator)
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
            // Alinhamento do documento: centro e direita (justificado e esquerda seguem o Conforto visual).
            when (paragraph.align) {
                DocxParagraph.Align.CENTER -> android.text.Layout.Alignment.ALIGN_CENTER
                DocxParagraph.Align.RIGHT -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                else -> null
            }?.let { out.setSpan(android.text.style.AlignmentSpan.Standard(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (paragraph.indent != 0f || paragraph.firstLine != 0f) {
                val indent = paragraph.indent.coerceIn(0f, maxIndent)
                val rest = (indent * em).roundToInt()
                val first = ((indent + paragraph.firstLine.coerceAtMost(maxIndent)) * em).roundToInt().coerceAtLeast(0)
                out.setSpan(android.text.style.LeadingMarginSpan.Standard(first, rest), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
                if (range.strike) out.setSpan(android.text.style.StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (range.vert != 0) {
                    out.setSpan(if (range.vert > 0) android.text.style.SuperscriptSpan() else android.text.style.SubscriptSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.75f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val relative = range.size
                if (relative != null && paragraph.kind == DocxParagraph.Kind.NORMAL) out.setSpan(RelativeSizeSpan(relative), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }
}
