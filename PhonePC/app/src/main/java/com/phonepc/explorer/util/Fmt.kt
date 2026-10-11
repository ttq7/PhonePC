package com.phonepc.explorer.util

import com.phonepc.explorer.model.FsEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {

    private val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    fun date(millis: Long): String =
        if (millis <= 0L) "" else dateFmt.format(Date(millis))

    /** Windows-style size string: "832 字节", "1.23 MB", "4.00 GB". */
    fun size(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes 字节"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble() / 1024.0
        var idx = 0
        while (value >= 1024.0 && idx < units.size - 1) {
            value /= 1024.0
            idx++
        }
        return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.2f %s", value, units[idx])
    }

    /** Coarse Windows-style file type name, keyed by extension. */
    fun typeName(entry: FsEntry): String {
        if (entry.isDir) {
            if (entry.childCount >= 0) return "文件夹（${entry.childCount} 个项目）"
            return "文件夹"
        }
        val e = entry.ext.lowercase(Locale.US)
        return TYPES[e] ?: if (e.isEmpty()) "文件" else "${e.uppercase(Locale.US)} 文件"
    }

    private val TYPES: Map<String, String> = mapOf(
        "txt" to "文本文档", "log" to "文本文档", "ini" to "配置设置", "cfg" to "配置设置",
        "md" to "Markdown 文档", "rtf" to "RTF 文档", "csv" to "逗号分隔值文件",
        "json" to "JSON 文件", "xml" to "XML 文档", "html" to "HTML 文档", "htm" to "HTML 文档",
        "js" to "JScript 文件", "ts" to "TypeScript 文件", "kt" to "Kotlin 文件",
        "java" to "Java 源文件", "py" to "Python 源文件", "c" to "C 源文件", "cpp" to "C++ 源文件",
        "cs" to "C# 源文件", "sh" to "Shell 脚本", "bat" to "批处理文件", "sql" to "SQL 文件",
        "yml" to "YAML 文件", "yaml" to "YAML 文件",
        "jpg" to "JPG 图片文件", "jpeg" to "JPEG 图片文件", "png" to "PNG 图片文件",
        "gif" to "GIF 图片文件", "bmp" to "BMP 图片文件", "webp" to "WEBP 图片文件",
        "heic" to "HEIC 图片文件", "svg" to "SVG 图片文件", "psd" to "PSD 图片文件",
        "mp3" to "MP3 音频文件", "wav" to "WAV 音频文件", "flac" to "FLAC 音频文件",
        "aac" to "AAC 音频文件", "m4a" to "M4A 音频文件", "ogg" to "OGG 音频文件",
        "wma" to "WMA 音频文件",
        "mp4" to "MP4 视频文件", "mkv" to "MKV 视频文件", "avi" to "AVI 视频文件",
        "mov" to "MOV 视频文件", "wmv" to "WMV 视频文件", "flv" to "FLV 视频文件",
        "webm" to "WEBM 视频文件", "3gp" to "3GP 视频文件",
        "pdf" to "PDF 文档", "doc" to "Microsoft Word 97-2003 文档", "docx" to "Microsoft Word 文档",
        "xls" to "Microsoft Excel 97-2003 工作表", "xlsx" to "Microsoft Excel 工作表",
        "ppt" to "Microsoft PowerPoint 97-2003 演示文稿", "pptx" to "Microsoft PowerPoint 演示文稿",
        "one" to "Microsoft OneNote 分区",
        "zip" to "压缩文件夹", "rar" to "压缩文件", "7z" to "压缩文件",
        "tar" to "压缩文件", "gz" to "压缩文件", "iso" to "光盘映像文件",
        "apk" to "Android 应用程序包", "exe" to "应用程序", "msi" to "Windows Installer 包",
        "dll" to "应用程序扩展", "sys" to "系统文件", "so" to "共享库",
        "lnk" to "快捷方式", "url" to "Internet 快捷方式",
        "ttf" to "TrueType 字体文件", "otf" to "OpenType 字体文件",
        "db" to "数据文件", "sqlite" to "SQLite 数据库",
        "vcf" to "vCard 联系人文件", "ics" to "iCalendar 日历文件", "eml" to "电子邮件",
        "xml.bak" to "备份文件", "bak" to "备份文件", "tmp" to "临时文件",
        "srt" to "字幕文件", "ass" to "字幕文件", "torrent" to "种子文件"
    )
}
