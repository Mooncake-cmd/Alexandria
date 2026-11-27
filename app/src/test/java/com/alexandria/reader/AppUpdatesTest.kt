package com.alexandria.reader

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Atualização pelo app: leitura da Release, comparação de versões, download e conferência do APK. */
@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {
    private lateinit var activity: Activity
    private val context: Context get() = activity

    private fun release(tag: String, apk: Boolean = true, draft: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("draft", draft).put("prerelease", false)
        .put("body", "- Atualização pelo próprio app\n- Correções")
        .put("assets", JSONArray().apply {
            put(JSONObject().put("name", "notas.txt").put("browser_download_url", "https://x/notas.txt").put("size", 3))
            if (apk) put(JSONObject().put("name", "Alexandria-${tag.removePrefix("v")}.apk").put("browser_download_url", "https://x/a.apk").put("size", 8))
        }).toString()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AppUpdates.automatic = false
        AppUpdates.transport = { Pair(500, "") }
    }

    /** Espera o trabalho em segundo plano e roda o retorno na linha principal. */
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        var value: Any? = UNSET
        start { value = it }
        val deadline = System.currentTimeMillis() + 5_000
        while (value === UNSET && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            CountDownLatch(1).await(10, TimeUnit.MILLISECONDS)
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Test
    fun comparesVersionsNumberByNumber() {
        assertTrue(AppUpdates.compare("0.21.0", "0.20.1") > 0)
        assertTrue(AppUpdates.compare("v0.20.10", "0.20.9") > 0)
        assertEquals(0, AppUpdates.compare("v1.0", "1.0.0"))
        assertTrue(AppUpdates.compare("0.9.9", "0.10.0") < 0)
    }

    @Test
    fun readsTheReleaseAndItsApk() {
        val r = AppUpdates.parse(release("v0.21.0"))!!
        assertEquals("0.21.0", r.version)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals(8L, r.apkSize)
        assertTrue(r.notes.contains("Atualização pelo próprio app"))
        assertNull(AppUpdates.parse(release("v0.21.0", apk = false)))
        assertNull(AppUpdates.parse(release("v0.21.0", draft = true)))
        assertNull(AppUpdates.parse("não é json"))
    }

    @Test
    fun checkOffersOnlyNewerVersionsAndRemembersTheDay() {
        val installed = AppUpdates.installedVersion(context)
        AppUpdates.transport = { Pair(200, release("v999.0.0")) }
        val newer = await<Result<AppRelease?>> { AppUpdates.check(activity, it) }
        assertEquals("999.0.0", newer.getOrThrow()!!.version)
        assertTrue(AppUpdates.lastCheck(context) > 0)

        AppUpdates.transport = { Pair(200, release("v$installed")) }
        assertNull(await<Result<AppRelease?>> { AppUpdates.check(activity, it) }.getOrThrow())

        // Nenhuma versão publicada ainda: não é erro.
        AppUpdates.transport = { Pair(404, "{}") }
        assertNull(await<Result<AppRelease?>> { AppUpdates.check(activity, it) }.getOrThrow())

        AppUpdates.transport = { throw java.io.IOException("sem rede") }
        assertTrue(await<Result<AppRelease?>> { AppUpdates.check(activity, it) }.isFailure)
    }

    @Test
    fun dailyCheckRunsOncePerDayAndCanBeTurnedOff() {
        AppUpdates.automatic = true
        val now = 10 * AppUpdates.DAY_MS
        assertTrue(AppUpdates.due(context, now))
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putLong("last_check", now).commit()
        assertFalse(AppUpdates.due(context, now + AppUpdates.DAY_MS - 1))
        assertTrue(AppUpdates.due(context, now + AppUpdates.DAY_MS))
        AppUpdates.setEnabled(context, false)
        assertFalse(AppUpdates.due(context, now + 5 * AppUpdates.DAY_MS))
        AppUpdates.setEnabled(context, true)
        AppUpdates.automatic = false
        assertFalse(AppUpdates.due(context, now + 5 * AppUpdates.DAY_MS))
    }

    @Test
    fun downloadChecksSizePackageAndVersion() {
        val r = AppUpdates.parse(release("v999.0.0"))!!
        AppUpdates.downloader = { _, target, progress ->
            target.writeBytes(ByteArray(8))
            progress(1f)
        }
        AppUpdates.archiveInfo = { _, _ -> PackageInfo().apply { packageName = context.packageName; @Suppress("DEPRECATION") versionCode = Int.MAX_VALUE } }
        val ok = await<Result<File>> { AppUpdates.download(activity, r, {}) { res -> it(res) } }
        val apk = ok.getOrThrow()
        assertTrue(apk.exists())
        assertEquals("Alexandria-999.0.0.apk", apk.name)

        // Outro app no lugar do Alexandria.
        AppUpdates.archiveInfo = { _, _ -> PackageInfo().apply { packageName = "com.outro.app"; @Suppress("DEPRECATION") versionCode = Int.MAX_VALUE } }
        val wrong = await<Result<File>> { AppUpdates.download(activity, r, {}) { res -> it(res) } }
        assertEquals("O arquivo baixado não é o Alexandria.", wrong.exceptionOrNull()!!.message)
        assertFalse(File(AppUpdates.updatesDir(context), "Alexandria-999.0.0.apk").exists())

        // Versão igual ou mais velha que a instalada.
        AppUpdates.archiveInfo = { _, _ -> PackageInfo().apply { packageName = context.packageName; @Suppress("DEPRECATION") versionCode = 0 } }
        val old = await<Result<File>> { AppUpdates.download(activity, r, {}) { res -> it(res) } }
        assertNotNull(old.exceptionOrNull())

        // Download cortado no meio.
        AppUpdates.downloader = { _, target, _ -> target.writeBytes(ByteArray(3)) }
        val cut = await<Result<File>> { AppUpdates.download(activity, r, {}) { res -> it(res) } }
        assertEquals("O download veio incompleto. Tente de novo.", cut.exceptionOrNull()!!.message)
    }

    private companion object {
        val UNSET = Any()
    }
}
