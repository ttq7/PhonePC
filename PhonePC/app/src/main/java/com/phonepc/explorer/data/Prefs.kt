package com.phonepc.explorer.data

import android.content.Context
import com.phonepc.explorer.model.SortBy
import com.phonepc.explorer.model.SortSpec
import com.phonepc.explorer.model.ViewMode

/** Remembers the view mode and sort order between launches. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("phonepc", Context.MODE_PRIVATE)

    var viewMode: ViewMode
        get() = runCatching { ViewMode.valueOf(sp.getString(KEY_VIEW, ViewMode.DETAILS.name)!!) }
            .getOrDefault(ViewMode.DETAILS)
        set(v) = sp.edit().putString(KEY_VIEW, v.name).apply()

    var sortSpec: SortSpec
        get() {
            val by = runCatching { SortBy.valueOf(sp.getString(KEY_SORT, SortBy.NAME.name)!!) }
                .getOrDefault(SortBy.NAME)
            return SortSpec(by, sp.getBoolean(KEY_SORT_ASC, true))
        }
        set(v) = sp.edit()
            .putString(KEY_SORT, v.by.name)
            .putBoolean(KEY_SORT_ASC, v.ascending)
            .apply()

    var lastPath: String?
        get() = sp.getString(KEY_PATH, null)
        set(v) = sp.edit().putString(KEY_PATH, v).apply()

    private companion object {
        const val KEY_VIEW = "view_mode"
        const val KEY_SORT = "sort_by"
        const val KEY_SORT_ASC = "sort_asc"
        const val KEY_PATH = "last_path"
    }
}
