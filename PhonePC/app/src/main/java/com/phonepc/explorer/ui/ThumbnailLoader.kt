package com.phonepc.explorer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.phonepc.explorer.model.FsEntry
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Loads real image / video thumbnails into icon views.
 *
 * The shell icon is shown immediately and replaced when the thumbnail is
 * decoded, so scrolling never blocks. An LRU cache keyed by path + size keeps
 * repeated binds free, and the view tag guards against a decode landing on a
 * recycled row.
 */
class ThumbnailLoader(cacheBytes: Int = DEFAULT_CACHE_BYTES) {

    private enum class Kind { IMAGE, VIDEO, NONE }

    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun into(view: ImageView, entry: FsEntry, sizePx: Int, shellIcon: Int) {
        val kind = kindOf(entry)
        if (kind == Kind.NONE || sizePx <= 0) {
            view.tag = null
            view.setImageResource(shellIcon)
            return
        }
        val key = entry.path + "@" + sizePx
        view.tag = key

        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(shellIcon)

        if (!inFlight.add(key)) return
        val path = entry.path
        pool.execute {
            val bmp = try {
                when (kind) {
                    Kind.IMAGE -> decodeImage(path, sizePx)
                    Kind.VIDEO -> decodeVideo(path, sizePx)
                    Kind.NONE -> null
                }
            } catch (_: Throwable) {
                null
            }
            inFlight.remove(key)
            if (bmp != null) cache.put(key, bmp)
            main.post {
                if (bmp != null && view.tag == key) view.setImageBitmap(bmp)
            }
        }
    }

    fun clear() {
        cache.evictAll()
    }

    private fun kindOf(e: FsEntry): Kind {
        if (e.isDir) return Kind.NONE
        return when (e.ext.lowercase()) {
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif",
            "tif", "tiff", "ico" -> Kind.IMAGE
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v",
            "mpg", "mpeg", "ts" -> Kind.VIDEO
            else -> Kind.NONE
        }
    }

    private fun decodeImage(path: String, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeVideo(path: String, sizePx: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val longest = maxOf(frame.width, frame.height)
            if (longest <= sizePx) return frame
            val scale = sizePx.toFloat() / longest
            val w = (frame.width * scale).toInt().coerceAtLeast(1)
            val h = (frame.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
            if (scaled != frame) frame.recycle()
            return scaled
        } finally {
            try {
                mmr.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var maxDim = maxOf(w, h)
        while (maxDim / 2 >= target) {
            maxDim /= 2
            sample *= 2
        }
        return sample
    }

    companion object {
        private const val DEFAULT_CACHE_BYTES = 24 * 1024 * 1024
    }
}
