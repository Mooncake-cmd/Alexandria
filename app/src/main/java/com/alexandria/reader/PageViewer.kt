package com.alexandria.reader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sqrt

/** Página já desenhada, com o tamanho em que deve aparecer na tela. */
class RenderedPage(val bitmap: Bitmap, val width: Int, val height: Int)

/**
 * Fonte de páginas de um documento paginado. Tudo aqui roda na thread de fundo do leitor (uma só),
 * então as implementações não precisam ser thread-safe.
 */
interface PageSource {
    val pageCount: Int
    /** Desenha a página [index] com largura de tela [width] (já multiplicada pelo zoom). */
    fun render(index: Int, width: Int): RenderedPage?

    /** Proporção altura/largura da página [index], para encaixá-la na tela antes de desenhar. */
    fun aspect(index: Int): Float
    fun close()

    companion object {
        /** Limite de memória de um bitmap de página (4 bytes por pixel, cerca de 32 MB). */
        const val MAX_PIXELS = 8_000_000L
    }
}

/** PDF pelo renderizador nativo do Android. */
class PdfSource(context: Context, uri: Uri) : PageSource {
    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Sem descritor de arquivo")
    private val renderer: PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }

    override val pageCount: Int get() = renderer.pageCount

    override fun render(index: Int, width: Int): RenderedPage {
        val page = renderer.openPage(index)
        try {
            var scale = width.toFloat() / page.width
            // Limita a memória do bitmap, inclusive em páginas muito compridas.
            val pixels = page.width * scale * page.height * scale
            if (pixels > PageSource.MAX_PIXELS) scale *= sqrt(PageSource.MAX_PIXELS / pixels)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            // Transformação explícita: página (em pontos) → bitmap, sem deixar o renderizador "encaixar"
            // por conta própria (o que distorcia páginas com caixa de corte diferente da de mídia).
            val matrix = android.graphics.Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return RenderedPage(bitmap, w, h)
        } finally {
            page.close()
        }
    }

    override fun aspect(index: Int): Float = renderer.openPage(index).use { page ->
        if (page.width > 0) page.height.toFloat() / page.width else 1.414f
    }

    override fun close() {
        try {
            renderer.close()
        } catch (e: Exception) {
            // Já fechado.
        }
        try {
            descriptor.close()
        } catch (e: Exception) {
            // Já fechado.
        }
    }
}

/** Quadrinho CBZ (ou CBR que na verdade é ZIP): cada imagem do arquivo é uma página. */
class ComicSource(private val zip: ZipFile) : PageSource {
    private val pages = ComicArchive.pageNames(zip)

    init {
        if (pages.isEmpty()) {
            zip.close()
            throw IllegalArgumentException("Nenhuma imagem no arquivo")
        }
    }

    override val pageCount: Int get() = pages.size

    override fun render(index: Int, width: Int): RenderedPage? {
        val entry = zip.getEntry(pages[index]) ?: return null
        val bitmap = BookFiles.decodeScaled({ zip.getInputStream(entry) }, width, PageSource.MAX_PIXELS) ?: return null
        // A imagem pode ter vindo menor (economia de memória): a tela mostra na largura pedida.
        val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1)
        return RenderedPage(bitmap, width, height)
    }

    override fun aspect(index: Int): Float {
        val entry = zip.getEntry(pages[index]) ?: return 1.5f
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        return if (bounds.outWidth > 0) bounds.outHeight.toFloat() / bounds.outWidth else 1.5f
    }

    override fun close() = zip.close()
}

/**
 * Página desenhada com zoom e deslocamento próprios, sem ScrollViews (que ignoravam a largura
 * pedida e esticavam a página). Encaixa a página inteira na área e a centraliza; com zoom, o
 * dedo arrasta a página.
 */
class ZoomPageView(context: Context) : android.view.View(context) {
    private var bitmap: Bitmap? = null
    /** Tamanho da página com zoom 1 (inteira na área). */
    var fitWidth = 0
        private set
    var fitHeight = 0
        private set
    var zoom = 1f
        private set
    private var left = 0f
    private var top = 0f
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val target = android.graphics.RectF()
    private val scroller = android.widget.OverScroller(context)

    /** Nova página: volta ao zoom 1, centralizada. */
    fun showPage(page: Bitmap, fitW: Int, fitH: Int) {
        scroller.forceFinished(true)
        bitmap = page
        fitWidth = fitW
        fitHeight = fitH
        zoom = 1f
        clamp(center = true)
        invalidate()
    }

    /** Filtro de cor do tema (PDF/DJVU/quadrinhos não têm cor de texto para trocar). */
    fun setTheme(theme: Comfort.Theme) {
        paint.colorFilter = Comfort.pageMatrix(theme)?.let { android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(it)) }
        setBackgroundColor(theme.background)
        invalidate()
    }

    /** Mesma página e geometria, bitmap mais nítido (depois de um zoom). */
    fun sharpen(page: Bitmap) {
        bitmap = page
        invalidate()
    }

    private fun contentWidth() = fitWidth * zoom
    private fun contentHeight() = fitHeight * zoom

    /** Centraliza o que é menor que a área e impede arrastar a página para fora dela. */
    private fun clamp(center: Boolean = false) {
        val cw = contentWidth()
        val ch = contentHeight()
        left = if (cw <= width) (width - cw) / 2 else if (center) (width - cw) / 2 else left.coerceIn(width - cw, 0f)
        top = if (ch <= height) (height - ch) / 2 else if (center) 0f else top.coerceIn(height - ch, 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp(center = true)
    }

    fun panBy(dx: Float, dy: Float) {
        left -= dx
        top -= dy
        clamp()
        invalidate()
    }

    fun fling(velocityX: Float, velocityY: Float) {
        val cw = contentWidth().toInt()
        val ch = contentHeight().toInt()
        if (cw <= width && ch <= height) return
        scroller.fling(
            left.toInt(), top.toInt(), velocityX.toInt(), velocityY.toInt(),
            minOf(width - cw, 0), 0, minOf(height - ch, 0), 0
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            left = scroller.currX.toFloat()
            top = scroller.currY.toFloat()
            clamp()
            postInvalidateOnAnimation()
        }
    }

    /** Zoom mantendo parado o ponto ([focusX], [focusY]) da área. */
    fun zoomTo(newZoom: Float, focusX: Float, focusY: Float) {
        val clamped = newZoom.coerceIn(1f, MAX_ZOOM)
        val px = (focusX - left) / contentWidth().coerceAtLeast(1f)
        val py = (focusY - top) / contentHeight().coerceAtLeast(1f)
        zoom = clamped
        left = focusX - px * contentWidth()
        top = focusY - py * contentHeight()
        clamp()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val page = bitmap ?: return
        target.set(left, top, left + contentWidth(), top + contentHeight())
        canvas.drawBitmap(page, null, target, paint)
    }

    companion object {
        const val MAX_ZOOM = 4f
    }
}

/**
 * Modo páginas para PDF, DJVU e quadrinhos: uma página por vez, inteira na tela.
 * Toque duplo na borda direita avança e na esquerda volta; no meio, alterna o zoom (100%/200%).
 * Pinça também dá zoom, e com zoom o dedo arrasta a página. Tocar no número abre "ir para a página".
 */
class PageViewer(host: ReaderHost, private val openSource: (LibraryItem) -> PageSource, private val kind: String) : BookViewer(host) {
    private var source: PageSource? = null // só tocado na thread de fundo
    private var count = 0
    private var pageIndex = 0
    private var renderToken = 0
    private var touchInPage = false
    private var pinching = false

    private val pageView = ZoomPageView(context).apply { setTheme(ComfortStore.load(context).theme) }
    private val prevButton = barButton("‹ Anterior", Palette.ink) { goTo(pageIndex - 1) }
    private val nextButton = barButton("Próxima ›", Palette.ink) { goTo(pageIndex + 1) }
    private val pageLabel = context.label("Abrindo…", 13f, Palette.muted).apply {
        gravity = Gravity.CENTER
        minHeight = context.dp(48)
        contentDescription = "Ir para a página"
        setOnClickListener { askPage(host.activity, count, pageIndex) { goTo(it) } }
    }
    private val gestures = Gestures()

    override val body = pageView
    override val footer = footerBar().apply {
        addView(prevButton)
        addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        addView(nextButton)
    }
    override val loaded get() = count > 0
    override val annotatesPages = true

    init {
        setEnabledLook(prevButton, false)
        setEnabledLook(nextButton, false)
    }

    override fun open(item: LibraryItem, start: Int) {
        openPageSource(host, openSource, item, kind) { opened ->
            source = opened.first
            count = opened.second
            pageView.post { showPage(start) }
        }
    }

    override fun position() = pageIndex
    override fun pageCount() = count
    override fun goTo(position: Int) = showPage(position)
    override fun atEnd() = count > 0 && pageIndex >= count - 1
    override fun describe(position: Int) = "Pág. ${position + 1}"
    override fun menuActions() = listOf("Ir para a página…" to { askPage(context, count, pageIndex) { goTo(it) } })

    override fun applyComfort(comfort: Comfort) = pageView.setTheme(comfort.theme)

    override fun close() {
        host.worker.execute {
            source?.close()
            source = null
        }
    }

    private fun showPage(index: Int) {
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        pageIndex = target
        updateControls(1f)
        host.onPositionChanged()
        val token = ++renderToken
        val areaW = pageView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val areaH = pageView.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        host.worker.execute {
            val result = try {
                val opened = source ?: return@execute
                val (fitW, fitH) = PageTurn.fitPage(areaW, areaH, opened.aspect(target))
                opened.render(target, fitW)?.let { Triple(it, fitW, fitH) }
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            onUi {
                if (token != renderToken) return@onUi
                if (result == null) {
                    Toast.makeText(context, "Não foi possível exibir esta página.", Toast.LENGTH_SHORT).show()
                    return@onUi
                }
                pageView.showPage(result.first.bitmap, result.second, result.third)
            }
        }
    }

    /** Depois de um zoom, desenha de novo na resolução nova (a prévia é só a imagem ampliada). */
    private fun sharpen() {
        val zoom = pageView.zoom
        updateControls(zoom)
        if (zoom <= 1.01f) return
        val token = ++renderToken
        val target = pageIndex
        val width = (pageView.fitWidth * zoom).toInt()
        host.worker.execute {
            val page = try {
                source?.render(target, width)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            onUi { if (token == renderToken && page != null) pageView.sharpen(page.bitmap) }
        }
    }

    private fun updateControls(zoom: Float) {
        val zoomText = if (zoom > 1.05f) " · ${(zoom * 100).toInt()}%" else ""
        pageLabel.text = "Página ${pageIndex + 1} de $count$zoomText"
        setEnabledLook(prevButton, pageIndex > 0)
        setEnabledLook(nextButton, pageIndex < count - 1)
    }

    private fun local(event: MotionEvent): Pair<Float, Float> = local(event.x, event.y)

    private fun local(windowX: Float, windowY: Float): Pair<Float, Float> {
        val location = IntArray(2)
        pageView.getLocationInWindow(location)
        return Pair(windowX - location[0], windowY - location[1])
    }

    /** Todos os toques na página são tratados aqui: zoom, arrastar e toque duplo. */
    override fun dispatchTouch(event: MotionEvent): Boolean? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val (x, y) = local(event)
            touchInPage = x >= 0 && y >= 0 && x < pageView.width && y < pageView.height
            pinching = false
        }
        if (!touchInPage || count == 0) return null
        gestures.scale.onTouchEvent(event)
        if (gestures.scale.isInProgress) pinching = true
        if (!pinching) gestures.tap.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (pinching) sharpen()
            pinching = false
        }
        return true
    }

    private inner class Gestures {
        val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val (x, y) = local(detector.focusX, detector.focusY)
                pageView.zoomTo(pageView.zoom * detector.scaleFactor, x, y)
                return true
            }
        })

        val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                pageView.panBy(distanceX, distanceY)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                pageView.fling(velocityX, velocityY)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val (x, y) = local(e)
                when (PageTurn.direction(x, pageView.width.toFloat())) {
                    1 -> goTo(pageIndex + 1)
                    -1 -> goTo(pageIndex - 1)
                    else -> {
                        pageView.zoomTo(if (pageView.zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM, x, y)
                        sharpen()
                        if (pageView.zoom <= 1.01f) updateControls(1f)
                    }
                }
                return true
            }
        })
    }

    companion object {
        private const val DOUBLE_TAP_ZOOM = 2f

        private fun create(host: ReaderHost, kind: String, open: (LibraryItem) -> PageSource): BookViewer =
            if (ReadingPrefs.paged(host.activity)) PageViewer(host, open, kind) else PageScrollViewer(host, open, kind)

        fun pdf(host: ReaderHost) = create(host, "pdf") { item -> PdfSource(host.activity, Uri.parse(item.uri)) }

        /** CBZ, ou CBR (ZIP disfarçado ou RAR de verdade, este pelo motor EraComic). */
        fun comic(host: ReaderHost) = create(host, "cbz") { item -> BookFiles.comicSource(host.activity, item) }

        /** DJVU pelo motor EraDjVu. */
        fun djvu(host: ReaderHost) = create(host, "djvu") { item -> BookFiles.djvuSource(host.activity, item) }
    }
}

/** Abre a fonte de páginas na thread de fundo e devolve (fonte, total) na thread principal. */
internal fun BookViewer.openPageSource(
    host: ReaderHost,
    openSource: (LibraryItem) -> PageSource,
    item: LibraryItem,
    kind: String,
    done: (Pair<PageSource, Int>) -> Unit
) {
    val error = when (kind) {
        "pdf" -> "Não foi possível abrir este PDF. Ele pode estar protegido por senha, danificado ou indisponível."
        "djvu" -> "Não foi possível abrir este DJVU. O arquivo pode estar danificado."
        else -> "Não foi possível abrir este quadrinho. O arquivo pode estar danificado ou sem imagens."
    }
    host.worker.execute {
        val opened = try {
            openSource(item)
        } catch (e: SecurityException) {
            host.activity.runOnUiThread { if (!host.closed) host.fail("Sem permissão para abrir este arquivo. Remova-o e importe de novo.") }
            return@execute
        } catch (e: Exception) {
            host.activity.runOnUiThread { if (!host.closed) host.fail(error) }
            return@execute
        } catch (e: OutOfMemoryError) {
            host.activity.runOnUiThread { if (!host.closed) host.fail(error) }
            return@execute
        }
        val total = opened.pageCount
        host.activity.runOnUiThread {
            if (host.closed) {
                host.worker.execute { opened.close() }
            } else if (total == 0) {
                host.fail("Este documento não tem páginas.")
            } else {
                done(opened to total)
            }
        }
    }
}

/** Diálogo "Ir para a página" (recebe e devolve índice começando em 0). */
internal fun askPage(context: android.app.Activity, count: Int, current: Int, go: (Int) -> Unit) {
    if (count == 0) return
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = "1 a $count"
        setText((current + 1).toString())
        selectAll()
    }
    val container = FrameLayout(context).apply {
        setPadding(context.dp(22), context.dp(8), context.dp(22), 0)
        addView(input)
    }
    AlertDialog.Builder(context)
        .setTitle("Ir para a página")
        .setView(container)
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Ir") { _, _ -> input.text.toString().toIntOrNull()?.let { go(it - 1) } }
        .show()
}

/**
 * Modo rolagem contínua para PDF, DJVU e quadrinhos: as páginas, na largura da tela, uma embaixo
 * da outra. Cada página é desenhada quando aparece e guardada num cache limitado.
 *
 * Zoom: a coluna de páginas inteira é ampliada (pinça ou toque duplo). A rolagem vertical continua
 * normal e, com zoom, o dedo arrasta a coluna para os lados. Depois do zoom, as páginas visíveis
 * são desenhadas de novo na resolução nova.
 */
class PageScrollViewer(host: ReaderHost, private val openSource: (LibraryItem) -> PageSource, private val kind: String) : BookViewer(host) {
    private var source: PageSource? = null // só tocado na thread de fundo
    private var count = 0
    private val aspects = HashMap<Int, Float>()
    private var defaultAspect = 1.414f
    /** Largura (em pixels) em que cada página está sendo desenhada agora. */
    private val inFlight = HashMap<Int, Int>()
    @Volatile
    private var visibleRange = 0..0
    private var zoom = 1f
    private var pinching = false
    private var touchInside = false
    private var pageFilter: android.graphics.ColorFilter? = null
    private val bitmaps = object : android.util.LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    private val adapter = object : android.widget.BaseAdapter() {
        override fun getCount(): Int = this@PageScrollViewer.count
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView as? ImageView ?: ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            view.colorFilter = pageFilter
            val width = parent.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val height = (width * (aspects[position] ?: defaultAspect)).toInt().coerceAtLeast(1)
            view.layoutParams = android.widget.AbsListView.LayoutParams(width, height)
            val bitmap = bitmaps.get(position)
            view.setImageBitmap(bitmap)
            // Sem bitmap, ou com um mais "borrado" que o zoom atual pede: desenha de novo.
            val wanted = renderWidth(width)
            if (bitmap == null || bitmap.width < wanted * 0.85f) request(position, wanted)
            return view
        }
    }

    private val list = android.widget.ListView(context).apply {
        adapter = this@PageScrollViewer.adapter
        dividerHeight = context.dp(8)
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        pivotX = 0f
        pivotY = 0f
        setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                visibleRange = (first - 1)..(first + visible + 1)
                if (count > 0) {
                    updateLabel()
                    host.onPositionChanged()
                }
            }
        })
    }

    /** Moldura que recorta a coluna ampliada. */
    private val frame = FrameLayout(context).apply {
        addView(list, FrameLayout.LayoutParams(-1, -1))
        addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyZoomLayout()
        }
    }

    private val pageLabel = context.label("Abrindo…", 13f, Palette.muted).apply {
        gravity = Gravity.CENTER
        minHeight = context.dp(48)
        contentDescription = "Ir para a página"
        setOnClickListener { askPage(host.activity, count, position()) { goTo(it) } }
    }

    override val body = frame
    override val footer = footerBar().apply { addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f)) }
    override val loaded get() = count > 0
    override val annotatesPages = true

    init {
        applyComfort(ComfortStore.load(context))
    }

    override fun applyComfort(comfort: Comfort) {
        pageFilter = Comfort.pageMatrix(comfort.theme)?.let { android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(it)) }
        frame.setBackgroundColor(comfort.theme.background)
        list.setBackgroundColor(comfort.theme.background)
        list.divider = android.graphics.drawable.ColorDrawable(comfort.theme.background)
        list.dividerHeight = context.dp(8)
        adapter.notifyDataSetChanged()
    }

    override fun open(item: LibraryItem, start: Int) {
        openPageSource(host, openSource, item, kind) { (opened, total) ->
            source = opened
            host.worker.execute {
                val first = try {
                    opened.aspect(0)
                } catch (e: Exception) {
                    1.414f
                }
                onUi {
                    defaultAspect = first
                    count = total
                    adapter.notifyDataSetChanged()
                    goTo(start)
                }
            }
        }
    }

    private fun renderWidth(baseWidth: Int): Int = (baseWidth * zoom).toInt().coerceAtLeast(1)

    private fun request(index: Int, width: Int) {
        val pending = inFlight[index]
        if (pending != null && pending >= width) return
        inFlight[index] = width
        host.worker.execute {
            // A página já saiu da tela enquanto esperava: não gasta tempo desenhando.
            if (index !in visibleRange) {
                onUi { if (inFlight[index] == width) inFlight.remove(index) }
                return@execute
            }
            val result = try {
                val opened = source ?: return@execute
                val aspect = opened.aspect(index)
                aspect to opened.render(index, width)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            onUi {
                if (inFlight[index] == width) inFlight.remove(index)
                if (result == null) return@onUi
                val changed = aspects[index] != result.first
                aspects[index] = result.first
                result.second?.let { bitmaps.put(index, it.bitmap) }
                if (changed || index in list.firstVisiblePosition..list.lastVisiblePosition) adapter.notifyDataSetChanged()
            }
        }
    }

    // ---------- zoom ----------

    /** A lista tem altura = moldura / zoom e é ampliada pelo canto de cima: preenche a moldura. */
    private fun applyZoomLayout() {
        val height = frame.height
        if (height <= 0) return
        val params = list.layoutParams
        val wanted = (height / zoom).toInt().coerceAtLeast(1)
        if (params.height != wanted) {
            params.height = wanted
            list.layoutParams = params
        }
        list.scaleX = zoom
        list.scaleY = zoom
        clampTranslation()
    }

    private fun clampTranslation() {
        val minX = frame.width - frame.width * zoom
        list.translationX = list.translationX.coerceIn(minOf(minX, 0f), 0f)
    }

    /** Zoom mantendo parado o ponto ([focusX], [focusY]) da moldura. */
    private fun setZoom(newZoom: Float, focusX: Float, focusY: Float) {
        val old = zoom
        val next = newZoom.coerceIn(1f, MAX_ZOOM)
        if (next == old) return
        list.translationX = focusX - (focusX - list.translationX) * next / old
        // Na vertical, o mesmo conteúdo continua sob o dedo: rola a lista a diferença.
        val delta = (focusY / old - focusY / next).toInt()
        zoom = next
        applyZoomLayout()
        if (delta != 0) list.scrollListBy(delta)
        updateLabel()
    }

    /** Depois do zoom, as páginas visíveis são desenhadas de novo na resolução nova. */
    private fun sharpen() = adapter.notifyDataSetChanged()

    private fun local(windowX: Float, windowY: Float): Pair<Float, Float> {
        val location = IntArray(2)
        frame.getLocationInWindow(location)
        return Pair(windowX - location[0], windowY - location[1])
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val (x, y) = local(detector.focusX, detector.focusY)
            setZoom(zoom * detector.scaleFactor, x, y)
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1.01f) {
                list.translationX -= distanceX
                clampTranslation()
            }
            return false // a lista continua rolando na vertical
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val (x, y) = local(e.x, e.y)
            setZoom(if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM, x, y)
            sharpen()
            return true
        }
    })

    override fun dispatchTouch(event: MotionEvent): Boolean? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val (x, y) = local(event.x, event.y)
            touchInside = x >= 0 && y >= 0 && x < frame.width && y < frame.height
            pinching = false
        }
        if (!touchInside || count == 0) return null
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress && !pinching) {
            // Durante a pinça a lista não rola: cancela o gesto que ela estava vendo.
            pinching = true
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            context.window.superDispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (pinching) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pinching = false
                sharpen()
            }
            return true
        }
        return if (tapDetector.onTouchEvent(event)) true else null
    }

    // ---------- posição ----------

    override fun position(): Int {
        if (list.childCount == 0) return list.firstVisiblePosition
        // A página que ocupa o meio da tela é a "atual".
        val middle = list.height / 2
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.bottom >= middle) return (list.firstVisiblePosition + i).coerceIn(0, (count - 1).coerceAtLeast(0))
        }
        return list.lastVisiblePosition
    }

    override fun pageCount() = count
    override fun goTo(position: Int) {
        if (count == 0) return
        list.setSelectionFromTop(position.coerceIn(0, count - 1), 0)
        list.post {
            updateLabel()
            host.onPositionChanged()
        }
    }

    override fun atEnd() = count > 0 && !list.canScrollVertically(1)
    override fun describe(position: Int) = "Pág. ${position + 1}"
    override fun menuActions() = listOf("Ir para a página…" to { askPage(context, count, position()) { goTo(it) } })

    private fun updateLabel() {
        val zoomText = if (zoom > 1.05f) " · ${(zoom * 100).toInt()}%" else ""
        pageLabel.text = "Página ${position() + 1} de $count$zoomText"
    }

    override fun close() {
        host.worker.execute {
            source?.close()
            source = null
        }
    }

    companion object {
        private const val MAX_ZOOM = 4f
        private const val DOUBLE_TAP_ZOOM = 2f
    }
}
