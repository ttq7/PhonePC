package com.phonepc.explorer.net

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A small HTTP server that exposes one folder to the local network, so a PC
 * browser can browse, download and upload files without any cable or app.
 *
 * Deliberately dependency-free: raw ServerSocket + minimal HTTP/1.1.
 * Handles: directory listing, file download (with Range), and PUT upload.
 * There is no authentication – it is meant for a trusted LAN.
 */
class HttpFileServer(
    private val rootProvider: () -> File,
    private val preferredPort: Int = 8080
) {

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    fun start(): Int {
        if (running.get()) return port
        var socket: ServerSocket? = null
        var chosen = 0
        for (p in preferredPort until preferredPort + 10) {
            try {
                socket = ServerSocket(p)
                chosen = p
                break
            } catch (_: Exception) {
            }
        }
        if (socket == null) throw IllegalStateException("无法监听端口（8080-8089 都被占用）")
        serverSocket = socket
        port = chosen
        running.set(true)

        acceptThread = Thread {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                pool.execute { handle(client) }
            }
        }.also { it.isDaemon = true; it.start() }
        return chosen
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
    }

    // ---- request handling -------------------------------------------------

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 15000
                val input = c.getInputStream().buffered()
                val output = BufferedOutputStream(c.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val rawTarget = parts[1]

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                            line.substring(idx + 1).trim()
                    }
                }

                val path = URLDecoder.decode(rawTarget.substringBefore('?'), "UTF-8")

                when {
                    method == "PUT" && path.startsWith("/__upload/") ->
                        handleUpload(input, output, headers, path.removePrefix("/__upload/"))

                    method == "GET" || method == "HEAD" ->
                        handleGet(output, headers, path, method == "HEAD")

                    else -> sendError(output, 405, "Method Not Allowed")
                }
            }
        } catch (_: Exception) {
            // a dropped connection is normal
        }
    }

    private fun resolve(path: String): File? {
        val root = rootProvider().canonicalFile
        val clean = path.trimStart('/')
        val target = File(root, clean).canonicalFile
        // never escape the shared folder
        return if (target.path == root.path || target.path.startsWith(root.path + File.separator)) target
        else null
    }

    private fun handleGet(output: OutputStream, headers: Map<String, String>, path: String, headOnly: Boolean) {
        val target = resolve(path)
        if (target == null) {
            sendError(output, 403, "Forbidden")
            return
        }
        if (!target.exists()) {
            sendError(output, 404, "Not Found")
            return
        }
        if (target.isDirectory) {
            val html = listingHtml(target, path)
            val bytes = html.toByteArray(Charsets.UTF_8)
            writeHeaders(output, 200, "OK", "text/html; charset=utf-8", bytes.size.toLong(), null)
            if (!headOnly) output.write(bytes)
            output.flush()
            return
        }
        serveFile(output, target, headers, headOnly)
    }

    private fun serveFile(output: OutputStream, file: File, headers: Map<String, String>, headOnly: Boolean) {
        val length = file.length()
        var start = 0L
        var end = length - 1
        var status = 200
        var statusText = "OK"

        val range = headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash).trim()
                val b = spec.substring(dash + 1).trim()
                try {
                    if (a.isEmpty()) {
                        val n = b.toLong()
                        start = (length - n).coerceAtLeast(0)
                    } else {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = b.toLong().coerceAtMost(length - 1)
                    }
                    if (start in 0 until length && end >= start) {
                        status = 206
                        statusText = "Partial Content"
                    } else {
                        start = 0; end = length - 1
                    }
                } catch (_: Exception) {
                    start = 0; end = length - 1
                }
            }
        }

        val count = end - start + 1
        val extra = StringBuilder()
        if (status == 206) extra.append("Content-Range: bytes $start-$end/$length\r\n")
        extra.append("Accept-Ranges: bytes\r\n")
        extra.append("Last-Modified: ${httpDate(file.lastModified())}\r\n")

        writeHeaders(output, status, statusText, mimeOf(file.name), count, extra.toString())
        if (headOnly) {
            output.flush()
            return
        }
        file.inputStream().use { input ->
            if (start > 0) input.skip(start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                output.write(buf, 0, n)
                left -= n
            }
        }
        output.flush()
    }

    private fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        name: String
    ) {
        val root = rootProvider().canonicalFile
        val safe = File(name).name // strip any path component
        if (safe.isEmpty() || safe.startsWith(".")) {
            sendError(output, 400, "Bad name")
            return
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length <= 0) {
            sendError(output, 411, "Length Required")
            return
        }
        if (length > MAX_UPLOAD) {
            sendError(output, 413, "Too large")
            return
        }
        val target = File(root, safe)
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        val body = "OK ${target.name}".toByteArray()
        writeHeaders(output, 200, "OK", "text/plain; charset=utf-8", body.size.toLong(), null)
        output.write(body)
        output.flush()
    }

    // ---- response helpers -------------------------------------------------

    private fun writeHeaders(
        output: OutputStream,
        code: Int,
        text: String,
        contentType: String,
        length: Long,
        extra: String?
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $text\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: $length\r\n")
        sb.append("Connection: close\r\n")
        if (extra != null) sb.append(extra)
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendError(output: OutputStream, code: Int, text: String) {
        val body = "$code $text".toByteArray()
        writeHeaders(output, code, text, "text/plain; charset=utf-8", body.size.toLong(), null)
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun httpDate(millis: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(Date(if (millis > 0) millis else System.currentTimeMillis()))

    private fun mimeOf(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.US))
            ?: "application/octet-stream"

    private fun listingHtml(dir: File, urlPath: String): String {
        val base = if (urlPath.endsWith("/")) urlPath else "$urlPath/"
        val rootPath = rootProvider().canonicalPath
        val prettyDir = dir.canonicalPath.removePrefix(rootPath).ifEmpty { "/" }
        val sb = StringBuilder()
        sb.append(
            """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${escape(dir.name.ifEmpty { "此手机" })}</title>
            <style>
            body{font-family:"Segoe UI","Microsoft YaHei UI",sans-serif;margin:0;background:#f3f3f3;color:#1b1b1b}
            header{background:#fff;padding:14px 18px;border-bottom:1px solid #e5e5e5}
            h1{font-size:16px;margin:0 0 4px}
            .p{font-size:12px;color:#616161}
            table{border-collapse:collapse;width:100%;background:#fff}
            td,th{padding:9px 18px;border-bottom:1px solid #eee;font-size:13px;text-align:left}
            th{color:#616161;font-weight:500;font-size:12px}
            a{color:#0067c0;text-decoration:none}
            a:hover{text-decoration:underline}
            .sz{color:#616161;white-space:nowrap}
            .tools{padding:14px 18px;background:#fff;border-top:1px solid #e5e5e5}
            button{padding:7px 16px;border:1px solid #d0d0d0;background:#fafafa;border-radius:4px;cursor:pointer}
            </style></head><body>
            <header><h1>此手机 · 局域网共享</h1>
            <div class="p">${escape(prettyDir)} &nbsp;·&nbsp; 共享目录：${escape(rootPath)}</div></header>
            <table><tr><th>名称</th><th class="sz">大小</th><th class="sz">修改时间</th></tr>
            """.trimIndent()
        )
        if (urlPath != "/") {
            sb.append("<tr><td><a href=\"../\">⬆ 返回上一级</a></td><td></td><td></td></tr>")
        }
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ).orEmpty()
        for (f in children) {
            val href = base + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20") +
                    (if (f.isDirectory) "/" else "")
            val size = if (f.isDirectory) "—" else humanSize(f.length())
            val time = if (f.lastModified() > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
            } else ""
            sb.append("<tr><td><a href=\"$href\">${if (f.isDirectory) "📁 " else "📄 "}${escape(f.name)}</a></td>")
            sb.append("<td class=\"sz\">$size</td><td class=\"sz\">$time</td></tr>")
        }
        if (children.isEmpty()) {
            sb.append("<tr><td colspan=3 style='color:#888'>（空文件夹）</td></tr>")
        }
        sb.append(
            """
            </table>
            <div class="tools">
            <input type="file" id="f" multiple style="font-size:13px">
            <button onclick="up()">上传到此处</button>
            <span id="s" style="margin-left:10px;font-size:12px;color:#616161"></span>
            </div>
            <script>
            async function up(){
              const fs=document.getElementById('f').files, s=document.getElementById('s');
              if(!fs.length){s.textContent='请先选择文件';return;}
              for(let i=0;i<fs.length;i++){
                s.textContent='上传中 '+(i+1)+'/'+fs.length+' …';
                await fetch('__upload/'+encodeURIComponent(fs[i].name),{method:'PUT',body:fs[i]});
              }
              location.reload();
            }
            </script></body></html>
            """.trimIndent()
        )
        return sb.toString()
    }

    private fun humanSize(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val MAX_UPLOAD = 4L * 1024 * 1024 * 1024

        /** Non-loopback IPv4 addresses, best-effort. */
        fun localAddresses(): List<String> {
            val out = ArrayList<String>()
            try {
                val nifs = NetworkInterface.getNetworkInterfaces() ?: return out
                while (nifs.hasMoreElements()) {
                    val nif = nifs.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (!a.isLoopbackAddress && a is Inet4Address) {
                            a.hostAddress?.let { out.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            // prefer typical Wi-Fi ranges first
            return out.sortedBy { if (it.startsWith("192.168.") || it.startsWith("10.")) 0 else 1 }
        }
    }
}
