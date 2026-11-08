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
}
