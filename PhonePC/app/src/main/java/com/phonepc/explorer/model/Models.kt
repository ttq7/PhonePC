package com.phonepc.explorer.model

import java.io.File

/** One row in an Explorer view: a file, a folder or a storage root. */
data class FsEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val childCount: Int = -1,
    val ext: String = "",
    val subtitle: String? = null,
    val isStorageRoot: Boolean = false,
    val usedBytes: Long = -1L,
    val totalBytes: Long = -1L,
    /** overrides the resolved icon (used by the virtual shortcut entries) */
    val iconRes: Int? = null
) {
    val path: String get() = file.absolutePath
}

enum class SortBy { NAME, DATE, TYPE, SIZE }

data class SortSpec(val by: SortBy = SortBy.NAME, val ascending: Boolean = true)

enum class ViewMode { XL_ICONS, LG_ICONS, MD_ICONS, SM_ICONS, TILES, LIST, DETAILS;
    val isGrid: Boolean get() = this == XL_ICONS || this == LG_ICONS || this == MD_ICONS ||
            this == SM_ICONS || this == TILES
}

/** A breadcrumb segment. [file] is null for the virtual device root. */
data class Crumb(
    val label: String,
    val file: File?,
    val iconRes: Int,
    val isLast: Boolean,
    val clickable: Boolean = true
)

/** A row in the navigation pane. */
sealed class NavRow {
    data class Section(val title: String) : NavRow()
    data class Item(
        val id: String,
        val title: String,
        val iconRes: Int,
        val file: File?,
        val isThisPc: Boolean = false
    ) : NavRow()
}
