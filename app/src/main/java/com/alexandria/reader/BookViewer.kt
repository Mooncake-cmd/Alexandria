package com.alexandria.reader

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ExecutorService

/** O que a tela de leitura oferece a cada visualizador de formato. */
interface ReaderHost {
    val activity: Activity
    val worker: ExecutorService
    /** true depois que a tela foi destruída: resultados atrasados da thread de fundo são descartados. */
    val closed: Boolean
    fun fail(message: String)
    /** Avisa que a posição mudou (atualiza o aviso de "chegou ao fim"). */
    fun onPositionChanged()
}

/** Resultado de busca: onde fica e um trecho para mostrar na lista. */
class SearchHit(val position: Int, val label: String, internal val chunk: Int, internal val offset: Int, internal val length: Int)

/**
 * Um visualizador de formato. Formatos paginados (PDF, CBZ) contam [position] em páginas; os de
 * texto corrido (TXT, DOCX, EPUB) usam uma fração de 0 a 10000 do livro inteiro.
 */
abstract class BookViewer(protected val host: ReaderHost) {
    protected val context: Activity get() = host.activity

    abstract val body: View
    abstract val footer: View

    /** true quando o documento terminou de abrir. */
    abstract val loaded: Boolean

    /** Abre o documento, em [start] (posição salva ou de uma anotação). */
    abstract fun open(item: LibraryItem, start: Int)

    abstract fun position(): Int

    /** Total de páginas ou capítulos, se o formato tiver (0 caso contrário). */
    open fun pageCount(): Int = 0

    abstract fun goTo(position: Int)

    abstract fun atEnd(): Boolean

    /** Rótulo curto de uma posição, para as anotações ("Pág. 12", "42%"). */
    abstract fun describe(position: Int): String

    /** Formatos paginados anotam a página inteira; os de texto anotam um trecho selecionado. */
    open val annotatesPages: Boolean = false

    /** Trecho selecionado agora e a posição dele, ou null se não há seleção no texto do livro. */
    open fun selection(done: (excerpt: String, position: Int) -> Unit): Boolean = false

    open val searchable: Boolean = false
    open fun search(term: String, done: (List<SearchHit>) -> Unit) = done(emptyList())
    open fun showHit(hit: SearchHit, term: String) = Unit
    open fun clearSearch() = Unit

    /** Ações extras do menu ⋮ (tamanho de letra, tema, sumário…). */
    open fun menuActions(): List<Pair<String, () -> Unit>> = emptyList()

    /** Aplica cores, tipografia etc. do painel "Conforto visual" (chamado também enquanto se ajusta). */
    open fun applyComfort(comfort: Comfort) = Unit

    /** Gestos próprios (zoom, deslizar). Retorna true se consumiu o evento, null para seguir o normal. */
    open fun dispatchTouch(event: MotionEvent): Boolean? = null

    open fun close() = Unit

    // ---------- peças de interface compartilhadas ----------

    protected fun barButton(text: String, color: Int, description: String? = null, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = context.dp(48)
            minHeight = context.dp(48)
            setPadding(context.dp(10), 0, context.dp(10), 0)
            setTextColor(color)
            if (description != null) contentDescription = description
            setOnClickListener { onClick() }
        }

    protected fun footerBar(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
    }

    protected fun setEnabledLook(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    /** Pequeno esmaecimento ao virar a página, para o olho perceber a troca. */
    protected fun flash(view: View) {
        view.animate().cancel()
        view.alpha = 0.55f
        view.animate().alpha(1f).setDuration(160).start()
    }

    /** Executa na thread principal, a menos que a tela já tenha fechado. */
    protected fun onUi(block: () -> Unit) = context.runOnUiThread { if (!host.closed) block() }
}

/** Preferências de leitura compartilhadas pelos formatos de texto (TXT, DOCX, EPUB). */
object ReadingPrefs {
    private const val PREFS = "reader"
    private const val KEY_TEXT_SIZE = "text_size_sp"
    private const val KEY_NIGHT = "night_mode"
    const val DEFAULT_TEXT_SP = 18f
    val NIGHT_BACKGROUND = Color.rgb(18, 22, 28)
    val NIGHT_TEXT = Color.rgb(214, 208, 196)

    fun textSize(context: Activity) = context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SP)
    fun setTextSize(context: Activity, value: Float) =
        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putFloat(KEY_TEXT_SIZE, value).apply()

    fun night(context: Activity) = context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getBoolean(KEY_NIGHT, false)
    fun setNight(context: Activity, value: Boolean) =
        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putBoolean(KEY_NIGHT, value).apply()

    private const val KEY_PAGED = "paged_mode"
    private const val KEY_RULER = "ruler_on"
    private const val KEY_RULER_Y = "ruler_y"

    /**
     * Modo de leitura: true = páginas (uma tela por vez, vira com toque duplo na borda);
     * false = rolagem contínua.
     */
    fun paged(context: Activity) = context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getBoolean(KEY_PAGED, true)
    fun setPaged(context: Activity, value: Boolean) =
        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putBoolean(KEY_PAGED, value).apply()

    /** Régua de leitura ligada e sua posição (fração da altura da área de leitura). */
    fun ruler(context: Activity) = context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getBoolean(KEY_RULER, false)
    fun setRuler(context: Activity, value: Boolean) =
        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putBoolean(KEY_RULER, value).apply()
    fun rulerY(context: Activity) = context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getFloat(KEY_RULER_Y, 0.4f)
    fun setRulerY(context: Activity, value: Float) =
        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putFloat(KEY_RULER_Y, value).apply()
}

/**
 * Toque duplo nas bordas da área de leitura: borda direita avança, esquerda volta (como virar a
 * página). Com [blockScroll], arrastar o dedo não rola o conteúdo (modo páginas dos textos).
 */
class EdgeTaps(
    context: Activity,
    private val area: View,
    private val blockScroll: () -> Boolean,
    private val onTurn: (direction: Int) -> Unit
) {
    private var inside = false
    private var swallowing = false

    private val detector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val direction = PageTurn.direction(local(e).first, area.width.toFloat())
            if (direction == 0) return false
            onTurn(direction)
            // O segundo toque não chega ao texto: assim ele não seleciona uma palavra.
            swallowing = true
            return true
        }
    })

    private fun local(event: MotionEvent): Pair<Float, Float> {
        val location = IntArray(2)
        area.getLocationInWindow(location)
        return Pair(event.x - location[0], event.y - location[1])
    }

    /** Recebe os toques da janela. true = consumido; null = segue o caminho normal. */
    fun dispatch(event: MotionEvent): Boolean? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val (x, y) = local(event)
            inside = x >= 0 && y >= 0 && x < area.width && y < area.height
            swallowing = false
        }
        if (!inside) return null
        detector.onTouchEvent(event)
        if (swallowing) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && blockScroll()) return true
        return null
    }
}

/** Regras do toque nas bordas. Kotlin puro, coberto por testes. */
object PageTurn {
    /** Fração da largura, em cada lado, que conta como borda. */
    const val EDGE = 0.3f

    /** -1 (borda esquerda: voltar), 1 (borda direita: avançar) ou 0 (meio). */
    fun direction(x: Float, width: Float): Int = when {
        width <= 0f -> 0
        x < width * EDGE -> -1
        x > width * (1 - EDGE) -> 1
        else -> 0
    }

    /** Quanto rolar para "virar a página" num texto: a altura visível menos uma sobra de contexto. */
    fun scrollStep(visibleHeight: Int, overlap: Int): Int = (visibleHeight - overlap).coerceAtLeast(visibleHeight / 2).coerceAtLeast(1)

    /** Tamanho da página para caber inteira na área (largura e altura), dada a proporção altura/largura. */
    fun fitPage(areaWidth: Int, areaHeight: Int, aspect: Float): Pair<Int, Int> {
        if (areaWidth <= 0 || areaHeight <= 0 || aspect <= 0f) return Pair(areaWidth.coerceAtLeast(1), areaHeight.coerceAtLeast(1))
        val byWidth = areaWidth.toFloat()
        val byHeight = areaHeight / aspect
        val width = minOf(byWidth, byHeight)
        return Pair(width.toInt().coerceAtLeast(1), (width * aspect).toInt().coerceAtLeast(1))
    }
}
