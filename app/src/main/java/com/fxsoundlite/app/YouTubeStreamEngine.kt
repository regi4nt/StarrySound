package com.fxsoundlite.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.util.Log
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import android.view.Surface
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Plays YouTube audio without the (blocked, error 153) embedded player: NewPipeExtractor resolves
 * the video id to a direct audio stream URL, which is then played by the framework MediaPlayer.
 * Owned by MusicService (a foreground service), so playback continues with the screen off.
 *
 * Note: YouTube's terms of service do not allow fetching streams outside its own players.
 */
class YouTubeStreamEngine(
    context: Context,
    private val handler: Handler,
    private val cb: RemoteEngine.Callback
) : TimedEngine() {

    companion object {
        private const val TAG = "YouTubeStream"
        private val ID_RE = Regex("[A-Za-z0-9_-]{6,20}")
        // Two threads: a background prefetch must not hold up the song the user just picked.
        private val executor = Executors.newFixedThreadPool(2)
        // Prefetching has its own thread, so a queue of background lookups can never delay the song
        // the user just picked (that one always has the two threads above to itself).
        private val prefetchExecutor = Executors.newFixedThreadPool(3)
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

        /** Resume attempts after a dropped connection (before the first sound: only one). */
        private const val MAX_RETRIES = 4
        /** A stream that is not ready / keeps buffering for this long counts as dropped. */
        private const val PREPARE_TIMEOUT_MS = 25_000L
        private const val STALL_TIMEOUT_MS = 20_000L
        /** Playing this long past a resume point proves the connection is healthy again. */
        private const val HEALTHY_AFTER_MS = 10_000

        // ---- stream URL cache: a link, once resolved, is reused instead of asking YouTube again
        private const val MAX_CACHE = 40
        private const val DEFAULT_TTL_MS = 3 * 60 * 60 * 1000L
        private const val EXPIRY_MARGIN_MS = 10 * 60 * 1000L
        private const val PREFETCH_RETRY_MS = 5 * 60 * 1000L
        private val EXPIRE_RE = Regex("[?&]expire=(\\d+)")

        private class Cached(val url: String, val validUntil: Long)

        private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)
        private val prefetchTried = HashMap<String, Long>()

        private fun key(videoId: String, q: StreamQuality.Level, lang: String = "") =
            (if (q == StreamQuality.Level.HIGH) videoId else "$videoId#${q.tag}") + (if (lang.isEmpty()) "" else "#$lang")

        private fun cached(key: String): String? = synchronized(cache) {
            val c = cache[key] ?: return null
            if (c.validUntil <= System.currentTimeMillis()) { cache.remove(key); null } else c.url
        }

        private fun store(key: String, url: String) = synchronized(cache) {
            val now = System.currentTimeMillis()
            // googlevideo links carry their own "expire=<epoch seconds>"
            val exp = EXPIRE_RE.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
            val until = if (exp != null) exp - EXPIRY_MARGIN_MS else now + DEFAULT_TTL_MS
            cache[key] = Cached(url, until)
            while (cache.size > MAX_CACHE) {
                val it = cache.keys.iterator(); it.next(); it.remove()
            }
        }

        private fun forget(key: String) { synchronized(cache) { cache.remove(key) } }

        /** What the chosen stream is (AAC / Opus + bitrate), by the same key as the link cache; shown in the player. */
        private val infoCache = java.util.concurrent.ConcurrentHashMap<String, AudioInfo>()

        private fun infoOf(a: AudioStream): AudioInfo {
            val codec = when (val sfx = a.format?.suffix.orEmpty().lowercase()) {
                "m4a", "mp4" -> "AAC"
                "webm", "opus" -> "OPUS" // YouTube's WebM audio is always Opus
                "mp3" -> "MP3"
                "ogg" -> "OGG"
                "" -> "?"
                else -> sfx.uppercase()
            }
            return AudioInfo(codec, false, 0, 0, 0, a.averageBitrate.coerceAtLeast(0))
        }

        /** Blocking: video id -> best audio stream URL (null if it has none). */
        private fun fetchUrl(videoId: String, q: StreamQuality.Level, lang: String): String? {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val best = pickAudio(info.audioStreams, q, lang) ?: return null
            infoCache[key(videoId, q, lang)] = infoOf(best)
            return best.content
        }

        private fun vkey(videoId: String, q: StreamQuality.Level, lang: String = "") = key(videoId, q, lang) + "#vid"

        /**
         * Blocking: video id -> a progressive MP4 that already carries its sound (YouTube's "muxed" streams, in practice
         * 360p), or null when there is none. Data saver / Low: the smallest; otherwise the biggest up to 480p.
         */
        private fun fetchVideoUrl(videoId: String, q: StreamQuality.Level): String? {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val muxed = info.videoStreams
                .filter { !it.isVideoOnly && it.isUrl && !it.content.isNullOrBlank() && it.format?.suffix.equals("mp4", true) }
                .sortedBy { it.height }
            if (muxed.isEmpty()) return null
            val pick = if (q == StreamQuality.Level.LOW) muxed.first() else (muxed.lastOrNull { it.height <= 480 } ?: muxed.first())
            return pick.content
        }

        /** Blocking: the progressive MP4s (picture + sound) of [videoId] as height -> link, smallest first; empty when none. */
        fun videoChoices(videoId: String): List<Pair<Int, String>> {
            if (!videoId.matches(ID_RE)) return emptyList()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            return info.videoStreams
                .filter { !it.isVideoOnly && it.isUrl && !it.content.isNullOrBlank() && it.format?.suffix.equals("mp4", true) && it.height > 0 }
                .groupBy { it.height }
                .map { (h, l) -> h to l.first().content }
                .sortedBy { it.first }
        }

        /** Blocking: a progressive MP4 (picture + sound) of [videoId], cached; null when it has none. Used by the video player when the embedded player refuses. */
        fun videoLink(context: Context, videoId: String): String? {
            if (!videoId.matches(ID_RE)) return null
            val q = StreamQuality.effective(context)
            val k = vkey(videoId, q, MusicPrefs.audioLang(context))
            cached(k)?.let { return it }
            return fetchVideoUrl(videoId, q)?.also { store(k, it) }
        }

        const val HTTP_USER_AGENT = USER_AGENT

        /** MediaPlayer is happiest with AAC/M4A; take the stream matching the chosen quality of those, else any audio. */
        private fun pickAudio(list: List<AudioStream>, q: StreamQuality.Level, lang: String): AudioStream? {
            val usable = list.filter { it.isUrl && !it.content.isNullOrBlank() }
            // The chosen dub language if this video has it, otherwise the original audio.
            val wanted = if (lang.isEmpty()) emptyList() else usable.filter { it.audioLocale?.language == lang }
            val ok = wanted.ifEmpty { originalTrackOnly(usable) }
            val m4a = ok.filter { it.format?.suffix.equals("m4a", true) }
            val pool = m4a.ifEmpty { ok }
            // Low (also Data saver in Auto): the smallest stream (about 48-70 kbps) = far less data and radio time.
            // Normal: closest to 128 kbps. High: the biggest.
            val want = StreamQuality.pickBitrate(q, pool.map { it.averageBitrate })
            return pool.firstOrNull { it.averageBitrate > 0 && it.averageBitrate == want }
                ?: if (q == StreamQuality.Level.LOW) pool.firstOrNull() else pool.maxByOrNull { it.averageBitrate }
        }

        /**
         * Resolves the stream link of [videoId] in the background and keeps it, so that starting
         * that song later is instant. Used by MusicService for the song after the current one.
         * Safe to call repeatedly: a cached link, or a recent attempt, makes it a no-op.
         */
        fun prefetch(context: Context, videoId: String, video: Boolean = false) {
            if (!videoId.matches(ID_RE)) return
            val q = StreamQuality.effective(context)
            val lang = MusicPrefs.audioLang(context)
            val k = if (video) vkey(videoId, q, lang) else key(videoId, q, lang)
            if (cached(k) != null) return
            synchronized(prefetchTried) {
                val last = prefetchTried[k]
                if (last != null && System.currentTimeMillis() - last < PREFETCH_RETRY_MS) return
                prefetchTried[k] = System.currentTimeMillis()
            }
            try {
                prefetchExecutor.execute {
                    try {
                        if (cached(k) != null) return@execute
                        (if (video) fetchVideoUrl(videoId, q) else fetchUrl(videoId, q, lang))?.let { store(k, it) }
                    } catch (e: Throwable) {
                        Log.w(TAG, "prefetch failed for $videoId", e)
                    }
                }
            } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
        }
    }

    override val needsAudioFocus = true

    private val appContext = context.applicationContext
    /** Format of the stream playing now (null until the link is resolved). */
    val currentInfo: AudioInfo?
        get() = curId?.let { infoCache[key(it, StreamQuality.effective(appContext), MusicPrefs.audioLang(appContext))] }

    /** Quick tab in video mode: play the video (with its sound) into [setSurface] instead of the audio only. Set before load(). */
    var videoMode = false
    /** True while the stream playing now really has a picture (a video was asked for and found). */
    var showsVideo = false
        private set
    /** Called (main thread) when the picture size of the playing video is known / changes: lets the page crop it to fill. */
    var onVideoSize: ((Int, Int) -> Unit)? = null
    private var surface: Surface? = null

    /** Where the picture goes (null = nowhere, the sound plays on). Can change while playing. */
    fun setSurface(s: Surface?) {
        surface = s
        try { mp?.setSurface(s) } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
        // A video was waiting for its picture area before starting (see startPlayer): go now.
        if (s != null) pendingStart?.let { handler.removeCallbacks(it); pendingStart = null; it.run() }
    }

    private var pendingStart: Runnable? = null

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var released = false
    private var started = false
    private var token = 0 // bumped on every load()/recovery; stale async results are ignored
    private var volume = 1f
    private var curId: String? = null
    private var retries = 0
    private var recoverPos = 0

    /** Fires when the stream never got ready, or stalled while buffering: treat as a dropped link. */
    private val watchdog = Runnable {
        if (released || !playing) return@Runnable
        Log.w(TAG, "stream stalled, recovering")
        if (!recover()) fail(RemoteEngine.ERR_NETWORK)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (released || !playing) return
            val m = mp ?: return
            if (prepared) try {
                val p = m.currentPosition
                setClock(p, true)
                if (retries > 0 && p - recoverPos > HEALTHY_AFTER_MS) retries = 0
            } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
            handler.postDelayed(this, if (MusicPrefs.saver(appContext)) 3000L else 1000L)
        }
    }

    // ------------------------------------------------------------------ RemoteEngine

    override fun load(track: Track) {
        val my = ++token
        stopPlayer()
        started = false
        retries = 0
        recoverPos = 0
        curId = null
        dur = track.durationMs.toInt()
        setClock(0, false)
        handler.removeCallbacks(poll)
        if (!track.remoteId.matches(ID_RE)) {
            handler.post { if (!released && my == token) cb.onError(RemoteEngine.ERR_YT_UNPLAYABLE) }
            return
        }
        curId = track.remoteId
        playing = true // optimistic, like play(): MusicService calls play() right after load()
        val q0 = StreamQuality.effective(appContext)
        val lang0 = MusicPrefs.audioLang(appContext)
        val video = videoMode
        Log.i(TAG, "load ${track.remoteId} videoMode=$video surface=${surface != null}")
        val hit = cached(if (video) vkey(track.remoteId, q0, lang0) else key(track.remoteId, q0, lang0))
        if (hit != null) { // link already known (e.g. prefetched): no waiting
            startPlayer(hit, my, video)
            return
        }
        resolveAsync(track.remoteId, my, fresh = false)
    }

    private fun resolveAsync(videoId: String, my: Int, fresh: Boolean) {
        try {
            executor.execute { resolve(videoId, my, fresh) }
        } catch (e: Exception) {
            handler.post { if (!released && my == token) fail(RemoteEngine.ERR_GENERIC) }
        }
    }

    /** Background thread: video id -> best stream URL (cached; [fresh] skips the cache). In [videoMode] a muxed video first, audio if it has none. */
    private fun resolve(videoId: String, my: Int, fresh: Boolean) {
        val q = StreamQuality.effective(appContext)
        val lang = MusicPrefs.audioLang(appContext)
        val k = key(videoId, q, lang)
        val vk = vkey(videoId, q, lang)
        val wantVideo = videoMode
        if (fresh) { forget(k); forget(vk) }
        var url: String? = null
        var isVideo = false
        var err = RemoteEngine.ERR_YT_UNPLAYABLE
        var network = false
        try {
            if (wantVideo) {
                url = cached(vk)
                if (url == null) url = fetchVideoUrl(videoId, q)?.also { store(vk, it) }
                isVideo = url != null
            }
            if (url == null) {
                url = cached(k)
                if (url == null) url = fetchUrl(videoId, q, lang)?.also { store(k, it) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "resolve failed (network)", e)
            err = RemoteEngine.ERR_NETWORK
            network = true
        } catch (e: Throwable) {
            Log.w(TAG, "resolve failed", e)
        }
        val u = url
        val video = isVideo
        handler.post {
            if (released || my != token) return@post
            when {
                u != null -> { if (wantVideo && !video) Log.w(TAG, "video: no muxed mp4 for $videoId, sound only"); startPlayer(u, my, video) }
                network && recover() -> Unit // try again shortly
                else -> fail(err)
            }
        }
    }

    /**
     * The stream broke (error, early end, stall, no network): remember where we were, drop the
     * stored link (it may have expired) and start again from that second with a freshly resolved
     * one, after a growing pause. Returns false when it should not / can no longer be retried.
     */
    private fun recover(): Boolean {
        val id = curId ?: return false
        val limit = if (started) MAX_RETRIES else 1 // never played yet: probably not a flaky link
        if (!playing || retries >= limit) return false
        retries++
        val pos = try { if (prepared) mp?.currentPosition ?: basePos else basePos } catch (_: Exception) { basePos }
        recoverPos = pos
        handler.removeCallbacks(poll)
        stopPlayer()
        setClock(pos, true)
        val my = ++token
        Log.w(TAG, "resuming $id at ${pos}ms (attempt $retries/$limit)")
        handler.postDelayed({
            if (!released && my == token && playing) resolveAsync(id, my, fresh = true)
        }, 1000L * retries)
        return true
    }

    private fun startPlayer(url: String, my: Int, video: Boolean = false, waited: Boolean = false) {
        // The player must know its surface BEFORE it prepares: without one the video is never decoded (sound only).
        // The picture area of a reel may not exist yet, so wait briefly for it, then go on with sound only.
        if (video && surface == null && !waited) {
            pendingStart?.let { handler.removeCallbacks(it) }
            val r = Runnable {
                pendingStart = null
                if (!released && my == token) {
                    Log.w(TAG, "video: no picture surface after 2s, playing sound only")
                    startPlayer(url, my, video, waited = true)
                }
            }
            pendingStart = r
            handler.postDelayed(r, 2000L)
            return
        }
        try {
            val m = MediaPlayer()
            mp = m
            prepared = false
            showsVideo = video
            if (video) surface?.let { m.setSurface(it) }
            m.setOnVideoSizeChangedListener { _, w, h ->
                if (mp === m && w > 0 && h > 0) onVideoSize?.invoke(w, h)
            }
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            m.setWakeMode(appContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            m.setVolume(volume, volume)
            m.setDataSource(appContext, Uri.parse(url), mapOf("User-Agent" to USER_AGENT))
            m.setOnPreparedListener {
                if (released || mp !== it || my != token) return@setOnPreparedListener
                handler.removeCallbacks(watchdog)
                prepared = true
                // No scaling mode here: the page's TextureView crops the picture itself (QuickAdapter.cropToFill). A second crop by the player would zoom in twice.
                if (it.duration > 0) dur = it.duration
                if (basePos > 0) it.seekTo(basePos)
                if (playing) {
                    applySpeed(it)
                    it.start()
                    handler.removeCallbacks(poll)
                    handler.postDelayed(poll, 1000)
                }
                started = true
                setClock(basePos, playing)
                cb.onStarted()
            }
            m.setOnCompletionListener {
                if (released || mp !== it) return@setOnCompletionListener
                handler.removeCallbacks(poll)
                // A dropped connection often looks like "the song ended" a long way before its end.
                val total = try { it.duration } catch (_: Exception) { 0 }
                val at = try { it.currentPosition } catch (_: Exception) { 0 }
                if (total > 0 && total - at > 10_000 && playing) {
                    basePos = at
                    if (recover()) return@setOnCompletionListener
                }
                setClock(0, false)
                started = false
                cb.onEnded()
            }
            m.setOnErrorListener { x, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra")
                if (!released && mp === x) {
                    handler.removeCallbacks(poll)
                    if (!recover()) fail(RemoteEngine.ERR_YT_UNPLAYABLE)
                }
                true
            }
            m.setOnInfoListener { x, what, _ ->
                if (!released && mp === x && prepared) {
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            handler.removeCallbacks(watchdog)
                            handler.postDelayed(watchdog, STALL_TIMEOUT_MS)
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> handler.removeCallbacks(watchdog)
                    }
                }
                false
            }
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, PREPARE_TIMEOUT_MS)
            m.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "could not start player", e)
            fail(RemoteEngine.ERR_YT_UNPLAYABLE)
        }
    }

    private fun fail(code: Int) {
        handler.removeCallbacks(watchdog)
        stopPlayer()
        setClock(basePos, false)
        cb.onError(code)
    }

    override fun play() {
        setClock(basePos, true)
        val m = mp
        if (m != null && prepared) {
            try { applySpeed(m); m.start() } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, 1000)
            cb.onStateChanged()
        } // else startPlayer()/onPrepared starts it as soon as the stream is ready
    }

    override fun pause() {
        setClock(position, false)
        handler.removeCallbacks(poll)
        handler.removeCallbacks(watchdog)
        try { if (prepared) mp?.pause() } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
    }

    override fun seekTo(ms: Int) {
        setClock(ms, playing)
        try { if (prepared) mp?.seekTo(ms) } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
    }

    /** Set before start() only: on some Android versions changing the speed of a paused player starts it. */
    private fun applySpeed(m: MediaPlayer) {
        if (clockSpeed == 1f) return
        try { m.playbackParams = m.playbackParams.setSpeed(clockSpeed) } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
    }

    override fun setSpeed(s: Float) {
        setClock(position, playing) // keep the interpolated clock continuous across the change
        clockSpeed = s
        val m = mp ?: return
        if (prepared) try { if (m.isPlaying) m.playbackParams = m.playbackParams.setSpeed(s) } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
    }

    override fun setVolume(v: Float) {
        volume = v
        try { mp?.setVolume(v, v) } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
    }

    override fun release() {
        released = true
        token++
        handler.removeCallbacks(poll)
        handler.removeCallbacks(watchdog)
        stopPlayer()
    }

    private fun stopPlayer() {
        handler.removeCallbacks(watchdog)
        pendingStart?.let { handler.removeCallbacks(it) }
        pendingStart = null
        mp?.let {
            try { it.setOnVideoSizeChangedListener(null); it.setOnErrorListener(null); it.setOnPreparedListener(null); it.setOnInfoListener(null); it.setOnCompletionListener(null); it.reset() } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
            try { it.release() } catch (e: Exception) { android.util.Log.d("YouTubeStreamEngine", "ignored: " + e) }
        }
        mp = null
        prepared = false
        showsVideo = false
    }
}

/**
 * YouTube videos with auto-dubbing list several audio tracks (original, English dub, ...). Keep only the
 * original-language one so a song is never played in a dubbed voice; unchanged if no track is marked original.
 */
fun originalTrackOnly(list: List<AudioStream>): List<AudioStream> {
    val original = list.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
    if (original.isNotEmpty()) return original
    val notDub = list.filter { it.audioTrackType != AudioTrackType.DUBBED && it.audioTrackType != AudioTrackType.DESCRIPTIVE }
    return notDub.ifEmpty { list }
}

/** One selectable audio track of a YouTube video: [lang] is the language code ("" = original audio). */
class AudioChoice(val lang: String, val label: String)

/** Blocking: the audio languages a video offers; the first entry is always the original audio. */
fun youtubeAudioChoices(videoId: String): List<AudioChoice> {
    val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
    val out = ArrayList<AudioChoice>()
    out += AudioChoice("", "")
    val seen = HashSet<String>()
    for (a in info.audioStreams) {
        val loc = a.audioLocale ?: continue
        val code = loc.language
        if (code.isEmpty() || !seen.add(code)) continue
        val name = loc.getDisplayLanguage(loc).replaceFirstChar { it.uppercase() }
        out += AudioChoice(code, name + if (a.audioTrackType == AudioTrackType.ORIGINAL) " *" else "")
    }
    return out
}
