package com.alexandria.reader

/** Formatos aceitos na biblioteca e quais deles já têm leitor. Kotlin puro, fácil de testar. */
object Formats {
    val supported = setOf("pdf", "epub", "mobi", "djvu", "docx", "txt", "cbz", "cbr")

    /** Formatos que o ReaderActivity já sabe abrir. */
    val readable = setOf("pdf", "txt")

    /**
     * Descobre o formato pelo nome do arquivo e, se a extensão não ajudar (alguns provedores
     * entregam nomes sem extensão), pelo tipo MIME. Retorna null se não for reconhecido.
     */
    fun detect(name: String, mime: String?): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in supported) return extension
        return when (mime) {
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "text/plain" -> "txt"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/x-mobipocket-ebook" -> "mobi"
            "image/vnd.djvu", "image/x-djvu" -> "djvu"
            "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
            "application/vnd.comicbook-rar", "application/x-cbr" -> "cbr"
            else -> null
        }
    }
}

/** Texto de progresso exibido no cartão da biblioteca. Kotlin puro, coberto por testes. */
object ReadingStatus {
    /** Fração (de 0 a 10000) a partir da qual um TXT conta como lido até o fim. */
    const val FINISHED_FRACTION = 9_950

    fun describe(item: LibraryItem): String = when {
        item.format !in Formats.readable -> "Leitor em breve"
        item.format == "pdf" -> describePdf(item.position, item.pageCount, item.openedAt > 0)
        item.position <= 0 -> "Toque para ler"
        item.position >= FINISHED_FRACTION -> "Concluído"
        else -> "${item.position / 100}% lido"
    }

    private fun describePdf(page: Int, pageCount: Int, opened: Boolean): String = when {
        pageCount <= 0 -> if (page <= 0) "Toque para ler" else "Parou na página ${page + 1}"
        !opened && page <= 0 -> "Toque para ler · $pageCount páginas"
        page >= pageCount - 1 && pageCount > 1 -> "Concluído · $pageCount páginas"
        else -> "Página ${page + 1} de $pageCount · ${(page + 1) * 100 / pageCount}%"
    }
}
