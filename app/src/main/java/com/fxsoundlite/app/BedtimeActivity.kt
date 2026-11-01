package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.UserRecoverableAuthException
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * "Sleep sounds" screen opened from the Home page's Sleep card (a full screen like the equalizer):
 * pick a timer, pick music on the device, in Google Drive (Drive tab, when signed in) or on YouTube, tap something to play. When the time is up
 * [SleepTimerService] fades the volume out and pauses whatever is playing. Playback runs in [MusicService].
 */
class BedtimeActivity : AppCompatActivity() {

    private val context: Context get() = this

    // Drive "read your files" permission (first time Drive is used); on OK the list loads again.
    private val driveConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (dismissed) return@registerForActivityResult
            if (res.resultCode == android.app.Activity.RESULT_OK) {
                driveError = null
                loadDrive(force = true)
            } else {
                driveError = context.getString(R.string.drive_music_no_permission)
            }
        }

    private val devicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refresh()
        }

    companion object {
        private const val PREFS = "bedtime_prefs"
        private const val KEY_MINUTES = "timer_minutes"
        private const val KEY_SOURCE = "source"
        private const val SOURCE_DEVICE = 0
        private const val SOURCE_YOUTUBE = 1
        private const val SOURCE_DRIVE = 3 // 2 was Radio (removed); an old saved value falls back to Device
        /** Drive list is reused for this long before it is fetched again. */
        private const val DRIVE_TTL_MS = 2 * 60_000L
        private val TIMER_CHOICES = intArrayOf(15, 30, 45, 60, 90, 0) // 0 = no timer
                private const val MAX_DEVICE_ROWS = 150
    }

    private class Row(val title: String, val subtitle: String, val artUrl: String, val onClick: () -> Unit)

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    /** Online searches get their own pool: a slow (or outdated) search must not hold up the one the user waits for. */
    private val netIo = Executors.newCachedThreadPool()
    /** Results of earlier YouTube searches of this screen: asking the same thing again is instant. */
    private val ytCache = HashMap<String, List<Track>>()
    private val prefs by lazy { applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val sourceChips by lazy { findViewById<LinearLayout>(R.id.bedtimeSources) }
    private val chips by lazy { findViewById<LinearLayout>(R.id.bedtimeChips) }
    private val list by lazy { findViewById<LinearLayout>(R.id.bedtimeList) }
    private val search by lazy { findViewById<EditText>(R.id.bedtimeSearch) }
    private val progress by lazy { findViewById<View>(R.id.bedtimeProgress) }
    private val messageBox by lazy { findViewById<View>(R.id.bedtimeMessageBox) }
    private val message by lazy { findViewById<TextView>(R.id.bedtimeMessage) }
    private val action by lazy { findViewById<TextView>(R.id.bedtimeAction) }
    private val running by lazy { findViewById<View>(R.id.bedtimeRunning) }
    private val runningText by lazy { findViewById<TextView>(R.id.bedtimeRunningText) }

    private var minutes = 30
    private var dismissed = false
    private var loadToken = 0
    private var suppressSearch = false
    private var deviceTracks: List<Track>? = null
    private var driveAll: List<Track>? = null
    private var driveLoading = false
    private var driveError: String? = null
    private var driveLoadedAt = 0L
    private var driveConsentAsked = false
    private var driveEmail = ""
    private var driveChipShown = false
    private var source = SOURCE_DEVICE
    private var lastYtQuery: String? = null
    /** Popular stations of the device's country, loaded once per screen visit. */
    private val ytSearch = Runnable { if (!dismissed && source == SOURCE_YOUTUBE) loadYoutube() }

    // Device audio is played by MusicService, so this screen binds to it while it is open.
    private var service: MusicService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? MusicService.LocalBinder)?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val countdown = object : Runnable {
        override fun run() {
            if (dismissed) return
            refreshRunning()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bedtime)
        minutes = prefs.getInt(KEY_MINUTES, 30)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        bound = applicationContext.bindService(
            Intent(applicationContext, MusicService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        source = prefs.getInt(KEY_SOURCE, SOURCE_DEVICE).takeIf { it == SOURCE_DEVICE || it == SOURCE_DRIVE || it == SOURCE_YOUTUBE } ?: SOURCE_DEVICE
        driveChipShown = DriveMusic.isSignedIn(this)
        MusicLibrary.addChangeListener(libraryChanged)
        buildSourceChips()
        buildChips()
        setupSearch()
        findViewById<View>(R.id.bedtimeStopBtn).setOnClickListener {
            SleepTimerService.cancel(this)
            handler.postDelayed({ refreshRunning() }, 300)
        }
        countdown.run()
        load()
    }

    override fun onResume() {
        super.onResume()
        // The user may have signed in or out of Google (Settings) while this screen was in the background.
        val signedIn = DriveMusic.isSignedIn(this)
        if (signedIn != driveChipShown) {
            driveChipShown = signedIn
            if (source == SOURCE_DRIVE) load() // the Drive tab depends on being signed in
        }
    }

    /** An SD card / OTG drive was plugged in or pulled out (or a download finished): read the device list again. */
    private val libraryChanged: () -> Unit = {
        if (!dismissed) {
            deviceTracks = null
            if (source == SOURCE_DEVICE) load()
        }
    }

    override fun onDestroy() {
        MusicLibrary.removeChangeListener(libraryChanged)
        dismissed = true
        loadToken++
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        netIo.shutdownNow()
        if (bound) {
            try {
                applicationContext.unbindService(connection)
            } catch (_: Exception) {
            }
            bound = false
        }
        service = null
        super.onDestroy()
    }

    /** Called after the audio permission was granted. */
    fun refresh() {
        if (dismissed) return
        deviceTracks = null
        load()
    }

    // ------------------------------------------------------------------ source + timer chips

    private fun buildChips() {
        chips.removeAllViews()
        for (m in TIMER_CHOICES) {
            val chip = LayoutInflater.from(context).inflate(R.layout.item_preset_chip, chips, false) as TextView
            chip.text = if (m == 0) context.getString(R.string.bedtime_no_timer)
            else context.getString(R.string.bedtime_minutes, m)
            chip.isSelected = m == minutes
            chip.setOnClickListener {
                minutes = m
                prefs.edit().putInt(KEY_MINUTES, m).apply()
                for (i in 0 until chips.childCount) chips.getChildAt(i).isSelected = TIMER_CHOICES[i] == m
                // Changing the timer while one is already running restarts it with the new length.
                if (SleepTimerService.isRunning()) {
                    if (m > 0) SleepTimerService.start(context, m, SleepTimerService.currentSource())
                    else SleepTimerService.cancel(context)
                    handler.postDelayed({ refreshRunning() }, 300)
                }
            }
            chips.addView(chip)
        }
    }

    private fun buildSourceChips() {
        sourceChips.removeAllViews()
        sourceChips.visibility = View.VISIBLE
        val labels = buildList {
            // Device, Drive (in the middle), YouTube.
            add(SOURCE_DEVICE to R.string.source_local)
            add(SOURCE_DRIVE to R.string.source_drive)
            add(SOURCE_YOUTUBE to R.string.source_youtube)
        }
        val gap = (8 * context.resources.displayMetrics.density).toInt()
        for ((index, pair) in labels.withIndex()) {
            val (id, label) = pair
            val chip = LayoutInflater.from(context).inflate(R.layout.item_preset_chip, sourceChips, false) as TextView
            // Equal-width buttons that together span the whole row, up to the search box.
            chip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (index == labels.lastIndex) 0 else gap
            }
            chip.gravity = android.view.Gravity.CENTER
            chip.setText(label)
            chip.isSelected = id == source
            chip.setOnClickListener {
                if (source == id) return@setOnClickListener
                source = id
                prefs.edit().putInt(KEY_SOURCE, id).apply()
                for (i in 0 until sourceChips.childCount) sourceChips.getChildAt(i).isSelected = labels[i].first == id
                suppressSearch = true
                search.setText("")
                suppressSearch = false
                lastYtQuery = null
                updateSearchBox()
                load()
            }
            sourceChips.addView(chip)
        }
    }

    private fun refreshRunning() {
        val ms = SleepTimerService.remainingMs()
        if (ms <= 0) {
            running.visibility = View.GONE
            return
        }
        val total = (ms + 999) / 1000
        val text = if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)
        runningText.text = context.getString(R.string.bedtime_stops_in, text)
        running.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ search box (Device)

    private fun setupSearch() {
        updateSearchBox()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}

            // Device music filters live as you type; YouTube searches by itself shortly after you stop.
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearch) return
                if (source == SOURCE_DEVICE) { renderDevice(); return }
                if (source == SOURCE_DRIVE) { renderDrive(); return }
                handler.removeCallbacks(ytSearch)
                handler.postDelayed(ytSearch, 650)
            }
        })
        search.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                if (source == SOURCE_YOUTUBE) { handler.removeCallbacks(ytSearch); loadYoutube() }
                hideKeyboard()
                true
            } else false
        }
    }

    private fun updateSearchBox() {
        search.setHint(
            when (source) {
                SOURCE_YOUTUBE -> R.string.bedtime_search_yt
                SOURCE_DRIVE -> R.string.bedtime_search_drive
                else -> R.string.bedtime_search_device
            }
        )
        search.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ------------------------------------------------------------------ content

    private fun showMessage(text: String, buttonLabel: String?, onClick: (() -> Unit)?) {
        message.text = text
        messageBox.visibility = View.VISIBLE
        if (buttonLabel != null && onClick != null) {
            action.text = buttonLabel
            action.visibility = View.VISIBLE
            action.setOnClickListener { onClick() }
        } else {
            action.visibility = View.GONE
        }
    }

    private fun load() {
        loadToken++ // drops anything still loading
        progress.visibility = View.INVISIBLE
        list.removeAllViews()
        messageBox.visibility = View.GONE
        when (source) {
            SOURCE_YOUTUBE -> loadYoutube()
            SOURCE_DRIVE -> loadDriveTab()
            else -> loadDevice()
        }
    }

    // ---- YouTube (results, no API key needed)

    private fun loadYoutube() {
        val q = search.text.toString().trim()
        val token = ++loadToken
        messageBox.visibility = View.GONE
        if (q.length < 2) {
            // Nothing typed: nothing to show (no suggestions).
            progress.visibility = View.INVISIBLE
            list.removeAllViews()
            return
        }
        if (q == lastYtQuery && list.childCount > 0) return
        ytCache[q.lowercase()]?.let { lastYtQuery = q; progress.visibility = View.INVISIBLE; renderYoutube(it); return }
        progress.visibility = View.VISIBLE
        val app = context.applicationContext
        try {
            netIo.execute {
                if (token != loadToken) return@execute // superseded while it was waiting: don't even start
                val result = runCatching { YouTubeApi.search(app, q, category = null, longOnly = false) }
                handler.post {
                    if (dismissed || token != loadToken) return@post
                    progress.visibility = View.INVISIBLE
                    result.onSuccess {
                        if (ytCache.size > 30) ytCache.clear()
                        ytCache[q.lowercase()] = it
                        lastYtQuery = q
                        renderYoutube(it)
                    }.onFailure { e ->
                        showMessage(context.getString(R.string.bedtime_failed, e.message.orEmpty()), null, null)
                    }
                }
            }
        } catch (e: Exception) {
            progress.visibility = View.INVISIBLE
        }
    }

    private fun renderYoutube(results: List<Track>?) {
        list.removeAllViews()
        messageBox.visibility = View.GONE
        if (results != null) {
            if (results.isEmpty()) {
                showMessage(context.getString(R.string.bedtime_empty), null, null)
            } else {
                addSection(R.string.bedtime_section_results, results.mapIndexed { i, t ->
                    Row(t.title, t.artist, t.artUrl) { playTracks(results, i) }
                })
            }
        }
    }

    // ---- Device

    private fun loadDevice() {
        if (!MusicLibrary.canRead(context)) {
            showMessage(
                context.getString(R.string.music_need_permission), context.getString(R.string.music_allow_access)
            ) { devicePermissionLauncher.launch(MusicLibrary.requiredPermission) }
            return
        }
        if (deviceTracks != null) {
            renderDevice()
            return
        }
        val app = context.applicationContext
        val token = ++loadToken
        progress.visibility = View.VISIBLE
        try {
            io.execute {
                val tracks = try {
                    MusicLibrary.load(app)
                } catch (e: Exception) {
                    emptyList()
                }
                handler.post {
                    if (dismissed || token != loadToken) return@post
                    progress.visibility = View.INVISIBLE
                    deviceTracks = tracks
                    renderDevice()
                }
            }
        } catch (e: Exception) {
            progress.visibility = View.INVISIBLE
        }
    }

    private fun renderDevice() {
        val device = deviceTracks ?: return
        list.removeAllViews()
        messageBox.visibility = View.GONE
        val all = device
        val q = search.text.toString().trim().lowercase()
        val matches = if (q.isEmpty()) all else all.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        if (matches.isEmpty()) {
            if (all.isEmpty() && !MusicLibrary.canRead(context)) {
                showMessage(
                    context.getString(R.string.music_need_permission), context.getString(R.string.music_allow_access)
                ) { devicePermissionLauncher.launch(MusicLibrary.requiredPermission) }
            } else {
                showMessage(
                    context.getString(if (all.isEmpty()) R.string.bedtime_empty_device else R.string.bedtime_no_match), null, null
                )
            }
            return
        }
        val rows = matches.take(MAX_DEVICE_ROWS).mapIndexed { i, t ->
            Row(t.title, t.labeledSubtitle(context, t.artist.ifBlank { t.album }), "") { playTracks(matches, i) }
        }
        addSection(R.string.bedtime_section_device, rows)
    }

    // ---- Drive tab: the user's Google Drive songs

    private fun loadDriveTab() {
        if (!DriveMusic.isSignedIn(context)) {
            showMessage(context.getString(R.string.drive_music_need_signin), null, null)
            return
        }
        val email = DriveSync.email(context)
        if (email != driveEmail) { driveEmail = email; driveAll = null; driveError = null } // another account
        if (driveAll == null || System.currentTimeMillis() - driveLoadedAt > DRIVE_TTL_MS) loadDrive(force = false)
        renderDrive()
    }

    private fun renderDrive() {
        list.removeAllViews()
        messageBox.visibility = View.GONE
        if (!DriveMusic.isSignedIn(context)) {
            showMessage(context.getString(R.string.drive_music_need_signin), null, null)
            return
        }
        val all = driveAll
        if (all == null) {
            if (driveLoading) showMessage(context.getString(R.string.drive_music_loading), null, null)
            else driveError?.let { showMessage(it.ifBlank { context.getString(R.string.drive_music_error) }, null, null) }
            return
        }
        val q = search.text.toString().trim().lowercase()
        val matches = if (q.isEmpty()) all else all.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        if (matches.isEmpty()) {
            showMessage(context.getString(if (all.isEmpty()) R.string.drive_music_empty else R.string.bedtime_no_match), null, null)
            return
        }
        val rows = matches.take(MAX_DEVICE_ROWS).mapIndexed { i, t ->
            Row(t.title, t.labeledSubtitle(context, t.artist.ifBlank { t.album }), "") { playTracks(matches, i) }
        }
        addSection(R.string.bedtime_section_drive, rows)
    }

    // ---- Google Drive songs (loaded in the background for the Drive tab)

    private fun loadDrive(force: Boolean) {
        if (driveLoading) return
        if (!force && driveAll != null && System.currentTimeMillis() - driveLoadedAt < DRIVE_TTL_MS) return
        driveLoading = true
        progress.visibility = View.VISIBLE
        val app = context.applicationContext
        try {
            io.execute {
                var consent: Intent? = null
                val result = try {
                    Result.success(DriveMusic.listAudio(app))
                } catch (e: UserRecoverableAuthException) {
                    consent = e.intent
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(e)
                }
                handler.post {
                    driveLoading = false
                    if (dismissed) return@post
                    progress.visibility = View.INVISIBLE
                    result.onSuccess {
                        driveAll = it
                        driveError = null
                        driveConsentAsked = false
                        driveLoadedAt = System.currentTimeMillis()
                    }.onFailure { e ->
                        val c = consent
                        if (c != null && !driveConsentAsked) {
                            driveConsentAsked = true
                            try { driveConsentLauncher.launch(c); return@post } catch (e: Exception) { android.util.Log.d("BedtimeActivity", "ignored: " + e) }
                        }
                        if (driveAll == null) driveError = when {
                            e is DriveMusic.NotSignedInException -> context.getString(R.string.drive_music_need_signin)
                            c != null -> context.getString(R.string.drive_music_no_permission)
                            e is java.io.IOException && e !is DriveMusic.DriveHttpException ->
                                context.getString(R.string.online_network_error)
                            else -> e.message.orEmpty()
                        }
                    }
                    if (source == SOURCE_DRIVE) renderDrive()
                }
            }
        } catch (e: Exception) {
            driveLoading = false
            progress.visibility = View.INVISIBLE
        }
    }

    // ---- shared list UI

    private fun addSection(titleRes: Int, rows: List<Row>) {
        val density = context.resources.displayMetrics.density
        val header = TextView(context).apply {
            setText(titleRes)
            setTextColor(themeColor(R.attr.colorTextSecondary))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val h = (20 * density).toInt()
            setPadding(h, (14 * density).toInt(), h, (4 * density).toInt())
        }
        list.addView(header)
        for (r in rows) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_playlist, list, false)
            row.findViewById<TextView>(R.id.playlistName).text = r.title
            row.findViewById<TextView>(R.id.playlistOwner).text = r.subtitle
            val art = row.findViewById<ImageView>(R.id.playlistArt)
            if (r.artUrl.isNotBlank() && !MusicPrefs.saver(context)) {
                art.tag = r.artUrl
                ImageLoader.load(r.artUrl) { bmp ->
                    if (bmp != null && art.tag == r.artUrl) {
                        art.imageTintList = null
                        art.setPadding(0, 0, 0, 0)
                        art.setImageBitmap(bmp)
                    }
                }
            }
            row.setOnClickListener { r.onClick() }
            list.addView(row)
        }
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    // ------------------------------------------------------------------ playing

    /** The whole list becomes the queue, so the next sound follows by itself. */
    private fun playTracks(tracks: List<Track>, index: Int) {
        val svc = service
        if (svc == null) {
            Toast.makeText(context, R.string.online_playback_failed, Toast.LENGTH_LONG).show()
            return
        }
        hideKeyboard()
        try {
            svc.setQueueAndPlay(tracks, index)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.online_playback_failed, Toast.LENGTH_LONG).show()
            return
        }
        onPlaying(tracks[index].title, tracks[index].source)
    }

    private fun onPlaying(name: String, playingSource: Int) {
        val m = minutes
        if (m > 0) {
            try {
                SleepTimerService.start(context, m, playingSource)
            } catch (e: Exception) {
                // playback already started; just no timer
            }
            Toast.makeText(context, context.getString(R.string.bedtime_timer_set, m), Toast.LENGTH_SHORT).show()
            if (playingSource == Track.SOURCE_DRIVE) {
                Toast.makeText(context, R.string.bedtime_drive_downloading, Toast.LENGTH_SHORT).show()
            }
        } else {
            SleepTimerService.cancel(context)
            Toast.makeText(context, context.getString(R.string.bedtime_playing, name), Toast.LENGTH_SHORT).show()
        }
        finish() // back to Home
    }
}
