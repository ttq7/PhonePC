package com.phonepc.explorer.net

import java.io.File

/**
 * Process-wide holder so the LAN share keeps serving while the user navigates
 * around the app instead of dying with one screen.
 */
object NetShare {

    @Volatile
    private var server: HttpFileServer? = null

    @Volatile
    var sharedDir: File? = null
        private set

    val isRunning: Boolean get() = server?.isRunning == true

    val port: Int get() = server?.port ?: 0

    /**
     * Address set in 设置. Auto-detection only knows this phone's own Wi-Fi
     * address, which is wrong behind a hotspot or a fixed port, so a user value
     * wins when present.
     */
    @Volatile
    var urlOverride: String = ""
        private set

    val url: String?
        get() {
            if (!isRunning) return null
            if (urlOverride.isNotBlank()) return urlOverride
            val ip = HttpFileServer.localAddresses().firstOrNull() ?: return null
            return "http://$ip:$port/"
        }

    fun start(dir: File, override: String = ""): Int {
        stop()
        urlOverride = override.trim()
        val s = HttpFileServer({ dir })
        val p = s.start()
        server = s
        sharedDir = dir
        return p
    }

    fun stop() {
        server?.stop()
        server = null
        sharedDir = null
    }
}
