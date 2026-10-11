package com.phonepc.explorer.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * A real recycle bin.
 *
 * Items are moved into `<external root>/.phonepc_trash/items/<id>` and an
 * append-only index records where each one came from, so restore can put it
 * back. Renaming into a sibling directory on the same volume keeps the
 * "delete" instant even for huge files.
 */
object TrashRepo {

    private const val DIR = FileRepo.INTERNAL_DIR
    private const val ITEMS = "items"
    private const val INDEX = "index.tsv"

    data class Entry(
        val id: String,
        val originalPath: String,
        val deletedAt: Long,
        val isDir: Boolean
    ) {
        val name: String
            get() = originalPath.trimEnd('/').substringAfterLast('/')
    }

    fun root(context: Context): File =
        File(Environment.getExternalStorageDirectory(), DIR)

    private fun itemsDir(context: Context) = File(root(context), ITEMS)

    private fun indexFile(context: Context) = File(root(context), INDEX)

    /** The real file backing an entry, inside the bin. */
    fun fileFor(context: Context, entry: Entry): File = File(itemsDir(context), entry.id)

    fun list(context: Context): List<Entry> {
        val idx = indexFile(context)
        if (!idx.isFile) return emptyList()
        val out = ArrayList<Entry>()
        try {
            idx.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val p = line.split('\t')
                if (p.size >= 4) {
                    out += Entry(p[0], p[1], p[2].toLongOrNull() ?: 0L, p[3] == "1")
                }
            }
        } catch (_: Exception) {
        }
        // drop index rows whose payload has gone missing
        return out.filter { fileFor(context, it).exists() }.sortedByDescending { it.deletedAt }
    }

    private fun writeIndex(context: Context, entries: List<Entry>) {
        try {
            val text = entries.joinToString("\n") {
                "${it.id}\t${it.originalPath}\t${it.deletedAt}\t${if (it.isDir) 1 else 0}"
            }
            indexFile(context).writeText(text)
        } catch (_: Exception) {
        }
    }

    fun count(context: Context): Int = list(context).size

    /** What [trash] did, including the entries created so the caller can undo. */
    data class Outcome(val result: OpResult, val entries: List<Entry>)

    /** Move [files] into the bin. Nothing is destroyed, so this is undoable. */
    fun trash(context: Context, files: List<File>, onItem: (String) -> Unit = {}): Outcome {
        val items = itemsDir(context)
        if (!items.exists() && !items.mkdirs()) {
            return Outcome(OpResult(failed = files.map { it.name }), emptyList())
        }
        val entries = list(context).toMutableList()
        val created = mutableListOf<Entry>()
        var ok = 0
        val failed = mutableListOf<String>()

        for ((i, f) in files.withIndex()) {
            onItem(f.name)
            try {
                if (!f.exists()) { failed += f.name; continue }
                val id = "t${System.currentTimeMillis()}_${i}_${Integer.toHexString(f.name.hashCode())}"
                val dst = File(items, id)
                if (!f.renameTo(dst)) {
                    // different volume: copy then remove the source
                    copyRecursive(f, dst)
                    deleteRecursive(f)
                }
                val e = Entry(id, f.absolutePath, System.currentTimeMillis(), f.isDirectory)
                entries += e
                created += e
                ok++
            } catch (e: Exception) {
                failed += f.name
            }
        }
        writeIndex(context, entries)
        return Outcome(OpResult(ok, failed), created)
    }

    /** Put entries back where they came from, avoiding name clashes. */
    fun restore(context: Context, entries: List<Entry>, onItem: (String) -> Unit = {}): OpResult {
        var ok = 0
        val failed = mutableListOf<String>()
        val remaining = list(context).toMutableList()

        for (e in entries) {
            onItem(e.name)
            try {
                val src = fileFor(context, e)
                if (!src.exists()) { failed += e.name; continue }
                val wanted = File(e.originalPath)
                val parent = wanted.parentFile ?: continue
                if (!parent.exists()) parent.mkdirs()
                val dst = if (wanted.exists()) {
                    val base = wanted.name.substringBeforeLast('.', wanted.name)
                    val ext = if (wanted.name.contains('.')) wanted.name.substringAfterLast('.') else null
                    File(parent, FileRepo.uniqueName(parent, "$base - 还原", ext))
                } else wanted

                val moved = src.renameTo(dst) || run {
                    copyRecursive(src, dst); deleteRecursive(src); true
                }
                if (moved) {
                    remaining.removeAll { it.id == e.id }
                    ok++
                } else {
                    failed += e.name
                }
            } catch (ex: Exception) {
                failed += e.name
            }
        }
        writeIndex(context, remaining)
        return OpResult(ok, failed)
    }

    /** Destroy entries for good. */
    fun purge(context: Context, entries: List<Entry>, onItem: (String) -> Unit = {}): OpResult {
        var ok = 0
        val failed = mutableListOf<String>()
        val remaining = list(context).toMutableList()

        for (e in entries) {
            onItem(e.name)
            try {
                deleteRecursive(fileFor(context, e))
                remaining.removeAll { it.id == e.id }
                ok++
            } catch (ex: Exception) {
                failed += e.name
            }
        }
        writeIndex(context, remaining)
        return OpResult(ok, failed)
    }

    fun emptyAll(context: Context): OpResult {
        val all = list(context)
        val r = purge(context, all)
        try {
            itemsDir(context).delete()
            indexFile(context).delete()
        } catch (_: Exception) {
        }
        return r
    }

    private fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
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
}
