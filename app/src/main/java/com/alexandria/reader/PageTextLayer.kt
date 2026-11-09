package com.alexandria.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast

/**
 * Camada de texto sobre as páginas de PDF: seleção (toque longo + alças), sublinhado dos termos do
 * glossário, destaque da busca e a barra flutuante "Copiar" (onde o leitor acrescenta "Definir
 * termo" e "Anotar"). As posições são frações da página; quem desenha informa o retângulo da página.
 */
class PageTextLayer(
    private val host: ReaderHost,
    /** Lê o texto de uma página. Roda na thread de fundo do leitor. */
    private val loadText: (Int) -> PageText?,
    /** Pede para as views da página redesenharem. */
    private val invalidate: () -> Unit
) {
    private val context: Context get() = host.activity
    private val texts = HashMap<Int, PageText>()
    private val loading = HashSet<Int>()
    private val termRects = HashMap<Int, List<Pair<PageRect, Long>>>()
    private var terms: List<GlossaryTerm> = emptyList()
    private var onTermTap: (GlossaryTerm) -> Unit = {}

    /** Seleção numa página: [anchor] fica parado, [focus] acompanha o dedo (índices de caracteres). */
    data class Selection(val page: Int, val anchor: Int, val focus: Int) {
        val start get() = minOf(anchor, focus)
        val end get() = maxOf(anchor, focus)
    }

    var selection: Selection? = null
        private set
    private var searchMark: Triple<Int, Int, Int>? = null // página, início, fim (deslocamentos no texto)
    private var actionMode: ActionMode? = null

    private val density = context.resources.displayMetrics.density
    private val selectionPaint = Paint().apply { color = Color.argb(90, 66, 133, 244) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(66, 133, 244) }
    private val searchPaint = Paint().apply { color = Color.argb(110, 230, 180, 60) }
    private val termPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.gold
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val handleRadius = 9f * density

    // ---------- texto das páginas ----------

    fun text(page: Int): PageText? = texts[page]

    /** Garante o texto da página (carrega em segundo plano na primeira vez). */
    fun ensure(page: Int) {
        if (page < 0 || texts.containsKey(page) || !loading.add(page)) return
        host.worker.execute {
            val text = try {
                loadText(page)
            } catch (e: Exception) {
                null
            }
            host.activity.runOnUiThread {
                if (host.closed) return@runOnUiThread
                loading.remove(page)
                texts[page] = text ?: PageText(emptyList())
                termRects.remove(page)
                invalidate()
            }
        }
    }

    fun setGlossary(terms: List<GlossaryTerm>, onTap: (GlossaryTerm) -> Unit) {
        this.terms = terms
        onTermTap = onTap
        termRects.clear()
        invalidate()
    }

    private fun termsOn(page: Int): List<Pair<PageRect, Long>> {
        val text = texts[page] ?: return emptyList()
        return termRects.getOrPut(page) {
            Glossary.find(text.string, terms).flatMap { m -> text.rectsForOffsets(m.start, m.end).map { it to m.termId } }
        }
    }

    fun setSearchMark(page: Int, start: Int, end: Int) {
        searchMark = Triple(page, start, end)
        ensure(page)
        invalidate()
    }

    fun clearSearchMark() {
        searchMark = null
        invalidate()
    }

    // ---------- desenho ----------

    private fun map(r: PageRect, dst: RectF) = RectF(
        dst.left + r.left * dst.width(), dst.top + r.top * dst.height(),
        dst.left + r.right * dst.width(), dst.top + r.bottom * dst.height()
    )

    /** Desenha a camada da [page], que ocupa [dst] na view. */
    fun draw(canvas: Canvas, page: Int, dst: RectF) {
        val text = texts[page] ?: return
        for ((rect, _) in termsOn(page)) {
            val r = map(rect, dst)
            val y = r.bottom + termPaint.strokeWidth
            canvas.drawLine(r.left, y, r.right, y, termPaint)
        }
        searchMark?.takeIf { it.first == page }?.let { (_, s, e) ->
            text.rectsForOffsets(s, e).forEach { canvas.drawRect(map(it, dst), searchPaint) }
        }
        val sel = selection?.takeIf { it.page == page } ?: return
        val rects = text.rects(sel.start, sel.end)
        rects.forEach { canvas.drawRect(map(it, dst), selectionPaint) }
        handlePositions(text, sel, dst)?.let { (start, end) ->
            drawHandle(canvas, start.first, start.second)
            drawHandle(canvas, end.first, end.second)
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawRect(x - density, y - handleRadius * 1.6f, x + density, y, handlePaint)
        canvas.drawCircle(x, y + handleRadius * 0.6f, handleRadius, handlePaint)
    }

    /** Alças: início (canto de baixo-esquerdo do 1º caractere) e fim (canto de baixo-direito do último). */
    private fun handlePositions(text: PageText, sel: Selection, dst: RectF): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        val first = text.chars.getOrNull(sel.start) ?: return null
        val last = text.chars.getOrNull(sel.end) ?: return null
        return Pair(
            dst.left + first.left * dst.width() to dst.top + first.bottom * dst.height(),
            dst.left + last.right * dst.width() to dst.top + last.bottom * dst.height()
        )
    }

    // ---------- toques ----------

    /** Toque longo no ponto (fração da página): seleciona a palavra. true se havia texto ali. */
    fun selectWordAt(page: Int, x: Float, y: Float): Boolean {
        val text = texts[page] ?: run {
            ensure(page)
            return false
        }
        val index = text.charAt(x, y) ?: return false
        val word = text.wordAt(index) ?: (index..index)
        selection = Selection(page, word.first, word.last)
        invalidate()
        return true
    }

    /**
     * Qual alça está sob o ponto (em pixels da view, com a página em [dst]): -1 início, 1 fim, 0 nenhuma.
     * Ao achar, a alça arrastada passa a ser o "foco" da seleção.
     */
    fun grabHandle(page: Int, px: Float, py: Float, dst: RectF): Int {
        val sel = selection?.takeIf { it.page == page } ?: return 0
        val text = texts[page] ?: return 0
        val (start, end) = handlePositions(text, sel, dst) ?: return 0
        val reach = handleRadius * 2.6f
        val dStart = distance(px, py, start.first, start.second + handleRadius * 0.6f)
        val dEnd = distance(px, py, end.first, end.second + handleRadius * 0.6f)
        return when {
            dEnd <= reach && dEnd <= dStart -> {
                selection = Selection(page, sel.start, sel.end)
                1
            }
            dStart <= reach -> {
                selection = Selection(page, sel.end, sel.start)
                -1
            }
            else -> 0
        }
    }

    /** Arrastando uma alça (ou o dedo depois do toque longo): move o foco para o caractere sob o ponto. */
    fun dragTo(page: Int, x: Float, y: Float) {
        val sel = selection?.takeIf { it.page == page } ?: return
        val text = texts[page] ?: return
        val index = text.charAt(x, y, maxDistance = 0.2f) ?: return
        if (index != sel.focus) {
            selection = sel.copy(focus = index)
            invalidate()
        }
    }

    fun termAt(page: Int, x: Float, y: Float): GlossaryTerm? {
        val slop = 0.01f
        val id = termsOn(page).firstOrNull { (r, _) -> x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop * 2 }?.second
            ?: return null
        return terms.firstOrNull { it.id == id }
    }

    /** Toque simples: some com a seleção ou abre a definição do termo tocado. true se tratou o toque. */
    fun tap(page: Int, x: Float, y: Float): Boolean {
        if (selection != null) {
            clearSelection()
            return true
        }
        val term = termAt(page, x, y) ?: return false
        onTermTap(term)
        return true
    }

    fun selectedText(): String? {
        val sel = selection ?: return null
        return texts[sel.page]?.textOf(sel.start, sel.end)?.takeIf { it.isNotBlank() }
    }

    fun clearSelection() {
        if (selection == null) return
        selection = null
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    // ---------- barra flutuante ----------

    /** Mostra (ou reposiciona) a barra "Copiar…" sobre a seleção. [bounds] devolve o retângulo na [view]. */
    fun showActions(view: View, bounds: () -> Rect?) {
        actionMode?.let {
            it.invalidateContentRect()
            return
        }
        actionMode = view.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_COPY, 0, "Copiar")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != MENU_COPY) return false
                selectedText()?.let { text ->
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Alexandria", text))
                    Toast.makeText(context, "Copiado.", Toast.LENGTH_SHORT).show()
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                if (selection != null) {
                    selection = null
                    invalidate()
                }
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                bounds()?.let { outRect.set(it) } ?: super.onGetContentRect(mode, view, outRect)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    /** Retângulo da seleção dentro de [dst] (pixels da view), para posicionar a barra. */
    fun selectionBounds(page: Int, dst: RectF): Rect? {
        val sel = selection?.takeIf { it.page == page } ?: return null
        val rects = texts[page]?.rects(sel.start, sel.end)?.map { map(it, dst) } ?: return null
        if (rects.isEmpty()) return null
        val union = RectF(rects.first())
        rects.forEach { union.union(it) }
        return Rect(union.left.toInt(), union.top.toInt(), union.right.toInt(), union.bottom.toInt())
    }

    companion object {
        private const val MENU_COPY = 0x434f
    }
}
