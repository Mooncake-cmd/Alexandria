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

    /**
     * Um documento pode ser copiado/convertido ao mesmo tempo pela capa (em segundo plano) e pelo
     * leitor. A trava por documento faz o segundo esperar e reaproveitar o arquivo pronto.
     */
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Any>()

    private inline fun <T> locked(id: Long, block: () -> T): T = synchronized(locks.getOrPut(id) { Any() }) { block() }

    private fun booksDir(context: Context) = File(context.cacheDir, "books").apply { mkdirs() }

    /**
     * Livros recebidos de outros apps ("Abrir com", "Compartilhar"). Ficam guardados no app (não no
     * cache), porque a permissão que o outro app dá é temporária.
     */
    fun libraryDir(context: Context) = File(context.filesDir, "library").apply { mkdirs() }

    /** O arquivo é uma cópia guardada pelo próprio app (e não um arquivo do celular). */
    fun isOwnedCopy(context: Context, uri: String): Boolean {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "file") return false
        val path = File(parsed.path ?: return false).canonicalPath
        return path.startsWith(libraryDir(context).canonicalPath + File.separator)
    }
    private fun coversDir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    fun coverFile(context: Context, id: Long) = File(coversDir(context), "$id.jpg")

    /** Capa escolhida pelo leitor na galeria. Fica à parte: a capa automática nunca a substitui. */
    fun customCoverFile(context: Context, id: Long) = File(coversDir(context), "$id.custom.jpg")

    fun hasCustomCover(context: Context, id: Long) = customCoverFile(context, id).exists()

    /** Largura e altura da capa personalizada: a proporção (2:3) dos livros na Estante. */
    const val CUSTOM_COVER_WIDTH = 400
    const val CUSTOM_COVER_HEIGHT = 600

    /**
     * Guarda [image] (da galeria) como capa do livro: endireitada pela orientação da foto, cortada
     * no centro na proporção 2:3 e reduzida. Roda em thread de fundo. Falso se não der para ler.
     */
    fun setCustomCover(context: Context, id: Long, image: Uri): Boolean {
        val resolver = context.contentResolver
        val decoded = try {
            decodeScaled({ resolver.openInputStream(image) ?: throw java.io.FileNotFoundException() }, CUSTOM_COVER_WIDTH, 4_000_000)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return false
        val rotation = try {
            photoRotation(context, image)
        } catch (e: Exception) {
            0
        }
        val upright = if (rotation == 0) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true
        ).also { if (it !== decoded) decoded.recycle() }
        val cover = centerCrop(upright, CUSTOM_COVER_WIDTH, CUSTOM_COVER_HEIGHT)
        if (cover !== upright) upright.recycle()
        val target = customCoverFile(context, id)
        val partial = File(target.path + ".part")
        FileOutputStream(partial).use { cover.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        cover.recycle()
        return partial.renameTo(target)
    }

    /** Volta à capa automática (a do arquivo, ou a de couro com o título). */
    fun removeCustomCover(context: Context, id: Long) {
        customCoverFile(context, id).delete()
    }

    /** Recorta o centro de [source] na proporção de [width]×[height] e redimensiona para esse tamanho. */
    fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val cropWidth = (width / scale).toInt().coerceIn(1, source.width)
        val cropHeight = (height / scale).toInt().coerceIn(1, source.height)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(Color.WHITE)
        val left = (source.width - cropWidth) / 2
        val top = (source.height - cropHeight) / 2
        canvas.drawBitmap(
            source,
            android.graphics.Rect(left, top, left + cropWidth, top + cropHeight),
            android.graphics.Rect(0, 0, width, height),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        return result
    }

    /**
     * Fotos de câmera guardam a rotação à parte (EXIF); sem aplicar, a capa sairia deitada.
     * Usa o ExifInterface do próprio Android (7+) para não adicionar dependências ao app.
     */
    @android.annotation.SuppressLint("ExifInterface")
    private fun photoRotation(context: Context, image: Uri): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return 0
        val exif = context.contentResolver.openInputStream(image)?.use { android.media.ExifInterface(it) } ?: return 0
        return when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

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
    fun localCopy(context: Context, item: LibraryItem): File = locked(item.id) {
        // Arquivo que já está no app (recebido de outro app): usa direto, sem copiar de novo.
        if (isOwnedCopy(context, item.uri)) return@locked File(Uri.parse(item.uri).path!!)
        val target = File(booksDir(context), "${item.id}.${item.format}")
        if (target.length() > 0) return@locked target
        val partial = File(target.path + ".part")
        val input = context.contentResolver.openInputStream(Uri.parse(item.uri))
            ?: throw IllegalStateException("Documento indisponível")
        input.use { source -> FileOutputStream(partial).use { source.copyTo(it, 256 * 1024) } }
        if (!partial.renameTo(target)) throw IllegalStateException("Não foi possível guardar a cópia local")
        target
    }

    /**
     * EPUB gerado a partir de um MOBI/AZW/AZW3 pelo EraMOBI. A conversão é feita uma vez e fica no
     * cache, junto da cópia local. Roda em thread de fundo.
     */
    fun mobiAsEpub(context: Context, item: LibraryItem): File = locked(item.id) {
        val epub = File(booksDir(context), "${item.id}.mobi.epub")
        if (epub.length() > 0) return@locked epub
        val source = localCopy(context, item)
        OreEngine.start(context, "eramobi").use { engine -> OreFormats.mobiToEpub(engine, source, epub) }
        epub
    }

    /** Pasta das imagens extraídas de um DOCX. */
    fun docxMediaDir(context: Context, id: Long) = File(booksDir(context), "$id.media")

    /** Grava uma entrada do ZIP em [dir] (uma vez) e devolve o arquivo, ou null se não existir. */
    fun extractEntry(zip: ZipFile, path: String, dir: File): File? {
        val entry = zip.getEntry(path) ?: return null
        dir.mkdirs()
        val target = File(dir, path.replace('/', '_'))
        if (target.length() != entry.size || entry.size <= 0) {
            zip.getInputStream(entry).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        }
        return target
    }

    /** Pasta onde as páginas de um CBR em RAR são extraídas sob demanda. */
    fun rarPagesDir(context: Context, id: Long) = File(booksDir(context), "$id.pages")

    /** Fonte de páginas de um quadrinho CBR: ZIP disfarçado ou RAR de verdade (pelo EraComic). */
    fun comicSource(context: Context, item: LibraryItem): PageSource {
        val file = localCopy(context, item)
        val header = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        if (looksLikeZip(header)) return ComicSource(ZipFile(file))
        return RarComicSource(OreEngine.start(context, "eracomic"), file, rarPagesDir(context, item.id))
    }

    fun djvuSource(context: Context, item: LibraryItem): PageSource =
        DjvuSource(OreEngine.start(context, "eradjvu"), localCopy(context, item))

    /** Apaga só as cópias e conversões em cache de um livro (a capa fica). */
    fun deleteCaches(context: Context, id: Long) {
        booksDir(context).listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.deleteRecursively() }
    }

    /** Apaga cópia local, capa e marcadores de um documento removido da biblioteca. */
    fun delete(context: Context, id: Long) {
        booksDir(context).listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.deleteRecursively() }
        coverFile(context, id).delete()
        customCoverFile(context, id).delete()
        noCoverMarker(context, id).delete()
    }

    fun loadCover(context: Context, id: Long): Bitmap? {
        val file = customCoverFile(context, id).takeIf { it.exists() } ?: coverFile(context, id)
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
                "epub" -> epubCover(context, item, database, localCopy(context, item))
                "mobi" -> epubCover(context, item, database, mobiAsEpub(context, item))
                "djvu", "cbr" -> pageCover(database, item, if (item.format == "djvu") djvuSource(context, item) else comicSource(context, item))
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

    private fun epubCover(context: Context, item: LibraryItem, database: LibraryDatabase, epub: File): Bitmap? =
        ZipFile(epub).use { zip ->
            val book = EpubPackage.read(zip)
            database.updatePageCount(item.id, book.chapters.size)
            val entry = book.coverPath?.let { zip.getEntry(it) } ?: return null
            decodeScaled({ zip.getInputStream(entry) }, COVER_WIDTH)
        }

    /** Capa de um documento paginado pelos motores: a primeira página, pequena. */
    private fun pageCover(database: LibraryDatabase, item: LibraryItem, source: PageSource): Bitmap? = try {
        database.updatePageCount(item.id, source.pageCount)
        source.render(0, COVER_WIDTH)?.bitmap
    } finally {
        source.close()
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
