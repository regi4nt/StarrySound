package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.fxsoundlite.app.databinding.FragmentQuickBinding
import java.util.concurrent.Executors

/**
 * Page 1 of MainActivity's pager ("Quick"): a vertical, reels-style feed. Every page is one song
 * shown as its cover, and only its CHORUS is played (a [QuickFeed.CLIP_MS] clip starting where the
 * chorus most likely begins). When the clip ends the feed scrolls down to the next song by itself;
 * the feed loads more when it runs low, and refreshing (button, or tapping the Quick tab again)
 * gives different songs. The action rail on each page: play full song / add to playlist / add to
 * queue / download / share.
 *
 * The previews use their own [YouTubeStreamEngine], NOT [MusicService], so the user's queue is
 * never touched by browsing: the service's music is only paused while previews play and resumed
 * when leaving the tab. (The mini player is hidden on this page, see MainActivity.)
 *
 * The page is a ViewPager2 child that is only STARTED, never RESUMED, when it is not the visible
 * page, so onResume / onPause mean "the Quick tab is on screen / was left".
 */
class QuickFragment : Fragment(), QuickActions {

    companion object {
        private const val FIRST_BATCH = 12
        private const val MORE_BATCH = 10
        private const val LOAD_MORE_WHEN_LEFT = 3
        private const val FULL_QUEUE_MAX = 30
        private const val MENU_DOWNLOAD = 1
        private const val MENU_SHARE = 2
        private const val MENU_INFO = 3
        private const val MENU_LYRICS = 4
        private const val MENU_VOLUME = 5
        private const val TICK_MS = 250L

        // Survives the fragment being re-created (rotation, accent change) within the process.
        private var cachedFeed: List<Track> = emptyList()
        private var cachedPos = 0
    }

    private var _binding: FragmentQuickBinding? = null
    private val binding get() = _binding!!

    private val feed = ArrayList<Track>()
    private lateinit var adapter: QuickAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val loaders = Executors.newFixedThreadPool(2)

    // ---- lyrics under the cover (LRCLIB via LyricsApi; one entry per track key, null = none found)
    private class LyricView(val times: LongArray, val texts: Array<String>, val synced: Boolean)
    private val lyricCache = HashMap<String, LyricView?>()
    private val lyricPending = HashSet<String>()
    private val lyricsExec = Executors.newFixedThreadPool(2)

    private var service: MusicService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MusicService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    // ---- feed loading
    @Volatile private var genToken = 0
    private var loading = false
    private var advanceWhenLoaded = false

    // ---- preview playback
    private var visible = false               // between onResume and onPause
    private var engine: YouTubeStreamEngine? = null
    private var currentPos = 0                // reel that is (or should be) playing
    private var loadedKey: String? = null     // track currently loaded in the engine
    private var clipStart = 0L
    private var clipEnd = 0L
    private var previewStarted = false        // audio really started (not just requested)
    private var paused = false                // user (or audio focus) paused the preview
    private var clipDone = false              // clip finished, waiting for the scroll to the next reel
    private var seekFixed = false
    private var errorStreak = 0
    private var suppressAutoStart = false     // after "play full": do not start previews by themselves
    private var resumeServiceAfter = false    // we paused the user's music: resume it when leaving
    private var lastFullAt = 0L
    private var audioTaken = false            // the user's music is paused and audio focus is ours (see takeAudio)

    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (engine == null) return@OnAudioFocusChangeListener // preview already stopped
                engine?.pause()
                paused = true
                updateUi()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine?.setVolume(0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> engine?.setVolume(1f)
        }
    }

    private val engineCallback = object : RemoteEngine.Callback {
        override fun onStarted() {
            // Late callback of an engine that was already stopped (e.g. "play full" tapped while the preview was
            // still loading): it must not pause the song that has just been queued.
            if (!visible || engine == null || suppressAutoStart) return
            // The preview was started silent while the user's music kept playing: only now, when it really
            // has sound, pause that music and give the preview full volume (see startPreview).
            if (!audioTaken) {
                takeAudio()
                engine?.setVolume(1f)
            }
            previewStarted = true
            errorStreak = 0
            updateUi()
        }

        override fun onStateChanged() {}

        // The whole song was shorter than expected: same as the clip ending.
        override fun onEnded() {
            if (visible && engine != null && !suppressAutoStart && !paused) advance()
        }

        override fun onError(code: Int) {
            if (visible && engine != null && !suppressAutoStart) onPreviewError()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (visible) handler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQuickBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = QuickAdapter(feed, this)
        binding.quickPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        binding.quickPager.offscreenPageLimit = 1
        (binding.quickPager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER // no glow / stretch at the first and last reel
        binding.quickPager.adapter = adapter
        binding.quickPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                cachedPos = position
                feed.getOrNull(position)?.let { ensureLyrics(it) }
                if (position >= feed.size - LOAD_MORE_WHEN_LEFT) loadMore()
                if (position != currentPos || loadedKey != feed.getOrNull(position)?.key) {
                    currentPos = position
                    if (visible) startPreview(position, force = false)
                }
            }
        })
        applyQuickLock(MusicPrefs.quickLock(requireContext()))
        binding.quickLock.setOnClickListener {
            val on = !MusicPrefs.quickLock(requireContext())
            MusicPrefs.setQuickLock(requireContext(), on)
            applyQuickLock(on)
            Toast.makeText(requireContext(), if (on) R.string.quick_lock_on else R.string.quick_lock_off, Toast.LENGTH_SHORT).show()
        }
        binding.quickRefresh.setOnClickListener { refresh() }
        binding.quickRetry.setOnClickListener { refresh() }

        // Re-created within the same process: show the same songs again instead of new ones.
        if (cachedFeed.isNotEmpty()) {
            // The same fragment instance can get a new view (pager recreating it) while `feed` still holds
            // the old songs: clear first, or every song shows twice.
            feed.clear()
            feed.addAll(cachedFeed)
            adapter.notifyDataSetChanged()
            currentPos = cachedPos.coerceIn(0, feed.size - 1)
            binding.quickPager.setCurrentItem(currentPos, false)
        }
    }

    override fun onStart() {
        super.onStart()
        requireContext().bindService(
            Intent(requireContext(), MusicService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            requireContext().unbindService(connection)
            bound = false
            service = null
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        when {
            // First time on this tab (or the last load failed): fetch songs now, not at app start,
            // so users who never open Quick spend no data on it.
            feed.isEmpty() -> if (!loading) refresh()
            // Back from the full player: the full song keeps playing, previews wait for a tap/swipe.
            suppressAutoStart -> { paused = true; updateUi() }
            else -> startPreview(currentPos, force = true)
        }
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(ticker)
        stopPreviewAudio()
        // Give the user's music back (or, if they queued a song while nothing was playing, start it).
        val svc = service ?: MusicService.instance
        if (resumeServiceAfter) {
            resumeServiceAfter = false
            svc?.play(fadeInMs = 400L) // fade in: never an abrupt full-volume burst when leaving the tab
        } else if (svc != null && !suppressAutoStart && svc.current == null && svc.queueTracks.isNotEmpty()) {
            svc.next()
        }
        super.onPause()
    }

    override fun onDestroy() {
        loaders.shutdownNow()
        lyricsExec.shutdownNow()
        super.onDestroy()
    }

    override fun onDestroyView() {
        genToken++ // drop any load still running for this view
        handler.removeCallbacksAndMessages(null)
        // Their completion callbacks were just removed: without this the next view thinks a load / lyrics
        // lookup is still running, never refreshes (blank tab) and never fetches those lyrics again.
        loading = false
        advanceWhenLoaded = false
        lyricPending.clear()
        stopPreviewAudio()
        cachedFeed = feed.toList()
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ feed

    /** New songs: clears the feed and loads a fresh, different batch. */
    fun refresh() {
        val b = _binding ?: return
        stopPreviewAudio()
        feed.clear()
        adapter.notifyDataSetChanged()
        cachedFeed = emptyList()
        cachedPos = 0
        currentPos = 0
        loadedKey = null
        suppressAutoStart = false
        paused = false
        advanceWhenLoaded = false
        b.quickEmpty.visibility = View.GONE
        loadFeed(FIRST_BATCH)
    }

    private fun loadMore() {
        if (!loading && _binding != null) loadFeed(MORE_BATCH)
    }

    private fun loadFeed(target: Int) {
        val b = _binding ?: return
        val app = requireContext().applicationContext
        val my = ++genToken
        loading = true
        if (feed.isEmpty()) b.quickLoading.visibility = View.VISIBLE
        val exclude = HashSet<String>(QuickFeed.seen(app))
        feed.forEach { exclude.add(it.remoteId) }
        loaders.execute {
            try {
                QuickFeed.generate(app, exclude, target, { my != genToken }) { chunk ->
                    handler.post { if (my == genToken) onChunk(chunk) }
                }
            } catch (e: Throwable) {
                android.util.Log.w("QuickFragment", "feed failed", e)
            }
            handler.post { if (my == genToken) onLoadFinished() }
        }
    }

    private fun onChunk(chunk: List<Track>) {
        val b = _binding ?: return
        val wasEmpty = feed.isEmpty()
        val start = feed.size
        feed.addAll(chunk)
        adapter.notifyItemRangeInserted(start, chunk.size)
        QuickFeed.markSeen(requireContext(), chunk.map { it.remoteId })
        b.quickLoading.visibility = View.GONE
        b.quickEmpty.visibility = View.GONE
        // Start resolving stream links right away (first 3 of the batch), in parallel with the
        // first reel loading, so the next swipes already have their link.
        val ctx = requireContext().applicationContext
        chunk.filter { it.source == Track.SOURCE_YOUTUBE }.take(3)
            .forEach { YouTubeStreamEngine.prefetch(ctx, it.remoteId, video = videoOn()) }
        if (wasEmpty) {
            currentPos = 0
            if (visible && !suppressAutoStart) startPreview(0, force = true)
        } else if (advanceWhenLoaded) {
            advanceWhenLoaded = false
            advance()
        }
    }

    private fun onLoadFinished() {
        val b = _binding ?: return
        loading = false
        b.quickLoading.visibility = View.GONE
        if (feed.isEmpty()) b.quickEmpty.visibility = View.VISIBLE
        if (advanceWhenLoaded) { // nothing new came: replay this clip rather than going silent
            advanceWhenLoaded = false
            if (visible && feed.isNotEmpty()) startPreview(currentPos, force = true)
        }
    }

    // ------------------------------------------------------------------ preview playback

    private fun startPreview(pos: Int, force: Boolean) {
        val t = feed.getOrNull(pos) ?: return
        if (!force && loadedKey == t.key && engine != null) return
        currentPos = pos
        suppressAutoStart = false
        paused = false
        clipDone = false
        seekFixed = false
        previewStarted = false
        clipStart = QuickFeed.chorusStartMs(t.durationMs)
        clipEnd = clipStart + QuickFeed.CLIP_MS
        loadedKey = t.key

        // If the user's music is playing, leave it alone until the preview actually starts to sound: leaving the
        // tab before that then changes nothing (no pause, no focus swap, no resume = no volume jump).
        val svc0 = service ?: MusicService.instance
        val waitForSound = !audioTaken && svc0 != null && svc0.isPlaying
        if (!waitForSound) takeAudio()
        val e = engine ?: YouTubeStreamEngine(requireContext(), handler, engineCallback).also { engine = it }
        e.onVideoSize = { w, h -> holderAt(currentPos)?.b?.quickVideo?.let { QuickAdapter.cropToFill(it, w, h) } }
        holderAt(pos)?.b?.quickVideo?.let { QuickAdapter.cropToFill(it, 0, 0) } // the new clip has its own size
        e.setVolume(if (waitForSound) 0f else 1f)
        e.videoMode = videoOn()
        // The reel's video view must be on screen for its surface to exist: switch it on before the player starts.
        val vh0 = holderAt(pos)
        if (e.videoMode) vh0?.b?.quickVideo?.visibility = View.VISIBLE
        e.setSurface(if (e.videoMode) vh0?.surface else null) // (a reel whose view is not ready yet reports it later)
        e.load(t)
        // Called before the stream is ready: the engine starts at this position once it is.
        e.seekTo(clipStart.toInt())
        e.play()
        // Resolve the next reels' stream links now (two ahead, one in data-saver mode), so swiping
        // to them starts without waiting for YouTube to be asked.
        val ahead = if (MusicPrefs.saver(requireContext())) 2 else 3
        for (d in 1..ahead) {
            feed.getOrNull(pos + d)?.let { n ->
                if (n.source == Track.SOURCE_YOUTUBE) YouTubeStreamEngine.prefetch(requireContext(), n.remoteId, video = videoOn())
            }
        }
        updateUi()
    }

    /** Pauses the user's own music (once) and asks for audio focus for the preview. */
    private fun takeAudio() {
        audioTaken = true
        val svc = service ?: MusicService.instance
        if (svc != null && svc.isPlaying) {
            svc.pause()
            resumeServiceAfter = true
        }
        val am = requireContext().getSystemService(AudioManager::class.java) ?: return
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build().also { focusRequest = it }
        am.requestAudioFocus(req)
    }

    /** Stops the preview engine and gives audio focus back. Does not touch [MusicService]. */
    private fun stopPreviewAudio() {
        engine?.setVolume(0f) // nothing a late-arriving stream could play is audible
        engine?.release()
        audioTaken = false
        engine = null
        loadedKey = null
        previewStarted = false
        focusRequest?.let { req ->
            context?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
        }
    }

    private fun tick() {
        val e = engine ?: return
        if (!previewStarted || paused || clipDone) return
        val pos = e.position
        // Embedded / web fallbacks cannot start mid-song: jump to the chorus once playing.
        if (!seekFixed && pos < clipStart - 3_000L) {
            seekFixed = true
            e.seekTo(clipStart.toInt())
            return
        }
        seekFixed = true
        if (pos >= clipEnd) {
            advance()
        } else {
            holderAt(currentPos)?.b?.quickProgress?.progress =
                ((pos - clipStart) * 1000L / QuickFeed.CLIP_MS).toInt().coerceIn(0, 1000)
        }
        if (!clipDone) holderAt(currentPos)?.let { applyLyrics(it, currentPos) }
    }

    /** The clip is over: scroll down to the next song (loading more first if this was the last). */
    private fun advance() {
        if (clipDone) return
        clipDone = true
        engine?.pause()
        holderAt(currentPos)?.b?.quickProgress?.progress = 1000
        val next = currentPos + 1
        when {
            next < feed.size -> binding.quickPager.setCurrentItem(next, true)
            loading -> advanceWhenLoaded = true
            else -> {
                advanceWhenLoaded = true
                loadMore()
            }
        }
    }

    private fun onPreviewError() {
        errorStreak++
        if (errorStreak >= 3) {
            engine?.pause()
            paused = true
            errorStreak = 0
            Toast.makeText(requireContext(), R.string.quick_empty, Toast.LENGTH_SHORT).show()
            updateUi()
            return
        }
        Toast.makeText(requireContext(), R.string.quick_skip_error, Toast.LENGTH_SHORT).show()
        clipDone = false
        advance()
    }

    // ------------------------------------------------------------------ UI

    /** Locked: only up / down scrolling; the lock icon shows the state (tap it again to unlock). */
    private fun applyQuickLock(on: Boolean) {
        binding.quickHost.verticalOnly = on
        binding.quickLock.setImageResource(if (on) R.drawable.ic_lock else R.drawable.ic_lock_open)
    }

    /** Video mode (and no data saver): the reel plays its clip as video instead of showing the cover. */
    private fun videoOn(): Boolean {
        val c = context ?: return false
        return MusicPrefs.videoMode(c) && !MusicPrefs.saver(c)
    }

    private fun holderAt(pos: Int): QuickAdapter.VH? {
        val rv = _binding?.quickPager?.getChildAt(0) as? RecyclerView ?: return null
        return rv.findViewHolderForAdapterPosition(pos) as? QuickAdapter.VH
    }

    /** Spinner / pause icon / progress of one reel, from the current playback state. */
    private fun applyState(vh: QuickAdapter.VH, pos: Int) {
        val isCurrent = pos == currentPos
        vh.b.quickSpinner.visibility =
            if (isCurrent && visible && !previewStarted && !paused && !clipDone && engine != null) View.VISIBLE else View.GONE
        vh.b.quickPausedIcon.visibility = if (isCurrent && paused) View.VISIBLE else View.GONE
        // Video mode: the current reel's video view is on (transparent until the clip really has a picture); other reels keep the cover.
        val e = engine
        val wantVideo = isCurrent && videoOn() && (e == null || e.videoMode)
        vh.b.quickVideo.visibility = if (wantVideo) View.VISIBLE else View.GONE
        val videoShown = wantVideo && previewStarted && e?.showsVideo == true
        // Never exactly 0: a fully transparent TextureView may not get its surface, and then no picture ever arrives.
        vh.b.quickVideo.alpha = if (videoShown) 1f else 0.01f
        // Video mode: never the small cover (the page picture / the video fills the screen instead).
        vh.b.quickCover.visibility = if (videoOn()) View.INVISIBLE else View.VISIBLE
        if (!isCurrent) vh.b.quickProgress.progress = 0
        feed.getOrNull(pos)?.let { applyLike(vh, it) }
        applyLyrics(vh, pos)
    }

    // ------------------------------------------------------------------ lyrics

    /** Starts the lookup of this song's lyrics (once); repaints the reel when it arrives. */
    private fun ensureLyrics(t: Track) {
        val ctx = context ?: return
        if (t.source == Track.SOURCE_RADIO || MusicPrefs.saver(ctx) || !MusicPrefs.quickLyrics(ctx)) return
        val key = t.key
        if (lyricCache.containsKey(key) || !lyricPending.add(key)) return
        val sec = t.durationMs
        lyricsExec.execute {
            var view: LyricView? = null
            var failed = false
            try {
                val res = LyricsApi.fetch(key, t.title, t.artist, sec, if (t.source == Track.SOURCE_YOUTUBE) t.remoteId else "")
                view = res?.let { toLyricView(it, sec) }
            } catch (e: java.io.IOException) {
                failed = true // network: not remembered, so the next bind tries again
            } catch (e: Exception) {
                // malformed reply: treated as "no lyrics"
            }
            handler.post {
                lyricPending.remove(key)
                if (!failed) lyricCache[key] = view
                if (_binding == null) return@post
                val pos = feed.indexOfFirst { it.key == key }
                if (pos >= 0) holderAt(pos)?.let { applyLyrics(it, pos) }
            }
        }
    }

    /**
     * Synced lyrics keep their own timestamps. Plain lyrics have none, so each line gets an even share of the
     * song: an estimate, which is why the reel does not highlight a "current" line for them.
     */
    private fun toLyricView(r: LyricsApi.Result, durationMs: Long): LyricView? {
        val synced = r.synced.filter { it.text.isNotBlank() }
        if (synced.isNotEmpty()) {
            return LyricView(LongArray(synced.size) { synced[it].timeMs }, Array(synced.size) { synced[it].text.trim() }, true)
        }
        val lines = r.plain.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
        if (lines.isEmpty()) return null
        val total = if (durationMs > 0) durationMs else 180_000L
        return LyricView(LongArray(lines.size) { it * total / lines.size }, lines.toTypedArray(), false)
    }

    /** Previous / current / next lyric line under the cover. The reel that is not playing shows the chorus part. */
    private fun applyLyrics(vh: QuickAdapter.VH, pos: Int) {
        val t = feed.getOrNull(pos)
        val lv = if (context?.let { MusicPrefs.quickLyrics(it) } == true) t?.let { lyricCache[it.key] } else null
        val box = vh.b.quickLyrics
        if (t == null || lv == null) {
            box.visibility = View.GONE
            box.tag = null
            return
        }
        val ms = if (pos == currentPos && engine != null && previewStarted && loadedKey == t.key) {
            engine!!.position.toLong()
        } else {
            QuickFeed.chorusStartMs(t.durationMs)
        }
        // Last line whose start time has passed; -1 before the first line.
        var idx = -1
        var lo = 0
        var hi = lv.times.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lv.times[mid] <= ms) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        val stamp = t.key + ":" + idx
        if (box.tag == stamp) return
        box.tag = stamp
        fun line(i: Int) = lv.texts.getOrNull(i).orEmpty()
        if (lv.synced) {
            vh.b.quickLyPrev.text = line(idx - 1)
            vh.b.quickLyCur.text = line(idx)
            vh.b.quickLyNext.text = line(idx + 1)
        } else {
            // Estimated position: show three plain lines, none of them singled out.
            val mid = idx.coerceAtLeast(0)
            vh.b.quickLyPrev.text = line(mid - 1)
            vh.b.quickLyCur.text = line(mid)
            vh.b.quickLyNext.text = line(mid + 1)
        }
        box.visibility = View.VISIBLE
    }

    /** Heart under "Full song": filled + accent colour when the song is in Liked songs. */
    private fun applyLike(vh: QuickAdapter.VH, t: Track) {
        val ctx = context ?: return
        val liked = PlaylistStore.isLiked(ctx, t)
        vh.b.actLike.setImageResource(if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        val tv = android.util.TypedValue()
        val color = if (liked && ctx.theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, tv, true)) tv.data else 0xFFFFFFFF.toInt()
        vh.b.actLike.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun updateUi() {
        holderAt(currentPos)?.let { applyState(it, currentPos) }
        // The reels next to the current one show their cover again (the clip now plays on the current one).
        for (d in intArrayOf(-1, 1)) holderAt(currentPos + d)?.b?.let { it.quickVideo.visibility = View.GONE; it.quickCover.visibility = if (videoOn()) View.INVISIBLE else View.VISIBLE }
    }

    override fun onVideoSurface(vh: QuickAdapter.VH, available: Boolean) {
        val e = engine ?: return
        val pos = vh.bindingAdapterPosition
        if (available) {
            if (pos == currentPos && e.videoMode) e.setSurface(vh.surface)
        } else if (pos == currentPos || pos == RecyclerView.NO_POSITION) {
            e.setSurface(null) // before the surface is released
        }
    }

    override fun onBound(vh: QuickAdapter.VH, pos: Int) {
        feed.getOrNull(pos)?.let { ensureLyrics(it) }
        vh.b.quickLyrics.tag = null // the reel was recycled for another song: repaint
        applyState(vh, pos)
    }

    // ------------------------------------------------------------------ QuickActions

    override fun onTap() {
        if (feed.isEmpty()) return
        val e = engine
        if (e == null) { // previews were stopped (after "play full"): start this reel's clip again
            startPreview(currentPos, force = true)
            return
        }
        if (paused) {
            paused = false
            suppressAutoStart = false
            takeAudio()
            e.setVolume(1f)
            e.play()
        } else {
            paused = true
            e.pause()
        }
        updateUi()
    }

    /** Plays the song from the start, as a normal full song: from here on it is [MusicService]'s. */
    override fun onFull(pos: Int) {
        // Video mode: the play button opens the clip as video (YouTube songs; other sources have no video and play as audio).
        if (MusicPrefs.videoMode(requireContext()) && feed.getOrNull(pos)?.source == Track.SOURCE_YOUTUBE) {
            onVideo(pos)
            return
        }
        val svc = service ?: MusicService.instance ?: return
        if (pos !in feed.indices) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFullAt < 1500L) return // a double tap must not restart the song / stack two players
        lastFullAt = now
        val queue = feed.subList(pos, minOf(feed.size, pos + FULL_QUEUE_MAX)).toList()
        // Flags first, then stop the preview: nothing the preview engine reports from now on may touch the music.
        resumeServiceAfter = false // the user chose something else to listen to
        suppressAutoStart = true
        stopPreviewAudio()
        paused = true
        updateUi()
        svc.setQueueAndPlay(queue, 0)
        // This page has no mini player, so open the full player as feedback.
        startActivity(Intent(requireContext(), NowPlayingActivity::class.java))
        activity?.overridePendingTransition(R.anim.slide_up, R.anim.stay)
    }

    /** Opens the clip of this reel as video (from the chorus start); the audio preview and the user's music stay stopped. */
    override fun onVideo(pos: Int) {
        val t = feed.getOrNull(pos) ?: return
        if (t.source != Track.SOURCE_YOUTUBE) {
            android.widget.Toast.makeText(requireContext(), R.string.video_not_available, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        resumeServiceAfter = false // the video has its own sound: do not bring the user's music back underneath it
        suppressAutoStart = true
        stopPreviewAudio()
        paused = true
        updateUi()
        startActivity(VideoPlayerActivity.intent(requireContext(), t, QuickFeed.chorusStartMs(t.durationMs)))
    }

    override fun onLike(pos: Int) {
        val t = feed.getOrNull(pos) ?: return
        PlaylistUi.toggleLike(requireContext(), t) {
            holderAt(pos)?.let { applyLike(it, t) }
        }
    }

    override fun onPlaylist(pos: Int) {
        val t = feed.getOrNull(pos) ?: return
        PlaylistUi.addToPlaylist(requireContext(), t)
    }

    override fun onQueue(pos: Int) {
        val t = feed.getOrNull(pos) ?: return
        val svc = service ?: MusicService.instance ?: return
        val added = svc.addToQueue(t)
        Toast.makeText(
            requireContext(),
            if (added) R.string.quick_queue_added else R.string.quick_queue_already,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Three-dot menu (bottom-right corner) of the reel at [pos]: download / share. */
    override fun onMore(pos: Int, anchor: View) {
        val t = feed.getOrNull(pos) ?: return
        val popup = android.widget.PopupMenu(requireContext(), anchor, android.view.Gravity.END)
        popup.menu.add(0, MENU_VOLUME, 0, R.string.quick_volume)
        popup.menu.add(0, MENU_LYRICS, 1, R.string.quick_lyrics_toggle).apply {
            isCheckable = true
            isChecked = MusicPrefs.quickLyrics(requireContext())
        }
        if (SongDownloader.canDownload(t)) popup.menu.add(0, MENU_DOWNLOAD, 2, R.string.np_download)
        popup.menu.add(0, MENU_SHARE, 3, R.string.np_share)
        popup.menu.add(0, MENU_INFO, 4, R.string.np_info)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_VOLUME -> showVolumeDialog()
                MENU_LYRICS -> toggleLyrics()
                MENU_DOWNLOAD -> downloadReel(pos)
                MENU_SHARE -> shareReel(pos)
                MENU_INFO -> showSongInfo(pos)
            }
            true
        }
        popup.show()
    }

    /** Volume from the three-dot menu: same as the player's volume button, shows the system media volume panel without changing the level. */
    private fun showVolumeDialog() {
        val ctx = context ?: return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) { android.util.Log.d("QuickFragment", "ignored: " + e) }
    }

    /** Lyrics on / off from the three-dot menu: repaints the reels that are on screen (current and its neighbours). */
    private fun toggleLyrics() {
        val ctx = context ?: return
        val on = !MusicPrefs.quickLyrics(ctx)
        MusicPrefs.setQuickLyrics(ctx, on)
        for (pos in currentPos - 1..currentPos + 1) {
            if (on) feed.getOrNull(pos)?.let { ensureLyrics(it) }
            holderAt(pos)?.let { vh -> vh.b.quickLyrics.tag = null; applyLyrics(vh, pos) }
        }
        Toast.makeText(ctx, if (on) R.string.quick_lyrics_on else R.string.quick_lyrics_off, Toast.LENGTH_SHORT).show()
    }

    private fun downloadReel(pos: Int) {
        val t = feed.getOrNull(pos) ?: return
        SongDownloader.download(requireContext(), t)
    }

    private fun shareReel(pos: Int) {
        val t = feed.getOrNull(pos) ?: return
        val act = activity ?: return
        SongShareCard.share(act, t, null, "https://youtu.be/${t.remoteId}", getString(R.string.np_share_via))
    }

    /** Same details as the full-screen player's "Song info". */
    private fun showSongInfo(pos: Int) {
        val ctx = context ?: return
        val t = feed.getOrNull(pos) ?: return
        val rows = mutableListOf(getString(R.string.np_info_title) to t.title)
        QuickFeed.cleanArtist(t.artist).takeIf { it.isNotBlank() }?.let { rows += getString(R.string.np_info_artist) to it }
        if (t.album.isNotBlank()) rows += getString(R.string.np_info_album) to t.album
        if (t.durationMs > 0) rows += getString(R.string.np_info_duration) to formatDuration(t.durationMs)
        rows += getString(R.string.np_info_source) to getString(
            when (t.source) {
                Track.SOURCE_YOUTUBE -> R.string.source_youtube
                Track.SOURCE_RADIO -> R.string.source_radio
                Track.SOURCE_DRIVE -> R.string.source_drive
                else -> R.string.source_local
            }
        )
        if (t.source == Track.SOURCE_YOUTUBE && t.remoteId.isNotBlank()) {
            rows += getString(R.string.np_info_link) to "https://youtu.be/${t.remoteId}"
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.np_info)
            .setMessage(rows.joinToString("\n\n") { (k, v) -> "$k\n$v" })
            .setPositiveButton(R.string.np_info_close, null)
            .show()
    }
}
