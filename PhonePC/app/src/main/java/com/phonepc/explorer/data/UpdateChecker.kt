package com.phonepc.explorer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update check against the project's GitHub releases.
 *
 * Deliberately manual: the app advertises "不联网", so nothing here runs unless
 * the user taps 检查更新 in the overflow menu. No background polling, no
 * analytics, no request on launch.
 *
 * Uses HttpURLConnection and org.json on purpose — the whole app has three
 * dependencies and a JSON library is not worth adding for one endpoint.
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/ttq7/PhonePC/releases/latest"

    /**
     * Endpoint used for the check. Overridable so the updater can be tested
     * against a local server instead of by publishing throwaway releases —
     * the standard approach for update systems, and the reason the default
     * lives in one place rather than inline at the call site.
     */
    @Volatile
    var endpoint: String = API

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long
    )

    /** Result of a check, so the UI can explain a failure instead of going silent. */
    sealed class Check {
        data class Newer(val release: Release, val current: String) : Check()
        data class UpToDate(val current: String) : Check()
        data class NoRelease(val current: String) : Check()
        data class Failed(val reason: String) : Check()
    }

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun check(context: Context, url: String = resolveEndpoint(context)): Check {
        val current = installedVersion(context)
        val body = try {
            httpGet(url)
        } catch (e: Exception) {
            return Check.Failed(e.message ?: e.javaClass.simpleName)
        }

        // no releases published yet -> GitHub answers 404, handled as an empty body
        if (body.isNullOrBlank()) return Check.NoRelease(current)

        return try {
            val o = JSONObject(body)
            val tag = o.optString("tag_name").ifBlank { o.optString("name") }
            val title = o.optString("name").ifBlank { tag }
            val notes = o.optString("body")
            val assets = o.optJSONArray("assets")

            var url = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", true)) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            val rel = Release(tag, title, notes, url, size)
            if (isNewer(tag, current)) Check.Newer(rel, current) else Check.UpToDate(current)
        } catch (e: Exception) {
            Check.Failed("解析失败: ${e.message}")
        }
    }

    /**
     * Version comparison that tolerates the schemes people actually use:
     * "2.5", "v2.5", "2.5.1", "2.10" (which must beat "2.9").
     */
    fun isNewer(tag: String, current: String): Boolean {
        val a = numbers(tag)
        val b = numbers(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        Regex("\\d+").findAll(v).mapNotNull { it.value.toIntOrNull() }.toList()

    /**
     * Resolves the endpoint, allowing a local override.
     *
     * An update system is awkward to test: you either publish throwaway
     * releases or you cannot exercise the code at all. Reading the address from
     * a file in the app's own external files directory makes the whole path —
     * request, JSON, download, signature check — testable against a local
     * server, which is how this kind of component is normally verified.
     *
     * The file lives under Android/data/<pkg>/files, which other apps cannot
     * write to on Android 11+. Delete it and the GitHub default applies.
     */
    private fun resolveEndpoint(context: Context): String {
        return try {
            val f = File(context.getExternalFilesDir(null), "update_endpoint.txt")
            if (f.isFile) {
                val v = f.readText().trim()
                if (v.startsWith("http")) v else endpoint
            } else endpoint
        } catch (e: Exception) {
            endpoint
        }
    }

    // ---- download & install ----------------------------------------------

    /** Download the release APK. [onProgress] is called with 0..100, or -1 when unknown. */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val dir = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }
        val out = File(dir, "此手机-${release.tag}.apk")
        if (out.exists()) out.delete()

        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PhonePC-Updater")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        val total = if (release.apkSize > 0) release.apkSize else conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(out).use { fos ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }
                fos.flush()
            }
        }
        conn.disconnect()
        return out
    }

    /**
     * Hand the downloaded APK to the system installer.
     *
     * The signature is checked first. An update path that installs whatever the
     * network hands it is a supply-chain hole: a hijacked endpoint or a
     * tampered repo could push any APK, and the system installer would happily
     * run it as an update. Android itself enforces signature equality at
     * install time, but failing here gives a comprehensible message instead of
     * a bare "App not installed".
     */
    fun install(context: Context, apk: File) {
        if (!sameSigner(context, apk)) {
            throw SecurityException("下载的安装包签名与本机应用不一致，已拒绝安装")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** true when [apk] is signed by the same key as the running app. */
    fun sameSigner(context: Context, apk: File): Boolean = try {
        @Suppress("DEPRECATION")
        val expected = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            .signatures
        @Suppress("DEPRECATION")
        val actual = context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
            ?.signatures
        when {
            expected == null || actual == null -> false
            expected.size != actual.size -> false
            else -> expected.indices.all { expected[it] == actual[it] }
        }
    } catch (e: Exception) {
        false
    }

    // ---- http ------------------------------------------------------------

    /** Returns null on 404 (no release yet) rather than throwing. */
    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PhonePC-Updater")
        }
        conn.connect()
        val code = conn.responseCode
        if (code == 404) { conn.disconnect(); return null }
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("GitHub 返回 HTTP $code")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }
}
