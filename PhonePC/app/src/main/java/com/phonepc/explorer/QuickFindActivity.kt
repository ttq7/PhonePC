package com.phonepc.explorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.phonepc.explorer.data.AppSettings
import com.phonepc.explorer.data.QuickFind
import com.phonepc.explorer.data.RemoteParser
import com.phonepc.explorer.data.Semantic
import com.phonepc.explorer.data.SemanticAnchors
import com.phonepc.explorer.data.ZipRepo
import com.phonepc.explorer.databinding.ActivityQuickfindBinding
import com.phonepc.explorer.model.FsEntry
import com.phonepc.explorer.ui.FileListAdapter
import com.phonepc.explorer.util.FileKinds
import java.util.concurrent.Executors

/**
 * Quick find: fuzzy name matching plus "which app produced this" lookup,
 * which is how people actually remember files on a phone.
 */
class QuickFindActivity : AppCompatActivity() {

    private lateinit var b: ActivityQuickfindBinding
    private lateinit var adapter: FileListAdapter

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val debounce = Runnable { runSearch() }

    private var indexing = false
    private var lastScanned = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityQuickfindBinding.inflate(layoutInflater)
        setContentView(b.root)
        com.phonepc.explorer.data.AppSettings.init(this)

        adapter = FileListAdapter(
            onClick = { open(it) },
            onLongClick = { showMenu(it); true }
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.btnBack.setOnClickListener { finish() }
        b.btnRescan.setOnClickListener { buildIndex(force = true) }

        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(debounce)
                main.postDelayed(debounce, 220)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, bb: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, bb: Int, c: Int) = Unit
        })

        b.searchInput.requestFocus()
        b.searchInput.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        // optional pre-filled query (also how the Chinese parser is regression-tested)
        intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }?.let {
            b.searchInput.setText(it)
            b.searchInput.setSelection(it.length)
        }

        if (QuickFind.size > 0 && !QuickFind.isStale()) {
            lastScanned = QuickFind.size
            b.loadingView.visibility = View.GONE
            b.statusText.text = getString(R.string.qf_indexed, lastScanned)
            showEmptyHint()
        } else {
            buildIndex(force = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(debounce)
        io.shutdown()
    }

    // ---- index ------------------------------------------------------------

    private fun buildIndex(force: Boolean) {
        if (indexing) return
        indexing = true
        b.loadingView.visibility = View.VISIBLE
        b.loadingText.text = getString(R.string.qf_scanning)
        io.execute {
            // load the static embedding table once; it only powers the fallback
            // for phrasings the rule parser does not know
            if (!Semantic.isReady) {
                try {
                    Semantic.load(this)
                    SemanticAnchors.init()
                } catch (e: Exception) {
                    // a missing/corrupt asset must not break plain search
                }
            }
            val n = try {
                if (force || QuickFind.size == 0 || QuickFind.isStale()) QuickFind.buildIndex()
                else QuickFind.size
            } catch (e: Exception) {
                0
            }
            main.post {
                indexing = false
                lastScanned = n
                b.loadingView.visibility = View.GONE
                b.statusText.text = getString(R.string.qf_indexed, n)
                runSearch()
            }
        }
    }

    // ---- search -----------------------------------------------------------

    private fun runSearch() {
        if (indexing) return
        val q = b.searchInput.text.toString()
        if (QuickFind.size == 0) { showEmptyHint(); return }
        io.execute {
            // optional cloud normalisation: the API only rewrites the phrasing,
        // the local parser still computes the time window and extensions
        var effective = q
        if (AppSettings.isApiReady(this) && q.any { it.code > 0x2E80 }) {
            val canon = RemoteParser.canonicalize(this, q)
            if (!canon.isNullOrBlank()) effective = canon
        }
        val r = QuickFind.search(effective)
            main.post {
                lastScanned = r.scanned
                val items = r.hits.map { entryOf(it) }
                adapter.items = items
                b.list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

                if (q.isBlank()) {
                    showEmptyHint()
                } else if (items.isEmpty()) {
                    b.emptyView.visibility = View.VISIBLE
                    b.emptyText.text = getString(R.string.qf_no_result)
                    b.statusText.text = getString(R.string.qf_result, 0, r.scanned)
                } else {
                    b.emptyView.visibility = View.GONE
                    b.statusText.text = if (r.matchedSource != null) {
                        getString(R.string.qf_result_source, items.size, r.matchedSource, r.scanned)
                    } else {
                        getString(R.string.qf_result, items.size, r.scanned)
                    }
                }
            }
        }
    }

    private fun showEmptyHint() {
        adapter.items = emptyList()
        b.list.visibility = View.GONE
        b.emptyView.visibility = View.VISIBLE
        b.emptyText.text = getString(R.string.qf_empty_hint)
    }

    private fun entryOf(h: QuickFind.Hit): FsEntry {
        val f = h.file
        return FsEntry(
            file = f,
            name = f.name,
            isDir = false,
            size = f.length(),
            lastModified = f.lastModified(),
            ext = f.name.substringAfterLast('.', ""),
            subtitle = f.parent
        )
    }

    // ---- actions ----------------------------------------------------------

    private fun open(e: FsEntry) {
        hideKeyboard()
        when {
            ZipRepo.isArchive(e.name) || ZipRepo.isApk(e.name) ->
                ArchiveActivity.open(this, e.path)

            FileKinds.of(e.name) == FileKinds.Kind.IMAGE ->
                startActivity(ViewerActivity.intentForImage(this, e.path, listOf(e.path), 0))

            FileKinds.of(e.name) != FileKinds.Kind.OTHER ->
                startActivity(ViewerActivity.intent(this, e.path))

            else -> if (!openViaSystem(e)) {
                startActivity(ViewerActivity.intent(this, e.path))
            }
        }
    }

    private fun openViaSystem(e: FsEntry): Boolean = try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", e.file
        )
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(e.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        true
    } catch (ex: Exception) {
        false
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun showMenu(e: FsEntry) {
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.ctx_open) to { open(e) },
            getString(R.string.ctx_open_with) to { if (!openViaSystem(e)) toast(getString(R.string.no_app_for_file)) },
            getString(R.string.qf_show_in_folder) to { showInFolder(e) },
            getString(R.string.ctx_copy_path) to { copyPath(e) }
        )
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(e.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showInFolder(e: FsEntry) {
        val parent = e.file.parent ?: return
        startActivity(MainActivity.intentAt(this, parent, e.path))
    }

    private fun copyPath(e: FsEntry) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", e.path))
        toast(e.path)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_QUERY = "q"

        fun intent(context: Context): Intent = Intent(context, QuickFindActivity::class.java)

        fun intent(context: Context, query: String): Intent =
            intent(context).putExtra(EXTRA_QUERY, query)
    }
}
