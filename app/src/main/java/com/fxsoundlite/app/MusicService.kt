package com.fxsoundlite.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.service.media.MediaBrowserService
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.os.PowerManager.WakeLock
import androidx.core.app.ServiceCompat
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Music player. Local files use the framework MediaPlayer on purpose (no ExoPlayer) to keep the
 * APK tiny; YouTube tracks go through a [RemoteEngine] (see YouTubeEngine). Both share the same queue, the same MediaSession and the same media
 * notification, so lock screen / notification / headset controls work for every source and keep
 * working with the app closed. Playback goes through the normal system output mixer, so the
 * StarrySound effect chain (EffectService) applies to it: while a local file plays, the chain is
 * attached to the player's own audio session (many devices ignore global session-0 effects);
 * otherwise (paused, stopped, YouTube) it stays on the global mix like for any other app.
 *
 * All public methods and listener callbacks run on the main thread.
 */
class MusicService : MediaBrowserService() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.fxsoundlite.app.action.MUSIC_PLAY_PAUSE"
        const val ACTION_NEXT = "com.fxsoundlite.app.action.MUSIC_NEXT"
        const val ACTION_PREV = "com.fxsoundlite.app.action.MUSIC_PREV"
        const val ACTION_STOP = "com.fxsoundlite.app.action.MUSIC_STOP"
        /** Plain pause (not a toggle): used by the sleep timer when it ends YouTube / device music. */
        const val ACTION_PAUSE = "com.fxsoundlite.app.action.MUSIC_PAUSE"

        // Android Auto / Bluetooth browse tree (see onLoadChildren)
        private const val BROWSE_ROOT = "root"
        private const val BR_LIKED = "liked"
        private const val BR_PLAYLISTS = "playlists"
        private const val BR_RECENT = "recent"
        private const val BR_DEVICE = "device"
        private const val BR_PL_PREFIX = "pl:"
        private const val MAX_BROWSE = 200

        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2

        private const val KEY_SPEED = "playback_speed"
        private const val CHANNEL_ID = "starrysound_music_channel"
        private const val NOTIF_ID = 2
        private const val PREFS = "music_prefs"
        private const val KEY_SHUFFLE = "shuffle"            // old boolean, still read once
        private const val KEY_SHUFFLE_MODE = "shuffle_mode"

        const val SHUFFLE_OFF = 0
        const val SHUFFLE_ON = 1
        const val SHUFFLE_SMART = 2

        private const val SMART_BATCH = 3                 // songs added per fetch
        private const val SMART_EVERY = 3                 // about one suggested song in every 3
        private const val SMART_MIN_GAP_MS = 30_000L
        private const val KEY_REPEAT = "repeat"

        private const val RADIO_CONNECT_TIMEOUT_MS = 60_000L
        private const val RADIO_RESOLVE_TIMEOUT_MS = 35_000L // playlist download + redirects, before connecting
        private const val RADIO_STALL_TIMEOUT_MS = 45_000L
        private const val RADIO_STABLE_MS = 30_000L
        private const val RADIO_MAX_RECONNECTS = 5

        /** The running service, if any (main thread only). */
        @Volatile
        var instance: MusicService? = null
            private set

        /** Listeners that are not tied to a bound client, e.g. the Screensaver's now-playing line. */
        private val globalListeners = CopyOnWriteArraySet<Listener>()

        fun addGlobalListener(l: Listener) { globalListeners.add(l) }
        fun removeGlobalListener(l: Listener) { globalListeners.remove(l) }

        /**
         * "Title • Artist" of what this app itself is playing right now, or null when nothing is.
         * Needs no notification access (unlike reading other apps' media sessions), so the
         * Screensaver can always show the music played from inside StarrySound.
         */
        fun nowPlayingLabel(): String? {
            val s = instance ?: return null
            if (!s.isPlaying) return null
            val t = s.current ?: return null
            if (t.title.isBlank()) return null
            return if (t.artist.isBlank()) t.title else "${t.title} \u2022 ${t.artist}"
        }
    }

    interface Listener {
        /** Track, play state, shuffle/repeat mode or artwork changed. */
        fun onMusicStateChanged()

        /** Playback of an online track failed in a way the user should hear about
         *  (one of the [RemoteEngine] ERR_* codes). */
        fun onMusicError(code: Int) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val artExecutor = Executors.newSingleThreadExecutor()
    private val driveExecutor = Executors.newSingleThreadExecutor() // Drive songs: download before playing

    private var player: MediaPlayer? = null
    private var prepared = false
    /** Set while the current track is a YouTube one (then [player] is unused). */
    private var remote: RemoteEngine? = null
    private var remoteKind = -1
    private var wakeLock: WakeLock? = null
    private var session: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var foregroundActive = false
    private var consecutiveErrors = 0
    @Volatile private var loadToken = 0
    /** The Drive song being played while it downloads (closing it stops the download). */
    private var driveSource: DriveMusic.StreamingSource? = null

    // Listening stats (see StatsStore): time is added while something plays; a song counts as
    // "played" once it has been heard for StatsStore.PLAY_THRESHOLD_MS.
    private var statsLast = 0L
    private var statsListened = 0L
    private var statsCounted = false

    // Last.fm (see LastFm): "now playing" after 5 s, scrobble after min(half the song, 4 min) of listening.
    private var lfmListened = 0L
    private var lfmNowSent = false
    private var lfmScrobbled = false
    private fun lastFmTick(t: Track, delta: Long) {
        if (lfmScrobbled || MusicPrefs.privateMode(this) || !LastFm.scrobblingEnabled(this)) return
        lfmListened += delta
        if (!lfmNowSent && lfmListened >= 5_000L) {
            lfmNowSent = true
            LastFm.nowPlaying(this, t)
        }
        if (lfmListened >= LastFm.thresholdMs(t)) {
            lfmScrobbled = true
            LastFm.scrobble(this, t, System.currentTimeMillis() / 1000 - lfmListened / 1000)
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val delta = (now - statsLast).coerceIn(0L, 10_000L)
            statsLast = now
            val t = current
            if (delta > 0 && t != null && isPlaying && !driveLoading && !MusicPrefs.privateMode(this@MusicService)) { // not while a Drive song is still downloading, nor in private mode
                StatsStore.addListening(this@MusicService, delta)
                lastFmTick(t, delta)
                if (!statsCounted) {
                    statsListened += delta
                    if (statsListened >= StatsStore.PLAY_THRESHOLD_MS) {
                        statsCounted = true
                        StatsStore.recordPlay(this@MusicService, t)
                    }
                }
            }
            handler.postDelayed(this, 5_000L)
        }
    }
    /** Shortly before a YouTube song ends, resolves the stream link of the next one (if it is YouTube too). */
    private val prefetchTick = object : Runnable {
        override fun run() {
            try { maybePrefetchNext() } catch (e: Exception) { android.util.Log.w("MusicService", "prefetch", e) }
            try { maybeSmartRefill() } catch (e: Exception) { android.util.Log.w("MusicService", "smart", e) }
            handler.postDelayed(this, 4_000L)
        }
    }

    private fun maybePrefetchNext() {
        val t = current ?: return
        if (t.source != Track.SOURCE_YOUTUBE || !isPlaying) return
        val d = duration
        // Resolve the next song's link early (after 8 s of this one), so it starts instantly; links stay valid for hours.
        if (d <= 0 || (position < 8_000 && d - position > 60_000)) return
        val n = peekNext()
        if (n < 0 || n == index) return
        val nt = queue[n]
        if (nt.source == Track.SOURCE_YOUTUBE) YouTubeStreamEngine.prefetch(applicationContext, nt.remoteId)
    }

    // ---------------------------------------------------------------- smart shuffle

    private fun smartAheadCount(): Int =
        queue.indices.count { it != index && queue[it].key in smartKeys && queue[it].key !in smartPlayed }

    private fun noteSmartPlayed(t: Track) {
        if (t.key in smartKeys) { smartPlayed.add(t.key); sinceSmart = 0 } else sinceSmart++
    }

    private fun resetSmart() {
        smartGen++
        smartKeys.clear()
        smartPlayed.clear()
        sinceSmart = 0
    }

    /** Songs that describe the theme: the current one, the last played, and a few from the queue. */
    private fun smartSeeds(): List<Track> {
        val seeds = LinkedHashMap<String, Track>()
        current?.let { seeds[it.key] = it }
        history.takeLast(4).reversed().forEach { i -> queue.getOrNull(i)?.let { seeds.putIfAbsent(it.key, it) } }
        queue.filter { it.source != Track.SOURCE_RADIO }.shuffled().forEach { if (seeds.size < 8) seeds.putIfAbsent(it.key, it) }
        return seeds.values.toList()
    }

    /** Smart shuffle on and no suggested song waiting: asks for new ones in the background. */
    private fun maybeSmartRefill() {
        if (!smartShuffle || smartFetching) return
        val t = current ?: return
        if (t.source == Track.SOURCE_RADIO || !isPlaying || position < 8_000) return // not while skipping around
        if (smartAheadCount() > 0) return
        val now = SystemClock.elapsedRealtime()
        if (smartLastFetch != 0L && now - smartLastFetch < SMART_MIN_GAP_MS) return
        smartLastFetch = now
        smartFetching = true
        val gen = smartGen
        val seeds = smartSeeds()
        val exclude = queue.map { it.key }.toHashSet()
        val app = applicationContext
        try {
            smartExecutor.execute {
                val found = try { SmartShuffle.suggest(app, seeds, exclude, SMART_BATCH) } catch (e: Exception) { emptyList() }
                handler.post {
                    smartFetching = false
                    if (gen == smartGen && smartShuffle && found.isNotEmpty()) insertSmartPicks(found)
                }
            }
        } catch (e: Exception) {
            smartFetching = false
        }
    }

    /** Puts the suggestions at random places after the current song (and after "play next" songs). */
    private fun insertSmartPicks(picks: List<Track>) {
        if (index !in queue.indices) return
        val list = queue.toMutableList()
        for (t in picks) {
            if (list.any { it.key == t.key }) continue
            val lo = index + 1 + pendingNext
            val pos = if (lo >= list.size) list.size else (lo..list.size).random()
            list.add(pos, t)
            for (i in history.indices) if (history[i] >= pos) history[i] = history[i] + 1
            smartKeys.add(t.key)
        }
        queue = list
        plannedShuffle = -1
        notifyListeners()
    }

    /** Leaving smart shuffle: suggested songs that have not played yet leave the queue. */
    private fun dropUnplayedSmartPicks() {
        if (smartKeys.isNotEmpty() && index in queue.indices) {
            val keep = queue.indices.filter { it == index || queue[it].key !in smartKeys || queue[it].key in smartPlayed }
            val remap = IntArray(queue.size) { -1 }
            keep.forEachIndexed { n, i -> remap[i] = n }
            val newHistory = history.mapNotNull { h -> remap.getOrNull(h)?.takeIf { it >= 0 } }
            queue = keep.map { queue[it] }
            index = remap[index]
            history.clear()
            history.addAll(newHistory)
        }
        resetSmart()
        plannedShuffle = -1
    }

    /** The queue index that will play after the current song ends by itself, or -1 for none. */
    private fun peekNext(): Int {
        if (queue.isEmpty() || index !in queue.indices) return -1
        if (pendingNext > 0 && index + 1 < queue.size) return index + 1
        if (repeatMode == REPEAT_ONE) return -1
        if (shuffle && queue.size > 1) {
            if (plannedShuffle !in queue.indices || plannedShuffle == index) plannedShuffle = randomOtherIndex()
            return plannedShuffle
        }
        val n = index + 1
        return if (n < queue.size) n else if (repeatMode == REPEAT_ALL) 0 else -1
    }

    /** Stable audio session shared by every local MediaPlayer, so effects can attach once. */
    private var musicSessionId = AudioManager.ERROR
    /** True when the current [player] really uses [musicSessionId]. */
    private var playerOnMusicSession = false
    /** The effect chain could not be created on [musicSessionId]: stop trying. */
    private var musicRoutingFailed = false
    /** Last error code reported by an online engine, shown if the whole queue ends up failing. */
    private var lastRemoteError = 0

    private var queue: List<Track> = emptyList()
    private var index = -1
    /** Shuffle: the song chosen ahead as "next", so it can be prefetched and then really played. */
    private var plannedShuffle = -1

    // Radio: connection timeout + automatic reconnect (see startRadio / radioFailed).
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val browseExecutor = Executors.newSingleThreadExecutor() // Android Auto browsing / voice search
    private val voiceExecutor = Executors.newSingleThreadExecutor()   // voice search on YouTube (network; must not block browsing)

    // Skipping non-music parts of YouTube songs (SponsorBlock) -- see loadSegments / segmentTick
    private var segments: List<SegmentSkipper.Segment> = emptyList()
    private var lastSkippedEnd = -1
    private val segmentTick = object : Runnable {
        override fun run() {
            val t = current
            if (segments.isEmpty() || t == null || t.source != Track.SOURCE_YOUTUBE) return // nothing to watch: stop ticking
            if (isPlaying) {
                val pos = position
                val seg = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 300 }
                // lastSkippedEnd: if the listener seeks back into a skipped part on purpose, leave them there.
                if (seg != null && seg.endMs != lastSkippedEnd) {
                    lastSkippedEnd = seg.endMs
                    val d = duration
                    if (d > 0 && seg.endMs >= d - 1_500) next() else seekTo(seg.endMs)
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    private fun clearSegments() {
        handler.removeCallbacks(segmentTick)
        segments = emptyList()
        lastSkippedEnd = -1
    }

    private fun loadSegments(track: Track, token: Int) {
        if (!MusicPrefs.skipSegments(this) || MusicPrefs.saver(this)) return
        try {
            netExecutor.execute {
                val found = SegmentSkipper.fetch(track.remoteId)
                handler.post {
                    if (token == loadToken && found.isNotEmpty() && current?.key == track.key) {
                        segments = found
                        lastSkippedEnd = -1
                        handler.removeCallbacks(segmentTick)
                        handler.post(segmentTick)
                    }
                }
            }
        } catch (e: Exception) {
            // Executor shut down: service is going away.
        }
    }
    private var radioGen = 0
    private var radioAttempts = 0
    private var radioAutoStart = true
    /** Why the last radio connection failed (MediaPlayer codes / host), shown with the error toast. */
    @Volatile var lastRadioError: String = ""
    /** A Drive song is being downloaded (nothing to hear yet). Tapping play meanwhile must not restart the download. */
    private var driveLoading = false
    private var driveAutoStart = false
    /** Why the last Drive download failed (shown next to the error message). */
    @Volatile var lastDriveError = ""
        private set
    private var radioUrls: List<String> = emptyList()
    private var radioUrlsFor = ""
    private val radioTimeout = Runnable {
        lastRadioError = "timeout"
        radioFailed(radioGen)
    }
    private val radioStable = Runnable { radioAttempts = 0 }
    /** Indices visited while shuffling, so "previous" can walk back. */
    private val history = ArrayList<Int>()
    /** How many songs right after [index] were put there with "play next" and must still be
     *  played before anything else (also while shuffling). Reset by any other navigation. */
    private var pendingNext = 0

    /** [SHUFFLE_OFF], [SHUFFLE_ON] or [SHUFFLE_SMART] (shuffle + suggested songs that fit the theme). */
    var shuffleMode = SHUFFLE_OFF
        private set
    val shuffle: Boolean get() = shuffleMode != SHUFFLE_OFF
    val smartShuffle: Boolean get() = shuffleMode == SHUFFLE_SMART

    // Smart shuffle state (main thread). [smartKeys] = suggested songs currently in the queue,
    // [smartPlayed] = those that already played, [sinceSmart] = songs played since the last suggestion.
    private val smartKeys = HashSet<String>()
    private val smartPlayed = HashSet<String>()
    private var sinceSmart = 0
    private var smartFetching = false
    private var smartLastFetch = 0L
    private var smartGen = 0
    private val smartExecutor = Executors.newSingleThreadExecutor()

    /** True for a song the smart shuffle added (not part of the playlist the user started). */
    fun isSmartPick(t: Track?): Boolean = t != null && t.key in smartKeys
    var repeatMode = REPEAT_OFF
        private set
    var artwork: Bitmap? = null
        private set

    val current: Track? get() = queue.getOrNull(index)

    /** YouTube: format of the direct audio stream playing now (null while unknown). */
    val youtubeAudioInfo: AudioInfo? get() = (remote as? YouTubeEngine)?.audioInfo

    /** YouTube: the WebView player is in use, so the exact format is up to YouTube. */
    val youtubeWebPlayer: Boolean get() = (remote as? YouTubeEngine)?.usingWebPlayer == true

    /** Read-only view of the play queue and the position of the current track in it. */
    val queueTracks: List<Track> get() = queue
    val queueIndex: Int get() = index

    /** Jumps to [i] in the current queue (queue screen). */
    fun playAt(i: Int) {
        if (i !in queue.indices) return
        if (shuffle && index in queue.indices) history.add(index)
        pendingNext = 0
        playIndex(i, autoStart = true)
    }

    /** "Play next": inserts [t] right after the current song (after earlier "play next" songs,
     *  so they play in the order they were added). With an empty queue it just starts playing. */
    fun playNext(t: Track) {
        if (queue.isEmpty() || index !in queue.indices) {
            setQueueAndPlay(listOf(t), 0)
            return
        }
        val pos = (index + 1 + pendingNext).coerceAtMost(queue.size)
        queue = queue.toMutableList().also { it.add(pos, t) }
        for (i in history.indices) if (history[i] >= pos) history[i] = history[i] + 1
        pendingNext++
        plannedShuffle = -1
        notifyListeners()
    }

    /** "Add to queue": the song plays right after the current one (after earlier queued songs, so they keep
     *  the order they were added in) and never interrupts playback. It goes AHEAD of the rest of the list the
     *  current song came from (e.g. the YouTube search results, which all sit in the queue), otherwise it would
     *  only come up after dozens of songs. A song that is already waiting further down is moved up instead.
     *  Returns false when it is the current song or already in the "queued next" block. With nothing playing
     *  the song just sits in the queue until [next] is called. */
    fun addToQueue(t: Track): Boolean {
        if (index !in queue.indices) {
            if (queue.any { it.key == t.key }) return false
            queue = queue + t
            notifyListeners()
            return true
        }
        if (queue[index].key == t.key) return false
        val existing = (index + 1 until queue.size).firstOrNull { queue[it].key == t.key }
        if (existing != null) {
            if (existing <= index + pendingNext) return false // already queued to play next
            moveToPlayNext(existing)
            return true
        }
        val pos = (index + 1 + pendingNext).coerceAtMost(queue.size)
        queue = queue.toMutableList().also { it.add(pos, t) }
        for (i in history.indices) if (history[i] >= pos) history[i] = history[i] + 1
        pendingNext++
        plannedShuffle = -1
        notifyListeners()
        return true
    }

    /** Moves the song at [from] so it plays right after the current one. */
    fun moveToPlayNext(from: Int) {
        if (from !in queue.indices || from == index || index !in queue.indices) return
        val wasPending = from in (index + 1)..(index + pendingNext)
        val t = queue[from]
        val list = queue.toMutableList()
        list.removeAt(from)
        val cur = if (from < index) index - 1 else index
        val pending = if (wasPending) pendingNext - 1 else pendingNext
        list.add((cur + 1 + pending).coerceAtMost(list.size), t)
        queue = list
        index = cur
        history.clear() // indices moved around; the shuffle "previous" trail is simply restarted
        pendingNext = pending + 1
        plannedShuffle = -1
        notifyListeners()
    }
    /** Removes the song at [i] from the queue (queue screen; Listen together: host drops a suggestion).
     *  The song that is playing right now stays. */
    fun removeFromQueue(i: Int) {
        if (index !in queue.indices || i !in queue.indices || i == index) return
        if (i > index && i <= index + pendingNext) pendingNext--
        queue = queue.toMutableList().also { it.removeAt(i) }
        if (i < index) index--
        history.removeAll { it == i }
        for (k in history.indices) if (history[k] > i) history[k] = history[k] - 1
        plannedShuffle = -1
        notifyListeners()
    }

    /** Playback speed (1 = normal), kept across songs and app restarts. Not applied to live radio. */
    var playbackSpeed = 1f
        private set

    fun setPlaybackSpeed(s: Float) {
        playbackSpeed = s.coerceIn(0.5f, 2f)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_SPEED, playbackSpeed).apply()
        remote?.setSpeed(playbackSpeed)
        val p = player
        // Only touch a player that is already playing (see YouTubeStreamEngine.applySpeed); a paused one
        // gets the speed in play().
        if (p != null && prepared && current?.source != Track.SOURCE_RADIO) {
            try { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(playbackSpeed) } catch (_: Exception) { }
        }
        notifyListeners()
    }

    private fun applyLocalSpeed(p: MediaPlayer) {
        if (current?.source == Track.SOURCE_RADIO) return
        if (playbackSpeed == 1f && !p.isPlaying) return
        try { p.playbackParams = p.playbackParams.setSpeed(playbackSpeed) } catch (_: Exception) { }
    }

    val isPlaying: Boolean
        get() = remote?.isPlaying ?: ((player != null && prepared && (player?.isPlaying == true)) || (driveLoading && driveAutoStart))
    val position: Int
        get() = remote?.position ?: (if (prepared) player?.currentPosition ?: 0 else 0)
    val duration: Int
        get() = remote?.duration?.takeIf { it > 0 } ?: (current?.durationMs?.toInt() ?: 0)

    private val engineCallback = object : RemoteEngine.Callback {
        override fun onStarted() {
            consecutiveErrors = 0
            acquireWake()
            goForeground()
            notifyListeners()
        }

        override fun onStateChanged() {
            if (remote?.isPlaying == true) {
                acquireWake()
                goForeground()
            } else {
                releaseWake()
                leaveForeground()
            }
            notifyListeners()
        }

        override fun onEnded() = onTrackCompleted()

        override fun onError(code: Int) = onRemoteError(code)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        statsLast = SystemClock.elapsedRealtime()
        handler.postDelayed(statsTick, 5_000L)
        handler.postDelayed(prefetchTick, 4_000L)
        LastFm.flushAsync(this) // send scrobbles that were waiting for a connection
        audioManager = getSystemService(AudioManager::class.java)
        musicSessionId = audioManager.generateAudioSessionId()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        shuffleMode = if (prefs.contains(KEY_SHUFFLE_MODE)) prefs.getInt(KEY_SHUFFLE_MODE, SHUFFLE_OFF).coerceIn(0, 2)
            else if (prefs.getBoolean(KEY_SHUFFLE, false)) SHUFFLE_ON else SHUFFLE_OFF
        repeatMode = prefs.getInt(KEY_REPEAT, REPEAT_OFF)
        playbackSpeed = prefs.getFloat(KEY_SPEED, 1f).coerceIn(0.5f, 2f)
        createChannel()
        session = MediaSession(this, "StarrySoundMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() = stopPlayback()
                override fun onSkipToQueueItem(id: Long) = playAt(id.toInt())
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId != null) playFromBrowseId(mediaId)
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) = playFromSearch(query.orEmpty())
            }, handler)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@MusicService, 1,
                    Intent(this@MusicService, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_MUSIC)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            isActive = true
        }
        // Lets Android Auto / Bluetooth head units / Assistant browse the library and control this session.
        session?.sessionToken?.let { setSessionToken(it) }
    }

    // ---------------------------------------------------------------- Android Auto / media browser

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Only song titles of the user's own library are exposed, the same as any media app does.
        val extras = Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) // list
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)  // list
        }
        return BrowserRoot(BROWSE_ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>) {
        result.detach() // the library / playlist reads below are blocking
        try {
            browseExecutor.execute {
                val items = try { buildBrowseChildren(parentId) } catch (e: Exception) {
                    android.util.Log.w("MusicService", "browse failed", e)
                    mutableListOf()
                }
                handler.post { try { result.sendResult(items) } catch (e: IllegalStateException) { /* already sent */ } }
            }
        } catch (e: Exception) {
            result.sendResult(mutableListOf())
        }
    }

    private fun browseItem(id: String, title: String, subtitle: String?, icon: android.net.Uri?, flags: Int) =
        MediaBrowser.MediaItem(
            MediaDescription.Builder()
                .setMediaId(id).setTitle(title).setSubtitle(subtitle)
                .apply { if (icon != null) setIconUri(icon) }
                .build(),
            flags
        )

    private fun artUri(t: Track): android.net.Uri? =
        if (t.artUrl.startsWith("https://")) android.net.Uri.parse(t.artUrl) else null

    private fun buildBrowseChildren(parentId: String): MutableList<MediaBrowser.MediaItem> {
        val folder = MediaBrowser.MediaItem.FLAG_BROWSABLE
        val song = MediaBrowser.MediaItem.FLAG_PLAYABLE
        if (parentId == BROWSE_ROOT) {
            return mutableListOf(
                browseItem(BR_LIKED, getString(R.string.auto_liked), null, null, folder),
                browseItem(BR_PLAYLISTS, getString(R.string.auto_playlists), null, null, folder),
                browseItem(BR_RECENT, getString(R.string.auto_recent), null, null, folder),
                browseItem(BR_DEVICE, getString(R.string.auto_device), null, null, folder)
            )
        }
        if (parentId == BR_PLAYLISTS) {
            return PlaylistStore.playlists(this).map {
                browseItem(BR_PL_PREFIX + it.id, it.name, resources.getQuantityString(R.plurals.auto_song_count, it.tracks.size, it.tracks.size), null, folder)
            }.toMutableList()
        }
        val tracks = browseTracks(parentId) ?: return mutableListOf()
        return tracks.take(MAX_BROWSE).map {
            browseItem("$parentId|${it.key}", it.title, it.artist.ifBlank { it.album }, artUri(it), song)
        }.toMutableList()
    }

    /** Songs behind a browse folder (blocking: MediaStore / disk). Null = unknown folder. */
    private fun browseTracks(parentId: String): List<Track>? {
        val list: List<Track> = when {
            parentId == BR_LIKED -> PlaylistStore.liked(this)
            parentId == BR_RECENT -> MusicPrefs.youtubeHistory(this)
            parentId == BR_DEVICE -> if (MusicLibrary.hasPermission(this)) MusicLibrary.load(this) else emptyList()
            parentId.startsWith(BR_PL_PREFIX) -> PlaylistStore.get(this, parentId.removePrefix(BR_PL_PREFIX))?.tracks ?: return null
            else -> return null
        }
        return list.filter { !it.missing }
    }

    /** A tapped song in the car / on the head unit: queue its whole folder and start at that song. */
    private fun playFromBrowseId(mediaId: String) {
        val sep = mediaId.indexOf('|')
        if (sep <= 0) return
        val parent = mediaId.substring(0, sep)
        val key = mediaId.substring(sep + 1)
        try {
            browseExecutor.execute {
                val list = browseTracks(parent) ?: return@execute
                val i = list.indexOfFirst { it.key == key }
                if (i >= 0) handler.post { setQueueAndPlay(list, i) }
            }
        } catch (e: Exception) { /* service going away */ }
    }

    /** Voice: "Play <something> on StarrySound". Searches liked songs, playlists and device music first;
     *  when nothing there matches, the words are searched on YouTube and the results play. */
    private fun playFromSearch(query: String) {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        try {
            browseExecutor.execute {
                val pool = ArrayList<Track>()
                pool += PlaylistStore.liked(this)
                PlaylistStore.playlists(this).forEach { pool += it.tracks }
                if (MusicLibrary.hasPermission(this)) pool += MusicLibrary.load(this)
                val all = pool.filter { !it.missing }.distinctBy { it.key }
                val hits = if (words.isEmpty()) all.take(MAX_BROWSE) else all.filter { t ->
                    val hay = "${t.title} ${t.artist} ${t.album}".lowercase()
                    words.all { hay.contains(it) }
                }
                if (hits.isNotEmpty()) {
                    handler.post { setQueueAndPlay(hits, 0) }
                    return@execute
                }
                if (words.isEmpty()) return@execute
                try {
                    voiceExecutor.execute {
                        val found = try {
                            YouTubeApi.search(applicationContext, query.trim(), maxResults = 10)
                        } catch (e: Exception) { emptyList() }
                        if (found.isNotEmpty()) handler.post { setQueueAndPlay(found, 0) }
                    }
                } catch (e: Exception) { /* service going away */ }
            }
        } catch (e: Exception) { /* service going away */ }
    }

    /** Android Auto & co. bind with the media-browser action; the app's own screens get the local binder. */
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == MediaBrowserService.SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
            ACTION_STOP -> stopPlayback()
            ACTION_PAUSE -> if (current == null) stopSelf() else pause()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsTick)
        handler.removeCallbacks(prefetchTick)
        handler.removeCallbacks(segmentTick)
        browseExecutor.shutdownNow()
        voiceExecutor.shutdownNow()
        handler.removeCallbacks(radioTimeout)
        handler.removeCallbacks(radioStable)
        netExecutor.shutdownNow()
        smartExecutor.shutdownNow()
        routeEffectsToGlobal()
        releasePlayer()
        releaseRemote()
        releaseWake()
        abandonFocus()
        unregisterNoisy()
        session?.release()
        session = null
        artExecutor.shutdownNow()
        driveExecutor.shutdownNow()
        if (instance === this) instance = null
        dispatchState() // tells the Screensaver the music is gone
        listeners.clear()
        super.onDestroy()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyListeners() {
        updateSession()
        updateNotification()
        dispatchState()
    }

    /** One misbehaving listener (a destroyed fragment, ...) must not crash playback. */
    private fun dispatchState() {
        for (l in listeners) {
            try { l.onMusicStateChanged() } catch (e: Exception) { android.util.Log.w("MusicService", "listener failed", e) }
        }
        for (l in globalListeners) {
            try { l.onMusicStateChanged() } catch (e: Exception) { android.util.Log.w("MusicService", "listener failed", e) }
        }
    }

    // ---------------------------------------------------------------- queue control

    /** Key of the one song picked from the YouTube search list: only that pick goes into the YouTube
     *  history (the list under the search box). Everything else only lands in "Recently played". */
    private var historyPickKey: String? = null

    /** Replaces the queue and starts playing [startIndex].
     *  [fromYoutubeSearch] = the song was chosen on the YouTube search tab, so it is also kept in the YouTube history. */
    fun setQueueAndPlay(allTracks: List<Track>, startIndex: Int, fromYoutubeSearch: Boolean = false) {
        if (allTracks.isEmpty() || startIndex !in allTracks.indices) return
        // Songs of a synced playlist that are not on this device cannot play: leave them out.
        val tracks = if (allTracks.any { it.missing }) allTracks.filter { !it.missing } else allTracks
        if (tracks.isEmpty()) return
        val start = if (tracks === allTracks) startIndex else {
            val first = allTracks.drop(startIndex).firstOrNull { !it.missing }
            (if (first != null) tracks.indexOf(first) else 0).coerceAtLeast(0)
        }
        queue = tracks
        historyPickKey = if (fromYoutubeSearch) tracks.getOrNull(start)?.key else null
        history.clear()
        resetSmart()
        smartLastFetch = 0L
        pendingNext = 0
        plannedShuffle = -1
        consecutiveErrors = 0
        lastRemoteError = 0
        playIndex(start, autoStart = true)
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    /** Ramps the volume of the current player from silence to full over [ms] (see play(fadeInMs)). */
    private var fadeToken = 0
    private fun rampVolume(ms: Long) {
        val my = ++fadeToken
        val steps = 8
        for (i in 1..steps) {
            handler.postDelayed({
                if (my != fadeToken) return@postDelayed // paused, ducked or restarted meanwhile
                val v = i / steps.toFloat()
                try { player?.setVolume(v, v) } catch (_: Exception) { }
                try { remote?.setVolume(v) } catch (_: Exception) { }
            }, ms * i / steps)
        }
    }

    /**
     * [fadeInMs] > 0 starts silent and fades in: used when the music is given back after the Quick tab,
     * so it never comes back as an abrupt full-volume burst (effects re-attaching, audio focus swapping).
     */
    fun play(fadeInMs: Long = 0L) {
        resumeAfterLoss = false
        lossToken++
        val startVol = if (fadeInMs > 0) 0f else 1f
        val r = remote
        if (r != null) {
            if (r.needsAudioFocus && !requestFocus()) return
            fadeToken++ // a ramp still running from an earlier resume must not fight this one
            r.setVolume(startVol)
            r.setSpeed(playbackSpeed)
            r.play()
            if (fadeInMs > 0) rampVolume(fadeInMs)
            acquireWake()
            registerNoisy()
            goForeground()
            notifyListeners()
            return
        }
        if (current?.source == Track.SOURCE_RADIO) radioAutoStart = true
        if (driveLoading) { // still downloading from Drive: just start as soon as it is ready
            driveAutoStart = true
            notifyListeners()
            return
        }
        val p = player
        if (p == null || !prepared) {
            // Nothing loaded yet (or still preparing): start whatever is current.
            if (current != null && p == null) playIndex(index, autoStart = true)
            return
        }
        if (!requestFocus()) return
        fadeToken++
        p.setVolume(startVol, startVol)
        routeEffectsToMusic()
        applyLocalSpeed(p)
        p.start()
        if (fadeInMs > 0) rampVolume(fadeInMs)
        registerNoisy()
        goForeground()
        notifyListeners()
    }

    /** [keepForeground] = paused only because another app took audio focus for a moment (notification sound,
     *  call...): stay a foreground service so the system does not demote / freeze us and playback can resume. */
    fun pause(keepForeground: Boolean = false) {
        fadeToken++ // cancel a fade-in that is still ramping up
        resumeAfterLoss = false // a pause the user (or the app) asked for is never auto-resumed
        val r = remote
        if (r != null) {
            resumeOnFocusGain = false
            r.pause()
            releaseWake()
            unregisterNoisy()
            if (!keepForeground) leaveForeground()
            notifyListeners()
            return
        }
        if (current?.source == Track.SOURCE_RADIO) radioAutoStart = false
        if (driveLoading) {
            driveAutoStart = false
            notifyListeners()
            return
        }
        val p = player ?: return
        resumeOnFocusGain = false
        if (prepared && p.isPlaying) p.pause()
        releaseWake()
        routeEffectsToGlobal()
        unregisterNoisy()
        if (!keepForeground) leaveForeground()
        notifyListeners()
    }

    fun next() {
        if (queue.isEmpty()) return
        val target = if (pendingNext > 0 && index + 1 < queue.size) {
            pendingNext--
            if (shuffle) history.add(index)
            index + 1
        } else if (shuffle && queue.size > 1) {
            history.add(index)
            val planned = plannedShuffle
            plannedShuffle = -1
            if (planned in queue.indices && planned != index) planned else randomOtherIndex()
        } else {
            val n = index + 1
            if (n >= queue.size) 0 else n
        }
        playIndex(target, autoStart = true)
    }

    fun previous() {
        if (queue.isEmpty()) return
        // Standard behaviour: past the first 3 seconds, restart the track instead.
        if (position > 3000 && current?.source != Track.SOURCE_RADIO) {
            seekTo(0)
            return
        }
        pendingNext = 0
        val target = if (shuffle && history.isNotEmpty()) {
            history.removeAt(history.size - 1)
        } else {
            if (index - 1 < 0) queue.size - 1 else index - 1
        }
        playIndex(target, autoStart = true)
    }

    /** Restarts the current song at the same second (e.g. after the audio language changed). */
    fun reloadCurrent() {
        val pos = position
        playIndex(index, autoStart = true)
        if (pos > 0) seekTo(pos)
    }

    fun seekTo(ms: Int) {
        if (current?.source == Track.SOURCE_RADIO) return // live: nothing to seek in
        val r = remote
        if (r != null) r.seekTo(ms) else if (prepared) player?.seekTo(ms)
        updateSession()
        dispatchState()
    }

    /** Cycles Off -> Shuffle -> Smart shuffle -> Off. */
    fun toggleShuffle() {
        val old = shuffleMode
        shuffleMode = (shuffleMode + 1) % 3
        history.clear()
        plannedShuffle = -1
        if (old == SHUFFLE_SMART) dropUnplayedSmartPicks()
        if (shuffleMode == SHUFFLE_SMART) { smartLastFetch = 0L; smartFetching = false }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_SHUFFLE_MODE, shuffleMode).apply()
        try {
            android.widget.Toast.makeText(
                this,
                when (shuffleMode) {
                    SHUFFLE_SMART -> R.string.shuffle_mode_smart
                    SHUFFLE_ON -> R.string.shuffle_mode_on
                    else -> R.string.shuffle_mode_off
                },
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) { }
        notifyListeners()
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_REPEAT, repeatMode).apply()
        notifyListeners()
    }

    /** Fully stops: releases the player and removes the notification. */
    fun stopPlayback() {
        cancelRadioTimers()
        routeEffectsToGlobal()
        releasePlayer()
        releaseRemote()
        releaseWake()
        abandonFocus()
        unregisterNoisy()
        queue = emptyList()
        index = -1
        artwork = null
        history.clear()
        resetSmart()
        pendingNext = 0
        if (foregroundActive) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        updateSession()
        dispatchState()
        stopSelf()
    }

    private fun randomOtherIndex(): Int {
        if (smartShuffle && smartKeys.isNotEmpty()) {
            fun waiting(i: Int) = queue[i].key in smartKeys && queue[i].key !in smartPlayed
            val picks = queue.indices.filter { it != index && waiting(it) }
            // About every third song is a suggested one; the rest come from the playlist itself.
            if (picks.isNotEmpty() && sinceSmart >= SMART_EVERY - 1) return picks.random()
            val own = queue.indices.filter { it != index && !waiting(it) }
            if (own.isNotEmpty()) return own.random()
        }
        var n: Int
        do { n = (0 until queue.size).random() } while (n == index)
        return n
    }

    // ---------------------------------------------------------------- playback core

    private fun playIndex(newIndex: Int, autoStart: Boolean) {
        val track = queue.getOrNull(newIndex) ?: return
        index = newIndex
        noteSmartPlayed(track)
        try { MusicPrefs.addRecent(this, track) } catch (_: Exception) { } // Home "Recently played"
        statsListened = 0L
        statsCounted = false
        lfmListened = 0L
        lfmNowSent = false
        lfmScrobbled = false
        releasePlayer()
        artwork = null
        val token = ++loadToken
        driveLoading = false

        // Needs to be a started + foreground service from here on so playback survives
        // the app being closed. startForeground must follow quickly.
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this, Intent(this, MusicService::class.java)
            )
        } catch (e: Exception) {
            // Background-start restriction (Android 12+): we are already running, so just carry on.
            android.util.Log.w("MusicService", "startForegroundService refused", e)
        }
        goForeground()

        clearSegments()
        if (track.source == Track.SOURCE_ONLINE && historyPickKey != null && track.key == historyPickKey) {
            historyPickKey = null
            MusicPrefs.addOnlineHistory(this, track) // picked from the Online search list
        }
        if (track.source == Track.SOURCE_YOUTUBE) {
            loadSegments(track, token)
            // The YouTube-tab history only holds songs picked from the search list; Quick, playlists,
            // Home and autoplay only go to "Recently played" (addRecent above).
            if (historyPickKey != null && track.key == historyPickKey) {
                historyPickKey = null
                MusicPrefs.addYoutubeHistory(this, track)
            }
            routeEffectsToGlobal() // YouTube audio is not ours: effects go back to the global mix
            playRemote(track)
            loadArtwork(track, token)
            return
        }
        releaseRemote() // switching from YouTube back to a local file / radio stream

        if (track.source == Track.SOURCE_RADIO) {
            radioAttempts = 0
            lastRadioError = ""
            radioAutoStart = autoStart
            startRadio(track, token)
            notifyListeners()
            loadArtwork(track, token)
            return
        }
        cancelRadioTimers()

        if (track.source == Track.SOURCE_DRIVE) {
            startDrive(track, token, autoStart)
            notifyListeners()
            return
        }

        val mp = try {
            createPlayer().also {
                // Online-tab song (Internet Archive, Audius, Jamendo, ccMixter): a plain https file link (seekable), not a content:// uri.
                if (track.source == Track.SOURCE_ONLINE) it.setDataSource(track.remoteId)
                else it.setDataSource(applicationContext, track.uri)
            }
        } catch (e: Exception) {
            onPlaybackError()
            return
        }
        try {
            mp.setOnPreparedListener {
                if (token != loadToken) return@setOnPreparedListener
                prepared = true
                consecutiveErrors = 0
                if (autoStart) play() else notifyListeners()
            }
            mp.setOnCompletionListener { if (token == loadToken) onTrackCompleted() }
            mp.setOnErrorListener { _, _, _ ->
                if (token == loadToken) onPlaybackError()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            onPlaybackError()
            return
        }
        notifyListeners()
        loadArtwork(track, token)
    }

    /** Google Drive song: download it to the cache (background), then play the file like a local one. */
    private fun startDrive(track: Track, token: Int, autoStart: Boolean) {
        val app = applicationContext
        driveLoading = true
        driveAutoStart = autoStart
        lastDriveError = ""
        try {
            driveExecutor.execute {
                // The executor has one thread: a song skipped while it waited must not hold up the current one.
                if (token != loadToken) return@execute
                var failure = ""
                var file: java.io.File? = DriveMusic.cachedFile(app, track.remoteId)
                var stream: DriveMusic.StreamingSource? = null
                if (file == null) {
                    // Not cached yet: play while it downloads into the cache.
                    try {
                        stream = DriveMusic.startStream(app, track.remoteId) { token != loadToken }
                    } catch (e: DriveMusic.NoLengthException) {
                        // Size unknown: download it completely first.
                        try { file = DriveMusic.fetch(app, track.remoteId) { token != loadToken } } catch (e2: Exception) {
                            failure = e2.message?.takeIf { it.isNotBlank() } ?: e2.javaClass.simpleName
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MusicService", "drive fetch: ${e.message}")
                        failure = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    }
                }
                val st = stream
                val fl = file
                handler.post {
                    if (token != loadToken) { st?.close(); return@post }
                    driveLoading = false
                    if (fl == null && st == null) {
                        lastDriveError = failure
                        listeners.forEach { it.onMusicError(RemoteEngine.ERR_DRIVE) }
                        // Started from a screen that has already closed (Sleep sounds): nobody is listening, so say it here.
                        if (listeners.isEmpty() && (queue.size <= 1 || consecutiveErrors + 1 >= queue.size)) {
                            android.widget.Toast.makeText(
                                app, app.getString(R.string.drive_music_play_failed) + if (failure.isBlank()) "" else "\n($failure)",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        onPlaybackError()
                        return@post
                    }
                    if (fl != null) {
                        // Downloaded copy on disk: read format / bit depth / sample rate for the player's quality badge.
                        try { artExecutor.execute { AudioQuality.probeFile(applicationContext, track, fl); handler.post { notifyListeners() } } } catch (_: Exception) { }
                    }
                    val mp = try {
                        createPlayer().also {
                            if (st != null) { driveSource = st; it.setDataSource(st) }
                            else it.setDataSource(fl!!.absolutePath)
                        }
                    } catch (e: Exception) {
                        st?.close()
                        driveSource = null
                        onPlaybackError()
                        return@post
                    }
                    try {
                        mp.setOnPreparedListener {
                            if (token != loadToken) return@setOnPreparedListener
                            prepared = true
                            consecutiveErrors = 0
                            if (driveAutoStart) play() else notifyListeners()
                        }
                        mp.setOnCompletionListener { if (token == loadToken) onTrackCompleted() }
                        mp.setOnErrorListener { _, _, _ ->
                            if (token == loadToken) onPlaybackError()
                            true
                        }
                        mp.prepareAsync()
                    } catch (e: Exception) {
                        onPlaybackError()
                    }
                }
            }
        } catch (e: Exception) {
            // Executor shut down: service is going away.
        }
    }

    /** A fresh MediaPlayer on the shared music audio session (becomes [player]); data source not set. */
    private fun createPlayer(): MediaPlayer {
        val mp = MediaPlayer()
        player = mp
        prepared = false
        playerOnMusicSession = false
        if (musicSessionId > 0) {
            try {
                mp.audioSessionId = musicSessionId // must happen while the player is still idle
                playerOnMusicSession = true
            } catch (e: Exception) {
                // Keep the player's own session; effects simply stay on the global mix.
            }
        }
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        return mp
    }

    // ---------------------------------------------------------------- radio (timeout + reconnect)

    private fun cancelRadioTimers() {
        radioGen++ // invalidates every pending radio callback
        handler.removeCallbacks(radioTimeout)
        handler.removeCallbacks(radioStable)
    }

    /**
     * Connects to the radio stream of [track]. Playlist links (.pls/.m3u) are first turned into the
     * real stream URL on a background thread. The whole connect step has a time limit
     * ([RADIO_CONNECT_TIMEOUT_MS]); on timeout, error or a dropped stream [radioFailed] reconnects.
     */
    private fun startRadio(track: Track, token: Int) {
        releasePlayer()
        handler.removeCallbacks(radioStable)
        val gen = ++radioGen
        acquireWake()
        handler.removeCallbacks(radioTimeout)
        handler.postDelayed(radioTimeout, RADIO_RESOLVE_TIMEOUT_MS)
        val attempt = radioAttempts
        val raw = track.remoteId
        val known = if (radioUrlsFor == raw) radioUrls else emptyList()
        // First try: the station's own link, untouched (a plain stream link is opened straight away;
        // a .pls/.m3u is resolved once). Only retries follow redirects themselves, as a fallback.
        val fast = if (attempt == 0) {
            if (RadioPlaylist.isPlaylistUrl(raw)) RadioPlaylist.cached(raw) else listOf(raw)
        } else {
            RadioPlaylist.cachedFollowed(raw)
        }
        if (fast != null) {
            handler.removeCallbacks(radioTimeout)
            handler.postDelayed(radioTimeout, RADIO_CONNECT_TIMEOUT_MS)
            openRadio(fast[attempt % fast.size], token, gen, attempt)
            return
        }
        try {
            netExecutor.execute {
                val urls = try {
                    if (attempt == 0) RadioPlaylist.resolve(raw) else RadioPlaylist.resolveFollowed(raw)
                } catch (e: Exception) {
                    android.util.Log.w("MusicService", "radio playlist: ${e.message}")
                    known.ifEmpty { listOf(raw) } // reuse what was found before, else try the link as is
                }
                handler.post {
                    if (token != loadToken || gen != radioGen) return@post
                    radioUrls = urls
                    radioUrlsFor = raw
                    // The connect time limit starts only now: finding the real address must not eat it.
                    handler.removeCallbacks(radioTimeout)
                    handler.postDelayed(radioTimeout, RADIO_CONNECT_TIMEOUT_MS)
                    openRadio(urls[attempt % urls.size], token, gen, attempt)
                }
            }
        } catch (e: Exception) {
            // Executor shut down: service is going away.
        }
    }

    private fun openRadio(url: String, token: Int, gen: Int, attempt: Int) {
        // The first try plays the stream URL as is. Every second try (2nd, 4th, ...) sends explicit
        // headers instead: some servers/CDNs refuse the framework player's identity or choke on the
        // interleaved ICY metadata. Waiting until the very last try cost minutes of timeouts.
        val withHeaders = attempt % 2 == 1
        val host = try { java.net.URI(url).host.orEmpty() } catch (_: Exception) { "" }
        val mp = try {
            createPlayer().also {
                if (withHeaders) {
                    it.setDataSource(
                        applicationContext, android.net.Uri.parse(url),
                        mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) StarrySound/1.0", "Icy-MetaData" to "0")
                    )
                } else {
                    it.setDataSource(url) // live stream URL
                }
            }
        } catch (e: Exception) {
            lastRadioError = "$host: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            android.util.Log.w("MusicService", "radio setDataSource: $lastRadioError")
            radioFailed(gen)
            return
        }
        try {
            mp.setOnPreparedListener {
                if (token != loadToken || gen != radioGen) return@setOnPreparedListener
                handler.removeCallbacks(radioTimeout)
                prepared = true
                consecutiveErrors = 0
                // Only a connection that stays up for a while counts as healthy again.
                handler.postDelayed(radioStable, RADIO_STABLE_MS)
                if (radioAutoStart) play() else notifyListeners()
            }
            // A live stream "completing" means the connection dropped.
            mp.setOnCompletionListener { if (token == loadToken && gen == radioGen) radioFailed(gen) }
            mp.setOnErrorListener { _, what, extra ->
                if (token == loadToken && gen == radioGen) {
                    lastRadioError = "$host: error $what/$extra"
                    android.util.Log.w("MusicService", "radio error: $lastRadioError (headers=$withHeaders)")
                    radioFailed(gen)
                }
                true
            }
            mp.setOnInfoListener { _, what, _ ->
                if (token == loadToken && gen == radioGen && prepared) {
                    when (what) {
                        // Buffering that never ends is a dead connection: same time limit.
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            handler.removeCallbacks(radioTimeout)
                            handler.postDelayed(radioTimeout, RADIO_STALL_TIMEOUT_MS)
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> handler.removeCallbacks(radioTimeout)
                    }
                }
                false
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            lastRadioError = "$host: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            radioFailed(gen)
        }
    }

    /** The radio connection failed, timed out or dropped: reconnect after a growing pause, give up after a few tries. */
    private fun radioFailed(gen: Int) {
        if (gen != radioGen || current?.source != Track.SOURCE_RADIO) return
        handler.removeCallbacks(radioTimeout)
        handler.removeCallbacks(radioStable)
        val token = loadToken
        val track = current ?: return
        radioGen++ // anything still pending from the dead connection is now stale
        releasePlayer()
        radioAttempts++
        RadioPlaylist.invalidate(track.remoteId) // a stale resolved link must not be reused for the retry
        if (radioAttempts > RADIO_MAX_RECONNECTS) {
            radioAttempts = 0
            onPlaybackError()
            return
        }
        notifyListeners()
        // Tell the user right away that the first try failed (the final error would come much later).
        if (radioAttempts == 1) listeners.forEach { it.onMusicError(RemoteEngine.ERR_RADIO_RETRY) }
        val delay = (1000L shl (radioAttempts - 1)).coerceAtMost(8_000L)
        val g = radioGen
        handler.postDelayed({
            if (token == loadToken && g == radioGen && current === track) startRadio(track, token)
        }, delay)
    }

    private fun onTrackCompleted() {
        when {
            // A live stream "completing" means the connection dropped: stop and tell the user.
            current?.source == Track.SOURCE_RADIO -> radioFailed(radioGen)
            // Online tracks are simply started again from the top.
            repeatMode == REPEAT_ONE && remote != null -> playIndex(index, autoStart = true)
            repeatMode == REPEAT_ONE -> {
                player?.seekTo(0)
                player?.start()
                notifyListeners()
            }
            // Last track, repeat off: stop at the beginning of the queue's last track.
            !shuffle && repeatMode == REPEAT_OFF && index >= queue.size - 1 -> {
                if (remote != null) remote?.seekTo(0) else player?.seekTo(0)
                pause()
            }
            else -> next()
        }
    }

    private fun onPlaybackError() {
        consecutiveErrors++
        if (consecutiveErrors >= queue.size || queue.size <= 1) {
            // Everything failed (or the only track did): give up.
            routeEffectsToGlobal()
            releasePlayer()
            remote?.pause()
            prepared = false
            releaseWake()
            leaveForeground()
            notifyListeners()
            if (current?.source == Track.SOURCE_RADIO) {
                listeners.forEach { it.onMusicError(RemoteEngine.ERR_RADIO) }
            }
            if (current?.source == Track.SOURCE_YOUTUBE) {
                val c = if (lastRemoteError != 0) lastRemoteError else RemoteEngine.ERR_YT_UNPLAYABLE
                listeners.forEach { it.onMusicError(c) }
            }
            return
        }
        next()
    }

    private fun releasePlayer() {
        player?.let {
            try { it.reset() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        prepared = false
        driveSource?.close() // a Drive song that was still downloading stops downloading
        driveSource = null
    }

    // ---------------------------------------------------------------- remote engine (YouTube)

    private fun playRemote(track: Track) {
        if (remote == null || remoteKind != track.source) {
            releaseRemote()
            remote = YouTubeEngine(applicationContext, handler, engineCallback)
            remoteKind = track.source
        }
        val r = remote ?: return
        r.load(track)
        r.setSpeed(playbackSpeed)
        if (r.needsAudioFocus && !requestFocus()) {
            notifyListeners()
            return
        }
        r.play()
        acquireWake()
        registerNoisy()
        notifyListeners()
    }

    private fun releaseRemote() {
        remote?.release()
        remote = null
        remoteKind = -1
    }

    /** Skips an unplayable video; a player-wide problem is reported to the user instead. */
    private fun onRemoteError(code: Int) {
        // A per-video failure: skip it. A player-wide problem (refused / never started) is
        // reported right away instead, since skipping to the next video would not help.
        if (remoteKind == Track.SOURCE_YOUTUBE) {
            lastRemoteError = code
            if (code != RemoteEngine.ERR_YT_CONFIG && code != RemoteEngine.ERR_YT_TIMEOUT) {
                onPlaybackError()
                return
            }
            remote?.pause() // make sure a late-starting player does not suddenly begin playing
        }
        releaseWake()
        leaveForeground()
        notifyListeners()
        listeners.forEach { it.onMusicError(code) }
    }

    /** Keeps the CPU awake while a remote engine plays with the screen off (MediaPlayer has its own). */
    private fun acquireWake() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarrySound:remote")
            .also { it.setReferenceCounted(false); wakeLock = it }
        if (!wl.isHeld) wl.acquire(6 * 60 * 60 * 1000L)
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ---------------------------------------------------------------- effects routing

    /** Attaches the EQ chain to the local player's audio session (no-op if already there). */
    private fun routeEffectsToMusic() {
        if (musicRoutingFailed || !playerOnMusicSession || musicSessionId <= 0) return
        val mgr = EffectService.instance?.effectManager ?: return
        if (mgr.boundSession == musicSessionId) return
        if (!mgr.rebind(musicSessionId)) musicRoutingFailed = true
    }

    /** Puts the EQ chain back on the global mix (no-op if it is already there). */
    private fun routeEffectsToGlobal() {
        val mgr = EffectService.instance?.effectManager ?: return
        if (mgr.boundSession == musicSessionId && musicSessionId > 0) {
            mgr.rebind(AudioEffectManager.GLOBAL_SESSION)
        }
    }

    // ---------------------------------------------------------------- audio focus / noisy

    private var focusRetries = 0

    /**
     * True when focus is ours and playback may start. A refused request used to leave the song silently paused
     * (it looked like a "paused" player right after Play from Quick, while the preview's focus was just being
     * handed back): now a delayed grant starts playback by itself on gain, and a refusal is retried a few times.
     */
    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
            .build().also { focusRequest = it }
        return when (audioManager.requestAudioFocus(req)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> { focusRetries = 0; true }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> { resumeOnFocusGain = true; false } // onFocusChange(GAIN) plays
            else -> { scheduleFocusRetry(); false }
        }
    }

    private fun scheduleFocusRetry() {
        if (focusRetries >= 3) { focusRetries = 0; return }
        focusRetries++
        val lt = loadToken
        val ft = fadeToken
        handler.postDelayed({
            // Still the same song and nobody paused / skipped meanwhile: try again.
            if (lt == loadToken && ft == fadeToken && current != null && !isPlaying) play()
        }, 350L * focusRetries)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    /** Playing when another app took focus (notification sound, call...): resume by ourselves if focus never comes back. */
    private var resumeAfterLoss = false
    private var lossToken = 0

    /**
     * Many notification sounds ask for focus as AUDIOFOCUS_GAIN (permanent) or never hand it back properly, so the
     * song stayed paused for good. After a loss we look again a few times: once nothing else is playing music and
     * the phone is not in a call, the song continues. Another music app that really took over keeps playing, so we stay out.
     */
    private fun scheduleLossResume() {
        val my = ++lossToken
        for (delay in longArrayOf(3_500L, 8_000L, 16_000L)) {
            handler.postDelayed({
                if (my != lossToken || !resumeAfterLoss || isPlaying || current == null) return@postDelayed
                val idle = audioManager.mode == AudioManager.MODE_NORMAL && !audioManager.isMusicActive
                if (idle) { resumeAfterLoss = false; resumeOnFocusGain = false; play() }
            }, delay)
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                val wasPlaying = isPlaying
                resumeOnFocusGain = false
                pause(keepForeground = true)
                if (wasPlaying) { resumeAfterLoss = true; scheduleLossResume() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = isPlaying
                pause(keepForeground = true)
                resumeOnFocusGain = wasPlaying
                if (wasPlaying) { resumeAfterLoss = true; scheduleLossResume() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                fadeToken++
                player?.setVolume(0.25f, 0.25f)
                remote?.setVolume(0.25f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                remote?.setVolume(1f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        androidx.core.content.ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        noisyRegistered = false
    }

    // ---------------------------------------------------------------- artwork

    /** Saver mode was just switched on: drop the cover and let every screen redraw its placeholder. */
    fun notifyCoverMode() {
        artwork = null
        notifyListeners()
    }

    /** Re-fetches the cover of the current track, e.g. after saver mode was switched off. */
    fun refreshArtwork() {
        val t = current ?: return
        if (artwork == null) loadArtwork(t, loadToken)
    }

    private fun loadArtwork(track: Track, token: Int) {
        // Saver mode shows no covers (placeholder everywhere), so don't fetch or decode any.
        if (MusicPrefs.saver(this)) return
        try {
            artExecutor.execute {
                val bmp = try {
                    if (track.source != Track.SOURCE_LOCAL) {
                        if (track.artUrl.isBlank() || MusicPrefs.saver(this)) null else Http.bitmap(track.artUrl)
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        contentResolver.loadThumbnail(track.uri, Size(384, 384), null)
                    } else {
                        embeddedArt(track)
                    }
                } catch (e: Exception) {
                    if (track.source == Track.SOURCE_LOCAL) embeddedArt(track) else null
                }
                handler.post {
                    if (token == loadToken && bmp != null) {
                        artwork = bmp
                        notifyListeners()
                    }
                }
            }
        } catch (e: Exception) {
            // Executor shut down: service is going away.
        }
    }

    private fun embeddedArt(track: Track): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(applicationContext, track.uri)
            r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- session + notification

    private fun updateSession() {
        val s = session ?: return
        val t = current
        if (t != null) {
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                    .apply { artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                    .build()
            )
        }
        val state = when {
            t == null -> PlaybackState.STATE_STOPPED
            isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
                )
                .setActiveQueueItemId(if (t != null && index >= 0) index.toLong() else MediaSession.QueueItem.UNKNOWN_ID.toLong())
                .setState(state, position.toLong(), if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build()
        )
        publishQueue(s)
    }

    private var publishedQueue: List<Track>? = null
    private var publishedIndex = -2

    /** The "up next" list shown by Android Auto / the lock screen. Rebuilt only when the queue or position in it changes. */
    private fun publishQueue(s: MediaSession) {
        if (publishedQueue === queue && publishedIndex == index) return
        publishedQueue = queue
        publishedIndex = index
        if (queue.isEmpty()) { s.setQueue(null); return }
        val from = (index - 5).coerceAtLeast(0)
        val to = (from + 60).coerceAtMost(queue.size)
        s.setQueue(
            (from until to).map { i ->
                val t = queue[i]
                MediaSession.QueueItem(
                    MediaDescription.Builder().setMediaId(t.key).setTitle(t.title)
                        .setSubtitle(t.artist.ifBlank { t.album })
                        .apply { artUri(t)?.let { setIconUri(it) } }
                        .build(),
                    i.toLong()
                )
            }
        )
        s.setQueueTitle(getString(R.string.auto_queue))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.music_channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun servicePi(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(): Notification {
        val t = current
        val playing = isPlaying
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_MUSIC)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(icon: Int, label: Int, action: String, code: Int) =
            Notification.Action.Builder(
                Icon.createWithResource(this, icon), getString(label), servicePi(action, code)
            ).build()

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(t?.title ?: getString(R.string.music_nothing_playing))
            .setContentText(t?.artist?.ifBlank { null } ?: t?.album ?: "")
            .setContentIntent(openIntent)
            .setDeleteIntent(servicePi(ACTION_STOP, 4))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(action(R.drawable.ic_prev, R.string.music_previous, ACTION_PREV, 1))
            .addAction(
                if (playing) action(R.drawable.ic_pause, R.string.music_pause, ACTION_PLAY_PAUSE, 2)
                else action(R.drawable.ic_play, R.string.music_play, ACTION_PLAY_PAUSE, 2)
            )
            .addAction(action(R.drawable.ic_next, R.string.music_next, ACTION_NEXT, 3))
            .setStyle(
                Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .apply { session?.sessionToken?.let { setMediaSession(it) } }
            )
        artwork?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun goForeground() {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            foregroundActive = true
        } catch (e: Exception) {
            // Not allowed to promote right now (rare background-start restriction):
            // playback still works while the app is open.
            foregroundActive = false
        }
    }

    /** Paused: keep the notification (so it can resume) but let it be swiped away. */
    private fun leaveForeground() {
        if (foregroundActive) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foregroundActive = false
        }
    }

    private fun updateNotification() {
        if (current == null) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }
}
