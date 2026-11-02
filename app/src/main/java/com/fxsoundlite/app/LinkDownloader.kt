package com.fxsoundlite.app

import android.content.Context
import android.util.Log
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.URL
import java.util.Locale

/**
 * Saves a song (audio) or a video from a pasted link. The link is resolved with NewPipeExtractor (YouTube, SoundCloud,
 * Bandcamp, PeerTube, ...), for TikTok / Instagram / Facebook with [SocialResolver], for any other website with [WebPageResolver]; a link straight to a media file (.mp3, .mp4, ...) is downloaded as it is. The file itself is
 * fetched by [SongDownloader.fetch] (ranged chunks, progress in the notification): songs go to Music/StarrySound, videos
 * to Movies/StarrySound, where the Device tab finds them.
 *
 * Only streams that carry sound and picture together are offered for video (what the services serve as progressive
 * files, YouTube up to 720p); the app has no muxer for separate video-only + audio tracks.
 */
object LinkDownloader {

    private const val TAG = "LinkDownloader"
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr")
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "mpg", "mpeg", "ogv")

    /** One thing that can be saved: [video] = false is a song. [label] is shown in the chooser. */
    class Option(val label: String, val url: String, val ext: String, val mime: String, val video: Boolean, val referer: String = "")

    class Info(val title: String, val uploader: String, val options: List<Option>)

    /** The first http(s) link in [text] (a shared message may carry more words), or null. */
    fun linkIn(text: String): String? =
        Regex("https?://[^\\s]+").find(text.trim())?.value?.trimEnd('.', ',', ')', ']', '>', '"', '\'')

    /** Blocking (network): call from a background thread. Throws when the link cannot be read. */
    fun resolve(link: String): Info {
        val direct = directKind(link)
        if (direct != null) return directInfo(link, direct)
        SocialResolver.resolve(link)?.let { return it } // TikTok / Instagram / Facebook (not covered by NewPipeExtractor)
        val info = try {
            StreamInfo.getInfo(link)
        } catch (e: Throwable) {
            // Not a site NewPipeExtractor knows: look for media files in the page itself.
            Log.d(TAG, "extractor: $e")
            return WebPageResolver.resolve(link)
        }
        val options = ArrayList<Option>()
        val audio = originalTrackOnly(info.audioStreams.filter { it.isUrl && !it.content.isNullOrBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP })
        val best = audio.filter { it.format?.suffix.equals("m4a", true) }.ifEmpty { audio }.maxByOrNull { it.averageBitrate }
        if (best != null) {
            val ext = best.format?.suffix?.takeIf { it.isNotBlank() } ?: "m4a"
            val kbps = if (best.averageBitrate > 0) " · ${best.averageBitrate} kbps" else ""
            options += Option("♪ " + ext.uppercase(Locale.ROOT) + kbps, best.content, ext, best.format?.mimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4", false)
        }
        val seenRes = HashSet<String>()
        info.videoStreams
            .filter { it.isUrl && !it.content.isNullOrBlank() && !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedByDescending { (it.resolution.filter { c -> c.isDigit() }.take(5).toIntOrNull() ?: 0) }
            .forEach {
                if (!seenRes.add(it.resolution + it.format?.suffix)) return@forEach
                val ext = it.format?.suffix?.takeIf { s -> s.isNotBlank() } ?: "mp4"
                options += Option("▶ " + it.resolution + " · " + ext.uppercase(Locale.ROOT), it.content, ext, it.format?.mimeType?.takeIf { m -> m.isNotBlank() } ?: "video/mp4", true)
            }
        return Info(info.name.orEmpty().ifBlank { link }, info.uploaderName.orEmpty(), options)
    }

    /** "audio" / "video" for a link that ends in a media file name, else null. */
    private fun directKind(link: String): String? {
        val ext = link.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            in AUDIO_EXT -> "audio"
            in VIDEO_EXT -> "video"
            else -> null
        }
    }

    private fun directInfo(link: String, kind: String): Info {
        val file = link.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val name = java.net.URLDecoder.decode(file.substringBeforeLast('.'), "UTF-8").ifBlank { link }
        val video = kind == "video"
        val mime = (if (video) "video/" else "audio/") + (if (ext == "mp3") "mpeg" else ext)
        return Info(name, URL(link).host.orEmpty(), listOf(Option((if (video) "▶ " else "♪ ") + ext.uppercase(Locale.ROOT), link, ext, mime, video)))
    }

    /** Starts the download in the background (two at a time); progress and the result show in the notification. */
    fun start(context: Context, info: Info, option: Option) {
        val app = context.applicationContext
        if (!SongDownloader.storageAllowed(context)) {
            SongDownloader.toast(app, R.string.np_download_permission)
            return
        }
        SongDownloader.toast(app, R.string.np_download_preparing)
        val notifId = SongDownloader.notifIds.incrementAndGet()
        val title = info.title
        SongDownloader.io.execute {
            try {
                val name = SongDownloader.safeName(
                    if (info.uploader.isBlank() || option.video) title else "${info.uploader} - $title", "download"
                ) + "." + option.ext
                SongDownloader.toast(app, if (option.video) R.string.link_download_started_video else R.string.np_download_started)
                SongDownloader.showProgress(app, notifId, title, 0, true)
                val sink = if (option.video) SongDownloader.openVideoSink(app, name, option.mime, title)
                else SongDownloader.openAudioSink(app, name, option.mime, title, info.uploader)
                var ok = false
                try {
                    sink.out.use { out -> SongDownloader.fetch(app, option.url, out, notifId, title, option.referer) }
                    ok = true
                } finally {
                    if (ok) sink.commit() else sink.abort()
                }
                SongDownloader.finishNotification(app, notifId, title, true)
                SongDownloader.toast(app, if (option.video) R.string.link_download_done_video else R.string.np_download_done)
            } catch (e: IOException) {
                Log.w(TAG, "link download failed (network)", e)
                SongDownloader.finishNotification(app, notifId, title, false)
                SongDownloader.toast(app, R.string.np_download_network)
            } catch (e: Throwable) {
                Log.w(TAG, "link download failed", e)
                SongDownloader.finishNotification(app, notifId, title, false)
                SongDownloader.toast(app, R.string.np_download_failed)
            }
        }
    }
}
