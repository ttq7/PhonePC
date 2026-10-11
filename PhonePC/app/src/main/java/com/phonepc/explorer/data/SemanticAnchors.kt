package com.phonepc.explorer.data

/**
 * Maps an unfamiliar phrasing onto a keyword the rule parser already understands.
 *
 * The split of labour matters: the embedding does *not* compute time ranges or
 * extension sets, it only answers "which known vocabulary word is this closest
 * to". Everything downstream is the existing deterministic parser, so [上午]
 * still yields the same window whether it was typed literally or reached via
 * 「刚过中午那会儿」.
 */
object SemanticAnchors {

    /**
     * Every keyword here must already be understood by [QueryParser], otherwise
     * a detection would produce a slot nothing can act on.
     */
    private val KEYWORDS = listOf(
        // time
        "上午", "下午", "晚上", "中午", "凌晨", "深夜", "早上", "傍晚",
        "今天", "昨天", "前天", "上周", "本周", "这个月", "上个月", "最近",
        // kind
        "图片", "照片", "截图", "视频", "音乐", "录音", "文档", "表格",
        "PDF", "安装包", "压缩包", "代码", "文本",
        // source
        "百度", "微信", "QQ浏览器", "迅雷", "钉钉", "夸克", "UC浏览器", "百度网盘", "蓝牙"
    )

    private var ready = false
    private var words: List<String> = emptyList()
    private var vecs: List<FloatArray> = emptyList()

    val isReady: Boolean get() = ready

    /** Embed every keyword once. Cheap: ~40 lookups per keyword. */
    fun init() {
        if (ready || !Semantic.isReady) return
        val vs = ArrayList<FloatArray>(KEYWORDS.size)
        val ws = ArrayList<String>(KEYWORDS.size)
        for (k in KEYWORDS) {
            val v = Semantic.embed(k)
            if (v != null) { ws.add(k); vs.add(v) }
        }
        words = ws
        vecs = vs
        ready = words.isNotEmpty()
    }

    /** the single closest keyword above the threshold, or null */
    fun detect(text: String): String? {
        if (!ready) return null
        val qv = Semantic.embed(text) ?: return null
        var best: String? = null
        var bestScore = Semantic.THRESHOLD
        for (i in words.indices) {
            val s = Semantic.cosine(qv, vecs[i])
            if (s > bestScore) { bestScore = s; best = words[i] }
        }
        return best
    }

    /**
     * Fold a semantic detection into a rule-parsed query. Only fires when the
     * rules left something unexplained, so literal wording always wins.
     */
    fun augment(base: QueryParser.Query): QueryParser.Query {
        if (!ready || base.text.isBlank()) return base
        // Only natural-language leftovers. A bare ASCII fragment like "rdme" or
        // "notes" is somebody typing part of a filename, and treating a fuzzy
        // nearest-anchor guess as a constraint there silently drops good hits.
        if (base.text.none { it.code > 0x2E80 }) return base
        val canon = detect(base.text) ?: return base
        val extra = QueryParser.parse(canon)
        if (extra.slots.isEmpty()) return base

        val slots = (base.slots + extra.slots).distinctBy { it.kind }
        return base.copy(
            from = if (base.from >= 0) base.from else extra.from,
            to = if (base.to >= 0) base.to else extra.to,
            extensions = if (base.extensions.isNotEmpty()) base.extensions else extra.extensions,
            sourceFragments = if (base.sourceFragments.isNotEmpty()) base.sourceFragments
            else extra.sourceFragments,
            sourceLabel = base.sourceLabel ?: extra.sourceLabel,
            slots = slots
        )
    }
}
