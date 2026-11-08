package com.alexandria.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

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
    private lateinit var pageScroll: ScrollView
    private lateinit var pageImage: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView

    // TXT
    private lateinit var textScroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var progressLabel: TextView
    private var textSizeSp = DEFAULT_TEXT_SP
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
            "pdf" -> if (pageCount > 0) database.updatePosition(item.id, pageIndex)
            "txt" -> if (textLoaded) database.updatePosition(item.id, scrollFraction())
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
        bar.addView(barButton("‹", Color.WHITE) { finish() })
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
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        pageScroll = ScrollView(this).apply {
            setBackgroundColor(Palette.paper)
            addView(pageImage, FrameLayout.LayoutParams(-1, -2))
        }
        prevButton = barButton("‹ Anterior", Palette.ink) { showPage(pageIndex - 1) }
        nextButton = barButton("Próxima ›", Palette.ink) { showPage(pageIndex + 1) }
        pageLabel = label("Abrindo…", 13f, Palette.muted).apply { gravity = Gravity.CENTER }
        prevButton.isEnabled = false
        nextButton.isEnabled = false

        val footer = footerBar()
        footer.addView(prevButton)
        footer.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(nextButton)
        setContentView(frame(item.name, pageScroll, footer))

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
                        showPage(item.position)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!destroyed) {
                        fail("Não foi possível abrir este PDF. Ele pode estar protegido por senha, danificado ou indisponível.")
                    }
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (pageCount == 0) return
        pageIndex = index.coerceIn(0, pageCount - 1)
        updatePageControls()
        val target = pageIndex
        val token = ++renderToken
        val width = if (pageScroll.width > 0) pageScroll.width else resources.displayMetrics.widthPixels
        worker.execute {
            val bitmap = try {
                renderPage(target, width)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (destroyed || token != renderToken) return@runOnUiThread
                if (bitmap == null) {
                    Toast.makeText(this, "Não foi possível exibir esta página.", Toast.LENGTH_SHORT).show()
                } else {
                    pageImage.setImageBitmap(bitmap)
                    pageScroll.scrollTo(0, 0)
                }
            }
        }
    }

    private fun updatePageControls() {
        pageLabel.text = "Página ${pageIndex + 1} de $pageCount"
        prevButton.isEnabled = pageIndex > 0
        nextButton.isEnabled = pageIndex < pageCount - 1
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.35f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.35f
    }

    /** Roda na thread worker. */
    private fun renderPage(index: Int, targetWidth: Int): Bitmap? {
        val pdf = renderer ?: return null
        val page = pdf.openPage(index)
        try {
            val scale = targetWidth.toFloat() / page.width
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
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

        textView = TextView(this).apply {
            text = "Carregando…"
            textSize = textSizeSp
            typeface = Typeface.SERIF
            setTextColor(Palette.ink)
            setLineSpacing(0f, 1.35f)
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setTextIsSelectable(true)
        }
        textScroll = ScrollView(this).apply {
            setBackgroundColor(Palette.paper)
            addView(textView)
            setOnScrollChangeListener { _, _, _, _, _ -> if (textLoaded) updateProgressLabel() }
        }
        progressLabel = label("", 12f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        val footer = footerBar()
        footer.addView(progressLabel, LinearLayout.LayoutParams(0, -2, 1f))

        val smaller = barButton("A-", Color.WHITE) { changeTextSize(-1f) }
        val larger = barButton("A+", Color.WHITE) { changeTextSize(1f) }
        setContentView(frame(item.name, textScroll, footer, listOf(smaller, larger)))

        val uri = Uri.parse(item.uri)
        worker.execute {
            val result = try {
                readText(uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (result == null) {
                    fail("Não foi possível abrir este arquivo de texto.")
                    return@runOnUiThread
                }
                textView.text = result.text
                textLoaded = true
                if (result.truncated) {
                    Toast.makeText(this, "Arquivo muito grande: exibindo só o começo.", Toast.LENGTH_LONG).show()
                }
                textScroll.post {
                    restoreFraction(item.position)
                    updateProgressLabel()
                }
            }
        }
    }

    private fun changeTextSize(delta: Float) {
        val fraction = scrollFraction()
        textSizeSp = (textSizeSp + delta).coerceIn(12f, 32f)
        textView.textSize = textSizeSp
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_TEXT_SIZE, textSizeSp).apply()
        textScroll.post { restoreFraction(fraction) }
    }

    private fun scrollRange(): Int = (textView.height - textScroll.height).coerceAtLeast(1)

    /** Posição de rolagem como fração de 0 a 10000, independente de tamanho de fonte e rotação. */
    private fun scrollFraction(): Int =
        (textScroll.scrollY.toLong() * 10000L / scrollRange()).toInt().coerceIn(0, 10000)

    private fun restoreFraction(fraction: Int) {
        textScroll.scrollTo(0, (fraction.toLong() * scrollRange() / 10000L).toInt())
    }

    private fun updateProgressLabel() {
        progressLabel.text = "${scrollFraction() / 100}% lido"
    }

    private class TextResult(val text: String, val truncated: Boolean)

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
        val text = decode(buffer.toByteArray(), truncated)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        return TextResult(text, truncated)
    }

    /**
     * Tenta UTF-8 estrito; se falhar, assume Windows-1252, comum em .txt antigos em português.
     * Quando o arquivo foi cortado, pode ter sobrado meio caractere no fim: tenta aparar até 3 bytes.
     */
    private fun decode(bytes: ByteArray, truncated: Boolean): String {
        val maxCut = if (truncated) 3 else 0
        for (cut in 0..maxCut) {
            val length = bytes.size - cut
            if (length < 0) break
            try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString().removePrefix("﻿")
            } catch (e: CharacterCodingException) {
                // Tenta de novo cortando mais um byte, ou cai no fallback.
            }
        }
        return String(bytes, Charset.forName("windows-1252"))
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        private const val PREFS = "reader"
        private const val KEY_TEXT_SIZE = "text_size_sp"
        private const val DEFAULT_TEXT_SP = 18f
        private const val MAX_TEXT_BYTES = 3 * 1024 * 1024
    }
}
