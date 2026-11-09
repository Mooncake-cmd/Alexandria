package com.alexandria.reader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Recebe livros de outros apps ("Abrir com" e "Compartilhar"): guarda na biblioteca e abre o leitor.
 * O mesmo livro aberto de novo volta ao ponto onde parou.
 */
class OpenActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.paper)
            addView(ProgressBar(this@OpenActivity))
            addView(label("Abrindo o livro…", 15f, Palette.ink, true).apply { setPadding(0, dp(12), 0, 0) })
        })
        val uri = IncomingBooks.uriOf(intent)
        if (uri == null) {
            Toast.makeText(this, "Nenhum arquivo recebido.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val mime = intent.type
        worker.execute {
            val outcome: Any = try {
                LibraryDatabase(applicationContext).use { db -> IncomingBooks.receive(applicationContext, db, uri, mime) }
            } catch (e: IncomingBooks.UnsupportedFormat) {
                e
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (closed) return@runOnUiThread
                when (outcome) {
                    is IncomingBooks.Result -> {
                        if (!outcome.reused) {
                            Toast.makeText(this, "\"${outcome.item.name}\" adicionado à biblioteca.", Toast.LENGTH_SHORT).show()
                        }
                        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ITEM_ID, outcome.item.id))
                    }
                    is IncomingBooks.UnsupportedFormat ->
                        Toast.makeText(this, "O Alexandria não lê \"${outcome.name}\".", Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(this, "Não foi possível abrir este arquivo.", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    override fun onDestroy() {
        closed = true
        worker.shutdown()
        super.onDestroy()
    }
}
