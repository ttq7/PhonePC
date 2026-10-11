package com.phonepc.explorer.data

import android.content.Context

/**
 * User-facing settings. Kept separate from [Prefs] (which holds view state)
 * so the two never get confused.
 */
object AppSettings {

    private const val FILE = "phonepc_settings"

    @Volatile
    private var appContext: Context? = null

    /**
     * Call once from each entry Activity. Storing the application context keeps
     * the property syntax below readable; failing loudly beats silently
     * returning a default when someone forgets.
     */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun context(): Context = appContext
        ?: throw IllegalStateException("AppSettings.init() was never called")

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- 文件共享 ---------------------------------------------------------

    /**
     * Override for the address shown on the share screen. Blank means
     * "auto-detect from the Wi-Fi interface", which is the common case; this is
     * for people behind a hotspot or who want a fixed port.
     */
    var shareUrl: String
        get() = sp(context()).getString("share_url", "") ?: ""
        set(v) = sp(context()).edit().putString("share_url", v).apply()

    // ---- 快捷搜索 API -----------------------------------------------------

    var apiEnabled: Boolean
        get() = sp(context()).getBoolean("api_enabled", false)
        set(v) = sp(context()).edit().putBoolean("api_enabled", v).apply()

    /** OpenAI-compatible chat-completions endpoint. */
    var apiEndpoint: String
        get() = sp(context()).getString("api_endpoint", "") ?: ""
        set(v) = sp(context()).edit().putString("api_endpoint", v).apply()

    var apiKey: String
        get() = sp(context()).getString("api_key", "") ?: ""
        set(v) = sp(context()).edit().putString("api_key", v).apply()

    var apiModel: String
        get() = sp(context()).getString("api_model", "") ?: ""
        set(v) = sp(context()).edit().putString("api_model", v).apply()

    fun isApiReady(context: Context): Boolean =
        apiEnabled && apiEndpoint.isNotBlank() && apiModel.isNotBlank()

    /** Never print the key itself; only whether one is present. */
    fun apiSummary(context: Context): String = when {
        !apiEnabled -> "已关闭"
        apiEndpoint.isBlank() -> "未填写接口地址"
        apiModel.isBlank() -> "未填写模型名"
        apiKey.isBlank() -> "$apiModel（未填密钥）"
        else -> apiModel
    }
}
