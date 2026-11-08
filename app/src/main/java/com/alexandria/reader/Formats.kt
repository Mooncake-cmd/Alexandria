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
