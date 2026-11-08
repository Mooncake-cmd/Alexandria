package com.alexandria.reader

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decodificação de arquivos de texto. Kotlin puro (só java.nio), coberto por testes unitários. */
object TextDecoding {
    private const val BOM = '\uFEFF'

    /**
     * Converte os bytes em texto com quebras de linha normalizadas para "\n".
     *
     * Ordem de tentativa: BOM de UTF-16 (LE/BE), UTF-8 estrito e, se falhar, Windows-1252,
     * comum em .txt antigos em português. Quando o arquivo foi cortado ([truncated]), pode ter
     * sobrado meio caractere no fim: o UTF-8 é tentado de novo aparando até 3 bytes.
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false): String =
        normalizeLineEndings(decodeRaw(bytes, truncated).removePrefix(BOM.toString()))

    fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun decodeRaw(bytes: ByteArray, truncated: Boolean): String {
        utf16Charset(bytes)?.let { charset ->
            // Um caractere UTF-16 cortado ao meio vira um byte solto: descarta para não gerar lixo.
            val length = if (bytes.size % 2 == 1) bytes.size - 1 else bytes.size
            return String(bytes, 2, length - 2, charset)
        }
        val maxCut = if (truncated) 3 else 0
        for (cut in 0..maxCut) {
            val length = bytes.size - cut
            if (length < 0) break
            strictUtf8(bytes, length)?.let { return it }
        }
        return String(bytes, Charset.forName("windows-1252"))
    }

    private fun utf16Charset(bytes: ByteArray): Charset? {
        if (bytes.size < 2) return null
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return when {
            first == 0xFF && second == 0xFE -> Charsets.UTF_16LE
            first == 0xFE && second == 0xFF -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun strictUtf8(bytes: ByteArray, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

/**
 * Divide um texto longo em blocos para o leitor exibir numa lista. Um único TextView com
 * megabytes de texto trava a interface; blocos pequenos são medidos e desenhados sob demanda.
 */
object TextChunker {
    const val TARGET_CHARS = 2_000
    const val MAX_CHARS = 4_000

    /**
     * Agrupa linhas inteiras até cerca de [target] caracteres. A quebra de linha entre dois
     * blocos fica implícita, então `chunks.joinToString("\n")` reconstrói o texto original.
     * A exceção são linhas maiores que [max]: essas são cortadas num espaço próximo ao limite.
     */
    fun chunk(text: String, target: Int = TARGET_CHARS, max: Int = MAX_CHARS): List<String> {
        require(target in 1..max) { "target deve estar entre 1 e max" }
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            chunks += current.toString()
            current.setLength(0)
        }

        var hasLine = false
        for (line in text.split('\n')) {
            if (line.length > max) {
                if (hasLine) flush()
                hasLine = false
                splitLongLine(line, max).forEach { chunks += it }
                continue
            }
            if (hasLine && current.length + 1 + line.length > target) {
                flush()
                hasLine = false
            }
            if (hasLine) current.append('\n')
            current.append(line)
            hasLine = true
        }
        if (hasLine) flush()
        return chunks
    }

    private fun splitLongLine(line: String, max: Int): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        while (line.length - start > max) {
            val limit = start + max
            val space = line.lastIndexOf(' ', limit - 1)
            // Corta no último espaço da metade final do bloco; sem espaço, corta no limite.
            val end = if (space > start + max / 2) space + 1 else limit
            parts += line.substring(start, end)
            start = end
        }
        parts += line.substring(start)
        return parts
    }
}
