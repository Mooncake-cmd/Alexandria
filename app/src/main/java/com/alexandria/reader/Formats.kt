package com.alexandria.reader

/** Formatos aceitos na biblioteca e quais deles já têm leitor. Kotlin puro, fácil de testar. */
object Formats {
    val supported = setOf("pdf", "epub", "mobi", "djvu", "docx", "txt", "cbz", "cbr")

    /** Formatos que o ReaderActivity já sabe abrir. CBR só abre quando é um ZIP disfarçado. */
    val readable = setOf("pdf", "txt", "cbz", "epub", "docx")

    /** Formatos paginados: a posição salva é o índice da página. Os demais usam fração de 0 a 10000. */
    val paged = setOf("pdf", "cbz")

    /** Outras extensões que são o mesmo formato. */
    private val aliases = mapOf("azw" to "mobi", "azw3" to "mobi", "djv" to "djvu")

    /**
     * Descobre o formato pelo nome do arquivo e, se a extensão não ajudar (alguns provedores
     * entregam nomes sem extensão), pelo tipo MIME. Retorna null se não for reconhecido.
     */
    fun detect(name: String, mime: String?): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in supported) return extension
        aliases[extension]?.let { return it }
        return when (mime) {
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "text/plain" -> "txt"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/x-mobipocket-ebook", "application/vnd.amazon.ebook" -> "mobi"
            "image/vnd.djvu", "image/x-djvu" -> "djvu"
            "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
            "application/vnd.comicbook-rar", "application/x-cbr" -> "cbr"
            else -> null
        }
    }
}

/** Texto e barra de progresso do cartão da biblioteca. Kotlin puro, coberto por testes. */
object ReadingStatus {
    /** Fração (de 0 a 10000) a partir da qual um texto conta como lido até o fim. */
    const val FINISHED_FRACTION = 9_950

    fun describe(item: LibraryItem): String = when {
        item.format !in Formats.readable -> "Leitor em breve"
        item.finished -> "Concluído"
        item.format in Formats.paged -> describePages(item.position, item.pageCount, item.openedAt > 0)
        item.format == "epub" && item.position <= 0 ->
            if (item.pageCount > 0) "Toque para ler · ${item.pageCount} capítulos" else "Toque para ler"
        item.position <= 0 -> "Toque para ler"
        item.position >= FINISHED_FRACTION -> "Lido até o fim"
        else -> "${item.position / 100}% lido"
    }

    /** Progresso de 0 a 1 para a barra do cartão. */
    fun progress(item: LibraryItem): Float = when {
        item.finished -> 1f
        item.format in Formats.paged ->
            if (item.pageCount > 0 && (item.openedAt > 0 || item.position > 0)) (item.position + 1f) / item.pageCount else 0f
        else -> item.position / 10_000f
    }.coerceIn(0f, 1f)

    private fun describePages(page: Int, pageCount: Int, opened: Boolean): String = when {
        pageCount <= 0 -> if (page <= 0) "Toque para ler" else "Parou na página ${page + 1}"
        !opened && page <= 0 -> "Toque para ler · $pageCount páginas"
        page >= pageCount - 1 && pageCount > 1 -> "Última página · $pageCount páginas"
        else -> "Página ${page + 1} de $pageCount · ${(page + 1) * 100 / pageCount}%"
    }
}

/** Onde fica uma anotação, em texto curto, sem precisar abrir o livro. Kotlin puro. */
object NoteLocation {
    fun describe(format: String, position: Int, pageCount: Int): String = when {
        format in Formats.paged -> "Pág. ${position + 1}"
        format == "epub" && pageCount > 0 -> {
            val chapter = (position.toLong() * pageCount / 10_000).toInt().coerceIn(0, pageCount - 1)
            "Cap. ${chapter + 1} · ${position / 100}%"
        }
        else -> "${position / 100}%"
    }
}
