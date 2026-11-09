package com.alexandria.reader

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente dos motores do OpenReadEra (EraMOBI, EraDjVu, EraComic).
 *
 * Os motores são programas separados, empacotados no APK como lib<motor>.so. O app inicia o
 * programa, passa dois FIFOs (pipes nomeados) na linha de comando e conversa por eles com o
 * protocolo binário do OpenReadEra: 1 byte de comando, 1 byte de resultado (só na resposta) e
 * uma lista de valores (inteiro, float ou bloco de bytes). Nada é ligado ao código do app.
 *
 * Uma instância atende um pedido por vez; quem usa deve chamar de uma única thread de fundo.
 */
class OreEngine private constructor(
    private val process: Process,
    private val directory: File,
    private val requests: BufferedOutputStream,
    private val responses: InputStream
) : Closeable {
    @Volatile
    private var closed = false

    /** Envia um pedido e espera a resposta. Lança [OreException] se o motor falhar ou morrer. */
    fun call(command: Int, vararg args: Arg): Response {
        if (closed) throw OreException("Motor encerrado")
        try {
            writeRequest(command, args)
            val response = readResponse()
            if (response.command != command + 1) {
                throw OreException("Resposta inesperada ${response.command} para o pedido $command")
            }
            return response
        } catch (e: IOException) {
            throw OreException("O motor parou de responder", e)
        }
    }

    private fun writeRequest(command: Int, args: Array<out Arg>) {
        requests.write(command or if (args.isNotEmpty()) HAS_DATA else 0)
        args.forEachIndexed { index, arg ->
            val hasNext = if (index < args.size - 1) HAS_DATA else 0
            requests.write(arg.type or hasNext)
            requests.write(le(arg.value))
            arg.bytes?.let { requests.write(it) }
        }
        requests.flush()
    }

    private fun readResponse(): Response {
        val header = readByte()
        val result = readByte()
        val values = mutableListOf<Value>()
        var hasData = header and HAS_DATA != 0
        while (hasData) {
            val type = readByte()
            hasData = type and HAS_DATA != 0
            val raw = readExactly(4)
            val value = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).int
            val kind = type and 0x7F
            val bytes = if (kind == TYPE_ARRAY && value > 0) readExactly(value) else null
            values += Value(kind, value, bytes)
        }
        return Response(header and 0x7F, result, values)
    }

    private fun readByte(): Int {
        val b = responses.read()
        if (b < 0) throw EOFException("Fim da conversa com o motor")
        return b
    }

    private fun readExactly(count: Int): ByteArray {
        if (count < 0 || count > MAX_PAYLOAD) throw OreException("Resposta grande demais: $count bytes")
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = responses.read(buffer, read, count - read)
            if (n < 0) throw EOFException("Resposta incompleta do motor")
            read += n
        }
        return buffer
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // Pede para sair com educação; se não der, encerra o processo.
            requests.write(CMD_REQ_QUIT)
            requests.flush()
        } catch (e: IOException) {
            // O motor já tinha saído.
        }
        try {
            requests.close()
        } catch (e: IOException) {
        }
        try {
            responses.close()
        } catch (e: IOException) {
        }
        process.destroy()
        directory.deleteRecursively()
    }

    /** Valor de um pedido. */
    class Arg private constructor(val type: Int, val value: Int, val bytes: ByteArray?) {
        companion object {
            fun int(value: Int) = Arg(TYPE_INT, value, null)

            /** Texto terminado em zero, como o motor espera (um texto vazio ainda manda o zero). */
            fun string(value: String): Arg {
                val bytes = value.toByteArray(Charsets.UTF_8) + 0
                return Arg(TYPE_ARRAY, bytes.size, bytes)
            }

            fun floats(vararg values: Float): Arg {
                val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { buffer.putFloat(it) }
                return Arg(TYPE_ARRAY, values.size * 4, buffer.array())
            }
        }
    }

    /** Valor de uma resposta: inteiro, float ou bloco de bytes. */
    class Value(val type: Int, val raw: Int, val bytes: ByteArray?) {
        val int: Int get() = raw
        val float: Float get() = java.lang.Float.intBitsToFloat(raw)

        /** Texto de um bloco de bytes (sem o zero final). */
        val string: String
            get() {
                val data = bytes ?: return ""
                val end = data.indexOf(0).let { if (it < 0) data.size else it }
                return String(data, 0, end, Charsets.UTF_8)
            }

        fun floats(): FloatArray {
            val data = bytes ?: return FloatArray(0)
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(data.size / 4) { buffer.getFloat() }
        }
    }

    class Response(val command: Int, val result: Int, val values: List<Value>) {
        val ok: Boolean get() = result == RES_OK

        fun requireOk(what: String): Response {
            if (!ok) throw OreException("$what falhou (código $result)")
            return this
        }
    }

    companion object {
        const val HAS_DATA = 0x80
        const val TYPE_INT = 3
        const val TYPE_FLOAT = 4
        const val TYPE_ARRAY = 5
        const val RES_OK = 0

        const val CMD_NOTIF_READY = 1
        const val CMD_REQ_OPEN = 2
        const val CMD_REQ_QUIT = 4
        const val CMD_REQ_PAGE_INFO = 6
        const val CMD_REQ_PAGE_RENDER = 10
        const val CMD_REQ_PAGE_TEXT = 14
        const val CMD_REQ_PAGE_FREE = 12
        const val CMD_REQ_CONVERT = 34
        const val CMD_REQ_VERSION = 36
        const val CMD_REQ_COMIC_RAR_INFO = 74
        const val CMD_REQ_COMIC_RAR_EXTRACT = 76

        const val FORMAT_EPUB = 1
        const val FORMAT_PDF = 3
        const val FORMAT_MOBI = 4
        const val FORMAT_DJVU = 5

        /** Maior resposta aceita (uma página DJVU renderizada grande cabe com folga). */
        private const val MAX_PAYLOAD = 64 * 1024 * 1024
        private const val START_TIMEOUT_MS = 15_000L

        private val counter = AtomicInteger()

        /**
         * Executável do motor [name] ("eramobi", "eradjvu", "eracomic") instalado com o app. Nos
         * testes na JVM, a propriedade "ore.hostEngines" aponta para os motores compilados para Linux.
         */
        fun binary(context: Context, name: String): File {
            System.getProperty("ore.hostEngines")?.let { dir ->
                val host = File(dir, name)
                if (host.canExecute()) return host
            }
            return File(context.applicationInfo.nativeLibraryDir, "lib$name.so")
        }

        /** Inicia o motor no Android. */
        fun start(context: Context, name: String): OreEngine {
            val binary = binary(context, name)
            if (!binary.exists()) throw OreException("Motor $name não está instalado neste aparelho")
            return start(binary, File(context.cacheDir, "engines"), ::makeFifo)
        }

        private fun makeFifo(fifo: File) {
            var error: Throwable? = null
            try {
                Os.mkfifo(fifo.path, "600".toInt(8))
            } catch (e: Throwable) {
                error = e
            }
            if (fifo.exists()) return
            // Fora do Android (testes na JVM) o android.system.Os não cria o FIFO: usa o comando.
            val result = try {
                ProcessBuilder("mkfifo", "-m", "600", fifo.path).start().waitFor()
            } catch (e: IOException) {
                -1
            }
            if (result != 0 || !fifo.exists()) throw OreException("Não foi possível criar o canal com o motor", error)
        }

        /**
         * Inicia [binary] com dois FIFOs criados em [workDir]. [makeFifo] permite que os testes na
         * JVM criem os FIFOs sem as APIs do Android.
         */
        fun start(binary: File, workDir: File, makeFifo: (File) -> Unit): OreEngine {
            val directory = File(workDir, "ore-${android.os.Process.myPid()}-${counter.incrementAndGet()}")
            directory.deleteRecursively()
            if (!directory.mkdirs()) throw OreException("Não foi possível preparar o motor")
            val input = File(directory, "in")   // pedidos: app escreve, motor lê
            val output = File(directory, "out") // respostas: motor escreve, app lê
            makeFifo(input)
            makeFifo(output)

            // Diretório de trabalho "/": o EraDjVu abre o documento como "fd:N", que o DjVuLibre
            // transforma em caminho absoluto e só reconhece quando ele vira "/fd:N".
            val process = ProcessBuilder(binary.path, input.path, output.path)
                .directory(File("/"))
                .redirectErrorStream(true)
                .start()
            // A saída de texto do motor (logs) precisa ser consumida para ele não travar.
            Thread({
                try {
                    process.inputStream.use { stream -> while (stream.read() >= 0) Unit }
                } catch (e: IOException) {
                }
            }, "ore-log").apply { isDaemon = true }.start()
            // Se o motor morrer antes de abrir os FIFOs, destrava as aberturas abaixo.
            Thread({
                try {
                    process.waitFor()
                } catch (e: InterruptedException) {
                    return@Thread
                }
                unblock(input)
                unblock(output)
            }, "ore-watch").apply { isDaemon = true }.start()
            val timeout = Thread({
                try {
                    Thread.sleep(START_TIMEOUT_MS)
                    process.destroy()
                } catch (e: InterruptedException) {
                }
            }, "ore-timeout").apply { isDaemon = true }
            timeout.start()

            try {
                // O motor abre primeiro as respostas, manda "pronto" e só então abre os pedidos.
                val responses = BufferedInputStream(FileInputStream(output), 64 * 1024)
                val ready = responses.read()
                if (ready != CMD_NOTIF_READY || responses.read() != RES_OK) throw OreException("O motor $binary não iniciou")
                val requests = BufferedOutputStream(FileOutputStream(input), 8 * 1024)
                timeout.interrupt()
                return OreEngine(process, directory, requests, responses)
            } catch (e: IOException) {
                process.destroy()
                directory.deleteRecursively()
                throw OreException("O motor ${binary.name} não iniciou", e)
            } catch (e: OreException) {
                process.destroy()
                directory.deleteRecursively()
                throw e
            }
        }

        /** Abrir um FIFO para leitura e escrita nunca bloqueia e libera quem espera do outro lado. */
        private fun unblock(fifo: File) {
            try {
                val fd = Os.open(fifo.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
                Os.close(fd)
            } catch (e: Throwable) {
                // Nos testes na JVM não há android.system.Os: recorre a abrir pelo Java.
                try {
                    java.io.RandomAccessFile(fifo, "rw").close()
                } catch (ignored: Exception) {
                }
            }
        }

        private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
    }
}

class OreException(message: String, cause: Throwable? = null) : IOException(message, cause)
