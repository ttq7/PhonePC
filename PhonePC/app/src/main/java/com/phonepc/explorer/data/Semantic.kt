package com.phonepc.explorer.data

import android.content.Context
import kotlin.math.sqrt

/**
 * On-device semantic matching for natural-language queries.
 *
 * This uses a *static* embedding, not a transformer: every token has one fixed
 * 512-dim vector, and a phrase is the mean of its token vectors. That is enough
 * to map "刚过中午那会儿" onto the 上午/下午 anchors, which is the whole job —
 * and it costs no native inference runtime at all, just a lookup table.
 *
 * The table is the dequantized token embedding matrix from bge-small-zh:
 * uint8 with zero-point 128. The per-tensor scale cancels under cosine
 * similarity, so it is not stored.
 *
 * Verified against the full transformer on the same six queries: the anchor
 * ranking is identical, only the absolute scores are lower (so the threshold
 * here is tuned lower).
 */
object Semantic {

    const val DIM = 512
    private const val ZERO_POINT = 128
    private const val UNK_ID = 100
    private const val CLS_ID = 101
    private const val SEP_ID = 102
    private const val MAX_TOKEN_LEN = 100

    /** anything at or above this counts as "this anchor fired" */
    const val THRESHOLD = 0.45f

    @Volatile
    private var table: ByteArray? = null

    @Volatile
    private var vocab: HashMap<String, Int>? = null

    private var prefix = "##"

    val isReady: Boolean get() = table != null && vocab != null
    val rows: Int get() = (table?.size ?: 0) / DIM

    fun load(context: Context) {
        if (isReady) return
        table = context.assets.open("emb_u8.bin").use { it.readBytes() }
        val text = context.assets.open("vocab.txt").use { it.readBytes() }.toString(Charsets.UTF_8)
        val map = HashMap<String, Int>(21128 * 2)
        text.split('\n').forEachIndexed { i, t -> if (t.isNotEmpty()) map[t] = i }
        vocab = map
    }

    // ---- tokenizer --------------------------------------------------------

    private fun isCjk(c: Char): Boolean {
        val cp = c.code
        return (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) ||
                (cp in 0xF900..0xFAFF) || (cp in 0x20000..0x2FA1F)
    }

    private fun isAsciiLetterOrDigit(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun isAsciiPunct(c: Char): Boolean {
        val cp = c.code
        return (cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)
    }

    /** WordPiece for one whitespace-free chunk. */
    private fun wordpiece(chunk: String, v: HashMap<String, Int>): List<Int> {
        if (chunk.length > MAX_TOKEN_LEN) return listOf(UNK_ID)
        val out = ArrayList<Int>(4)
        var start = 0
        while (start < chunk.length) {
            var end = chunk.length
            var hit = -1
            while (start < end) {
                val sub = if (start > 0) prefix + chunk.substring(start, end)
                else chunk.substring(start, end)
                val id = v[sub]
                if (id != null) { hit = id; break }
                end--
            }
            if (hit < 0) return listOf(UNK_ID)
            out.add(hit)
            start = end
        }
        return out
    }

    fun tokenize(text: String, v: HashMap<String, Int>): IntArray {
        val ids = ArrayList<Int>(16)
        ids.add(CLS_ID)
        val ascii = StringBuilder()
        fun flush() {
            if (ascii.isNotEmpty()) {
                ids.addAll(wordpiece(ascii.toString().lowercase(), v))
                ascii.setLength(0)
            }
        }
        for (c in text) {
            when {
                isCjk(c) -> { flush(); v[c.toString()]?.let { ids.add(it) } ?: ids.add(UNK_ID) }
                isAsciiLetterOrDigit(c) -> ascii.append(c)
                else -> flush()
            }
        }
        flush()
        ids.add(SEP_ID)
        return ids.toIntArray()
    }

    // ---- embedding --------------------------------------------------------

    /** mean of the token vectors, normalised; null when nothing matched */
    fun embed(text: String): FloatArray? {
        val t = table ?: return null
        val v = vocab ?: return null
        val ids = tokenize(text, v)
        val n = rows
        val acc = FloatArray(DIM)
        var used = 0
        for (id in ids) {
            if (id < 0 || id >= n) continue
            val off = id * DIM
            for (d in 0 until DIM) {
                acc[d] += ((t[off + d].toInt() and 0xFF) - ZERO_POINT).toFloat()
            }
            used++
        }
        if (used == 0) return null
        var norm = 0f
        for (d in 0 until DIM) {
            acc[d] /= used
            norm += acc[d] * acc[d]
        }
        norm = sqrt(norm)
        if (norm < 1e-6f) return null
        for (d in 0 until DIM) acc[d] /= norm
        return acc
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
