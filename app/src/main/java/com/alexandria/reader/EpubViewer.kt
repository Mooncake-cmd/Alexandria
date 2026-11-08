package com.alexandria.reader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.text.Html
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** WebView que expõe o tamanho rolável do conteúdo (protegido na classe original). */
@SuppressLint("ViewConstructor")
class BookWebView(context: Context) : WebView(context) {
    /** Quanto o conteúdo pode rolar além da altura visível, em pixels. */
    fun scrollableHeight(): Int = (computeVerticalScrollRange() - height).coerceAtLeast(0)
}

/**
 * Leitor de EPUB com o WebView do sistema. Cada capítulo é servido direto de dentro do ZIP por um
 * endereço interno (book.alexandria): o app não tem permissão de internet, então nada sai do aparelho,
 * e os scripts do livro ficam bloqueados por uma política de segurança de conteúdo.
 *
 * A posição é uma fração de 0 a 10000 do livro: capítulo atual mais o quanto dele já foi rolado.
 */
class EpubViewer(
    host: ReaderHost,
    /** Arquivo EPUB a abrir (roda em thread de fundo). MOBI/AZW3 chegam aqui já convertidos. */
    private val epubFile: (LibraryItem) -> java.io.File = { item -> BookFiles.localCopy(host.activity, item) }
) : BookViewer(host) {
    private var zip: ZipFile? = null
    private var book: EpubPackage? = null
    private var chapter = 0
    /** Fração do capítulo (0 a 1) a restaurar quando ele terminar de carregar. */
    private var pendingFraction = 0.0
    private var pendingFind: Pair<String, Int>? = null
    private var pendingAnchor: String? = null
    private var loadToken = 0
    private var chapterReady = false
    private var textSizeSp = ReadingPrefs.textSize(context)
    private var night = ReadingPrefs.night(context)

    private val web = BookWebView(context).apply {
        @SuppressLint("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true // só para ler a seleção do usuário; os scripts do livro são bloqueados
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.builtInZoomControls = false
        settings.textZoom = textZoom()
        isVerticalScrollBarEnabled = true
        webViewClient = Client()
        setOnScrollChangeListener { _, _, _, _, _ ->
            if (chapterReady) {
                updateLabel()
                host.onPositionChanged()
            }
        }
    }
    private val prevButton = barButton("‹ Cap.", Palette.ink, "Capítulo anterior") { openChapter(chapter - 1, 0.0) }
    private val nextButton = barButton("Cap. ›", Palette.ink, "Próximo capítulo") { openChapter(chapter + 1, 0.0) }
    private val label = context.label("Abrindo…", 12f, Palette.muted).apply {
        gravity = Gravity.CENTER
        minHeight = context.dp(48)
        maxLines = 2
        contentDescription = "Sumário"
        setOnClickListener { showContents() }
    }

    override val body: View = web
    override val footer: View = footerBar().apply {
        addView(prevButton)
        addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        addView(nextButton)
    }
    override val loaded get() = book != null
    override val searchable = true

    init {
        applyBackground()
    }

    override fun open(item: LibraryItem, start: Int) {
        host.worker.execute {
            val result = try {
                val file = ZipFile(epubFile(item))
                try {
                    file to EpubPackage.read(file)
                } catch (e: Exception) {
                    file.close()
                    throw e
                }
            } catch (e: SecurityException) {
                onUi { host.fail("Sem permissão para abrir este arquivo. Remova-o e importe de novo.") }
                return@execute
            } catch (e: Exception) {
                onUi { host.fail("Não foi possível abrir este livro. Ele pode estar danificado ou protegido por DRM.") }
                return@execute
            }
            onUi {
                zip = result.first
                book = result.second
                goTo(start)
            }
        }
    }

    override fun close() {
        web.stopLoading()
        web.destroy()
        val file = zip
        zip = null
        host.worker.execute { file?.close() }
    }

    override fun pageCount() = book?.chapters?.size ?: 0

    override fun position(): Int {
        val total = pageCount()
        if (total == 0) return 0
        return ((chapter + chapterFraction()) / total * 10_000).roundToInt().coerceIn(0, 10_000)
    }

    override fun goTo(position: Int) {
        val total = pageCount()
        if (total == 0) return
        val exact = position.coerceIn(0, 10_000).toDouble() * total / 10_000 + 1e-6
        val index = exact.toInt().coerceIn(0, total - 1)
        openChapter(index, (exact - index).coerceIn(0.0, 1.0))
    }

    override fun atEnd() = chapterReady && chapter == pageCount() - 1 && !web.canScrollVertically(1)

    override fun describe(position: Int): String {
        val total = pageCount()
        if (total == 0) return "${position / 100}%"
        val index = (position.toDouble() * total / 10_000).toInt().coerceIn(0, total - 1)
        return "Cap. ${index + 1} · ${position / 100}%"
    }

    override fun menuActions() = listOf(
        "Sumário" to { showContents() },
        "Letra menor" to { changeTextSize(-1f) },
        "Letra maior" to { changeTextSize(1f) },
        "Tema claro/escuro" to { toggleNight() }
    )

    // ---------- capítulos ----------

    private fun chapterFraction(): Double {
        val range = web.scrollableHeight()
        return if (range <= 0) 0.0 else (web.scrollY.toDouble() / range).coerceIn(0.0, 1.0)
    }

    private fun openChapter(index: Int, fraction: Double, anchor: String? = null) {
        val chapters = book?.chapters ?: return
        val file = zip ?: return
        if (index !in chapters.indices) return
        chapter = index
        pendingFraction = fraction
        pendingAnchor = anchor
        chapterReady = false
        val token = ++loadToken
        updateLabel()
        val path = chapters[index]
        val css = readerCss()
        host.worker.execute {
            val html = try {
                file.getEntry(path)?.let { entry -> file.getInputStream(entry).use { prepareHtml(it.readBytes(), css) } }
            } catch (e: Exception) {
                null
            }
            onUi {
                if (token != loadToken) return@onUi
                if (html == null) {
                    host.fail("Este capítulo do EPUB está danificado.")
                    return@onUi
                }
                web.loadDataWithBaseURL(BASE + path, html, mimeType(path), "utf-8", null)
            }
        }
    }

    private fun updateLabel() {
        val current = book ?: return
        val title = current.chapterTitles.getOrNull(chapter)
        val prefix = "Cap. ${chapter + 1} de ${current.chapters.size}"
        label.text = if (title != null) "$title\n$prefix · ${position() / 100}%" else "$prefix · ${position() / 100}%"
        setEnabledLook(prevButton, chapter > 0)
        setEnabledLook(nextButton, chapter < current.chapters.size - 1)
    }

    private fun showContents() {
        val current = book ?: return
        val names = current.chapters.indices.map { i ->
            val title = current.chapterTitles[i] ?: "Capítulo ${i + 1}"
            if (i == chapter) "▸ $title" else title
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Sumário")
            .setItems(names) { _, which -> openChapter(which, 0.0) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /** Depois do desenho (imagens incluídas), a altura do capítulo já é a real: restaura a rolagem. */
    private fun restoreScroll() {
        val fraction = pendingFraction
        web.postVisualStateCallback(chapter.toLong(), object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (requestId != chapter.toLong()) return
                web.scrollTo(0, (fraction * web.scrollableHeight()).roundToInt())
                // Imagens carregadas por último podem mudar a altura: confere de novo um pouco depois.
                web.postDelayed({
                    val anchor = pendingAnchor
                    pendingAnchor = null
                    if (anchor != null) {
                        val id = org.json.JSONObject.quote(anchor)
                        web.evaluateJavascript("var e=document.getElementById($id); if(e) e.scrollIntoView();", null)
                    } else if (fraction > 0) {
                        web.scrollTo(0, (fraction * web.scrollableHeight()).roundToInt())
                    }
                    chapterReady = true
                    updateLabel()
                    host.onPositionChanged()
                    pendingFind?.let { (term, nth) -> findInChapter(term, nth) }
                    pendingFind = null
                }, 250)
            }
        })
    }

    // ---------- aparência ----------

    private fun textZoom() = (textSizeSp / ReadingPrefs.DEFAULT_TEXT_SP * 100).roundToInt()

    private fun changeTextSize(delta: Float) {
        val keep = position()
        textSizeSp = (textSizeSp + delta).coerceIn(12f, 32f)
        ReadingPrefs.setTextSize(context, textSizeSp)
        web.settings.textZoom = textZoom()
        goTo(keep)
    }

    private fun toggleNight() {
        night = !night
        ReadingPrefs.setNight(context, night)
        applyBackground()
        goTo(position())
    }

    private fun applyBackground() {
        web.setBackgroundColor(if (night) ReadingPrefs.NIGHT_BACKGROUND else Palette.paper)
    }

    private fun readerCss(): String {
        val background = if (night) "#12161c" else "#f5f2eb"
        val text = if (night) "#d6d0c4" else "#162234"
        return """
            html, body { background: $background !important; color: $text !important; }
            body { margin: 0 !important; padding: 20px 6% 48px !important; line-height: 1.55; word-wrap: break-word; }
            img, svg, video { max-width: 100% !important; height: auto !important; }
            a { color: #b78942 !important; }
            pre, table { max-width: 100%; overflow-x: auto; }
        """.trimIndent()
    }

    // ---------- seleção e busca ----------

    override fun selection(done: (excerpt: String, position: Int) -> Unit): Boolean {
        if (!chapterReady || !web.hasFocus()) return false
        val position = position()
        web.evaluateJavascript("window.getSelection().toString()") { raw ->
            // O resultado vem como literal JSON ("texto"): tira as aspas e os escapes.
            val text = try {
                org.json.JSONArray("[$raw]").optString(0, "")
            } catch (e: Exception) {
                ""
            }
            done(text.trim(), position)
        }
        return true
    }

    override fun search(term: String, done: (List<SearchHit>) -> Unit) {
        val current = book ?: return done(emptyList())
        val file = zip ?: return done(emptyList())
        host.worker.execute {
            val hits = mutableListOf<SearchHit>()
            val total = current.chapters.size
            for ((index, path) in current.chapters.withIndex()) {
                val text = try {
                    chapterText(file, path)
                } catch (e: Exception) {
                    continue
                }
                val offsets = TextSearch.findAll(text, term)
                offsets.forEachIndexed { nth, offset ->
                    val inChapter = offset.toDouble() / text.length.coerceAtLeast(1)
                    val position = ((index + inChapter) / total * 10_000).roundToInt()
                    val title = current.chapterTitles[index] ?: "Cap. ${index + 1}"
                    hits += SearchHit(position, "$title: " + TextViewer.excerpt(text, offset, term.trim().length), index, nth, term.length)
                }
                if (hits.size >= 500) break
            }
            onUi { done(hits) }
        }
    }

    override fun showHit(hit: SearchHit, term: String) {
        val index = hit.chunk
        val total = pageCount().coerceAtLeast(1)
        if (index == chapter && chapterReady) {
            findInChapter(term, hit.offset)
        } else {
            pendingFind = term to hit.offset
            openChapter(index, (hit.position.toDouble() * total / 10_000 - index).coerceIn(0.0, 1.0))
        }
    }

    /** Destaca o termo no capítulo aberto e pula para a ocorrência número [nth]. */
    private fun findInChapter(term: String, nth: Int) {
        web.setFindListener { active, matches, done ->
            if (!done || matches == 0) return@setFindListener
            val target = nth.coerceIn(0, matches - 1)
            if (active != target) {
                web.setFindListener(null)
                repeat((target - active + matches) % matches) { web.findNext(true) }
            }
        }
        web.findAllAsync(term.trim())
    }

    override fun clearSearch() {
        web.setFindListener(null)
        web.clearMatches()
    }

    // ---------- conteúdo servido de dentro do ZIP ----------

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            serve(request.url.toString())

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!url.startsWith(BASE)) return true // links externos: o app é offline, ignora
            val path = url.removePrefix(BASE).substringBefore('#')
            val index = book?.chapters?.indexOf(ZipPaths.resolve("", path)) ?: -1
            if (index >= 0) openChapter(index, 0.0, request.url.fragment)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            restoreScroll()
        }
    }

    /** Imagens, CSS e fontes do capítulo. Roda numa thread do WebView; nada sai para fora do livro. */
    private fun serve(url: String): WebResourceResponse {
        val file = zip
        if (!url.startsWith(BASE) || file == null) return notFound()
        val path = ZipPaths.resolve("", url.removePrefix(BASE))
        val entry = file.getEntry(path) ?: return notFound()
        val type = mimeType(path)
        // Um capítulo só entra por openChapter, já preparado; HTML pedido por fora (ex.: iframe) é recusado.
        if (type == "text/html" || type == "application/xhtml+xml") return notFound()
        return try {
            val charset = if (type.startsWith("text/") || type.endsWith("xml")) "utf-8" else null
            WebResourceResponse(type, charset, 200, "OK", mapOf("Cache-Control" to "no-store"), file.getInputStream(entry))
        } catch (e: Exception) {
            notFound()
        }
    }

    /**
     * Prepara o HTML de um capítulo: política de segurança logo no começo do <head> (antes de
     * qualquer script do livro) e o estilo de leitura do app no fim (vence o CSS do livro).
     */
    private fun prepareHtml(bytes: ByteArray, css: String): String =
        EpubHtml.prepare(String(bytes, Charsets.UTF_8), css)

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        private const val BASE = "https://book.alexandria/"

        fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "xhtml", "xht" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "otf" -> "font/otf"
            "ttf" -> "font/ttf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "xml", "opf", "ncx" -> "application/xml"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }

        /** Texto visível de um capítulo (sem tags), para a busca. */
        private fun chapterText(zip: ZipFile, path: String): String {
            val entry = zip.getEntry(path) ?: return ""
            val html = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
            val body = html.substringAfter("<body", html).substringAfter('>')
                .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            @Suppress("DEPRECATION")
            val text = if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY) else Html.fromHtml(body)
            return text.toString().replace('\uFFFC', ' ')
        }
    }
}
