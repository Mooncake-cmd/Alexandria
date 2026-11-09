package com.alexandria.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Livros que chegam de outros apps ("Abrir com" e "Compartilhar") e a liberação de espaço dos
 * concluídos. Tudo aqui roda em thread de fundo (copia e apaga arquivos).
 */
object IncomingBooks {
    class Result(val item: LibraryItem, val reused: Boolean)

    class UnsupportedFormat(val name: String) : Exception("Formato não suportado: $name")

    /** Nome e tamanho que o app de origem informa para o arquivo. */
    fun describe(context: Context, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = 0L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)?.takeIf { it.isNotBlank() }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            // Alguns provedores não informam: cai no fim do endereço.
        }
        if (uri.scheme == "file") uri.path?.let { size = File(it).length() }
        return Pair(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Documento", size)
    }

    /**
     * Guarda o livro recebido e devolve o item da biblioteca. O mesmo livro aberto de novo (mesmo nome e
     * tamanho) volta ao registro existente, com o progresso, as anotações e o glossário dele; se estava
     * arquivado na Estante, o arquivo novo o traz de volta.
     */
    fun receive(context: Context, database: LibraryDatabase, uri: Uri, mime: String?): Result {
        val (name, declaredSize) = describe(context, uri)
        var format = Formats.detect(name, mime ?: context.contentResolver.getType(uri)) ?: throw UnsupportedFormat(name)
        if (format == "cbr" && looksLikeZip(BookFiles.header(context, uri, 4))) format = "cbz"

        database.findByNameAndSize(name, declaredSize)?.let { existing ->
            if (!existing.archived && canRead(context, existing.uri)) return Result(existing, true)
        }

        val copy = copyIn(context, uri, name)
        val size = copy.length()
        val existing = database.findByNameAndSize(name, size)
        if (existing != null) {
            if (!existing.archived && canRead(context, existing.uri)) {
                copy.delete()
                return Result(existing, true)
            }
            // Arquivado (ou arquivo antigo sumiu): o livro volta a ter arquivo, no mesmo registro.
            val old = existing.uri
            database.relink(existing.id, Uri.fromFile(copy).toString(), format, size)
            if (BookFiles.isOwnedCopy(context, old)) File(Uri.parse(old).path!!).delete()
            BookFiles.deleteCaches(context, existing.id)
            return Result(database.find(existing.id)!!, true)
        }
        val id = database.add(name, Uri.fromFile(copy).toString(), format, size)
            ?: throw IllegalStateException("Não foi possível adicionar o livro")
        return Result(database.find(id)!!, false)
    }

    private fun canRead(context: Context, uri: String): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun copyIn(context: Context, uri: Uri, name: String): File {
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { "livro" }
        val target = File(BookFiles.libraryDir(context), "${System.currentTimeMillis()}-$safe")
        val partial = File(target.path + ".part")
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Arquivo indisponível")
        try {
            input.use { source -> FileOutputStream(partial).use { source.copyTo(it, 256 * 1024) } }
            if (!partial.renameTo(target)) throw IllegalStateException("Não foi possível guardar o arquivo")
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        return target
    }

    /** O arquivo do livro que chegou num Intent (VIEW traz em data; SEND, em EXTRA_STREAM ou clipData). */
    @Suppress("DEPRECATION")
    fun uriOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        else -> null
    }

    /** Resultado de liberar o espaço de um livro concluído. */
    enum class Freed { FILE_DELETED, ORIGINAL_KEPT, ORIGINAL_NOT_ALLOWED }

    /**
     * Apaga o arquivo do livro concluído e o deixa na Estante (capa, nome, datas, anotações e glossário
     * ficam). Cópias do app são sempre apagadas; o arquivo do celular, só com [deleteOriginal] e se o
     * app tiver permissão de escrita nele.
     */
    fun freeSpace(context: Context, database: LibraryDatabase, item: LibraryItem, deleteOriginal: Boolean): Freed {
        val uri = Uri.parse(item.uri)
        val result = when {
            BookFiles.isOwnedCopy(context, item.uri) -> {
                File(uri.path!!).delete()
                Freed.FILE_DELETED
            }
            !deleteOriginal -> Freed.ORIGINAL_KEPT
            else -> try {
                if (DocumentsContract.deleteDocument(context.contentResolver, uri)) Freed.FILE_DELETED else Freed.ORIGINAL_NOT_ALLOWED
            } catch (e: Exception) {
                Freed.ORIGINAL_NOT_ALLOWED
            }
        }
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            try {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: Exception) {
            }
        }
        BookFiles.deleteCaches(context, item.id)
        database.archive(item.id)
        return result
    }
}
