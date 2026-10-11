package com.phonepc.explorer.data

import android.content.Context
import android.os.Environment
import com.phonepc.explorer.R
import java.io.File

/**
 * The customizable shortcut list on the "此手机" page.
 *
 * The defaults are exactly the folders the navigation pane used to nest under
 * 此手机, so nothing disappears when they move here. The storage volumes are
 * listed separately and are fixed — they cannot be removed.
 */
object Shortcuts {

    /** virtual destinations that have no path on disk */
    const val RECYCLE = "::recycle"
    const val NETSHARE = "::netshare"

    data class Item(val path: String, val label: String)

    private const val PREF = "phonepc"
    private const val KEY = "shortcuts_v1"

    fun isVirtual(path: String): Boolean = path == RECYCLE || path == NETSHARE

    /** the folders pinned out of the box */
    fun defaults(context: Context): List<Item> =
        Locations.quickAccess(context).mapNotNull { row ->
            row.file?.let { Item(it.absolutePath, row.title) }
        }

    fun load(context: Context): List<Item> {
        val raw = prefs(context).getString(KEY, null) ?: return defaults(context)
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else Item(line.substring(0, i), line.substring(i + 1))
        }
    }

    fun save(context: Context, items: List<Item>) {
        prefs(context).edit()
            .putString(KEY, items.joinToString("\n") { "${it.path}\t${it.label}" })
            .apply()
    }

    fun labelOf(context: Context, path: String): String = when (path) {
        RECYCLE -> context.getString(R.string.nav_recyclebin)
        NETSHARE -> context.getString(R.string.nav_network)
        else -> File(path).name.ifEmpty { path }
    }

    /**
     * Everything the user may pin, so the picker can hide what is already there.
     * The six media folders plus the two virtual destinations.
     */
    fun candidates(context: Context): List<Item> = defaults(context) + listOf(
        Item(RECYCLE, context.getString(R.string.nav_recyclebin)),
        Item(NETSHARE, context.getString(R.string.nav_network))
    )

    /** create the entry when the path does not exist yet on disk */
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
