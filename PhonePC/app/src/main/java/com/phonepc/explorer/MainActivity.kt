package com.phonepc.explorer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.phonepc.explorer.data.ApkInspector
import com.phonepc.explorer.data.ConflictPolicy
import com.phonepc.explorer.data.FileRepo
import com.phonepc.explorer.data.Locations
import com.phonepc.explorer.data.OpResult
import com.phonepc.explorer.data.Prefs
import com.phonepc.explorer.data.Shortcuts
import com.phonepc.explorer.data.TrashRepo
import com.phonepc.explorer.data.ZipRepo
import com.phonepc.explorer.databinding.ActivityMainBinding
import com.phonepc.explorer.databinding.DialogApkInfoBinding
import com.phonepc.explorer.databinding.DialogBatchRenameBinding
import com.phonepc.explorer.databinding.DialogInputBinding
import com.phonepc.explorer.databinding.DialogNewFileBinding
import com.phonepc.explorer.databinding.DialogPropertiesBinding
import com.phonepc.explorer.databinding.ItemCrumbBinding
import com.phonepc.explorer.databinding.ItemTabBinding
import com.phonepc.explorer.databinding.RowPropertyBinding
import com.phonepc.explorer.databinding.ViewCommandButtonBinding
import com.phonepc.explorer.model.Crumb
import com.phonepc.explorer.model.FsEntry
import com.phonepc.explorer.model.NavRow
import com.phonepc.explorer.model.SortBy
import com.phonepc.explorer.model.SortSpec
import com.phonepc.explorer.model.ViewMode
import com.phonepc.explorer.ui.FileGridAdapter
import com.phonepc.explorer.ui.FileListAdapter
import com.phonepc.explorer.ui.IconResolver
import com.phonepc.explorer.ui.NavAdapter
import com.phonepc.explorer.ui.ThumbnailLoader
import com.phonepc.explorer.util.FileKinds
import com.phonepc.explorer.util.Fmt
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private lateinit var listAdapter: FileListAdapter
    private lateinit var gridAdapter: FileGridAdapter
    private lateinit var navAdapter: NavAdapter
    private val thumbs = ThumbnailLoader()

    private val io = Executors.newSingleThreadExecutor()

    /** Hashing must never queue behind directory listing. */
    private val hashIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val searchDebounce = Runnable { applyFilter() }

    // ---- state ------------------------------------------------------------
    private var currentPath: String? = null            // null == the virtual device root (labelled 此电脑 in Windows terms)
    private var allEntries: List<FsEntry> = emptyList()
    private var entries: List<FsEntry> = emptyList()
    private var storageRoots: List<FsEntry> = emptyList()
    private var storageRootPaths: Set<String> = emptySet()
    private val selection = linkedSetOf<String>()
    private val history = ArrayList<String?>()
    private var historyIndex = -1

    /** One tab in the Win11-style tab strip; each keeps its own location and history. */
    private class TabState(var path: String?, var index: Int, val history: ArrayList<String?>)

    private val tabs = mutableListOf<TabState>()
    private var activeTab = 0
    private var sortSpec = SortSpec()
    private var viewMode = ViewMode.DETAILS
    private var clipboard: List<File> = emptyList()
    private var clipboardCut = false
    private var searchQuery = ""
    private var cancelFlag = false
    private var accessAtStart = false

    /** true while the address bar shows the editable path box instead of crumbs. */
    private var editingPath = false

    /** a path typed into the address bar that should be selected once listed. */
    private var pendingSelect: String? = null

    /** true when the current folder could not be listed at all (restricted). */
    private var unreadable = false

    /** entries created by the most recent "移到回收站", for the undo strip */
    private var lastTrashed: List<TrashRepo.Entry> = emptyList()
    private var trashCount = 0
    private val undoTimer = Runnable { hideUndo() }

    // ---- lifecycle --------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        sortSpec = prefs.sortSpec
        viewMode = prefs.viewMode
        accessAtStart = hasStorageAccess()

        setUpAdapters()
        setUpChrome()
        setUpSearch()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        tabs += TabState(null, -1, ArrayList())
        pendingSelect = intent.getStringExtra(EXTRA_SELECT)
        navigateTo(intent.getStringExtra(EXTRA_OPEN_PATH) ?: initialPath(), record = true)
        if (!accessAtStart) ensureAccess()
    }

    override fun onResume() {
        super.onResume()
        if (!accessAtStart && hasStorageAccess()) {
            accessAtStart = true
            navigateTo(currentPath, record = false)
        }
        refreshTrashCount()
    }

    private fun initialPath(): String? {
        val saved = prefs.lastPath
        if (saved != null) {
            val f = File(saved)
            if (f.isDirectory && f.canRead()) return saved
        }
        return null // This PC
    }

    // ---- wiring -----------------------------------------------------------

    private fun setUpAdapters() {
        listAdapter = FileListAdapter(
            onClick = { onEntryClick(it) },
            onLongClick = { onEntryLongClick(it); true }
        )
        gridAdapter = FileGridAdapter(
            onClick = { onEntryClick(it) },
            onLongClick = { onEntryLongClick(it); true },
            thumbs = thumbs
        )
        navAdapter = NavAdapter { item -> onNavClick(item) }

        b.fileList.layoutManager = LinearLayoutManager(this)
        b.fileList.adapter = listAdapter
        b.fileList.itemAnimator = null
        b.navList.layoutManager = LinearLayoutManager(this)
        b.navList.adapter = navAdapter
    }

    private fun drawerView(): View = b.navList.parent as View

    private fun setUpChrome() {
        b.btnMenu.setOnClickListener { b.drawerLayout.openDrawer(drawerView()) }
        b.btnBack.setOnClickListener { goBack() }
        b.btnForward.setOnClickListener { goForward() }
        b.btnUp.setOnClickListener { goUp() }
        b.btnRefresh.setOnClickListener { reload() }
        b.btnNewTab.setOnClickListener { addTab(currentPath) }
        b.tabSpinner.setOnClickListener { showViewMenu(it) }
        b.btnOverflow.setOnClickListener { showOverflowMenu() }
        b.undoAction.setOnClickListener { undoTrash() }
        // tapping the blank part of the address bar switches it to an editable path
        b.addressBox.setOnClickListener { enterPathEdit() }
        // the scroll view swallows taps aimed at the FrameLayout, so the row itself
        // is the real click target (individual crumbs consume their own taps)
        b.crumbContainer.setOnClickListener { enterPathEdit() }
        b.crumbContainer.setOnLongClickListener { enterPathEdit(); true }
        b.pathInput.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            confirmPath()
            true
        }
        b.statusDetails.setOnClickListener { setViewMode(ViewMode.DETAILS) }
        b.statusIcons.setOnClickListener {
            setViewMode(if (viewMode.isGrid) ViewMode.DETAILS else ViewMode.LG_ICONS)
        }
        b.btnCancelOp.setOnClickListener {
            cancelFlag = true
            b.btnCancelOp.visibility = View.GONE
            b.loadingText.text = "正在取消…"
        }
    }

    private fun setUpSearch() {
        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                b.btnClearSearch.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                main.removeCallbacks(searchDebounce)
                main.postDelayed(searchDebounce, 200)
            }
        })
        b.btnClearSearch.setOnClickListener { b.searchInput.setText("") }
        b.searchInput.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            runRecursiveSearch()
            true
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
    }

    // ---- navigation -------------------------------------------------------

    private fun navigateTo(path: String?, record: Boolean = true) {
        exitPathEdit()
        if (record) {
            while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
            if (historyIndex < 0 || history.getOrNull(historyIndex) != path) {
                history.add(path)
                historyIndex = history.size - 1
            }
        }
        currentPath = path
        if (activeTab in tabs.indices) {
            val t = tabs[activeTab]
            t.path = path
            t.index = historyIndex
            t.history.clear()
            t.history.addAll(history)
        }
        selection.clear()
        searchQuery = ""
        b.searchInput.setText("")
        prefs.lastPath = path
        reload()
    }

    // ---- tabs -------------------------------------------------------------

    private fun addTab(path: String?) {
        tabs += TabState(path, -1, ArrayList())
        if (path != null) {
            tabs.last().history.add(path)
            tabs.last().index = 0
        }
        loadTab(tabs.size - 1)
    }

    private fun loadTab(i: Int) {
        if (i !in tabs.indices) return
        activeTab = i
        val t = tabs[i]
        currentPath = t.path
        historyIndex = t.index
        history.clear()
        history.addAll(t.history)
        selection.clear()
        searchQuery = ""
        b.searchInput.setText("")
        prefs.lastPath = currentPath
        renderTabs()
        reload()
    }

    private fun closeTab(i: Int) {
        if (tabs.size <= 1) {
            toast(getString(R.string.tab_last))
            return
        }
        val wasActive = i == activeTab
        tabs.removeAt(i)
        val target = when {
            i < activeTab -> activeTab - 1
            wasActive -> minOf(i, tabs.size - 1)
            else -> activeTab
        }
        loadTab(target)
    }

    private fun renderTabs() {
        b.tabStrip.removeAllViews()
        val inflater = LayoutInflater.from(this)
        tabs.forEachIndexed { i, t ->
            val vb = ItemTabBinding.inflate(inflater, b.tabStrip, false)
            val p = t.path
            vb.tabText.text = pathLabel(p)
            vb.tabIcon.setImageResource(
                when (p) {
                    null -> R.drawable.g_phone
                    HOME_PATH -> R.drawable.g_home
                    else -> IconResolver.forFolder(File(p).name)
                }
            )
            vb.tabRoot.isSelected = i == activeTab
            vb.tabRoot.setOnClickListener { if (i != activeTab) loadTab(i) }
            if (tabs.size > 1) {
                vb.tabClose.visibility = View.VISIBLE
                vb.tabClose.contentDescription = getString(R.string.tab_close)
                vb.tabClose.setOnClickListener { closeTab(i) }
            } else {
                vb.tabClose.visibility = View.GONE
            }
            b.tabStrip.addView(vb.root)
        }
        b.tabScroll.post { b.tabScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun goBack() {
        if (historyIndex > 0) {
            historyIndex--
            navigateTo(history[historyIndex], record = false)
        } else {
            finish()
        }
    }

    private fun goForward() {
        if (historyIndex < history.size - 1) {
            historyIndex++
            navigateTo(history[historyIndex], record = false)
        }
    }

    private fun goUp() {
        val path = currentPath ?: return
        if (path == HOME_PATH) {
            navigateTo(null)
            return
        }
        val dir = File(path)
        if (storageRootPaths.contains(dir.absolutePath)) navigateTo(null)
        else dir.parentFile?.let { navigateTo(it.absolutePath) }
    }

    private fun handleBack() {
        when {
            b.drawerLayout.isDrawerOpen(drawerView()) -> b.drawerLayout.closeDrawers()
            editingPath -> exitPathEdit()
            selection.isNotEmpty() -> clearSelection()
            searchQuery.isNotEmpty() -> b.searchInput.setText("")
            historyIndex > 0 -> goBack()
            // launched straight into a folder (restored session): go up first
            currentPath != null -> goUp()
            else -> finish()
        }
    }

    private fun onNavClick(item: NavRow.Item) {
        b.drawerLayout.closeDrawers()
        when {
            item.id == "home" -> navigateTo(HOME_PATH)
            item.id == "thispc" -> navigateTo(null)
            item.id == "quickfind" -> startActivity(QuickFindActivity.intent(this))
            item.id == "network" -> startActivity(
                NetShareActivity.intent(this, Environment.getExternalStorageDirectory().absolutePath)
            )
            item.id == "recycle" -> startActivity(RecycleBinActivity.intent(this))
            item.file != null -> navigateTo(item.file.absolutePath)
            else -> toast("此项目暂不可用")
        }
    }

    // ---- loading ----------------------------------------------------------

    private fun refreshStorageRoots() {
        storageRoots = Locations.storageRoots(this)
        storageRootPaths = storageRoots.map { it.path }.toSet()
    }

    private fun reload() {
        refreshStorageRoots()
        showLoading(getString(R.string.loading))
        val path = currentPath
        val roots = storageRoots
        val spec = sortSpec
        io.execute {
            val listed: List<FsEntry>? = when {
                path == null -> thisPcEntries()
                path == HOME_PATH -> homeEntries()
                else -> FileRepo.readDir(File(path), spec)
            }
            main.post {
                unreadable = listed == null
                allEntries = listed ?: emptyList()
                applyFilter()
                hideLoading()
                // a path typed into the address bar may point at a file: select it
                pendingSelect?.let { wanted ->
                    pendingSelect = null
                    val hit = allEntries.firstOrNull { it.path == wanted }
                    if (hit != null) {
                        selection.clear()
                        selection.add(hit.path)
                        refreshSelection()
                        updateChrome()
                        val idx = entries.indexOfFirst { it.path == wanted }
                        if (idx >= 0) b.fileList.scrollToPosition(idx)
                    }
                }
            }
        }
    }

    /**
     * The "此手机" page: the storage volumes first (fixed), then the user's
     * shortcut list, then a trailing tile that opens the pin picker.
     */
    private fun thisPcEntries(): List<FsEntry> {
        val out = mutableListOf<FsEntry>()
        out += storageRoots
        Shortcuts.load(this).forEach { s ->
            val virtual = Shortcuts.isVirtual(s.path)
            val f = File(s.path)
            if (!virtual && !f.exists()) return@forEach
            out += FsEntry(
                file = f,
                name = s.label,
                isDir = virtual || f.isDirectory,
                size = if (virtual || f.isDirectory) 0L else f.length(),
                lastModified = if (virtual) 0L else f.lastModified(),
                ext = "",
                subtitle = if (virtual) getString(R.string.shortcut_virtual) else s.path
            )
        }
        out += FsEntry(
            file = File(ADD_PATH),
            name = getString(R.string.shortcut_add),
            isDir = true,
            size = 0L,
            lastModified = 0L,
            ext = "",
            subtitle = getString(R.string.shortcut_add_hint),
            iconRes = R.drawable.ic_shortcut_add
        )
        return out
    }

    private fun showAddShortcut() {
        val pinned = Shortcuts.load(this).map { it.path }.toSet()
        val options = Shortcuts.candidates(this).filter { it.path !in pinned }
        val labels = options.map { it.label } + getString(R.string.shortcut_add_custom)
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.shortcut_add)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < options.size) {
                    val picked = options[which]
                    Shortcuts.save(this, Shortcuts.load(this) + picked)
                    reload()
                } else {
                    promptCustomShortcut()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** pin any path by typing it, reusing the address-bar path editor */
    private fun promptCustomShortcut() {
        val vb = DialogInputBinding.inflate(layoutInflater)
        vb.inputField.hint = getString(R.string.path_hint)
        vb.inputField.setText("")
        val dialog = AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.shortcut_add)
            .setView(vb.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = vb.inputField.text.toString().trim()
            val base = Environment.getExternalStorageDirectory()
            val f = try {
                val t = if (raw == "~" || raw.startsWith("~/")) raw.replaceFirst("~", base.absolutePath) else raw
                (if (File(t).isAbsolute) File(t) else File(base, t)).canonicalFile
            } catch (e: Exception) { File(raw) }
            if (!f.exists()) {
                toast(getString(R.string.path_not_found, raw))
            } else {
                Shortcuts.save(
                    this,
                    Shortcuts.load(this) + Shortcuts.Item(f.absolutePath, f.name.ifEmpty { f.absolutePath })
                )
                dialog.dismiss()
                reload()
            }
        }
    }

    private fun confirmRemoveShortcut(e: FsEntry) {
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.shortcut_remove)
            .setMessage(getString(R.string.shortcut_remove_message, e.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.shortcut_remove) { _, _ ->
                Shortcuts.save(this, Shortcuts.load(this).filterNot { it.path == e.file.path })
                reload()
            }
            .show()
    }

    /** Windows 11's Home: quick-access folders first, then recently touched files. */
    private fun homeEntries(): List<FsEntry> {
        val out = mutableListOf<FsEntry>()
        Locations.quickAccess(this).forEach { item ->
            val f = item.file ?: return@forEach
            out += FsEntry(
                file = f,
                name = item.title,
                isDir = true,
                size = 0L,
                lastModified = f.lastModified(),
                ext = "",
                subtitle = getString(R.string.home_quick)
            )
        }
        out += recentFiles(20).map { it.copy(subtitle = getString(R.string.home_recent)) }
        return out
    }

    private fun recentFiles(limit: Int): List<FsEntry> {
        val root = Environment.getExternalStorageDirectory()
        val seeds = listOf(
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES
        ).map { File(root, it) }.filter { it.isDirectory }

        val acc = ArrayList<FsEntry>()
        val stack = ArrayDeque<Pair<File, Int>>()
        seeds.forEach { stack.addLast(it to 0) }
        while (stack.isNotEmpty() && acc.size < 400) {
            val (dir, depth) = stack.removeLast()
            dir.listFiles()?.forEach { f ->
                val hidden = f.name.startsWith(".")
                if (f.isDirectory) {
                    if (depth < 2 && !hidden) stack.addLast(f to depth + 1)
                } else if (!hidden) {
                    acc += FsEntry(
                        file = f,
                        name = f.name,
                        isDir = false,
                        size = f.length(),
                        lastModified = f.lastModified(),
                        ext = f.name.substringAfterLast('.', "")
                    )
                }
            }
        }
        return acc.sortedByDescending { it.lastModified }.take(limit)
    }

    private fun pathLabel(path: String?): String = when (path) {
        null -> getString(R.string.nav_thispc)
        HOME_PATH -> getString(R.string.nav_home)
        else -> locationsLabel(path)
    }

    private fun runRecursiveSearch() {
        val q = searchQuery.trim()
        val base = currentPath
        if (q.isEmpty()) { applyFilter(); return }
        if (base == null) { toast("请先进入一个存储设备再搜索"); return }
        showLoading("正在搜索…")
        val spec = sortSpec
        io.execute {
            val result = FileRepo.search(File(base), q, spec)
            main.post {
                allEntries = result
                entries = result
                hideLoading()
                applyView()
                updateChrome()
            }
        }
    }

    private fun applyFilter() {
        val q = searchQuery.trim()
        entries = if (q.isEmpty()) allEntries
        else allEntries.filter { it.name.contains(q, ignoreCase = true) }
        applyView()
        updateChrome()
    }

    private fun applyView() {
        val grid = viewMode.isGrid
        b.fileList.layoutManager =
            if (grid) GridLayoutManager(this, spanFor(viewMode)) else LinearLayoutManager(this)

        listAdapter.compact = viewMode == ViewMode.LIST
        listAdapter.selected = selection.toSet()
        gridAdapter.selected = selection.toSet()
        gridAdapter.iconPx = dp(iconDpFor(viewMode))
        gridAdapter.nameTextSize = if (viewMode == ViewMode.TILES) 13f else 11.5f
        // a 30dp thumbnail is not worth decoding
        gridAdapter.useThumbnails = viewMode != ViewMode.SM_ICONS
        listAdapter.items = entries
        gridAdapter.items = entries

        b.fileList.adapter = if (grid) gridAdapter else listAdapter
        b.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        b.emptyText.text = when {
            searchQuery.isNotBlank() -> "没有找到匹配的项目"
            unreadable -> getString(R.string.folder_unreadable)
            else -> getString(R.string.empty_folder)
        }
    }

    private fun refreshSelection() {
        listAdapter.selected = selection.toSet()
        gridAdapter.selected = selection.toSet()
    }

    private fun updateChrome() {
        // ---- breadcrumb ----
        val rootLabel = getString(R.string.nav_thispc)
        val crumbs: List<Crumb> = when {
            currentPath == null -> listOf(Crumb(rootLabel, null, R.drawable.g_phone, true))
            currentPath == HOME_PATH ->
                listOf(Crumb(getString(R.string.nav_home), null, R.drawable.g_home, true))

            else -> Locations.crumbsFor(File(currentPath!!), storageRootPaths, rootLabel).map { c ->
                // storage volumes show their friendly label ("内部存储", "SD 卡"), not the raw folder name
                val p = c.file?.absolutePath
                val friendly = if (p != null) storageRoots.firstOrNull { it.path == p }?.name else null
                if (friendly != null) c.copy(label = friendly) else c
            }
        }
        b.crumbContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (c in collapseCrumbs(crumbs)) {
            val vb = ItemCrumbBinding.inflate(inflater, b.crumbContainer, false)
            if (c.iconRes == 0) vb.crumbIcon.visibility = View.GONE
            else vb.crumbIcon.setImageResource(c.iconRes)
            vb.crumbText.text = c.label
            when {
                c.isLast -> {
                    vb.crumbRoot.isSelected = true
                    vb.crumbChevron.visibility = View.GONE
                    // tapping the current folder's crumb edits the path, like Explorer
                    vb.crumbRoot.setOnClickListener { enterPathEdit() }
                }
                !c.clickable -> {
                    vb.crumbChevron.visibility = View.GONE
                    vb.crumbRoot.isClickable = false
                }
                else -> vb.crumbRoot.setOnClickListener {
                    if (c.file == null) navigateTo(null) else navigateTo(c.file!!.absolutePath)
                }
            }
            b.crumbContainer.addView(vb.root)
        }
        b.crumbScroll.post { b.crumbScroll.fullScroll(View.FOCUS_RIGHT) }

        // ---- tabs ----
        renderTabs()

        // ---- status bar ----
        b.statusText.text = when {
            selection.isNotEmpty() -> {
                val bytes = entries.filter { selection.contains(it.path) && !it.isDir }.sumOf { it.size }
                val extra = if (bytes > 0) "    ${Fmt.size(bytes)}" else ""
                "已选择 ${selection.size} 个项目$extra"
            }
            currentPath == null -> "${entries.size} 个驱动器"
            currentPath == HOME_PATH ->
                getString(
                    R.string.home_count,
                    entries.count { it.isDir },
                    entries.count { !it.isDir }
                )

            searchQuery.isNotBlank() -> "「$searchQuery」的搜索结果：${entries.size} 个项目"
            else -> "${entries.size} 个项目"
        }

        // ---- navigation pane ----
        navAdapter.currentPath = currentPath
        navAdapter.rows = buildNavRows()

        buildCommandBar()
    }

    // ---- address bar: type a path directly --------------------------------

    /**
     * Windows lets you click the blank part of the address bar and type a path.
     * Accepts an absolute path, one relative to the current folder, a leading
     * `~` for internal storage, and also tolerates a file path by opening its
     * parent folder and selecting the file.
     */
    private fun enterPathEdit() {
        if (editingPath) return
        editingPath = true
        b.pathInput.setText(currentPath ?: "")
        b.pathInput.setSelection(b.pathInput.text.length)
        b.crumbScroll.visibility = View.GONE
        b.pathInput.visibility = View.VISIBLE
        b.pathInput.requestFocus()
        b.pathInput.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(b.pathInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun exitPathEdit() {
        if (!editingPath) return
        editingPath = false
        b.pathInput.visibility = View.GONE
        b.crumbScroll.visibility = View.VISIBLE
        hideKeyboard()
    }

    private fun confirmPath() {
        val raw = b.pathInput.text.toString().trim()
        if (raw.isEmpty()) {
            exitPathEdit()
            navigateTo(null)
            return
        }
        val base = currentPath?.takeIf { File(it).isDirectory }?.let { File(it) }
            ?: Environment.getExternalStorageDirectory()
        val expanded = if (raw == "~" || raw.startsWith("~/")) {
            raw.replaceFirst("~", Environment.getExternalStorageDirectory().absolutePath)
        } else raw

        val candidate = try {
            val f = if (File(expanded).isAbsolute) File(expanded) else File(base, expanded)
            f.canonicalFile
        } catch (e: Exception) {
            File(expanded)
        }

        when {
            candidate.isDirectory -> {
                exitPathEdit()
                navigateTo(candidate.absolutePath)
            }
            candidate.isFile -> {
                // open the containing folder and select the file, like Explorer
                val parent = candidate.parentFile?.absolutePath
                exitPathEdit()
                if (parent != null) {
                    pendingSelect = candidate.absolutePath
                    navigateTo(parent)
                }
            }
            else -> {
                toast(getString(R.string.path_not_found, raw))
                b.pathInput.setSelection(0, b.pathInput.text.length)
            }
        }
    }

    private fun locationsLabel(path: String): String {
        if (storageRootPaths.contains(path)) {
            storageRoots.firstOrNull { it.path == path }?.name?.let { return it }
        }
        return File(path).name.ifEmpty { path }
    }

    /**
     * A deep trail never fits a phone-width address bar, so keep the root and
     * the current folder and elide the middle – the same thing Explorer does
     * when the window is narrow.
     */
    private fun collapseCrumbs(crumbs: List<Crumb>): List<Crumb> {
        if (crumbs.size <= 3) return crumbs
        return listOf(
            crumbs.first(),
            Crumb("…", null, 0, false, clickable = false),
            crumbs.last()
        )
    }

    private fun buildNavRows(): List<NavRow> {
        val rows = mutableListOf<NavRow>()
        val pictures = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_PICTURES)
        rows += NavRow.Item("home", getString(R.string.nav_home), R.drawable.g_home, null)
        if (pictures.isDirectory) {
            rows += NavRow.Item("gallery", getString(R.string.nav_gallery), R.drawable.ic_gallery, pictures)
        }
        // the folders that used to nest here now live on the 此手机 page as
        // pin-able shortcuts, so the pane no longer needs a collapsible group
        rows += NavRow.Item("thispc", getString(R.string.nav_thispc), R.drawable.g_phone, null)
        storageRoots.forEach { root ->
            rows += NavRow.Item("drive:" + root.path, root.name, R.drawable.ic_drive, root.file)
        }
        rows += NavRow.Item("quickfind", getString(R.string.nav_quickfind), R.drawable.g_search, null)
        rows += NavRow.Item("network", getString(R.string.nav_network), R.drawable.ic_network, null)
        rows += NavRow.Item(
            "recycle",
            if (trashCount > 0) "${getString(R.string.nav_recyclebin)}  ($trashCount)"
            else getString(R.string.nav_recyclebin),
            R.drawable.ic_recyclebin,
            null
        )
        return rows
    }

    // ---- command bar ------------------------------------------------------

    private data class Cmd(
        val label: String,
        val iconRes: Int,
        val arrow: Boolean,
        val action: (View) -> Unit
    )

    /** Commands that did not fit on the bar; they live in the ⋯ menu instead. */
    private var overflowCommands: List<Cmd> = emptyList()

    private fun buildCommandView(cmd: Cmd): View {
        val cb = ViewCommandButtonBinding.inflate(layoutInflater, b.commandContainer, false)
        cb.cmdIcon.setImageResource(cmd.iconRes)
        cb.cmdLabel.text = cmd.label
        cb.cmdArrow.visibility = if (cmd.arrow) View.VISIBLE else View.GONE
        cb.cmdRoot.setOnClickListener { v -> cmd.action(v) }
        return cb.root
    }

    private fun normalCommands(): List<Cmd> = listOf(
        Cmd(getString(R.string.cmd_new), R.drawable.g_add, true) { showNewMenu(it) },
        Cmd(getString(R.string.cmd_cut), R.drawable.g_cut, false) { requireSelection { cut() } },
        Cmd(getString(R.string.cmd_copy), R.drawable.g_copy, false) { requireSelection { copy() } },
        Cmd(getString(R.string.cmd_paste), R.drawable.g_paste, false) { paste() },
        Cmd(getString(R.string.cmd_rename), R.drawable.g_rename, false) { requireSelection { renameSingle() } },
        Cmd(getString(R.string.cmd_delete), R.drawable.g_delete, false) { requireSelection { confirmDelete() } },
        Cmd(getString(R.string.cmd_sort), R.drawable.g_sort, true) { showSortMenu(it) },
        Cmd(getString(R.string.cmd_view), R.drawable.g_view, true) { showViewMenu(it) }
    )

    private fun selectionCommands(): List<Cmd> = listOf(
        Cmd(getString(R.string.cmd_cut), R.drawable.g_cut, false) { cut() },
        Cmd(getString(R.string.cmd_copy), R.drawable.g_copy, false) { copy() },
        Cmd(getString(R.string.cmd_rename), R.drawable.g_rename, false) { renameSingle() },
        Cmd(getString(R.string.cmd_share), R.drawable.g_share, false) { shareSelection() },
        Cmd(getString(R.string.cmd_delete), R.drawable.g_delete, false) { confirmDelete() },
        Cmd(getString(R.string.cmd_select_all), R.drawable.g_select_all, false) { selectAll() },
        Cmd(getString(R.string.cancel), R.drawable.g_close, false) { clearSelection() }
    )

    private fun clearSelection() {
        selection.clear()
        refreshSelection()
        updateChrome()
    }

    private fun buildCommandBar() {
        val cmds = if (selection.isEmpty()) normalCommands() else selectionCommands()
        b.commandContainer.removeAllViews()
        b.commandScroll.scrollTo(0, 0)
        overflowCommands = emptyList()
        b.commandScroll.post { layoutCommands(cmds, 0) }
    }

    /**
     * Windows 11 collapses the commands that do not fit into the ⋯ button
     * rather than hiding them behind a horizontal scroll.
     */
    private fun layoutCommands(cmds: List<Cmd>, attempt: Int) {
        val avail = b.commandScroll.width
        if (avail <= 0) {
            if (attempt < 4) b.commandScroll.post { layoutCommands(cmds, attempt + 1) }
            else cmds.forEach { b.commandContainer.addView(buildCommandView(it)) }
            return
        }
        b.commandContainer.removeAllViews()
        val height = if (b.commandBar.height > 0) b.commandBar.height else dp(42)
        val hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        val wSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val budget = avail - dp(6)
        var used = 0
        val overflow = mutableListOf<Cmd>()
        for ((i, cmd) in cmds.withIndex()) {
            val v = buildCommandView(cmd)
            v.measure(wSpec, hSpec)
            val w = v.measuredWidth
            // always keep the first three reachable, even on very narrow screens
            if (used + w <= budget || i < 3) {
                b.commandContainer.addView(v)
                used += w
            } else {
                overflow += cmd
            }
        }
        overflowCommands = overflow
    }

    private inline fun requireSelection(action: () -> Unit) {
        if (selection.isEmpty()) toast("请先选择要操作的项目") else action()
    }

    private fun showNewMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.cmd_new_folder))
        popup.menu.add(0, 2, 1, getString(R.string.cmd_new_text))
        popup.menu.add(0, 3, 2, getString(R.string.new_more_types))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> promptNewFolder()
                2 -> promptNewFile("txt", getString(R.string.cmd_new_text))
                3 -> showNewTypeList()
            }
            true
        }
        popup.show()
    }

    private fun showNewTypeList() {
        val labels = NEW_FILE_TYPES.map { "${it.second}  (.${it.first})" } +
                getString(R.string.new_custom_type)
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.new_choose_type)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < NEW_FILE_TYPES.size) {
                    val (ext, label) = NEW_FILE_TYPES[which]
                    promptNewFile(ext, label)
                } else {
                    promptCustomExtension()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptCustomExtension() {
        promptName(getString(R.string.new_custom_type), "") { raw ->
            val clean = raw.trim().removePrefix(".").filter { it.isLetterOrDigit() || it == '_' }
            if (clean.isEmpty()) toast(getString(R.string.new_ext_invalid))
            else promptNewFile(clean, clean.uppercase() + " 文件")
        }
    }

    /** Creates an empty file of the chosen type, with a name prompt. */
    private fun promptNewFile(ext: String, typeLabel: String) {
        val dir = currentPath?.let { File(it) } ?: run {
            toast("请先进入一个存储设备")
            return
        }
        val suggested = FileRepo.uniqueName(dir, getString(R.string.new_file_default_name), ext)
        val vb = DialogNewFileBinding.inflate(layoutInflater)
        vb.typeHint.text = "$typeLabel    .$ext"
        vb.nameInput.setText(suggested)
        vb.nameInput.setSelection(0, suggested.substringBeforeLast('.', suggested).length)

        val dialog = AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.new_file_title)
            .setView(vb.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = vb.nameInput.text.toString().trim()
                when {
                    name.isEmpty() -> toast("名称不能为空")
                    name.contains('/') || name.contains('\\') -> toast("名称不能包含 / 或 \\")
                    else -> {
                        dialog.dismiss()
                        createNewFile(dir, name, ext)
                    }
                }
            }
        }
        dialog.show()
        vb.nameInput.requestFocus()
    }

    private fun createNewFile(dir: File, name: String, ext: String) {
        io.execute {
            val err = try {
                val target = File(dir, name)
                if (ext.equals("zip", ignoreCase = true)) {
                    // produce a real (empty but valid) archive, not a 0-byte file
                    java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(target.outputStream()))
                        .use { it.finish() }
                } else {
                    if (!target.createNewFile()) throw IllegalStateException("无法创建文件")
                }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                if (err == null) {
                    toast(getString(R.string.op_result, 1))
                    reload()
                } else {
                    toast(getString(R.string.op_failed, err))
                }
            }
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val options = listOf(
            SortBy.NAME to getString(R.string.sort_name),
            SortBy.DATE to getString(R.string.sort_date),
            SortBy.TYPE to getString(R.string.sort_type),
            SortBy.SIZE to getString(R.string.sort_size)
        )
        options.forEachIndexed { i, (by, label) ->
            val mi = popup.menu.add(0, i, i, label)
            mi.isCheckable = true
            mi.isChecked = sortSpec.by == by
        }
        popup.menu.add(0, 90, 9, getString(R.string.sort_asc)).apply {
            isCheckable = true; isChecked = sortSpec.ascending
        }
        popup.menu.add(0, 91, 10, getString(R.string.sort_desc)).apply {
            isCheckable = true; isChecked = !sortSpec.ascending
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                90 -> sortSpec = sortSpec.copy(ascending = true)
                91 -> sortSpec = sortSpec.copy(ascending = false)
                else -> sortSpec = sortSpec.copy(by = options[item.itemId].first)
            }
            prefs.sortSpec = sortSpec
            applyFilter()
            true
        }
        popup.show()
    }

    private fun showViewMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        VIEW_LABELS.forEachIndexed { i, (mode, labelRes) ->
            val mi = popup.menu.add(0, i, i, getString(labelRes))
            mi.isCheckable = true
            mi.isChecked = viewMode == mode
        }
        popup.setOnMenuItemClickListener { item ->
            setViewMode(VIEW_LABELS[item.itemId].first)
            true
        }
        popup.show()
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(this, b.btnOverflow)
        val actions = LinkedHashMap<Int, () -> Unit>()
        val seen = mutableSetOf<String>()
        var id = 1

        // an entry can arrive both from the collapsed commands and the fixed
        // list, so de-duplicate by label
        fun add(label: String, act: () -> Unit) {
            if (!seen.add(label)) return
            popup.menu.add(0, id, id, label)
            actions[id] = act
            id++
        }

        overflowCommands.forEach { cmd -> add(cmd.label) { cmd.action(b.btnOverflow) } }
        add(getString(R.string.cmd_select_all)) { selectAll() }
        add(getString(R.string.cmd_refresh)) { reload() }
        add(getString(R.string.cmd_paste)) { paste() }
        add(getString(R.string.cmd_new_folder)) { promptNewFolder() }
        add(getString(R.string.cmd_share_folder)) {
            startActivity(NetShareActivity.intent(this, currentPath))
        }
        add(getString(R.string.cmd_edit_path)) { enterPathEdit() }
        if (selection.size >= 2) {
            add(getString(R.string.cmd_batch_rename)) { batchRename() }
        }
        add(getString(R.string.cmd_properties)) { showProperties() }

        popup.setOnMenuItemClickListener { item ->
            actions[item.itemId]?.invoke()
            true
        }
        popup.show()
    }

    private fun setViewMode(mode: ViewMode) {
        viewMode = mode
        prefs.viewMode = mode
        applyView()
    }

    // ---- selection --------------------------------------------------------

    private fun onEntryClick(e: FsEntry) {
        // the This PC page hosts pin-able shortcuts. These sentinels are not real
        // paths, so compare the raw File path — absolutePath would resolve them
        // against the working directory and never match.
        when (e.file.path) {
            ADD_PATH -> { showAddShortcut(); return }
            Shortcuts.RECYCLE -> { startActivity(RecycleBinActivity.intent(this)); return }
            Shortcuts.NETSHARE -> {
                startActivity(
                    NetShareActivity.intent(this, Environment.getExternalStorageDirectory().absolutePath)
                )
                return
            }
        }
        if (selection.isNotEmpty()) { toggleSelection(e); return }
        if (e.isDir) { navigateTo(e.path); return }
        when {
            ZipRepo.isApk(e.name) -> showApkInfo(e)
            ZipRepo.isArchive(e.name) -> ArchiveActivity.open(this, e.path)
            FileKinds.of(e.name) == FileKinds.Kind.IMAGE -> openImage(e)
            FileKinds.of(e.name) != FileKinds.Kind.OTHER ->
                startActivity(ViewerActivity.intent(this, e.path))
            // no built-in renderer (pdf, office, ...): hand it to the system,
            // and fall back to the viewer's info screen when nothing accepts it
            else -> if (!openFile(e)) startActivity(ViewerActivity.intent(this, e.path))
        }
    }

    private fun openImage(e: FsEntry) {
        val siblings = entries.filter { !it.isDir && FileKinds.isImage(it.name) }.map { it.path }
        val idx = siblings.indexOf(e.path).coerceAtLeast(0)
        startActivity(ViewerActivity.intentForImage(this, e.path, siblings, idx))
    }

    /**
     * APK details read through PackageManager (which parses the binary
     * manifest for us). The icon and label are resolved off the main thread.
     */
    private fun showApkInfo(e: FsEntry) {
        val vb = DialogApkInfoBinding.inflate(layoutInflater)
        vb.apkLabel.text = e.name
        vb.apkPackage.text = e.path
        vb.apkIcon.setImageResource(R.drawable.ic_file_apk)

        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.apk_title)
            .setView(vb.root)
            .setPositiveButton(R.string.apk_install) { _, _ -> openFile(e) }
            .setNeutralButton(R.string.apk_view_contents) { _, _ -> ArchiveActivity.open(this, e.path) }
            .setNegativeButton(R.string.apk_close, null)
            .show()

        io.execute {
            val info = ApkInspector.inspect(this, e.file)
            main.post {
                if (info == null) {
                    vb.apkPackage.setText(R.string.apk_parse_failed)
                    vb.apkPermTitle.visibility = View.GONE
                    vb.apkPermList.visibility = View.GONE
                    return@post
                }
                vb.apkLabel.text = info.label
                vb.apkPackage.text = info.packageName
                if (info.icon != null) vb.apkIcon.setImageDrawable(info.icon)

                fun row(label: String, value: String) {
                    if (value.isEmpty()) return
                    val rb = RowPropertyBinding.inflate(layoutInflater, vb.apkRows, false)
                    rb.pRowLabel.text = label
                    rb.pRowValue.text = value
                    vb.apkRows.addView(rb.root)
                }
                row(getString(R.string.apk_version), info.versionName)
                row(getString(R.string.apk_version_code), info.versionCode.toString())
                if (info.minSdk > 0) {
                    row(getString(R.string.apk_min_sdk), "Android ${apiName(info.minSdk)}（API ${info.minSdk}）")
                }
                if (info.targetSdk > 0) {
                    row(getString(R.string.apk_target_sdk), "Android ${apiName(info.targetSdk)}（API ${info.targetSdk}）")
                }
                row(getString(R.string.prop_size), Fmt.size(info.size))

                val perms = info.permissions
                vb.apkPermTitle.text = getString(R.string.apk_permissions, perms.size)
                vb.apkPermList.text = if (perms.isEmpty()) {
                    getString(R.string.apk_none)
                } else {
                    perms.joinToString("\n") { it.removePrefix("android.permission.") }
                }
            }
        }
    }

    private fun apiName(api: Int): String = when (api) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"
        26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
        31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
        else -> "API $api"
    }

    private fun onEntryLongClick(e: FsEntry) {
        // on the This PC page a long press manages the shortcut instead
        if (currentPath == null && e.file.path != ADD_PATH && !e.isStorageRoot) {
            confirmRemoveShortcut(e)
            return
        }
        showEntryMenu(e)
    }

    /**
     * The right-click equivalent: everything you can do to one item, without
     * having to enter selection mode first.
     */
    private fun showEntryMenu(e: FsEntry) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.ctx_open) to { onEntryClick(e) }
        if (!e.isDir) actions += getString(R.string.ctx_open_with) to { openFile(e) }
        actions += getString(R.string.cmd_copy) to { selectOnly(e); copy() }
        actions += getString(R.string.cmd_cut) to { selectOnly(e); cut() }
        actions += getString(R.string.cmd_rename) to { selectOnly(e); renameSingle() }
        actions += getString(R.string.cmd_share) to { selectOnly(e); shareSelection() }
        actions += getString(R.string.cmd_properties) to { showPropertiesFor(e) }
        actions += getString(R.string.cmd_delete) to { selectOnly(e); confirmDelete() }
        actions += getString(R.string.ctx_multi) to { toggleSelection(e) }

        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(e.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** preselect a single entry so the existing selection-based actions work */
    private fun selectOnly(e: FsEntry) {
        selection.clear()
        selection.add(e.path)
        refreshSelection()
        updateChrome()
    }

    private fun toggleSelection(e: FsEntry) {
        if (!selection.add(e.path)) selection.remove(e.path)
        refreshSelection()
        updateChrome()
    }

    private fun selectAll() {
        if (selection.size == entries.size) selection.clear()
        else { selection.clear(); entries.forEach { selection.add(it.path) } }
        refreshSelection()
        updateChrome()
    }

    private fun selectedFiles(): List<File> =
        entries.filter { selection.contains(it.path) }.map { it.file }

    // ---- clipboard & operations -------------------------------------------

    private fun cut() {
        clipboard = selectedFiles()
        clipboardCut = true
        toast(getString(R.string.clipboard_cut_hint, clipboard.size))
        selection.clear(); refreshSelection(); updateChrome()
    }

    private fun copy() {
        clipboard = selectedFiles()
        clipboardCut = false
        toast(getString(R.string.clipboard_copy_hint, clipboard.size))
        selection.clear(); refreshSelection(); updateChrome()
    }

    private fun paste() {
        val dest = currentPath?.let { File(it) }
        if (dest == null) { toast(getString(R.string.cannot_paste_here)); return }
        if (clipboard.isEmpty()) { toast("剪贴板为空"); return }
        val items = clipboard.toList()
        val moving = clipboardCut
        val conflicts = FileRepo.findConflicts(items, dest)
        if (conflicts.isEmpty()) {
            startTransfer(items, dest, moving, ConflictPolicy.KEEP_BOTH)
            return
        }
        // Windows' "目标位置已包含同名项目" prompt – never silently overwrite.
        val options = arrayOf(
            getString(R.string.conflict_replace),
            getString(R.string.conflict_skip),
            getString(R.string.conflict_keep_both)
        )
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.conflict_title)
            .setMessage(getString(R.string.conflict_message, conflicts.size))
            .setItems(options) { _, which ->
                val policy = when (which) {
                    0 -> ConflictPolicy.REPLACE
                    1 -> ConflictPolicy.SKIP
                    else -> ConflictPolicy.KEEP_BOTH
                }
                startTransfer(items, dest, moving, policy)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startTransfer(
        items: List<File>, dest: File, moving: Boolean, policy: ConflictPolicy
    ) {
        runFileOp(
            labelRes = if (moving) R.string.op_moving else R.string.op_copying,
            onDone = { r ->
                // keep the clipboard if a move was cancelled half way
                if (moving && !r.cancelled) { clipboard = emptyList(); clipboardCut = false }
            }
        ) { onItem, cancelled ->
            FileRepo.transfer(items, dest, moving, policy, onItem, cancelled)
        }
    }

    private fun confirmDelete() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_trash_message, files.size))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete_permanent) { _, _ ->
                runFileOp(R.string.op_deleting) { _, cancelled -> FileRepo.delete(files, cancelled) }
            }
            .setPositiveButton(R.string.delete_to_trash) { _, _ ->
                val created = java.util.concurrent.atomic.AtomicReference<List<TrashRepo.Entry>>(emptyList())
                runFileOp(
                    labelRes = R.string.op_deleting,
                    onDone = { r ->
                        val entries = created.get()
                        if (entries.isNotEmpty()) {
                            showUndo(entries, getString(R.string.trash_moved, r.ok))
                        }
                        refreshTrashCount()
                    }
                ) { _, _ ->
                    val outcome = TrashRepo.trash(this, files)
                    created.set(outcome.entries)
                    outcome.result
                }
            }
            .show()
    }

    // ---- undo strip -------------------------------------------------------

    private fun showUndo(entries: List<TrashRepo.Entry>, message: String) {
        lastTrashed = entries
        b.undoText.text = message
        b.undoBar.visibility = View.VISIBLE
        main.removeCallbacks(undoTimer)
        main.postDelayed(undoTimer, 7000)
    }

    private fun hideUndo() {
        main.removeCallbacks(undoTimer)
        b.undoBar.visibility = View.GONE
        lastTrashed = emptyList()
    }

    private fun undoTrash() {
        val entries = lastTrashed
        hideUndo()
        if (entries.isEmpty()) return
        runFileOp(
            labelRes = R.string.recycle_restoring,
            onDone = { refreshTrashCount() }
        ) { _, _ -> TrashRepo.restore(this, entries) }
    }

    private fun refreshTrashCount() {
        io.execute {
            val n = TrashRepo.count(this)
            main.post {
                if (n != trashCount) {
                    trashCount = n
                    navAdapter.rows = buildNavRows()
                }
            }
        }
    }

    private fun renameSingle() {
        val files = selectedFiles()
        if (files.size != 1) { toast("请只选择一个项目进行重命名"); return }
        val f = files.first()
        promptName(getString(R.string.rename), f.name) { newName ->
            try {
                FileRepo.rename(f, newName)
                selection.clear()
                reload()
            } catch (ex: Exception) {
                toast(getString(R.string.op_failed, ex.message ?: ""))
            }
        }
    }

    private fun promptNewFolder() {
        val dir = currentPath?.let { File(it) } ?: run { toast("请先进入一个存储设备"); return }
        promptName(getString(R.string.new_folder), FileRepo.uniqueName(dir, "新建文件夹")) { name ->
            try {
                FileRepo.mkdir(dir, name); reload()
            } catch (ex: Exception) {
                toast(getString(R.string.op_failed, ex.message ?: ""))
            }
        }
    }

    private fun promptName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = DialogInputBinding.inflate(layoutInflater)
        input.inputField.setText(initial)
        input.inputField.setSelection(0, initial.substringBeforeLast('.', initial).length)
        val dialog = AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(title)
            .setView(input.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.inputField.text.toString().trim()
                when {
                    name.isEmpty() -> toast("名称不能为空")
                    name.contains('/') || name.contains('\\') -> toast("名称不能包含 / 或 \\")
                    else -> { dialog.dismiss(); onOk(name) }
                }
            }
        }
        dialog.show()
        input.inputField.requestFocus()
    }

    /**
     * Windows-style batch rename: an optional find/replace over the original
     * name, then a template with {name} {n} {ext} placeholders, with a live
     * preview of the first few results.
     */
    private fun batchRename() {
        val files = selectedFiles()
        if (files.size < 2) {
            toast(getString(R.string.batch_need_two))
            return
        }
        val vb = DialogBatchRenameBinding.inflate(layoutInflater)
        vb.tplInput.setText("{name}")
        vb.startInput.setText("1")

        fun startIndex(): Int = vb.startInput.text.toString().trim().toIntOrNull() ?: 1

        fun newName(index: Int, f: File): String {
            val tpl = vb.tplInput.text.toString().ifBlank { "{name}" }
            val find = vb.findInput.text.toString()
            val repl = vb.replaceInput.text.toString()
            val dot = f.name.lastIndexOf('.')
            val base = if (dot > 0) f.name.substring(0, dot) else f.name
            val ext = if (dot > 0) f.name.substring(dot + 1) else ""
            val edited = if (find.isNotEmpty()) base.replace(find, repl) else base
            var name = tpl.replace("{name}", edited)
                .replace("{n}", (startIndex() + index).toString())
                .replace("{ext}", ext)
            if (name.isBlank()) name = edited
            // keep the original extension unless the template places {ext} itself
            if (!tpl.contains("{ext}") && ext.isNotEmpty() && !name.endsWith(".$ext")) {
                name = "$name.$ext"
            }
            return name
        }

        fun refreshPreview() {
            val head = files.take(3).mapIndexed { i, f -> newName(i, f) }
            val tail = if (files.size > 3) "\n…  共 ${files.size} 个项目" else ""
            vb.previewText.text = getString(R.string.batch_preview_title) + "\n" +
                    head.joinToString("\n") + tail
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPreview()
        }
        listOf(vb.findInput, vb.replaceInput, vb.tplInput, vb.startInput)
            .forEach { it.addTextChangedListener(watcher) }
        refreshPreview()

        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(getString(R.string.batch_title, files.size))
            .setView(vb.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                runFileOp(R.string.rename) { _, _ ->
                    FileRepo.batchRename(files) { i, f -> newName(i, f) }
                }
            }
            .show()
    }

    private fun shareSelection() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        try {
            val uris = ArrayList<Uri>()
            files.forEach { uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).setType(mimeOf(files.first()))
                    .putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                    .putExtra(Intent.EXTRA_STREAM, uris)
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.cmd_share)))
        } catch (ex: Exception) {
            toast(getString(R.string.op_failed, ex.message ?: ""))
        }
    }

    private fun openFile(e: FsEntry): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", e.file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(e.file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
            true
        } catch (ex: ActivityNotFoundException) {
            false
        } catch (ex: Exception) {
            toast(getString(R.string.op_failed, ex.message ?: ""))
            true
        }
    }

    private fun mimeOf(f: File): String {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun showProperties() {
        val target = entries.firstOrNull { selection.contains(it.path) }
        if (target == null) { toast("请先选择要查看属性的项目"); return }
        showPropertiesFor(target)
    }

    private fun showPropertiesFor(e: FsEntry) {
        val vb = DialogPropertiesBinding.inflate(layoutInflater)
        vb.propIcon.setImageResource(IconResolver.forEntry(e))
        vb.propTitle.text = e.name
        val dialog = AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setView(vb.root)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.show()

        fun row(label: String, value: String): TextView? {
            if (value.isEmpty()) return null
            val rb = RowPropertyBinding.inflate(layoutInflater, vb.propRows, false)
            rb.pRowLabel.text = label
            rb.pRowValue.text = value
            vb.propRows.addView(rb.root)
            return rb.pRowValue
        }

        row(getString(R.string.prop_name), e.name)
        row(getString(R.string.prop_type), Fmt.typeName(e))
        row(getString(R.string.prop_path), e.path)
        row(getString(R.string.prop_modified), Fmt.date(e.lastModified))
        row(getString(R.string.prop_readable), if (e.file.canRead()) getString(R.string.yes) else getString(R.string.no))
        row(getString(R.string.prop_writable), if (e.file.canWrite()) getString(R.string.yes) else getString(R.string.no))
        if (e.totalBytes > 0) {
            row(getString(R.string.prop_size),
                getString(R.string.storage_used, Fmt.size(e.usedBytes), Fmt.size(e.totalBytes)))
        }
        if (e.isDir) {
            io.execute {
                val count = FileRepo.countChildren(e.file)
                val size = FileRepo.sizeOf(e.file)
                main.post {
                    row(getString(R.string.prop_contains), "$count 个项目")
                    row(getString(R.string.prop_size), Fmt.size(size))
                }
            }
        } else {
            row(getString(R.string.prop_size), Fmt.size(e.size))
            if (e.size in 1..HASH_LIMIT) {
                val md5Row = row(getString(R.string.prop_md5), getString(R.string.prop_computing))
                val shaRow = row(getString(R.string.prop_sha256), getString(R.string.prop_computing))
                md5Row?.setTextIsSelectable(true)
                shaRow?.setTextIsSelectable(true)
                hashIo.execute {
                    val r = FileRepo.md5AndSha256(e.file)
                    main.post {
                        md5Row?.text = r?.first ?: getString(R.string.prop_hash_failed)
                        shaRow?.text = r?.second ?: getString(R.string.prop_hash_failed)
                    }
                }
            } else if (e.size > HASH_LIMIT) {
                row(getString(R.string.prop_sha256), getString(R.string.prop_hash_skipped))
            }
        }
    }

    // ---- async helpers ----------------------------------------------------

    private fun runFileOp(
        labelRes: Int,
        onDone: (OpResult) -> Unit = {},
        work: (
            onProgress: (done: Int, total: Int, name: String) -> Unit,
            isCancelled: () -> Boolean
        ) -> OpResult
    ) {
        showLoading(getString(labelRes), cancellable = true)
        io.execute {
            val result = try {
                work(
                    { done, total, name ->
                        main.post {
                            if (total > 1) {
                                b.opSpinner.visibility = View.GONE
                                b.opProgress.visibility = View.VISIBLE
                                b.opProgress.max = total
                                b.opProgress.progress = done
                                b.loadingText.text = getString(R.string.op_progress, done, total, name)
                            } else {
                                b.opSpinner.visibility = View.VISIBLE
                                b.opProgress.visibility = View.GONE
                                b.loadingText.text = name
                            }
                        }
                    },
                    { cancelFlag }
                )
            } catch (ex: Exception) {
                OpResult(failed = listOf(ex.message ?: ex.javaClass.simpleName))
            }
            main.post {
                hideLoading()
                toast(resultText(result))
                onDone(result)
                selection.clear()
                reload()
            }
        }
    }

    private fun resultText(r: OpResult): String = when {
        r.cancelled -> getString(R.string.op_cancelled)
        r.failed.isNotEmpty() -> getString(R.string.op_result_failed, r.ok, r.failed.size)
        r.ok > 0 -> getString(R.string.op_result, r.ok)
        else -> getString(R.string.op_result_none)
    }

    private fun showLoading(text: String, cancellable: Boolean = false) {
        cancelFlag = false
        b.opSpinner.visibility = View.VISIBLE
        b.opProgress.visibility = View.GONE
        b.loadingText.text = text
        b.btnCancelOp.visibility = if (cancellable) View.VISIBLE else View.GONE
        b.loadingView.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        b.loadingView.visibility = View.GONE
    }

    // ---- permissions ------------------------------------------------------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private fun ensureAccess() {
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.permission_grant) { _, _ -> requestAccess() }
            .show()
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (ex: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (ex2: Exception) {
                    toast(getString(R.string.permission_denied))
                }
            }
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                REQ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            accessAtStart = hasStorageAccess()
            reload()
        }
    }

    // ---- misc -------------------------------------------------------------

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun spanFor(mode: ViewMode): Int = when (mode) {
        ViewMode.XL_ICONS -> 3
        ViewMode.LG_ICONS -> 4
        ViewMode.MD_ICONS -> 5
        ViewMode.SM_ICONS -> 6
        ViewMode.TILES -> 2
        else -> 4
    }

    private fun iconDpFor(mode: ViewMode): Int = when (mode) {
        ViewMode.XL_ICONS -> 72
        ViewMode.LG_ICONS -> 56
        ViewMode.MD_ICONS -> 44
        ViewMode.SM_ICONS -> 30
        ViewMode.TILES -> 60
        else -> 44
    }

    companion object {
        private const val REQ_STORAGE = 1001

        /** virtual location: Home (quick access folders + recent files) */
        private const val HOME_PATH = "::home"

        // virtual entry on the This PC page that opens the pin picker
        private const val ADD_PATH = "::addshortcut"

        /** extras used by "在文件夹中显示" from the search screen */
        private const val EXTRA_OPEN_PATH = "open_path"
        private const val EXTRA_SELECT = "select_path"

        fun intentAt(context: Context, dir: String, select: String?): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PATH, dir)
                .putExtra(EXTRA_SELECT, select)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** above this size the properties sheet does not hash the file */
        private const val HASH_LIMIT = 256L * 1024 * 1024

        /** extension -> label for the "更多文件类型" chooser */
        private val NEW_FILE_TYPES = listOf(
            "txt" to "文本文档",
            "md" to "Markdown 文档",
            "json" to "JSON 文件",
            "xml" to "XML 文档",
            "html" to "HTML 网页",
            "csv" to "CSV 表格",
            "js" to "JavaScript 文件",
            "kt" to "Kotlin 源文件",
            "java" to "Java 源文件",
            "py" to "Python 脚本",
            "sh" to "Shell 脚本",
            "bat" to "批处理文件",
            "sql" to "SQL 脚本",
            "png" to "PNG 图片",
            "jpg" to "JPG 图片",
            "docx" to "Word 文档",
            "xlsx" to "Excel 工作表",
            "pptx" to "PowerPoint 演示文稿",
            "zip" to "压缩文件夹"
        )

        private val VIEW_LABELS = listOf(
            ViewMode.XL_ICONS to R.string.view_xl,
            ViewMode.LG_ICONS to R.string.view_lg,
            ViewMode.MD_ICONS to R.string.view_md,
            ViewMode.SM_ICONS to R.string.view_sm,
            ViewMode.TILES to R.string.view_tiles,
            ViewMode.LIST to R.string.view_list,
            ViewMode.DETAILS to R.string.view_details
        )
    }
}
