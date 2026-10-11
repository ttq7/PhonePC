package com.phonepc.explorer

import android.app.Activity
import android.app.ProgressDialog
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.phonepc.explorer.data.UpdateChecker
import com.phonepc.explorer.databinding.DialogUpdateBinding
import java.util.concurrent.Executors

/**
 * The whole "检查更新 -> 下载 -> 安装" flow, kept out of MainActivity.
 *
 * Network work happens on a single background thread; every dialog is created on
 * the main thread. Nothing is ever started automatically.
 */
object UpdateFlow {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun start(activity: Activity) {
        val progress = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.update_checking))
            setCancelable(false)
            show()
        }

        io.execute {
            val result = try {
                UpdateChecker.check(activity)
            } catch (e: Exception) {
                UpdateChecker.Check.Failed(e.message ?: e.javaClass.simpleName)
            }
            main.post {
                if (activity.isFinishing) return@post
                progress.dismiss()
                when (result) {
                    is UpdateChecker.Check.Newer -> showNewer(activity, result)
                    is UpdateChecker.Check.UpToDate -> info(
                        activity,
                        activity.getString(R.string.update_title),
                        activity.getString(R.string.update_latest, result.current)
                    )
                    is UpdateChecker.Check.NoRelease -> info(
                        activity,
                        activity.getString(R.string.update_title),
                        activity.getString(R.string.update_none, result.current)
                    )
                    is UpdateChecker.Check.Failed -> info(
                        activity,
                        activity.getString(R.string.update_failed_title),
                        result.reason + "\n\n" + activity.getString(R.string.update_failed_hint)
                    )
                }
            }
        }
    }

    private fun showNewer(activity: Activity, r: UpdateChecker.Check.Newer) {
        val rel = r.release
        val vb = DialogUpdateBinding.inflate(LayoutInflater.from(activity))
        vb.updVersion.text = activity.getString(R.string.update_version, r.current, rel.tag)
        vb.updSize.text = if (rel.apkSize > 0)
            activity.getString(R.string.update_size, rel.apkSize / 1024 / 1024) else ""
        vb.updNotes.text = rel.notes.ifBlank { activity.getString(R.string.update_no_notes) }

        val hasApk = rel.apkUrl.isNotBlank()
        val builder = AlertDialog.Builder(activity, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.update_available)
            .setView(vb.root)
            .setNegativeButton(R.string.cancel, null)
        if (hasApk) {
            builder.setPositiveButton(R.string.update_download) { _, _ -> download(activity, rel) }
        } else {
            vb.updNotes.append("\n\n" + activity.getString(R.string.update_no_apk))
        }
        builder.show()
    }

    private fun download(activity: Activity, rel: UpdateChecker.Release) {
        val pd = ProgressDialog(activity).apply {
            setTitle(R.string.update_downloading)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        io.execute {
            try {
                val apk = UpdateChecker.download(activity, rel) { pct ->
                    main.post { if (pct in 0..100) pd.progress = pct }
                }
                main.post {
                    if (activity.isFinishing) return@post
                    pd.dismiss()
                    try {
                        UpdateChecker.install(activity, apk)
                    } catch (e: Exception) {
                        info(activity, activity.getString(R.string.update_failed_title),
                            activity.getString(R.string.update_install_failed, e.message ?: ""))
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (activity.isFinishing) return@post
                    pd.dismiss()
                    info(activity, activity.getString(R.string.update_failed_title),
                        activity.getString(R.string.update_download_failed, e.message ?: ""))
                }
            }
        }
    }

    private fun info(activity: Activity, title: String, message: String) {
        AlertDialog.Builder(activity, R.style.Theme_PhonePC_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun toast(activity: Activity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
