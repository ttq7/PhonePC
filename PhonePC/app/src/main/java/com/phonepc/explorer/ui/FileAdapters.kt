package com.phonepc.explorer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.phonepc.explorer.R
import com.phonepc.explorer.databinding.ItemFileGridBinding
import com.phonepc.explorer.databinding.ItemFileRowBinding
import com.phonepc.explorer.model.FsEntry
import com.phonepc.explorer.util.Fmt

private fun subtitleFor(e: FsEntry): String {
    val parts = mutableListOf<String>()
    if (!e.subtitle.isNullOrBlank()) parts += e.subtitle
    else {
        val d = Fmt.date(e.lastModified)
        if (d.isNotEmpty()) parts += d
    }
    parts += Fmt.typeName(e)
    return parts.joinToString("  ·  ")
}

/** Detailed / list / content style rows. */
class FileListAdapter(
    private val onClick: (FsEntry) -> Unit,
    private val onLongClick: (FsEntry) -> Boolean
) : RecyclerView.Adapter<FileListAdapter.VH>() {

    var items: List<FsEntry> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    var selected: Set<String> = emptySet()
        set(v) { field = v; notifyDataSetChanged() }

    /** "列表" mode: one compact line, no date/size columns. */
    var compact: Boolean = false
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val b: ItemFileRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val b = holder.b
        b.rowIcon.setImageResource(e.iconRes ?: IconResolver.forEntry(e))
        b.rowName.text = e.name

        if (compact) {
            b.rowSubtitle.visibility = View.GONE
            b.rowMeta.visibility = View.GONE
            b.rowRoot.layoutParams.height = b.rowRoot.resources.getDimensionPixelSize(R.dimen.row_height_compact)
        } else {
            b.rowRoot.layoutParams.height = b.rowRoot.resources.getDimensionPixelSize(R.dimen.row_height_list)
            b.rowSubtitle.visibility = View.VISIBLE
            b.rowSubtitle.text = subtitleFor(e)
            val meta = if (e.isDir) "" else Fmt.size(e.size)
            b.rowMeta.text = meta
            b.rowMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        }

        val isSel = selected.contains(e.path)
        b.rowRoot.isActivated = isSel
        b.rowCheck.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        b.rowCheck.alpha = if (isSel) 1f else 0.3f
        b.rowRoot.setOnClickListener { onClick(e) }
        b.rowRoot.setOnLongClickListener { onLongClick(e) }
    }
}

/** Icon / tile style cells. */
class FileGridAdapter(
    private val onClick: (FsEntry) -> Unit,
    private val onLongClick: (FsEntry) -> Boolean,
    private val thumbs: ThumbnailLoader? = null
) : RecyclerView.Adapter<FileGridAdapter.VH>() {

    var items: List<FsEntry> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    var selected: Set<String> = emptySet()
        set(v) { field = v; notifyDataSetChanged() }

    var iconPx: Int = 0
        set(v) { field = v; notifyDataSetChanged() }

    var nameTextSize: Float = 12f
        set(v) { field = v; notifyDataSetChanged() }

    /** Real thumbnails only make sense at the larger icon sizes. */
    var useThumbnails: Boolean = true
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val b: ItemFileGridBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFileGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val b = holder.b
        if (iconPx > 0) {
            b.gridIcon.layoutParams = b.gridIcon.layoutParams.apply {
                width = iconPx
                height = iconPx
            }
        }
        b.gridIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        b.gridIcon.setPadding(0, 0, 0, 0)

        val shellIcon = e.iconRes ?: IconResolver.forEntry(e)
        if (thumbs != null && useThumbnails) {
            val box = if (iconPx > 0) iconPx else b.gridIcon.layoutParams.width
            thumbs.into(b.gridIcon, e, box, shellIcon)
        } else {
            b.gridIcon.tag = null
            b.gridIcon.setImageResource(shellIcon)
        }

        b.gridName.text = e.name
        b.gridName.textSize = nameTextSize

        val isSel = selected.contains(e.path)
        b.gridRoot.isActivated = isSel
        b.gridRoot.setOnClickListener { onClick(e) }
        b.gridRoot.setOnLongClickListener { onLongClick(e) }
    }
}
