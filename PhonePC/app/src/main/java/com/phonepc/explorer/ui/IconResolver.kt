package com.phonepc.explorer.ui

import com.phonepc.explorer.R
import com.phonepc.explorer.model.FsEntry
import java.util.Locale

/**
 * Maps a file system entry to the authentic Windows shell artwork extracted
 * from this machine's own shell32.dll / imageres.dll.
 */
object IconResolver {

    fun forEntry(entry: FsEntry): Int {
        if (entry.isStorageRoot) return R.drawable.ic_drive
        if (entry.isDir) return forFolder(entry.name)
        return forExtension(entry.ext.lowercase(Locale.US))
    }

    /** Recognises the well-known Android media folders and gives them the
     *  matching Windows special-folder icon. */
    fun forFolder(name: String): Int = when (name.lowercase(Locale.US)) {
        "download", "downloads", "下载" -> R.drawable.ic_downloads
        "documents", "document", "文档" -> R.drawable.ic_documents
        "pictures", "picture", "images", "图片" -> R.drawable.ic_pictures
        "dcim", "camera", "相机" -> R.drawable.ic_pictures
        "music", "audio", "音乐" -> R.drawable.ic_music
        "movies", "movie", "video", "videos", "视频" -> R.drawable.ic_videos
        "ringtones", "podcasts" -> R.drawable.ic_music
        "notifications", "alarms", "recordings", "audiobooks" -> R.drawable.ic_folder
        "desktop", "桌面" -> R.drawable.ic_desktop
        "gallery", "图库" -> R.drawable.ic_gallery
        else -> R.drawable.ic_folder
    }

    fun forExtension(ext: String): Int = when (ext) {
        "txt", "log", "md", "markdown", "nfo", "lst" -> R.drawable.ic_file_text
        "ini", "cfg", "conf", "properties", "reg" -> R.drawable.ic_file_ini
        "rtf" -> R.drawable.ic_file_rtf
        "csv" -> R.drawable.ic_file_csv
        "json" -> R.drawable.ic_file_json
        "xml", "xsd", "plist" -> R.drawable.ic_file_xml
        "html", "htm", "mht", "mhtml" -> R.drawable.ic_file_html
        "js", "mjs", "ts", "kt", "kts", "java", "py", "c", "cpp", "cc", "h", "hpp",
        "cs", "go", "rs", "rb", "php", "swift", "gradle", "pro", "lua", "pl" ->
            R.drawable.ic_file_code
        "sh", "bash", "zsh", "bat", "cmd", "ps1" -> R.drawable.ic_file_bat
        "sql" -> R.drawable.ic_file_sql
        "yml", "yaml", "toml", "env" -> R.drawable.ic_file_yml
        "jpg", "jpeg" -> R.drawable.ic_file_jpg
        "png" -> R.drawable.ic_file_image
        "gif" -> R.drawable.ic_file_gif
        "bmp" -> R.drawable.ic_file_bmp
        "webp" -> R.drawable.ic_file_webp
        "heic", "heif" -> R.drawable.ic_file_heic
        "svg", "tif", "tiff", "raw", "ico", "avif" -> R.drawable.ic_file_image
        "psd", "ai", "xd", "sketch", "fig" -> R.drawable.ic_file_psd
        "mp3", "wav", "flac", "aac", "m4a", "ogg", "wma", "opus", "amr", "mid",
        "midi", "aiff", "ape" -> R.drawable.ic_file_audio
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "mpg",
        "mpeg", "rmvb", "ts", "vob" -> R.drawable.ic_file_video
        "pdf" -> R.drawable.ic_file_pdf
        "doc", "docx", "odt", "pages", "wps" -> R.drawable.ic_file_word
        "xls", "xlsx", "ods", "numbers", "et" -> R.drawable.ic_file_excel
        "ppt", "pptx", "odp", "key", "dps" -> R.drawable.ic_file_ppt
        "one", "onetoc2" -> R.drawable.ic_file_onenote
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst" -> R.drawable.ic_file_archive
        "iso", "img", "dmg", "vhd", "vmdk" -> R.drawable.ic_file_iso
        "apk", "aab", "xapk", "apks", "dex" -> R.drawable.ic_file_apk
        "exe", "com", "app", "bin", "run", "out", "elf" -> R.drawable.ic_file_exe
        "dll", "so", "dylib", "jar", "aar", "class" -> R.drawable.ic_file_dll
        "sys", "drv", "ko" -> R.drawable.ic_file_sys
        "ttf", "otf", "woff", "woff2", "fon", "ttc" -> R.drawable.ic_file_font
        "db", "sqlite", "sqlite3", "mdb", "realm", "dat" -> R.drawable.ic_file_db
        "lnk", "desktop" -> R.drawable.ic_file_lnk
        "url", "webloc", "website" -> R.drawable.ic_file_url
        "eml", "msg", "mbox", "oft" -> R.drawable.ic_file_eml
        "ics", "ical", "vcs" -> R.drawable.ic_file_ics
        "vcf", "vcard" -> R.drawable.ic_file
        "srt", "ass", "ssa", "vtt", "sub" -> R.drawable.ic_file_text
        "torrent" -> R.drawable.ic_file_archive
        else -> R.drawable.ic_file
    }
}
