package com.fxsoundlite.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Tiny thumbnail loader for the lists: 4 worker threads + an in-memory cache limited by bytes (not by count, so a
 * few big covers cannot push the app out of memory). The same url requested by several rows at once is downloaded
 * once, and every row gets its callback.
 */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    // Main thread only: url -> callbacks waiting for the download that is already running.
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** [onLoaded] runs on the main thread; null when the image could not be fetched. Call from the main thread. */
    fun load(url: String, onLoaded: (Bitmap?) -> Unit) = run(url, onLoaded) { Http.bitmap(url) }

    /** Cover of a device song (MediaStore thumbnail, or the picture embedded in the file). Cached like web covers. */
    fun loadLocal(ctx: Context, track: Track, onLoaded: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        run("local:" + track.id, onLoaded) { localCover(app, track) }
    }

    private fun localCover(ctx: Context, track: Track): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try { return ctx.contentResolver.loadThumbnail(track.uri, Size(256, 256), null) } catch (e: Exception) { android.util.Log.d("ImageLoader", "ignored: " + e) }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, track.uri)
            r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { android.util.Log.d("ImageLoader", "ignored: " + e) }
        }
    }

    private fun run(key: String, onLoaded: (Bitmap?) -> Unit, fetch: () -> Bitmap?) {
        cache.get(key)?.let { onLoaded(it); return }
        val list = waiting[key]
        if (list != null) { list.add(onLoaded); return }
        waiting[key] = mutableListOf(onLoaded)
        try {
            pool.execute {
                // Throwable (e.g. OutOfMemoryError while decoding) must not leave the key stuck in [waiting]
                // forever, otherwise that cover would never load again until the app restarts.
                val bmp = try { fetch() } catch (_: Throwable) { null }
                if (bmp != null) cache.put(key, bmp)
                main.post { waiting.remove(key)?.forEach { it(bmp) } }
            }
        } catch (e: Exception) {
            waiting.remove(key)
        }
    }
}
