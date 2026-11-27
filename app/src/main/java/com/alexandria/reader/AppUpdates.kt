package com.alexandria.reader

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Uma versão publicada nas Releases do GitHub do projeto. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

/**
 * Atualização pelo próprio app: confere (uma vez por dia, ou pelo botão em Ajustes) a última Release
 * do GitHub do projeto, baixa o APK, confere que é o Alexandria assinado com a mesma chave e de
 * versão mais nova, e pede ao Android para instalar. O Android sempre pede a confirmação da pessoa.
 */
object AppUpdates {
    const val LATEST = "https://api.github.com/repos/mooncake-cmd/Alexandria/releases/latest"
    private const val PREFS = "updates"
    const val DAY_MS = 24L * 60 * 60 * 1000

    private val executor = Executors.newSingleThreadExecutor()

    /** Troca da rede nos testes: GET do endereço → (código, corpo). */
    @Volatile
    var transport: (String) -> Pair<Int, String> = ::httpGet

    /** Troca do download nos testes. */
    @Volatile
    var downloader: (String, File, (Float) -> Unit) -> Unit = ::httpDownload

    /** Nos testes de tela (Robolectric) a verificação diária não roda sozinha. */
    @Volatile
    var automatic = Build.FINGERPRINT != "robolectric"

    /** Compara versões "0.20.1" e "0.21.0" número a número. */
    fun compare(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Lê a resposta de releases/latest. Sem APK anexado, não há o que instalar. */
    fun parse(json: String): AppRelease? = try {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) apk = a
        }
        val tag = o.optString("tag_name")
        if (apk == null || tag.isBlank() || o.optBoolean("draft") || o.optBoolean("prerelease")) null
        else AppRelease(tag.removePrefix("v"), o.optString("body").trim(), apk.getString("browser_download_url"), apk.optLong("size"))
    } catch (e: org.json.JSONException) {
        null
    }

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: PackageManager.NameNotFoundException) {
        "0"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("daily", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("daily", on).apply()
    fun lastCheck(context: Context) = prefs(context).getLong("last_check", 0)

    /** Hora da verificação diária? (ligada, e mais de um dia desde a última) */
    fun due(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        automatic && enabled(context) && (now - lastCheck(context) >= DAY_MS || now < lastCheck(context))

    /**
     * Procura versão nova. Devolve a Release se ela for mais nova que a instalada, null se o app já
     * está em dia. Erro só para falta de internet ou resposta estranha do GitHub.
     */
    fun check(context: Activity, done: (Result<AppRelease?>) -> Unit) {
        val app = context.applicationContext
        val installed = installedVersion(context)
        executor.execute {
            val result = runCatching {
                val (code, body) = transport(LATEST)
                // 404: o projeto ainda não publicou nenhuma versão.
                if (code == 404) return@runCatching null
                if (code !in 200..299) throw IllegalStateException("O GitHub respondeu $code.")
                prefs(app).edit().putLong("last_check", System.currentTimeMillis()).apply()
                parse(body)?.takeIf { compare(it.version, installed) > 0 }
            }
            context.runOnUiThread { done(result) }
        }
    }

    fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    /** Baixa o APK da Release para a pasta de atualizações (a anterior é apagada). */
    fun download(context: Activity, release: AppRelease, onProgress: (Float) -> Unit, done: (Result<File>) -> Unit) {
        val dir = updatesDir(context)
        executor.execute {
            val result = runCatching {
                dir.deleteRecursively()
                dir.mkdirs()
                val target = File(dir, "Alexandria-${release.version}.apk")
                val partial = File(dir, target.name + ".part")
                downloader(release.apkUrl, partial) { f -> context.runOnUiThread { onProgress(f) } }
                if (release.apkSize > 0 && partial.length() != release.apkSize) {
                    partial.delete()
                    throw IllegalStateException("O download veio incompleto. Tente de novo.")
                }
                if (!partial.renameTo(target)) throw IllegalStateException("Não foi possível guardar a atualização.")
                verify(context, target)?.let {
                    target.delete()
                    throw IllegalStateException(it)
                }
                target
            }
            context.runOnUiThread { done(result) }
        }
    }

    /** Lê o pacote de um APK (troca nos testes). */
    @Volatile
    var archiveInfo: (Context, File) -> PackageInfo? = { context, file ->
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        context.packageManager.getPackageArchiveInfo(file.path, flags)
    }

    /** Impressões digitais (SHA-256) das chaves que assinaram o pacote. */
    @Suppress("DEPRECATION")
    fun signatures(info: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { s ->
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else info.signatures
        return raw.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Confere o APK baixado. Devolve o problema (em português) ou null se está tudo certo. */
    fun verify(context: Context, apk: File): String? {
        val info = archiveInfo(context, apk) ?: return "O arquivo baixado não é um APK válido."
        if (info.packageName != context.packageName) return "O arquivo baixado não é o Alexandria."
        val installed = try {
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            context.packageManager.getPackageInfo(context.packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        if (versionCode(info) <= versionCode(installed)) return "A versão baixada não é mais nova que a instalada."
        val mine = signatures(installed)
        val theirs = signatures(info)
        // Alguns Androids antigos não leem a assinatura de um APK ainda não instalado: aí quem confere é o instalador.
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
            return "A atualização foi assinada com outra chave e o Android não instalaria por cima. Nada foi alterado."
        }
        return null
    }

    /** O Android (8+) exige que a pessoa permita, uma vez, que o Alexandria instale atualizações. */
    fun canInstall(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Só é usada quando [canInstall] é falso, o que só acontece do Android 8 em diante. */
    @android.annotation.TargetApi(26)
    fun permissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))

    /** Entrega o APK ao instalador do Android, que mostra a tela de confirmação. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("alexandria.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Alexandria")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return Pair(code, stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun httpDownload(url: String, target: File, onProgress: (Float) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Alexandria")
            // O GitHub redireciona o download para outro endereço https; o HttpURLConnection segue sozinho.
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) throw IllegalStateException("O download respondeu ${connection.responseCode}.")
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            var done = 0L
            var lastShown = -1
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastShown) {
                                lastShown = percent
                                onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Resposta do instalador do Android: mostra a confirmação, ou avisa se não deu certo. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(context, "Atualização cancelada.", Toast.LENGTH_SHORT).show()
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "A atualização foi assinada com outra chave."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Falta espaço no aparelho para a atualização."
                    else -> "A atualização não foi instalada${if (detail.isNullOrBlank()) "." else ": $detail"}"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
