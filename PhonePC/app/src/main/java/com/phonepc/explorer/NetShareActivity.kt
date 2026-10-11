package com.phonepc.explorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phonepc.explorer.databinding.ActivityNetshareBinding
import com.phonepc.explorer.databinding.ViewCommandButtonBinding
import com.phonepc.explorer.net.NetShare
import java.io.File

/**
 * Turns the phone into a tiny file server on the local network. A PC browser
 * can browse, download from, and upload into the shared folder – no cable, no
 * extra software, no account.
 */
class NetShareActivity : AppCompatActivity() {

    private lateinit var b: ActivityNetshareBinding
    private var shareDir: File = File("/")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNetshareBinding.inflate(layoutInflater)
        setContentView(b.root)

        val path = intent.getStringExtra(EXTRA_PATH)
        shareDir = if (!path.isNullOrEmpty()) File(path) else File("/")
        if (!shareDir.isDirectory) shareDir = shareDir.parentFile ?: File("/")

        b.btnBack.setOnClickListener { finish() }
        b.dirText.text = shareDir.absolutePath
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        // the process-wide NetShare holder owns the server; nothing to release
    }

    // ---- ui ---------------------------------------------------------------

    private fun render() {
        val running = NetShare.isRunning
        val url = NetShare.url

        b.statusText.text = getString(if (running) R.string.net_running else R.string.net_stopped)
        b.statusDot.setImageResource(if (running) R.drawable.g_checkbox else R.drawable.g_close)
        b.statusDot.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.win_accent else R.color.win_text_disabled)
        )
        b.urlText.text = when {
            running && url != null -> url
            running -> getString(R.string.net_no_wifi)
            else -> getString(R.string.net_url_placeholder)
        }
        b.hintText.text = getString(
            if (running) R.string.net_hint_on else R.string.net_hint_off
        )
        b.warnText.text = getString(R.string.net_warning)

        buildCommandBar()
    }

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
        if (NetShare.isRunning) {
            addCommand(getString(R.string.net_stop), R.drawable.g_close) { stopShare() }
            addCommand(getString(R.string.net_copy_url), R.drawable.g_copy) { copyUrl() }
            addCommand(getString(R.string.net_open_browser), R.drawable.g_forward) { openBrowser() }
        } else {
            addCommand(getString(R.string.net_start), R.drawable.g_view) { startShare() }
            addCommand(getString(R.string.net_copy_url), R.drawable.g_copy) {
                toast(getString(R.string.net_not_running))
            }
        }
    }

    // ---- actions ----------------------------------------------------------

    private fun startShare() {
        try {
            NetShare.start(shareDir)
            render()
            val url = NetShare.url
            toast(if (url != null) getString(R.string.net_started, url) else getString(R.string.net_no_wifi))
        } catch (e: Exception) {
            toast(getString(R.string.op_failed, e.message ?: ""))
        }
    }

    private fun stopShare() {
        NetShare.stop()
        render()
        toast(getString(R.string.net_stopped_toast))
    }

    private fun copyUrl() {
        val url = NetShare.url
        if (url == null) { toast(getString(R.string.net_not_running)); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", url))
        toast(getString(R.string.net_copied, url))
    }

    private fun openBrowser() {
        val url = NetShare.url
        if (url == null) { toast(getString(R.string.net_not_running)); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(url)))
        } catch (e: Exception) {
            toast(getString(R.string.op_failed, e.message ?: ""))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_PATH = "share_path"

        fun intent(context: Context, path: String?): Intent =
            Intent(context, NetShareActivity::class.java).putExtra(EXTRA_PATH, path)
    }
}
