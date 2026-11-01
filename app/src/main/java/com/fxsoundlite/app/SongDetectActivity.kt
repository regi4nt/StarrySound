package com.fxsoundlite.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * "Detect a song" screen, opened from the Home tile. Three modes, picked with the chips:
 *  - Sing: the system speech recogniser transcribes sung lyrics, which are searched on YouTube.
 *    The transcript is editable, so a misheard word can be fixed and searched again.
 *  - Hum: a short clip is matched by melody through ACRCloud ([SongRecognizer]); needs the
 *    user's ACRCloud keys from Settings.
 *  - Playing nearby: a recording of a song playing around you is matched through Shazam
 *    ([ShazamRecognizer]); no key needed, but it does not understand humming.
 * Tapping a result plays it through [MusicService].
 */
class SongDetectActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "song_detect_prefs"
        private const val KEY_MODE = "mode"
        private const val KEY_LANG = "lang"
        private const val MODE_SING = 0
        private const val MODE_HUM = 1
        private const val MODE_NEARBY = 2
        private val MODES = listOf(
            MODE_SING to R.string.detect_mode_sing,
            MODE_HUM to R.string.detect_mode_hum,
            MODE_NEARBY to R.string.detect_mode_nearby
        )
        /** Language tag for the speech recogniser; "" = the device's own language. */
        private val LANGS = listOf(
            "" to R.string.detect_lang_auto,
            "id-ID" to R.string.detect_lang_id,
            "en-US" to R.string.detect_lang_en
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var dismissed = false
    private var busy = false
    @Volatile private var stopRequested = false
    private var speech: SpeechRecognizer? = null
    private var mode = MODE_SING
    private var lang = ""
    /** Bumped whenever a new attempt starts or the mode changes, so late answers are dropped. */
    private var token = 0

    private val button by lazy { findViewById<ImageView>(R.id.detectButton) }
    private val status by lazy { findViewById<TextView>(R.id.detectStatus) }
    private val heard by lazy { findViewById<TextView>(R.id.detectHeard) }
    private val edit by lazy { findViewById<EditText>(R.id.detectEdit) }
    private val subtitle by lazy { findViewById<TextView>(R.id.detectSubtitle) }
    private val setup by lazy { findViewById<View>(R.id.detectSetup) }
    private val list by lazy { findViewById<LinearLayout>(R.id.detectList) }
    private val resultsLabel by lazy { findViewById<View>(R.id.detectResultsLabel) }
    private val modeChips by lazy { findViewById<LinearLayout>(R.id.detectModes) }
    private val langChips by lazy { findViewById<LinearLayout>(R.id.detectLangs) }
    private val langScroll by lazy { findViewById<View>(R.id.detectLangsScroll) }

    private var service: MusicService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? MusicService.LocalBinder)?.getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else setStatus(getString(R.string.detect_need_mic))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_detect)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        bound = applicationContext.bindService(
            Intent(applicationContext, MusicService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        mode = prefs.getInt(KEY_MODE, MODE_SING).takeIf { m -> MODES.any { it.first == m } } ?: MODE_SING
        lang = prefs.getString(KEY_LANG, "").orEmpty().takeIf { l -> LANGS.any { it.first == l } } ?: ""
        buildModeChips()
        buildLangChips()
        button.setOnClickListener { onButton() }
        setup.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PAGE_ONLINE))
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(edit.windowToken, 0)
                val q = edit.text.toString().trim()
                if (q.isNotEmpty() && !busy) { clearResults(); setBusy(true); searchLyrics(listOf(q), token) }
                true
            } else false
        }
        applyMode()
    }

    override fun onResume() {
        super.onResume()
        // The ACRCloud keys may just have been filled in.
        applyMode()
    }

    override fun onDestroy() {
        dismissed = true
        stopRequested = true
        token++
        handler.removeCallbacksAndMessages(null)
        speech?.destroy()
        speech = null
        io.shutdown()
        if (bound) {
            try { applicationContext.unbindService(connection) } catch (e: Exception) { android.util.Log.d("SongDetectActivity", "ignored: " + e) }
            bound = false
        }
        service = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ mode + language chips

    private fun buildModeChips() {
        modeChips.removeAllViews()
        val gap = (8 * resources.displayMetrics.density).toInt()
        for ((index, pair) in MODES.withIndex()) {
            val (id, label) = pair
            val chip = LayoutInflater.from(this).inflate(R.layout.item_preset_chip, modeChips, false) as TextView
            chip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (index == MODES.lastIndex) 0 else gap
            }
            chip.gravity = android.view.Gravity.CENTER
            chip.setPadding(gap, chip.paddingTop, gap, chip.paddingBottom)
            chip.setText(label)
            chip.setOnClickListener {
                if (id == mode) return@setOnClickListener
                cancelCurrent()
                mode = id
                prefs.edit().putInt(KEY_MODE, id).apply()
                clearResults()
                setStatus(getString(R.string.detect_idle))
                applyMode()
            }
            modeChips.addView(chip)
        }
    }

    private fun buildLangChips() {
        langChips.removeAllViews()
        for ((index, pair) in LANGS.withIndex()) {
            val (code, label) = pair
            val chip = LayoutInflater.from(this).inflate(R.layout.item_preset_chip, langChips, false) as TextView
            // No trailing margin on the last chip, otherwise the row sits 8dp left of the middle.
            (chip.layoutParams as LinearLayout.LayoutParams).marginEnd = if (index == LANGS.lastIndex) 0 else (8 * resources.displayMetrics.density).toInt()
            chip.gravity = android.view.Gravity.CENTER
            chip.setText(label)
            chip.setOnClickListener {
                lang = code
                prefs.edit().putString(KEY_LANG, code).apply()
                for (i in 0 until langChips.childCount) langChips.getChildAt(i).isSelected = LANGS[i].first == code
            }
            langChips.addView(chip)
        }
    }

    private fun applyMode() {
        for (i in 0 until modeChips.childCount) modeChips.getChildAt(i).isSelected = MODES[i].first == mode
        for (i in 0 until langChips.childCount) langChips.getChildAt(i).isSelected = LANGS[i].first == lang
        langScroll.visibility = if (mode == MODE_SING) View.VISIBLE else View.GONE
        if (mode != MODE_SING) edit.visibility = View.GONE
        subtitle.setText(
            when (mode) {
                MODE_HUM -> R.string.detect_sub_hum
                MODE_NEARBY -> R.string.detect_sub_nearby
                else -> R.string.detect_sub_sing
            }
        )
        val needKeys = mode == MODE_HUM && !MusicPrefs.acrConfigured(this)
        setup.visibility = if (needKeys) View.VISIBLE else View.GONE
        if (needKeys && !busy) setStatus(getString(R.string.detect_hum_need_keys))
        else if (!needKeys && !busy && status.text.isNullOrEmpty()) setStatus(getString(R.string.detect_idle))
    }

    // ------------------------------------------------------------------ flow

    private fun cancelCurrent() {
        token++
        stopRequested = true
        speech?.cancel()
        setBusy(false)
    }

    private fun onButton() {
        if (busy) {
            // While recording, tap again = "that's enough, send it". Speech mode: stop listening.
            stopRequested = true
            speech?.stopListening()
            return
        }
        start()
    }

    private fun start() {
        if (mode == MODE_HUM && !MusicPrefs.acrConfigured(this)) {
            setStatus(getString(R.string.detect_hum_need_keys))
            setup.visibility = View.VISIBLE
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        clearResults()
        token++
        when (mode) {
            MODE_HUM -> startHumming(token)
            MODE_NEARBY -> startNearby(token)
            else -> startSinging(token)
        }
    }

    private fun clearResults() {
        list.removeAllViews()
        resultsLabel.visibility = View.GONE
        heard.visibility = View.GONE
    }

    private fun setBusy(b: Boolean) {
        busy = b
        button.alpha = if (b) 0.7f else 1f
    }

    private fun setStatus(s: String) { status.text = s }

    private fun networkOrError(e: ApiException): String = when {
        e.code == RemoteEngine.ERR_NETWORK -> getString(R.string.detect_network)
        e.message == ShazamRecognizer.RATE_LIMITED -> getString(R.string.detect_rate_limited)
        else -> getString(R.string.detect_error, e.message.orEmpty())
    }

    /** Runs [block] on the main thread unless this attempt was cancelled or the screen closed. */
    private fun ui(tok: Int, block: () -> Unit) {
        handler.post { if (!dismissed && tok == token) block() }
    }

    // ---- melody matching (ACRCloud)

    private fun startHumming(tok: Int) {
        setBusy(true)
        stopRequested = false
        setStatus(getString(R.string.detect_listening_hum))
        val host = MusicPrefs.acrHost(this)
        val key = MusicPrefs.acrKey(this)
        val secret = MusicPrefs.acrSecret(this)
        io.execute {
            val wav = SongRecognizer.record({ stopRequested || dismissed || tok != token }) { sec ->
                ui(tok) { setStatus(getString(R.string.detect_listening_secs, sec, SongRecognizer.MAX_SECONDS)) }
            }
            if (dismissed || tok != token) return@execute
            if (wav == null) {
                ui(tok) { setBusy(false); setStatus(getString(R.string.detect_too_short)) }
                return@execute
            }
            ui(tok) { setStatus(getString(R.string.detect_searching)) }
            try {
                val matches = SongRecognizer.identify(host, key, secret, wav)
                ui(tok) {
                    setBusy(false)
                    if (matches.isEmpty()) setStatus(getString(R.string.detect_none))
                    else showMatches(matches, getString(R.string.detect_found))
                }
            } catch (e: ApiException) {
                ui(tok) { setBusy(false); setStatus(networkOrError(e)) }
            }
        }
    }

    // ---- a song playing nearby (Shazam, no key)

    private fun startNearby(tok: Int) {
        setBusy(true)
        stopRequested = false
        setStatus(getString(R.string.detect_listening_nearby))
        io.execute {
            val pcm = SongRecognizer.recordPcm(
                ShazamRecognizer.RATE, ShazamRecognizer.MAX_SECONDS, ShazamRecognizer.MIN_SECONDS,
                { stopRequested || dismissed || tok != token }
            ) { sec ->
                ui(tok) { setStatus(getString(R.string.detect_listening_secs, sec, ShazamRecognizer.MAX_SECONDS)) }
            }
            if (dismissed || tok != token) return@execute
            if (pcm == null) {
                ui(tok) { setBusy(false); setStatus(getString(R.string.detect_too_short)) }
                return@execute
            }
            ui(tok) { setStatus(getString(R.string.detect_searching)) }
            try {
                val matches = ShazamRecognizer.identify(pcm)
                ui(tok) {
                    setBusy(false)
                    if (matches.isEmpty()) setStatus(getString(R.string.detect_none_nearby))
                    else showMatches(matches, getString(R.string.detect_found_nearby))
                }
            } catch (e: ApiException) {
                ui(tok) { setBusy(false); setStatus(networkOrError(e)) }
            }
        }
    }

    private fun showMatches(matches: List<SongRecognizer.Match>, header: String) {
        setStatus(header)
        resultsLabel.visibility = View.VISIBLE
        for (m in matches.take(8)) {
            addRow(m.title, m.artist, m.art) { playByName("${m.artist} ${m.title}".trim()) }
        }
    }

    // ---- sung lyrics (system speech recogniser -> YouTube search)

    private fun startSinging(tok: Int) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(getString(R.string.detect_no_speech))
            return
        }
        setBusy(true)
        stopRequested = false
        setStatus(getString(R.string.detect_listening_sing))
        edit.visibility = View.GONE
        val sr = speech ?: SpeechRecognizer.createSpeechRecognizer(this).also { speech = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (tok != token) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) { heard.text = t; heard.visibility = View.VISIBLE }
            }
            override fun onError(error: Int) {
                if (tok != token) return
                setBusy(false)
                setStatus(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.detect_none_sing)
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> getString(R.string.detect_network)
                        else -> getString(R.string.detect_error, error.toString())
                    }
                )
            }
            override fun onResults(results: Bundle?) {
                if (tok != token) return
                // The recogniser returns several guesses: keep the distinct, non-blank ones.
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (all.isEmpty()) { setBusy(false); setStatus(getString(R.string.detect_none_sing)); return }
                heard.visibility = View.GONE
                edit.setText(all.first())
                edit.visibility = View.VISIBLE
                searchLyrics(all, tok)
            }
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                if (lang.isNotEmpty()) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                }
            }
        )
    }

    /** Searches YouTube with each guess in turn and shows the first one that finds songs. */
    private fun searchLyrics(guesses: List<String>, tok: Int) {
        setStatus(getString(R.string.detect_searching))
        io.execute {
            try {
                var tracks: List<Track> = emptyList()
                for (g in guesses.take(3)) {
                    if (dismissed || tok != token) return@execute
                    tracks = YouTubeApi.search(this, "$g lyrics", maxResults = 8)
                    if (tracks.isNotEmpty()) break
                }
                ui(tok) {
                    setBusy(false)
                    if (tracks.isEmpty()) { setStatus(getString(R.string.detect_none_sing)); return@ui }
                    setStatus(getString(R.string.detect_found_sing))
                    resultsLabel.visibility = View.VISIBLE
                    tracks.forEachIndexed { i, t -> addRow(t.title, t.artist, t.artUrl) { play(tracks, i) } }
                }
            } catch (e: ApiException) {
                ui(tok) { setBusy(false); setStatus(networkOrError(e)) }
            }
        }
    }

    // ---- results

    private fun addRow(title: String, artist: String, artUrl: String, onClick: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_track, list, false)
        row.findViewById<TextView>(R.id.trackTitle).text = title
        row.findViewById<TextView>(R.id.trackArtist).text = artist
        row.findViewById<TextView>(R.id.trackDuration).visibility = View.GONE
        if (artUrl.isNotBlank() && !MusicPrefs.saver(this)) {
            val icon = row.findViewById<ImageView>(R.id.trackIcon)
            ImageLoader.load(artUrl) { bmp ->
                if (bmp != null && !dismissed) {
                    icon.setPadding(0, 0, 0, 0)
                    icon.imageTintList = null
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    icon.setImageBitmap(bmp)
                }
            }
        }
        row.setOnClickListener { onClick() }
        list.addView(row)
    }

    /** A recognised song has no video yet: find it on YouTube, then play the first hit. */
    private fun playByName(query: String) {
        Toast.makeText(this, R.string.detect_opening, Toast.LENGTH_SHORT).show()
        io.execute {
            val tracks = try { YouTubeApi.search(this, query, maxResults = 5) } catch (e: ApiException) { emptyList() }
            handler.post {
                if (dismissed) return@post
                if (tracks.isEmpty()) Toast.makeText(this, R.string.detect_play_failed, Toast.LENGTH_SHORT).show()
                else play(tracks, 0)
            }
        }
    }

    private fun play(tracks: List<Track>, index: Int) {
        val svc = service ?: MusicService.instance
        if (svc == null) {
            Toast.makeText(this, R.string.detect_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        svc.setQueueAndPlay(tracks, index)
    }
}
