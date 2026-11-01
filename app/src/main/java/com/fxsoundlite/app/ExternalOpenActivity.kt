package com.fxsoundlite.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * "Open with" target: tapping an audio or video file in a file manager (or any other app) offers StarrySound.
 * Audio goes to the music player (MusicService + full-screen player), video to the video player.
 * No screen of its own (translucent): it only reads the file's details, hands it over and closes.
 * The new screens are started in this same task, so the read permission the sender granted stays valid while they play.
 */
class ExternalOpenActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || uri == null) { finish(); return }
        // Keep the file readable after this screen is gone, when the sender allows it (not all do).
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { android.util.Log.d("ExternalOpen", "ignored: $e") }

        val video = isVideo(uri, intent.type)
        Thread {
            val track = buildTrack(applicationContext, uri, video)
            main.post { if (!isFinishing) open(track, video) }
        }.start()
    }

    private fun open(track: Track, video: Boolean) {
        PlayerMode.init(this)
        if (video) {
            MusicService.instance?.takeIf { it.isPlaying }?.pause() // video has its own sound
            startActivity(VideoPlayerActivity.intent(this, track, 0L, false))
            finish()
            return
        }
        // Audio: make sure the service runs, wait for it, queue the file and show the player.
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? MusicService.LocalBinder)?.getService()
                if (svc != null) {
                    svc.setQueueAndPlay(listOf(track), 0)
                    startActivity(Intent(this@ExternalOpenActivity, NowPlayingActivity::class.java))
                } else {
                    Toast.makeText(this@ExternalOpenActivity, R.string.external_open_failed, Toast.LENGTH_LONG).show()
                }
                release()
                finish()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        connection = conn
        val svcIntent = Intent(this, MusicService::class.java)
        try { ContextCompat.startForegroundService(this, svcIntent) } catch (e: Exception) { android.util.Log.d("ExternalOpen", "ignored: $e") }
        if (!applicationContext.bindService(svcIntent, conn, Context.BIND_AUTO_CREATE)) {
            Toast.makeText(this, R.string.external_open_failed, Toast.LENGTH_LONG).show()
            connection = null
            finish()
        }
    }

    private fun release() {
        connection?.let { try { applicationContext.unbindService(it) } catch (e: Exception) { android.util.Log.d("ExternalOpen", "ignored: $e") } }
        connection = null
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun isVideo(uri: Uri, declared: String?): Boolean {
        val type = (declared?.takeIf { it.isNotBlank() && it != "*/*" } ?: try { contentResolver.getType(uri) } catch (e: Exception) { null })
            ?.lowercase(Locale.ROOT)
        if (type != null) {
            if (type.startsWith("video/")) return true
            if (type.startsWith("audio/") || type == "application/ogg" || type == "application/x-ogg") return false
        }
        val name = (displayName(applicationContext, uri) ?: uri.lastPathSegment ?: "").lowercase(Locale.ROOT)
        return VIDEO_EXT.any { name.endsWith(".$it") }
    }

    companion object {
        private val VIDEO_EXT = listOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v", "ts", "flv", "wmv")

        private fun displayName(c: Context, uri: Uri): String? {
            if (uri.scheme == "content") {
                try {
                    c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) return it.getString(0)
                    }
                } catch (e: Exception) { android.util.Log.d("ExternalOpen", "ignored: $e") }
            }
            return uri.lastPathSegment?.substringAfterLast('/')
        }

        /** A local track for a file that is not in the media library: [Track.remoteId] holds its uri. */
        private fun buildTrack(c: Context, uri: Uri, video: Boolean): Track {
            var title = ""
            var artist = ""
            var album = ""
            var duration = 0L
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(c, uri)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
                artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)).orEmpty()
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                android.util.Log.d("ExternalOpen", "no metadata: $e")
            } finally {
                try { r.release() } catch (e: Exception) { android.util.Log.d("ExternalOpen", "ignored: $e") }
            }
            if (title.isBlank()) title = displayName(c, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "?"
            return Track(
                id = 0L, title = title, artist = artist, album = album, durationMs = duration,
                source = if (video) Track.SOURCE_VIDEO else Track.SOURCE_LOCAL,
                remoteId = uri.toString()
            )
        }
    }
}
