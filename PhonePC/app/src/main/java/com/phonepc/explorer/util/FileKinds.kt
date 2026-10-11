package com.phonepc.explorer.util

import java.io.File
import java.util.Locale

/** Classifies a file by name so the built-in viewer knows what to show. */
object FileKinds {

    enum class Kind { IMAGE, VIDEO, AUDIO, TEXT, OTHER }

    private val IMAGE = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "avif",
        "ico", "tif", "tiff", "svg"
    )
    private val VIDEO = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v",
        "mpg", "mpeg", "ts", "rmvb", "vob"
    )
    private val AUDIO = setOf(
        "mp3", "wav", "flac", "aac", "m4a", "ogg", "wma", "opus", "amr",
        "mid", "midi", "aiff", "ape", "oga"
    )
    private val TEXT = setOf(
        "txt", "log", "md", "markdown", "json", "xml", "yml", "yaml", "toml",
        "ini", "cfg", "conf", "properties", "csv", "tsv",
        "html", "htm", "css", "js", "mjs", "ts", "jsx", "tsx",
        "kt", "kts", "java", "py", "c", "h", "cpp", "cc", "hpp", "cs",
        "go", "rs", "rb", "php", "swift", "lua", "pl", "r", "sql",
        "sh", "bash", "zsh", "bat", "cmd", "ps1", "gradle", "pro",
        "srt", "ass", "vtt", "sub", "eml", "vcf", "ics", "gitignore", "env"
    )

    fun of(name: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            in IMAGE -> Kind.IMAGE
            in VIDEO -> Kind.VIDEO
            in AUDIO -> Kind.AUDIO
            in TEXT -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    fun isImage(name: String) = of(name) == Kind.IMAGE

    /**
     * Text files are only worth handing to the editor when they really are
     * text – a NUL byte in the first block means binary.
     */
    fun isProbablyText(file: File, probe: Int = 8192): Boolean {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(probe)
                val n = input.read(buf)
                if (n <= 0) return true
                for (i in 0 until n) if (buf[i] == 0.toByte()) return false
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
