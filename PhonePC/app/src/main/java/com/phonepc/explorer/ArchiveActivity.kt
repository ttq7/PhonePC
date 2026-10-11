package com.phonepc.explorer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.phonepc.explorer.data.FileRepo
import com.phonepc.explorer.data.OpResult
import com.phonepc.explorer.data.ZipRepo
import com.phonepc.explorer.databinding.ActivityArchiveBinding
import com.phonepc.explorer.databinding.ItemCrumbBinding
import com.phonepc.explorer.databinding.ViewCommandButtonBinding
import com.phonepc.explorer.model.Crumb
import com.phonepc.explorer.model.FsEntry
import com.phonepc.explorer.ui.FileListAdapter
import com.phonepc.explorer.util.Fmt
import java.io.File
import java.util.concurrent.Executors

/**
 * Browses the inside of a ZIP-family archive (zip / apk / jar) without
 * unpacking it, and extracts the selection.
 *
 * Entries go through the same [FileListAdapter] and Win11 chrome as the main
 * window, so the interaction feels identical. A [FsEntry] carries a pseudo
 * File path (real zip path + inner path) purely as a unique key, which keeps
 * the shared adapter unchanged.
 */
class ArchiveActivity : AppCompatActivity() {

    private lateinit var b: ActivityArchiveBinding
    private lateinit var adapter: FileListAdapter

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var zipPath: String = ""
    private var innerDir: String = ""
    private var items: List<FsEntry> = emptyList()

    /** FsEntry -> the archive item it stands for (keeps the exact inner path). */
    private var innerItems: Map<FsEntry, ZipRepo.Item> = emptyMap()
    private val selection = linkedSetOf<String>()
    private var cancelFlag = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityArchiveBinding.inflate(layoutInflater)
        setContentView(b.root)

        zipPath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val zip = File(zipPath)
        if (zipPath.isEmpty() || !zip.isFile) {
            toast(getString(R.string.archive_open_failed))
            finish()
            return
        }

        b.titleText.text = zip.name

        adapter = FileListAdapter(
            onClick = { onItemClick(it) },
            onLongClick = { toggleSelection(it); true }
        )
        adapter.compact = false
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        b.btnBack.setOnClickListener { handleBack() }
        b.btnCancelOp.setOnClickListener {
            cancelFlag = true
            b.btnCancelOp.visibility = View.GONE
            b.loadingText.text = getString(R.string.op_cancelled)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        load("")
    }

    // ---- browsing ---------------------------------------------------------

    private fun load(dir: String) {
        innerDir = dir
        selection.clear()
        showLoading(getString(R.string.loading), false)
        io.execute {
            val list = try {
                ZipRepo.list(zipPath, dir)
            } catch (e: Exception) {
                emptyList()
            }
            val entries = list.mapIndexed { _, item -> item.toFsEntry() }
            main.post {
                innerItems = entries.indices.associate { entries[it] to list[it] }
                items = entries
                hideLoading()
                applyView()
                updateChrome()
            }
        }
    }

    private fun ZipRepo.Item.toFsEntry(): FsEntry {
        // Many archives carry no real timestamp (DOS epoch), which would show
        // up as a bogus 1981 date – hide it instead.
        val realTime = if (time > MIN_PLAUSIBLE_TIME) time else 0L
        return FsEntry(
            file = File(zipPath, path),   // unique key only, never touched as a real file
            name = name,
            isDir = isDir,
            size = size,
            lastModified = realTime,
            ext = if (isDir) "" else name.substringAfterLast('.', ""),
            subtitle = if (realTime > 0) Fmt.date(realTime) else null
        )
    }

    private fun applyView() {
        adapter.selected = selection.toSet()
        adapter.items = items
        b.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateChrome() {
        renderCrumbs()
        b.statusText.text = if (selection.isEmpty()) {
            getString(R.string.archive_item_count, items.size)
        } else {
            val bytes = items.filter { selection.contains(it.path) && !it.isDir }.sumOf { it.size }
            val extra = if (bytes > 0) "    ${Fmt.size(bytes)}" else ""
            getString(R.string.archive_selected, selection.size) + extra
        }
        buildCommandBar()
    }

    private fun renderCrumbs() {
        b.crumbContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // each crumb carries the exact inner directory it navigates to
        val crumbs = ArrayList<Pair<Crumb, String>>()
        crumbs += Crumb(File(zipPath).name, null, R.drawable.ic_file_archive, innerDir.isEmpty()) to ""

        if (innerDir.isNotEmpty()) {
            val parts = innerDir.trimEnd('/').split('/')
            var acc = ""
            parts.forEachIndexed { i, p ->
                acc += "$p/"
                crumbs += Crumb(p, null, R.drawable.ic_folder, i == parts.lastIndex) to acc
            }
        }

        for ((c, target) in crumbs) {
            val vb = ItemCrumbBinding.inflate(inflater, b.crumbContainer, false)
            vb.crumbIcon.setImageResource(c.iconRes)
            vb.crumbText.text = c.label
            if (c.isLast) {
                vb.crumbRoot.isSelected = true
                vb.crumbChevron.visibility = View.GONE
            } else {
                vb.crumbRoot.setOnClickListener { load(target) }
            }
            b.crumbContainer.addView(vb.root)
        }
        b.crumbScroll.post { b.crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun onItemClick(e: FsEntry) {
        if (selection.isNotEmpty()) {
            toggleSelection(e)
            return
        }
        if (e.isDir) {
            val inner = innerItems[e]?.path
            if (inner != null) load(inner)
        } else {
            toast(getString(R.string.archive_preview_unsupported))
        }
    }

    private fun toggleSelection(e: FsEntry) {
        if (!selection.add(e.path)) selection.remove(e.path)
        adapter.selected = selection.toSet()
        updateChrome()
    }

    private fun handleBack() {
        when {
            selection.isNotEmpty() -> {
                selection.clear(); applyView(); updateChrome()
            }
            innerDir.isNotEmpty() -> {
                val trimmed = innerDir.trimEnd('/')
                val idx = trimmed.lastIndexOf('/')
                load(if (idx < 0) "" else trimmed.substring(0, idx + 1))
            }
            else -> finish()
        }
    }

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
            addCommand(getString(R.string.archive_extract_all), R.drawable.g_save) { extractAll() }
            addCommand(getString(R.string.archive_extract_selected), R.drawable.g_download) {
                toast(getString(R.string.archive_select_first))
            }
            addCommand(getString(R.string.cmd_select_all), R.drawable.g_select_all) { selectAll() }
        } else {
            addCommand(getString(R.string.archive_extract_selected), R.drawable.g_download) { extractSelection() }
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

    // ---- extraction -------------------------------------------------------

    /** Extracted content lands in a sibling folder named after the archive. */
    private fun destDir(): File {
        val zip = File(zipPath)
        val base = zip.name.substringBeforeLast('.', zip.name)
        val parent = zip.parentFile ?: zip
        return File(parent, FileRepo.uniqueName(parent, base))
    }

    private fun extractAll() {
        val dest = destDir()
        runExtract(dest) { ZipRepo.allFilePaths(zipPath) }
    }

    private fun extractSelection() {
        val chosen = items.filter { selection.contains(it.path) }
            .mapNotNull { innerItems[it]?.path }
        if (chosen.isEmpty()) {
            toast(getString(R.string.archive_select_first))
            return
        }
        val dest = destDir()
        runExtract(dest) { chosen }
    }

    private fun runExtract(dest: File, pathsProvider: () -> List<String>) {
        showLoading(getString(R.string.archive_extracting), true)
        io.execute {
            val result: OpResult = try {
                ZipRepo.extract(
                    zipPath = zipPath,
                    selectedPaths = pathsProvider(),
                    destDir = dest,
                    onItem = { name -> main.post { b.loadingText.text = name } },
                    isCancelled = { cancelFlag }
                )
            } catch (e: Exception) {
                OpResult(failed = listOf(e.message ?: e.javaClass.simpleName))
            }
            main.post {
                hideLoading()
                selection.clear()
                applyView()
                updateChrome()
                toast(
                    when {
                        result.cancelled -> getString(R.string.op_cancelled)
                        result.failed.isNotEmpty() ->
                            getString(R.string.op_result_failed, result.ok, result.failed.size)
                        result.ok > 0 -> getString(R.string.archive_extracted, result.ok, dest.name)
                        else -> getString(R.string.op_result_none)
                    }
                )
            }
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun showLoading(text: String, cancellable: Boolean) {
        cancelFlag = false
        b.loadingText.text = text
        b.btnCancelOp.visibility = if (cancellable) View.VISIBLE else View.GONE
        b.loadingView.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        b.loadingView.visibility = View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_PATH = "archive_path"

        /** 1990-01-01 – anything older is a placeholder, not a real mtime. */
        private const val MIN_PLAUSIBLE_TIME = 631152000000L

        fun intent(context: Context, path: String): Intent =
            Intent(context, ArchiveActivity::class.java).putExtra(EXTRA_PATH, path)

        fun open(activity: Activity, path: String) {
            activity.startActivity(intent(activity, path))
        }
    }
}
