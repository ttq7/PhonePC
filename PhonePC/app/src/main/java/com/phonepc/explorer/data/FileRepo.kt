package com.phonepc.explorer.data

import com.phonepc.explorer.model.FsEntry
import com.phonepc.explorer.model.SortBy
import com.phonepc.explorer.model.SortSpec
import java.io.File
import java.util.Locale

/** What to do when a name already exists at the destination. */
enum class ConflictPolicy { REPLACE, SKIP, KEEP_BOTH }

/** Outcome of a batch file operation: partial success is reported, not thrown. */
data class OpResult(
    val ok: Int = 0,
    val failed: List<String> = emptyList(),
    val cancelled: Boolean = false
)

/** All file-system access funnels through here, off the main thread. */
object FileRepo {

    /** our own recycle-bin store; never shown to the user (Windows hides $Recycle.Bin too) */
    const val INTERNAL_DIR = ".phonepc_trash"

    /**
     * @return the directory contents, or **null** when the directory cannot be
     * listed at all (e.g. Android/data on Android 11+ even with all-files
     * access). The caller shows a different message for null than for empty.
     */
    fun readDir(dir: File, spec: SortSpec): List<FsEntry>? {
        val children = dir.listFiles() ?: return null
        val out = ArrayList<FsEntry>(children.size)
        for (f in children) {
            if (f.name == INTERNAL_DIR) continue
            val isDir = f.isDirectory
            val name = f.name
            out += FsEntry(
                file = f,
                name = name,
                isDir = isDir,
                size = if (isDir) 0L else f.length(),
                lastModified = f.lastModified(),
                ext = if (isDir) "" else name.substringAfterLast('.', ""),
                childCount = -1
            )
        }
        return sort(out, spec)
    }

    fun sort(items: List<FsEntry>, spec: SortSpec): List<FsEntry> {
        val base: Comparator<FsEntry> = when (spec.by) {
            SortBy.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
            SortBy.DATE -> Comparator { a, b -> a.lastModified.compareTo(b.lastModified) }
            SortBy.TYPE -> Comparator { a, b ->
                val c = naturalCompare(a.ext, b.ext)
                if (c != 0) c else naturalCompare(a.name, b.name)
            }
            SortBy.SIZE -> Comparator { a, b ->
                val c = a.size.compareTo(b.size)
                if (c != 0) c else naturalCompare(a.name, b.name)
            }
        }
        val ordered = if (spec.ascending) base else base.reversed()
        // folders always lead, exactly like Explorer
        val foldersFirst = Comparator<FsEntry> { a, b ->
            when {
                a.isDir == b.isDir -> 0
                a.isDir -> -1
                else -> 1
            }
        }
        return items.sortedWith(foldersFirst.then(ordered))
    }

    /**
     * Explorer sorts with StrCmpLogicalW, so "图 2.png" comes before "图 10.png".
     * A plain string compare would put 10 first.
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++
                val na = a.substring(i, i2).trimStart('0')
                val nb = b.substring(j, j2).trimStart('0')
                var c = na.length.compareTo(nb.length)
                if (c == 0) c = na.compareTo(nb)
                if (c != 0) return c
                i = i2
                j = j2
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Recursive search under [root]; breadth-limited to keep the UI responsive. */
    fun search(root: File, query: String, spec: SortSpec, limit: Int = 800): List<FsEntry> {
        val needle = query.lowercase(Locale.getDefault())
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<FsEntry>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < limit) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.name == INTERNAL_DIR) continue
                val name = f.name
                if (name.lowercase(Locale.getDefault()).contains(needle)) {
                    val isDir = f.isDirectory
                    out += FsEntry(
                        file = f,
                        name = name,
                        isDir = isDir,
                        size = if (isDir) 0L else f.length(),
                        lastModified = f.lastModified(),
                        ext = if (isDir) "" else name.substringAfterLast('.', ""),
                        subtitle = dir.absolutePath
                    )
                }
                if (f.isDirectory && !f.name.startsWith(".")) stack.addLast(f)
            }
        }
        return sort(out, spec)
    }

    /** Sources whose name already exists at the destination. */
    fun findConflicts(sources: List<File>, destDir: File): List<String> =
        sources.filter { src ->
            src.parentFile?.absolutePath != destDir.absolutePath &&
                    File(destDir, src.name).exists()
        }.map { it.name }

    /** "新建文件夹", "新建文件夹 (2)", ... – never collides. */
    fun uniqueName(dir: File, base: String, ext: String? = null): String {
        fun candidate(n: Int): String {
            val suffix = if (n == 0) "" else " ($n)"
            return if (ext.isNullOrEmpty()) "$base$suffix" else "$base$suffix.$ext"
        }
        var n = 0
        var name = candidate(0)
        while (File(dir, name).exists()) {
            n++
            name = candidate(n)
            if (n > 9999) break
        }
        return name
    }

    fun mkdir(parent: File, name: String): File {
        val target = File(parent, name)
        if (!target.mkdirs()) throw IllegalStateException("无法创建文件夹")
        return target
    }

    fun createTextFile(parent: File, name: String): File {
        val target = File(parent, name)
        if (!target.createNewFile()) throw IllegalStateException("无法创建文件")
        return target
    }

    fun rename(file: File, newName: String): File {
        val target = File(file.parentFile, newName)
        if (target.exists()) throw IllegalStateException("同名项目已存在")
        if (!file.renameTo(target)) throw IllegalStateException("重命名失败")
        return target
    }

    /**
     * Copy or move [sources] into [destDir].
     *
     * A single failing item never aborts the batch – it is collected in
     * [OpResult.failed] so the caller can report "3 个成功，1 个失败".
     * [isCancelled] is polled between items, and [onItem] reports
     * (done, total, currentName) so the UI can show real progress.
     */
    fun transfer(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        policy: ConflictPolicy,
        onItem: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): OpResult {
        var ok = 0
        val failed = mutableListOf<String>()
        var cancelled = false
        val total = sources.size

        for ((i, src) in sources.withIndex()) {
            if (isCancelled()) { cancelled = true; break }
            if (src.parentFile?.absolutePath == destDir.absolutePath) continue
            if (!src.exists()) { failed += src.name; continue }
            onItem(i, total, src.name)
            try {
                val raw = File(destDir, src.name)
                val target: File? = when {
                    !raw.exists() -> raw
                    policy == ConflictPolicy.SKIP -> null
                    policy == ConflictPolicy.REPLACE -> {
                        deleteRecursive(raw); raw
                    }
                    else -> {  // KEEP_BOTH – never destroy the existing file
                        val dot = src.name.lastIndexOf('.')
                        val base = if (dot > 0) src.name.substring(0, dot) else src.name
                        val ext = if (dot > 0) src.name.substring(dot + 1) else null
                        File(destDir, uniqueName(destDir, "$base - 副本", ext))
                    }
                }
                if (target == null) continue

                // same volume + move => atomic rename
                if (move && src.renameTo(target)) { ok++; continue }
                copyRecursive(src, target)
                if (move) deleteRecursive(src)
                ok++
            } catch (e: Exception) {
                failed += src.name
            }
        }
        return OpResult(ok, failed, cancelled)
    }

    fun delete(files: List<File>, isCancelled: () -> Boolean = { false }): OpResult {
        var ok = 0
        val failed = mutableListOf<String>()
        var cancelled = false
        for (f in files) {
            if (isCancelled()) { cancelled = true; break }
            try {
                deleteRecursive(f); ok++
            } catch (e: Exception) {
                failed += f.name
            }
        }
        return OpResult(ok, failed, cancelled)
    }

    private fun deleteRecursive(f: File) {
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursive(it) }
        }
        if (!f.delete() && f.exists()) throw IllegalStateException("无法删除 ${f.name}")
    }

    private fun copyRecursive(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw IllegalStateException("无法创建 ${dst.name}")
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
    }

    /** Recursive byte count, used for folder Properties. */
    fun sizeOf(file: File, limit: Int = 20000): Long {
        if (file.isFile) return file.length()
        var total = 0L
        var seen = 0
        val stack = ArrayDeque<File>()
        stack.addLast(file)
        while (stack.isNotEmpty() && seen < limit) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                seen++
                if (seen > limit) break
                if (c.isDirectory) stack.addLast(c) else total += c.length()
            }
        }
        return total
    }

    fun countChildren(dir: File): Int = dir.listFiles()?.size ?: 0

    /**
     * Rename many files at once. [nameFor] returns the wanted name for each
     * source. Renames run through temporary names so a batch that swaps or
     * chains names (a->b, b->c) cannot clobber itself, and a generated name
     * that would hit an unrelated existing file gets a " (n)" suffix.
     */
    fun batchRename(files: List<File>, nameFor: (index: Int, file: File) -> String): OpResult {
        val failed = mutableListOf<String>()
        val batchPaths = files.map { it.absolutePath }.toSet()
        val plan = ArrayList<Pair<File, File>>(files.size)
        val used = HashSet<String>()

        files.forEachIndexed { i, f ->
            val raw = nameFor(i, f).trim()
            if (raw.isEmpty() || raw.contains('/') || raw.contains('\\')) {
                failed += f.name
                return@forEachIndexed
            }
            var name = raw
            var n = 1
            while (true) {
                val cand = File(f.parentFile, name)
                val clash = used.contains(cand.absolutePath) ||
                        (cand.exists() && cand.absolutePath != f.absolutePath &&
                                !batchPaths.contains(cand.absolutePath))
                if (!clash) break
                val dot = raw.lastIndexOf('.')
                name = if (dot > 0) raw.substring(0, dot) + " ($n)" + raw.substring(dot)
                else "$raw ($n)"
                n++
                if (n > 999) break
            }
            val target = File(f.parentFile, name)
            used.add(target.absolutePath)
            if (target.absolutePath != f.absolutePath) plan.add(f to target)
        }

        if (plan.isEmpty()) return OpResult(0, failed)

        // phase 1: every source moves to a unique temporary name
        val temps = ArrayList<Pair<File, File>>(plan.size)
        plan.forEachIndexed { i, (src, dst) ->
            val tmp = File(src.parentFile, "~phonepc_ren_${System.currentTimeMillis()}_$i")
            if (src.renameTo(tmp)) temps.add(tmp to dst) else failed += src.name
        }
        // phase 2: temporary name -> final name
        var ok = 0
        for ((tmp, dst) in temps) {
            try {
                if (dst.exists()) deleteRecursive(dst)
                if (tmp.renameTo(dst)) ok++ else failed += dst.name
            } catch (e: Exception) {
                failed += dst.name
            }
        }
        return OpResult(ok, failed)
    }

    /** MD5 and SHA-256 in a single pass over the file. */
    fun md5AndSha256(file: File, isCancelled: () -> Boolean = { false }): Pair<String, String>? {
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        try {
            file.inputStream().use { input ->
                while (true) {
                    if (isCancelled()) return null
                    val n = input.read(buf)
                    if (n <= 0) break
                    md5.update(buf, 0, n)
                    sha.update(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            return null
        }
        return hex(md5.digest()) to hex(sha.digest())
    }

    private const val HEX = "0123456789abcdef"

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
