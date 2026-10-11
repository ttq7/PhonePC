package com.phonepc.explorer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.phonepc.explorer.data.OpResult
import com.phonepc.explorer.data.TrashRepo
import com.phonepc.explorer.databinding.ActivityRecycleBinding
import com.phonepc.explorer.databinding.ViewCommandButtonBinding
import com.phonepc.explorer.model.FsEntry
import com.phonepc.explorer.ui.FileListAdapter
import com.phonepc.explorer.util.Fmt
import java.util.concurrent.Executors

/**
 * The recycle bin: everything deleted from the main window lands here and can
 * be restored, permanently deleted, or the whole bin emptied.
 */
class RecycleBinActivity : AppCompatActivity() {

    private lateinit var b: ActivityRecycleBinding
    private lateinit var adapter: FileListAdapter

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var entries: List<TrashRepo.Entry> = emptyList()
    private var items: List<FsEntry> = emptyList()
    private var entryOf: Map<FsEntry, TrashRepo.Entry> = emptyMap()
    private val selection = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRecycleBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = FileListAdapter(
            onClick = { onItemClick(it) },
            onLongClick = { toggleSelection(it); true }
        )
        adapter.compact = false
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.btnBack.setOnClickListener { finish() }

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---- data -------------------------------------------------------------

    private fun load() {
        b.loadingView.visibility = View.VISIBLE
        io.execute {
            val list = TrashRepo.list(this)
            val fs = list.map { e ->
                val f = TrashRepo.fileFor(this, e)
                FsEntry(
                    file = f,
                    name = e.name,
                    isDir = e.isDir,
                    size = if (e.isDir) 0L else f.length(),
                    lastModified = e.deletedAt,
                    ext = if (e.isDir) "" else e.name.substringAfterLast('.', ""),
                    subtitle = "原位置 ${e.originalPath.substringBeforeLast('/')}"
                )
            }
            main.post {
                entries = list
                items = fs
                entryOf = fs.indices.associate { fs[it] to list[it] }
                // drop stale selection keys
                selection.retainAll(fs.map { it.path }.toSet())
                b.loadingView.visibility = View.GONE
                applyView()
                updateChrome()
            }
        }
    }

    private fun applyView() {
        adapter.selected = selection.toSet()
        adapter.items = items
        b.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateChrome() {
        val total = items.filter { !it.isDir }.sumOf { it.size }
        b.statusText.text = if (selection.isEmpty()) {
            getString(R.string.recycle_count, items.size, Fmt.size(total))
        } else {
            getString(R.string.recycle_selected, selection.size)
        }
        buildCommandBar()
    }

    private fun onItemClick(e: FsEntry) {
        if (selection.isNotEmpty()) {
            toggleSelection(e)
        } else {
            toast(getString(R.string.recycle_hint))
        }
    }

    private fun toggleSelection(e: FsEntry) {
        if (!selection.add(e.path)) selection.remove(e.path)
        applyView()
        updateChrome()
    }

    private fun selectedEntries(): List<TrashRepo.Entry> =
        items.filter { selection.contains(it.path) }.mapNotNull { entryOf[it] }

    // ---- command bar ------------------------------------------------------

    private fun addCommand(label: String, iconRes: Int, action: () -> Unit) {
        val cb = ViewCommandButtonBinding.inflate(layoutInflater, b.commandContainer, false)
        cb.cmdIcon.setImageResource(iconRes)
        cb.cmdLabel.text = label
        cb.cmdArrow.visibility = View.GONE
        cb.cmdRoot.setOnClickListener { action() }
        b.commandContainer.addView(cb.root)
    }

    private fun buildCommandBar() {
        b.commandContainer.removeAllViews()
        if (selection.isEmpty()) {
            addCommand(getString(R.string.recycle_restore), R.drawable.g_history) {
                toast(getString(R.string.recycle_select_first))
            }
            addCommand(getString(R.string.recycle_empty_bin), R.drawable.g_delete) { confirmEmpty() }
            addCommand(getString(R.string.cmd_select_all), R.drawable.g_select_all) { selectAll() }
        } else {
            addCommand(getString(R.string.recycle_restore), R.drawable.g_history) { restoreSelected() }
            addCommand(getString(R.string.recycle_delete_forever), R.drawable.g_delete) { confirmPurge() }
            addCommand(getString(R.string.cmd_select_all), R.drawable.g_select_all) { selectAll() }
            addCommand(getString(R.string.cancel), R.drawable.g_close) {
                selection.clear(); applyView(); updateChrome()
            }
        }
    }

    private fun selectAll() {
        if (selection.size == items.size) selection.clear()
        else {
            selection.clear()
            items.forEach { selection.add(it.path) }
        }
        applyView()
        updateChrome()
    }

    // ---- actions ----------------------------------------------------------

    private fun restoreSelected() {
        val chosen = selectedEntries()
        if (chosen.isEmpty()) { toast(getString(R.string.recycle_select_first)); return }
        runOp(
            label = getString(R.string.recycle_restoring),
            work = { onItem -> TrashRepo.restore(this, chosen) { onItem(it) } },
            onDone = { r ->
                toast(
                    if (r.failed.isEmpty()) getString(R.string.recycle_restored, r.ok)
                    else getString(R.string.op_result_failed, r.ok, r.failed.size)
                )
            }
        )
    }

    private fun confirmPurge() {
        val chosen = selectedEntries()
        if (chosen.isEmpty()) { toast(getString(R.string.recycle_select_first)); return }
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.recycle_delete_forever)
            .setMessage(getString(R.string.recycle_purge_message, chosen.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runOp(
                    label = getString(R.string.op_deleting),
                    work = { onItem -> TrashRepo.purge(this, chosen) { onItem(it) } },
                    onDone = { r -> toast(getString(R.string.op_result, r.ok)) }
                )
            }
            .show()
    }

    private fun confirmEmpty() {
        if (items.isEmpty()) { toast(getString(R.string.recycle_empty)); return }
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.recycle_empty_bin)
            .setMessage(getString(R.string.recycle_purge_message, items.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                selection.clear()
                runOp(
                    label = getString(R.string.op_deleting),
                    work = { _ -> TrashRepo.emptyAll(this) },
                    onDone = { r -> toast(getString(R.string.recycle_emptied, r.ok)) }
                )
            }
            .show()
    }

    private fun runOp(
        label: String,
        work: (onItem: (String) -> Unit) -> OpResult,
        onDone: (OpResult) -> Unit
    ) {
        b.loadingText.text = label
        b.loadingView.visibility = View.VISIBLE
        io.execute {
            val r = try {
                work { name -> main.post { b.loadingText.text = "$label  $name" } }
            } catch (e: Exception) {
                OpResult(failed = listOf(e.message ?: e.javaClass.simpleName))
            }
            main.post {
                b.loadingView.visibility = View.GONE
                onDone(r)
                selection.clear()
                load()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context): Intent = Intent(context, RecycleBinActivity::class.java)
    }
}
