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

    fun check(context: Context): Check {
        val current = installedVersion(context)
        val body = try {
            httpGet(API)
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

    /** Hand the downloaded APK to the system installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
