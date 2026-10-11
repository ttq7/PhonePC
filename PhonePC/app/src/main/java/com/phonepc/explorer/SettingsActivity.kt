package com.phonepc.explorer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.phonepc.explorer.data.AppSettings
import com.phonepc.explorer.data.UpdateChecker
import com.phonepc.explorer.databinding.ActivitySettingsBinding
import com.phonepc.explorer.databinding.DialogApiBinding
import com.phonepc.explorer.databinding.DialogInputBinding
import com.phonepc.explorer.net.HttpFileServer

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        com.phonepc.explorer.data.AppSettings.init(this)

        b.btnBack.setOnClickListener { finish() }
        b.rowUpdate.setOnClickListener { UpdateFlow.start(this) }
        b.rowPlugins.setOnClickListener { showPlugins() }
        b.rowShare.setOnClickListener { editShareUrl() }
        b.rowApi.setOnClickListener { editApi() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        b.updSummary.text = getString(R.string.settings_update_summary, UpdateChecker.installedVersion(this))

        val custom = AppSettings.shareUrl
        b.shareSummary.text = if (custom.isBlank()) {
            val auto = HttpFileServer.localAddresses().firstOrNull()
            if (auto != null) getString(R.string.settings_share_auto, auto)
            else getString(R.string.settings_share_auto_none)
        } else {
            getString(R.string.settings_share_custom, custom)
        }

        b.apiSummary.text = getString(R.string.settings_api_summary, AppSettings.apiSummary(this))
    }

    // ---- plugins ----------------------------------------------------------

    private fun showPlugins() {
        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.settings_plugins)
            .setMessage(R.string.settings_plugins_detail)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---- share address ----------------------------------------------------

    /**
     * Blank means auto-detect. The share screen only knows the phone's own
     * Wi-Fi address, which is wrong behind a hotspot or when a fixed port is
     * needed, so this exists as an escape hatch.
     */
    private fun editShareUrl() {
        val vb = DialogInputBinding.inflate(LayoutInflater.from(this))
        vb.inputField.hint = "http://192.168.1.5:8080/"
        vb.inputField.setText(AppSettings.shareUrl)
        val dlg = AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.settings_share)
            .setMessage(R.string.settings_share_detail)
            .setView(vb.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.settings_share_reset, null)
            .setPositiveButton(R.string.ok, null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = vb.inputField.text.toString().trim()
            if (v.isNotEmpty() && !v.startsWith("http")) {
                toast(getString(R.string.settings_share_bad, v))
            } else {
                AppSettings.shareUrl = v
                dlg.dismiss()
                refresh()
            }
        }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AppSettings.shareUrl = ""
            dlg.dismiss()
            refresh()
        }
    }

    // ---- remote search api ------------------------------------------------

    private fun editApi() {
        val vb = DialogApiBinding.inflate(LayoutInflater.from(this))
        vb.apiOn.isChecked = AppSettings.apiEnabled
        vb.apiEndpoint.setText(AppSettings.apiEndpoint)
        vb.apiModel.setText(AppSettings.apiModel)
        vb.apiKey.setText(AppSettings.apiKey)

        AlertDialog.Builder(this, R.style.Theme_PhonePC_Dialog)
            .setTitle(R.string.settings_api)
            .setView(vb.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                AppSettings.apiEnabled = vb.apiOn.isChecked
                AppSettings.apiEndpoint = vb.apiEndpoint.text.toString().trim()
                AppSettings.apiModel = vb.apiModel.text.toString().trim()
                AppSettings.apiKey = vb.apiKey.text.toString().trim()
                refresh()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context) = android.content.Intent(context, SettingsActivity::class.java)
    }
}
