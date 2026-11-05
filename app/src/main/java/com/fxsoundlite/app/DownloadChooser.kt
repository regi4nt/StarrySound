package com.fxsoundlite.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog

/**
 * The single "Download" item of the three-dot menu (music player and video player). For a YouTube track it asks what to
 * save: the audio (a song in Music/StarrySound, [SongDownloader]) or the video (Movies/StarrySound, [LinkDownloader]).
 * For a video the available qualities are looked up first (progressive streams with sound and picture together).
 * Tracks of other sources keep the old behavior of [SongDownloader.download].
 */
object DownloadChooser {

    private const val TAG = "DownloadChooser"
    private val main = Handler(Looper.getMainLooper())

    fun show(activity: Activity, track: Track) {
        if (!SongDownloader.canDownload(track)) {
            SongDownloader.download(activity, track)
            return
        }
        val items = arrayOf(
            activity.getString(R.string.dl_choose_audio),
            activity.getString(R.string.dl_choose_video)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.np_download_media)
            .setItems(items) { _, which ->
                if (which == 0) SongDownloader.download(activity, track) else downloadVideo(activity, track)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Download playlist": audio of every song, or the videos at one quality (the best each song has up to it). */
    fun showPlaylist(c: android.content.Context, tracks: List<Track>, name: String) {
        if (tracks.none { SongDownloader.canDownload(it) }) {
            SongDownloader.downloadAll(c, tracks, name) // tells the person there is nothing to download
            return
        }
        AlertDialog.Builder(c)
            .setTitle(R.string.playlist_download)
            .setItems(arrayOf(c.getString(R.string.dl_choose_audio), c.getString(R.string.dl_choose_video))) { _, which ->
                if (which == 0) {
                    SongDownloader.downloadAll(c, tracks, name)
                } else {
                    val labels = arrayOf(c.getString(R.string.dl_quality_best), "720p", "480p", "360p")
                    val heights = intArrayOf(Int.MAX_VALUE, 720, 480, 360)
                    AlertDialog.Builder(c)
                        .setTitle(R.string.dl_quality_title)
                        .setItems(labels) { _, q -> SongDownloader.downloadAll(c, tracks, name, video = true, maxHeight = heights[q]) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadVideo(activity: Activity, track: Track) {
        val app = activity.applicationContext
        SongDownloader.toast(app, R.string.np_download_preparing)
        Thread {
            val info = try {
                LinkDownloader.resolve("https://www.youtube.com/watch?v=${track.remoteId}")
            } catch (e: Throwable) {
                Log.w(TAG, "resolve failed", e)
                null
            }
            main.post {
                if (info == null) {
                    SongDownloader.toast(app, R.string.link_download_unreadable)
                    return@post
                }
                val videos = info.options.filter { it.video } // already sorted from the highest quality down
                when {
                    videos.isEmpty() -> SongDownloader.toast(app, R.string.link_download_nothing)
                    // Only one quality, or the player was left while looking up: no need to ask.
                    videos.size == 1 || activity.isFinishing || activity.isDestroyed ->
                        LinkDownloader.start(app, info, videos.first())
                    else -> AlertDialog.Builder(activity)
                        .setTitle(R.string.dl_choose_video)
                        .setItems(videos.map { it.label }.toTypedArray()) { _, which ->
                            LinkDownloader.start(activity, info, videos[which])
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }.start()
    }
}
