package com.alexandria.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Arquivos que o app mantém por conta própria: cópia local dos livros em ZIP (CBZ, EPUB, DOCX
 * precisam de acesso aleatório, que um URI do seletor de documentos não garante) e capas.
 * As cópias ficam no cache: se o Android apagar para liberar espaço, são refeitas na próxima leitura.
 */
object BookFiles {
    private const val COVER_WIDTH = 240

    private fun booksDir(context: Context) = File(context.cacheDir, "books").apply { mkdirs() }
    private fun coversDir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    fun coverFile(context: Context, id: Long) = File(coversDir(context), "$id.jpg")

    /** Marca que já se tentou gerar a capa e não deu (formato sem capa ou arquivo inválido). */
    private fun noCoverMarker(context: Context, id: Long) = File(coversDir(context), "$id.none")

    fun coverAttempted(context: Context, id: Long) =
        coverFile(context, id).exists() || noCoverMarker(context, id).exists()

    /** Lê os primeiros bytes do documento, para reconhecer o tipo real (ex.: CBR que é ZIP). */
    fun header(context: Context, uri: Uri, size: Int = 8): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buffer, read, size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        } ?: ByteArray(0)

    /** Cópia local do documento. Roda em thread de fundo: pode levar alguns segundos. */
    fun localCopy(context: Context, item: LibraryItem): File {
        val target = File(booksDir(context), "${item.id}.${item.format}")
        if (target.length() > 0) return target
        val partial = File(target.path + ".part")
        val input = context.contentResolver.openInputStream(Uri.parse(item.uri))
            ?: throw IllegalStateException("Documento indisponível")
        input.use { source -> FileOutputStream(partial).use { source.copyTo(it, 256 * 1024) } }
        if (!partial.renameTo(target)) throw IllegalStateException("Não foi possível guardar a cópia local")
        return target
    }

    /** Apaga cópia local, capa e marcadores de um documento removido da biblioteca. */
    fun delete(context: Context, id: Long) {
        booksDir(context).listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
        coverFile(context, id).delete()
        noCoverMarker(context, id).delete()
    }

    fun loadCover(context: Context, id: Long): Bitmap? {
        val file = coverFile(context, id)
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }

    /**
     * Gera e guarda a capa (PDF: primeira página; CBZ: primeira imagem; EPUB: a capa declarada).
     * Roda em thread de fundo. Também conta páginas/capítulos para o cartão da biblioteca.
     */
    fun prepare(context: Context, database: LibraryDatabase, item: LibraryItem) {
        val cover = try {
            when (item.format) {
                "pdf" -> pdfCover(context, item, database)
                "cbz" -> zipCover(context, item, database) { zip ->
                    val pages = ComicArchive.pageNames(zip)
                    database.updatePageCount(item.id, pages.size)
                    pages.firstOrNull()
                }
                "epub" -> zipCover(context, item, database) { zip ->
                    val book = EpubPackage.read(zip)
                    database.updatePageCount(item.id, book.chapters.size)
                    book.coverPath
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (cover == null) {
            noCoverMarker(context, item.id).createNewFile()
            return
        }
        FileOutputStream(coverFile(context, item.id)).use { cover.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        cover.recycle()
    }

    private fun pdfCover(context: Context, item: LibraryItem, database: LibraryDatabase): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r") ?: return null
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                database.updatePageCount(item.id, renderer.pageCount)
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val height = (page.height * COVER_WIDTH.toFloat() / page.width).toInt().coerceIn(1, COVER_WIDTH * 3)
                    Bitmap.createBitmap(COVER_WIDTH, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun zipCover(
        context: Context,
        item: LibraryItem,
        database: LibraryDatabase,
        pickImage: (ZipFile) -> String?
    ): Bitmap? = ZipFile(localCopy(context, item)).use { zip ->
        val entry = pickImage(zip)?.let { zip.getEntry(it) } ?: return null
        decodeScaled({ zip.getInputStream(entry) }, COVER_WIDTH)
    }

    /**
     * Decodifica uma imagem já reduzida para perto de [targetWidth] (nunca menor), economizando
     * memória em páginas de quadrinho enormes. [open] é chamado duas vezes: medir e decodificar.
     */
    fun decodeScaled(open: () -> java.io.InputStream, targetWidth: Int, maxPixels: Long = Long.MAX_VALUE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > maxPixels
        ) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return open().use { BitmapFactory.decodeStream(it, null, options) }
    }
}
