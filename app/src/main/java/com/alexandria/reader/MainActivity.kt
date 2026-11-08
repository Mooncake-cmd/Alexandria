package com.alexandria.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var database: LibraryDatabase
    private lateinit var content: LinearLayout
    private val ink = Palette.ink
    private val gold = Palette.gold
    private val paper = Palette.paper
    private val muted = Palette.muted

    private var section = SECTION_LIBRARY
    private var refreshOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = LibraryDatabase(this)
        section = savedInstanceState?.getString(KEY_SECTION) ?: SECTION_LIBRARY
        showSection(section)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION, section)
    }

    override fun onResume() {
        super.onResume()
        // Ao voltar do leitor, atualiza o progresso exibido nos cartões.
        if (refreshOnResume) {
            refreshOnResume = false
            if (section == SECTION_LIBRARY) showLibrary()
        }
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    private fun showSection(name: String) {
        if (name == SECTION_LIBRARY) showLibrary() else showPlaceholder(name)
    }

    private fun baseScreen(selected: String): LinearLayout {
        section = selected
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(20))
            setBackgroundColor(ink)
        }
        header.addView(label("ALEXANDRIA", 12f, gold, true))
        header.addView(label("Sua biblioteca pessoal", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(3))
        })
        header.addView(label("Um espaço para suas leituras.", 13f, Color.rgb(202, 207, 214)))
        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(6), dp(5), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        NAV_ITEMS.forEach { name ->
            val active = name == selected
            val entry = TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setTextColor(if (active) gold else muted)
                if (active) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { showSection(name) }
            }
            nav.addView(entry, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        installSystemBarInsets(root, header, nav)
        return root
    }

    private fun sectionTitle(title: String, subtitle: String? = null) {
        content.addView(label(title, 21f, ink, true))
        if (subtitle != null) {
            content.addView(label(subtitle, 13f, muted).apply {
                setPadding(0, dp(4), 0, dp(14))
            })
        } else content.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
    }

    private fun showLibrary() {
        setContentView(baseScreen(SECTION_LIBRARY))
        sectionTitle("Minha biblioteca", "Seus documentos ficam organizados neste dispositivo.")

        val importButton = Button(this).apply {
            text = "＋  Importar documentos"
            setTextColor(Color.WHITE)
            background = rounded(ink, 12)
            setAllCaps(false)
            setOnClickListener { chooseDocument() }
        }
        content.addView(importButton, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(label("LEITURA DISPONÍVEL", 10f, muted, true).apply {
            setPadding(0, dp(22), 0, dp(4))
        })
        content.addView(label("PDF  ·  TXT", 12f, ink))
        content.addView(label("EM BREVE", 10f, muted, true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        content.addView(label("EPUB  ·  MOBI  ·  DJVU  ·  DOCX  ·  CBZ  ·  CBR", 12f, ink))
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(22)))

        val items = database.all()
        sectionTitle("Adicionados", "${items.size} documento(s) na biblioteca")
        if (items.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(28), dp(18), dp(28))
                background = rounded(Color.WHITE, 18)
            }
            empty.addView(label("▤", 34f, gold, true))
            empty.addView(label("Sua biblioteca começa aqui", 16f, ink, true).apply {
                setPadding(0, dp(10), 0, dp(5))
            })
            empty.addView(label("Importe um PDF ou TXT para começar a ler. Os outros formatos ficam guardados até o leitor deles ficar pronto.", 12f, muted).apply {
                gravity = Gravity.CENTER
            })
            content.addView(empty, LinearLayout.LayoutParams(-1, -2))
        } else {
            items.forEach { entry ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(13), dp(15), dp(13))
                    background = rounded(Color.WHITE, 14)
                    setOnClickListener { openItem(entry) }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remover da biblioteca?")
                            .setMessage(entry.name)
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Remover") { _, _ -> removeItem(entry) }
                            .show()
                        true
                    }
                }
                card.addView(label(entry.name, 15f, ink, true))
                card.addView(label(entry.format.uppercase(), 10f, gold, true).apply {
                    setPadding(0, dp(5), 0, 0)
                })
                card.addView(label(ReadingStatus.describe(entry), 11f, muted).apply {
                    setPadding(0, dp(3), 0, 0)
                })
                content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(9)
                })
            }
            content.addView(label("Toque em um item para abri-lo. Mantenha pressionado para removê-lo da biblioteca.", 11f, muted))
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            // Alguns aparelhos (TVs, sistemas modificados) não têm o seletor de documentos.
            Toast.makeText(this, "Este aparelho não tem um seletor de arquivos disponível.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK) return
        val result = data ?: return

        val uris = mutableListOf<Uri>()
        result.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        if (uris.isEmpty()) result.data?.let { uris += it }

        var added = 0
        var rejected = 0
        uris.forEach { uri ->
            val name = queryDisplayName(uri) ?: "Documento importado"
            val format = Formats.detect(name, contentResolver.getType(uri))
            if (format == null) {
                rejected++
                return@forEach
            }
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Nem todo provedor oferece permissão persistente; o item entra mesmo assim.
            }
            if (database.add(name, uri.toString(), format)) added++
        }
        showLibrary()

        val message = when {
            added > 0 && rejected > 0 -> "$added adicionado(s). $rejected com formato não reconhecido."
            added > 0 -> "$added documento(s) adicionado(s) à biblioteca"
            rejected > 0 -> "Formato não reconhecido. Os formatos aceitos estão listados na biblioteca."
            else -> "Esse documento já está na biblioteca."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: Exception) {
            // Cai no fallback abaixo.
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun openItem(entry: LibraryItem) {
        if (entry.format in Formats.readable) {
            refreshOnResume = true
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_ITEM_ID, entry.id)
            )
        } else {
            Toast.makeText(
                this,
                "O leitor de ${entry.format.uppercase()} ainda não existe. O arquivo continua guardado na biblioteca.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun removeItem(entry: LibraryItem) {
        try {
            // Libera a permissão persistida: o Android limita quantas ela pode guardar por app.
            contentResolver.releasePersistableUriPermission(
                Uri.parse(entry.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Sem permissão persistida para liberar; segue com a remoção.
        }
        database.remove(entry.id)
        showLibrary()
    }

    private fun showPlaceholder(name: String) {
        setContentView(baseScreen(name))
        sectionTitle(name, "Esta área será desenvolvida em uma próxima etapa.")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(22))
            background = rounded(Color.WHITE, 16)
        }
        panel.addView(label("Em construção", 17f, ink, true))
        panel.addView(label("A estrutura inicial do Alexandria está sendo preparada. Esta seção receberá seus recursos durante as próximas etapas.", 13f, muted).apply {
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(panel)
    }

    companion object {
        private const val REQUEST_DOCUMENT = 4101
        private const val KEY_SECTION = "section"
        private const val SECTION_LIBRARY = "Biblioteca"
        private val NAV_ITEMS = listOf(SECTION_LIBRARY, "Notas", "Estatísticas", "Ajustes")
    }
}
