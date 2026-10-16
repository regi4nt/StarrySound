package com.fxsoundlite.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves a YouTube track's audio to Music/StarrySound on the device. The stream URL is resolved the
 * same way playback does it (NewPipeExtractor) and then fetched by the app itself in ranged chunks:
 * YouTube's CDN often answers a plain full-file request (what the system DownloadManager sends)
 * with 403 / throttling, which made downloads fail silently. Progress shows in a notification.
 *
 * Device tracks are already files on the phone, so only YouTube tracks can be downloaded.
 */
object SongDownloader {

    private const val TAG = "SongDownloader"
    private const val FOLDER = "StarrySound"
    private const val CHANNEL_ID = "starrysound_download_channel"
    private const val CHUNK = 1024L * 1024L
    private const val REQ_STORAGE = 4711
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    // Two at a time; the rest wait in line.
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val notifIds = AtomicInteger(5000)

    fun canDownload(t: Track) = t.source == Track.SOURCE_YOUTUBE && t.remoteId.isNotBlank()

    fun download(context: Context, track: Track) {
        val app = context.applicationContext
        if (!canDownload(track)) {
            toast(app, R.string.np_download_local)
            return
        }
        // Android 8-9 still needs the classic storage permission to write into the Music folder.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val act = findActivity(context)
            if (act != null) {
                ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            }
            toast(app, R.string.np_download_permission)
            return
        }
        toast(app, R.string.np_download_preparing)
        val notifId = notifIds.incrementAndGet()
        io.execute {
            try {
                run(app, track, notifId)
            } catch (e: IOException) {
                Log.w(TAG, "download failed (network)", e)
                finishNotification(app, notifId, track.title, false)
                toast(app, R.string.np_download_network)
            } catch (e: Throwable) {
                Log.w(TAG, "download failed", e)
                finishNotification(app, notifId, track.title, false)
                toast(app, R.string.np_download_failed)
            }
        }
    }

    private fun run(app: Context, track: Track, notifId: Int) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${track.remoteId}")
        val usable = originalTrackOnly(info.audioStreams.filter { it.isUrl && !it.content.isNullOrBlank() })
        // Prefer M4A (plays everywhere, including the system music apps), best bitrate.
        val best = usable.filter { it.format?.suffix.equals("m4a", true) }.ifEmpty { usable }
            .maxByOrNull { it.averageBitrate }
        if (best == null) {
            toast(app, R.string.np_download_failed)
            return
        }
        val ext = best.format?.suffix?.takeIf { it.isNotBlank() } ?: "m4a"
        val mime = best.format?.mimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4"
        val name = fileName(track) + "." + ext

        toast(app, R.string.np_download_started)
        showProgress(app, notifId, track.title, 0, true)

        val sink = openSink(app, name, mime, track)
        var ok = false
        try {
            sink.out.use { out -> fetch(app, best.content, out, notifId, track.title) }
            ok = true
        } finally {
            if (ok) sink.commit() else sink.abort()
        }
        finishNotification(app, notifId, track.title, true)
        toast(app, R.string.np_download_done)
    }

    /** Copies [url] into [out] in 1 MB ranged requests (falls back to one plain request). */
    private fun fetch(app: Context, url: String, out: OutputStream, notifId: Int, title: String) {
        var start = 0L
        var total = -1L
        var lastPercent = -1
        val buf = ByteArray(32 * 1024)
        while (total < 0 || start < total) {
            val end = start + CHUNK - 1
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Range", "bytes=$start-$end")
                val code = conn.responseCode
                when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        // "bytes 0-1048575/3456789"
                        total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                            ?: throw IOException("no Content-Range")
                    }
                    HttpURLConnection.HTTP_OK -> {
                        if (start != 0L) throw IOException("server ignored Range")
                        total = conn.contentLengthLong // may be -1: then read until EOF
                        copy(conn, out, buf)
                        return
                    }
                    else -> throw IOException("HTTP $code")
                }
                copy(conn, out, buf)
                start = minOf(end + 1, total)
            } finally {
                conn.disconnect()
            }
            if (total > 0) {
                val pct = (start * 100 / total).toInt()
                if (pct != lastPercent) {
                    lastPercent = pct
                    showProgress(app, notifId, title, pct, false)
                }
            }
        }
    }

    private fun copy(conn: HttpURLConnection, out: OutputStream, buf: ByteArray) {
        conn.inputStream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }

    // ------------------------------------------------------------------ storage

    private class Sink(val out: OutputStream, val commit: () -> Unit, val abort: () -> Unit)

    private fun openSink(app: Context, name: String, mime: String, t: Track): Sink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.TITLE, t.title)
                put(MediaStore.Audio.Media.IS_MUSIC, 1) // so it shows up in the Device tab
                if (t.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, t.artist)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            val out = resolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
            return Sink(
                out,
                commit = {
                    val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                    MusicLibrary.notifyChanged() // the Device tab picks the new song up
                },
                abort = { try { resolver.delete(uri, null, null) } catch (_: Exception) { } }
            )
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val file = File(dir, name)
        return Sink(
            file.outputStream(),
            commit = {
                MediaScannerConnection.scanFile(app, arrayOf(file.absolutePath), arrayOf(mime)) { _, _ ->
                    MusicLibrary.notifyChanged()
                }
            },
            abort = { file.delete() }
        )
    }

    // ------------------------------------------------------------------ notification

    private fun nm(app: Context): NotificationManager? {
        val m = app.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            m.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, app.getString(R.string.np_download_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return m
    }

    private fun showProgress(app: Context, id: Int, title: String, percent: Int, indeterminate: Boolean) {
        try {
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(app.getString(R.string.np_download_notif_running))
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm(app)?.notify(id, n)
        } catch (_: Exception) { } // notifications may be blocked; the download itself carries on
    }

    private fun finishNotification(app: Context, id: Int, title: String, success: Boolean) {
        try {
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(app.getString(if (success) R.string.np_download_done else R.string.np_download_failed))
                .setAutoCancel(true)
                .build()
            nm(app)?.notify(id, n)
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ helpers

    private fun findActivity(c: Context): Activity? {
        var ctx: Context? = c
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun fileName(t: Track): String {
        val raw = if (t.artist.isBlank()) t.title else "${t.artist} - ${t.title}"
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim()
        return clean.take(100).ifBlank { "song_${t.remoteId}" }
    }

    private fun toast(context: Context, res: Int) {
        main.post { Toast.makeText(context, res, Toast.LENGTH_LONG).show() }
    }
}
