package com.phonepc.explorer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional cloud assist for the quick-search box.
 *
 * The model is NOT asked for timestamps or extension lists. It only rewrites a
 * colloquial query into canonical keywords that the deterministic parser
 * already understands:
 *
 *   我上午在百度下载的图片   ->   上午 百度 图片
 *
 * That keeps the response tiny, makes hallucination easy to spot, and means the
 * time-window maths and extension sets stay in the verified local code. If the
 * call fails for any reason the caller simply keeps the local result.
 */
object RemoteParser {

    /** Canonical words the local parser is known to handle. */
    private val ALLOWED = setOf(
        "今天", "昨天", "前天", "上午", "下午", "中午", "晚上", "凌晨", "深夜", "早上", "傍晚",
        "上周", "本周", "这个月", "上个月", "最近",
        "图片", "照片", "截图", "视频", "音乐", "录音", "文档", "表格",
        "PDF", "安装包", "压缩包", "代码", "文本",
        "百度", "微信", "QQ浏览器", "迅雷", "钉钉", "夸克", "UC浏览器", "百度网盘", "蓝牙"
    )

    private const val SYSTEM = """你是一个文件搜索关键词归一化器。
把用户的中文口语查询转换成若干标准关键词，只输出这些词，用空格分隔，不要解释。
可用的标准词只有这些：%s
如果查询里有具体文件名或人名，原样附加在最后。
示例：
输入：我上午在百度下载的图片
输出：上午 百度 图片
输入：上周微信发我的那个季度报表表格
输出：上周 微信 表格 季度报表
输入：迅雷下的压缩包
输出：迅雷 压缩包
不要输出 JSON，不要输出标点，不要输出任何解释。"""

    /**
     * @return canonical keywords, or null when the API is off, unconfigured, or
     *         the call failed. Never throws.
     */
    fun canonicalize(context: Context, query: String): String? {
        if (!AppSettings.isApiReady(context)) return null
        val endpoint = AppSettings.apiEndpoint.trim()
        val model = AppSettings.apiModel.trim()
        val key = AppSettings.apiKey.trim()
        if (endpoint.isBlank() || model.isBlank()) return null

        return try {
            val payload = JSONObject().apply {
                put("model", model)
                put("temperature", 0)
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM.format(ALLOWED.joinToString("、")))
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", query)
                    })
                })
            }.toString()

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return null }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val content = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?: return null

            sanitize(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Keep only tokens the local parser recognises, plus a trailing free-text
     * tail. Anything else the model invented is dropped rather than trusted.
     */
    private fun sanitize(raw: String): String? {
        val cleaned = raw.replace(Regex("[，,。.、\\n\\r\\t\"'`]"), " ").trim()
        if (cleaned.isBlank()) return null

        val kept = mutableListOf<String>()
        val extras = mutableListOf<String>()
        for (tok in cleaned.split(' ').filter { it.isNotBlank() }) {
            if (tok in ALLOWED) {
                if (tok !in kept) kept += tok
            } else if (tok.length >= 2 && tok.all { it.code > 0x2E80 }) {
                extras += tok      // plausible keyword, keep it as free text
            }
        }
        val all = kept + extras.take(3)
        return if (all.isEmpty()) null else all.joinToString(" ")
    }
}
