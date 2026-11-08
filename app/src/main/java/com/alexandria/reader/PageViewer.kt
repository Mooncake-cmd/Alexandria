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
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return RenderedPage(bitmap, w, h)
        } finally {
            page.close()
        }
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

    override fun close() = zip.close()
}

/**
 * Visualizador de páginas (PDF e CBZ): uma página por vez, na largura da tela.
 * Pinça para zoom, toque duplo alterna 100%/200%, deslizar para os lados (sem zoom) troca de
 * página e tocar no número da página abre "ir para a página".
 */
class PageViewer(host: ReaderHost, private val openSource: (LibraryItem) -> PageSource, private val kind: String) : BookViewer(host) {
    private var source: PageSource? = null // só tocado na thread de fundo, exceto [pageCount] guardado abaixo
    private var count = 0
    private var pageIndex = 0
    private var renderToken = 0
    private var zoom = 1f
    private var touchInPage = false
    private var pinching = false

    private val pageImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(Color.WHITE)
    }
    private val pageHScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(pageImage, FrameLayout.LayoutParams(-2, -2))
    }
    private val pageScroll = ScrollView(context).apply {
        setBackgroundColor(Palette.paper)
        addView(pageHScroll, FrameLayout.LayoutParams(-1, -2))
    }
    private val prevButton = barButton("‹ Anterior", Palette.ink) { goTo(pageIndex - 1) }
    private val nextButton = barButton("Próxima ›", Palette.ink) { goTo(pageIndex + 1) }
    private val pageLabel = context.label("Abrindo…", 13f, Palette.muted).apply {
        gravity = Gravity.CENTER
        minHeight = context.dp(48)
        contentDescription = "Ir para a página"
        setOnClickListener { askPage() }
    }
    private val gestures = Gestures()

    override val body = pageScroll
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
        host.worker.execute {
            val opened = try {
                openSource(item)
            } catch (e: SecurityException) {
                onUi { host.fail("Sem permissão para abrir este arquivo. Remova-o e importe de novo.") }
                return@execute
            } catch (e: Exception) {
                onUi { host.fail(openError()) }
                return@execute
            } catch (e: OutOfMemoryError) {
                onUi { host.fail(openError()) }
                return@execute
            }
            source = opened
            val total = opened.pageCount
            onUi {
                if (total == 0) {
                    host.fail("Este documento não tem páginas.")
                } else {
                    count = total
                    // Espera o layout para saber a largura real da área de leitura.
                    pageScroll.post { showPage(start, null) }
                }
            }
        }
    }

    private fun openError() = if (kind == "pdf") {
        "Não foi possível abrir este PDF. Ele pode estar protegido por senha, danificado ou indisponível."
    } else {
        "Não foi possível abrir este quadrinho. O arquivo pode estar danificado, sem imagens ou em formato RAR."
    }

    override fun position() = pageIndex
    override fun pageCount() = count
    override fun goTo(position: Int) = showPage(position, null)
    override fun atEnd() = count > 0 && pageIndex >= count - 1
    override fun describe(position: Int) = "Pág. ${position + 1}"

    override fun menuActions() = listOf("Ir para a página…" to { askPage() })

    override fun close() {
        host.worker.execute {
            source?.close()
            source = null
        }
    }

    /** Muda de página (ou só re-renderiza, com outro zoom). [focus] mantém um ponto da tela parado no zoom. */
    private fun showPage(index: Int, focus: Focus?) {
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        val changedPage = target != pageIndex
        pageIndex = target
        updateControls()
        host.onPositionChanged()
        val token = ++renderToken
        val baseWidth = if (pageScroll.width > 0) pageScroll.width else context.resources.displayMetrics.widthPixels
        val width = (baseWidth * zoom).toInt()
        host.worker.execute {
            val page = try {
                source?.render(target, width)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            onUi {
                if (token != renderToken) return@onUi
                pageImage.scaleX = 1f
                pageImage.scaleY = 1f
                if (page == null) {
                    Toast.makeText(context, "Não foi possível exibir esta página.", Toast.LENGTH_SHORT).show()
                    return@onUi
                }
                // O limite de memória pode ter reduzido o zoom pedido.
                zoom = page.width.toFloat() / baseWidth
                updateControls()
                pageImage.layoutParams = FrameLayout.LayoutParams(page.width, page.height)
                pageImage.setImageBitmap(page.bitmap)
                pageScroll.post {
                    if (focus != null && !changedPage) {
                        pageHScroll.scrollTo((focus.fractionX * page.width - focus.screenX).toInt(), 0)
                        pageScroll.scrollTo(0, (focus.fractionY * page.height - focus.screenY).toInt())
                    } else {
                        pageHScroll.scrollTo(0, 0)
                        pageScroll.scrollTo(0, 0)
                    }
                }
            }
        }
    }

    private fun updateControls() {
        val zoomText = if (zoom > 1.05f) " · ${(zoom * 100).toInt()}%" else ""
        pageLabel.text = "Página ${pageIndex + 1} de $count$zoomText"
        setEnabledLook(prevButton, pageIndex > 0)
        setEnabledLook(nextButton, pageIndex < count - 1)
    }

    private fun askPage() {
        if (count == 0) return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 a $count"
            setText((pageIndex + 1).toString())
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
            .setPositiveButton("Ir") { _, _ -> input.text.toString().toIntOrNull()?.let { goTo(it - 1) } }
            .show()
    }

    // ---------- zoom e gestos ----------

    /** Ponto da tela (em pixels da área de leitura) e onde ele cai na página, de 0 a 1. */
    private class Focus(val screenX: Float, val screenY: Float, val fractionX: Float, val fractionY: Float)

    private fun focusAt(screenX: Float, screenY: Float): Focus {
        val fx = ((pageHScroll.scrollX + screenX) / pageImage.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val fy = ((pageScroll.scrollY + screenY) / pageImage.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        return Focus(screenX, screenY, fx, fy)
    }

    private fun setZoom(newZoom: Float, focus: Focus) {
        val clamped = newZoom.coerceIn(1f, MAX_ZOOM)
        if (abs(clamped - zoom) < 0.05f) {
            pageImage.scaleX = 1f
            pageImage.scaleY = 1f
            return
        }
        zoom = clamped
        showPage(pageIndex, focus)
    }

    /** Converte coordenadas da janela para coordenadas visíveis da área de leitura. */
    private fun toPageCoords(windowX: Float, windowY: Float): Pair<Float, Float> {
        val location = IntArray(2)
        pageScroll.getLocationInWindow(location)
        return Pair(windowX - location[0], windowY - location[1])
    }

    /** Recebe os eventos de toque da janela inteira, antes das ScrollViews. */
    override fun dispatchTouch(event: MotionEvent): Boolean? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val (x, y) = toPageCoords(event.x, event.y)
            touchInPage = x >= 0 && y >= 0 && x < pageScroll.width && y < pageScroll.height
            pinching = false
        }
        if (!touchInPage || count == 0) return null
        gestures.scale.onTouchEvent(event)
        gestures.tap.onTouchEvent(event)
        if (gestures.scale.isInProgress && !pinching) {
            // Durante a pinça a rolagem fica parada: cancela o gesto que as ScrollViews viram.
            pinching = true
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            context.window.superDispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (pinching) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
            return true
        }
        return null
    }

    private inner class Gestures {
        private var pinchScale = 1f
        private var pinchFocusX = 0f
        private var pinchFocusY = 0f
        private val minFlingVelocity = context.dp(500)
        private val minFlingDistance = context.dp(60)

        val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchScale = 1f
                val (x, y) = toPageCoords(detector.focusX, detector.focusY)
                pinchFocusX = x
                pinchFocusY = y
                // Prévia instantânea: escala a imagem atual em volta do ponto da pinça.
                pageImage.pivotX = pageHScroll.scrollX + x
                pageImage.pivotY = pageScroll.scrollY + y
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchScale = (pinchScale * detector.scaleFactor).coerceIn(1f / zoom, MAX_ZOOM / zoom)
                pageImage.scaleX = pinchScale
                pageImage.scaleY = pinchScale
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                setZoom(zoom * pinchScale, focusAt(pinchFocusX, pinchFocusY))
            }
        })

        val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val (x, y) = toPageCoords(e.x, e.y)
                setZoom(if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM, focusAt(x, y))
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                if (zoom > 1.05f || scale.isInProgress) return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) < minFlingDistance || abs(dx) < abs(dy) * 1.5f || abs(velocityX) < minFlingVelocity) return false
                goTo(if (dx < 0) pageIndex + 1 else pageIndex - 1)
                return true
            }
        })
    }

    companion object {
        private const val MAX_ZOOM = 3f
        private const val DOUBLE_TAP_ZOOM = 2f

        fun pdf(host: ReaderHost) = PageViewer(host, { item -> PdfSource(host.activity, Uri.parse(item.uri)) }, "pdf")

        fun comic(host: ReaderHost) = PageViewer(host, { item ->
            ComicSource(ZipFile(BookFiles.localCopy(host.activity, item)))
        }, "cbz")
    }
}
