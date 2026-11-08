package com.alexandria.reader

import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Formatos lidos pelos motores do OpenReadEra:
 * - MOBI/AZW/AZW3: o EraMOBI converte para EPUB, que o leitor de EPUB do app exibe;
 * - DJVU: o EraDjVu desenha cada página;
 * - CBR em RAR: o EraComic lista e extrai as páginas, que o leitor de quadrinhos exibe.
 */
object OreFormats {
    /** Converte um MOBI/AZW/AZW3 em EPUB. Roda em thread de fundo; pode levar alguns segundos. */
    fun mobiToEpub(engine: OreEngine, source: File, target: File) {
        target.delete()
        engine.call(
            OreEngine.CMD_REQ_CONVERT,
            OreEngine.Arg.string(""), // sem socket: o motor abre o arquivo pelo caminho
            OreEngine.Arg.int(OreEngine.FORMAT_MOBI),
            OreEngine.Arg.string(source.path),
            OreEngine.Arg.int(OreEngine.FORMAT_EPUB),
            // O EraMOBI tira a última extensão do caminho de saída: "x.epub.tmp" vira "x.epub".
            OreEngine.Arg.string(target.path + ".tmp")
        ).requireOk("Conversão do MOBI")
        if (target.length() == 0L) throw OreException("A conversão do MOBI não gerou o EPUB")
    }

    /** Entrada de um arquivo RAR, como o EraComic lista: "índice|nome|tamanho|comprimido". */
    data class RarEntry(val index: Int, val name: String, val size: Long)

    fun parseRarEntry(line: String): RarEntry? {
        val first = line.indexOf('|')
        val last = line.lastIndexOf('|')
        val middle = if (last > first) line.lastIndexOf('|', last - 1) else -1
        if (first <= 0 || middle <= first) return null
        val index = line.substring(0, first).toIntOrNull() ?: return null
        val size = line.substring(middle + 1, last).toLongOrNull() ?: 0L
        return RarEntry(index, line.substring(first + 1, middle), size)
    }

    /** As páginas de um quadrinho RAR: imagens em ordem natural (mesma regra do CBZ). */
    fun rarPages(entries: List<RarEntry>): List<RarEntry> {
        val byName = entries.associateBy { it.name.replace('\\', '/') }
        return ComicArchive.pageNames(byName.keys.toList()).mapNotNull { byName[it] }
    }
}

/** Quadrinho CBR em RAR. Cada página é extraída sob demanda para o cache e decodificada de lá. */
class RarComicSource(
    private val engine: OreEngine,
    private val archive: File,
    private val pagesDir: File
) : PageSource {
    private val pages: List<OreFormats.RarEntry>

    init {
        val response = engine.call(OreEngine.CMD_REQ_COMIC_RAR_INFO, OreEngine.Arg.string(""), OreEngine.Arg.string(archive.path))
            .requireOk("Leitura do RAR")
        pages = OreFormats.rarPages(response.values.mapNotNull { OreFormats.parseRarEntry(it.string) })
        if (pages.isEmpty()) {
            engine.close()
            throw IllegalArgumentException("Nenhuma imagem no RAR")
        }
        pagesDir.mkdirs()
    }

    override val pageCount: Int get() = pages.size

    /** Extrai a página para um arquivo (uma vez) e devolve o caminho. */
    fun extract(index: Int): File {
        val entry = pages[index]
        val target = File(pagesDir, "${entry.index}.${entry.name.substringAfterLast('.', "img")}")
        if (target.length() > 0) return target
        val result = engine.call(
            OreEngine.CMD_REQ_COMIC_RAR_EXTRACT,
            OreEngine.Arg.string(""),
            OreEngine.Arg.string(archive.path),
            OreEngine.Arg.int(entry.index),
            OreEngine.Arg.string(target.path)
        ).requireOk("Extração da página")
        if (result.values.firstOrNull()?.int != 1 || target.length() == 0L) throw OreException("Página ${index + 1} não extraída")
        return target
    }

    override fun render(index: Int, width: Int): RenderedPage? {
        val file = extract(index)
        val bitmap = BookFiles.decodeScaled({ file.inputStream() }, width, PageSource.MAX_PIXELS) ?: return null
        val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1)
        return RenderedPage(bitmap, width, height)
    }

    override fun close() = engine.close()
}

/** Documento DJVU desenhado pelo EraDjVu (DjVuLibre). */
class DjvuSource(private val engine: OreEngine, file: File) : PageSource {
    override val pageCount: Int

    init {
        val response = try {
            engine.call(
                OreEngine.CMD_REQ_OPEN,
                OreEngine.Arg.string(""),
                OreEngine.Arg.int(OreEngine.FORMAT_DJVU),
                OreEngine.Arg.string(file.path)
            ).requireOk("Abertura do DJVU")
        } catch (e: Exception) {
            engine.close()
            throw e
        }
        pageCount = response.values.firstOrNull()?.int ?: 0
    }

    /** Tamanho da página em pixels, na resolução do documento. */
    fun pageSize(index: Int): Pair<Float, Float> {
        val values = engine.call(OreEngine.CMD_REQ_PAGE_INFO, OreEngine.Arg.int(index)).requireOk("Página do DJVU")
            .values.firstOrNull()?.floats() ?: FloatArray(0)
        if (values.size < 2 || values[0] <= 0f || values[1] <= 0f) throw OreException("Página ${index + 1} sem tamanho")
        return values[0] to values[1]
    }

    /** Pixels RGBA da página inteira em [width] x [height]. */
    fun renderPixels(index: Int, width: Int, height: Int): ByteArray {
        val response = engine.call(
            OreEngine.CMD_REQ_PAGE_RENDER,
            OreEngine.Arg.int(index),
            OreEngine.Arg.int(width),
            OreEngine.Arg.int(height),
            // Recorte da página (x, y, largura, altura em frações) e dois valores reservados.
            OreEngine.Arg.floats(0f, 0f, 1f, 1f, 0f, 0f)
        ).requireOk("Desenho da página do DJVU")
        val pixels = response.values.firstOrNull()?.bytes ?: throw OreException("Página sem pixels")
        if (pixels.size != width * height * 4) throw OreException("Tamanho de página inesperado")
        return pixels
    }

    override fun render(index: Int, width: Int): RenderedPage {
        val (pageWidth, pageHeight) = pageSize(index)
        var w = width
        var h = (pageHeight * width / pageWidth).toInt().coerceAtLeast(1)
        val pixels = w.toLong() * h
        if (pixels > PageSource.MAX_PIXELS) {
            val scale = sqrt(PageSource.MAX_PIXELS.toDouble() / pixels)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }
        val data = renderPixels(index, w, h)
        // O motor entrega bytes R, G, B, A: a mesma ordem que o Bitmap ARGB_8888 guarda na memória.
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        engine.call(OreEngine.CMD_REQ_PAGE_FREE, OreEngine.Arg.int(index))
        return RenderedPage(bitmap, w, h)
    }

    override fun close() = engine.close()
}
