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

/**
 * Tiny thumbnail loader for the lists: 4 worker threads + an in-memory cache limited by bytes (not by count, so a
 * few big covers cannot push the app out of memory). The same url requested by several rows at once is downloaded
 * once, and every row gets its callback.
 */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // Newest request first: rows the user scrolled to a moment ago must not wait behind ones already scrolled past
    // (matters on a slow connection, where the queue grows faster than it empties).
    private class LifoQueue : java.util.concurrent.LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        4, 4, 30L, java.util.concurrent.TimeUnit.SECONDS, LifoQueue()
    ).apply { allowCoreThreadTimeOut(true) }
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

    /** Preview picture of a device video: MediaStore / provider thumbnail, else a frame of the file. Cached like covers. */
    fun loadVideoThumb(ctx: Context, track: Track, onLoaded: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        run("video:" + track.remoteId, onLoaded) { videoThumb(app, track) }
    }

    private fun videoThumb(ctx: Context, track: Track): Bitmap? {
        val uri = android.net.Uri.parse(track.remoteId)
        if (Build.VERSION.SDK_INT >= 29) {
            try { return ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null) } catch (e: Exception) { android.util.Log.d("ImageLoader", "ignored: " + e) }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val f = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0L)
            f?.let { b -> if (b.width > 320) Bitmap.createScaledBitmap(b, 320, (b.height * 320f / b.width).toInt().coerceAtLeast(1), true) else b }
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { android.util.Log.d("ImageLoader", "ignored: " + e) }
        }
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
