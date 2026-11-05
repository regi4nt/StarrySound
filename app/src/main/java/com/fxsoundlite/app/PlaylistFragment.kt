package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fxsoundlite.app.databinding.FragmentPlaylistBinding
import com.fxsoundlite.app.databinding.ViewPlaylistHeaderBinding
import java.util.Locale

/**
 * Page 2 of MainActivity's pager (right of "Search"): create a playlist, search playlists and
 * open "Liked songs" or any playlist. The overview lists [PlRow]s; an opened list shows its songs
 * with the shared [TrackAdapter]. Data lives in [PlaylistStore]; playback goes through [MusicService].
 */
class PlaylistFragment : Fragment(), MusicService.Listener {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    private var service: MusicService? = null
    private var bound = false

    /** null = overview (create / liked / playlists); otherwise the id of the open list. */
    private var openList: String? = null
    /** Id of the folder whose playlists the overview shows; null = main list. */
    private var openFolder: String? = null
    /** One of SRC_*: the source folder (Device / Drive / YouTube / Spotify) whose playlists the overview shows; null = main list. */
    private var openSource: String? = null
    /** True while a generated playlist is being built (the top row shows "Picking songs..."). */
    private var generating = false
    private var shown: List<Track> = emptyList()
    /** Song order inside an opened list (one of SORT_*), remembered across launches. */
    private var sortMode = SORT_DEFAULT
    private var backCallback: OnBackPressedCallback? = null
    /** Multi-select mode inside an opened (editable) list; [selected] = [Track.syncKey]s of the picked songs. */
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Id of the playlist being exported to an M3U file (set before the file picker opens). */
    private var exportId: String? = null

    private val createM3u = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) writeM3u(uri)
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeBackup(uri)
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    /** Set while the edit dialog waits for the gallery's answer. */
    private var onCoverPicked: ((Uri) -> Unit)? = null
    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onCoverPicked?.invoke(uri)
    }

    // ---- YouTube account playlists (read live from the signed-in Google account)
    private val ytHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ytExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var ytLists: List<YouTubeAccount.Pl>? = null
    private var ytLoading = false
    private var ytLoadedAt = 0L
    /** Time of the last failed fetch (offline, ...): no retry for [RETRY_AFTER_FAIL_MS], else a failing load
     *  would call refresh() -> load -> fail -> refresh() in a tight loop and the list could never be tapped. */
    private var ytFailedAt = 0L
    private var ytEmail = ""
    private var ytConsentAsked = false
    private val ytTracks = HashMap<String, List<Track>>()
    private val ytTracksLoading = HashSet<String>()
    private val ytTracksFailed = HashSet<String>()

    private val ytConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (_binding == null) return@registerForActivityResult
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            ytLoadedAt = 0L
            ytFailedAt = 0L
            loadYtLists()
        }
    }

    /** Fetches the account's playlists in the background (at most every 5 minutes); the page redraws when done. */
    private fun loadYtLists() {
        val app = context?.applicationContext ?: return
        if (ytLoading || !YouTubeAccount.isSignedIn(app)) return
        val email = DriveSync.email(app)
        if (email != ytEmail) { ytEmail = email; ytLists = null; ytTracks.clear(); ytTracksFailed.clear() }
        if (ytLists != null && System.currentTimeMillis() - ytLoadedAt < 5 * 60_000L) return
        if (System.currentTimeMillis() - ytFailedAt < RETRY_AFTER_FAIL_MS) return
        ytLoading = true
        ytExecutor.execute {
            var consent: Intent? = null
            val result = try {
                Result.success(YouTubeAccount.listPlaylists(app))
            } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
                consent = e.intent
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
            ytHandler.post {
                ytLoading = false
                if (_binding == null) return@post
                result.onSuccess {
                    ytLists = it
                    ytLoadedAt = System.currentTimeMillis()
                    ytFailedAt = 0L
                    ytConsentAsked = false
                    ytTracks.keys.retainAll(it.map { p -> YouTubeAccount.ID_PREFIX + p.id }.toSet())
                }.onFailure {
                    ytFailedAt = System.currentTimeMillis()
                    val c = consent
                    if (c != null && !ytConsentAsked) {
                        ytConsentAsked = true
                        try { ytConsentLauncher.launch(c) } catch (e: Exception) { android.util.Log.d("PlaylistFragment", "ignored: " + e) }
                    }
                }
                refresh()
            }
        }
    }

    /** Fetches the songs of one account playlist when it is opened. */
    private fun loadYtTracks(id: String) {
        val app = context?.applicationContext ?: return
        if (!ytTracksLoading.add(id)) return
        ytTracksFailed.remove(id)
        ytExecutor.execute {
            val result = try {
                Result.success(YouTubeAccount.listTracks(app, id.removePrefix(YouTubeAccount.ID_PREFIX)))
            } catch (e: Exception) {
                Result.failure(e)
            }
            ytHandler.post {
                ytTracksLoading.remove(id)
                if (_binding == null) return@post
                result.onSuccess { ytTracks[id] = it }.onFailure { ytTracksFailed.add(id) }
                refresh()
            }
        }
    }

    // ---- Google Drive folders with audio files, shown as playlists
    private var drvFolders: List<DriveMusic.Folder>? = null
    private var drvLoading = false
    private var drvLoadedAt = 0L
    private var drvFailedAt = 0L // see ytFailedAt
    private var drvEmail = ""
    private var drvVideo = false // which kind of files drvFolders holds: audio (music mode) or video (video mode)
    private var drvConsentAsked = false

    private val drvConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (_binding == null) return@registerForActivityResult
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            drvLoadedAt = 0L
            drvFailedAt = 0L
            loadDriveFolders()
        }
    }

    private fun loadDriveFolders() {
        val app = context?.applicationContext ?: return
        if (drvLoading || !DriveMusic.isSignedIn(app)) return
        val email = DriveSync.email(app)
        val video = MusicPrefs.videoMode(app)
        if (email != drvEmail || video != drvVideo) { drvEmail = email; drvVideo = video; drvFolders = null }
        if (drvFolders != null && System.currentTimeMillis() - drvLoadedAt < 5 * 60_000L) return
        if (System.currentTimeMillis() - drvFailedAt < RETRY_AFTER_FAIL_MS) return
        drvLoading = true
        ytExecutor.execute {
            var consent: Intent? = null
            val result = try {
                Result.success(DriveMusic.listFolders(app, video))
            } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
                consent = e.intent
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
            ytHandler.post {
                drvLoading = false
                if (_binding == null) return@post
                if (video != MusicPrefs.videoMode(app)) { loadDriveFolders(); return@post } // mode changed meanwhile: reload
                result.onSuccess {
                    drvFolders = it
                    drvLoadedAt = System.currentTimeMillis()
                    drvFailedAt = 0L
                    drvConsentAsked = false
                }.onFailure {
                    drvFailedAt = System.currentTimeMillis()
                    val c = consent
                    if (c != null && !drvConsentAsked) {
                        drvConsentAsked = true
                        try { drvConsentLauncher.launch(c) } catch (e: Exception) { android.util.Log.d("PlaylistFragment", "ignored: " + e) }
                    }
                }
                refresh()
            }
        }
    }

    // ---- Folders of the device / SD card / OTG drive that hold songs, shown as playlists (label "Device" / "SD card" / "OTG")
    private var devFolders: List<MusicLibrary.DeviceFolder>? = null
    private var devLoading = false
    private val devExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var devVideo = false // which kind of files devFolders holds: songs (music mode) or videos (video mode)

    private fun deviceFoldersAvailable(ctx: Context) =
        if (MusicPrefs.videoMode(ctx)) MusicLibrary.canReadVideo(ctx) else MusicLibrary.canRead(ctx)

    /** Takes the folders of the last library scan; scans in the background when there is none yet or the library changed. */
    private fun loadDeviceFolders() {
        val app = context?.applicationContext ?: return
        if (!deviceFoldersAvailable(app)) { devFolders = null; return }
        val video = MusicPrefs.videoMode(app)
        if (video != devVideo) { devVideo = video; devFolders = null }
        if (video) {
            val known = MusicLibrary.videoFolders
            if (known != null) devFolders = known
            if (known != null && MusicLibrary.videoFoldersVersion == MusicLibrary.version) return
        } else {
            val known = MusicLibrary.folders
            if (known != null) devFolders = known
            if (known != null && MusicLibrary.foldersVersion == MusicLibrary.version) return
        }
        if (devLoading) return
        devLoading = true
        devExecutor.execute {
            try {
                if (video) MusicLibrary.loadVideos(app) else MusicLibrary.load(app)
            } catch (e: Exception) {
                android.util.Log.w("PlaylistFragment", "device folders not loaded", e)
            }
            ytHandler.post {
                devLoading = false
                if (_binding == null) return@post
                if (video != MusicPrefs.videoMode(app)) { loadDeviceFolders(); return@post } // mode changed meanwhile
                devFolders = if (video) MusicLibrary.videoFolders else MusicLibrary.folders
                refresh()
            }
        }
    }

    private fun deviceFolderPlaylist(id: String): Playlist? {
        val f = devFolders?.takeIf { devVideo == MusicPrefs.videoMode(requireContext()) }?.firstOrNull { DEVICE_PREFIX + it.id == id } ?: return null
        return Playlist(id, f.name, f.tracks)
    }

    private fun driveFolderPlaylist(id: String): Playlist? {
        val f = drvFolders?.takeIf { drvVideo == MusicPrefs.videoMode(requireContext()) }?.firstOrNull { DRIVE_PREFIX + it.id == id } ?: return null
        return Playlist(id, f.name, f.tracks)
    }

    /** True for playlists that live in an account (YouTube / Drive) and are read-only here. */
    private fun isRemoteList(id: String?): Boolean = YouTubeAccount.isYtId(id) || (id != null && (id.startsWith(DRIVE_PREFIX) || id.startsWith(DEVICE_PREFIX)))

    // ---- Convert: local playlist -> YouTube account playlist, or YouTube account playlist -> local playlist

    private var convertDialog: AlertDialog? = null
    /** Runs again after the user allows YouTube write access. */
    private var pendingConvert: (() -> Unit)? = null

    private val writeConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val again = pendingConvert
        pendingConvert = null
        if (_binding == null) return@registerForActivityResult
        if (res.resultCode == android.app.Activity.RESULT_OK) again?.invoke()
        else Toast.makeText(requireContext(), R.string.convert_no_permission, Toast.LENGTH_LONG).show()
    }

    private fun showConvertDialog() {
        val ctx = requireContext()
        if (!YouTubeAccount.isSignedIn(ctx)) {
            toast(ctx, R.string.convert_need_signin)
            return
        }
        loadYtLists()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.convert_title)
            .setItems(arrayOf(getString(R.string.convert_to_yt), getString(R.string.convert_to_local))) { _, which ->
                if (which == 0) pickLocalForYt() else pickYtForLocal()
            }
            .setNegativeButton(R.string.radio_cancel, null)
            .show()
    }

    private fun pickLocalForYt() {
        val ctx = requireContext()
        val lists = PlaylistStore.playlists(ctx).filter { it.tracks.isNotEmpty() }
        if (lists.isEmpty()) { toast(ctx, R.string.convert_no_local); return }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.convert_pick)
            .setItems(lists.map { "${it.name} (${it.tracks.size})" }.toTypedArray()) { _, which -> convertToYt(lists[which]) }
            .setNegativeButton(R.string.radio_cancel, null)
            .show()
    }

    private fun pickYtForLocal() {
        val ctx = requireContext()
        val lists = ytLists
        if (lists == null) { toast(ctx, R.string.yt_playlist_loading); return }
        if (lists.isEmpty()) { toast(ctx, R.string.convert_no_yt); return }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.convert_pick)
            .setItems(lists.map { "${it.title} (${it.count})" }.toTypedArray()) { _, which -> convertToLocal(lists[which]) }
            .setNegativeButton(R.string.radio_cancel, null)
            .show()
    }

    private fun showProgress(text: String) {
        convertDialog?.dismiss()
        convertDialog = AlertDialog.Builder(requireContext()).setMessage(text).setCancelable(false).show()
    }

    private fun finishConvert(text: String) {
        convertDialog?.dismiss()
        convertDialog = null
        if (_binding == null) return
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        ytLoadedAt = 0L
        refresh()
    }

    /** Local playlist -> a new private playlist in the YouTube account (songs are searched on YouTube). */
    private fun convertToYt(pl: Playlist) {
        val app = requireContext().applicationContext
        showProgress(getString(R.string.convert_working))
        ytExecutor.execute {
            var consent: Intent? = null
            var message: String
            try {
                YouTubeAccount.ensureWriteAccess(app)
                val songs = pl.tracks.filter { !it.missing && it.source != Track.SOURCE_RADIO }
                val batch = songs.take(MAX_CONVERT_SONGS)
                val newId = YouTubeAccount.createPlaylist(app, pl.name)
                var added = 0
                var notFound = 0
                var quota = false
                val seen = HashSet<String>()
                for ((i, t) in batch.withIndex()) {
                    ytHandler.post {
                        if (convertDialog != null) convertDialog?.setMessage(getString(R.string.convert_progress, i + 1, batch.size))
                    }
                    val vid = if (t.source == Track.SOURCE_YOUTUBE) t.remoteId else try {
                        YouTubeApi.search(app, "${t.artist} ${t.title}".trim(), maxResults = 3)
                            .firstOrNull { it.source == Track.SOURCE_YOUTUBE }?.remoteId
                    } catch (_: Exception) { null }
                    if (vid.isNullOrBlank()) { notFound++; continue }
                    if (!seen.add(vid)) continue
                    try {
                        YouTubeAccount.addVideo(app, newId, vid)
                        added++
                    } catch (e: YouTubeAccount.HttpException) {
                        if (e.code == 403) { quota = true; break } else notFound++
                    }
                }
                message = getString(R.string.convert_done_yt, added, songs.size, pl.name) +
                    (if (notFound > 0) "\n" + getString(R.string.convert_not_found, notFound) else "") +
                    (if (quota) "\n" + getString(R.string.convert_quota) else "") +
                    (if (songs.size > MAX_CONVERT_SONGS && !quota) "\n" + getString(R.string.convert_capped, MAX_CONVERT_SONGS) else "")
            } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
                consent = e.intent
                message = ""
            } catch (e: Exception) {
                message = getString(R.string.convert_failed, e.message.orEmpty())
            }
            ytHandler.post {
                val c = consent
                if (c != null) {
                    convertDialog?.dismiss(); convertDialog = null
                    pendingConvert = { convertToYt(pl) }
                    try { writeConsentLauncher.launch(c) } catch (_: Exception) { pendingConvert = null }
                } else finishConvert(message)
            }
        }
    }

    /** YouTube account playlist -> a local playlist (songs found on this device are switched to the device file). */
    private fun convertToLocal(info: YouTubeAccount.Pl) {
        val app = requireContext().applicationContext
        showProgress(getString(R.string.convert_working))
        ytExecutor.execute {
            val message = try {
                val key = YouTubeAccount.ID_PREFIX + info.id
                val tracks = ytTracks[key] ?: YouTubeAccount.listTracks(app, info.id)
                var onDevice = 0
                val mapped = tracks.map { t ->
                    val probe = Track(id = -1L, title = t.title, artist = t.artist, album = "", durationMs = t.durationMs)
                    val r = try { LocalMatcher.resolve(app, probe) } catch (_: Exception) { probe.copy(missing = true) }
                    if (!r.missing) { onDevice++; r } else t
                }
                if (mapped.isEmpty()) getString(R.string.convert_no_songs)
                else {
                    val saved = PlaylistStore.createWith(app, info.title, mapped)
                    DriveSync.markDirty(app)
                    getString(R.string.convert_done_local, saved.tracks.size, saved.name, onDevice)
                }
            } catch (e: Exception) {
                getString(R.string.convert_failed, e.message.orEmpty())
            }
            ytHandler.post { finishConvert(message) }
        }
    }

    /** The open account playlist as a [Playlist] (songs empty until loaded); null if it is gone. */
    private fun ytPlaylist(id: String): Playlist? {
        val info = ytLists?.firstOrNull { YouTubeAccount.ID_PREFIX + it.id == id } ?: return null
        return Playlist(id, info.title, ytTracks[id].orEmpty())
    }

    /** Any playlist the page can open: a stored one or one from the YouTube account. */
    private fun resolveList(ctx: Context, id: String): Playlist? =
        when {
            YouTubeAccount.isYtId(id) -> ytPlaylist(id)
            id.startsWith(DRIVE_PREFIX) -> driveFolderPlaylist(id)
            id.startsWith(DEVICE_PREFIX) -> deviceFolderPlaylist(id)
            else -> PlaylistStore.get(ctx, id)
        }

    /** Cover / description row on top of an opened list's songs; scrolls with them. */
    private var header: ViewPlaylistHeaderBinding? = null
    private var openAdapter: androidx.recyclerview.widget.ConcatAdapter? = null

    private class HeaderAdapter(private val v: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(v) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    private val trackAdapter = TrackAdapter(
        onClick = { position -> onTrackClicked(position) },
        onLong = { position -> onTrackLongClicked(position) }
    )
    private val rowAdapter = RowAdapter(
        onClick = { row -> onRowClicked(row) },
        onLong = { row -> onRowLongClicked(row) }
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MusicService.LocalBinder).getService().also { it.addListener(this@PlaylistFragment) }
            bound = true
            onMusicStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportId = savedInstanceState?.getString(STATE_EXPORT_ID)
        sortMode = try {
            requireContext().getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).getInt(SORT_KEY, SORT_DEFAULT)
        } catch (_: Exception) { SORT_DEFAULT }
        listSort = try {
            requireContext().getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).getInt(LIST_SORT_KEY, SORT_DEFAULT)
        } catch (_: Exception) { SORT_DEFAULT }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EXPORT_ID, exportId)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.plList.layoutManager = LinearLayoutManager(requireContext())
        binding.plList.adapter = rowAdapter
        header = ViewPlaylistHeaderBinding.inflate(layoutInflater, binding.plList, false)
        openAdapter = androidx.recyclerview.widget.ConcatAdapter(HeaderAdapter(header!!.root), trackAdapter)

        binding.plSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        binding.plSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                requireContext().getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(binding.plSearchInput.windowToken, 0)
                true
            } else false
        }
        // The "+" in the header, next to the title, offers two ways to add a playlist: create an empty one, or generate one.
        binding.plAddButton.setOnClickListener { showAddMenu() }
        binding.plMoreButton.setOnClickListener { showMoreMenu() }
        binding.plFilterButton.setOnClickListener { showFilterDialog() }
        // "‹ Name · N songs" doubles as a back button while a list is open.
        binding.plBackButton.setOnClickListener { if (openList != null || openFolder != null || openSource != null) goBack() }
        binding.plPlayBtn.setOnClickListener { if (openList == null) openFolder?.let { playFolder(it, false) } else playOpenList() }
        binding.plEditBtn.setOnClickListener { showEditDialog() }
        binding.plSelectBtn.setOnClickListener { startSelection(null) }
        binding.plSelClose.setOnClickListener { exitSelection(); refresh() }
        binding.plSelAll.setOnClickListener { toggleSelectAll() }
        binding.plSelAdd.setOnClickListener { addSelected() }
        binding.plSelMove.setOnClickListener { if (openList == null) moveSelectedLists() else moveSelected() }
        binding.plSelDelete.setOnClickListener { if (openList == null) deleteSelectedLists() else removeSelected() }

        // Only intercepts Back while this page is the visible one (see updateBack()).
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = goBack()
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
    }

    override fun onStart() {
        super.onStart()
        requireContext().bindService(
            Intent(requireContext(), MusicService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // likes / playlists may have changed from the player or the Search page
        applyCoverBg()
        backCallback?.isEnabled = openList != null // this page is now the visible one
        consumePendingImport()
        consumePendingOpen()
        CollabPlaylist.onRemoteChange = { if (_binding != null) refresh() } // a friend changed a shared playlist
        CollabPlaylist.startPolling(requireContext())
        autoImportSpotify()
    }

    /** Connected to Spotify: its playlists are copied into the automatic Spotify folder, in the background. */
    private fun autoImportSpotify() {
        val ctx = context?.applicationContext ?: return
        SpotifyAutoImport.maybeRun(ctx,
            onStart = { n -> Toast.makeText(ctx, ctx.getString(R.string.spotify_auto_import_start, n), Toast.LENGTH_SHORT).show() },
            onDone = { r, left ->
                Toast.makeText(ctx, ctx.getString(R.string.spotify_import_done, r.playlists, r.songs, r.missed), Toast.LENGTH_LONG).show()
                if (left > 0) Toast.makeText(ctx, ctx.getString(R.string.spotify_auto_import_more, left), Toast.LENGTH_SHORT).show()
                if (_binding != null) refresh()
            })
    }

    override fun onPause() {
        CollabPlaylist.stopPolling()
        CollabPlaylist.onRemoteChange = null
        backCallback?.isEnabled = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
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
        header = null
        openAdapter = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ytExecutor.shutdownNow()
    }

    // ------------------------------------------------------------------ rendering

    /** Back pressed while this page is shown: closes the open list and returns true; false when none is open. */
    fun consumeBack(): Boolean {
        if (selecting && _binding != null) { exitSelection(); refresh(); return true } // Back first leaves selection mode, also on the main list
        if ((openList == null && openFolder == null && openSource == null) || _binding == null) return false
        goBack()
        return true
    }

    /** Playlist tab tapped again while shown: leaves any open list / folder / source and goes back to the top of the main list. */
    fun resetToStart() {
        if (_binding == null) return
        if (selecting) exitSelection()
        openList = null; openFolder = null; openSource = null
        if (binding.plSearchInput.text.isNullOrEmpty()) refresh() else binding.plSearchInput.setText("")
        binding.plList.scrollToPosition(0)
    }

    private fun goBack() {
        if (selecting) { exitSelection(); refresh(); return } // Back first leaves selection mode
        if (openList != null) openList = null else if (openFolder != null) openFolder = null else openSource = null // a list closes to its folder, a folder to the main list
        if (binding.plSearchInput.text.isNullOrEmpty()) refresh() else binding.plSearchInput.setText("")
    }

    // ------------------------------------------------------------------ opened list: header

    /** Cover, description, "N songs · X min", Play and Edit of the open list. */
    private fun bindDetail(pl: Playlist) {
        val b = _binding ?: return
        val ctx = context ?: return
        val liked = pl.id == PlaylistStore.LIKED_ID
        val h = header ?: return
        b.plEditBtn.visibility = View.GONE // Edit lives in the ⋮ menu now (showMoreMenu)
        h.plDesc.text = when {
            pl.description.isNotBlank() -> pl.description
            liked -> ""
            else -> getString(R.string.playlist_no_desc)
        }
        h.plDesc.alpha = if (pl.description.isBlank()) 0.6f else 1f
        h.plDesc.visibility = if (h.plDesc.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val minutes = ((pl.tracks.sumOf { it.durationMs } + 59_999L) / 60_000L).toInt()
        val people = when {
            PlaylistBlend.isBlend(ctx, pl.id) -> PlaylistBlend.percentOf(ctx, pl.id).let { p ->
                if (p > 0) getString(R.string.blend_badge, p) else getString(R.string.blend_badge_waiting)
            }
            CollabPlaylist.isShared(ctx, pl.id) -> getString(R.string.collab_badge, CollabPlaylist.info(pl.id)?.members ?: 1)
            else -> ""
        }
        h.plMeta.text = listOf(songCount(pl.tracks.size), if (minutes > 0) getString(R.string.playlist_total_min, minutes) else "", people)
            .filter { it.isNotBlank() }.joinToString(" \u00B7 ")
        b.plPlayBtn.isEnabled = pl.tracks.any { !it.missing }
        b.plPlayBtn.alpha = if (b.plPlayBtn.isEnabled) 1f else 0.4f // an icon button has no built-in disabled look

        // Cover: the chosen picture, else the first song's artwork, else the placeholder icon.
        val cover = h.plCover
        val file = PlaylistStore.coverFile(ctx, pl.id)
        val pad = (64 * ctx.resources.displayMetrics.density).toInt()
        val placeholder = {
            cover.setPadding(pad, pad, pad, pad)
            cover.imageTintList = ColorStateList.valueOf(attrColor(R.attr.colorTextSecondary))
            cover.setImageResource(R.drawable.ic_playlist)
        }
        val custom = if (!liked && !isRemoteList(pl.id) && file.exists()) {
            try { android.graphics.BitmapFactory.decodeFile(file.absolutePath) } catch (e: Exception) { null }
        } else null
        if (custom != null) {
            cover.setPadding(0, 0, 0, 0); cover.imageTintList = null; cover.setImageBitmap(custom)
            listAccent = CoverColors.accentOf(ctx, custom)
            applyCoverBg()
            return
        }
        placeholder()
        if (pl.tracks.none { it.artUrl.isNotBlank() } || MusicPrefs.saver(ctx)) { listAccent = null; applyCoverBg() }
        val art = pl.tracks.firstOrNull { it.artUrl.isNotBlank() }?.artUrl.orEmpty()
        if (art.isNotBlank() && !MusicPrefs.saver(ctx)) {
            cover.tag = art
            ImageLoader.load(art) { bmp ->
                if (bmp != null && _binding != null && cover.tag == art) {
                    cover.setPadding(0, 0, 0, 0); cover.imageTintList = null; cover.setImageBitmap(bmp)
                    listAccent = CoverColors.accentOf(ctx, bmp)
                    applyCoverBg()
                }
            }
        }
    }

    private fun attrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun playOpenList() {
        val ctx = context ?: return
        val id = openList ?: return
        val tracks = sortTracks(resolveList(ctx, id)?.tracks.orEmpty()).filter { !it.missing }
        val svc = service ?: MusicService.instance
        if (tracks.isNotEmpty()) MusicPrefs.setLastPlayedList(ctx, id)
        if (MusicPrefs.videoMode(ctx) && tracks.isNotEmpty()) { playAsVideo(tracks, 0); return }
        if (tracks.isEmpty() || svc == null) {
            Toast.makeText(ctx, R.string.playlist_empty_tracks, Toast.LENGTH_SHORT).show()
            return
        }
        svc.setQueueAndPlay(withVideosAsAudio(tracks), 0, fromPlaylist = resolveList(ctx, id)?.name)
    }

    /** Plays the playlist [id] from its row's long-press menu (Play / Shuffle play), without opening it. */
    private fun playRow(id: String, shuffle: Boolean) {
        val ctx = context ?: return
        if (YouTubeAccount.isYtId(id) && !ytTracks.containsKey(id)) { // songs not fetched yet
            loadYtTracks(id)
            toast(ctx, R.string.yt_playlist_loading)
            return
        }
        val pl = resolveList(ctx, id)
        var tracks = pl?.tracks.orEmpty().filter { !it.missing }
        if (shuffle) tracks = tracks.shuffled()
        val svc = service ?: MusicService.instance
        if (tracks.isEmpty() || svc == null) { toast(ctx, R.string.playlist_empty_tracks); return }
        MusicPrefs.setLastPlayedList(ctx, id)
        if (MusicPrefs.videoMode(ctx)) { playAsVideo(tracks, 0); return }
        svc.setQueueAndPlay(withVideosAsAudio(tracks), 0, fromPlaylist = if (id == PlaylistStore.LIKED_ID) getString(R.string.playlist_liked) else pl?.name)
    }

    /** Plays every song of the playlists in folder [folderId], optionally in random order. */
    private fun playFolder(folderId: String, shuffle: Boolean) {
        val ctx = context ?: return
        var tracks = PlaylistFolders.playlistIds(ctx, folderId)
            .flatMap { PlaylistStore.get(ctx, it)?.tracks.orEmpty() }
            .filter { !it.missing }.distinctBy { it.syncKey }
        if (shuffle) tracks = tracks.shuffled()
        val svc = service ?: MusicService.instance
        if (tracks.isEmpty() || svc == null) { toast(ctx, R.string.folder_no_songs); return }
        if (MusicPrefs.videoMode(ctx)) { playAsVideo(tracks, 0); return }
        svc.setQueueAndPlay(withVideosAsAudio(tracks), 0, fromPlaylist = PlaylistFolders.get(ctx, folderId)?.name)
    }

    /** Orders [list] by the chosen [sortMode]; the stored playlist itself is never reordered. */
    private fun sortTracks(list: List<Track>): List<Track> {
        val coll = java.text.Collator.getInstance(Locale.getDefault())
        val byText = { a: String, b: String -> coll.compare(a.trim(), b.trim()) }
        return when (sortMode) {
            SORT_NEWEST -> list.withIndex()
                .sortedWith(compareByDescending<IndexedValue<Track>> { it.value.addedAt }.thenByDescending { it.index })
                .map { it.value }
            SORT_OLDEST -> list.withIndex()
                .sortedWith(compareBy<IndexedValue<Track>> { it.value.addedAt }.thenBy { it.index })
                .map { it.value }
            SORT_TITLE_AZ -> list.sortedWith { a, b -> byText(a.title, b.title) }
            SORT_TITLE_ZA -> list.sortedWith { a, b -> byText(b.title, a.title) }
            SORT_ARTIST -> list.sortedWith { a, b ->
                byText(a.artist, b.artist).let { if (it != 0) it else byText(a.title, b.title) }
            }
            SORT_LONGEST -> list.sortedByDescending { it.durationMs }
            SORT_SHORTEST -> list.sortedBy { it.durationMs }
            else -> list
        }
    }

    // ---- filter button: overview = kinds of playlist, open list = source / length / artist of its songs

    private val songFilter = SongFilter()
    /** 0 = all; otherwise one of the F_* kinds below. */
    private var kindFilter = 0
    /** Order of the playlist overview (one of LSORT_*), remembered across launches. */
    private var listSort = SORT_DEFAULT

    /** True inside a user folder or a source folder (Device / Drive / YouTube / Spotify): no source filter there. */
    private fun inAnyFolder() = openFolder != null || openSource != null

    private fun showFilterDialog() {
        val ctx = context ?: return
        val id = openList
        if (id != null) {
            val tracks = resolveList(ctx, id)?.tracks.orEmpty()
            val sort = SortOption(
                listOf(
                    R.string.playlist_sort_default, R.string.playlist_sort_newest, R.string.playlist_sort_oldest,
                    R.string.playlist_sort_title_az, R.string.playlist_sort_title_za, R.string.playlist_sort_artist,
                    R.string.playlist_sort_longest, R.string.playlist_sort_shortest
                ),
                { sortMode },
                { which ->
                    sortMode = which
                    try {
                        ctx.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).edit().putInt(SORT_KEY, which).apply()
                    } catch (e: Exception) { android.util.Log.d("PlaylistFragment", "ignored: " + e) }
                }
            )
            FilterUi.showSongFilter(ctx, tracks, songFilter, sort, showSource = !inAnyFolder()) { if (_binding != null) refresh() }
            return
        }
        if (inAnyFolder()) {
            // Inside a folder every playlist already has the same source: only the order is offered.
            val folderSort = SortOption(
                listOf(
                    R.string.sort_default, R.string.sort_name_az, R.string.sort_name_za,
                    R.string.sort_most_songs, R.string.sort_fewest_songs
                ),
                { listSort },
                { which ->
                    listSort = which
                    try {
                        ctx.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).edit().putInt(LIST_SORT_KEY, which).apply()
                    } catch (e: Exception) { android.util.Log.d("PlaylistFragment", "ignored: " + e) }
                }
            )
            FilterUi.showSongFilter(ctx, emptyList(), SongFilter(), folderSort, kinds = null, showSource = false) {
                if (_binding != null) refresh()
            }
            return
        }
        val kinds = ArrayList<Int>().apply {
            add(0); add(F_LIKED); add(F_MINE)
            if (YouTubeAccount.isSignedIn(ctx)) add(F_YT)
            if (deviceFoldersAvailable(ctx)) add(F_DEVICE)
            if (DriveMusic.isSignedIn(ctx)) add(F_DRIVE)
        }
        val sort = SortOption(
            listOf(
                R.string.sort_default, R.string.sort_name_az, R.string.sort_name_za,
                R.string.sort_most_songs, R.string.sort_fewest_songs
            ),
            { listSort },
            { which ->
                listSort = which
                try {
                    ctx.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).edit().putInt(LIST_SORT_KEY, which).apply()
                } catch (e: Exception) { android.util.Log.d("PlaylistFragment", "ignored: " + e) }
            }
        )
        val kindOption = KindOption(
            kinds,
            kinds.map {
                when (it) {
                    F_LIKED -> R.string.playlist_liked
                    F_MINE -> R.string.filter_pl_mine
                    F_DEVICE -> R.string.source_local
                    F_YT -> R.string.source_youtube
                    F_DRIVE -> R.string.source_drive
                    else -> R.string.filter_all
                }
            },
            { kindFilter }, { kindFilter = it }
        )
        FilterUi.showSongFilter(ctx, emptyList(), SongFilter(), sort, kinds = kindOption) {
            if (_binding != null) refresh()
        }
    }

    /** Edit the name, description and cover picture of a (non-liked) list: the open one, or the one
     *  long-pressed on the overview ([listId]). Everything is applied together by Save. */
    private fun showEditDialog(listId: String? = openList) {
        val ctx = context ?: return
        val id = listId ?: return
        if (id == PlaylistStore.LIKED_ID) return
        val pl = PlaylistStore.get(ctx, id) ?: return
        val dp = ctx.resources.displayMetrics.density
        val v = android.view.LayoutInflater.from(ctx).inflate(R.layout.dialog_playlist_edit, null)
        val cover = v.findViewById<ImageView>(R.id.editCover)
        val removeBtn = v.findViewById<android.widget.TextView>(R.id.editCoverRemove)
        val nameInput = v.findViewById<EditText>(R.id.editName)
        val descInput = v.findViewById<EditText>(R.id.editDesc)
        nameInput.setText(pl.name)
        nameInput.setSelection(nameInput.text.length)
        descInput.setText(pl.description)

        var newCover: android.graphics.Bitmap? = null
        var removeCover = false
        cover.clipToOutline = true
        cover.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        fun showPreview() {
            val bmp = newCover ?: if (!removeCover && PlaylistStore.hasCover(ctx, id))
                try { android.graphics.BitmapFactory.decodeFile(PlaylistStore.coverFile(ctx, id).absolutePath) } catch (e: Exception) { null }
            else null
            if (bmp != null) { cover.setPadding(0, 0, 0, 0); cover.imageTintList = null; cover.setImageBitmap(bmp) }
            else {
                val p = (48 * dp).toInt()
                cover.setPadding(p, p, p, p)
                cover.imageTintList = ColorStateList.valueOf(attrColor(R.attr.colorTextSecondary))
                cover.setImageResource(R.drawable.ic_playlist)
            }
            removeBtn.visibility = if (bmp != null) android.view.View.VISIBLE else android.view.View.GONE
        }
        showPreview()
        onCoverPicked = { uri ->
            val bmp = decodeCover(ctx, uri)
            if (bmp == null) Toast.makeText(ctx, R.string.playlist_cover_failed, Toast.LENGTH_SHORT).show()
            else { newCover = bmp; removeCover = false; showPreview() }
        }
        val pick = android.view.View.OnClickListener { pickCover.launch("image/*") }
        cover.setOnClickListener(pick)
        v.findViewById<android.view.View>(R.id.editCoverBadge).setOnClickListener(pick)
        removeBtn.setOnClickListener { newCover = null; removeCover = true; showPreview() }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.playlist_edit)
            .setView(v)
            .setPositiveButton(R.string.radio_save) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty() && name != pl.name) PlaylistStore.rename(ctx, id, name)
                PlaylistStore.setDescription(ctx, id, descInput.text.toString())
                when {
                    newCover != null -> PlaylistStore.setCover(ctx, id, newCover)
                    removeCover -> PlaylistStore.setCover(ctx, id, null)
                }
                if (_binding != null) refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { onCoverPicked = null }
            .show()
    }

    /** Reads a gallery picture, center-crops it to a square and scales it to at most 600 px. */
    private fun decodeCover(ctx: Context, uri: Uri): android.graphics.Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 600 && bounds.outHeight / (sample * 2) >= 600) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        raw?.let {
            val side = minOf(it.width, it.height)
            val sq = android.graphics.Bitmap.createBitmap(it, (it.width - side) / 2, (it.height - side) / 2, side, side)
            if (side > 600) android.graphics.Bitmap.createScaledBitmap(sq, 600, 600, true) else sq
        }
    } catch (e: Exception) { null }

    private fun songCount(n: Int) = resources.getQuantityString(R.plurals.playlist_file_count, n, n)

    private fun refresh() {
        val b = _binding ?: return
        val ctx = context ?: return
        if (openList == null && openFolder != null && PlaylistFolders.get(ctx, openFolder!!) == null) openFolder = null // deleted meanwhile
        backCallback?.isEnabled = isResumed && (openList != null || openFolder != null || openSource != null || selecting)
        if (openList == null) { listAccent = null; applyCoverBg() }
        // Create (+) shows on the overview only; the three-dot menu on the overview and inside an opened list.
        // Inside a folder only (+) stays: it creates a playlist right there.
        // Inside one of the user's folders the (+) is replaced by the ⋮ menu (Create playlist / Select playlists / Rename folder).
        b.plAddButton.visibility = if (openSource != null || openFolder != null || openList != null) View.GONE else View.VISIBLE
        // Overview, inside a folder, and inside an opened playlist (its menu holds Move / Export / Share link / Shared playlist).
        b.plMoreButton.visibility = if (openSource != null) View.GONE else View.VISIBLE // no ⋮ in a source folder or in a playlist opened from one
        b.plPlayBtn.visibility = if (openList == null && (openFolder == null || openSource != null)) View.GONE else View.VISIBLE
        if (openList == null) { b.plPlayBtn.isEnabled = true; b.plPlayBtn.alpha = 1f }
        if (openList == null) b.plEditBtn.visibility = View.GONE // inside a list bindDetail decides
        b.plBackButton.visibility = if (openList == null && openFolder == null && openSource == null) View.GONE else View.VISIBLE
        // Full strength while a filter or a non-default order is active.
        FilterUi.setButtonState(
            b.plFilterButton,
            if (openList == null) (!inAnyFolder() && kindFilter != 0) || listSort != SORT_DEFAULT else songFilter.active || sortMode != SORT_DEFAULT
        )
        val q = b.plSearchInput.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val id = openList
        // Selection lives in an opened own list (songs), or on the main list / inside one of the user's folders (playlists and folders).
        if (if (id == null) openSource != null else isRemoteList(id)) exitSelection()

        if (id == null) {
            syncSelectionUi(null)
            b.plSearchInput.setHint(R.string.playlist_search_hint)
            if (b.plList.adapter !== rowAdapter) b.plList.adapter = rowAdapter
            val likedName = getString(R.string.playlist_liked)
            // Video mode: playlists imported from Spotify (audio only) are hidden everywhere on this page.
            val lists = PlaylistStore.playlists(ctx).let { all ->
                if (MusicPrefs.videoMode(ctx)) { val sp = SpotifyLists.ids(ctx); all.filter { it.id !in sp } } else all
            }
            val rows = mutableListOf<Row>()
            // "Generate playlist" lives in the "+" button; "Convert playlist" in the "⋮" menu.
            val want = { kind: Int -> inAnyFolder() || kindFilter == 0 || kindFilter == kind }
            val srcView = openSource
            // Device / Drive / YouTube playlists sit in their own folders on the main list; a search or a
            // kind filter lists everything flat instead, like the user's own folders.
            val grouped = q.isEmpty() && (kindFilter == 0 || inAnyFolder())
            if (openFolder == null && srcView == null && want(F_LIKED) && (q.isEmpty() || likedName.lowercase(Locale.getDefault()).contains(q))) {
                rows += Row(ROW_LIKED, PlaylistStore.LIKED_ID, likedName, songCount(PlaylistStore.liked(ctx).size))
            }
            val folderId = openFolder
            val pinSet = PlaylistPins.ids(ctx).toSet()
            // Folders sit on the main list only (they do not nest); a search also matches their names.
            if (folderId == null && srcView == null && want(F_MINE)) {
                val ownIds = lists.map { it.id }.toSet()
                PlaylistFolders.folders(ctx).filter { q.isEmpty() || it.name.lowercase(Locale.getDefault()).contains(q) }.forEach { f ->
                    val n = PlaylistFolders.playlistIds(ctx, f.id).count { it in ownIds }
                    rows += Row(ROW_FOLDER, f.id, f.name, resources.getString(R.string.playlist_overview_count, n), count = n)
                }
            }
            // The automatic "Spotify" folder (below YouTube) exists only in music mode, and only when the app is
            // connected to a Spotify account OR at least one playlist was imported from Spotify (which still exists).
            // Otherwise (video mode, or nothing from Spotify) there is no folder and no Spotify row; in video mode the
            // imported playlists are filtered out of [lists] above, so they do not show at all.
            val ownPlaylistIds = lists.map { it.id }.toSet()
            val importedFromSpotify = SpotifyLists.ids(ctx).any { it in ownPlaylistIds }
            val spotifyFolder = !MusicPrefs.videoMode(ctx) && (SpotifyAuth.isConnected(ctx) || importedFromSpotify)
            val spotifyIds = if (spotifyFolder) SpotifyLists.ids(ctx) else emptySet()
            // The Spotify folder was open and is gone now (e.g. switched to video mode): back to the main list.
            if (!spotifyFolder && openSource == SRC_SPOTIFY) { openSource = null; refresh(); return }
            // Playlists in a folder show only inside it (a pinned one also stays on top of the main list).
            // A search looks through every playlist, folder or not.
            lists.filter { pl ->
                val inFolder = PlaylistFolders.folderOf(ctx, pl.id)
                want(F_MINE) && (q.isEmpty() || pl.name.lowercase(Locale.getDefault()).contains(q)) &&
                    (if (folderId != null) inFolder == folderId else q.isNotEmpty() || inFolder == null || pl.id in pinSet)
            }
                .forEach {
                    // artUrl: fallback cover = artwork of the first song that has one
                    val art = it.tracks.firstOrNull { t -> t.artUrl.isNotBlank() }?.artUrl.orEmpty()
                    rows += Row(ROW_LIST, it.id, it.name, songCount(it.tracks.size), art, count = it.tracks.size,
                        src = if (it.id in spotifyIds) SRC_SPOTIFY else null,
                        coverStamp = PlaylistStore.coverFile(ctx, it.id).let { f -> if (f.exists()) f.lastModified() else 0L })
                }
            var ytCount = 0
            // Playlists of the signed-in YouTube account, read live (label "YouTube").
            if (openFolder == null && YouTubeAccount.isSignedIn(ctx)) {
                loadYtLists()
                ytLists.orEmpty().forEach {
                    ytCount++
                    if (want(F_YT) && (q.isEmpty() || it.title.lowercase(Locale.getDefault()).contains(q))) {
                        rows += Row(
                            ROW_YT, YouTubeAccount.ID_PREFIX + it.id, it.title,
                            "\u25B6 " + getString(R.string.source_youtube) + " \u00B7 " + songCount(it.count), it.thumb, count = it.count,
                            src = SRC_YT
                        )
                    }
                }
            }
            // Device / SD card / OTG folders that hold songs, one playlist per folder.
            if (openFolder == null && deviceFoldersAvailable(ctx)) {
                loadDeviceFolders()
                devFolders.orEmpty().forEach {
                    ytCount++
                    if (want(F_DEVICE) && (q.isEmpty() || it.name.lowercase(Locale.getDefault()).contains(q))) {
                        rows += Row(
                            ROW_DEVICE, DEVICE_PREFIX + it.id, it.name,
                            "\uD83D\uDCC1 " + getString(StorageKind.labelRes(it.kind)) + " \u00B7 " + songCount(it.tracks.size),
                            count = it.tracks.size, src = SRC_DEVICE
                        )
                    }
                }
            }
            // Drive folders that hold audio files, one playlist per folder (label "Drive").
            if (openFolder == null && DriveMusic.isSignedIn(ctx)) {
                loadDriveFolders()
                (if (drvVideo == MusicPrefs.videoMode(ctx)) drvFolders else null).orEmpty().forEach {
                    ytCount++
                    if (want(F_DRIVE) && (q.isEmpty() || it.name.lowercase(Locale.getDefault()).contains(q))) {
                        rows += Row(
                            ROW_DRIVE, DRIVE_PREFIX + it.id, it.name,
                            "\u2601 " + getString(R.string.source_drive) + " \u00B7 " + songCount(it.tracks.size),
                            count = it.tracks.size, src = SRC_DRIVE
                        )
                    }
                }
            }
            // "Liked songs" stays on top; the order setting applies to the playlists below it.
            // "Liked songs" is always on top (no pin needed), then the pinned rows (max 4, in pin order),
            // then the playlist played last, then the rest.
            val pinIds = PlaylistPins.ids(ctx)
            // Source folders: on the main list a source's playlists are hidden behind its folder row (unless pinned or
            // last played); inside a source folder only that source's playlists show.
            val allRows = rows.toList()
            if (srcView != null) rows.retainAll { it.src == srcView }
            else if (grouped && openFolder == null) {
                val lastPlayed = MusicPrefs.lastPlayedList(ctx)
                rows.removeAll { it.src != null && it.id !in pinIds && it.id != lastPlayed }
                fun srcRow(src: String, name: Int, show: Boolean) {
                    if (!show) return
                    val n = allRows.count { it.src == src }
                    rows += Row(ROW_SOURCE, SRC_PREFIX + src, getString(name), resources.getString(R.string.playlist_overview_count, n), count = n, src = null)
                }
                srcRow(SRC_DEVICE, R.string.source_local, deviceFoldersAvailable(ctx))
                srcRow(SRC_DRIVE, R.string.source_drive, DriveMusic.isSignedIn(ctx))
                srcRow(SRC_YT, R.string.source_youtube, YouTubeAccount.isSignedIn(ctx))
                srcRow(SRC_SPOTIFY, R.string.source_spotify, spotifyFolder) // right below the YouTube folder
            }
            val likedRows = rows.filter { it.kind == ROW_LIKED }
            val pinnedRows = pinIds.mapNotNull { pid -> rows.firstOrNull { it.id == pid && it.kind != ROW_LIKED } }
                .onEach { it.pinned = true }
            val lastId = MusicPrefs.lastPlayedList(ctx)
            val playing = rows.firstOrNull { it.id == lastId && it.kind != ROW_LIKED && it.id !in pinIds }
            val pinned = if (srcView != null) emptyList() else likedRows + pinnedRows + listOfNotNull(playing)
            val rest = rows.filter { srcView != null || (it.kind != ROW_LIKED && it.id !in pinIds && it !== playing) }
            val sourceRows = rest.filter { it.kind == ROW_SOURCE }
            val folderRows = rest.filter { it.kind == ROW_FOLDER }.sortedWith { a, b -> java.text.Collator.getInstance().compare(a.name.trim(), b.name.trim()) }
            val collator = java.text.Collator.getInstance()
            val others = rest.filter { it.kind != ROW_FOLDER && it.kind != ROW_SOURCE }
            // Default order: regular playlists newest first (own playlists are stored oldest first, so reverse them;
            // YouTube / Device / Drive rows keep their order after them), then the user's own folders, and the
            // automatic source folders at the very bottom.
            val ordered = when (listSort) {
                LSORT_NAME_AZ -> others.sortedWith { a, b -> collator.compare(a.name.trim(), b.name.trim()) }
                LSORT_NAME_ZA -> others.sortedWith { a, b -> collator.compare(b.name.trim(), a.name.trim()) }
                LSORT_MOST -> others.sortedByDescending { it.count }
                LSORT_FEWEST -> others.sortedBy { it.count }
                else -> others.filter { it.kind == ROW_LIST }.asReversed() + others.filter { it.kind != ROW_LIST }
            } + folderRows + sourceRows
            rowAdapter.submit(pinned + ordered)
            if (selecting) { selected.retainAll((pinned + ordered).filter { it.kind == ROW_LIST || it.kind == ROW_FOLDER }.map { it.id }.toSet()); syncSelectionUi(null) }
            shown = emptyList()
            if (srcView != null) {
                b.plTitle.text = getString(srcName(srcView))
                b.plCount.text = getString(R.string.playlist_overview_count, ordered.size)
                if (ordered.isEmpty() && q.isEmpty()) { showEmpty(R.string.source_folder_empty); return }
            } else if (folderId != null) {
                val inside = ordered.size + pinned.size
                b.plTitle.text = PlaylistFolders.get(ctx, folderId)?.name.orEmpty()
                b.plCount.text = getString(R.string.playlist_overview_count, inside)
                if (inside == 0 && q.isEmpty()) { exitSelection(); syncSelectionUi(null); showEmpty(R.string.folder_empty); return }
            } else {
                b.plTitle.text = getString(R.string.source_playlist)
                b.plCount.text = getString(R.string.playlist_overview_count, lists.size + ytCount)
            }
            showList()
            return
        }

        val isYt = YouTubeAccount.isYtId(id)
        if (isYt && ytLists == null) { // opened before the account's list arrived
            loadYtLists()
            if (ytLoading) { showEmpty(R.string.yt_playlist_loading); return }
        }
        if (id.startsWith(DRIVE_PREFIX) && (drvFolders == null || drvVideo != MusicPrefs.videoMode(ctx))) { // opened before the folders arrived
            loadDriveFolders()
            if (drvLoading) { showEmpty(R.string.drive_music_loading); return }
        }
        if (id.startsWith(DEVICE_PREFIX) && (devFolders == null || devVideo != MusicPrefs.videoMode(ctx))) { // opened before the folders were read
            loadDeviceFolders()
            if (devLoading) { showEmpty(R.string.device_folders_loading); return }
        }
        val pl = resolveList(ctx, id)
        if (pl == null) { // deleted meanwhile
            openList = null
            refresh()
            return
        }
        b.plSearchInput.setHint(R.string.music_search_hint)
        openAdapter?.let { if (b.plList.adapter !== it) { b.plList.adapter = it; b.plList.scrollToPosition(0) } }
        val name = if (id == PlaylistStore.LIKED_ID) getString(R.string.playlist_liked) else pl.name
        shown = sortTracks((if (q.isEmpty()) pl.tracks else pl.tracks.filter {
            it.title.lowercase(Locale.getDefault()).contains(q) ||
                it.artist.lowercase(Locale.getDefault()).contains(q) ||
                it.album.lowercase(Locale.getDefault()).contains(q)
        }).filter { songFilter.matches(it) })
        selected.retainAll(pl.tracks.map { it.syncKey }.toSet()) // songs removed elsewhere drop out
        if (selecting && selected.isEmpty() && pl.tracks.isEmpty()) exitSelection()
        syncSelectionUi(pl)
        trackAdapter.submit(shown)
        trackAdapter.setCurrentKey((service ?: MusicService.instance)?.current?.key.orEmpty())
        b.plTitle.text = name
        b.plCount.text = songCount(pl.tracks.size)
        bindDetail(pl)
        if (isYt && !ytTracks.containsKey(id) && id !in ytTracksFailed) loadYtTracks(id)
        val emptyText = if (shown.isEmpty()) getString(
            when {
                isYt && !ytTracks.containsKey(id) ->
                    if (id in ytTracksFailed) R.string.online_network_error else R.string.yt_playlist_loading
                pl.tracks.isNotEmpty() -> R.string.music_no_results
                id == PlaylistStore.LIKED_ID -> R.string.playlist_empty_liked
                else -> R.string.playlist_empty_tracks
            }
        ) else null
        // The cover row stays; the message sits under it instead of replacing the whole page.
        header?.plHeaderEmpty?.let { it.text = emptyText.orEmpty(); it.visibility = if (emptyText != null) View.VISIBLE else View.GONE }
        showList()
    }

    private fun showList() {
        binding.plEmptyPanel.visibility = View.GONE
        binding.plList.visibility = View.VISIBLE
    }

    private fun showEmpty(textRes: Int) {
        binding.plList.visibility = View.GONE
        binding.plEmptyPanel.visibility = View.VISIBLE
        binding.plEmptyText.setText(textRes)
    }

    override fun onMusicStateChanged() {
        if (_binding == null) return
        trackAdapter.setCurrentKey((service ?: MusicService.instance)?.current?.key.orEmpty())
    }

    /** Colour of the opened playlist's own cover (null on the overview / without a coloured cover). */
    private var listAccent: Int? = null

    /** Inside an opened playlist the page background takes its colour from that playlist's cover
     *  (Settings > Player > Colours from the cover); the overview keeps the plain background. */
    private fun applyCoverBg() {
        val b = _binding ?: return
        val ctx = context ?: return
        val accent = if (openList != null) listAccent else null
        if (accent == null) {
            b.root.setBackgroundColor(CoverColors.themeColor(ctx, R.attr.colorBg))
        } else {
            val top = CoverColors.pageBg(ctx, accent)
            val bottom = CoverColors.wash(CoverColors.themeColor(ctx, R.attr.colorBg), accent, if (CoverColors.isNight(ctx)) 0.1f else 0.07f)
            b.root.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
            )
        }
    }

    // ------------------------------------------------------------------ actions

    private fun open(id: String) {
        if (openList != id) { songFilter.reset(); listAccent = null; exitSelection() }
        openList = id
        if (binding.plSearchInput.text.isNullOrEmpty()) refresh() else binding.plSearchInput.setText("")
    }

    private fun onRowClicked(row: Row) {
        if (selecting && openList == null) { if (row.kind == ROW_LIST || row.kind == ROW_FOLDER) toggleSelectedList(row.id); return } // picking playlists / folders
        if (row.kind == ROW_GENERATE) { showGenerateDialog(); return }
        if (row.kind == ROW_CONVERT) { showConvertDialog(); return }
        if (row.kind == ROW_FOLDER) {
            openFolder = row.id
            if (binding.plSearchInput.text.isNullOrEmpty()) refresh() else binding.plSearchInput.setText("")
            return
        }
        if (row.kind == ROW_SOURCE) {
            openSource = row.id.removePrefix(SRC_PREFIX)
            if (binding.plSearchInput.text.isNullOrEmpty()) refresh() else binding.plSearchInput.setText("")
            return
        }
        open(row.id)
    }

    /** Tap on "Generate playlist": type what you want ("lagu santai buat belajar") and the AI builds it,
     *  or skip the text and build from your own taste as before. */
    private fun showGenerateDialog() {
        if (generating) return
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            hint = ctx.getString(R.string.playlist_ai_hint)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val dp = ctx.resources.displayMetrics.density
        val tv = android.util.TypedValue()
        fun attr(a: Int): Int { ctx.theme.resolveAttribute(a, tv, true); return tv.data }
        // Compact content: one input, then two pill buttons side by side (Cancel is the dialog's own).
        input.apply {
            setBackgroundResource(R.drawable.input_bg)
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            textSize = 14f
            setTextColor(attr(R.attr.colorTextPrimary))
            setHintTextColor(attr(R.attr.colorTextSecondary))
        }
        fun pill(textRes: Int, primary: Boolean) = TextView(ctx).apply {
            setText(textRes)
            gravity = android.view.Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setBackgroundResource(R.drawable.chip_bg)
            isSelected = primary // accent fill for the main action
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.chip_text_color))
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            isClickable = true
            isFocusable = true
        }
        val createBtn = pill(R.string.playlist_ai_create, true)
        val tasteBtn = pill(R.string.playlist_ai_from_taste, false)
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(createBtn, android.widget.LinearLayout.LayoutParams(0, (42 * dp).toInt(), 1f).apply { marginEnd = (4 * dp).toInt() })
            addView(tasteBtn, android.widget.LinearLayout.LayoutParams(0, (42 * dp).toInt(), 1f).apply { marginStart = (4 * dp).toInt() })
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(input, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt() })
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.playlist_ai_title)
            .setMessage(R.string.playlist_ai_message)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun submit() {
            val wish = input.text.toString().trim()
            if (wish.isEmpty()) {
                input.error = ctx.getString(R.string.ai_err_empty_prompt)
            } else if (!AiRecommender.hasKey(ctx)) {
                Toast.makeText(ctx, R.string.ai_err_no_key, Toast.LENGTH_LONG).show()
            } else {
                dialog.dismiss()
                generateFromPrompt(wish)
            }
        }
        createBtn.setOnClickListener { submit() }
        tasteBtn.setOnClickListener { dialog.dismiss(); generatePlaylist() }
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dialog.show()
    }

    /** Asks the AI for a playlist matching [wish], saves it at once as a normal playlist and opens it.
     *  Saved even if the page is gone by then. */
    private fun generateFromPrompt(wish: String) {
        if (generating) return
        val app = requireContext().applicationContext
        generating = true
        Toast.makeText(app, R.string.playlist_generating, Toast.LENGTH_SHORT).show()
        refresh()
        Thread {
            var pl: Playlist? = null
            var error: String? = null
            try {
                val ai = AiRecommender.fromPrompt(app, wish)
                pl = PlaylistStore.createWith(app, ai.name, ai.tracks)
            } catch (e: AiRecommender.AiException) {
                error = if (e.detail.isNotEmpty()) app.getString(e.resId, e.detail) else app.getString(e.resId)
            } catch (e: Throwable) {
                error = app.getString(R.string.ai_err_generic) + " (" + (e.message ?: e.javaClass.simpleName) + ")"
            }
            val saved = pl
            val errText = error
            mainHandler.post {
                generating = false
                if (saved == null) {
                    Toast.makeText(app, errText ?: app.getString(R.string.ai_err_generic), Toast.LENGTH_LONG).show()
                    if (_binding != null) refresh()
                } else {
                    Toast.makeText(
                        app, app.getString(R.string.playlist_generated_done, saved.tracks.size), Toast.LENGTH_SHORT
                    ).show()
                    if (_binding != null) open(saved.id) else openList = saved.id
                }
            }
        }.start()
    }

    /** Builds a [PlaylistGenerator.SIZE]-song playlist from the user's taste in the background,
     *  saves it as a normal playlist and opens it. Saved even if the page is gone by then. */
    private fun generatePlaylist() {
        if (generating) return
        val app = requireContext().applicationContext
        generating = true
        Toast.makeText(app, R.string.playlist_generating, Toast.LENGTH_SHORT).show()
        refresh()
        Thread {
            val result = try { PlaylistGenerator.generate(app) } catch (e: Exception) { null }
            val pl = result?.tracks?.takeIf { it.isNotEmpty() }?.let {
                val name = app.getString(
                    R.string.playlist_generated_name,
                    java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date())
                )
                PlaylistStore.createWith(app, name, it)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                generating = false
                if (pl == null) {
                    android.widget.Toast.makeText(app, R.string.playlist_generate_empty, android.widget.Toast.LENGTH_LONG).show()
                    if (_binding != null) refresh()
                } else {
                    android.widget.Toast.makeText(
                        app, app.getString(R.string.playlist_generated_done, pl.tracks.size), android.widget.Toast.LENGTH_SHORT
                    ).show()
                    if (_binding != null) open(pl.id) else openList = pl.id
                }
            }
        }.start()
    }

    /** True while the playlist behind a pinned id still exists (lists not loaded yet count as existing). */
    private fun pinTargetAlive(ctx: Context, own: Set<String>, id: String): Boolean = when {
        id == PlaylistStore.LIKED_ID -> true
        YouTubeAccount.isYtId(id) -> ytLists?.any { YouTubeAccount.ID_PREFIX + it.id == id } ?: true
        id.startsWith(DRIVE_PREFIX) ->
            if (drvFolders == null || drvVideo != MusicPrefs.videoMode(ctx)) true
            else drvFolders!!.any { DRIVE_PREFIX + it.id == id }
        id.startsWith(DEVICE_PREFIX) ->
            if (devFolders == null || devVideo != MusicPrefs.videoMode(ctx)) true
            else devFolders!!.any { DEVICE_PREFIX + it.id == id }
        PlaylistFolders.get(ctx, id) != null -> true
        else -> id in own
    }

    /** Pins / unpins a row of the overview; at most [PlaylistPins.MAX] can be pinned. */
    private fun togglePin(row: Row) {
        val ctx = context ?: return
        val own = PlaylistStore.playlists(ctx).map { it.id }.toSet()
        PlaylistPins.retain(ctx) { pinTargetAlive(ctx, own, it) } // free slots of deleted playlists
        if (PlaylistPins.isPinned(ctx, row.id)) {
            PlaylistPins.unpin(ctx, row.id)
            toast(ctx, R.string.playlist_unpinned)
        } else if (PlaylistPins.pin(ctx, row.id)) {
            toast(ctx, R.string.playlist_pinned)
        } else {
            toast(ctx, R.string.playlist_pin_full)
        }
        if (_binding != null) refresh()
    }

    private fun onRowLongClicked(row: Row) {
        val ctx = requireContext()
        if (selecting && openList == null) { if (row.kind == ROW_LIST || row.kind == ROW_FOLDER) toggleSelectedList(row.id); return }
        if (row.kind == ROW_GENERATE || row.kind == ROW_CONVERT || row.kind == ROW_SOURCE) return
        if (row.kind == ROW_FOLDER) {
            val f = PlaylistFolders.get(ctx, row.id) ?: return
            val label = getString(if (PlaylistPins.isPinned(ctx, row.id)) R.string.playlist_unpin else R.string.playlist_pin)
            PlaylistUi.folderActions(ctx, f, { shuffle -> playFolder(f.id, shuffle) }, label, { togglePin(row) }, onSelect = { startListSelection(row.id) }) {
                if (_binding != null) refresh()
            }
            return
        }
        val pinLabel = getString(if (PlaylistPins.isPinned(ctx, row.id)) R.string.playlist_unpin else R.string.playlist_pin)
        if (row.kind == ROW_YT || row.kind == ROW_DRIVE || row.kind == ROW_DEVICE) {
            PlaylistUi.pinOnly(ctx, row.name, pinLabel, { shuffle -> playRow(row.id, shuffle) }) { togglePin(row) }
            return
        }
        val pl = PlaylistStore.get(ctx, row.id) ?: return
        if (row.kind == ROW_LIKED) {
            PlaylistUi.exportActions(ctx, getString(R.string.playlist_liked), { exportM3u(pl) }, { shareLink(pl) }, onPlay = { shuffle -> playRow(row.id, shuffle) })
            return
        }
        PlaylistUi.playlistActions(
            ctx, pl, { showEditDialog(it.id) }, { exportM3u(it) }, { shareLink(it) },
            pinLabel, { togglePin(row) },
            onCollab = { // Shared playlist / Blend: long-press only
                if (PlaylistBlend.isBlend(ctx, it.id)) PlaylistBlendUi.manage(ctx, it) { if (_binding != null) refresh() }
                else CollabUi.manage(ctx, it) { if (_binding != null) refresh() }
            },
            onPlay = { shuffle -> playRow(row.id, shuffle) },
            onSelect = { startListSelection(row.id) }
        ) { if (_binding != null) refresh() }
    }

    private fun onTrackClicked(position: Int) {
        if (position !in shown.indices) return
        if (selecting) { toggleSelected(shown[position]); return }
        openList?.let { MusicPrefs.setLastPlayedList(requireContext(), it) } // pinned right below Liked songs
        if (MusicPrefs.videoMode(requireContext())) { playAsVideo(shown, position, tappedByUser = true); return }
        val ctx = requireContext()
        val from = openList?.let { resolveList(ctx, it)?.name }
        // Music mode: a video of the list plays as audio too (its audio twin); the video button of the player brings the picture back.
        (service ?: MusicService.instance)?.setQueueAndPlay(withVideosAsAudio(shown), position, fromPlaylist = from)
    }

    /** Video files (device / Drive) never enter the audio queue as such: each one goes in as its audio twin, so every video plays in music mode. Same order and size. */
    private fun withVideosAsAudio(list: List<Track>): List<Track> =
        if (list.any { it.isVideoFile }) list.map { if (it.isVideoFile) PlayerMode.videoToAudio(it) else it } else list

    /**
     * Video mode: the playlist plays as a video queue. Songs without a video (device songs, radio, ...) are skipped;
     * the tapped one must have one, else the first that does is used.
     */
    private fun playAsVideo(list: List<Track>, position: Int, tappedByUser: Boolean = false) {
        val ctx = context ?: return
        // Any song without a video (device song, radio, Spotify, ...) plays as audio even in video mode: the tapped one,
        // or the first one when nothing in the list has a video.
        val tapped0 = list.getOrNull(position)
        val audioTapped = tappedByUser && tapped0 != null && !tapped0.isVideoPlayable && !tapped0.isVideoFile && !tapped0.missing
        val noVideo = list.none { it.isVideoPlayable && !it.missing }
        val firstAudio = list.indexOfFirst { !it.isVideoFile && !it.missing }
        if (audioTapped || (noVideo && firstAudio >= 0)) {
            playAsAudio(ctx, list, if (audioTapped) position else firstAudio)
            return
        }
        val playable = list.filter { it.isVideoPlayable && !it.missing }
        if (playable.isEmpty()) {
            Toast.makeText(ctx, R.string.video_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        val tapped = list.getOrNull(position)
        val i = tapped?.let { t -> playable.indexOfFirst { it.source == t.source && it.remoteId == t.remoteId } }?.takeIf { it >= 0 } ?: 0
        val skipped = list.size - playable.size
        if (skipped > 0) Toast.makeText(ctx, getString(R.string.video_skipped, skipped), Toast.LENGTH_SHORT).show()
        (service ?: MusicService.instance)?.let { if (it.isPlaying) it.pause() }
        startActivity(VideoPlayerActivity.queueIntent(ctx, playable, i, 0L))
    }

    /** Video mode: the list goes to the music service (video files are not audio, so they stay out) and starts at the song [position]. */
    private fun playAsAudio(ctx: android.content.Context, list: List<Track>, position: Int) {
        val start = list.getOrNull(position) ?: return
        val audio = list.filter { !it.isVideoFile && !it.missing }
        val i = audio.indexOfFirst { it.key == start.key }.coerceAtLeast(0)
        val from = openList?.let { resolveList(ctx, it)?.name }
        (service ?: MusicService.instance)?.setQueueAndPlay(audio, i, fromPlaylist = from)
    }

    private fun onTrackLongClicked(position: Int) {
        if (position !in shown.indices) return
        val t = shown[position]
        if (selecting) { toggleSelected(t); return }
        val editable = openList != null && !isRemoteList(openList)
        PlaylistUi.trackActions(
            requireContext(), t, openList.takeUnless { isRemoteList(it) },
            onSelect = if (editable) ({ startSelection(t) }) else null,
            onMove = if (editable) ({ moveTrack(t) }) else null,
            onCopy = if (editable) ({ copyTrack(t) }) else null
        ) { if (_binding != null) refresh() }
    }

    /** Adds one song of the open playlist to another playlist; it stays in this one too. */
    private fun copyTrack(t: Track) {
        val ctx = context ?: return
        val id = openList ?: return
        PlaylistUi.pickPlaylist(ctx, getString(R.string.track_copy_title, t.title), id) { target ->
            val added = PlaylistStore.addTracks(ctx, target.id, listOf(t)) > 0
            toast(ctx, getString(if (added) R.string.playlist_added else R.string.playlist_already, target.name))
            if (_binding != null) refresh()
        }
    }

    /** Moves one song of the open playlist to another playlist (the multi-select "Move" does the same for several). */
    private fun moveTrack(t: Track) {
        val ctx = context ?: return
        val id = openList ?: return
        PlaylistUi.pickPlaylist(ctx, getString(R.string.track_move_title, t.title), id) { target ->
            PlaylistStore.addTracks(ctx, target.id, listOf(t))
            PlaylistStore.removeTracks(ctx, id, listOf(t))
            Toast.makeText(ctx, getString(R.string.track_moved, t.title, target.name), Toast.LENGTH_SHORT).show()
            if (_binding != null) refresh()
        }
    }

    // ------------------------------------------------------------------ multi-select: remove / move several songs

    private fun startSelection(first: Track?) {
        val id = openList ?: return
        if (isRemoteList(id)) return
        selecting = true
        selected.clear()
        first?.let { selected += it.syncKey }
        refresh()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
    }

    private fun toggleSelected(t: Track) {
        if (!selected.remove(t.syncKey)) selected += t.syncKey
        syncSelectionUi(openList?.let { resolveList(requireContext(), it) })
    }

    /** Selects every song currently shown (respects search / filter); a second tap clears the selection. */
    private fun toggleSelectAll() {
        if (openList == null) { // playlists of the open folder
            val ids = rowAdapter.selectableIds()
            if (ids.isNotEmpty() && selected.containsAll(ids)) selected.removeAll(ids.toSet()) else selected.addAll(ids)
            syncSelectionUi(null)
            return
        }
        val keys = shown.map { it.syncKey }
        if (keys.isNotEmpty() && selected.containsAll(keys)) selected.removeAll(keys.toSet()) else selected.addAll(keys)
        syncSelectionUi(openList?.let { resolveList(requireContext(), it) })
    }

    /** Header row <-> selection bar, check boxes on the rows, "N selected". */
    private fun syncSelectionUi(pl: Playlist?) {
        val b = _binding ?: return
        val canSelect = pl != null && !isRemoteList(pl.id) && pl.tracks.isNotEmpty()
        b.plHeaderRow.visibility = if (selecting) View.GONE else View.VISIBLE
        b.plSelectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        b.plSelectBtn.visibility = View.GONE // Select songs lives in the ⋮ menu now (showMoreMenu)
        b.plSelCount.text = getString(R.string.select_count, selected.size)
        val any = selected.isNotEmpty()
        for (v in listOf(b.plSelAdd, b.plSelMove, b.plSelDelete)) { v.isEnabled = any; v.alpha = if (any) 1f else 0.4f }
        if (openList == null) { // picking playlists inside a folder: all / move / delete only
            b.plSelAdd.visibility = View.GONE
            trackAdapter.setSelection(false, emptySet())
            rowAdapter.setSelection(selecting, selected.toSet())
        } else {
            b.plSelAdd.visibility = View.VISIBLE
            rowAdapter.setSelection(false, emptySet())
            trackAdapter.setSelection(selecting, selected.toSet())
        }
    }

    // ------------------------------------------------------------------ multi-select inside a folder: move / delete several playlists

    /** [first] = the playlist / folder that was long-pressed: it starts out ticked, like a long-pressed song. */
    private fun startListSelection(first: String? = null) {
        if (openList != null || openSource != null) return // main list or one of the user's folders
        selecting = true
        selected.clear()
        first?.let { selected += it }
        refresh()
    }

    private fun toggleSelectedList(id: String) {
        if (!selected.remove(id)) selected += id
        syncSelectionUi(null)
    }

    /** Ids of the selected user folders (the main list can select folders too); the rest of [selected] are playlists. */
    private fun selectedFolderIds(ctx: Context): Set<String> {
        val all = PlaylistFolders.folders(ctx).map { it.id }.toSet()
        return selected.filter { it in all }.toSet()
    }

    /** Moves the selected playlists into a folder. Folders do not nest, so selected folders are left out. */
    private fun moveSelectedLists() {
        val ctx = context ?: return
        val folderIds = selectedFolderIds(ctx)
        val ids = selected.filter { it !in folderIds }
        if (ids.isEmpty()) {
            Toast.makeText(ctx, if (folderIds.isEmpty()) R.string.select_pick_one_playlist else R.string.select_folders_cannot_move, Toast.LENGTH_SHORT).show()
            return
        }
        PlaylistUi.moveManyToFolder(ctx, ids, openFolder) { // openFolder = null on the main list: no folder is hidden from the choices
            exitSelection()
            if (_binding != null) refresh()
        }
    }

    /** Deletes the selected playlists and folders. A deleted folder keeps its playlists (they go back to the main list). */
    private fun deleteSelectedLists() {
        val ctx = context ?: return
        if (selected.isEmpty()) { Toast.makeText(ctx, R.string.select_pick_one_playlist, Toast.LENGTH_SHORT).show(); return }
        val folderIds = selectedFolderIds(ctx).toList()
        val listIds = selected.filter { it !in folderIds }
        val message = when {
            folderIds.isEmpty() -> getString(R.string.select_playlists_delete_confirm, listIds.size)
            listIds.isEmpty() -> getString(R.string.select_folders_delete_confirm, folderIds.size)
            else -> getString(R.string.select_mixed_delete_confirm, listIds.size, folderIds.size)
        }
        AlertDialog.Builder(ctx)
            .setMessage(message)
            .setPositiveButton(R.string.playlist_delete) { _, _ ->
                listIds.forEach { PlaylistStore.delete(ctx, it) }
                folderIds.forEach { PlaylistPins.unpin(ctx, it); PlaylistFolders.delete(ctx, it) } // playlists inside stay
                Toast.makeText(ctx, when {
                    folderIds.isEmpty() -> getString(R.string.select_playlists_deleted, listIds.size)
                    listIds.isEmpty() -> getString(R.string.select_folders_deleted, folderIds.size)
                    else -> getString(R.string.select_mixed_deleted, listIds.size, folderIds.size)
                }, Toast.LENGTH_SHORT).show()
                exitSelection()
                if (_binding != null) refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameOpenFolder() {
        val ctx = context ?: return
        val f = openFolder?.let { PlaylistFolders.get(ctx, it) } ?: return
        PlaylistUi.promptNameText(ctx, getString(R.string.folder_rename), f.name, R.string.folder_name_hint) { name ->
            PlaylistFolders.rename(ctx, f.id, name)
            if (_binding != null) refresh()
        }
    }

    private fun selectedTracks(): List<Track> {
        val id = openList ?: return emptyList()
        return resolveList(requireContext(), id)?.tracks.orEmpty().filter { it.syncKey in selected }
    }

    private fun removeSelected() {
        val ctx = context ?: return
        val id = openList ?: return
        val tracks = selectedTracks()
        if (tracks.isEmpty()) { Toast.makeText(ctx, R.string.select_pick_one, Toast.LENGTH_SHORT).show(); return }
        val name = if (id == PlaylistStore.LIKED_ID) getString(R.string.playlist_liked) else resolveList(ctx, id)?.name.orEmpty()
        AlertDialog.Builder(ctx)
            .setMessage(getString(R.string.select_remove_confirm, tracks.size, name))
            .setPositiveButton(R.string.select_remove) { _, _ ->
                PlaylistStore.removeTracks(ctx, id, tracks)
                Toast.makeText(ctx, getString(R.string.select_removed, tracks.size), Toast.LENGTH_SHORT).show()
                exitSelection()
                if (_binding != null) refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Adds the picked songs to another playlist; they stay in this one too. */
    private fun addSelected() {
        val ctx = context ?: return
        val id = openList ?: return
        val tracks = selectedTracks()
        if (tracks.isEmpty()) { Toast.makeText(ctx, R.string.select_pick_one, Toast.LENGTH_SHORT).show(); return }
        PlaylistUi.pickPlaylist(ctx, getString(R.string.select_add_title, tracks.size), id) { target ->
            val added = PlaylistStore.addTracks(ctx, target.id, tracks)
            Toast.makeText(ctx, getString(R.string.select_added, added, target.name), Toast.LENGTH_SHORT).show()
            exitSelection()
            if (_binding != null) refresh()
        }
    }

    /** Moves the picked songs to another playlist: added there first, then taken out of this one. */
    private fun moveSelected() {
        val ctx = context ?: return
        val id = openList ?: return
        val tracks = selectedTracks()
        if (tracks.isEmpty()) { Toast.makeText(ctx, R.string.select_pick_one, Toast.LENGTH_SHORT).show(); return }
        PlaylistUi.pickPlaylist(ctx, getString(R.string.select_move_title, tracks.size), id) { target ->
            PlaylistStore.addTracks(ctx, target.id, tracks)
            PlaylistStore.removeTracks(ctx, id, tracks)
            Toast.makeText(ctx, getString(R.string.select_moved, tracks.size, target.name), Toast.LENGTH_SHORT).show()
            exitSelection()
            if (_binding != null) refresh()
        }
    }

    // ------------------------------------------------------------------ import / export

    private fun toast(app: Context, text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    private fun toast(app: Context, resId: Int) = Toast.makeText(app, resId, Toast.LENGTH_LONG).show()

    private fun displayName(app: Context, pl: Playlist) =
        if (pl.id == PlaylistStore.LIKED_ID) app.getString(R.string.playlist_liked) else pl.name

    /** The "⋮" menu: convert / import / backup on the overview, export / share while a list is open. */
    private fun showMoreMenu() {
        val ctx = requireContext()
        val id = openList
        if (id != null) {
            val pl = PlaylistStore.get(ctx, id) ?: return
            val liked = id == PlaylistStore.LIKED_ID
            val remote = isRemoteList(id)
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            fun item(label: String, action: () -> Unit) { labels += label; actions += action }
            // Shared playlist sits at the top, above Select songs (same dialog as the long-press on the overview).
            if (!liked && !remote && PlaylistBlend.isBlend(ctx, id)) item(getString(R.string.blend_menu_shared)) {
                PlaylistBlendUi.manage(ctx, pl) { if (_binding != null) refresh() }
            }
            if (!liked && !remote && !PlaylistBlend.isBlend(ctx, id)) item(getString(R.string.collab_menu)) {
                CollabUi.manage(ctx, pl) { if (_binding != null) refresh() }
            }
            // Select songs and Edit used to be header buttons; they live in this menu now.
            if (!remote && (resolveList(ctx, id)?.tracks?.isNotEmpty() == true)) item(getString(R.string.select_songs)) { startSelection(null) }
            if (!liked && !remote) item(getString(R.string.playlist_edit)) { showEditDialog() }
            if (!liked && !remote) item(getString(R.string.folder_move)) {
                PlaylistUi.moveToFolder(ctx, pl) {
                    // Back now returns to the folder the playlist went into (or the main list).
                    openFolder = PlaylistFolders.folderOf(ctx, pl.id)
                    if (_binding != null) refresh()
                }
            }
            item(getString(R.string.playlist_menu_export_m3u)) { exportM3u(pl) }
            item(getString(R.string.playlist_menu_share_link)) { shareLink(pl) }
            AlertDialog.Builder(ctx)
                .setTitle(displayName(ctx, pl))
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .show()
            return
        }
        val folderId = openFolder
        if (folderId != null) { // inside one of the user's folders: Create playlist / Select playlists / Rename folder
            val labels = arrayOf(
                getString(R.string.folder_add_playlist),
                getString(R.string.folder_select_playlists),
                getString(R.string.folder_rename)
            )
            AlertDialog.Builder(ctx)
                .setTitle(PlaylistFolders.get(ctx, folderId)?.name.orEmpty())
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> showAddMenu()
                        1 -> if (rowAdapter.selectableIds().isEmpty()) toast(ctx, R.string.folder_empty) else startListSelection()
                        else -> renameOpenFolder()
                    }
                }
                .show()
            return
        }
        val labels = arrayOf(
            getString(R.string.playlist_select_items),
            getString(R.string.convert_title),
            getString(R.string.playlist_menu_import_link),
            getString(R.string.playlist_menu_import_file),
            getString(R.string.playlist_menu_backup)
        )
        AlertDialog.Builder(ctx)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> if (rowAdapter.selectableIds().isEmpty()) toast(ctx, R.string.playlist_select_none) else startListSelection()
                    1 -> showConvertDialog()
                    2 -> promptImportLink()
                    3 -> openFile.launch(arrayOf("*/*"))
                    else -> createBackup.launch(PlaylistIo.backupFileName())
                }
            }
            .show()
    }

    /** The "+" button (and "Add playlist" in a folder's ⋮ menu): 1) create a playlist yourself, 2) have one generated (what used to be in the "⋮" menu). */
    private fun showAddMenu() {
        val ctx = requireContext()
        val folderId = openFolder
        if (folderId != null) { // inside a folder: a new playlist goes straight into it
            PlaylistUi.createPlaylist(ctx) { pl ->
                PlaylistFolders.move(ctx, pl.id, folderId)
                if (_binding != null) open(pl.id)
            }
            return
        }
        val labels = arrayOf(
            getString(R.string.playlist_create),
            getString(R.string.collab_create_menu),
            getString(R.string.blend_create_menu),
            getString(R.string.playlist_generate) + if (generating) " \u2026" else "",
            getString(R.string.folder_new),
            getString(R.string.collab_join_menu),
            getString(R.string.blend_join_menu)
        )
        AlertDialog.Builder(ctx)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> PlaylistUi.createPlaylist(ctx) { pl -> if (_binding != null) open(pl.id) }
                    1 -> PlaylistUi.createPlaylist(ctx) { pl -> // new playlist, made shared right away (code + share dialog)
                        if (_binding != null) open(pl.id)
                        CollabUi.manage(ctx, pl) { if (_binding != null) refresh() }
                    }
                    2 -> PlaylistBlendUi.create(ctx) { id -> if (_binding != null) { refresh(); open(id) } else openList = id }
                    3 -> if (generating) toast(ctx, R.string.playlist_generating) else showGenerateDialog()
                    4 -> PlaylistUi.createFolder(ctx) { if (_binding != null) refresh() }
                    5 -> CollabUi.promptJoin(ctx) { id -> if (_binding != null) open(id) }
                    else -> PlaylistBlendUi.promptJoin(ctx) { id -> if (_binding != null) { refresh(); open(id) } else openList = id }
                }
            }
            .show()
    }

    private fun exportM3u(pl: Playlist) {
        exportId = pl.id
        createM3u.launch(PlaylistIo.safeFileName(displayName(requireContext(), pl)) + ".m3u")
    }

    private fun writeM3u(uri: Uri) {
        val app = context?.applicationContext ?: return
        val pl = exportId?.let { PlaylistStore.get(app, it) } ?: return
        val name = displayName(app, pl)
        Thread {
            val ok = try {
                PlaylistIo.writeText(app, uri, PlaylistIo.toM3u(app, name, pl.tracks))
                true
            } catch (e: Exception) {
                false
            }
            mainHandler.post {
                if (ok) toast(app, app.getString(R.string.playlist_export_done, name))
                else toast(app, R.string.playlist_export_failed)
            }
        }.start()
    }

    private fun writeBackup(uri: Uri) {
        val app = context?.applicationContext ?: return
        Thread {
            val ok = try {
                PlaylistIo.writeText(app, uri, PlaylistIo.backupJson(app))
                true
            } catch (e: Exception) {
                false
            }
            mainHandler.post {
                if (ok) toast(app, R.string.playlist_backup_done) else toast(app, R.string.playlist_export_failed)
            }
        }.start()
    }

    private fun shareLink(pl: Playlist) {
        val ctx = requireContext()
        val name = displayName(ctx, pl)
        val share = PlaylistIo.buildShareLink(name, pl.tracks)
        if (share.shared == 0) {
            toast(ctx, R.string.playlist_share_none)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, getString(R.string.playlist_share_text, name, share.shared, share.link))
        }
        startActivity(CopyLink.withCopyTarget(ctx, Intent.createChooser(send, getString(R.string.playlist_share_chooser)), share.link))
        if (share.omitted > 0) toast(ctx, ctx.getString(R.string.playlist_share_partial, share.omitted))
    }

    private fun promptImportLink() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            hint = ctx.getString(R.string.playlist_import_link_hint)
            // A link already on the clipboard is the usual case: save the paste.
            val clip = try {
                ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            } catch (e: Exception) {
                ""
            }
            if (PlaylistIo.recognizes(clip) || SpotifyEmbed.idFrom(clip) != null) setText(clip.trim())
        }
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val box = FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.playlist_menu_import_link)
            .setMessage(R.string.playlist_import_link_help)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> importFromText(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show() // one field for every link (YouTube, YouTube Music, Spotify, StarrySound); no separate Spotify button
    }

    /** Imports from pasted text: a StarrySound share link or a YouTube / YouTube Music playlist link. */
    fun importFromText(text: String) {
        val ctx = context ?: return
        if (PlaylistIo.isShareLink(text)) {
            val imp = PlaylistIo.decodeLink(text)
            if (imp == null) toast(ctx, R.string.playlist_import_bad_link) else confirmImport(imp)
            return
        }
        if (SpotifyEmbed.idFrom(text) != null) { // a Spotify playlist link: songs are matched to YouTube
            activity?.let { act -> SpotifyImportUi.importLink(act, text) { if (_binding != null) refresh() } }
            return
        }
        val listId = PlaylistIo.youtubePlaylistId(text)
        if (listId == null) {
            toast(ctx, R.string.playlist_import_bad_link)
            return
        }
        fetchYouTube(listId)
    }

    private fun fetchYouTube(listId: String) {
        val ctx = requireContext()
        val app = ctx.applicationContext
        val dialog = AlertDialog.Builder(ctx)
            .setMessage(R.string.playlist_import_fetching)
            .setCancelable(false)
            .create()
        dialog.show()
        Thread {
            var imp: PlaylistIo.Imported? = null
            var errorRes = 0
            try {
                imp = PlaylistIo.importYouTubePlaylist(listId)
            } catch (e: PlaylistIo.ImportException) {
                errorRes = e.resId
            } catch (e: Exception) {
                errorRes = R.string.playlist_import_err_failed
            }
            mainHandler.post {
                try { dialog.dismiss() } catch (e: Exception) { /* window already gone */ }
                val result = imp
                when {
                    errorRes != 0 -> toast(app, errorRes)
                    result == null || result.tracks.isEmpty() -> toast(app, R.string.playlist_import_empty)
                    _binding != null && isAdded -> confirmImport(result)
                    else -> saveImported(app, result, result.name.ifBlank { app.getString(R.string.playlist_import_default_name) })
                }
            }
        }.start()
    }

    private fun importFile(uri: Uri) {
        val app = context?.applicationContext ?: return
        Thread {
            var imp: PlaylistIo.Imported? = null
            var restored = false
            var errorRes = 0
            try {
                val text = PlaylistIo.readText(app, uri)
                val backup = PlaylistIo.parseBackup(text)
                if (backup != null) {
                    PlaylistStore.importJson(app, backup, merge = true, fromDrive = false)
                    DriveSync.markDirty(app) // importJson alone never triggers an upload
                    restored = true
                } else {
                    imp = PlaylistIo.parseM3u(text, PlaylistIo.fileTitle(app, uri))
                }
            } catch (e: Exception) {
                errorRes = R.string.playlist_import_err_file
            }
            mainHandler.post {
                val result = imp
                when {
                    errorRes != 0 -> toast(app, errorRes)
                    restored -> {
                        toast(app, R.string.playlist_backup_restored)
                        if (_binding != null) refresh()
                    }
                    result == null || result.tracks.isEmpty() -> toast(app, R.string.playlist_import_empty)
                    _binding != null && isAdded -> confirmImport(result)
                    else -> saveImported(app, result, result.name.ifBlank { app.getString(R.string.playlist_import_default_name) })
                }
            }
        }.start()
    }

    /** Lets the user name the imported list before it is saved. */
    private fun confirmImport(imp: PlaylistIo.Imported) {
        val ctx = context ?: return
        val title = getString(R.string.playlist_import_name_title, imp.tracks.size)
        val initial = imp.name.ifBlank { getString(R.string.playlist_import_default_name) }
        PlaylistUi.promptNameText(ctx, title, initial) { name ->
            val pl = saveImported(ctx.applicationContext, imp, name)
            if (_binding != null) open(pl.id)
        }
    }

    private fun saveImported(app: Context, imp: PlaylistIo.Imported, name: String): Playlist {
        val pl = PlaylistStore.createWith(app, name, imp.tracks)
        toast(
            app,
            if (imp.skipped > 0) app.getString(R.string.playlist_import_done_skipped, pl.tracks.size, imp.skipped)
            else app.getString(R.string.playlist_import_done, pl.tracks.size)
        )
        return pl
    }

    /** A link opened from outside (MainActivity hands it over through [pendingImport]). */
    fun consumePendingImport() {
        if (_binding == null || !isResumed) return
        val text = pendingImport ?: return
        pendingImport = null
        importFromText(text)
    }

    /** A list another page asked to show (Home's "Liked songs" tile, via [pendingOpen]): opens it. */
    fun consumePendingOpen() {
        if (_binding == null || !isResumed) return
        val id = pendingOpen ?: return
        pendingOpen = null
        if (PlaylistStore.get(requireContext(), id) != null) open(id)
    }

    // ------------------------------------------------------------------ overview rows

    private class Row(
        val kind: Int, val id: String, val name: String, val subtitle: String, val artUrl: String = "",
        /** Number of songs, for sorting the overview. */
        val count: Int = 0,
        /** SRC_* of the source folder this row belongs to (Device / Drive / YouTube / Spotify), or null. */
        val src: String? = null,
        /** lastModified of the custom cover file (0 = none), so a changed cover is not mistaken for an unchanged row. */
        val coverStamp: Long = 0L
    ) {
        /** Set by refresh(): the row is one of the pinned playlists (shows the pin icon). */
        var pinned: Boolean = false

        /** Same row as [o] as far as the list shows it (so rebinding it would change nothing). */
        fun sameAs(o: Row) = kind == o.kind && id == o.id && name == o.name && subtitle == o.subtitle &&
            artUrl == o.artUrl && count == o.count && src == o.src && pinned == o.pinned && coverStamp == o.coverStamp
    }

    private class RowAdapter(
        private val onClick: (Row) -> Unit,
        private val onLong: (Row) -> Unit
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        private var items: List<Row> = emptyList()
        private var selecting = false
        private var selectedIds: Set<String> = emptySet()

        /** Multi-select mode (main list or inside a folder): own playlist rows and folder rows show a check box. */
        fun setSelection(active: Boolean, ids: Set<String>) {
            if (active == selecting && ids == selectedIds) return
            selecting = active
            selectedIds = ids
            notifyDataSetChanged()
        }

        fun selectableIds(): List<String> = items.filter { it.kind == ROW_LIST || it.kind == ROW_FOLDER }.map { it.id }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val art: ImageView = view.findViewById(R.id.playlistArt)
            val name: TextView = view.findViewById(R.id.playlistName)
            val sub: TextView = view.findViewById(R.id.playlistOwner)
            val trailing: ImageView = view.findViewById(R.id.playlistTrailing)
            val check: android.widget.CheckBox = view.findViewById(R.id.playlistCheck)

            init {
                // Round the corners of a cover picture like the placeholder background.
                art.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                art.clipToOutline = true
            }
        }

        /** Small decoded copies of the custom covers (key = file + timestamp, so an edit shows at once). */
        private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(40)

        private fun thumb(f: java.io.File): android.graphics.Bitmap? {
            val key = "${f.absolutePath}:${f.lastModified()}"
            thumbs.get(key)?.let { return it }
            return try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 192) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)?.also { thumbs.put(key, it) }
            } catch (e: Exception) { null }
        }

        private fun showCover(art: ImageView, bmp: android.graphics.Bitmap) {
            art.setPadding(0, 0, 0, 0)
            art.scaleType = ImageView.ScaleType.CENTER_CROP
            art.imageTintList = null
            art.setImageBitmap(bmp)
        }

        fun submit(list: List<Row>) {
            // refresh() runs often (resume, network loads, ...). Rebinding an unchanged list would cancel a tap or a
            // long press in progress, so an identical list is left alone.
            if (list.size == items.size && list.indices.all { list[it].sameAs(items[it]) }) {
                items = list
                return
            }
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
            val vh = VH(v)
            v.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            v.setOnLongClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) { onLong(items[pos]); true } else false
            }
            return vh
        }

        override fun getItemCount(): Int = items.size

        private fun attrColor(ctx: Context, attr: Int): Int {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(attr, tv, true)
            return tv.data
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            val ctx = holder.itemView.context
            holder.name.text = row.name
            holder.sub.text = row.subtitle
            holder.check.visibility = if (selecting && (row.kind == ROW_LIST || row.kind == ROW_FOLDER)) View.VISIBLE else View.GONE
            holder.check.isChecked = selecting && row.id in selectedIds
            if (row.pinned) {
                holder.trailing.setImageResource(R.drawable.ic_pin)
                holder.trailing.imageTintList = ColorStateList.valueOf(attrColor(ctx, androidx.appcompat.R.attr.colorAccent))
                holder.trailing.visibility = View.VISIBLE
            } else {
                holder.trailing.visibility = View.GONE
            }
            val pad = (12 * ctx.resources.displayMetrics.density).toInt()
            holder.art.setPadding(pad, pad, pad, pad)
            holder.art.scaleType = ImageView.ScaleType.CENTER_INSIDE
            holder.art.setImageResource(
                when (row.kind) {
                    ROW_LIKED -> R.drawable.ic_heart
                    ROW_GENERATE -> R.drawable.ic_sparkle
                    ROW_CONVERT -> R.drawable.ic_playlist
                    ROW_FOLDER, ROW_DEVICE, ROW_SOURCE -> R.drawable.ic_folder
                    else -> R.drawable.ic_playlist
                }
            )
            holder.art.imageTintList = ColorStateList.valueOf(
                attrColor(
                    ctx,
                    if (row.kind == ROW_LIST || row.kind == ROW_YT || row.kind == ROW_DRIVE || row.kind == ROW_DEVICE || row.kind == ROW_FOLDER || row.kind == ROW_SOURCE) R.attr.colorTextSecondary else androidx.appcompat.R.attr.colorAccent
                )
            )
            holder.art.tag = null
            if (row.kind != ROW_LIST && row.kind != ROW_YT) return
            // Own playlist: its chosen cover picture, else the first song's artwork, else the icon above.
            val file = PlaylistStore.coverFile(ctx, row.id)
            val custom = if (row.kind == ROW_LIST && file.exists()) thumb(file) else null
            if (custom != null) {
                showCover(holder.art, custom)
            } else if (row.artUrl.isNotBlank() && !MusicPrefs.saver(ctx)) {
                holder.art.tag = row.artUrl
                ImageLoader.load(row.artUrl) { bmp ->
                    if (bmp != null && holder.art.tag == row.artUrl) showCover(holder.art, bmp)
                }
            }
        }
    }

    companion object {
        /** Text of a `starrysound://playlist` link that opened the app; imported once this page is visible. */
        @Volatile
        var pendingImport: String? = null

        /** Id of a list to open as soon as the Playlist page is shown. */
        @Volatile
        var pendingOpen: String? = null

        /** After a failed YouTube / Drive fetch (e.g. offline) wait this long before trying again. */
        private const val RETRY_AFTER_FAIL_MS = 30_000L

        private const val STATE_EXPORT_ID = "export_id"
        private const val SORT_PREFS = "playlist_sort"
        private const val SORT_KEY = "mode"
        private const val LIST_SORT_KEY = "overview_mode"
        private const val LSORT_NAME_AZ = 1
        private const val LSORT_NAME_ZA = 2
        private const val LSORT_MOST = 3
        private const val LSORT_FEWEST = 4
        private const val SORT_DEFAULT = 0
        private const val F_LIKED = 1
        private const val F_MINE = 2
        private const val F_YT = 3
        private const val F_DRIVE = 4
        private const val F_DEVICE = 5
        private const val SORT_NEWEST = 1
        private const val SORT_OLDEST = 2
        private const val SORT_TITLE_AZ = 3
        private const val SORT_TITLE_ZA = 4
        private const val SORT_ARTIST = 5
        private const val SORT_LONGEST = 6
        private const val SORT_SHORTEST = 7
        private const val ROW_LIKED = 1
        private const val ROW_LIST = 2
        private const val ROW_GENERATE = 3
        private const val ROW_YT = 4
        private const val ROW_CONVERT = 6
        private const val ROW_FOLDER = 7
        private const val CONVERT_ID = "__convert__"
        private const val MAX_CONVERT_SONGS = 150
        private const val ROW_DRIVE = 5
        private const val ROW_DEVICE = 8
        private const val ROW_SOURCE = 9
        private const val SRC_PREFIX = "src:"
        private const val SRC_DEVICE = "device"
        private const val SRC_DRIVE = "drive"
        private const val SRC_YT = "youtube"
        private const val SRC_SPOTIFY = "spotify"
        private fun srcName(src: String) = when (src) {
            SRC_DEVICE -> R.string.source_local
            SRC_DRIVE -> R.string.source_drive
            SRC_YT -> R.string.source_youtube
            else -> R.string.source_spotify
        }
        private const val DEVICE_PREFIX = "dev:"
        private const val DRIVE_PREFIX = "drv:"
        private const val GENERATE_ID = "__generate__"
    }
}
