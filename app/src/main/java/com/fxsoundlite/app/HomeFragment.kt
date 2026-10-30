package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.fxsoundlite.app.databinding.FragmentHomeBinding
import java.util.Calendar

/**
 * Page 0 of MainActivity's ViewPager2, laid out like a streaming app: a time-of-day greeting with
 * the settings button, the "Your stats" card, 2x2 quick tiles (Wake-up alarm, Equalizer, Sleep sounds,
 * Detect song) and horizontal shelves -- Recently played, Top songs, New releases,
 * Recommended and Popular playlists, then Recommended radio stations and Popular radio (see [buildShelves]). The equalizer itself lives in EqualizerActivity, sleep sounds in BedtimeActivity.
 *
 * Still binds to the single, persistent EffectService itself -- exactly as
 * MainActivity used to -- since the ViewPager2's default offscreen page
 * limit means this fragment stays created (and its onStart/onStop keep
 * firing) the whole time MainActivity is alive, not just while it's the
 * visible page.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var effectManager: AudioEffectManager? = null
    private var effectService: EffectService? = null
    private var bound = false
    private var bindRequested = false

    private val powerListener: (Boolean) -> Unit = { on -> if (_binding != null) applyPowerVisualState(on) }
    // Redraws the preset name when it is changed from the notification or widget while Home is showing.
    private val presetListener: () -> Unit = { if (_binding != null && effectManager != null) setupEqButton() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EffectService.LocalBinder
            effectService = binder.getService()
            effectManager = effectService?.effectManager
            bound = true
            onEngineReady()
            // Mirrors "Off"/"On" pressed on the notification while this screen is showing.
            effectService?.addPowerListener(powerListener)
            effectService?.addPresetListener(presetListener)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            effectService = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Does not need the effect engine, so it is wired right away.
        val openSleep = View.OnClickListener {
            startActivity(Intent(requireContext(), BedtimeActivity::class.java))
        }
        binding.sleepCard.setOnClickListener(openSleep)
        binding.statsWrapped.setOnClickListener {
            startActivity(Intent(requireContext(), WrappedActivity::class.java))
        }
        binding.tileDetect.setOnClickListener { startActivity(Intent(requireContext(), SongDetectActivity::class.java)) }
        binding.tileAlarm.setOnClickListener { startActivity(Intent(requireContext(), AlarmActivity::class.java)) }

        // These do not need the effect engine either: they must work even if EffectService binds slowly.
        setupSettingsButton()
        binding.eqCard.setOnClickListener { startActivity(Intent(requireContext(), EqualizerActivity::class.java)) }

        binding.greetingText.text = greeting()
        buildShelves()
        restoreOnlineShelves()

        binding.aiList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.aiList.adapter = aiAdapter
        binding.aiRefreshBtn.setOnClickListener { loadAiPicks(force = true) }
    }

    // ------------------------------------------------------------------ AI picks shelf

    private var aiTracks: List<Track> = emptyList()
    private var aiLoading = false
    /** The key the last automatic load was tried with, so a failure is not retried on every onResume. */
    private var aiAutoTriedKey: String? = null
    private var aiStatusAction: (() -> Unit)? = null
    private val aiMain = Handler(Looper.getMainLooper())

    private val aiAdapter = HomeCardAdapter(
        onClick = { i -> if (i in aiTracks.indices) MusicService.instance?.setQueueAndPlay(aiTracks, i) },
        onLong = { i ->
            val ctx = context
            if (ctx != null && i in aiTracks.indices) PlaylistUi.trackActions(ctx, aiTracks[i], null, false) {}
        }
    )

    private fun showAiStatus(text: String, action: (() -> Unit)? = null) {
        val b = _binding ?: return
        aiStatusAction = action
        b.aiStatus.text = text
        b.aiStatus.isClickable = action != null
        b.aiStatus.visibility = View.VISIBLE
        b.aiList.visibility = View.GONE
    }

    private fun showAiTracks(tracks: List<Track>) {
        val b = _binding ?: return
        aiTracks = tracks.take(10)
        aiStatusAction = null
        b.aiStatus.visibility = View.GONE
        b.aiList.visibility = View.VISIBLE
        aiAdapter.submit(aiTracks.map { HomeCard("a:" + it.key, it.title, it.artist, it.artUrl) })
        // Picks that came from the non-AI fallback (AI quota used up...) are titled without "AI".
        b.aiPicksTitle.setText(
            if (AiRecommender.cachedIsFallback(requireContext())) R.string.home_picks else R.string.home_ai_picks
        )
    }

    /** Shows the cached picks, or asks the AI for new ones ([force] = the user pressed refresh). */
    private fun loadAiPicks(force: Boolean) {
        val b = _binding ?: return
        val app = requireContext().applicationContext
        b.aiStatus.setOnClickListener { aiStatusAction?.invoke() }

        if (!AiRecommender.hasKey(app)) {
            showAiStatus(getString(R.string.home_ai_need_key)) {
                startActivity(Intent(requireContext(), SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PAGE_ONLINE))
            }
            return
        }
        if (aiLoading) return
        if (!force) {
            AiRecommender.cached(app)?.let { showAiTracks(it); return }
            val key = MusicPrefs.aiKey(app)
            if (aiAutoTriedKey == key) { // already tried this session and failed: wait for a manual retry
                if (aiTracks.isNotEmpty()) showAiTracks(aiTracks)
                return
            }
            aiAutoTriedKey = key
        }

        aiLoading = true
        b.aiRefreshBtn.isEnabled = false
        showAiStatus(getString(R.string.home_ai_loading))
        Thread {
            var tracks: List<Track>? = null
            var error: String? = null
            try {
                tracks = AiRecommender.fetch(app)
            } catch (e: AiRecommender.AiException) {
                error = if (e.detail.isNotEmpty()) app.getString(e.resId, e.detail) else app.getString(e.resId)
            } catch (e: Exception) {
                error = app.getString(R.string.ai_err_generic)
            }
            val okTracks = tracks
            val errText = error.orEmpty()
            aiMain.post {
                aiLoading = false
                if (_binding == null) return@post
                binding.aiRefreshBtn.isEnabled = true
                if (okTracks != null) showAiTracks(okTracks)
                else showAiStatus(getString(R.string.home_ai_error, errText)) { loadAiPicks(force = true) }
            }
        }.start()
    }

    // ------------------------------------------------------------------ shelves

    private class Shelf(val root: View, val adapter: HomeCardAdapter)

    private var recent: List<Track> = emptyList()
    private var topSongs: List<HomeFeeds.Song> = emptyList()
    private var newSongs: List<HomeFeeds.Song> = emptyList()
    private var radioReco: List<Track> = emptyList()
    private var radioPopular: List<Track> = emptyList()
    private var tvReco: List<Track> = emptyList()
    private var tvPopular: List<Track> = emptyList()
    private var playlistReco: List<HomePlaylists.Item> = emptyList()
    private var playlistPopular: List<HomePlaylists.Item> = emptyList()

    private lateinit var recentShelf: Shelf
    private lateinit var topShelf: Shelf
    private lateinit var newShelf: Shelf
    private lateinit var radioRecoShelf: Shelf
    private lateinit var radioPopularShelf: Shelf
    private lateinit var tvRecoShelf: Shelf
    private lateinit var tvPopularShelf: Shelf
    private lateinit var playlistRecoShelf: Shelf
    private lateinit var playlistPopularShelf: Shelf

    private var onlineLoadedAt = 0L
    private var onlineLoading = false
    private val onlineTtlMs = 30 * 60 * 1000L

    private fun addShelf(titleRes: Int, onClick: (Int) -> Unit, onLong: (Int) -> Unit = {}): Shelf {
        val root = layoutInflater.inflate(R.layout.view_home_shelf, binding.shelvesContainer, false)
        root.findViewById<android.widget.TextView>(R.id.shelfTitle).setText(titleRes)
        val adapter = HomeCardAdapter(onClick, onLong)
        root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.shelfList).also {
            it.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            it.adapter = adapter
        }
        binding.shelvesContainer.addView(root)
        return Shelf(root, adapter)
    }

    private fun buildShelves() {
        binding.shelvesContainer.removeAllViews()
        recentShelf = addShelf(R.string.home_recent,
            onClick = { i -> if (i in recent.indices) MusicService.instance?.setQueueAndPlay(recent, i) },
            onLong = { i ->
                val ctx = context
                if (ctx != null && i in recent.indices) {
                    PlaylistUi.trackActions(ctx, recent[i], null, recent[i].source == Track.SOURCE_YOUTUBE) {
                        if (_binding != null) refreshShelves()
                    }
                }
            })
        topShelf = addShelf(R.string.home_top_songs,
            { i -> topSongs.getOrNull(i)?.let { playSong(it) } },
            { i -> topSongs.getOrNull(i)?.let { songActions(it) } })
        newShelf = addShelf(R.string.home_new_releases,
            { i -> newSongs.getOrNull(i)?.let { playSong(it) } },
            { i -> newSongs.getOrNull(i)?.let { songActions(it) } })
        playlistRecoShelf = addShelf(R.string.home_playlist_reco,
            { i -> playPlaylist(playlistReco, i) }, { i -> offerSavePlaylist(playlistReco, i) })
        playlistPopularShelf = addShelf(R.string.home_playlist_popular,
            { i -> playPlaylist(playlistPopular, i) }, { i -> offerSavePlaylist(playlistPopular, i) })
        radioRecoShelf = addShelf(R.string.home_radio_reco,
            { i -> playStation(radioReco, i) }, { i -> stationActions(radioReco, i) })
        radioPopularShelf = addShelf(R.string.home_radio_popular,
            { i -> playStation(radioPopular, i) }, { i -> stationActions(radioPopular, i) })
        tvRecoShelf = addShelf(R.string.home_tv_reco, { i -> playTv(tvReco, i) })
        tvPopularShelf = addShelf(R.string.home_tv_popular, { i -> playTv(tvPopular, i) })
    }

    /** Live TV opens in the video player; the channels of the shelf are its queue. */
    private fun playTv(list: List<Track>, i: Int) {
        val ctx = context ?: return
        if (i !in list.indices) return
        MusicService.instance?.let { if (it.isPlaying) it.pause() }
        startActivity(VideoPlayerActivity.queueIntent(ctx, list, i, 0L))
    }

    /** After the view was recreated the new, empty shelves are filled from the lists already loaded. */
    private fun restoreOnlineShelves() {
        fun fill(shelf: Shelf, cards: List<HomeCard>) {
            shelf.root.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
            shelf.adapter.submit(cards)
        }
        fill(topShelf, songCards("t:", topSongs))
        fill(newShelf, songCards("n:", newSongs))
        fill(radioRecoShelf, stationCards("rr:", radioReco))
        fill(radioPopularShelf, stationCards("rp:", radioPopular))
        fill(tvRecoShelf, stationCards("tr:", tvReco))
        fill(tvPopularShelf, stationCards("tp:", tvPopular))
        fill(playlistRecoShelf, playlistCards("pr:", playlistReco))
        fill(playlistPopularShelf, playlistCards("pp:", playlistPopular))
    }

    private fun songCards(prefix: String, list: List<HomeFeeds.Song>) =
        list.map { HomeCard(prefix + it.title + "|" + it.artist, it.title, it.artist, it.artUrl) }

    private fun stationCards(prefix: String, list: List<Track>) =
        list.map { HomeCard(prefix + it.key, it.title, it.artist, it.artUrl) }

    private fun playlistCards(prefix: String, list: List<HomePlaylists.Item>) = list.map {
        val sub = listOf(it.owner, if (it.count > 0) getString(R.string.home_songs_count, it.count.toInt()) else "")
            .filter { x -> x.isNotBlank() }.joinToString(" \u00B7 ")
        HomeCard(prefix + it.listId, it.name, sub, it.artUrl)
    }

    /** Fetches the first songs of the playlist (one page), then plays them from the top. */
    private fun playPlaylist(list: List<HomePlaylists.Item>, i: Int) {
        val item = list.getOrNull(i) ?: return
        val app = requireContext().applicationContext
        Toast.makeText(app, R.string.home_playlist_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val tracks = try { HomePlaylists.tracks(app, item.listId) } catch (e: Exception) { null }
            aiMain.post {
                if (tracks == null) Toast.makeText(app, R.string.home_playlist_failed, Toast.LENGTH_SHORT).show()
                else MusicService.instance?.setQueueAndPlay(tracks, 0)
            }
        }.start()
    }

    /** Long press: offer to keep the playlist in the user's own playlists. */
    private fun offerSavePlaylist(list: List<HomePlaylists.Item>, i: Int) {
        val item = list.getOrNull(i) ?: return
        val ctx = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(item.name)
            .setMessage(R.string.home_playlist_save_msg)
            .setPositiveButton(R.string.home_playlist_save) { _, _ ->
                val app = ctx.applicationContext
                Thread {
                    val tracks = try { HomePlaylists.tracks(app, item.listId) } catch (e: Exception) { null }
                    aiMain.post {
                        if (tracks == null) Toast.makeText(app, R.string.home_playlist_failed, Toast.LENGTH_SHORT).show()
                        else {
                            PlaylistStore.createWith(app, item.name, tracks)
                            Toast.makeText(app, R.string.home_playlist_saved, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun playStation(list: List<Track>, i: Int) {
        if (i in list.indices) MusicService.instance?.setQueueAndPlay(list, i)
    }

    /** Long press on a station: the same song menu (like / add to playlist / queue). */
    private fun stationActions(list: List<Track>, i: Int) {
        val ctx = context ?: return
        val t = list.getOrNull(i) ?: return
        PlaylistUi.trackActions(ctx, t, null) { }
    }

    /** Long press on a chart song: find it on YouTube first, then open the song menu. */
    private fun songActions(song: HomeFeeds.Song) {
        val app = requireContext().applicationContext
        Toast.makeText(app, R.string.home_song_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val track = try {
                YouTubeApi.search(app, song.artist + " " + song.title, maxResults = 3).firstOrNull()
            } catch (e: Exception) { null }
            aiMain.post {
                val ctx = context
                if (ctx == null || _binding == null) return@post
                if (track == null) Toast.makeText(app, R.string.home_song_not_found, Toast.LENGTH_SHORT).show()
                else PlaylistUi.trackActions(ctx, track.copy(artUrl = track.artUrl.ifBlank { song.artUrl }), null) { }
            }
        }.start()
    }

    /** Chart songs have no stream yet: find the song on YouTube, then play it. */
    private fun playSong(song: HomeFeeds.Song) {
        val app = requireContext().applicationContext
        Toast.makeText(app, R.string.home_song_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val track = try {
                YouTubeApi.search(app, song.artist + " " + song.title, maxResults = 3).firstOrNull()
            } catch (e: Exception) { null }
            aiMain.post {
                if (track == null) Toast.makeText(app, R.string.home_song_not_found, Toast.LENGTH_SHORT).show()
                else MusicService.instance?.setQueueAndPlay(
                    listOf(track.copy(artUrl = track.artUrl.ifBlank { song.artUrl })), 0
                )
            }
        }.start()
    }

    /** Fetches the online shelves (charts + radio + playlists) at most every 30 minutes; each one shows up as it arrives. */
    private fun loadOnlineShelves() {
        if (onlineLoading || System.currentTimeMillis() - onlineLoadedAt < onlineTtlMs) return
        onlineLoading = true
        val app = requireContext().applicationContext
        var pending = 8
        var anyOk = false
        var failed = 0
        fun <T> job(fetch: () -> T, apply: (T) -> Unit) {
            Thread {
                val r = runCatching(fetch)
                aiMain.post {
                    r.onSuccess { anyOk = true; if (_binding != null) apply(it) }
                    r.onFailure { failed++ }
                    if (--pending == 0) {
                        onlineLoading = false
                        // All good: next refresh in 30 min. Some shelves failed: try again in ~2 min instead of leaving them empty for 30.
                        if (anyOk) onlineLoadedAt = System.currentTimeMillis() - if (failed == 0) 0L else onlineTtlMs - 2 * 60_000L
                    }
                }
            }.start()
        }
        job({ HomeFeeds.topSongs(app) }) {
            topSongs = it; topShelf.root.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            topShelf.adapter.submit(songCards("t:", it))
        }
        job({ HomeFeeds.newReleases(app) }) {
            newSongs = it; newShelf.root.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            newShelf.adapter.submit(songCards("n:", it))
        }
        job({ RadioApi.recommended(app) }) {
            radioReco = it; radioRecoShelf.root.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            radioRecoShelf.adapter.submit(stationCards("rr:", it))
        }
        job({ RadioApi.popularMixed(app) }) {
            radioPopular = it; radioPopularShelf.root.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            radioPopularShelf.adapter.submit(stationCards("rp:", it))
        }
        job({ TvChannels.recommended(app) }) {
            tvReco = it; tvRecoShelf.root.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            tvRecoShelf.adapter.submit(stationCards("tr:", it))
        }
        job({ TvChannels.popularMixed(app) }) {
            tvPopular = it; tvPopularShelf.root.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            tvPopularShelf.adapter.submit(stationCards("tp:", it))
        }
        job({ HomePlaylists.recommended(app) }) {
            playlistReco = it; playlistRecoShelf.root.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            playlistRecoShelf.adapter.submit(playlistCards("pr:", it))
        }
        job({ HomePlaylists.popular(app) }) {
            playlistPopular = it; playlistPopularShelf.root.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            playlistPopularShelf.adapter.submit(playlistCards("pp:", it))
        }
    }

    private fun greeting(): String {
        val res = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> R.string.greet_morning
            in 11..14 -> R.string.greet_afternoon
            in 15..17 -> R.string.greet_evening
            else -> R.string.greet_night
        }
        return getString(res)
    }

    /** Redraws "Recently played"; the adapter ignores the call when nothing changed. */
    private fun refreshShelves() {
        val ctx = context ?: return
        if (_binding == null) return
        recent = MusicPrefs.recentPlayed(ctx).take(10)
        recentShelf.root.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        recentShelf.adapter.submit(recent.map { HomeCard("r:" + it.key, it.title, it.artist, it.artUrl) })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------------ stats card

    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRefresh = object : Runnable {
        override fun run() {
            refreshStats()
            statsHandler.postDelayed(this, 5_000L) // keeps counting up while music plays
        }
    }

    override fun onResume() {
        super.onResume()
        _binding?.greetingText?.text = greeting() // the fragment stays alive for hours: keep "Good morning" honest
        loadAiPicks(force = false)
        loadOnlineShelves()
        statsRefresh.run()
    }

    override fun onPause() {
        statsHandler.removeCallbacks(statsRefresh)
        super.onPause()
    }

    private fun formatListening(ms: Long): String {
        val totalMin = (ms / 60_000L).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) getString(R.string.stats_fmt_hour_min, h, m) else getString(R.string.stats_fmt_min, m)
    }

    private fun refreshStats() {
        val b = _binding ?: return
        val ctx = context ?: return
        b.statsTotal.text = formatListening(StatsStore.totalMs(ctx))
        b.statsToday.text = formatListening(StatsStore.todayMs(ctx))
        b.statsPlays.text = StatsStore.plays(ctx).toString()
        b.tileAlarmSub.text = if (WakeAlarm.isEnabled(ctx))
            String.format(java.util.Locale.US, "%02d:%02d", WakeAlarm.hour(ctx), WakeAlarm.minute(ctx))
        else getString(R.string.alarm_tile_off)
        refreshShelves()
        StatsStore.topArtist(ctx).let { a ->
            b.statsTopArtist.visibility = if (a == null) View.GONE else View.VISIBLE
            if (a != null) b.statsTopArtist.text = getString(R.string.stats_top_artist, a)
        }
        StatsStore.topTrack(ctx).let { t ->
            b.statsTopTrack.visibility = if (t == null) View.GONE else View.VISIBLE
            if (t != null) b.statsTopTrack.text = getString(R.string.stats_top_track, t)
        }
    }

    // ------------------------------------------------------------------ online / offline

    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var wasOffline = false

    /** True when some network that claims internet access is active (says nothing about really reaching it). */
    private fun hasNetwork(): Boolean {
        val cm = context?.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private var probing = false

    /** A tiny request (two 204 endpoints), used only while offline to notice that the internet is back. */
    private fun reachable(): Boolean {
        for (u in listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                conn.instanceFollowRedirects = false // a captive portal answers with a redirect or a 200 page
                conn.useCaches = false
                if (conn.responseCode == 204) return true
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    private fun showNet(on: Boolean) {
        val b = _binding ?: return
        b.statsNet.setText(if (on) R.string.net_online else R.string.net_offline)
        b.statsNet.setTextColor(if (on) 0xFF4CAF50.toInt() else 0xFFE53935.toInt())
        if (!on) wasOffline = true
        else if (wasOffline) {
            wasOffline = false
            if (isAdded) { onlineLoadedAt = 0L; loadOnlineShelves() } // back online: refresh the online shelves
        }
    }

    /** Offline: no network, or the app's own requests keep failing (see [NetStatus]). Costs no traffic. */
    private fun updateNetStatus() {
        if (_binding == null) return
        showNet(hasNetwork() && !NetStatus.failing)
    }

    private val netListener: () -> Unit = { updateNetStatus() }

    /** While offline only: every 15 s a tiny probe checks whether the internet is back. */
    private val netPoll = object : Runnable {
        override fun run() {
            if (_binding == null || !netPolling) return
            if (hasNetwork() && NetStatus.failing && !probing) {
                probing = true
                Thread {
                    val ok = reachable()
                    statsHandler.post {
                        probing = false
                        if (ok) NetStatus.reset() // notifies -> status turns Online
                    }
                }.start()
            }
            statsHandler.postDelayed(this, 15_000L)
        }
    }
    private var netPolling = false

    private fun startNetWatch() {
        updateNetStatus()
        NetStatus.addListener(netListener)
        netPolling = true
        statsHandler.removeCallbacks(netPoll)
        statsHandler.postDelayed(netPoll, 15_000L)
        val cm = context?.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { NetStatus.reset(); statsHandler.post { updateNetStatus() } }
            override fun onLost(network: android.net.Network) { statsHandler.post { updateNetStatus() } }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                statsHandler.post { updateNetStatus() }
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (_: Exception) { }
    }

    private fun stopNetWatch() {
        netPolling = false
        NetStatus.removeListener(netListener)
        statsHandler.removeCallbacks(netPoll)
        val cb = netCallback ?: return
        netCallback = null
        try { context?.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
    }

    override fun onStart() {
        super.onStart()
        startNetWatch()
        Intent(requireContext(), EffectService::class.java).also { intent ->
            bindRequested = requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        stopNetWatch()
        effectService?.removePowerListener(powerListener)
        effectService?.removePresetListener(presetListener)
        // Unbind even if onServiceConnected has not fired yet, or the connection leaks.
        if (bindRequested || bound) {
            try { requireContext().unbindService(connection) } catch (_: IllegalArgumentException) { }
            bindRequested = false
            bound = false
        }
    }

    /** Called once the real (single, persistent) effect engine is bound. */
    private fun onEngineReady() {
        if (_binding == null) return
        setupEqButton()
        applyPowerVisualState(em().isEnabled)
    }

    private fun em() = effectManager!!

    /** Home only shows a doorway to the equalizer: the current preset name plus
     *  an EQ button. The full screen (presets, master gain, band sliders and the
     *  Bass/Surround/Loudness/Ambience/Volume Leveling sliders) is EqualizerActivity.
     *  Re-run on every onStart() and whenever the preset changes while Home is showing
     *  (notification buttons / widget), so the preset name is always current. */
    private fun setupEqButton() {
        val preset = em().prefs.getString("last_preset", "Flat") ?: "Flat"
        binding.eqPresetSummary.text = getString(R.string.eq_preset_summary, preset)
    }

    /** The on/off switch now lives in the equalizer screen. Home only dims the card while effects
     *  are off; it stays tappable, because that is where the user turns them back on. */
    private fun applyPowerVisualState(on: Boolean) {
        binding.eqCard.alpha = if (on) 1.0f else 0.55f
    }

    /** Root Mode lives in Settings (see SettingsActivity) -- this screen just
     *  launches it. */
    private fun setupSettingsButton() {
        binding.listenButton.setOnClickListener { ListenTogetherUi.show(requireActivity()) }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }
}
