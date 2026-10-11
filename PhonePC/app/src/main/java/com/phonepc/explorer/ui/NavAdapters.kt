package com.phonepc.explorer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.phonepc.explorer.databinding.ItemCrumbBinding
import com.phonepc.explorer.databinding.ItemNavBinding
import com.phonepc.explorer.databinding.ItemNavHeaderBinding
import com.phonepc.explorer.model.Crumb
import com.phonepc.explorer.model.NavRow

/** The address-bar breadcrumb. */
class CrumbAdapter(private val onClick: (Crumb) -> Unit) :
    RecyclerView.Adapter<CrumbAdapter.VH>() {

    var items: List<Crumb> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val b: ItemCrumbBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCrumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val b = holder.b
        b.crumbIcon.setImageResource(c.iconRes)
        b.crumbText.text = c.label
        if (c.isLast) {
            b.crumbRoot.isSelected = true
            b.crumbChevron.visibility = View.GONE
            b.crumbRoot.setOnClickListener(null)
            b.crumbRoot.isClickable = false
        } else {
            b.crumbRoot.isSelected = false
            b.crumbChevron.visibility = View.VISIBLE
            b.crumbRoot.isClickable = true
            b.crumbRoot.setOnClickListener { onClick(c) }
        }
    }
}

/** The navigation pane. */
class NavAdapter(
    private val onClick: (NavRow.Item) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val TYPE_ITEM = 0
    private val TYPE_SECTION = 1

    var rows: List<NavRow> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    /** Absolute path of the folder currently shown, for the highlight. */
    var currentPath: String? = null
        set(v) { field = v; notifyDataSetChanged() }

    class ItemVH(val b: ItemNavBinding) : RecyclerView.ViewHolder(b.root)
    class SectionVH(val b: ItemNavHeaderBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is NavRow.Section) TYPE_SECTION else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionVH(ItemNavHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemVH(ItemNavBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is NavRow.Section -> {
                val b = (holder as SectionVH).b
                b.navText.text = row.title
                b.navArrow.visibility = View.VISIBLE
                b.navRoot.isClickable = false
            }
            is NavRow.Item -> {
                val b = (holder as ItemVH).b
                b.navIcon.setImageResource(row.iconRes)
                b.navText.text = row.title
                val path = row.file?.absolutePath
                b.navRoot.isActivated = path != null && path == currentPath
                b.navRoot.setOnClickListener { onClick(row) }
            }
        }
    }
}
