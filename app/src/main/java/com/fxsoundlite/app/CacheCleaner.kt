package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebStorage
import android.webkit.WebView
import java.io.File

/**
 * Frees disk space that Android's own "Clear cache" button misses: the hidden YouTube WebView keeps
 * its media / service-worker / storage files in the data folder, and the old ML Kit translation
 * models (no longer used) sit there too. Playlists, history, settings and logins are NOT touched.
 */
object CacheCleaner {

    private const val AUTO_TRIM_BYTES = 50L * 1024 * 1024

    private fun dirSize(f: File?): Long =
        if (f == null || !f.exists()) 0L else if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    private fun wipe(dir: File?) { dir?.listFiles()?.forEach { it.deleteRecursively() } }

    private fun mlKitDirs(c: Context): List<File> =
        listOfNotNull(c.filesDir, c.noBackupFilesDir).flatMap { root ->
            root.listFiles { f -> f.name.startsWith("com.google.mlkit") }?.toList().orEmpty()
        }

    private fun webDataDirs(c: Context): List<File> {
        val base = File(c.dataDir, "app_webview")
        val names = listOf("Service Worker", "Cache Storage", "IndexedDB", "Local Storage", "GPUCache",
            "Code Cache", "blob_storage", "File System", "Session Storage", "databases", "VideoDecodeStats")
        return names.flatMap { n -> listOf(File(base, n), File(File(base, "Default"), n)) }.filter { it.exists() }
    }

    /** Bytes that [clear] would free. Blocking (reads the file tree). */
    fun size(c: Context): Long =
        dirSize(c.cacheDir) + dirSize(c.externalCacheDir) + dirSize(File(c.dataDir, "code_cache")) +
            mlKitDirs(c).sumOf { dirSize(it) } + webDataDirs(c).sumOf { dirSize(it) }

    /** At app start: drop the unused ML Kit models, and an oversized cache (nothing is open yet). */
    fun autoTrim(c: Context) {
        try {
            mlKitDirs(c).forEach { it.deleteRecursively() }
            // Everything in the cache except the Drive songs is wiped when oversized; the songs are trimmed to their own limit (LRU).
            val driveDir = File(c.cacheDir, "drive_music")
            val others = (c.cacheDir.listFiles() ?: emptyArray()).filter { it != driveDir }
            if (others.sumOf { dirSize(it) } > AUTO_TRIM_BYTES) others.forEach { it.deleteRecursively() }
            DriveMusic.trimToLimit(c)
        } catch (_: Exception) { }
    }

    /** Clears everything listed above on a background thread; [onDone] runs on the main thread with the freed bytes. */
    fun clear(c: Context, onDone: (Long) -> Unit) {
        val app = c.applicationContext
        val main = Handler(Looper.getMainLooper())
        // WebView's own APIs first (main thread), then delete what is left on disk.
        try {
            WebStorage.getInstance().deleteAllData()
            WebView(app).also { it.clearCache(true); it.destroy() }
        } catch (_: Throwable) { }
        Thread {
            val before = size(app)
            try {
                wipe(app.cacheDir); wipe(app.externalCacheDir); wipe(File(app.dataDir, "code_cache"))
                mlKitDirs(app).forEach { it.deleteRecursively() }
                webDataDirs(app).forEach { it.deleteRecursively() }
            } catch (_: Exception) { }
            val freed = (before - size(app)).coerceAtLeast(0L)
            main.post { onDone(freed) }
        }.start()
    }
}
