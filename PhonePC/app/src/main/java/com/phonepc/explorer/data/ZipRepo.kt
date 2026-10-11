package com.phonepc.explorer.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Read-only browsing and extraction for ZIP-family archives (zip / apk / jar).
 *
 * Archives are opened as a virtual one-level tree: the caller asks for a
 * directory prefix inside the archive and gets back its immediate children.
 * Directory entries are synthesised from path prefixes, because many archives
 * (including most APKs) ship no explicit directory records.
 */
object ZipRepo {

    data class Item(
        val name: String,
        /** full path inside the archive; folders end with '/' */
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val compressed: Long,
        val time: Long
    )

    /** True when this file looks like something we can open as an archive. */
    fun isArchive(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase()) {
        "zip", "apk", "jar", "war", "ear", "aar", "xapk", "apks", "kra", "kmz", "cbz" -> true
        else -> false
    }

    fun isApk(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase()) {
        "apk", "xapk", "apks" -> true
        else -> false
    }

    private fun allEntries(zf: ZipFile): List<ZipEntry> {
        val out = ArrayList<ZipEntry>()
        val en = zf.entries()
        while (en.hasMoreElements()) out.add(en.nextElement())
        return out
    }

    /** Immediate children of [dir] ("" = archive root). */
    fun list(zipPath: String, dir: String): List<Item> {
        val out = LinkedHashMap<String, Item>()
        ZipFile(zipPath).use { zf ->
            for (e in allEntries(zf)) {
                val name = e.name.replace('\\', '/')
                if (name.isEmpty() || !name.startsWith(dir)) continue
                val rest = name.substring(dir.length)
                if (rest.isEmpty()) continue
                val slash = rest.indexOf('/')
                if (slash >= 0) {
                    val folder = rest.substring(0, slash)
                    if (folder.isEmpty()) continue
                    val full = dir + folder + "/"
                    if (!out.containsKey(full)) {
                        // newest timestamp wins for a synthetic folder
                        val existing = out[full]
                        val t = if (existing == null) e.time else maxOf(existing.time, e.time)
                        out[full] = Item(folder, full, true, 0L, 0L, t)
                    }
                } else if (!e.isDirectory) {
                    val full = dir + rest
                    if (!out.containsKey(full)) {
                        out[full] = Item(rest, full, false, e.size, e.compressedSize, e.time)
                    }
                }
            }
        }
        // folders first, then Windows-style natural order
        val order = Comparator<Item> { a, b ->
            if (a.isDir == b.isDir) FileRepo.naturalCompare(a.name, b.name)
            else if (a.isDir) -1 else 1
        }
        return out.values.sortedWith(order)
    }

    fun totalUncompressed(zipPath: String): Long {
        var total = 0L
        try {
            ZipFile(zipPath).use { zf ->
                for (e in allEntries(zf)) if (!e.isDirectory) total += e.size
            }
        } catch (_: Exception) {
        }
        return total
    }

    fun entryCount(zipPath: String): Int {
        var n = 0
        try {
            ZipFile(zipPath).use { zf -> n = allEntries(zf).count { !it.isDirectory } }
        } catch (_: Exception) {
        }
        return n
    }

    /**
     * Extract the chosen paths. Selecting a folder pulls in everything beneath
     * it. Entry names are sanitised so a hostile archive cannot escape
     * [destDir] with "..".
     */
    fun extract(
        zipPath: String,
        selectedPaths: List<String>,
        destDir: File,
        onItem: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): OpResult {
        var ok = 0
        val failed = mutableListOf<String>()
        var cancelled = false

        try {
            ZipFile(zipPath).use { zf ->
                val chosen = allEntries(zf).filter { e ->
                    val n = e.name.replace('\\', '/')
                    selectedPaths.any { s -> n == s || n.startsWith(s) }
                }
                for (e in chosen) {
                    if (isCancelled()) {
                        cancelled = true
                        break
                    }
                    val safe = e.name.replace('\\', '/')
                    if (safe.contains("..") || safe.startsWith("/")) {
                        failed += e.name
                        continue
                    }
                    if (e.isDirectory) {
                        File(destDir, safe).mkdirs()
                        continue
                    }
                    onItem(safe)
                    try {
                        val target = File(destDir, safe)
                        target.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input ->
                            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                        }
                        ok++
                    } catch (ex: Exception) {
                        failed += safe
                    }
                }
            }
        } catch (ex: Exception) {
            return OpResult(ok, failed + (ex.message ?: "无法打开压缩包"), cancelled)
        }
        return OpResult(ok, failed, cancelled)
    }

    /** Every non-directory path, used by "解压全部". */
    fun allFilePaths(zipPath: String): List<String> {
        val out = ArrayList<String>()
        try {
            ZipFile(zipPath).use { zf ->
                for (e in allEntries(zf)) if (!e.isDirectory) out.add(e.name.replace('\\', '/'))
            }
        } catch (_: Exception) {
        }
        return out
    }
}
