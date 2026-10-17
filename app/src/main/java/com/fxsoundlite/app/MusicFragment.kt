package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fxsoundlite.app.databinding.FragmentMusicBinding
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Page 1 of MainActivity's pager: the player. Two sources share the same list, search box and
 * player controls: the device's own music and YouTube (searched with the API key from Settings). The actual playback lives in [MusicService] so it keeps
 * going when the app is closed, with a media notification like any other player.
 */
class MusicFragment : Fragment(), MusicService.Listener {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    private var service: MusicService? = null
    private var bound = false

    private var allTracks: List<Track> = emptyList()
    private var loadedVersion = -1 // MusicLibrary.version of the list above
    private var shown: List<Track> = emptyList()

    private var source = Track.SOURCE_LOCAL
    private val onlineResults = HashMap<Int, List<Track>>()
    private val onlineQuery = HashMap<Int, String>()
    @Volatile private var searchToken = 0
    /** Radio tab with an empty search box: popular stations (null = not loaded yet). */
    private var radioDefault: List<Track>? = null
    private var radioFailed = false
    private var radioLoading = false
    private var emptyAction: (() -> Unit)? = null
    private var loading = false
    private var loadedOnce = false
    private var permissionDeniedOnce = false
    private var dragging = false

    private val adapter = TrackAdapter(
        onClick = { position -> onTrackClicked(position) },
        onLong = { position -> onTrackLongClicked(position) }
    )
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    // Two workers: a search still running for an older query must not hold up the newest one.
    private val netExecutor = Executors.newFixedThreadPool(2)

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            updateDataUsage()
            handler.postDelayed(this, if (MusicPrefs.saver(requireContext())) 1500L else 500L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) permissionDeniedOnce = true
            loadLibrary()
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MusicService.LocalBinder).getService().also {
                it.addListener(this@MusicFragment)
            }
            bound = true
            if (_binding != null) onMusicStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.trackList.layoutManager = LinearLayoutManager(requireContext())
        binding.trackList.adapter = adapter

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            // Device music filters live as you type; online sources search by themselves shortly
            // after you stop typing (the keyboard's Search key still searches right away).
            override fun afterTextChanged(s: Editable?) {
                if (source == Track.SOURCE_LOCAL) { applyFilter(); return }
                handler.removeCallbacks(liveSearch)
                if (source == Track.SOURCE_ONLINE) { handler.postDelayed(liveSearch, 600); return } // empty box = popular songs
                val q = s?.toString()?.trim().orEmpty()
                if (q.isEmpty()) { // cleared: forget the earlier results, back to the recently played list
                    searchToken++
                    onlineResults.remove(source)
                    onlineQuery.remove(source)
                    binding.searchProgress.visibility = View.GONE
                    showOnline()
                    return
                }
                // Radio has no separate filter: the search box is the filter, so it applies at once
                // (instant local filter of the popular list below, then a quick network search).
                if (source == Track.SOURCE_RADIO) {
                    showOnline()
                    if (q.length >= 2 && q != onlineQuery[source]) handler.postDelayed(liveSearch, 250)
                } else if (q.length >= 2 && q != onlineQuery[source]) handler.postDelayed(liveSearch, 600)
            }
        })
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                handler.removeCallbacks(liveSearch)
                if (source == Track.SOURCE_ONLINE) runOnlinePage()
                else if (source != Track.SOURCE_LOCAL) runOnlineSearch()
                hideKeyboard()
                true
            } else false
        }

        binding.actionButton.setOnClickListener { emptyAction?.invoke() }

        // Drive songs are part of the Device list now (labelled "Drive"), so there is no Drive tab.
        source = MusicPrefs.lastSource(requireContext()).let { if (it == Track.SOURCE_DRIVE) Track.SOURCE_LOCAL else it }
        if (!tabAllowed(source)) source = Track.SOURCE_LOCAL
        loadSortModes()
        binding.filterButton.setOnClickListener { showFilterDialog() }
        binding.tabLocal.setOnClickListener { selectSource(Track.SOURCE_LOCAL) }
        binding.tabYoutube.setOnClickListener { selectSource(Track.SOURCE_YOUTUBE) }
        binding.tabOnline.setOnClickListener { selectSource(Track.SOURCE_ONLINE) }
        binding.tabRadio.setOnClickListener { selectSource(Track.SOURCE_RADIO) }
        binding.radioSourceButton.setOnClickListener { showRadioSourceDialog() }
        loadOnlineSrc()
        updateSaverButton()
        binding.saverButton.setOnClickListener { toggleSaver() }
        updatePrivateButton()
        binding.privateButton.setOnClickListener {
            val app = requireContext().applicationContext
            val on = !MusicPrefs.privateMode(app)
            MusicPrefs.setPrivateMode(app, on)
            updatePrivateButton()
            toast(if (on) R.string.private_mode_on else R.string.private_mode_off)
        }
        binding.dataUsedText.setOnClickListener {
            DataUsage.reset(requireContext())
            updateDataUsage()
            toast(R.string.data_used_reset_toast)
        }
        updateTabs()
        view.post { applyLaunchRequest() } // a widget may have launched the app on this page

        binding.playPauseButton.setOnClickListener { onPlayPauseClicked() }
        binding.nextButton.setOnClickListener { service?.next() }
        binding.prevButton.setOnClickListener { service?.previous() }
        binding.shuffleButton.setOnClickListener { service?.toggleShuffle() }
        binding.repeatButton.setOnClickListener { service?.cycleRepeat() }

        binding.progressSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.timeElapsed.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                service?.seekTo(sb?.progress ?: 0)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        requireContext().bindService(
            Intent(requireContext(), MusicService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        // Settings > Search tabs may have switched the current tab off meanwhile.
        if (!tabAllowed(source)) { source = Track.SOURCE_LOCAL; MusicPrefs.setLastSource(requireContext(), source); refreshList() }
        updateTabs()
        if (source == Track.SOURCE_LOCAL) refreshList() // the user may have signed in or out of Google meanwhile
        // Re-check on every start: the user may have granted access in system settings meanwhile.
        if (!loadedOnce || loadedVersion != MusicLibrary.version ||
            (allTracks.isEmpty() && MusicLibrary.hasPermission(requireContext()))
        ) {
            loadLibrary()
        }
        // A download finished while this page is open: reload the Device list (shortly after, so the
        // system has finished reading the new file's details).
        MusicLibrary.onChanged = { handler.postDelayed({ if (_binding != null) loadLibrary() }, 1500) }
        handler.post(tick)
        // Back from Settings (key typed): update the prompts.
        refreshList()
    }

    override fun onStop() {
        super.onStop()
        MusicLibrary.onChanged = null
        handler.removeCallbacks(tick)
        service?.removeListener(this)
        if (bound) {
            requireContext().unbindService(connection)
            bound = false
            service = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
        netExecutor.shutdownNow()
    }

    // ------------------------------------------------------------------ library

    private fun loadLibrary() {
        if (_binding == null) return
        if (!MusicLibrary.hasPermission(requireContext())) {
            refreshList()
            return
        }
        if (loading) return
        loading = true
        loadedVersion = MusicLibrary.version
        val appContext = requireContext().applicationContext
        try {
            loader.execute {
                val tracks = MusicLibrary.load(appContext)
                handler.post {
                    loading = false
                    loadedOnce = true
                    if (_binding == null) return@post
                    allTracks = tracks
                    refreshList()
                }
            }
        } catch (e: Exception) {
            loading = false
        }
    }

    /** Re-draws the list area for the current source. */
    private fun refreshList() {
        if (_binding == null) return
        if (source == Track.SOURCE_LOCAL) {
            if (MusicLibrary.hasPermission(requireContext()) || DriveMusic.isSignedIn(requireContext())) {
                applyFilter()
            } else {
                shown = emptyList()
                adapter.submit(shown)
                binding.musicCount.text = ""
                showEmpty(R.string.music_need_permission, R.string.music_allow_access) { requestLibraryAccess() }
            }
        } else if (source == Track.SOURCE_ONLINE) {
            handler.removeCallbacks(liveSearch) // selectSource clears the box first, which schedules a search
            runOnlinePage()
        } else {
            showOnline()
        }
    }

    private fun requestLibraryAccess() {
        if (permissionDeniedOnce) {
            // The system won't show the dialog again after "Don't allow" twice: go to app settings.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", requireContext().packageName, null))
            )
        } else {
            permissionLauncher.launch(MusicLibrary.requiredPermission)
        }
    }

    // ---- filter button (Device: source / length / artist; Radio: country / genre)

    private val deviceFilter = SongFilter()
    private var deviceBase: List<Track> = emptyList()
    private val radioFilter = SongFilter()
    /** List order of the Device and Radio tabs (0 = as loaded), remembered across launches. */
    private var deviceSort = 0
    private var radioSort = 0

    private fun sortPrefs() = requireContext().getSharedPreferences("music_sort", android.content.Context.MODE_PRIVATE)

    private fun loadSortModes() {
        try {
            deviceSort = sortPrefs().getInt("device", 0)
            radioSort = sortPrefs().getInt("radio", 0)
        } catch (_: Exception) { }
    }

    private fun saveSortMode(key: String, v: Int) {
        try { sortPrefs().edit().putInt(key, v).apply() } catch (_: Exception) { }
    }

    private fun byText(a: String, b: String) = java.text.Collator.getInstance().compare(a.trim(), b.trim())

    /** Device list order: 0 as loaded, 1 title A-Z, 2 title Z-A, 3 artist A-Z, 4 longest, 5 shortest. */
    private fun sortDevice(list: List<Track>): List<Track> = when (deviceSort) {
        1 -> list.sortedWith { a, b -> byText(a.title, b.title) }
        2 -> list.sortedWith { a, b -> byText(b.title, a.title) }
        3 -> list.sortedWith { a, b -> byText(a.artist, b.artist).let { if (it != 0) it else byText(a.title, b.title) } }
        4 -> list.sortedByDescending { it.durationMs }
        5 -> list.sortedBy { it.durationMs }
        else -> list
    }

    /** Radio list order: 0 as the directory gave it, 1 name A-Z, 2 name Z-A. */
    private fun sortRadio(list: List<Track>): List<Track> = when (radioSort) {
        1 -> list.sortedWith { a, b -> byText(a.title, b.title) }
        2 -> list.sortedWith { a, b -> byText(b.title, a.title) }
        else -> list
    }
    private var radioBase: List<Track> = emptyList()
    /** YouTube tab: length / channel filter (the source is always YouTube, so that section never shows). */
    private val ytFilter = SongFilter()
    private var ytBase: List<Track> = emptyList()

    /** YouTube list narrowed by [ytFilter]; also remembers the unfiltered list for the dialog. */
    private fun ytFiltered(list: List<Track>): List<Track> {
        ytBase = list
        return if (ytFilter.active) list.filter { ytFilter.matches(it) } else list
    }

    /** Radio list narrowed to the chosen country / genre; also remembers the unfiltered list for the dialog. */
    private fun radioFiltered(list: List<Track>): List<Track> {
        radioBase = list
        return sortRadio(if (radioFilter.active) list.filter { radioFilter.matches(it) } else list)
    }

    private fun showFilterDialog() {
        val ctx = context ?: return
        if (source == Track.SOURCE_LOCAL) {
            val sort = SortOption(
                listOf(
                    R.string.sort_default, R.string.playlist_sort_title_az, R.string.playlist_sort_title_za,
                    R.string.playlist_sort_artist, R.string.playlist_sort_longest, R.string.playlist_sort_shortest
                ),
                { deviceSort }, { deviceSort = it; saveSortMode("device", it) }
            )
            FilterUi.showSongFilter(ctx, deviceBase, deviceFilter, sort) { if (_binding != null) { applyFilter(); updateFilterButton() } }
        } else if (source == Track.SOURCE_YOUTUBE) {
            val before = MusicPrefs.ytNewest(ctx)
            val sort = SortOption(
                listOf(R.string.yt_sort_relevance, R.string.yt_sort_newest),
                { if (MusicPrefs.ytNewest(ctx)) 1 else 0 },
                { MusicPrefs.setYtNewest(ctx, it == 1) }
            )
            FilterUi.showSongFilter(ctx, ytBase, ytFilter, sort) {
                if (_binding == null) return@showSongFilter
                updateFilterButton()
                // A new order means asking YouTube again; a filter alone only narrows what is shown.
                if (MusicPrefs.ytNewest(ctx) != before && !binding.searchInput.text.isNullOrBlank()) runOnlineSearch()
                else showOnline()
            }
        } else if (source == Track.SOURCE_ONLINE) {
            val sort = SortOption(
                listOf(R.string.sort_default, R.string.playlist_sort_title_az, R.string.playlist_sort_title_za),
                { onlineSort }, { onlineSort = it }
            )
            val before = listOf(onlineSrc, onlineGenre, onlineLossless, onlineCommercial)
            FilterUi.showSongFilter(
                ctx, if (binding.searchInput.text.isNullOrBlank()) MusicPrefs.onlineHistory(ctx) else onlineBase,
                onlineFilter, sort, online = onlineOption()
            ) {
                if (_binding == null) return@showSongFilter
                updateFilterButton()
                if (before != listOf(onlineSrc, onlineGenre, onlineLossless, onlineCommercial)) onlineChanged() else showOnline()
            }
        } else if (source == Track.SOURCE_RADIO) {
            val sort = SortOption(
                listOf(R.string.sort_default, R.string.playlist_sort_title_az, R.string.playlist_sort_title_za),
                { radioSort }, { radioSort = it; saveSortMode("radio", it) }
            )
            FilterUi.showSongFilter(ctx, radioBase, radioFilter, sort, tagMode = true) {
                if (_binding != null) { showOnline(); updateFilterButton() }
            }
        }
    }

    /** The filter button shows on every tab (Device, YouTube, Radio); full strength while a filter (or YouTube's newest-first order) is on. */
    private fun updateFilterButton() {
        val b = _binding ?: return
        b.filterButton.visibility =
            if (source == Track.SOURCE_LOCAL || source == Track.SOURCE_YOUTUBE || source == Track.SOURCE_RADIO || source == Track.SOURCE_ONLINE) View.VISIBLE else View.GONE
        FilterUi.setButtonState(
            b.filterButton,
            when (source) {
                Track.SOURCE_LOCAL -> deviceFilter.active || deviceSort != 0
                Track.SOURCE_YOUTUBE -> ytFilter.active || MusicPrefs.ytNewest(requireContext())
                Track.SOURCE_ONLINE -> onlineFilter.active || onlineSort != 0 || onlineGenre != null || onlineLossless || onlineCommercial
                else -> radioFilter.active || radioSort != 0
            }
        )
    }

    /** Device songs plus the user's Google Drive songs (when signed in); Drive rows carry a "Drive" label. */
    private fun applyFilter() {
        if (_binding == null || source != Track.SOURCE_LOCAL) return
        val ctx = requireContext()
        val driveOn = DriveMusic.isSignedIn(ctx)
        if (driveOn) {
            val email = DriveSync.email(ctx)
            if (email != driveEmail) { driveEmail = email; driveAll = null; driveError = null } // another account
            if (driveAll == null || System.currentTimeMillis() - driveLoadedAt > 2 * 60_000L) loadDrive(force = false)
        }
        val device = if (MusicLibrary.hasPermission(ctx)) allTracks else emptyList()
        val everything = device + (if (driveOn) driveAll.orEmpty() else emptyList())
        deviceBase = everything
        val q = binding.searchInput.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        shown = (if (q.isEmpty()) everything else everything.filter {
            it.title.lowercase(Locale.getDefault()).contains(q) ||
                it.artist.lowercase(Locale.getDefault()).contains(q) ||
                it.album.lowercase(Locale.getDefault()).contains(q)
        }).filter { deviceFilter.matches(it) }.let { sortDevice(it) }
        adapter.submit(shown)
        binding.musicCount.text = resources.getQuantityString(
            R.plurals.music_song_count, everything.size, everything.size
        )
        if (shown.isEmpty()) {
            when {
                everything.isEmpty() && !MusicLibrary.hasPermission(ctx) ->
                    showEmpty(R.string.music_need_permission, R.string.music_allow_access) { requestLibraryAccess() }
                everything.isEmpty() && driveOn && driveLoading -> showEmpty(R.string.drive_music_loading)
                everything.isEmpty() -> showEmpty(R.string.music_empty)
                else -> showEmpty(R.string.music_no_results)
            }
        } else {
            showList()
        }
        adapter.setCurrentKey(service?.current?.key.orEmpty())
    }

    private fun showList() {
        binding.emptyPanel.visibility = View.GONE
        binding.trackList.visibility = View.VISIBLE
    }

    private fun showEmpty(textRes: Int, actionRes: Int = 0, action: (() -> Unit)? = null) {
        binding.trackList.visibility = View.GONE
        binding.emptyPanel.visibility = View.VISIBLE
        binding.emptyText.setText(textRes)
        emptyAction = action
        binding.actionButton.visibility = if (action != null) View.VISIBLE else View.GONE
        if (actionRes != 0) binding.actionButton.setText(actionRes)
    }

    // ------------------------------------------------------------------ sources + online search

    /** Device is always there; the online tabs can be switched off in Settings > Search tabs. */
    private fun tabAllowed(src: Int): Boolean = when (src) {
        Track.SOURCE_YOUTUBE -> SearchTabs.isShown(requireContext(), SearchTabs.Tab.YOUTUBE)
        Track.SOURCE_ONLINE -> SearchTabs.isShown(requireContext(), SearchTabs.Tab.ONLINE)
        Track.SOURCE_RADIO -> SearchTabs.isShown(requireContext(), SearchTabs.Tab.RADIO)
        else -> true
    }

    private fun sourceName(src: Int): String = when (src) {
        Track.SOURCE_ONLINE -> getString(R.string.source_online)
        Track.SOURCE_YOUTUBE -> getString(R.string.source_youtube)
        Track.SOURCE_RADIO -> getString(R.string.source_radio)
        else -> getString(R.string.source_local)
    }

    private fun updateTabs() {
        binding.tabLocal.isSelected = source == Track.SOURCE_LOCAL
        binding.tabYoutube.isSelected = source == Track.SOURCE_YOUTUBE
        binding.tabRadio.isSelected = source == Track.SOURCE_RADIO
        binding.tabOnline.isSelected = source == Track.SOURCE_ONLINE
        val ctx = requireContext()
        binding.tabYoutube.visibility = if (SearchTabs.isShown(ctx, SearchTabs.Tab.YOUTUBE)) View.VISIBLE else View.GONE
        binding.tabOnline.visibility = if (SearchTabs.isShown(ctx, SearchTabs.Tab.ONLINE)) View.VISIBLE else View.GONE
        binding.tabRadio.visibility = if (SearchTabs.isShown(ctx, SearchTabs.Tab.RADIO)) View.VISIBLE else View.GONE
                updateFilterButton()
        binding.radioSourceButton.visibility = View.GONE // radio searches every directory at once
        binding.radioSourceButton.text =
            getString(R.string.radio_source_label, radioSourceName(MusicPrefs.radioSource(requireContext())))
        binding.searchInput.setHint(
            when (source) {
                Track.SOURCE_LOCAL -> R.string.music_search_hint
                Track.SOURCE_RADIO -> R.string.radio_search_hint
                Track.SOURCE_ONLINE -> R.string.online_archive_hint
                else -> R.string.online_search_hint
            }
        )
    }

    private val radioSourceIds = intArrayOf(
        RadioApi.SRC_BROWSER, RadioApi.SRC_SOMA, RadioApi.SRC_ICECAST,
        RadioApi.SRC_GARDEN, RadioApi.SRC_SHOUTCAST, RadioApi.SRC_PARADISE
    )

    private fun radioSourceName(id: Int): String = getString(
        when (id) {
            RadioApi.SRC_SOMA -> R.string.radio_src_soma
            RadioApi.SRC_ICECAST -> R.string.radio_src_icecast
            RadioApi.SRC_GARDEN -> R.string.radio_src_garden
            RadioApi.SRC_SHOUTCAST -> R.string.radio_src_shoutcast
            RadioApi.SRC_PARADISE -> R.string.radio_src_paradise
            else -> R.string.radio_src_browser
        }
    )

    private fun showRadioSourceDialog() {
        val ctx = requireContext()
        val current = radioSourceIds.indexOf(MusicPrefs.radioSource(ctx)).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.radio_source_title)
            .setSingleChoiceItems(radioSourceIds.map { radioSourceName(it) }.toTypedArray(), current) { d, which ->
                d.dismiss()
                if (radioSourceIds[which] == MusicPrefs.radioSource(ctx)) return@setSingleChoiceItems
                MusicPrefs.setRadioSource(ctx, radioSourceIds[which])
                // Results and the popular list belong to the old directory.
                radioDefault = null
                radioFailed = false
                onlineResults.remove(Track.SOURCE_RADIO)
                onlineQuery.remove(Track.SOURCE_RADIO)
                searchToken++
                binding.searchProgress.visibility = View.GONE
                updateTabs()
                binding.searchInput.setText("")
                refreshList()
            }
            .setNegativeButton(R.string.radio_cancel, null)
            .show()
    }

    /** Shoutcast needs the user's own free developer key. */
    private fun askShoutcastKey() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            setSingleLine()
            setHint(R.string.radio_shoutcast_key_hint)
            setText(MusicPrefs.shoutcastKey(ctx))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.radio_src_shoutcast)
            .setMessage(R.string.radio_shoutcast_needs_key)
            .setView(input)
            .setPositiveButton(R.string.radio_save) { _, _ ->
                MusicPrefs.setShoutcastKey(ctx, input.text.toString())
                radioDefault = null
                radioFailed = false
                if (_binding != null) showOnline()
            }
            .setNegativeButton(R.string.radio_cancel, null)
            .show()
    }

    private fun selectSource(newSource: Int) {
        if (newSource == source) return
        source = newSource
        MusicPrefs.setLastSource(requireContext(), newSource)
        searchToken++ // drop an in-flight search that belonged to the other source
        binding.searchProgress.visibility = View.GONE
        updateTabs()
        // Both tabs start with an empty box: Device shows everything, YouTube shows recent plays.
        binding.searchInput.setText("")
        refreshList()
    }

    /** Online tab: prompts for what is missing (key / login), otherwise shows the last results. */
    private fun showOnline() {
        if (source == Track.SOURCE_ONLINE) { showOnlineList(); return }
        val ctx = requireContext()
        val src = source
        val blocker: Blocker? = when {
            else -> null
        }
        if (blocker != null) {
            shown = emptyList()
            adapter.submit(shown)
            binding.musicCount.text = sourceName(src)
            showEmpty(blocker.text, blocker.actionText, blocker.action)
            return
        }
        val inputBlank = binding.searchInput.text.isNullOrBlank()
        if (src == Track.SOURCE_RADIO && inputBlank) {
            showRadioDefault()
            return
        }
        if (src == Track.SOURCE_YOUTUBE && inputBlank) {
            // Nothing typed yet: show the last songs played from YouTube.
            val history = ytFiltered(MusicPrefs.youtubeHistory(ctx))
            if (history.isNotEmpty() || ytFilter.active) {
                shown = history
                adapter.submit(history)
                binding.musicCount.text = getString(R.string.online_history_title, history.size)
                if (history.isEmpty()) showEmpty(R.string.music_no_results) else showList()
                adapter.setCurrentKey(service?.current?.key.orEmpty())
                return
            }
        }
        var list = if (inputBlank) emptyList() else onlineResults[src].orEmpty()
        if (src == Track.SOURCE_RADIO && !inputBlank) {
            val typed = binding.searchInput.text.toString().trim()
            // Results of an older query are stale: until the new search lands, filter what we have.
            if (onlineQuery[src] != typed) {
                val base = (radioDefault.orEmpty() + list).distinctBy { it.key }
                list = base.filter { it.title.contains(typed, true) || it.artist.contains(typed, true) }
            }
        }
        if (src == Track.SOURCE_RADIO) list = radioFiltered(list)
        if (src == Track.SOURCE_YOUTUBE) list = ytFiltered(list)
        shown = list
        adapter.submit(list)
        binding.musicCount.text = if (list.isEmpty()) sourceName(src)
        else resources.getQuantityString(R.plurals.online_result_count, list.size, list.size, sourceName(src))
        if (list.isEmpty()) {
            val filteredOut = (src == Track.SOURCE_RADIO && radioFilter.active) ||
                (src == Track.SOURCE_YOUTUBE && ytFilter.active)
            showEmpty(
                if (!filteredOut && (inputBlank || onlineQuery[src].isNullOrBlank())) R.string.online_search_prompt
                else R.string.music_no_results
            )
        } else {
            showList()
        }
        adapter.setCurrentKey(service?.current?.key.orEmpty())
        // Warm up the first results: tapping one later starts without waiting for YouTube to answer.
        if (src == Track.SOURCE_YOUTUBE && !MusicPrefs.saver(ctx)) {
            list.asSequence().filter { it.source == Track.SOURCE_YOUTUBE }.take(3)
                .forEach { YouTubeStreamEngine.prefetch(ctx, it.remoteId) }
        }
    }

    // ------------------------------------------------------------------ Online tab (Internet Archive, Audius, Jamendo, ccMixter)

    private var onlineSrc = OnlineSource.ARCHIVE
    private var onlineGenre: String? = null
    private var onlineLossless = false
    private var onlineCommercial = false
    private val onlineFilter = SongFilter()
    private var onlineSort = 0
    /** What the source returned (Internet Archive albums are shown as rows that open to their songs). */
    private var onlineBase: List<Track> = emptyList()
    private var onlineFailed = false

    private val albumPrefix = "ia:"
    private fun isArchiveAlbum(t: Track) = t.source == Track.SOURCE_ONLINE && t.remoteId.startsWith(albumPrefix)

    private fun loadOnlineSrc() {
        try { onlineSrc = OnlineSource.values().getOrNull(sortPrefs().getInt("online_src", 0)) ?: OnlineSource.ARCHIVE } catch (_: Exception) { }
    }

    private fun onlineOption(): OnlineOption {
        val all = OnlineSource.values()
        val genres = OnlineApi.GENRES
        return OnlineOption(
            all.map { getString(it.labelRes) }, { all.indexOf(onlineSrc) },
            { onlineSrc = all[it]; try { sortPrefs().edit().putInt("online_src", it).apply() } catch (_: Exception) { } },
            listOf(getString(R.string.online_genre_all)) + genres.map { g -> g.replaceFirstChar { c -> c.uppercase() } },
            { onlineGenre?.let { genres.indexOf(it) + 1 } ?: 0 }, { onlineGenre = if (it == 0) null else genres[it - 1] },
            { onlineLossless }, { onlineLossless = it }, { onlineCommercial }, { onlineCommercial = it }
        )
    }

    /** A source or option changed: ask again (the old results belong to the old choice). */
    private fun onlineChanged() {
        onlineBase = emptyList()
        runOnlinePage()
    }

    /** Searches the chosen source for what is typed (empty box = its popular songs) and shows the result like any other tab. */
    private fun runOnlinePage() {
        if (_binding == null || source != Track.SOURCE_ONLINE) return
        val ctx = requireContext()
        val app = ctx.applicationContext
        val src = onlineSrc
        // Like the YouTube tab: nothing typed = the songs played before (no search, no network).
        if (binding.searchInput.text.isNullOrBlank()) {
            searchToken++
            binding.searchProgress.visibility = View.GONE
            onlineFailed = false
            showOnlineList()
            return
        }
        if (OnlineApi.needsKey(app, src)) {
            searchToken++
            binding.searchProgress.visibility = View.GONE
            shown = emptyList()
            adapter.submit(shown)
            binding.musicCount.text = getString(src.labelRes)
            showEmpty(R.string.online_need_jamendo_key, R.string.jamendo_key_title) {
                OnlineKeys.showJamendoDialog(ctx) { runOnlinePage() }
            }
            return
        }
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        val filter = OnlineFilter(onlineGenre, onlineLossless, onlineCommercial)
        val token = ++searchToken
        onlineFailed = false
        binding.searchProgress.visibility = View.VISIBLE
        try {
            netExecutor.execute {
                if (token != searchToken) return@execute
                val result = runCatching {
                    OnlineApi.search(app, src, q, filter).map { r ->
                        when (r) {
                            is OnlineResult.Song -> r.track
                            is OnlineResult.Album -> Track(
                                0L, r.item.title, r.item.creator, "", 0L, Track.SOURCE_ONLINE, albumPrefix + r.item.identifier, r.item.thumbUrl
                            )
                        }
                    }
                }
                handler.post {
                    if (_binding == null || token != searchToken || source != Track.SOURCE_ONLINE) return@post
                    binding.searchProgress.visibility = View.GONE
                    result.onSuccess { onlineBase = it }.onFailure { onlineBase = emptyList(); onlineFailed = true }
                    showOnlineList()
                }
            }
        } catch (e: Exception) {
            binding.searchProgress.visibility = View.GONE
        }
    }

    /** Online list after the filter button's choices (length / artist) and sort order. */
    private fun showOnlineList() {
        if (_binding == null) return
        val blank = binding.searchInput.text.isNullOrBlank()
        val base = if (blank) MusicPrefs.onlineHistory(requireContext()) else onlineBase
        var list = if (onlineFilter.active) base.filter { isArchiveAlbum(it) || onlineFilter.matches(it) } else base
        if (onlineLossless && !blank) list = list.filter { isArchiveAlbum(it) || AudioQuality.cached(it)?.lossless == true }
        list = when (onlineSort) {
            1 -> list.sortedWith { a, b -> byText(a.title, b.title) }
            2 -> list.sortedWith { a, b -> byText(b.title, a.title) }
            else -> list
        }
        shown = list
        adapter.submit(list)
        binding.musicCount.text = if (list.isEmpty()) getString(onlineSrc.labelRes)
        else if (blank) getString(R.string.online_history_title, list.size)
        else resources.getQuantityString(R.plurals.online_result_count, list.size, list.size, getString(onlineSrc.labelRes))
        when {
            list.isNotEmpty() -> showList()
            blank && !onlineFilter.active -> showEmpty(R.string.online_search_prompt)
            onlineFailed -> showEmpty(R.string.online_network_error, R.string.radio_retry) { runOnlinePage() }
            binding.searchProgress.visibility == View.VISIBLE -> showEmpty(R.string.radio_loading)
            else -> showEmpty(R.string.music_no_results)
        }
        adapter.setCurrentKey(service?.current?.key.orEmpty())
    }

    /** An Internet Archive album row was tapped: fetch its songs, then play them as a queue. */
    private fun playArchiveAlbum(album: Track) {
        val app = requireContext().applicationContext
        val item = ArchiveItem(album.remoteId.removePrefix(albumPrefix), album.title, album.artist, album.artUrl)
        binding.searchProgress.visibility = View.VISIBLE
        try {
            netExecutor.execute {
                val songs = runCatching { ArchiveApi.tracks(app, item) }.getOrDefault(emptyList())
                handler.post {
                    if (_binding == null) return@post
                    binding.searchProgress.visibility = View.GONE
                    if (songs.isEmpty()) Toast.makeText(requireContext(), R.string.music_no_results, Toast.LENGTH_SHORT).show() else service?.setQueueAndPlay(songs, 0)
                }
            }
        } catch (e: Exception) {
            binding.searchProgress.visibility = View.GONE
        }
    }

    /** What an online tab still needs before it can search, plus the button that fixes it. */
    private class Blocker(val text: Int, val actionText: Int, val action: () -> Unit)

    private val liveSearch = Runnable {
        if (_binding != null && source == Track.SOURCE_ONLINE) runOnlinePage()
        else if (_binding != null && source != Track.SOURCE_LOCAL) runOnlineSearch()
    }

    /** Radio tab, nothing typed: popular stations (loaded once, with a retry button on failure). */
    private fun showRadioDefault() {
        val list = radioDefault?.let { radioFiltered(it) }
        if (list != null) {
            shown = list
            adapter.submit(list)
            binding.musicCount.text = getString(R.string.radio_popular_title, list.size)
            if (list.isEmpty()) {
                showEmpty(R.string.music_no_results)
            } else showList()
            adapter.setCurrentKey(service?.current?.key.orEmpty())
            return
        }
        shown = emptyList()
        adapter.submit(shown)
        binding.musicCount.text = sourceName(Track.SOURCE_RADIO)
        if (radioFailed) {
            showEmpty(R.string.online_network_error, R.string.radio_retry) {
                radioFailed = false
                showOnline()
            }
            return
        }
        showEmpty(R.string.radio_loading)
        if (radioLoading) return
        radioLoading = true
        binding.searchProgress.visibility = View.VISIBLE
        val app = requireContext().applicationContext
        try {
            netExecutor.execute {
                val result = runCatching { RadioApi.popularAll(app) }
                handler.post {
                    radioLoading = false
                    if (_binding == null) return@post
                    binding.searchProgress.visibility = View.GONE
                    result.onSuccess { radioDefault = it }.onFailure { radioFailed = true }
                    if (source == Track.SOURCE_RADIO && binding.searchInput.text.isNullOrBlank()) showOnline()
                }
            }
        } catch (e: Exception) {
            radioLoading = false
            binding.searchProgress.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ Google Drive songs (merged into Device)

    private var driveAll: List<Track>? = null
    private var driveLoading = false
    private var driveError: String? = null
    private var driveLoadedAt = 0L
    private var driveConsentAsked = false
    private var driveEmail = ""

    // Drive "read your files" permission (first time the tab is used); on OK the list is loaded again.
    private val driveConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (_binding == null) return@registerForActivityResult
            if (res.resultCode == android.app.Activity.RESULT_OK) {
                driveError = null
                loadDrive(force = true)
            } else {
                driveError = getString(R.string.drive_music_no_permission)
            }
        }

    private fun loadDrive(force: Boolean) {
        if (driveLoading) return
        if (!force && driveAll != null && System.currentTimeMillis() - driveLoadedAt < 2 * 60_000L) return
        driveLoading = true
        binding.searchProgress.visibility = View.VISIBLE
        val app = requireContext().applicationContext
        try {
            netExecutor.execute {
                var consent: Intent? = null
                val result = try {
                    Result.success(DriveMusic.listAudio(app))
                } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
                    consent = e.intent
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(e)
                }
                handler.post {
                    driveLoading = false
                    if (_binding == null) return@post
                    binding.searchProgress.visibility = View.GONE
                    result.onSuccess {
                        driveAll = it
                        driveError = null
                        driveConsentAsked = false
                        driveLoadedAt = System.currentTimeMillis()
                    }.onFailure { e ->
                        val c = consent
                        if (c != null && !driveConsentAsked) {
                            driveConsentAsked = true
                            try { driveConsentLauncher.launch(c); return@post } catch (_: Exception) { }
                        }
                        if (driveAll == null) driveError = when {
                            e is DriveMusic.NotSignedInException -> getString(R.string.drive_music_need_signin)
                            c != null -> getString(R.string.drive_music_no_permission)
                            e is java.io.IOException && e !is DriveMusic.DriveHttpException ->
                                getString(R.string.online_network_error)
                            else -> e.message.orEmpty()
                        }
                    }
                    if (source == Track.SOURCE_LOCAL) applyFilter() // Drive songs join the Device list
                }
            }
        } catch (e: Exception) {
            driveLoading = false
            binding.searchProgress.visibility = View.GONE
        }
    }

    private fun runOnlineSearch() {
        if (source == Track.SOURCE_ONLINE) { runOnlinePage(); return }
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        val src = source
        onlineQuery[src] = q
        val token = ++searchToken
        binding.searchProgress.visibility = View.VISIBLE
        val app = requireContext().applicationContext
        try {
            netExecutor.execute {
                if (token != searchToken) return@execute // superseded while it was waiting: don't even start
                val result = runCatching {
                    if (src == Track.SOURCE_RADIO) RadioApi.searchAll(app, q) else YouTubeApi.userSearch(app, q)
                }
                handler.post {
                    if (_binding == null || token != searchToken) return@post
                    binding.searchProgress.visibility = View.GONE
                    result.onSuccess {
                        onlineResults[src] = it
                        if (source == src) showOnline()
                    }.onFailure { onSearchFailed(it) }
                }
            }
        } catch (e: Exception) {
            binding.searchProgress.visibility = View.GONE
        }
    }

    private fun onSearchFailed(e: Throwable) {
        if (e is ApiException && e.code == RemoteEngine.ERR_NETWORK) {
            toast(R.string.online_network_error)
            return
        }
        Toast.makeText(
            requireContext(), getString(R.string.online_search_failed, e.message.orEmpty()), Toast.LENGTH_LONG
        ).show()
    }

    private fun openSettings() {
        startActivity(Intent(requireContext(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PAGE_ONLINE))
    }

    /** Handles a request from the music-search widget (MainActivity.pendingMusicSearch): switch
     *  to the tapped source, then focus the search box and show the keyboard. Consumed once. */
    fun applyLaunchRequest() {
        val req = MainActivity.pendingMusicSearch ?: return
        if (_binding == null) return // view not created yet; onViewCreated retries
        MainActivity.pendingMusicSearch = null
        if (req == Track.SOURCE_LOCAL || req == Track.SOURCE_YOUTUBE || req == Track.SOURCE_RADIO ||
            req == Track.SOURCE_DRIVE
        ) {
            selectSource(if (req == Track.SOURCE_DRIVE) Track.SOURCE_LOCAL else req)
        }
        binding.searchInput.requestFocus()
        // Give the pager page a moment to become the visible, focused window before the IME opens.
        binding.searchInput.postDelayed({
            if (_binding == null) return@postDelayed
            requireContext().getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun toast(res: Int) {
        if (isAdded) Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show()
    }

    /** Playback problems reported by the service for online tracks. */
    override fun onMusicError(code: Int) {
        if (_binding == null || !isAdded) return
        when (code) {
            RemoteEngine.ERR_RADIO_RETRY -> {
                val detail = (service ?: MusicService.instance)?.lastRadioError.orEmpty()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.radio_retrying) + if (detail.isBlank()) "" else "\n($detail)",
                    Toast.LENGTH_LONG
                ).show()
            }
            RemoteEngine.ERR_RADIO -> {
                val detail = (service ?: MusicService.instance)?.lastRadioError.orEmpty()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.radio_playback_failed) + if (detail.isBlank()) "" else "\n($detail)",
                    Toast.LENGTH_LONG
                ).show()
            }
            RemoteEngine.ERR_DRIVE -> {
                val detail = (service ?: MusicService.instance)?.lastDriveError.orEmpty()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.drive_music_play_failed) + if (detail.isBlank()) "" else "\n($detail)",
                    Toast.LENGTH_LONG
                ).show()
            }
            RemoteEngine.ERR_YT_UNPLAYABLE -> toast(R.string.youtube_unplayable)
            RemoteEngine.ERR_YT_CONFIG -> toast(R.string.youtube_config_error)
            RemoteEngine.ERR_YT_TIMEOUT -> toast(R.string.youtube_timeout)
            RemoteEngine.ERR_NETWORK -> toast(R.string.online_network_error)
            else -> if (code >= RemoteEngine.ERR_YT_PLAYER_BASE) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.youtube_player_error, code - RemoteEngine.ERR_YT_PLAYER_BASE),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                toast(R.string.online_playback_failed)
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private fun onTrackLongClicked(position: Int) {
        if (position !in shown.indices) return
        if (isArchiveAlbum(shown[position])) return // an album row is not a song: nothing to add to a playlist
        val inHistory = binding.searchInput.text.isNullOrBlank() && when (source) {
            Track.SOURCE_YOUTUBE -> MusicPrefs.youtubeHistory(requireContext()).isNotEmpty()
            Track.SOURCE_ONLINE -> MusicPrefs.onlineHistory(requireContext()).isNotEmpty()
            else -> false
        }
        val track = shown[position]
        val onDelete: (() -> Unit)? =
            if (track.source == Track.SOURCE_LOCAL && !track.missing) ({ confirmDeleteLocal(track) }) else null
        PlaylistUi.trackActions(requireContext(), track, null, inHistory, onDelete) {
            if (inHistory) showOnline()
        }
    }

    // ------------------------------------------------------------------ delete a song from the device

    private var pendingDelete: Track? = null

    /** Android 10: the user's OK for one file; Android 11+: the system's delete request (the file is gone on OK). */
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val t = pendingDelete
            pendingDelete = null
            if (t == null || _binding == null) return@registerForActivityResult
            if (res.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            if (android.os.Build.VERSION.SDK_INT >= 30) onSongDeleted(t) else deleteLocal(t)
        }

    /** Android 8-9 only: writing to shared storage needs this permission to delete a file. */
    private val deletePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val t = pendingDelete
            pendingDelete = null
            if (t == null || _binding == null) return@registerForActivityResult
            if (granted) deleteLocal(t) else toast(R.string.track_delete_failed)
        }

    private fun confirmDeleteLocal(t: Track) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.track_delete_confirm, t.title))
            .setPositiveButton(R.string.playlist_delete) { _, _ -> deleteLocal(t) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteLocal(t: Track) {
        val ctx = requireContext()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val pi = android.provider.MediaStore.createDeleteRequest(ctx.contentResolver, listOf(t.uri))
                pendingDelete = t
                deleteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                return
            }
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT < 29 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                pendingDelete = t
                deletePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
            if (ctx.contentResolver.delete(t.uri, null, null) > 0) onSongDeleted(t) else toast(R.string.track_delete_failed)
        } catch (e: SecurityException) {
            // Android 10: a file the app did not create needs the user's OK for this one file.
            if (android.os.Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException) {
                pendingDelete = t
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                )
            } else {
                toast(R.string.track_delete_failed)
            }
        } catch (e: Exception) {
            toast(R.string.track_delete_failed)
        }
    }

    /** The file is gone: stop it if it was playing, drop it from the playlists and from the list. */
    private fun onSongDeleted(t: Track) {
        val ctx = context ?: return
        val svc = service ?: MusicService.instance
        if (svc?.current?.key == t.key) svc.stopPlayback()
        // Also forget it in every playlist and in Liked songs, so none keeps a dead entry.
        (PlaylistStore.playlists(ctx).map { it.id } + PlaylistStore.LIKED_ID).forEach { id ->
            val list = if (id == PlaylistStore.LIKED_ID) PlaylistStore.liked(ctx) else PlaylistStore.get(ctx, id)?.tracks.orEmpty()
            if (list.any { it.syncKey == t.syncKey }) PlaylistStore.removeTrack(ctx, id, t)
        }
        allTracks = allTracks.filter { it.id != t.id }
        MusicLibrary.notifyChanged()
        if (_binding != null) refreshList()
        Toast.makeText(ctx, R.string.track_deleted, Toast.LENGTH_SHORT).show()
    }


    private fun onTrackClicked(position: Int) {
        val svc = service ?: return
        if (position !in shown.indices) return
        if (isArchiveAlbum(shown[position])) { playArchiveAlbum(shown[position]); return }
        svc.setQueueAndPlay(shown, position, fromYoutubeSearch = source == Track.SOURCE_YOUTUBE || source == Track.SOURCE_ONLINE)
    }

    private fun onPlayPauseClicked() {
        val svc = service ?: return
        if (svc.current != null) {
            svc.togglePlayPause()
        } else if (shown.isNotEmpty()) {
            // Nothing queued yet: play from the top of what's on screen.
            svc.setQueueAndPlay(shown, 0, fromYoutubeSearch = source == Track.SOURCE_YOUTUBE || source == Track.SOURCE_ONLINE)
        }
    }

    // ------------------------------------------------------------------ UI sync

    override fun onMusicStateChanged() {
        val b = _binding ?: return
        val svc = service ?: return
        val t = svc.current

        b.nowTitle.text = t?.title ?: getString(R.string.music_nothing_playing)
        b.nowArtist.text = t?.let { tr ->
            val tag = when (tr.source) {
                Track.SOURCE_YOUTUBE -> getString(R.string.source_youtube)
                Track.SOURCE_RADIO -> getString(R.string.source_radio)
                else -> ""
            }
            listOf(tr.artist.ifBlank { tr.album }, tag).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
        } ?: ""
        b.timeTotal.text = formatTime(svc.duration.toLong())
        b.progressSeek.max = svc.duration.coerceAtLeast(0)

        b.playPauseButton.setImageResource(if (svc.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        val art = svc.artwork
        if (art != null) {
            b.nowArt.setPadding(0, 0, 0, 0)
            b.nowArt.imageTintList = null
            b.nowArt.setImageBitmap(art)
        } else {
            val pad = (13 * resources.displayMetrics.density).toInt()
            b.nowArt.setPadding(pad, pad, pad, pad)
            b.nowArt.setImageResource(R.drawable.ic_music)
            b.nowArt.imageTintList = android.content.res.ColorStateList.valueOf(
                resolveAttrColor(R.attr.colorTextSecondary)
            )
        }

        val accent = resolveAttrColor(androidx.appcompat.R.attr.colorAccent)
        val inactive = resolveAttrColor(R.attr.colorTextSecondary)
        b.shuffleButton.setImageResource(if (svc.smartShuffle) R.drawable.ic_shuffle_smart else R.drawable.ic_shuffle)
        b.shuffleButton.imageTintList =
            android.content.res.ColorStateList.valueOf(if (svc.shuffle) accent else inactive)
        b.repeatButton.setImageResource(
            if (svc.repeatMode == MusicService.REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        b.repeatButton.imageTintList = android.content.res.ColorStateList.valueOf(
            if (svc.repeatMode == MusicService.REPEAT_OFF) inactive else accent
        )

        adapter.setCurrentKey(t?.key.orEmpty())
        updateProgress()
    }

    private fun toggleSaver() {
        val ctx = requireContext()
        // Saver is on only because of the automatic "on mobile data" setting: tapping turns that off.
        if (MusicPrefs.saver(ctx) && !MusicPrefs.saverManual(ctx)) MusicPrefs.setSaverAuto(ctx, false)
        else MusicPrefs.setSaver(ctx, !MusicPrefs.saverManual(ctx))
        val on = MusicPrefs.saver(ctx)
        if (on) DataUsage.reset(ctx) // a fresh count from the moment saver is switched on
        updateSaverButton()
        updateDataUsage()
        toast(if (on) R.string.saver_on_toast else R.string.saver_off_toast)
        adapter.notifyDataSetChanged() // thumbnails appear / disappear
        service?.let { svc -> if (on) svc.notifyCoverMode() else svc.refreshArtwork() }
    }

    /** Private mode button (right of the saver logo): accent colour while on, plain while off. */
    private fun updatePrivateButton() {
        val b = _binding ?: return
        val on = MusicPrefs.privateMode(requireContext())
        val color = resolveAttrColor(if (on) androidx.appcompat.R.attr.colorAccent else R.attr.colorTextSecondary)
        b.privateButton.imageTintList = ColorStateList.valueOf(color)
    }

    /** Logo menyala (warna aksen) saat mode hemat aktif, redup saat normal. */
    private fun updateSaverButton() {
        val b = _binding ?: return
        val on = MusicPrefs.saver(requireContext())
        val color = resolveAttrColor(if (on) androidx.appcompat.R.attr.colorAccent else R.attr.colorTextSecondary)
        b.saverButton.imageTintList = ColorStateList.valueOf(color)
        b.saverButton.isSelected = on
    }

    /** Data used since Data saver was switched on, left of the saver button (hidden when saver is off). */
    private fun updateDataUsage() {
        val b = _binding ?: return
        val ctx = context ?: return
        if (!MusicPrefs.saver(ctx)) {
            DataUsage.clear(ctx)
            b.dataUsedText.visibility = View.GONE
            return
        }
        val used = DataUsage.used(ctx)
        if (used < 0) { b.dataUsedText.visibility = View.GONE; return }
        b.dataUsedText.text = DataUsage.format(used)
        b.dataUsedText.visibility = View.VISIBLE
    }

    private fun updateProgress() {
        val b = _binding ?: return
        val svc = service ?: return
        if (dragging) return
        val pos = svc.position
        b.progressSeek.progress = pos
        b.timeElapsed.text = formatTime(pos.toLong())
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun formatTime(ms: Long): String = formatDuration(ms)
}
