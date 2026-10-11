package com.phonepc.explorer.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.phonepc.explorer.R
import com.phonepc.explorer.model.Crumb
import com.phonepc.explorer.model.FsEntry
import com.phonepc.explorer.model.NavRow
import com.phonepc.explorer.util.Fmt
import java.io.File

/**
 * Discovers the storage volumes shown under the device root and the
 * quick-access folders shown in the navigation pane.
 */
object Locations {

    private val QUICK_ACCESS = listOf(
        QuickAccess("downloads", R.string.nav_downloads, R.drawable.ic_downloads, Environment.DIRECTORY_DOWNLOADS),
        QuickAccess("documents", R.string.nav_documents, R.drawable.ic_documents, Environment.DIRECTORY_DOCUMENTS),
        QuickAccess("pictures", R.string.nav_pictures, R.drawable.ic_pictures, Environment.DIRECTORY_PICTURES),
        QuickAccess("dcim", R.string.nav_camera, R.drawable.ic_pictures, Environment.DIRECTORY_DCIM),
        QuickAccess("music", R.string.nav_music, R.drawable.ic_music, Environment.DIRECTORY_MUSIC),
        QuickAccess("movies", R.string.nav_videos, R.drawable.ic_videos, Environment.DIRECTORY_MOVIES)
    )

    private data class QuickAccess(
        val id: String,
        val labelRes: Int,
        val iconRes: Int,
        val dirName: String
    )

    /** Volumes reachable from the This PC root. */
    fun storageRoots(context: Context): List<FsEntry> {
        val out = mutableListOf<FsEntry>()
        val seen = mutableSetOf<String>()

        fun add(dir: File, label: String) {
            if (!seen.add(dir.absolutePath)) return
            val stat = try { StatFs(dir.absolutePath) } catch (_: Exception) { null }
            val total = stat?.let { it.blockCountLong * it.blockSizeLong } ?: -1L
            val free = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: -1L
            val used = if (total > 0 && free >= 0) total - free else -1L
            out += FsEntry(
                file = dir,
                name = label,
                isDir = true,
                size = 0L,
                lastModified = dir.lastModified(),
                subtitle = if (total > 0) "已用 ${Fmt.size(used)}，共 ${Fmt.size(total)}" else null,
                isStorageRoot = true,
                usedBytes = used,
                totalBytes = total
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.forEach { vol ->
                val dir = vol.directory ?: return@forEach
                val label = when {
                    vol.isPrimary -> context.getString(R.string.nav_internal)
                    vol.isRemovable -> vol.getDescription(context)
                        ?.takeIf { it.isNotBlank() } ?: context.getString(R.string.nav_sdcard)
                    else -> vol.getDescription(context)?.takeIf { it.isNotBlank() } ?: dir.name
                }
                add(dir, label)
            }
        }
        if (out.isEmpty()) {
            add(Environment.getExternalStorageDirectory(), context.getString(R.string.nav_internal))
        }
        return out
    }

    /** Quick-access folders that actually exist on this device. */
    fun quickAccess(context: Context): List<NavRow.Item> {
        val root = Environment.getExternalStorageDirectory()
        return QUICK_ACCESS.mapNotNull { qa ->
            val dir = File(root, qa.dirName)
            if (!dir.isDirectory) null
            else NavRow.Item(qa.id, context.getString(qa.labelRes), qa.iconRes, dir)
        }
    }

    /**
     * Breadcrumb trail from the device root down to [dir]. Walking stops at the
     * first known storage root so we never expose /storage/emulated internals.
     */
    fun crumbsFor(dir: File, storageRootPaths: Set<String>, rootLabel: String): List<Crumb> {
        val chain = mutableListOf<File>()
        var cur: File? = dir
        while (cur != null) {
            chain.add(0, cur)
            if (storageRootPaths.contains(cur.absolutePath)) break
            val parent = cur.parentFile ?: break
            if (parent.absolutePath == cur.absolutePath) break
            if (!parent.canRead() && !storageRootPaths.contains(parent.absolutePath)) break
            cur = parent
        }
        val out = mutableListOf<Crumb>()
        out += Crumb(rootLabel, null, R.drawable.g_phone, false)
        chain.forEachIndexed { i, f ->
            out += Crumb(labelFor(f), f, navIconFor(f), i == chain.lastIndex)
        }
        return out
    }

    fun labelFor(f: File): String {
        val root = Environment.getExternalStorageDirectory()
        return if (f.absolutePath == root.absolutePath) "内部存储"
        else f.name.ifEmpty { f.absolutePath }
    }

    private fun navIconFor(f: File): Int = when (f.name.lowercase()) {
        "download", "downloads" -> R.drawable.ic_downloads
        "documents" -> R.drawable.ic_documents
        "pictures" -> R.drawable.ic_pictures
        "dcim" -> R.drawable.ic_pictures
        "music" -> R.drawable.ic_music
        "movies", "videos" -> R.drawable.ic_videos
        "desktop" -> R.drawable.ic_desktop
        else -> R.drawable.ic_folder
    }
}
