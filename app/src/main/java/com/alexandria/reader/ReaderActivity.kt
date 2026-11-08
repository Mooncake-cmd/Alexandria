package com.alexandria.reader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Leitor de PDF (PdfRenderer da plataforma) e TXT. Salva o ponto de leitura ao sair.
 * Todo acesso ao PdfRenderer acontece na thread [worker], porque ele não é thread-safe.
 */
class ReaderActivity : Activity() {
    private lateinit var database: LibraryDatabase
    private var current: LibraryItem? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false

    // PDF. renderer e descriptor só são tocados na thread worker.
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var pageIndex = 0
    private var renderToken = 0
    private var zoom = 1f
    private lateinit var pageScroll: ScrollView
    private lateinit var pageHScroll: HorizontalScrollView
    private lateinit var pageImage: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private var pdfGestures: PdfGestures? = null
    private var touchInPage = false
    private var pinching = false

    // TXT
    private lateinit var textList: ListView
    private lateinit var progressLabel: TextView
    private lateinit var textAdapter: ChunkAdapter
    private var textSizeSp = DEFAULT_TEXT_SP
    private var nightMode = false
    private var textLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = LibraryDatabase(this)
        val item = database.find(intent.getLongExtra(EXTRA_ITEM_ID, -1L))
        if (item == null) {
            fail("Documento não encontrado na biblioteca.")
            return
        }
        current = item
        when (item.format) {
            "pdf" -> buildPdfScreen(item)
            "txt" -> buildTextScreen(item)
            else -> fail("O leitor de ${item.format.uppercase()} ainda não existe.")
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        destroyed = true
        worker.execute { closePdf() }
        worker.shutdown()
        database.close()
        super.onDestroy()
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun saveProgress() {
        val item = current ?: return
        when (item.format) {
            "pdf" -> if (pageCount > 0) database.updateProgress(item.id, pageIndex, pageCount)
            "txt" -> if (textLoaded) database.updateProgress(item.id, textFraction())
        }
    }

    // ---------- estrutura comum ----------

    private fun barButton(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(color)
            setOnClickListener { onClick() }
        }

    private fun frame(title: String, body: View, footer: View, topActions: List<View> = emptyList()): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.paper)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.ink)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(barButton("‹", Color.WHITE) { finish() }.apply { contentDescription = "Voltar" })
        bar.addView(
            label(title, 15f, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(8), 0)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        topActions.forEach { bar.addView(it) }
        root.addView(bar)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(footer)
        installSystemBarInsets(root, bar, footer)
        return root
    }

    private fun footerBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    // ---------- PDF ----------

    private fun buildPdfScreen(item: LibraryItem) {
        pageImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.WHITE)
        }
        pageHScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(pageImage, FrameLayout.LayoutParams(-2, -2))
        }
        pageScroll = ScrollView(this).apply {
            setBackgroundColor(Palette.paper)
            addView(pageHScroll, FrameLayout.LayoutParams(-1, -2))
        }
        prevButton = barButton("‹ Anterior", Palette.ink) { showPage(pageIndex - 1) }
        nextButton = barButton("Próxima ›", Palette.ink) { showPage(pageIndex + 1) }
        pageLabel = label("Abrindo…", 13f, Palette.muted).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            contentDescription = "Ir para a página"
            setOnClickListener { askPage() }
        }
        prevButton.isEnabled = false
        nextButton.isEnabled = false

        val footer = footerBar()
        footer.addView(prevButton)
        footer.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(nextButton)
        setContentView(frame(item.name, pageScroll, footer))
        pdfGestures = PdfGestures()

        val uri = Uri.parse(item.uri)
        worker.execute {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Sem descritor de arquivo")
                val opened = try {
                    PdfRenderer(pfd)
                } catch (e: Exception) {
                    pfd.close()
                    throw e
                }
                descriptor = pfd
                renderer = opened
                val count = opened.pageCount
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    if (count == 0) {
                        fail("Este PDF não tem páginas.")
                    } else {
                        pageCount = count
                        // Espera o layout para saber a largura real da área de leitura.
                        pageScroll.post { showPage(item.position) }
                    }
                }
            } catch (e: SecurityException) {
                failOnUi("Sem permissão para abrir este PDF. Remova-o e importe de novo.")
            } catch (e: Exception) {
                failOnUi("Não foi possível abrir este PDF. Ele pode estar protegido por senha, danificado ou indisponível.")
            }
        }
    }

    private fun failOnUi(message: String) = runOnUiThread { if (!destroyed) fail(message) }

    /** Muda de página (ou só re-renderiza, com outro zoom). [focus] mantém um ponto da tela parado no zoom. */
    private fun showPage(index: Int, focus: Focus? = null) {
        if (pageCount == 0) return
        val changedPage = index.coerceIn(0, pageCount - 1) != pageIndex
        pageIndex = index.coerceIn(0, pageCount - 1)
        updatePageControls()
        val target = pageIndex
        val token = ++renderToken
        val baseWidth = if (pageScroll.width > 0) pageScroll.width else resources.displayMetrics.widthPixels
        val requestedZoom = zoom
        worker.execute {
            val bitmap = try {
                renderPage(target, baseWidth, requestedZoom)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            runOnUiThread {
                if (destroyed || token != renderToken) return@runOnUiThread
                pageImage.scaleX = 1f
                pageImage.scaleY = 1f
                if (bitmap == null) {
                    Toast.makeText(this, "Não foi possível exibir esta página.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // O limite de memória pode ter reduzido o zoom pedido.
                zoom = bitmap.width.toFloat() / baseWidth
                updatePageControls()
                pageImage.layoutParams = FrameLayout.LayoutParams(bitmap.width, bitmap.height)
                pageImage.setImageBitmap(bitmap)
                pageScroll.post {
                    if (focus != null && !changedPage) {
                        pageHScroll.scrollTo((focus.fractionX * bitmap.width - focus.screenX).toInt(), 0)
                        pageScroll.scrollTo(0, (focus.fractionY * bitmap.height - focus.screenY).toInt())
                    } else {
                        pageHScroll.scrollTo(0, 0)
                        pageScroll.scrollTo(0, 0)
                    }
                }
            }
        }
    }

    private fun updatePageControls() {
        val zoomText = if (zoom > 1.05f) " · ${(zoom * 100).toInt()}%" else ""
        pageLabel.text = "Página ${pageIndex + 1} de $pageCount$zoomText"
        prevButton.isEnabled = pageIndex > 0
        nextButton.isEnabled = pageIndex < pageCount - 1
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.35f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.35f
    }

    private fun askPage() {
        if (pageCount == 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 a $pageCount"
            setText((pageIndex + 1).toString())
            selectAll()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Ir para a página")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Ir") { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page != null) showPage(page - 1)
            }
            .show()
    }

    /** Ponto da tela (em pixels da área de leitura) e onde ele cai na página, de 0 a 1. */
    private class Focus(val screenX: Float, val screenY: Float, val fractionX: Float, val fractionY: Float)

    private fun focusAt(screenX: Float, screenY: Float): Focus {
        val width = pageImage.width.coerceAtLeast(1)
        val height = pageImage.height.coerceAtLeast(1)
        val fx = ((pageHScroll.scrollX + screenX) / width).coerceIn(0f, 1f)
        val fy = ((pageScroll.scrollY + screenY) / height).coerceIn(0f, 1f)
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

    /**
     * Gestos na página: pinça para zoom, toque duplo alterna 100%/200% e deslizar para os lados
     * (sem zoom) troca de página. Os eventos chegam por [dispatchTouchEvent].
     */
    private inner class PdfGestures {
        private var pinchScale = 1f
        private var pinchFocusX = 0f
        private var pinchFocusY = 0f
        private val minFlingVelocity = dp(500)
        private val minFlingDistance = dp(60)

        val scale = ScaleGestureDetector(this@ReaderActivity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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

        val tap = GestureDetector(this@ReaderActivity, object : GestureDetector.SimpleOnGestureListener() {
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
                showPage(if (dx < 0) pageIndex + 1 else pageIndex - 1)
                return true
            }
        })
    }

    /** Converte coordenadas da janela para coordenadas visíveis da área de leitura. */
    private fun toPageCoords(windowX: Float, windowY: Float): Pair<Float, Float> {
        val location = IntArray(2)
        pageScroll.getLocationInWindow(location)
        return Pair(windowX - location[0], windowY - location[1])
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val gestures = pdfGestures ?: return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val (x, y) = toPageCoords(ev.x, ev.y)
            touchInPage = x >= 0 && y >= 0 && x < pageScroll.width && y < pageScroll.height
            pinching = false
        }
        if (!touchInPage) return super.dispatchTouchEvent(ev)
        gestures.scale.onTouchEvent(ev)
        gestures.tap.onTouchEvent(ev)
        if (gestures.scale.isInProgress && !pinching) {
            // Durante a pinça a rolagem fica parada: cancela o gesto que as ScrollViews viram.
            pinching = true
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (pinching) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Roda na thread worker. */
    private fun renderPage(index: Int, baseWidth: Int, zoomLevel: Float): Bitmap? {
        val pdf = renderer ?: return null
        val page = pdf.openPage(index)
        try {
            var scale = baseWidth * zoomLevel / page.width
            // Limita a memória do bitmap (4 bytes por pixel), inclusive em páginas muito compridas.
            val pixels = page.width * scale * page.height * scale
            if (pixels > MAX_BITMAP_PIXELS) scale *= sqrt(MAX_BITMAP_PIXELS / pixels)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    /** Roda na thread worker. */
    private fun closePdf() {
        try {
            renderer?.close()
        } catch (e: Exception) {
            // Já fechado.
        }
        try {
            descriptor?.close()
        } catch (e: Exception) {
            // Já fechado.
        }
        renderer = null
        descriptor = null
    }

    // ---------- TXT ----------

    private fun buildTextScreen(item: LibraryItem) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        textSizeSp = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SP)
        nightMode = prefs.getBoolean(KEY_NIGHT, false)

        textAdapter = ChunkAdapter()
        textList = ListView(this).apply {
            adapter = textAdapter
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalFadingEdgeEnabled = false
            clipToPadding = false
            setPadding(0, dp(16), 0, dp(24))
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                    if (textLoaded) updateProgressLabel()
                }
            })
        }
        progressLabel = label("Carregando…", 12f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        val footer = footerBar()
        footer.addView(progressLabel, LinearLayout.LayoutParams(0, -2, 1f))

        val theme = barButton("◐", Color.WHITE) { toggleNightMode() }.apply { contentDescription = "Alternar tema claro e escuro" }
        val smaller = barButton("A-", Color.WHITE) { changeTextSize(-1f) }.apply { contentDescription = "Diminuir letra" }
        val larger = barButton("A+", Color.WHITE) { changeTextSize(1f) }.apply { contentDescription = "Aumentar letra" }
        setContentView(frame(item.name, textList, footer, listOf(theme, smaller, larger)))
        applyTextColors()

        val uri = Uri.parse(item.uri)
        worker.execute {
            val result = try {
                readText(uri)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (result == null) {
                    fail("Não foi possível abrir este arquivo de texto.")
                    return@runOnUiThread
                }
                if (result.chunks.isEmpty()) {
                    fail("Este arquivo de texto está vazio.")
                    return@runOnUiThread
                }
                textAdapter.chunks = result.chunks
                textAdapter.notifyDataSetChanged()
                textLoaded = true
                if (result.truncated) {
                    Toast.makeText(this, "Arquivo muito grande: exibindo só o começo.", Toast.LENGTH_LONG).show()
                }
                restoreFraction(item.position)
            }
        }
    }

    private inner class ChunkAdapter : BaseAdapter() {
        var chunks: List<String> = emptyList()

        override fun getCount() = chunks.size
        override fun getItem(position: Int) = chunks[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView as? TextView ?: TextView(this@ReaderActivity).apply {
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.35f)
                setPadding(dp(20), 0, dp(20), 0)
                setTextIsSelectable(true)
            }
            view.textSize = textSizeSp
            view.setTextColor(if (nightMode) NIGHT_TEXT else Palette.ink)
            view.text = chunks[position]
            return view
        }
    }

    private fun applyTextColors() {
        textList.setBackgroundColor(if (nightMode) NIGHT_BACKGROUND else Palette.paper)
        textList.cacheColorHint = if (nightMode) NIGHT_BACKGROUND else Palette.paper
    }

    private fun toggleNightMode() {
        nightMode = !nightMode
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_NIGHT, nightMode).apply()
        applyTextColors()
        textAdapter.notifyDataSetChanged()
    }

    private fun changeTextSize(delta: Float) {
        val fraction = textFraction()
        textSizeSp = (textSizeSp + delta).coerceIn(12f, 32f)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_TEXT_SIZE, textSizeSp).apply()
        textAdapter.notifyDataSetChanged()
        if (textLoaded) restoreFraction(fraction)
    }

    /**
     * Posição de leitura como fração de 0 a 10000: bloco visível no topo mais quanto dele já
     * passou. Não depende do tamanho da fonte nem da rotação, e é o mesmo formato da versão 0.2.
     */
    private fun textFraction(): Int {
        val count = textAdapter.count
        if (count == 0 || textList.childCount == 0) return 0
        if (!textList.canScrollVertically(1)) return 10_000
        val slot = readingSlot()
        val child = textList.getChildAt(slot)
        val passed = ((textList.paddingTop - child.top).toDouble() / child.height.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return ((textList.firstVisiblePosition + slot + passed) / count * 10_000).roundToInt().coerceIn(0, 10_000)
    }

    /**
     * Filho da lista que está na linha de leitura (logo abaixo do padding do topo). Como o padding
     * não corta o conteúdo, o bloco anterior pode aparecer nele e ser o primeiro filho visível.
     */
    private fun readingSlot(): Int {
        var slot = 0
        while (slot < textList.childCount - 1 && textList.getChildAt(slot).bottom <= textList.paddingTop) slot++
        return slot
    }

    /**
     * Leva a lista até [fraction]. Primeiro posiciona o bloco no topo; quando o layout termina e a
     * altura dele é conhecida, rola o que falta dentro do bloco.
     */
    private fun restoreFraction(fraction: Int) {
        val count = textAdapter.count
        if (count == 0) return
        // O epsilon evita que 5000/10000 de 76 blocos vire 37,999… e caia no bloco anterior.
        val exact = fraction.coerceIn(0, 10_000).toDouble() * count / 10_000 + 1e-6
        val index = floor(exact).toInt().coerceIn(0, count - 1)
        val passed = (exact - index).coerceIn(0.0, 1.0)
        // O deslocamento já é medido a partir do padding do topo.
        textList.setSelectionFromTop(index, 0)

        textList.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var layouts = 0
            override fun onGlobalLayout() {
                val child = textList.getChildAt(index - textList.firstVisiblePosition)
                val ready = child != null && index >= textList.firstVisiblePosition
                if (!ready && ++layouts < MAX_RESTORE_LAYOUTS) return
                textList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (ready && passed > 0.001) scrollTextBy((passed * child!!.height).toInt())
                textList.post { updateProgressLabel() }
            }
        })
        textList.requestLayout()
    }

    /** O ListView limita cada rolagem à própria altura; blocos maiores que a tela pedem vários passos. */
    private fun scrollTextBy(pixels: Int) {
        val step = (textList.height - textList.paddingTop - textList.paddingBottom - 2).coerceAtLeast(1)
        var remaining = pixels
        while (remaining > 0 && textList.canScrollVertically(1)) {
            val delta = minOf(step, remaining)
            textList.scrollListBy(delta)
            remaining -= delta
        }
    }

    private fun updateProgressLabel() {
        progressLabel.text = "${textFraction() / 100}% lido"
    }

    private class TextResult(val chunks: List<String>, val truncated: Boolean)

    /** Roda na thread worker. */
    private fun readText(uri: Uri): TextResult? {
        val input = contentResolver.openInputStream(uri) ?: return null
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
        return TextResult(TextChunker.chunk(text), truncated)
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        private const val PREFS = "reader"
        private const val KEY_TEXT_SIZE = "text_size_sp"
        private const val KEY_NIGHT = "night_mode"
        private const val DEFAULT_TEXT_SP = 18f
        private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
        private const val MAX_RESTORE_LAYOUTS = 5
        private const val MAX_ZOOM = 3f
        private const val DOUBLE_TAP_ZOOM = 2f
        private const val MAX_BITMAP_PIXELS = 8_000_000f
        private val NIGHT_BACKGROUND = Color.rgb(18, 22, 28)
        private val NIGHT_TEXT = Color.rgb(214, 208, 196)
    }
}
