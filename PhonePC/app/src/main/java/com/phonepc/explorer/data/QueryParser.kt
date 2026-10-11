package com.phonepc.explorer.data

import java.io.File
import java.util.Calendar

/**
 * Turns a natural-language Chinese query into structured slots.
 *
 * This is deliberately a *parser*, not a model. "我上午在百度下载的图片" is not
 * vague — it carries three concrete constraints (a time window, a source app,
 * a file kind), all of which live in a closed vocabulary. Extracting them is
 * slot filling, and slot filling is deterministic, fast and, crucially,
 * explainable: the UI can show the slots as removable chips, so a wrong
 * interpretation is visible and correctable instead of silently wrong.
 *
 * The same `Query` shape is what an on-device LLM would be asked to emit, so a
 * model can be dropped in front of this later without touching the search.
 */
object QueryParser {

    enum class SlotKind { TIME, SOURCE, TYPE }

    data class Slot(val kind: SlotKind, val label: String, val tokens: List<String>)

    /** -1 means "unset" for the bounds. */
    data class Query(
        val text: String,
        val from: Long,
        val to: Long,
        val sourceFragments: List<String>,
        val sourceLabel: String?,
        val extensions: Set<String>,
        val slots: List<Slot>
    ) {
        val isStructured: Boolean get() = slots.isNotEmpty()
    }

    // ---- vocabularies -----------------------------------------------------

    private val PART_WORDS = mapOf(
        "凌晨" to (0 to 6), "清晨" to (5 to 8), "早上" to (6 to 12), "早晨" to (6 to 12),
        "上午" to (6 to 12), "中午" to (11 to 14), "下午" to (12 to 18),
        "傍晚" to (17 to 20), "晚上" to (18 to 24), "夜里" to (20 to 24), "深夜" to (22 to 24)
    )

    private val SPAN_WORDS = mapOf(
        "本周" to 0, "这周" to 0, "这个星期" to 0,
        "上周" to 1, "上星期" to 1,
        "本月" to 0, "这个月" to 0,
        "上个月" to 1, "上月" to 1
    )

    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff", "svg")
    private val VIDEOS = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "m4v", "ts")
    private val AUDIO = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "amr", "opus")

    private val TYPE_WORDS = mapOf(
        "图片" to IMAGES, "照片" to IMAGES, "相片" to IMAGES, "截图" to IMAGES, "图像" to IMAGES,
        "视频" to VIDEOS, "影片" to VIDEOS, "电影" to VIDEOS, "录像" to VIDEOS, "录屏" to VIDEOS,
        "音乐" to AUDIO, "歌曲" to AUDIO, "音频" to AUDIO, "录音" to AUDIO, "声音" to AUDIO,
        "文档" to setOf("doc", "docx", "pdf", "txt", "md", "rtf", "odt"),
        "表格" to setOf("xls", "xlsx", "csv", "et"),
        "幻灯片" to setOf("ppt", "pptx", "dps"),
        "pdf" to setOf("pdf"),
        "安装包" to setOf("apk", "apks", "xapk", "aab"),
        "压缩包" to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz"),
        "压缩文件" to setOf("zip", "rar", "7z", "tar", "gz"),
        "代码" to setOf("kt", "java", "py", "js", "ts", "c", "cpp", "h", "go", "rs", "sh", "json", "xml", "html", "css"),
        "文本" to setOf("txt", "md", "log", "json", "xml", "csv"),
        "电子书" to setOf("epub", "mobi", "azw3", "pdf", "txt")
    )

    /** extensions people type literally instead of naming the kind in Chinese */
    private val EXT_LITERALS = listOf(
        "apk", "apks", "xapk", "aab", "exe", "iso",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg",
        "mp4", "mkv", "avi", "mov", "3gp", "webm",
        "mp3", "flac", "wav", "aac", "ogg", "m4a",
        "zip", "rar", "7z", "tar", "gz",
        "txt", "md", "json", "xml", "html", "log", "db"
    )

    /** words that carry no constraint */
    private val STOP_WORDS = listOf(
        "帮我", "帮忙", "给我", "我", "你", "的", "了", "在", "是", "找", "查找", "搜索", "一下",
        "那个", "这个", "那", "这", "一个", "个", "些", "和", "与", "跟", "把", "被", "给",
        "下载", "存", "保存", "有", "没有", "看过", "打开过", "文件", "里", "里面", "中", "中旬",
        "用", "从", "到", "在", "吗", "呢", "吧", "啊", "呀", "请问",
        // scope words: "on my phone" narrows nothing, so it must not become a keyword
        "手机", "本机", "设备", "本地", "这台", "这手机", "电脑", "储存", "存储",
        "中", "内", "里面", "当中", "所有", "全部", "哪些", "哪些", "有没有", "有些"
    )

    // ---- parsing ----------------------------------------------------------

    fun parse(raw: String, now: Long = System.currentTimeMillis()): Query {
        var work = raw.lowercase()
        val slots = mutableListOf<Slot>()

        var from = -1L
        var to = -1L
        var dayOffset: Int? = null

        // 1. whole-day words, longest first so 大前天 does not lose to 前天
        for ((offset, words) in listOf(
            -3 to listOf("大前天"),
            -2 to listOf("前天"),
            -1 to listOf("昨天", "昨日"),
            0 to listOf("今天", "今日", "本日")
        )) {
            val hit = words.firstOrNull { work.contains(it) }
            if (hit != null) {
                dayOffset = offset
                work = work.replace(hit, " ")
                break
            }
        }

        // 2. week / month spans
        if (dayOffset == null) {
            for ((word, back) in SPAN_WORDS) {
                if (!work.contains(word)) continue
                work = work.replace(word, " ")
                when {
                    word.contains("周") || word.contains("星期") -> {
                        val start = weekStart(now, back)
                        from = start
                        to = start + 7 * DAY - 1
                    }
                    else -> {
                        val c = Calendar.getInstance()
                        c.timeInMillis = now
                        c.add(Calendar.MONTH, -back)
                        c.set(Calendar.DAY_OF_MONTH, 1)
                        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                        from = c.timeInMillis
                        c.add(Calendar.MONTH, 1)
                        to = c.timeInMillis - 1
                    }
                }
                break
            }
        }

        // 3. "最近 N 天" / "N 天前"
        if (from < 0) {
            val m = Regex("(最近|近|过去)?\\s*(\\d+)\\s*(天|日|小时|个?月)内?").find(work)
            if (m != null) {
                val n = m.groupValues[2].toIntOrNull() ?: 0
                val unit = m.groupValues[3]
                val span = when {
                    unit.contains("小时") -> n * HOUR
                    unit.contains("月") -> n * 30L * DAY
                    else -> n * DAY
                }
                from = now - span
                to = now
                work = work.replace(m.value, " ")
            }
        }

        // 4. part of day
        var part: Pair<Int, Int>? = null
        for ((word, range) in PART_WORDS) {
            if (work.contains(word)) {
                part = range
                work = work.replace(word, " ")
                break
            }
        }

        // 5. combine day + part into a window
        if (from < 0) {
            val base = dayStart(now, dayOffset ?: 0)
            if (part != null) {
                from = base + part.first * HOUR
                to = base + part.second * HOUR - 1
            } else if (dayOffset != null) {
                from = base
                to = base + DAY - 1
            }
            if (from >= 0 && dayOffset == null && part == null) { from = -1; to = -1 }
        }

        if (from >= 0) {
            val label = buildTimeLabel(dayOffset, part, from, to)
            slots += Slot(SlotKind.TIME, label, emptyList())
        }

        // 6. file kind
        val exts = mutableSetOf<String>()
        var typeLabel: String? = null
        for ((word, set) in TYPE_WORDS) {
            if (!work.contains(word)) continue
            exts += set
            if (typeLabel == null) typeLabel = word
            work = work.replace(word, " ")
        }
        if (typeLabel != null) slots += Slot(SlotKind.TYPE, typeLabel, emptyList())

        // 7. source app
        val src = QuickFind.findSource(work)
        if (src != null) {
            for (k in src.keys) work = work.replace(k, " ")
            slots += Slot(SlotKind.SOURCE, src.label, src.keys)
        }

        // 7b. a bare file extension counts as a kind filter:
        //     "apk 文件" / ".pdf" / "mp4" are all how people actually ask
        if (exts.isEmpty()) {
            for (e in EXT_LITERALS) {
                if (Regex("\\b" + Regex.escape(e) + "\\b").containsMatchIn(work)) {
                    exts += e
                    typeLabel = e
                    work = work.replace(Regex("\\b" + Regex.escape(e) + "\\b"), " ")
                    break
                }
            }
            if (typeLabel != null) slots += Slot(SlotKind.TYPE, typeLabel!!, emptyList())
        }

        // 8. whatever is left, minus filler words, is free text.
        //    Chinese has no word boundaries, so this must not require spaces
        //    around a stop word — the old " w " form stripped nothing at all.
        var free = work
        for (w in STOP_WORDS.sortedByDescending { it.length }) free = free.replace(w, " ")
        free = free.replace(Regex("\\s+"), " ").trim()

        return Query(
            text = free,
            from = from,
            to = to,
            sourceFragments = src?.keys ?: emptyList(),
            sourceLabel = src?.label,
            extensions = exts,
            slots = slots
        )
    }

    /** drop the slots the user removed, then re-derive the constraints */
    fun apply(query: Query, suppressed: Set<SlotKind>): Query {
        if (suppressed.isEmpty()) return query
        val kept = query.slots.filterNot { it.kind in suppressed }
        return query.copy(
            from = if (SlotKind.TIME in suppressed) -1 else query.from,
            to = if (SlotKind.TIME in suppressed) -1 else query.to,
            extensions = if (SlotKind.TYPE in suppressed) emptySet() else query.extensions,
            sourceFragments = if (SlotKind.SOURCE in suppressed) emptyList() else query.sourceFragments,
            sourceLabel = if (SlotKind.SOURCE in suppressed) null else query.sourceLabel,
            slots = kept
        )
    }

    // ---- helpers ----------------------------------------------------------

    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    private fun dayStart(now: Long, offsetDays: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.add(Calendar.DAY_OF_MONTH, offsetDays)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun weekStart(now: Long, weeksBack: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        c.add(Calendar.WEEK_OF_YEAR, -weeksBack)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun buildTimeLabel(dayOffset: Int?, part: Pair<Int, Int>?, from: Long, to: Long): String {
        val day = when (dayOffset) {
            0 -> "今天"
            -1 -> "昨天"
            -2 -> "前天"
            -3 -> "大前天"
            null -> null
            else -> null
        }
        if (day != null) {
            val partName = PART_WORDS.entries.firstOrNull { it.value == part }?.key
            return if (partName != null && part != null) day + partName else day
        }
        if (part != null) return PART_WORDS.entries.firstOrNull { it.value == part }?.key ?: "今天"
        return "%s ~ %s".format(shortDate(from), shortDate(to))
    }

    private fun shortDate(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return "%d/%d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
}
