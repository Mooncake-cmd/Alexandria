package com.alexandria.reader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tela de leitura. Monta a moldura comum (barra de título, busca, anotações, aviso de fim de
 * livro) e entrega o conteúdo a um [BookViewer] do formato. Salva o ponto de leitura ao sair.
 */
class ReaderActivity : Activity(), ReaderHost {
    private lateinit var database: LibraryDatabase
    private var current: LibraryItem? = null
    private var viewer: BookViewer? = null
    override val worker: ExecutorService = Executors.newSingleThreadExecutor()
    override val activity: Activity get() = this
    override var closed = false
        private set

    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchInfo: TextView
    private lateinit var finishBanner: LinearLayout
    private var ruler: ReadingRuler? = null
    /** Barra de cima (título, busca) e controles de baixo: somem na tela limpa. */
    private lateinit var topGroup: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var bottomGroup: LinearLayout
    private var lockButton: TextView? = null
    private lateinit var bodyFrame: FrameLayout
    private var controlsShown = true
    private var selectionActive = false
    private var lastTermTap = 0L
    /** Canto invisível que marca a página (com a fita azul quando marcada). */
    private lateinit var corner: BookmarkCorner
    /** Barra dos marcadores (✕, lista, "1 de 3", ‹ ›), aberta ao escolher um marcador da lista. */
    private lateinit var markBar: LinearLayout
    private lateinit var markLabel: TextView
    private lateinit var markPrev: TextView
    private lateinit var markNext: TextView
    /** Aviso curto embaixo ("Adicionado marcador…  MUDAR"). */
    private lateinit var snack: LinearLayout
    private lateinit var snackText: TextView
    private lateinit var snackAction: TextView
    /** "↩ Voltar" depois de seguir um link ou uma nota. */
    private lateinit var returnRow: LinearLayout
    private var returnTo: Long? = null
    private var marks: List<Bookmark> = emptyList()
    private var markMode = false
    private var markIndex = -1
    private var hits: List<SearchHit> = emptyList()
    private var hitIndex = -1
    private var searchTerm = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = LibraryDatabase(this)
        val item = database.find(intent.getLongExtra(EXTRA_ITEM_ID, -1L))
        if (item == null) {
            fail("Documento não encontrado na biblioteca.")
            return
        }
        val created = when (item.format) {
            "pdf" -> PageViewer.pdf(this)
            "cbz", "cbr" -> PageViewer.comic(this)
            "djvu" -> PageViewer.djvu(this)
            "mobi" -> EpubViewer(this) { BookFiles.mobiAsEpub(this, it) }
            "txt" -> TextViewer.txt(this)
            "docx" -> TextViewer.docx(this)
            "epub" -> EpubViewer(this)
            else -> null
        }
        if (created == null) {
            fail("O leitor de ${item.format.uppercase()} ainda não existe.")
            return
        }
        current = item
        viewer = created
        prepareFullScreen()
        setContentView(withComfortLayers(frame(item, created)))
        applyComfort(ComfortStore.load(this))
        if (ReadingPrefs.cleanScreen(this)) {
            showControls(false)
            if (ReadingPrefs.firstCleanScreenHint(this)) {
                Toast.makeText(this, "Toque no meio da tela para mostrar os controles.", Toast.LENGTH_LONG).show()
            }
        } else {
            showControls(true)
        }
        bookSecondsAtOpen = item.readingSeconds
        reloadGlossary()
        marks = database.bookmarksFor(item.id)
        // Uma anotação aberta pela aba Notas leva direto ao ponto dela.
        val fromNote = intent.getIntExtra(EXTRA_POSITION, -1).takeIf { it >= 0 }
        created.open(item, fromNote ?: item.position, if (fromNote != null) -1 else item.exactPosition)
    }

    override fun onResume() {
        super.onResume()
        if (current == null) return
        clock.start(System.currentTimeMillis())
        timerHandler.removeCallbacks(timerTick)
        timerHandler.post(timerTick)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        timerHandler.removeCallbacks(timerTick)
        val now = System.currentTimeMillis()
        clock.stop(now, Days.today(now))
        flushReadingTime()
    }

    // ---------- tempo de leitura ----------

    /** Conta o tempo com o livro aberto; para sozinho depois de 5 minutos sem toque. */
    private val clock = ReadingClock()
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var timerLabel: TextView
    private var bookSecondsAtOpen = 0L
    private var savedSessionSeconds = 0L
    private var ticks = 0

    private val timerTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            clock.tick(now, Days.today(now))
            if (++ticks % 30 == 0) flushReadingTime()
            updateTimerLabel(now)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private fun flushReadingTime() {
        val item = current ?: return
        for ((day, seconds) in clock.drain()) {
            database.addReadingTime(item.id, day, seconds)
            savedSessionSeconds += seconds
        }
    }

    private fun updateTimerLabel(now: Long) {
        val session = clock.sessionMs / 1000
        val total = bookSecondsAtOpen + session
        val paused = if (clock.isIdle(now)) " · pausado" else ""
        timerLabel.text = "⏱ ${ReadingTime.clock(session)} · ${ReadingTime.format(total)} neste livro$paused"
    }

    override fun onDestroy() {
        closed = true
        viewer?.close()
        worker.shutdown()
        database.close()
        super.onDestroy()
    }

    override fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun saveProgress() {
        val item = current ?: return
        val view = viewer ?: return
        if (view.loaded) database.updateProgress(item.id, view.position(), view.pageCount(), view.exactPosition())
    }

    /** O dedo começou na alça da régua: o gesto inteiro é dela, não do livro. */
    private var rulerGesture = false
    /** Toque que começou sobre a faixa da régua (com alça escondida): se for um toque rápido, mostra a alça. */
    private var bandDown: Triple<Float, Float, Long>? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) clock.interaction(System.currentTimeMillis())
        if (current == null) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            controlGesture = hits(topGroup, ev) || hits(bottomControls, ev) || hits(finishBanner, ev) || lockButton?.let { hits(it, ev) } == true ||
                hits(markBar, ev) || hits(snack, ev) || hits(returnRow, ev) || (viewer?.loaded == true && hits(corner, ev))
        }
        if (controlGesture) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            rulerGesture = ruler?.let { r ->
                val location = IntArray(2)
                r.getLocationInWindow(location)
                r.visibility == View.VISIBLE && r.hitsHandle(ev.x - location[0], ev.y - location[1])
            } ?: false
            bandDown = ruler?.takeIf { !rulerGesture && it.visibility == View.VISIBLE }?.let { r ->
                val location = IntArray(2)
                r.getLocationInWindow(location)
                if (r.hitsBand(ev.y - location[1])) Triple(ev.x, ev.y, ev.eventTime) else null
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            bandDown?.let { (x, y, time) ->
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                val still = kotlin.math.abs(ev.x - x) < slop && kotlin.math.abs(ev.y - y) < slop
                if (still && ev.eventTime - time < android.view.ViewConfiguration.getLongPressTimeout()) ruler?.revealHandle()
            }
            bandDown = null
        }
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) bandDown = null
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) tapCanToggle = !rulerGesture && bandDown == null && !selectionActive
        if (tapCanToggle) controlTaps.onTouchEvent(ev)
        if (rulerGesture) return super.dispatchTouchEvent(ev)
        return viewer?.dispatchTouch(ev) ?: super.dispatchTouchEvent(ev)
    }

    // ---------- moldura ----------

    private fun barButton(text: String, description: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(Color.WHITE)
            contentDescription = description
            setOnClickListener { onClick(it) }
        }

    /**
     * Moldura: o livro ocupa a tela inteira e as barras ficam por cima dele, para que mostrar ou
     * esconder os controles (tela limpa) não mude o tamanho da página nem a posição da leitura.
     */
    private fun frame(item: LibraryItem, viewer: BookViewer): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.paper) }
        topGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.ink)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.ink)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(barButton("‹", "Voltar") { finish() })
        timerLabel = label("⏱ 0:00", 11f, Color.rgb(202, 207, 214)).apply {
            maxLines = 1
            setPadding(dp(4), dp(1), dp(8), 0)
            contentDescription = "Tempo de leitura"
        }
        bar.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(item.name, 15f, Color.WHITE, true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(8), 0)
                })
                addView(timerLabel)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        if (viewer.searchable) bar.addView(barButton("🔍", "Buscar no livro") { toggleSearch() })
        bar.addView(barButton("🔖", "Marcadores") { showBookmarks() })
        bar.addView(barButton("📝", "Anotações") { showNotes() })
        bar.addView(barButton("⋮", "Mais opções") { showMenu(it) })
        topGroup.addView(bar)

        searchBar = buildSearchBar()
        topGroup.addView(searchBar)
        // A régua fica por cima do conteúdo do livro.
        bodyFrame = FrameLayout(this)
        bodyFrame.addView(viewer.body, FrameLayout.LayoutParams(-1, -1))
        ruler = ReadingRuler(this, ReadingPrefs.rulerY(this)) { ReadingPrefs.setRulerY(this, it) }.apply {
            visibility = if (ReadingPrefs.ruler(this@ReaderActivity)) View.VISIBLE else View.GONE
        }
        bodyFrame.addView(ruler, FrameLayout.LayoutParams(-1, -1))
        corner = BookmarkCorner(this) { toggleBookmark() }
        bodyFrame.addView(corner, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END))
        root.addView(bodyFrame, FrameLayout.LayoutParams(-1, -1))
        root.addView(topGroup, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        finishBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(250, 243, 228))
            setPadding(dp(16), dp(4), dp(8), dp(4))
            visibility = View.GONE
            addView(label("Você chegou ao fim.", 13f, Palette.ink), LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@ReaderActivity).apply {
                text = "🏁 Concluir livro"
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(Palette.gold)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { markFinished(true) }
            })
        }
        bottomGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Bloquear o movimento para os lados (só formatos de página), no canto de baixo à direita.
        if (viewer.supportsSideLock) {
            val button = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                minWidth = dp(76)
                minHeight = dp(56)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { toggleSideLock() }
            }
            lockButton = button
            styleLockButton()
            bottomGroup.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END
                setMargins(0, 0, dp(12), dp(10))
            })
        }
        returnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = rounded(Palette.ink, 24)
            elevation = dp(4).toFloat()
            addView(TextView(this@ReaderActivity).apply {
                text = "↩ Voltar"
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(16), 0, dp(8), 0)
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                contentDescription = "Voltar ao ponto de antes do link"
                setOnClickListener { goBack() }
            })
            addView(TextView(this@ReaderActivity).apply {
                text = "✕"
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = dp(44)
                minHeight = dp(44)
                setTextColor(Color.rgb(202, 207, 214))
                contentDescription = "Dispensar o Voltar"
                setOnClickListener { offerReturnHide() }
            })
        }
        bottomGroup.addView(returnRow, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.START
            setMargins(dp(12), 0, 0, dp(10))
        })
        snack = buildSnack()
        bottomGroup.addView(snack, LinearLayout.LayoutParams(-1, -2))
        bottomGroup.addView(finishBanner)
        markBar = buildMarkBar()
        bottomGroup.addView(markBar, LinearLayout.LayoutParams(-1, -2))
        bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(viewer.footer)
        }
        bottomGroup.addView(bottomControls)
        root.addView(bottomGroup, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        installReaderInsets(root)
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitBodyBetweenBars() }
        topGroup.addOnLayoutChangeListener(relayout)
        bottomGroup.addOnLayoutChangeListener(relayout)
        return root
    }

    /** As barras ficam longe da barra de status e dos botões do sistema; o livro vai até a borda. */
    @Suppress("DEPRECATION")
    private fun installReaderInsets(root: View) {
        val topPadding = topGroup.paddingTop
        val bottomPadding = bottomControls.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            topGroup.setPadding(insets.systemWindowInsetLeft, topPadding + insets.systemWindowInsetTop, insets.systemWindowInsetRight, 0)
            bottomGroup.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, 0)
            bottomControls.setPadding(0, 0, 0, bottomPadding + insets.systemWindowInsetBottom)
            // Na tela limpa (sem os controles) o aviso de fim também fica acima dos botões do sistema.
            // A barra dos marcadores fica por último quando os controles estão escondidos.
            val bottomInset = if (controlsShown) 0 else insets.systemWindowInsetBottom
            (finishBanner.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.bottomMargin = if (markBar.visibility == View.VISIBLE) 0 else bottomInset
                finishBanner.layoutParams = it
            }
            (markBar.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.bottomMargin = bottomInset
                markBar.layoutParams = it
            }
            insets
        }
    }

    // ---------- tela limpa ----------

    /** Desenha por trás das barras do sistema, para poder escondê-las junto com as do leitor. */
    @Suppress("DEPRECATION")
    private fun prepareFullScreen() {
        window.statusBarColor = Palette.ink
        window.navigationBarColor = Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Barras do sistema (status e navegação) somem junto com os controles do leitor. */
    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        val hide = !controlsShown
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            val types = android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
            if (hide) {
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(types)
            } else {
                controller.show(types)
            }
        } else {
            val layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (hide) {
                layout or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else layout
        }
    }

    /**
     * Com "controles sempre visíveis", o livro fica entre as barras (nada escondido atrás delas).
     * Na tela limpa, as barras aparecem por cima do livro, sem mudar o tamanho da página.
     */
    private fun fitBodyBetweenBars() {
        val clean = ReadingPrefs.cleanScreen(this)
        val top = if (clean || topGroup.visibility != View.VISIBLE) 0 else topGroup.height
        val bottom = if (clean) 0 else bottomGroup.height - (lockButton?.let { it.height + dp(10) } ?: 0)
        val params = bodyFrame.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin == top && params.bottomMargin == bottom.coerceAtLeast(0)) return
        params.topMargin = top
        params.bottomMargin = bottom.coerceAtLeast(0)
        bodyFrame.post { bodyFrame.layoutParams = params }
    }

    /** Mostra ou esconde barra de cima, controles de baixo e o botão de bloquear. */
    private fun showControls(show: Boolean) {
        controlsShown = show
        val visibility = if (show) View.VISIBLE else View.GONE
        topGroup.visibility = visibility
        bottomControls.visibility = visibility
        lockButton?.visibility = visibility
        applySystemBars()
        topGroup.rootView?.requestApplyInsets()
    }

    /** Com a busca aberta, os controles ficam. */
    private fun toggleControls() {
        if (controlsShown && searchBar.visibility == View.VISIBLE) return
        showControls(!controlsShown)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Ao voltar de um diálogo ou de outro app, o Android pode ter mostrado as barras do sistema.
        if (hasFocus && current != null) applySystemBars()
    }

    private fun toggleSideLock() {
        val locked = !ReadingPrefs.sideLock(this)
        ReadingPrefs.setSideLock(this, locked)
        viewer?.setSideLock(locked)
        styleLockButton()
        Toast.makeText(
            this,
            if (locked) "Movimento para os lados bloqueado. Zoom e rolagem para cima e para baixo continuam." else "Movimento para os lados liberado.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun styleLockButton() {
        val button = lockButton ?: return
        val locked = ReadingPrefs.sideLock(this)
        button.text = android.text.SpannableString(if (locked) "⟷\nBloqueado" else "⟷\nBloquear").apply {
            setSpan(android.text.style.RelativeSizeSpan(2f), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        button.contentDescription = if (locked) "Desbloquear o movimento para os lados" else "Bloquear o movimento para os lados"
        button.setTextColor(if (locked) Color.WHITE else Palette.ink)
        button.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (locked) Palette.ink else Color.WHITE)
            setStroke(dp(2), Palette.ink)
        }
    }

    /** Toque simples no livro (sem ser num termo, numa seleção ou na régua) mostra/esconde os controles. */
    private val controlTaps by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val time = android.os.SystemClock.uptimeMillis()
                // O toque num termo do glossário (no EPUB a resposta vem um pouco depois) não mexe nos controles.
                timerHandler.postDelayed({
                    if (!closed && lastTermTap < time - TERM_TAP_WINDOW && !selectionActive) toggleControls()
                }, TERM_TAP_WINDOW)
                return false
            }
        })
    }
    /** O gesto começou num controle visível (barras, botão, aviso de fim): é dele, não do livro. */
    private var controlGesture = false
    /** O gesto atual pode mostrar/esconder os controles (não começou na régua nem com seleção). */
    private var tapCanToggle = false

    private fun hits(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE || view.width == 0 || !view.isShown) return false
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return ev.x >= location[0] && ev.x < location[0] + view.width && ev.y >= location[1] && ev.y < location[1] + view.height
    }

    // ---------- conforto visual ----------

    /** Camadas sobre a tela inteira: escurecimento extra, luz quente e sobreposição colorida. */
    private val dimLayer by lazy { comfortLayer() }
    private val warmLayer by lazy { comfortLayer() }
    private val tintLayer by lazy { comfortLayer() }

    private fun comfortLayer() = View(this).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun withComfortLayers(content: View): View = FrameLayout(this).apply {
        addView(content, FrameLayout.LayoutParams(-1, -1))
        addView(tintLayer, FrameLayout.LayoutParams(-1, -1))
        addView(warmLayer, FrameLayout.LayoutParams(-1, -1))
        addView(dimLayer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun applyComfort(comfort: Comfort) {
        window.attributes = window.attributes.apply { screenBrightness = Comfort.windowBrightness(comfort) }
        dimLayer.setBackgroundColor(android.graphics.Color.argb((Comfort.extraDim(comfort) * 255).toInt(), 0, 0, 0))
        warmLayer.setBackgroundColor(Comfort.warmColor(comfort))
        tintLayer.setBackgroundColor(Comfort.overlayColor(comfort))
        ruler?.applyStyle(comfort)
        viewer?.applyComfort(comfort)
    }

    private fun showComfort() {
        ComfortPanel.show(
            this,
            rulerOn = ruler?.visibility == View.VISIBLE,
            onRulerToggle = { on -> setRuler(on) }
        ) { applyComfort(it) }
    }

    override fun onPositionChanged() {
        clock.interaction(System.currentTimeMillis())
        val view = viewer ?: return
        val item = current ?: return
        val show = view.loaded && view.atEnd() && !item.finished
        finishBanner.visibility = if (show) View.VISIBLE else View.GONE
        updateCorner()
    }

    override fun tapHandled() {
        lastTermTap = android.os.SystemClock.uptimeMillis()
    }

    // ---------- "Voltar" depois de um link ----------

    override fun offerReturn(exactPosition: Long) {
        returnTo = exactPosition
        returnRow.visibility = View.VISIBLE
    }

    private fun goBack() {
        val target = returnTo ?: return
        offerReturnHide()
        viewer?.goToExact(target)
    }

    private fun offerReturnHide() {
        returnTo = null
        returnRow.visibility = View.GONE
    }

    // ---------- marcadores ----------

    private fun markOnScreen(): Int {
        val view = viewer ?: return -1
        if (!view.loaded) return -1
        return Bookmarks.indexOnScreen(marks, view.exactPosition(), view.screenSpan())
    }

    /** Fita azul no canto quando a página da tela tem marcador; na barra, segue o marcador da tela. */
    private fun updateCorner() {
        if (!::corner.isInitialized) return
        val index = markOnScreen()
        corner.marked = index >= 0
        if (markMode && index >= 0 && index != markIndex) {
            markIndex = index
            updateMarkBar()
        }
    }

    /** Toque no canto: marca a página da tela, ou desmarca se ela já tem marcador. */
    private fun toggleBookmark() {
        val item = current ?: return
        val view = viewer ?: return
        if (!view.loaded) return
        val top = view.exactPosition()
        val span = view.screenSpan()
        val here = marks.filter { Bookmarks.onScreen(it.position, top, span) }
        if (here.isNotEmpty()) {
            here.forEach { database.removeBookmark(it.id) }
            reloadMarks()
            showSnack("Marcador \"${here.first().name}\" removido", null, null)
        } else {
            val mark = database.addBookmark(item.id, top)
            reloadMarks()
            showSnack("Adicionado marcador \"${mark.name}\"", "MUDAR") { renameBookmark(mark) }
        }
    }

    private fun reloadMarks() {
        val item = current ?: return
        val selected = marks.getOrNull(markIndex)?.id
        marks = database.bookmarksFor(item.id)
        markIndex = marks.indexOfFirst { it.id == selected }
        if (markMode && marks.isEmpty()) exitMarkMode()
        updateCorner()
        updateMarkBar()
    }

    private fun buildSnack(): LinearLayout {
        snackText = label("", 14f, Color.WHITE).apply { setPadding(dp(18), dp(14), dp(8), dp(14)) }
        snackAction = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(14), 0, dp(18), 0)
            setTextColor(Color.rgb(255, 193, 7))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(50, 50, 50))
            visibility = View.GONE
            addView(snackText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(snackAction)
        }
    }

    private val hideSnack = Runnable { snack.visibility = View.GONE }

    private fun showSnack(message: String, action: String?, onAction: (() -> Unit)?) {
        snackText.text = message
        snackAction.visibility = if (action != null) View.VISIBLE else View.GONE
        snackAction.text = action ?: ""
        snackAction.setOnClickListener {
            snack.visibility = View.GONE
            onAction?.invoke()
        }
        snack.visibility = View.VISIBLE
        timerHandler.removeCallbacks(hideSnack)
        timerHandler.postDelayed(hideSnack, 4_000)
    }

    private fun buildMarkBar(): LinearLayout {
        fun button(text: String, color: Int, description: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 20f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            background = rounded(color, 3)
            contentDescription = description
            setOnClickListener { onClick() }
        }
        val teal = Color.rgb(0, 96, 122)
        val gray = Color.rgb(85, 85, 85)
        markLabel = label("", 15f, Palette.muted).apply { gravity = Gravity.CENTER }
        markPrev = button("‹", gray, "Marcador anterior") { stepMark(-1) }
        markNext = button("›", gray, "Próximo marcador") { stepMark(1) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            visibility = View.GONE
            fun gap() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
            addView(button("✕", teal, "Sair dos marcadores") { exitMarkMode() }, gap())
            addView(button("☰", teal, "Lista de marcadores") { showBookmarks() }, gap())
            addView(markLabel, LinearLayout.LayoutParams(0, -2, 1.4f))
            addView(markPrev, gap())
            addView(markNext, gap())
        }
    }

    private fun updateMarkBar() {
        if (!::markBar.isInitialized || !markMode) return
        markLabel.text = if (markIndex >= 0) "${markIndex + 1} de ${marks.size}" else "${marks.size} marcador(es)"
        val view = viewer
        val top = view?.exactPosition() ?: 0
        setLook(markPrev, previousMark(top) >= 0)
        setLook(markNext, nextMark(top) >= 0)
    }

    private fun setLook(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    /** Marcador anterior ao da tela (ou ao ponto de leitura, se a tela não tem marcador). */
    private fun previousMark(top: Long): Int =
        if (markIndex >= 0) markIndex - 1 else marks.indexOfLast { it.position < top }

    private fun nextMark(top: Long): Int =
        if (markIndex >= 0) (markIndex + 1).takeIf { it < marks.size } ?: -1 else marks.indexOfFirst { it.position > top }

    private fun stepMark(direction: Int) {
        val top = viewer?.exactPosition() ?: return
        val target = if (direction < 0) previousMark(top) else nextMark(top)
        if (target in marks.indices) openMark(target)
    }

    private fun openMark(index: Int) {
        val view = viewer ?: return
        markIndex = index
        markMode = true
        snack.visibility = View.GONE
        markBar.visibility = View.VISIBLE
        markBar.rootView?.requestApplyInsets()
        view.goToExact(marks[index].position)
        updateMarkBar()
    }

    private fun exitMarkMode() {
        markMode = false
        markIndex = -1
        markBar.visibility = View.GONE
        markBar.rootView?.requestApplyInsets()
    }

    /** Lista dos marcadores do livro: tocar leva à página; ⋮ renomeia ou exclui. */
    private fun showBookmarks() {
        val view = viewer ?: return
        val list = marks
        val builder = AlertDialog.Builder(this)
            .setTitle(if (list.isEmpty()) "Marcadores" else "Marcadores (${list.size})")
            .setNegativeButton("Fechar", null)
        if (list.isEmpty()) {
            builder.setMessage("Nenhum marcador ainda. Toque no canto de cima, à direita da página, para marcá-la. Toque de novo no canto para desmarcar.")
            builder.show()
            return
        }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val dialog = builder.setView(android.widget.ScrollView(this).apply { addView(rows) }).create()
        list.forEachIndexed { index, mark ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), 0, 0, 0)
                background = rounded(Color.rgb(236, 233, 226), 8)
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    openMark(index)
                }
            }
            row.addView(label(mark.name, 16f, Palette.ink).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(view.describeExact(mark.position), 14f, Palette.muted).apply {
                setPadding(dp(8), 0, dp(4), 0)
            })
            row.addView(TextView(this).apply {
                text = "⋮"
                textSize = 20f
                gravity = Gravity.CENTER
                minWidth = dp(44)
                minHeight = dp(52)
                setTextColor(Palette.muted)
                contentDescription = "Opções de ${mark.name}"
                setOnClickListener { anchor ->
                    val popup = PopupMenu(this@ReaderActivity, anchor)
                    popup.menu.add(Menu.NONE, 0, 0, "Editar nome")
                    popup.menu.add(Menu.NONE, 1, 1, "Excluir")
                    popup.setOnMenuItemClickListener { choice ->
                        dialog.dismiss()
                        if (choice.itemId == 0) renameBookmark(mark, reopenList = true) else {
                            database.removeBookmark(mark.id)
                            reloadMarks()
                            Toast.makeText(this@ReaderActivity, "\"${mark.name}\" excluído.", Toast.LENGTH_SHORT).show()
                            showBookmarks()
                        }
                        true
                    }
                    popup.show()
                }
            })
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
        dialog.show()
    }

    private fun renameBookmark(mark: Bookmark, reopenList: Boolean = false) {
        val input = EditText(this).apply {
            setText(mark.name)
            selectAll()
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Nome do marcador")
            .setView(container)
            .setNegativeButton("Cancelar") { _, _ -> if (reopenList) showBookmarks() }
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) database.renameBookmark(mark.id, name)
                reloadMarks()
                if (reopenList) showBookmarks()
            }
            .show()
    }

    private fun showMenu(anchor: View) {
        val view = viewer ?: return
        val item = current ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += view.menuActions()
        val paged = ReadingPrefs.paged(this)
        actions += (if (paged) "Modo: rolagem contínua" else "Modo: páginas (toque duplo na borda)") to { switchMode(!paged) }
        val rulerOn = ruler?.visibility == View.VISIBLE
        actions += (if (rulerOn) "Esconder régua de leitura" else "Mostrar régua de leitura") to { toggleRuler() }
        val clean = ReadingPrefs.cleanScreen(this)
        actions += (if (clean) "Controles sempre visíveis" else "Tela limpa (toque para mostrar os controles)") to {
            ReadingPrefs.setCleanScreen(this, !clean)
            showControls(clean)
            fitBodyBetweenBars()
        }
        val focus = ComfortStore.load(this).rulerOutside == Comfort.RulerOutside.FOCO
        actions += (if (focus && rulerOn) "Desligar modo foco" else "Modo foco (só o texto da régua)") to { toggleFocus(!(focus && rulerOn)) }
        actions.add(0, "Conforto visual…" to { showComfort() })
        actions.add(1, "Glossário do livro" to { showGlossary() })
        actions += if (item.finished) {
            "Voltar para \"Lendo agora\"" to { markFinished(false) }
        } else {
            "Concluir livro" to { markFinished(true) }
        }
        val popup = PopupMenu(this, anchor)
        actions.forEachIndexed { index, (title, _) -> popup.menu.add(Menu.NONE, index, index, title) }
        popup.setOnMenuItemClickListener { menuItem ->
            actions.getOrNull(menuItem.itemId)?.second?.invoke()
            true
        }
        popup.show()
    }

    /** Troca o modo de leitura: salva o ponto e reabre a tela já no modo novo. */
    private fun switchMode(paged: Boolean) {
        ReadingPrefs.setPaged(this, paged)
        Toast.makeText(
            this,
            if (paged) "Modo páginas: toque duas vezes na borda direita para avançar e na esquerda para voltar."
            else "Modo rolagem contínua.",
            Toast.LENGTH_LONG
        ).show()
        recreate()
    }

    private fun toggleRuler() {
        val on = ruler?.visibility != View.VISIBLE
        setRuler(on)
        if (on) Toast.makeText(this, "Toque na régua para mostrar a alça e arraste-a para mover. Espessura, cor e modo foco: ⋮ > Conforto visual.", Toast.LENGTH_LONG).show()
    }

    /** Modo foco: liga a régua e deixa preto tudo fora dela (ou volta a só escurecer). */
    private fun toggleFocus(on: Boolean) {
        val comfort = ComfortStore.load(this)
        val updated = comfort.copy(rulerOutside = if (on) Comfort.RulerOutside.FOCO else Comfort.RulerOutside.ESCURECER)
        ComfortStore.save(this, updated)
        if (on) setRuler(true)
        applyComfort(updated)
        if (on) Toast.makeText(this, "Modo foco: só o texto sob a régua fica visível. Toque na régua para mostrar a alça e movê-la.", Toast.LENGTH_LONG).show()
    }

    private fun setRuler(on: Boolean) {
        ruler?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) ruler?.revealHandle()
        ReadingPrefs.setRuler(this, on)
    }

    /** Concluir move o livro para a Estante, como o botão "Finalizar" do leitor web. */
    private fun markFinished(finished: Boolean) {
        val item = current ?: return
        saveProgress()
        database.setFinished(item.id, finished)
        current = database.find(item.id)
        onPositionChanged()
        if (finished) {
            Toast.makeText(this, "\"${item.name}\" concluído e movido para a Estante.", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "De volta para \"Lendo agora\".", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- busca ----------

    private fun buildSearchBar(): LinearLayout {
        searchInput = EditText(this).apply {
            hint = "Buscar no livro"
            textSize = 14f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    runSearch()
                    true
                } else {
                    false
                }
            }
        }
        searchInfo = label("", 12f, Palette.muted).apply {
            gravity = Gravity.CENTER
            minWidth = dp(56)
            setOnClickListener { showHitList() }
        }
        fun small(text: String, description: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(48)
            setTextColor(Palette.ink)
            contentDescription = description
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), 0, dp(4), 0)
            visibility = View.GONE
            addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(searchInfo)
            addView(small("◀", "Resultado anterior") { stepHit(-1) })
            addView(small("▶", "Próximo resultado") { stepHit(1) })
            addView(small("✕", "Fechar busca") { toggleSearch() })
        }
    }

    private fun toggleSearch() {
        val input = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (searchBar.visibility == View.VISIBLE) {
            searchBar.visibility = View.GONE
            input.hideSoftInputFromWindow(searchInput.windowToken, 0)
            viewer?.clearSearch()
            hits = emptyList()
            hitIndex = -1
            searchInfo.text = ""
        } else {
            if (!controlsShown) showControls(true)
            searchBar.visibility = View.VISIBLE
            searchInput.requestFocus()
            input.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun runSearch() {
        val view = viewer ?: return
        val term = searchInput.text.toString().trim()
        if (term.isEmpty() || !view.loaded) return
        searchTerm = term
        searchInfo.text = "…"
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
        view.search(term) { found ->
            hits = found
            if (found.isEmpty()) {
                hitIndex = -1
                searchInfo.text = "0"
                view.clearSearch()
                Toast.makeText(this, "Nada encontrado para \"$term\".", Toast.LENGTH_SHORT).show()
            } else {
                showHit(0)
            }
        }
    }

    private fun stepHit(delta: Int) {
        if (hits.isEmpty()) return
        showHit((hitIndex + delta + hits.size) % hits.size)
    }

    private fun showHit(index: Int) {
        hitIndex = index
        searchInfo.text = "${index + 1}/${hits.size}"
        viewer?.showHit(hits[index], searchTerm)
    }

    private fun showHitList() {
        if (hits.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("${hits.size} resultado(s)")
            .setItems(hits.map { it.label }.toTypedArray()) { _, which -> showHit(which) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ---------- anotações ----------

    /** Ao selecionar texto do livro, o menu da seleção ganha a opção "Anotar". */
    override fun onActionModeFinished(mode: ActionMode) {
        super.onActionModeFinished(mode)
        // Um instante depois: o toque que fecha a seleção não deve também mostrar os controles.
        timerHandler.postDelayed({ selectionActive = false }, TERM_TAP_WINDOW * 3)
    }

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        selectionActive = true
        val view = viewer ?: return
        if (!view.selectsText || currentFocus == searchInput) return
        mode.menu.add(Menu.NONE, MENU_TERM, 0, "Definir termo").setOnMenuItemClickListener {
            val found = view.selection { excerpt, _ ->
                mode.finish()
                if (excerpt.isNotBlank()) editTerm(null, excerpt.trim().take(80))
                else Toast.makeText(this, "Selecione a palavra que quer definir.", Toast.LENGTH_SHORT).show()
            }
            if (!found) {
                mode.finish()
                Toast.makeText(this, "Selecione a palavra que quer definir.", Toast.LENGTH_SHORT).show()
            }
            true
        }
        mode.menu.add(Menu.NONE, MENU_ANNOTATE, 0, "Anotar").setOnMenuItemClickListener {
            // A seleção do EPUB é lida de forma assíncrona: o menu só fecha depois da leitura.
            val found = view.selection { excerpt, position ->
                mode.finish()
                if (excerpt.isNotEmpty()) editNote(null, excerpt, position)
                else Toast.makeText(this, "Selecione um trecho do livro para anotar.", Toast.LENGTH_SHORT).show()
            }
            if (!found) {
                mode.finish()
                Toast.makeText(this, "Selecione um trecho do livro para anotar.", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    // ---------- glossário ----------

    private var terms: List<GlossaryTerm> = emptyList()

    private fun reloadGlossary() {
        val item = current ?: return
        terms = database.termsFor(item.id)
        viewer?.setGlossary(terms) {
            lastTermTap = android.os.SystemClock.uptimeMillis()
            showTerm(it)
        }
    }

    /** Cartão da definição, aberto ao tocar numa palavra sublinhada. */
    private fun showTerm(term: GlossaryTerm) {
        AlertDialog.Builder(this)
            .setTitle(term.term)
            .setMessage(term.definition.ifBlank { "(sem definição)" })
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Editar") { _, _ -> editTerm(term, term.term) }
            .setNegativeButton("Remover") { _, _ ->
                database.removeTerm(term.id)
                reloadGlossary()
                Toast.makeText(this, "\"${term.term}\" removido do glossário.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Cria ([existing] nulo) ou edita um termo. Se o termo já existe no livro, abre a definição dele. */
    private fun editTerm(existing: GlossaryTerm?, initial: String) {
        val item = current ?: return
        val known = existing ?: terms.firstOrNull { TextSearch.fold(it.term) == TextSearch.fold(initial) }
        val termInput = EditText(this).apply {
            hint = "Palavra ou expressão"
            maxLines = 1
            setText(known?.term ?: initial)
        }
        val definitionInput = EditText(this).apply {
            hint = "O que significa (ex.: planeta do deserto)"
            minLines = 3
            gravity = Gravity.TOP
            setText(known?.definition ?: "")
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(termInput, LinearLayout.LayoutParams(-1, -2))
            addView(definitionInput, LinearLayout.LayoutParams(-1, -2))
            addView(label("As próximas vezes em que aparecer no livro, a palavra fica sublinhada: toque nela para ver a definição.", 11f, Palette.muted).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(if (known != null) "Editar termo" else "Definir termo")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val term = termInput.text.toString().trim()
                if (term.isEmpty()) {
                    Toast.makeText(this, "Escreva a palavra.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Renomeou um termo existente: a versão antiga sai.
                if (known != null && TextSearch.fold(known.term) != TextSearch.fold(term)) database.removeTerm(known.id)
                database.saveTerm(item.id, term, definitionInput.text.toString())
                reloadGlossary()
                Toast.makeText(this, "\"$term\" salvo no glossário.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showGlossary() {
        val list = terms
        val builder = AlertDialog.Builder(this)
            .setTitle(if (list.isEmpty()) "Glossário do livro" else "Glossário do livro (${list.size})")
            .setNegativeButton("Fechar", null)
            .setPositiveButton("Novo termo") { _, _ -> editTerm(null, "") }
        if (list.isEmpty()) {
            builder.setMessage(
                if (viewer?.annotatesPages == true) "Nenhum termo ainda. Neste formato o texto não é selecionável: use \"Novo termo\" e digite a palavra."
                else "Nenhum termo ainda. Selecione uma palavra no texto e toque em \"Definir termo\"."
            )
        } else {
            builder.setItems(list.map { "${it.term} — ${it.definition.take(80)}" }.toTypedArray()) { _, which -> showTerm(list[which]) }
        }
        builder.show()
    }

    private fun showNotes() {
        val item = current ?: return
        val view = viewer ?: return
        val notes = database.notesFor(item.id)
        val labels = notes.map { note ->
            buildString {
                append(view.describe(note.position))
                if (note.excerpt.isNotEmpty()) append(" — “").append(note.excerpt.take(120)).append(if (note.excerpt.length > 120) "…”" else "”")
                if (note.text.isNotEmpty()) append("\n").append(note.text)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (notes.isEmpty()) "Anotações" else "Anotações (${notes.size})")
            .setNegativeButton("Fechar", null)
        if (notes.isEmpty()) {
            builder.setMessage(
                if (view.annotatesPages) "Nenhuma anotação ainda. Use \"Anotar esta página\"."
                else "Nenhuma anotação ainda. Selecione um trecho do texto e toque em \"Anotar\"."
            )
        } else {
            builder.setItems(labels.toTypedArray()) { _, which -> view.goTo(notes[which].position) }
        }
        if (view.annotatesPages) {
            builder.setPositiveButton("Anotar esta página") { _, _ -> if (view.loaded) editNote(null, "", view.position()) }
        }
        val dialog = builder.show()
        dialog.listView?.setOnItemLongClickListener { _, _, which, _ ->
            dialog.dismiss()
            noteOptions(notes[which])
            true
        }
    }

    private fun noteOptions(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(viewer?.describe(note.position) ?: "Anotação")
            .setItems(arrayOf("Ir para o trecho", "Editar nota", "Remover")) { _, which ->
                when (which) {
                    0 -> viewer?.goTo(note.position)
                    1 -> editNote(note, note.excerpt, note.position)
                    2 -> {
                        database.removeNote(note.id)
                        Toast.makeText(this, "Anotação removida.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Cria ([existing] nulo) ou edita uma anotação. */
    private fun editNote(existing: Note?, excerpt: String, position: Int) {
        val item = current ?: return
        val input = EditText(this).apply {
            hint = "Sua nota sobre esse trecho (opcional)"
            minLines = 3
            gravity = Gravity.TOP
            setText(existing?.text ?: "")
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            if (excerpt.isNotEmpty()) {
                addView(label("“${excerpt.take(400)}${if (excerpt.length > 400) "…" else ""}”", 13f, Palette.muted).apply {
                    setTypeface(typeface, android.graphics.Typeface.ITALIC)
                    setPadding(0, 0, 0, dp(8))
                })
            }
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val title = if (existing != null) "Editar anotação" else "Salvar anotação · ${viewer?.describe(position) ?: ""}"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val text = input.text.toString()
                if (existing != null) {
                    database.updateNote(existing.id, text)
                } else {
                    if (excerpt.isEmpty() && text.isBlank()) {
                        Toast.makeText(this, "Escreva uma nota para esta página.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    database.addNote(item.id, excerpt, text, position)
                }
                Toast.makeText(this, "Anotação salva.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_POSITION = "position"
        private const val MENU_ANNOTATE = 0x414e
        private const val MENU_TERM = 0x5445
        /** Espera curta depois do toque, para saber se ele caiu num termo do glossário. */
        private const val TERM_TAP_WINDOW = 180L
    }
}
