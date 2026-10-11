package com.phonepc.explorer.data

import android.os.Environment
import java.io.File

/**
 * Fuzzy, source-aware file search.
 *
 * Two things make searching a phone painful. You rarely remember the exact file
 * name, and you often remember *which app* produced the file instead ("the one
 * QQ Browser downloaded"). So matching runs on two axes:
 *
 *  1. a subsequence match over the whole path, scored like fzf so that
 *     `xmbg` still finds `项目报告.docx`;
 *  2. an alias table mapping app nicknames to the directories those apps write
 *     into, so "QQ浏览器" finds files whose names contain none of those words.
 *
 * The index is a flat, lazily built snapshot of the storage tree. It is not
 * persisted: a phone's storage changes constantly and a stale index that misses
 * a file you just downloaded is worse than a two-second scan.
 */
object QuickFind {

    data class Hit(val file: File, val score: Int, val source: String?)

    /** Result of one query, so the UI can explain what it did. */
    data class Result(
        val hits: List<Hit>,
        val scanned: Int,
        val matchedSource: String?,
        val query: QueryParser.Query
    )

    class Source(val label: String, val keys: List<String>)

    /** the app whose nickname appears in [text], if any */
    fun findSource(text: String): Source? =
        SOURCES.firstOrNull { s -> s.keys.any { text.contains(it) } }

    /**
     * App nickname -> fragments of the path those apps write to, plus common
     * ways people type the name. Keep the keys lowercase.
     */
    private val SOURCES = listOf(
        Source("QQ浏览器", listOf("qqbrowser", "qq浏览器", "qqllq", "qqbrowserdl")),
        Source("微信", listOf("micromsg", "weixin", "wechat", "微信", "wx")),
        Source("QQ", listOf("qqfile_recv", "qq_images", "qq/", "腾讯qq", "qq")),
        Source("钉钉", listOf("dingtalk", "dingtalkmedia", "钉钉")),
        Source("夸克", listOf("quark", "夸克")),
        Source("UC浏览器", listOf("ucdownloads", "ucmobile", "uc浏览器", "uc")),
        // Every entry needs BOTH the Chinese nickname and the ASCII folder name.
        // A query word that is not consumed here falls through to free text,
        // where a Chinese word can never match an ASCII path segment.
        Source("百度网盘", listOf("baidunetdisk", "bdnetdisk", "百度网盘", "百度")),
        Source("迅雷", listOf("xldownload", "thunder", "迅雷")),
        Source("支付宝", listOf("alipay", "支付宝")),
        Source("抖音", listOf("aweme", "douyin", "抖音")),
        Source("网易云音乐", listOf("cloudmusic", "网易云")),
        Source("蓝牙接收", listOf("bluetooth", "蓝牙")),
        Source("浏览器下载", listOf("download", "下载"))
    )

    /** directory names we never descend into */
    private val SKIP_DIRS = setOf(
        FileRepo.INTERNAL_DIR, "Android/data", "Android/obb", ".thumbnails", "LOST.DIR"
    )

    /**
     * A file plus everything the matcher reads. Caching these at index time
     * matters: filtering 50k entries by date would otherwise be 50k
     * `lastModified()` syscalls on every keystroke.
     */
    class Entry(
        val file: File,
        val nameLower: String,
        val pathLower: String,
        val ext: String,
        val modified: Long
    )

    @Volatile
    private var index: List<Entry> = emptyList()

    @Volatile
    var indexedAt: Long = 0L
        private set

    val size: Int get() = index.size

    /** an index older than this is rebuilt on the next visit */
    private const val TTL = 10 * 60 * 1000L

    fun isStale(): Boolean =
        index.isEmpty() || System.currentTimeMillis() - indexedAt > TTL

    /** Walk the storage tree once. Safe to call repeatedly; refreshes. */
    fun buildIndex(): Int {
        val root = Environment.getExternalStorageDirectory()
        val out = ArrayList<Entry>(8192)
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                    if (SKIP_DIRS.any { rel.equals(it, true) || rel.startsWith("$it/", true) }) continue
                    stack.addLast(f)
                } else {
                    val name = f.name
                    out.add(
                        Entry(
                            file = f,
                            nameLower = name.lowercase(),
                            pathLower = f.absolutePath.lowercase(),
                            ext = name.substringAfterLast('.', "").lowercase(),
                            modified = f.lastModified()
                        )
                    )
                }
            }
        }
        index = out
        indexedAt = System.currentTimeMillis()
        return out.size
    }

    fun search(
        query: String,
        suppressed: Set<QueryParser.SlotKind> = emptySet(),
        limit: Int = 400
    ): Result {
        val raw = query.trim()
        if (raw.isEmpty()) {
            return Result(emptyList(), index.size, null, QueryParser.parse(""))
        }

        val q = QueryParser.apply(QueryParser.parse(raw), suppressed)
        // A lone CJK character left over after stripping is a fragment of a
        // phrase ("发我的" -> "发"), not a keyword. Treating it as one demands
        // that Chinese text appear in an ASCII path, which can never match.
        val tokens = q.text.split(' ', '　')
            .filter { it.isNotBlank() }
            .filterNot { q.isStructured && it.length == 1 && it[0].code > 0x2E80 }
        // nothing left to match on: neither structured slots nor free text
        if (tokens.isEmpty() && !q.isStructured) {
            return Result(emptyList(), index.size, null, q)
        }

        // A semantic guess must NEVER exclude anything — it only re-ranks.
        // Using it as a filter silently hides good hits: a query with no kind
        // word at all could get narrowed to images because the leftover text
        // happened to sit closest to the 图片 anchor.
        val soft = if (tokens.isNotEmpty() && SemanticAnchors.isReady &&
            q.text.any { it.code > 0x2E80 }
        ) {
            SemanticAnchors.detect(q.text)?.let { QueryParser.parse(it) }
        } else null

        val frags = q.sourceFragments
        val scored = ArrayList<Hit>(256)
        for (e in index) {
            // structured constraints first — they are pure memory comparisons
            if (q.from >= 0 && e.modified < q.from) continue
            if (q.to >= 0 && e.modified > q.to) continue
            if (q.extensions.isNotEmpty() && e.ext !in q.extensions) continue
            if (frags.isNotEmpty() && !frags.any { e.pathLower.contains(it) }) continue

            var score = 0
            var ok = true
            for (t in tokens) {
                val s = score(e.nameLower, e.pathLower, t)
                if (s < 0) { ok = false; break }
                score += s
            }
            if (!ok) continue

            if (frags.isNotEmpty()) score += 700
            // soft semantic agreement: a bonus, never a gate
            if (soft != null) {
                if (soft.extensions.isNotEmpty() && e.ext in soft.extensions) score += 400
                if (soft.sourceFragments.any { e.pathLower.contains(it) }) score += 300
                if (soft.from >= 0 && e.modified in soft.from..soft.to) score += 300
            }
            score += recencyBonus(e.modified)
            scored.add(Hit(e.file, score, q.sourceLabel))
        }

        scored.sortWith(compareByDescending<Hit> { it.score }.thenBy { it.file.name.length })
        return Result(scored.take(limit), index.size, q.sourceLabel, q)
    }

    /** -1 when [q] is not a subsequence of name or path; higher is better. */
    private fun score(name: String, path: String, q: String): Int {
        val inName = name.indexOf(q)
        if (inName == 0) return 1000 + (60 - name.length).coerceAtLeast(0)
        if (inName > 0) return 800 + (60 - name.length).coerceAtLeast(0)
        if (path.contains(q)) return 500
        val sub = subsequence(q, name)
        if (sub > 0) return 300 + sub
        val subPath = subsequence(q, path)
        return if (subPath > 0) 100 + subPath else -1
    }

    private fun subsequence(needle: String, hay: String): Int {
        var i = 0
        var run = 0
        var total = 0
        for (c in hay) {
            if (i < needle.length && c == needle[i]) {
                run++
                total += 1 + run * 2
                i++
            } else {
                run = 0
            }
        }
        return if (i == needle.length) total else -1
    }

    private fun recencyBonus(modified: Long): Int {
        if (modified <= 0) return 0
        val days = (System.currentTimeMillis() - modified) / 86_400_000L
        return when {
            days < 1 -> 120
            days < 7 -> 80
            days < 30 -> 45
            days < 180 -> 20
            else -> 0
        }
    }
}
