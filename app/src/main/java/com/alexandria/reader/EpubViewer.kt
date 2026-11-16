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
import kotlin.math.roundToLong

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
    @Volatile
    private var cssSnapshot = ""
    private var chapterReady = false
    /** Modo páginas: o paginador por linhas foi instalado no capítulo aberto. */
    private var pagerReady = false
    /** Modo páginas: a tela mostra a última página do capítulo. */
    private var pagerLast = false
    private var textSizeSp = ReadingPrefs.textSize(context)
    private var comfort = ComfortStore.load(context)

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

    override val exactScale = EXACT_SCALE

    override fun exactPosition(): Long {
        val total = pageCount()
        if (total == 0) return 0
        return ((chapter + chapterFraction()) / total * 10_000L * EXACT_SCALE).roundToLong().coerceIn(0, 10_000L * EXACT_SCALE)
    }

    override fun goToExact(position: Long) {
        val total = pageCount()
        if (total == 0) return
        val exact = position.coerceIn(0, 10_000L * EXACT_SCALE).toDouble() * total / (10_000.0 * EXACT_SCALE) + 1e-9
        val index = exact.toInt().coerceIn(0, total - 1)
        openChapter(index, (exact - index).coerceIn(0.0, 1.0))
    }

    /** No começo de um capítulo, a posição inteira (0 a 10000) cairia no capítulo anterior. */
    override fun describeExact(position: Long): String {
        val total = pageCount()
        if (total == 0) return "${position / (100 * EXACT_SCALE)}%"
        val index = (position.toDouble() * total / (10_000.0 * EXACT_SCALE) + 1e-6).toInt().coerceIn(0, total - 1)
        return "Cap. ${index + 1} · ${position / (100 * EXACT_SCALE)}%"
    }

    /** Uma tela, na mesma escala de [exactPosition]. */
    override fun screenSpan(): Long {
        val total = pageCount()
        val range = contentRange()
        if (total == 0 || !chapterReady) return 1
        val screen = if (range <= 0) 1.0 else (web.height.toDouble() / (range + web.height)).coerceIn(0.0, 1.0)
        return (screen / total * 10_000L * EXACT_SCALE).roundToLong().coerceAtLeast(1)
    }

    override fun atEnd() = chapterReady && chapter == pageCount() - 1 &&
        if (paged && pagerReady) pagerLast else !web.canScrollVertically(1)

    override fun describe(position: Int): String {
        val total = pageCount()
        if (total == 0) return "${position / 100}%"
        val index = (position.toDouble() * total / 10_000).toInt().coerceIn(0, total - 1)
        return "Cap. ${index + 1} · ${position / 100}%"
    }

    // ---------- modo páginas ----------

    private val paged = ReadingPrefs.paged(context)
    private val edgeTaps = EdgeTaps(context, web, { paged }) { turnPage(it) }

    override fun dispatchTouch(event: android.view.MotionEvent): Boolean? {
        if (book == null) return null
        termTaps.onTouchEvent(event)
        return if (paged) edgeTaps.dispatch(event) else null
    }

    // ---------- glossário ----------

    @Volatile
    private var glossary: List<GlossaryTerm> = emptyList()
    private var onTermTap: (GlossaryTerm) -> Unit = {}

    override fun setGlossary(terms: List<GlossaryTerm>, onTap: (GlossaryTerm) -> Unit) {
        val changed = terms != glossary
        glossary = terms
        onTermTap = onTap
        // O capítulo é marcado ao carregar: com termos novos, recarrega no mesmo ponto.
        if (changed && book != null) goTo(position())
    }

    /**
     * Toque simples no texto: pergunta à página o que está sob o dedo. Uma palavra sublinhada do
     * glossário mostra a definição; um link (o mais perto do dedo, num raio de [LINK_RADIUS_DP]) é
     * seguido pelo app: nota de rodapé em janela, pulo dentro do capítulo ou outro capítulo.
     */
    private val termTaps = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
            if (!chapterReady) return false
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            val density = context.resources.displayMetrics.density
            val x = (e.rawX - location[0]) / density
            val y = (e.rawY - location[1]) / density
            web.evaluateJavascript(
                "(function(){var e=document.elementFromPoint($x,$y);var t=e&&e.closest?e.closest('.alx-term'):null;" +
                    "if(t)return 'T'+t.getAttribute('data-term');var l=window.alxLinks?alxLinks.near($x,$y,$LINK_RADIUS_DP):'';return l?'L'+l:'';})()"
            ) { raw ->
                val value = jsString(raw)
                when {
                    value.startsWith("T") -> {
                        val id = value.drop(1).toLongOrNull() ?: return@evaluateJavascript
                        glossary.firstOrNull { it.id == id }?.let(onTermTap)
                    }
                    value.startsWith("L") -> {
                        host.tapHandled()
                        val link = try {
                            org.json.JSONObject(value.drop(1))
                        } catch (e: org.json.JSONException) {
                            return@evaluateJavascript
                        }
                        followLink(link)
                    }
                }
            }
            return false
        }
    })

    /** Resultado de evaluateJavascript (literal JSON) como texto. */
    private fun jsString(raw: String?): String = try {
        org.json.JSONArray("[${raw ?: "null"}]").optString(0, "").let { if (it == "null") "" else it }
    } catch (e: org.json.JSONException) {
        ""
    }

    // ---------- links e notas de rodapé ----------

    private fun followLink(link: org.json.JSONObject) {
        val current = book ?: return
        val from = current.chapters.getOrNull(chapter) ?: return
        val target = BookLinks.resolve(from, link.optString("href")) ?: return
        val text = link.optString("text")
        // O número de volta, dentro da própria nota, leva de volta ao texto: é um pulo comum.
        val likely = !link.optBoolean("inNote") && (link.optBoolean("noteref") ||
            (BookLinks.looksLikeNoteMark(text) && (link.optBoolean("raised") || text.startsWith("["))))
        val index = current.chapters.indexOf(target.path)
        val title = if (BookLinks.looksLikeNoteMark(text)) "Nota ${text.trim('[', ']', '(', ')', '.')}" else "Nota"
        if (index == chapter) {
            val fragment = target.fragment ?: return jumpWithin(null)
            if (!likely) return jumpWithin(fragment)
            readNote(fragment, null, likely, false) { note ->
                if (note != null) showNote(title, note) { jumpWithin(fragment) } else jumpWithin(fragment)
            }
            return
        }
        val file = zip ?: return
        if (index >= 0 && (target.fragment == null || !likely)) return jumpToChapter(index, target.fragment)
        // Nota em outro arquivo (notas no fim do livro) ou arquivo fora da ordem de leitura.
        host.worker.execute {
            val html = try {
                file.getEntry(target.path)?.let { entry -> file.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) } }
            } catch (e: Exception) {
                null
            }
            onUi {
                if (html == null) return@onUi
                readNote(target.fragment, html, likely, index < 0) { note ->
                    when {
                        note != null -> showNote(title, note, if (index >= 0) ({ jumpToChapter(index, target.fragment) }) else null)
                        index >= 0 -> jumpToChapter(index, target.fragment)
                    }
                }
            }
        }
    }

    /** Pergunta à página o texto da nota [id] (no capítulo aberto, ou em [html] de outro arquivo). */
    private fun readNote(id: String?, html: String?, likely: Boolean, force: Boolean, done: (org.json.JSONObject?) -> Unit) {
        val idArg = id?.let { org.json.JSONObject.quote(it) } ?: "null"
        val htmlArg = html?.let { org.json.JSONObject.quote(it) } ?: "null"
        web.evaluateJavascript("window.alxLinks?alxLinks.note($idArg,$htmlArg,$likely,$force):''") { raw ->
            val value = jsString(raw)
            done(if (value.isEmpty()) null else try {
                org.json.JSONObject(value)
            } catch (e: org.json.JSONException) {
                null
            })
        }
    }

    /** Janela com o texto da nota: lê-se e fecha, sem sair do ponto de leitura. */
    private fun showNote(title: String, note: org.json.JSONObject, goToNote: (() -> Unit)?) {
        @Suppress("DEPRECATION")
        val body = if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(note.optString("html"), Html.FROM_HTML_MODE_COMPACT)
        else Html.fromHtml(note.optString("html"))
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(body.ifBlank { note.optString("text") })
            .setPositiveButton("Fechar", null)
        if (goToNote != null) builder.setNeutralButton("Ir para a nota") { _, _ -> goToNote() }
        builder.show()
    }

    /** Pula para [fragment] no capítulo aberto (sem recarregar) e oferece o "Voltar". */
    private fun jumpWithin(fragment: String?) {
        host.offerReturn(exactPosition())
        if (fragment == null) {
            web.scrollTo(0, 0)
            return
        }
        val id = org.json.JSONObject.quote(fragment)
        web.evaluateJavascript(
            "(function(){var e=document.getElementById($id);if(!e)return;e.scrollIntoView();window.scrollBy(0,-12);" +
                "if(window.alxPager)alxPager.snap();})()"
        ) {
            updateLabel()
            host.onPositionChanged()
        }
    }

    private fun jumpToChapter(index: Int, fragment: String?) {
        host.offerReturn(exactPosition())
        openChapter(index, 0.0, fragment)
    }

    /** Rola uma tela; no fim (ou começo) do capítulo, passa para o próximo (ou o anterior, já no fim). */
    private fun turnPage(direction: Int) {
        if (!chapterReady) return
        if (pagerReady) {
            // Páginas com linhas inteiras: o paginador do capítulo diz se ainda há página ou se acabou.
            web.evaluateJavascript(if (direction > 0) "alxPager.next()" else "alxPager.prev()") { raw ->
                when (raw?.trim('"')) {
                    "end" -> if (chapter < pageCount() - 1) {
                        openChapter(chapter + 1, 0.0)
                        flash(web)
                    }
                    "start" -> if (chapter > 0) {
                        openChapter(chapter - 1, 1.0)
                        flash(web)
                    }
                    null, "null" -> Unit
                    else -> {
                        pagerLast = raw.trim('"') == "last"
                        flash(web)
                        updateLabel()
                        host.onPositionChanged()
                    }
                }
            }
            return
        }
        val line = (textSizeSp * context.resources.displayMetrics.scaledDensity * 1.55f).toInt()
        val step = PageTurn.scrollStep(web.height, (line * 1.5f).toInt())
        when {
            web.canScrollVertically(direction) -> {
                val target = (web.scrollY + step * direction).coerceIn(0, web.scrollableHeight())
                web.scrollTo(0, target)
            }
            direction > 0 && chapter < pageCount() - 1 -> openChapter(chapter + 1, 0.0)
            direction < 0 && chapter > 0 -> openChapter(chapter - 1, 1.0)
            else -> return
        }
        flash(web)
    }

    override fun menuActions() = listOf(
        "Sumário" to { showContents() },
        "Letra menor" to { changeTextSize(-1f) },
        "Letra maior" to { changeTextSize(1f) }
    )

    // ---------- capítulos ----------

    /** Quanto o texto do capítulo rola (sem a folga em branco que o modo páginas põe no fim). */
    private fun contentRange(): Int =
        (web.scrollableHeight() - if (pagerReady) web.height else 0).coerceAtLeast(0)

    private fun chapterFraction(): Double {
        val range = contentRange()
        return if (range <= 0) 0.0 else (web.scrollY.toDouble() / range).coerceIn(0.0, 1.0)
    }

    private fun openChapter(index: Int, fraction: Double, anchor: String? = null) {
        val chapters = book?.chapters ?: return
        val file = zip ?: return
        if (index !in chapters.indices) return
        chapter = index
        pagerReady = false
        pagerLast = false
        pendingFraction = fraction
        pendingAnchor = anchor
        chapterReady = false
        val token = ++loadToken
        updateLabel()
        val path = chapters[index]
        val terms = glossary
        val css = readerCss()
        cssSnapshot = css
        host.worker.execute {
            val html = try {
                file.getEntry(path)?.let { entry ->
                    file.getInputStream(entry).use { input ->
                        // Termos do glossário viram <span class="alx-term"> antes do preparo do HTML.
                        val marked = Glossary.markHtml(String(input.readBytes(), Charsets.UTF_8), terms)
                        EpubHtml.prepare(marked, css)
                    }
                }
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
                web.scrollTo(0, (fraction * contentRange()).roundToInt())
                // Imagens carregadas por último podem mudar a altura: confere de novo um pouco depois.
                web.postDelayed({
                    val anchor = pendingAnchor
                    pendingAnchor = null
                    if (anchor != null) {
                        val id = org.json.JSONObject.quote(anchor)
                        web.evaluateJavascript("var e=document.getElementById($id); if(e) e.scrollIntoView();", null)
                    } else if (fraction > 0) {
                        web.scrollTo(0, (fraction * contentRange()).roundToInt())
                    }
                    chapterReady = true
                    installPager()
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

    private fun applyBackground() {
        web.setBackgroundColor(comfort.theme.background)
    }

    private val reloadForComfort = Runnable { if (book != null) goTo(position()) }

    override fun applyComfort(comfort: Comfort) {
        this.comfort = comfort
        applyBackground()
        // Arrastar um controle manda várias mudanças seguidas: o capítulo é redesenhado só quando para.
        web.removeCallbacks(reloadForComfort)
        web.postDelayed(reloadForComfort, 350)
    }

    private fun readerCss(): String = Comfort.epubCss(comfort, ReaderFonts.epubFontFaces(BASE))

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
            // Depois de pular para o resultado, a página volta a começar numa linha inteira.
            if (pagerReady) web.postDelayed({ if (pagerReady) web.evaluateJavascript("alxPager.snap()", null) }, 400)
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

    // ---------- páginas com linhas inteiras ----------

    /** Instala o paginador no capítulo aberto (só no modo páginas) e encaixa a primeira linha. */
    private fun installPager() {
        if (!paged) return
        val color = String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and comfort.theme.background)
        web.evaluateJavascript("$PAGER_JS;alxPager.setup('$color');alxPager.snap()") { raw ->
            val state = raw?.trim('"') ?: return@evaluateJavascript
            if (state == "last" || state == "more") {
                pagerReady = true
                pagerLast = state == "last"
                host.onPositionChanged()
            }
        }
    }

    // ---------- conteúdo servido de dentro do ZIP ----------

    private inner class Client : WebViewClient() {
        // null = o WebView carrega sozinho. Isso vale para o próprio capítulo, que entra como
        // "data:" pelo loadDataWithBaseURL: recusar esse endereço deixava a página em branco
        // com "net::ERR_HTTP_RESPONSE_CODE_FAILURE".
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            when (route(request.url.toString())) {
                Route.PASS -> null
                Route.BOOK -> serve(request.url.toString())
                Route.BLOCK -> notFound()
            }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!url.startsWith(BASE)) return true // links externos: o app é offline, ignora
            val path = url.removePrefix(BASE).substringBefore('#')
            val index = book?.chapters?.indexOf(ZipPaths.resolve("", path)) ?: -1
            if (index >= 0) openChapter(index, 0.0, request.url.fragment)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            // Os links passam a ser decididos pelo app (notas em janela, "Voltar").
            view.evaluateJavascript(BookLinks.SCRIPT, null)
            restoreScroll()
        }
    }

    /** Arquivos de dentro do livro (imagens, CSS, fontes). Roda numa thread do WebView. */
    private fun serve(url: String): WebResourceResponse {
        val path = ZipPaths.resolve("", url.removePrefix(BASE))
        if (path.startsWith(APP_FONTS)) return appFont(path.removePrefix(APP_FONTS))
        val file = zip ?: return notFound()
        val entry = file.getEntry(path) ?: return notFound()
        val type = mimeType(path)
        return try {
            val stream = if (type == "text/html" || type == "application/xhtml+xml") {
                // HTML do livro pedido diretamente (ex.: iframe): sai já com a política de segurança.
                ByteArrayInputStream(prepareHtml(file.getInputStream(entry).use { it.readBytes() }, cssSnapshot).toByteArray())
            } else {
                file.getInputStream(entry)
            }
            val charset = if (type.startsWith("text/") || type.endsWith("xml")) "utf-8" else null
            WebResourceResponse(type, charset, 200, "OK", mapOf("Cache-Control" to "no-store"), stream)
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

    /** Fontes do próprio app (Atkinson Hyperlegible), pedidas pelo CSS de leitura. */
    private fun appFont(name: String): WebResourceResponse {
        if (!name.matches(Regex("[a-z0-9-]+\\.woff2"))) return notFound()
        return try {
            WebResourceResponse("font/woff2", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400"), context.assets.open("fonts/$name"))
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    enum class Route { PASS, BOOK, BLOCK }

    companion object {
        /**
         * Paginador por linhas, injetado em cada capítulo no modo páginas. Acha a linha cortada na
         * borda de baixo (pelo ponto na tela), cobre-a com a cor da página e, ao virar, rola até ela
         * ficar no topo. Uma folga em branco no fim deixa a última página subir sem repetir linhas.
         */
        private val PAGER_JS = """
            (function(){
            if (window.alxPager) return;
            var P = {top: 10, bottom: 10, masks: null, color: '#ffffff', raf: 0, spacer: null};
            function win(){ var b = window.innerHeight - P.bottom; return {t: P.top, b: b, h: b - P.top}; }
            function box(el, y){ var r = el.getBoundingClientRect(); return (r.top <= y && r.bottom >= y) ? {top: r.top, bottom: r.bottom} : null; }
            function lineAt(y){
              var xs = [0.5, 0.3, 0.7, 0.15, 0.85], W = window.innerWidth;
              for (var i = 0; i < xs.length; i++) {
                var x = W * xs[i], el = document.elementFromPoint(x, y);
                if (!el) continue;
                var tag = (el.tagName || '').toLowerCase();
                if (tag == 'img' || tag == 'svg' || tag == 'image' || tag == 'video' || tag == 'canvas') { var b = box(el, y); if (b) return b; continue; }
                var c = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
                if (!c || c.startContainer.nodeType != 3) continue;
                var n = c.startContainer;
                for (var d = 0; d >= -1; d--) {
                  var o = Math.min(c.startOffset + d, n.length - 1);
                  if (o < 0) continue;
                  var rg = document.createRange(); rg.setStart(n, o); rg.setEnd(n, o + 1);
                  var rs = rg.getClientRects();
                  for (var k = 0; k < rs.length; k++) {
                    var r = rs[k];
                    if (r.height > 0 && r.top <= y && r.bottom >= y) {
                      var lh = parseFloat(getComputedStyle(n.parentElement).lineHeight);
                      if (isNaN(lh)) lh = r.height * 1.2;
                      var half = Math.max(0, (lh - r.height) / 2);
                      return {top: r.top - half, bottom: r.bottom + half};
                    }
                  }
                }
              }
              return null;
            }
            function contentEnd(){ return P.spacer ? P.spacer.getBoundingClientRect().top : document.documentElement.scrollHeight - window.scrollY; }
            function state(){ return contentEnd() <= win().b + 1 ? 'last' : 'more'; }
            function cut(w){ var l = lineAt(w.b - 0.5); return (l && l.bottom > w.b + 0.5 && l.top > w.t + 0.5) ? l : null; }
            function mask(){
              var d = document.createElement('div'), s = d.style;
              d.setAttribute('aria-hidden', 'true');
              s.position = 'fixed'; s.left = '0'; s.right = '0'; s.zIndex = '2147483647'; s.pointerEvents = 'none'; s.background = P.color;
              document.documentElement.appendChild(d);
              return d;
            }
            function update(){
              if (!P.masks) P.masks = [mask(), mask()];
              var w = win(), c = cut(w), start = c ? c.top : w.b;
              var a = P.masks[0].style, z = P.masks[1].style;
              a.top = '0px'; a.height = w.t + 'px';
              z.top = start + 'px'; z.height = Math.max(0, window.innerHeight - start) + 'px';
            }
            function later(){ if (!P.raf) P.raf = requestAnimationFrame(function(){ P.raf = 0; update(); }); }
            window.alxPager = {
              setup: function(color){
                P.color = color;
                if (!P.spacer && document.body) {
                  P.spacer = document.createElement('div');
                  P.spacer.setAttribute('aria-hidden', 'true');
                  P.spacer.style.height = window.innerHeight + 'px';
                  document.body.appendChild(P.spacer);
                }
                window.addEventListener('scroll', later, {passive: true});
                window.addEventListener('resize', later);
                update();
                return state();
              },
              next: function(){
                var w = win();
                if (contentEnd() <= w.b + 1) return 'end';
                var c = cut(w), d = c ? c.top - w.t : w.h;
                if (d < w.h * 0.25) d = w.h;
                window.scrollBy(0, Math.round(d));
                update();
                return state();
              },
              prev: function(){
                if (window.scrollY <= 0) return 'start';
                var w = win();
                window.scrollBy(0, -Math.round(w.h));
                var s = lineAt(w.t + 0.5);
                if (s && s.top < w.t - 0.5 && s.bottom - w.t < w.h * 0.75) window.scrollBy(0, Math.round(s.bottom - w.t));
                update();
                return state();
              },
              snap: function(){
                var w = win(), s = lineAt(w.t + 0.5);
                if (s && s.top < w.t - 0.5 && w.t - s.top < w.h * 0.5) window.scrollBy(0, Math.floor(s.top - w.t));
                update();
                return state();
              }
            };
            })()
        """.trimIndent()

        private const val BASE = "https://book.alexandria/"
        /** Raio (em dp, igual ao pixel CSS da página) em que um toque ainda acerta um link. */
        private const val LINK_RADIUS_DP = 22
        private const val APP_FONTS = "__alexandria/fonts/"

        /**
         * O que fazer com um pedido do WebView: endereços sem rede (data:, about:, blob:) seguem
         * normalmente; os do livro são servidos de dentro do ZIP; qualquer outro é recusado.
         */
        fun route(url: String): Route = when {
            url.startsWith(BASE) -> Route.BOOK
            url.startsWith("data:") || url.startsWith("about:") || url.startsWith("blob:") -> Route.PASS
            else -> Route.BLOCK
        }

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
