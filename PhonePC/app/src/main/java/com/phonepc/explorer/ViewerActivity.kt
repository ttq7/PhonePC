package com.phonepc.explorer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.phonepc.explorer.data.FileRepo
import com.phonepc.explorer.databinding.ActivityViewerBinding
import com.phonepc.explorer.databinding.ItemViewerImageBinding
import com.phonepc.explorer.databinding.ViewCommandButtonBinding
import com.phonepc.explorer.ui.IconResolver
import com.phonepc.explorer.util.FileKinds
import com.phonepc.explorer.util.Fmt
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Built-in viewer so images, text and media open inside 此手机 instead of
 * bouncing straight out to another app.
 *
 * - images: swipeable pager with pinch / double-tap zoom
 * - text:   read view plus a simple editor that writes back as UTF-8
 * - video / audio: framework playback
 * - anything else: a clear message plus "用其他应用打开"
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityViewerBinding
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var file: File = File("/")
    private var kind: FileKinds.Kind = FileKinds.Kind.OTHER
    private var images: List<String> = emptyList()
    private var index = 0
    private var editing = false
    private var dirty = false
    private var mediaPlayer: MediaPlayer? = null
    private var seekRunner: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        file = File(path)
        if (path.isEmpty() || !file.exists()) {
            toast(getString(R.string.viewer_open_failed))
            finish()
            return
        }
        kind = FileKinds.of(file.name)
        images = intent.getStringArrayListExtra(EXTRA_IMAGES) ?: listOf(path)
        index = intent.getIntExtra(EXTRA_INDEX, images.indexOf(path).coerceAtLeast(0))
            .coerceIn(0, max(0, images.size - 1))

        b.titleText.text = file.name
        b.titleIcon.setImageResource(IconResolver.forExtension(file.name.substringAfterLast('.', "")))
        b.btnBack.setOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        when (kind) {
            FileKinds.Kind.IMAGE -> showImage()
            FileKinds.Kind.TEXT -> loadText()
            FileKinds.Kind.VIDEO -> showVideo()
            FileKinds.Kind.AUDIO -> showAudio()
            FileKinds.Kind.OTHER -> showUnsupported()
        }
    }

    override fun onStop() {
        super.onStop()
        // never keep playing in the background from a viewer
        if (::b.isInitialized) {
            try {
                b.videoView.stopPlayback()
            } catch (_: Throwable) {
            }
        }
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        io.shutdown()
    }

    // ---- images -----------------------------------------------------------

    private fun showImage() {
        b.pager.visibility = View.VISIBLE
        val lm = androidx.recyclerview.widget.LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        b.pager.layoutManager = lm
        b.pager.adapter = ImagePager(images)
        b.pager.itemAnimator = null
        PagerSnapHelper().attachToRecyclerView(b.pager)
        b.pager.scrollToPosition(index)
        updateCounter(index)
        b.counterText.visibility = View.VISIBLE
        b.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val p = (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.findFirstCompletelyVisibleItemPosition() ?: 0
                    if (p >= 0) {
                        index = p
                        file = File(images[p])
                        b.titleText.text = file.name
                        updateCounter(p)
                        buildCommands()
                    }
                }
            }
        })
        b.statusText.text = Fmt.size(file.length())
        buildCommands()
    }

    private fun updateCounter(i: Int) {
        b.counterText.text = getString(R.string.viewer_image_counter, i + 1, images.size)
    }

    private inner class ImagePager(private val paths: List<String>) :
        RecyclerView.Adapter<ImagePager.VH>() {

        inner class VH(val b: ItemViewerImageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemViewerImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = paths.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val view = holder.b.pageImage
            val key = paths[position]
            view.tag = key
            view.setImageDrawable(null)
            view.resetToFit()
            io.execute {
                val bmp = decodeSampled(key)
                main.post {
                    if (bmp != null && view.tag == key) view.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun decodeSampled(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val target = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            var sample = 1
            var dim = max(bounds.outWidth, bounds.outHeight)
            while (dim / 2 >= target) {
                dim /= 2
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Throwable) {
            null
        }
    }

    // ---- text -------------------------------------------------------------

    private fun loadText() {
        b.loading.visibility = View.VISIBLE
        io.execute {
            val text = readText(file)
            main.post {
                b.loading.visibility = View.GONE
                if (text == null) {
                    showUnsupported(getString(R.string.viewer_binary))
                    return@post
                }
                b.textView.text = text
                b.textScroll.visibility = View.VISIBLE
                b.statusText.text = getString(
                    R.string.viewer_text_status,
                    file.length() / 1024 + 1,
                    text.count { it == '\n' } + 1
                )
                buildCommands()
            }
        }
    }

    private fun readText(f: File): String? {
        if (f.length() > MAX_TEXT_BYTES) return null
        if (!FileKinds.isProbablyText(f)) return null
        return try {
            val bytes = f.readBytes()
            var s = String(bytes, Charsets.UTF_8)
            if (s.startsWith('\uFEFF')) s = s.substring(1)
            s
        } catch (e: Exception) {
            null
        }
    }

    private fun enterEdit() {
        editing = true
        b.editText.setText(b.textView.text)
        b.editText.setSelection(0)
        b.textScroll.visibility = View.GONE
        b.editScroll.visibility = View.VISIBLE
        // focus + IME, otherwise the editor looks ready but swallows input
        b.editText.requestFocus()
        b.editText.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(b.editText, InputMethodManager.SHOW_IMPLICIT)
        }
        b.editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                dirty = b.editText.text.toString() != b.textView.text.toString()
            }
        })
        buildCommands()
    }

    private fun saveText() {
        val content = b.editText.text.toString()
        b.loading.visibility = View.VISIBLE
        io.execute {
            val err = try {
                file.writeText(content, Charsets.UTF_8)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                b.loading.visibility = View.GONE
                if (err == null) {
                    dirty = false
                    editing = false
                    b.textView.text = content
                    b.editScroll.visibility = View.GONE
                    b.textScroll.visibility = View.VISIBLE
                    toast(getString(R.string.viewer_saved))
                    buildCommands()
                } else {
                    toast(getString(R.string.viewer_save_failed, err))
                }
            }
        }
    }

    private fun cancelEdit() {
        editing = false
        dirty = false
        b.editScroll.visibility = View.GONE
        b.textScroll.visibility = View.VISIBLE
        buildCommands()
    }

    // ---- video / audio ----------------------------------------------------

    private fun showVideo() {
        b.videoBox.visibility = View.VISIBLE
        try {
            b.videoView.setVideoPath(file.absolutePath)
            b.videoView.setMediaController(MediaController(this))
            b.videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                b.videoView.start()
                b.statusText.text = "${Fmt.size(file.length())}    ${fmtDuration(mp.duration.toLong())}"
            }
            b.videoView.setOnErrorListener { _, _, _ ->
                showUnsupported(getString(R.string.viewer_open_failed))
                true
            }
        } catch (e: Exception) {
            showUnsupported(getString(R.string.viewer_open_failed))
        }
        buildCommands()
    }

    private fun showAudio() {
        b.audioBox.visibility = View.VISIBLE
        b.audioName.text = file.name
        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                b.audioDur.text = fmtDuration(p.duration.toLong())
                b.statusText.text = "${Fmt.size(file.length())}    ${fmtDuration(p.duration.toLong())}"
                b.audioSeek.max = 1000
                buildCommands()
            }
            mp.setOnCompletionListener {
                stopSeekUpdates()
                b.audioSeek.progress = 0
                b.audioPos.text = fmtDuration(0)
                buildCommands()
            }
            mp.prepareAsync()
            b.audioSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val p = mediaPlayer ?: return
                    if (p.duration > 0) p.seekTo(p.duration * (sb?.progress ?: 0) / 1000)
                }
            })
        } catch (e: Exception) {
            showUnsupported(getString(R.string.viewer_open_failed))
        }
        buildCommands()
    }

    private fun togglePlay() {
        val p = mediaPlayer ?: return
        if (p.isPlaying) {
            p.pause()
            stopSeekUpdates()
        } else {
            p.start()
            startSeekUpdates()
        }
        buildCommands()
    }

    private fun startSeekUpdates() {
        stopSeekUpdates()
        val r = object : Runnable {
            override fun run() {
                val p = mediaPlayer ?: return
                if (p.duration > 0) {
                    b.audioSeek.progress = (p.currentPosition * 1000L / p.duration).toInt()
                    b.audioPos.text = fmtDuration(p.currentPosition.toLong())
                }
                main.postDelayed(this, 500)
            }
        }
        seekRunner = r
        main.post(r)
    }

    private fun stopSeekUpdates() {
        seekRunner?.let { main.removeCallbacks(it) }
        seekRunner = null
    }

    private fun releasePlayer() {
        stopSeekUpdates()
        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {
        }
        mediaPlayer = null
    }

    private fun fmtDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    // ---- other ------------------------------------------------------------

    private fun showUnsupported(message: String = getString(R.string.viewer_unsupported)) {
        b.unsupportedBox.visibility = View.VISIBLE
        b.unsupportedIcon.setImageResource(IconResolver.forExtension(file.name.substringAfterLast('.', "")))
        b.unsupportedText.text = message
        b.statusText.text = "${Fmt.size(file.length())}    ${file.name.substringAfterLast('.', "").uppercase()}"
        buildCommands()
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

    private fun buildCommands() {
        b.commandContainer.removeAllViews()
        if (editing) {
            addCommand(getString(R.string.viewer_save), R.drawable.g_save) { saveText() }
            addCommand(getString(R.string.cancel), R.drawable.g_close) { cancelEdit() }
            return
        }
        when (kind) {
            FileKinds.Kind.IMAGE -> {
                addCommand(getString(R.string.viewer_share), R.drawable.g_share) { share() }
                addCommand(getString(R.string.viewer_open_with), R.drawable.g_forward) { openWithSystem() }
            }
            FileKinds.Kind.TEXT -> {
                addCommand(getString(R.string.viewer_edit), R.drawable.g_rename) { enterEdit() }
                addCommand(getString(R.string.viewer_share), R.drawable.g_share) { share() }
                addCommand(getString(R.string.viewer_open_with), R.drawable.g_forward) { openWithSystem() }
            }
            FileKinds.Kind.AUDIO -> {
                val playing = mediaPlayer?.isPlaying == true
                addCommand(
                    getString(if (playing) R.string.viewer_pause else R.string.viewer_play),
                    if (playing) R.drawable.g_close else R.drawable.g_view
                ) { togglePlay() }
                addCommand(getString(R.string.viewer_share), R.drawable.g_share) { share() }
                addCommand(getString(R.string.viewer_open_with), R.drawable.g_forward) { openWithSystem() }
            }
            FileKinds.Kind.VIDEO -> {
                addCommand(getString(R.string.viewer_share), R.drawable.g_share) { share() }
                addCommand(getString(R.string.viewer_open_with), R.drawable.g_forward) { openWithSystem() }
            }
            FileKinds.Kind.OTHER -> {
                addCommand(getString(R.string.viewer_open_with), R.drawable.g_forward) { openWithSystem() }
                addCommand(getString(R.string.viewer_share), R.drawable.g_share) { share() }
            }
        }
    }

    // ---- intents ----------------------------------------------------------

    private fun uri(): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun mimeOf(): String {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun share() {
        try {
            val i = Intent(Intent.ACTION_SEND).setType(mimeOf())
                .putExtra(Intent.EXTRA_STREAM, uri())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, getString(R.string.viewer_share)))
        } catch (e: Exception) {
            toast(getString(R.string.op_failed, e.message ?: ""))
        }
    }

    private fun openWithSystem() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri(), mimeOf())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.open_with_failed))
        } catch (e: Exception) {
            toast(getString(R.string.op_failed, e.message ?: ""))
        }
    }

    // ---- back / misc ------------------------------------------------------

    private fun handleBack() {
        if (editing && dirty) {
            AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
                .setTitle(R.string.viewer_unsaved_title)
                .setMessage(getString(R.string.viewer_unsaved_message, file.name))
                .setPositiveButton(R.string.viewer_save) { _, _ -> saveText() }
                .setNeutralButton(R.string.viewer_discard) { _, _ -> cancelEdit(); finish() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_PATH = "viewer_path"
        private const val EXTRA_IMAGES = "viewer_images"
        private const val EXTRA_INDEX = "viewer_index"
        private const val MAX_TEXT_BYTES = 4L * 1024 * 1024

        fun intent(context: Context, path: String): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_PATH, path)

        fun intentForImage(context: Context, path: String, siblings: List<String>, index: Int): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putStringArrayListExtra(EXTRA_IMAGES, ArrayList(siblings))
                .putExtra(EXTRA_INDEX, index)
    }
}
