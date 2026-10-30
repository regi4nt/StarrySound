package com.fxsoundlite.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fxsoundlite.app.databinding.ActivitySettingsBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.color.MaterialColors

/**
 * Settings screen. The layout (activity_settings.xml) and this class use the
 * same categories, in the same top-to-bottom order:
 *
 *  1. General               -- theme mode, accent color, language
 *  2. Online music          -- YouTube key
 *  3. Player                -- custom cover photo for the mini / full player
 *  4. Screensaver display   -- background, clock style, greeting, now-playing title,
 *                              auto-open over the lock screen while charging
 *  5. Advanced              -- Auto preset per output, Preset notification, Root Mode, Storage, About & help
 *
 * Pages: the main screen shows the single-function sections plus one card per category that has
 * several functions (General, Player, Screensaver, Online music, Advanced). Tapping a card opens
 * that category's own page (same Activity, see [showPage]); Back / the arrow returns to the cards.
 * [EXTRA_PAGE] opens straight on a page.
 *
 * Each category has one setupXxx() entry point called from onCreate(), plus
 * (where a permission is involved) a refreshXxx() re-run from onResume().
 * The code below is grouped under matching "// ===" banners.
 *
 * Root Mode lives here rather than on MainActivity: it's a niche, higher-risk
 * toggle (distortion risk, forces an audioserver restart). Like MainActivity,
 * this binds to the already-running EffectService instead of creating its own
 * AudioEffectManager -- see AudioEffectManager's class doc for why there must
 * only ever be one instance of it alive at a time.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Open Settings straight on this page ([PAGE_ONLINE], ...); anything else = the main screen. */
        const val EXTRA_PAGE = "settings_page"
        const val PAGE_ONLINE = "online"
        private const val PAGE_HUB = "hub"
        private const val STATE_PAGE = "page"
        private const val SUPPORT_EMAIL = "regiant2012@gmail.com"
        private const val GITHUB_URL = "https://github.com/regi4nt"
    }

    private lateinit var binding: ActivitySettingsBinding
    private var page = PAGE_HUB
    private val backToCards = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showPage(PAGE_HUB, true)
    }

    private var effectManager: AudioEffectManager? = null
    private var effectService: EffectService? = null

    private var bound = false

    // Must be registered unconditionally before onCreate/onStart -- a property
    // initializer runs during construction, well before this Activity reaches
    // STARTED, which is what registerForActivityResult() requires.
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // Persist read access across app/device restarts -- without this
                // the Uri stops working the moment the picker's own grant expires.
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // (SecurityException, or IllegalArgumentException on some providers)
                    // Some providers don't support persistable grants; the Uri may
                    // still work for this session, so keep going rather than bail.
                }
                // Let go of the previous pick's grant (Android caps the number
                // of persisted grants per app, so old ones must not pile up).
                val previous = ScreensaverPrefs.videoUri(this)
                if (previous != null && previous != uri.toString()) releaseGrant(previous)
                ScreensaverPrefs.setMedia(this, uri.toString(), detectMediaType(uri))
                refreshScreensaverStatus()
            }
        }

    // Custom cover photo for the mini player / full player.
    private val pickCoverLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                if (!CoverUi.setCustom(this, uri)) {
                    Toast.makeText(this, R.string.cover_save_failed, Toast.LENGTH_SHORT).show()
                }
                refreshCoverStatus()
            }
        }

    // Google sign-in screen (account chooser + Drive consent).
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleGoogleSignInResult(result.data)
        }

    // Drive permission prompt that Google asks for when the silent token request needs the user's OK.
    private var consentRetried = false
    private val driveConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                runDriveSync()
            } else {
                consentRetried = false
                Toast.makeText(this, R.string.drive_err_no_permission, Toast.LENGTH_LONG).show()
            }
        }

    // Notification permission prompt (Android 13+) for the charging screensaver.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshChargingStatus()
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EffectService.LocalBinder
            effectService = binder.getService()
            effectManager = effectService?.effectManager
            bound = true
            onEngineReady()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            effectService = null
        }
    }

    // === Storage: clear cache ===

    private fun formatMb(bytes: Long) = String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1048576.0)

    private fun refreshCacheSize() {
        Thread {
            val n = CacheCleaner.size(applicationContext)
            runOnUiThread { if (!isFinishing) binding.cacheSizeText.text = getString(R.string.cache_size, formatMb(n)) }
        }.start()
    }

    private fun setupCache() {
        refreshCacheSize()
        binding.clearCacheBtn.setOnClickListener {
            binding.clearCacheBtn.isEnabled = false
            CacheCleaner.clear(this) { freed ->
                if (isFinishing) return@clear
                binding.clearCacheBtn.isEnabled = true
                android.widget.Toast.makeText(this, getString(R.string.cache_cleared, formatMb(freed)), android.widget.Toast.LENGTH_LONG).show()
                refreshCacheSize()
            }
        }
    }

    // === About & help ===

    private fun setupAbout() {
        binding.aboutVersionText.text = getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        binding.checkUpdateBtn.setOnClickListener { UpdateChecker.checkNow(this) }
        binding.supportEmailBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_email_subject, BuildConfig.VERSION_NAME))
            try { startActivity(i) } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.about_no_email_app, SUPPORT_EMAIL), Toast.LENGTH_LONG).show()
            }
        }
        binding.githubBtn.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) } catch (_: Exception) {
                Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        ThemeHelper.watchAuto(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { if (page != PAGE_HUB) showPage(PAGE_HUB, true) else finish() }
        onBackPressedDispatcher.addCallback(this, backToCards)
        binding.cardGeneral.setOnClickListener { showPage("general", true) }
        binding.cardPlayer.setOnClickListener { showPage("player", true) }
        binding.cardScreensaver.setOnClickListener { showPage("screensaver", true) }
        binding.cardOnline.setOnClickListener { showPage(PAGE_ONLINE, true) }
        binding.cardAdvanced.setOnClickListener { showPage("advanced", true) }
        showPage(savedInstanceState?.getString(STATE_PAGE) ?: intent.getStringExtra(EXTRA_PAGE) ?: PAGE_HUB, false)

        setupCache()
        setupAbout()
        // 1. General
        setupAppearance()
        setupLanguagePicker()
        // 1a. Google account + Drive backup
        setupDriveSection()
        // 1b. Online music source (YouTube key)
        setupOnlineMusicSection()
        // 1c. Player cover
        setupCoverSection()
        setupCoverShapeSection()
        // 2. Screensaver display (incl. auto-open while charging)
        setupScreensaverSection()
        for ((sw, get, set) in listOf(
            Triple(binding.ytNoShortsSwitch, { MusicPrefs.ytBlockShorts(this) }, { v: Boolean -> MusicPrefs.setYtBlockShorts(this, v) }),
            Triple(binding.ytLocalSwitch, { MusicPrefs.ytLocal(this) }, { v: Boolean -> MusicPrefs.setYtLocal(this, v) })
        )) {
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = get()
            sw.setOnCheckedChangeListener { _, checked -> set(checked) }
        }
        binding.saverAutoSwitch.setOnCheckedChangeListener(null)
        binding.saverAutoSwitch.isChecked = MusicPrefs.saverAuto(this)
        binding.saverAutoSwitch.setOnCheckedChangeListener { _, checked -> MusicPrefs.setSaverAuto(this, checked) }
        binding.coverThemeSwitch.setOnCheckedChangeListener(null)
        binding.coverThemeSwitch.isChecked = MusicPrefs.coverTheme(this)
        binding.coverThemeSwitch.setOnCheckedChangeListener { _, checked -> MusicPrefs.setCoverTheme(this, checked) }
        binding.skipSegmentsSwitch.setOnCheckedChangeListener(null)
        binding.skipSegmentsSwitch.isChecked = MusicPrefs.skipSegments(this)
        binding.skipSegmentsSwitch.setOnCheckedChangeListener { _, checked -> MusicPrefs.setSkipSegments(this, checked) }
        setupClockStyleSection()
        setupGreetingSection()
        setupNowPlayingSection()
        setupTasteSection()
        setupChargingSection()
        setupPresetNotifSection() // lives on the Advanced page; needs no engine, so it is wired here
        // 4. Advanced: wired up in onEngineReady(), once EffectService is bound.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page)
    }

    /** Shows the card list ([PAGE_HUB]) or one category's page, with the matching title in the top bar. */
    private fun showPage(key: String, animate: Boolean) {
        val pages = mapOf(
            "general" to (binding.pageGeneral to R.string.settings_group_general),
            "player" to (binding.pagePlayer to R.string.settings_group_player),
            "screensaver" to (binding.pageScreensaver to R.string.settings_group_screensaver_display),
            PAGE_ONLINE to (binding.pageOnline to R.string.settings_group_online),
            "advanced" to (binding.pageAdvanced to R.string.settings_group_advanced)
        )
        val target = pages[key]
        page = if (target == null) PAGE_HUB else key
        binding.hubGroup.visibility = if (target == null) View.VISIBLE else View.GONE
        for ((k, v) in pages) v.first.visibility = if (k == key) View.VISIBLE else View.GONE
        binding.settingsTitle.setText(target?.second ?: R.string.settings_title)
        binding.settingsScroll.scrollTo(0, 0)
        backToCards.isEnabled = target != null
        if (animate) {
            val shown: View = target?.first ?: binding.hubGroup
            val dx = (if (target != null) 40 else -40) * resources.displayMetrics.density
            shown.alpha = 0f
            shown.translationX = dx
            shown.animate().alpha(1f).translationX(0f).setDuration(180L).start()
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a system permission page: re-check what was granted.
        refreshNowPlayingStatus()
        refreshTasteStatus()
        refreshChargingStatus()
        refreshLastFmStatus() // e.g. back from approving the login in the browser
        refreshSpotifyStatus()
    }

    override fun onStart() {
        super.onStart()
        // EffectService is already running (MainActivity started it); this just
        // attaches to the existing instance, it never creates a second one.
        Intent(this, EffectService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    // === GENERAL: theme, accent color, language ==========================================

    /** Theme mode (System/Light/Dark) + accent color sub-theme. Doesn't need
     *  the bound EffectService, so it's wired up straight from onCreate()
     *  like the language picker, not gated behind onEngineReady(). */
    private fun setupAppearance() {
        val group = binding.themeModeGroup
        when (ThemeHelper.currentMode(this)) {
            ThemeHelper.MODE_LIGHT -> group.check(binding.themeLight.id)
            ThemeHelper.MODE_DARK -> group.check(binding.themeDark.id)
            else -> group.check(binding.themeSystemDefault.id)
        }
        // Auto day / night (below the accent colours): the clock decides, so the three manual choices are greyed out.
        fun showAuto(on: Boolean) {
            group.alpha = if (on) 0.4f else 1f
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !on
        }
        binding.themeAutoSwitch.setOnCheckedChangeListener(null)
        binding.themeAutoSwitch.isChecked = ThemeHelper.isAuto(this)
        showAuto(ThemeHelper.isAuto(this))
        binding.themeAutoSwitch.setOnCheckedChangeListener { _, on ->
            showAuto(on)
            ThemeHelper.setAuto(this, on) // recreates this screen if the theme has to change right now
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.themeLight.id -> ThemeHelper.MODE_LIGHT
                binding.themeDark.id -> ThemeHelper.MODE_DARK
                else -> ThemeHelper.MODE_SYSTEM
            }
            ThemeHelper.setMode(this, mode) // recreates this activity to apply immediately
        }

        setupAccentSwatches()
    }

    private fun setupAccentSwatches() {
        binding.accentSwatchContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val current = ThemeHelper.currentAccent(this)
        val swatchViews = mutableListOf<Pair<ThemeHelper.Accent, View>>()

        ThemeHelper.Accent.entries.forEach { accent ->
            val swatch = inflater.inflate(R.layout.item_accent_swatch, binding.accentSwatchContainer, false)
            val circle = swatch.findViewById<View>(R.id.swatchCircle)
            val check = swatch.findViewById<View>(R.id.swatchCheck)
            circle.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, accentColorRes(accent)))
            check.visibility = if (accent == current) View.VISIBLE else View.GONE
            swatch.contentDescription = getString(accentLabelRes(accent))

            swatch.setOnClickListener {
                ThemeHelper.setAccent(this, accent)
                swatchViews.forEach { (_, v) -> v.findViewById<View>(R.id.swatchCheck).visibility = View.GONE }
                check.visibility = View.VISIBLE
                recreate() // re-applies the new accent ThemeOverlay from onCreate()
            }

            binding.accentSwatchContainer.addView(swatch)
            swatchViews.add(accent to swatch)
        }
    }

    private fun accentColorRes(accent: ThemeHelper.Accent) = when (accent) {
        ThemeHelper.Accent.DEFAULT -> R.color.accent_default
        ThemeHelper.Accent.RED -> R.color.accent_red
        ThemeHelper.Accent.YELLOW -> R.color.accent_yellow
        ThemeHelper.Accent.BLUE -> R.color.accent_blue
        ThemeHelper.Accent.ORANGE -> R.color.accent_orange
        ThemeHelper.Accent.GREEN -> R.color.accent_green
        ThemeHelper.Accent.PURPLE -> R.color.accent_purple
    }

    private fun accentLabelRes(accent: ThemeHelper.Accent) = when (accent) {
        ThemeHelper.Accent.DEFAULT -> R.string.accent_default
        ThemeHelper.Accent.RED -> R.string.accent_red
        ThemeHelper.Accent.YELLOW -> R.string.accent_yellow
        ThemeHelper.Accent.BLUE -> R.string.accent_blue
        ThemeHelper.Accent.ORANGE -> R.string.accent_orange
        ThemeHelper.Accent.GREEN -> R.string.accent_green
        ThemeHelper.Accent.PURPLE -> R.string.accent_purple
    }

    private fun setupLanguagePicker() {
        val group = binding.languageGroup
        when (LocaleHelper.currentTag()) {
            LocaleHelper.TAG_ENGLISH -> group.check(binding.langEnglish.id)
            LocaleHelper.TAG_INDONESIAN -> group.check(binding.langIndonesian.id)
            else -> group.check(binding.langSystemDefault.id)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val tag = when (checkedId) {
                binding.langEnglish.id -> LocaleHelper.TAG_ENGLISH
                binding.langIndonesian.id -> LocaleHelper.TAG_INDONESIAN
                else -> LocaleHelper.TAG_SYSTEM_DEFAULT
            }
            LocaleHelper.apply(tag) // recreates this activity to apply immediately
        }
    }

    // === PLAYER: custom cover photo ======================================================

    private fun setupCoverSection() {
        binding.chooseCoverBtn.setOnClickListener { pickCoverLauncher.launch(arrayOf("image/*")) }
        binding.removeCoverBtn.setOnClickListener {
            CoverUi.clearCustom(this)
            refreshCoverStatus()
        }
        refreshCoverStatus()
    }

    private fun refreshCoverStatus() {
        val bmp = CoverUi.custom(this)
        if (bmp != null) {
            binding.coverPreview.imageTintList = null
            binding.coverPreview.setImageBitmap(bmp)
        } else {
            binding.coverPreview.setImageDrawable(null)
        }
        binding.coverStatusText.setText(if (bmp != null) R.string.cover_status_set else R.string.cover_status_none)
        binding.removeCoverBtn.isEnabled = bmp != null
        binding.removeCoverBtn.alpha = if (bmp != null) 1.0f else 0.5f
    }

    // === PLAYER: cover frame shape (square / circle) =====================================

    private fun setupCoverShapeSection() {
        binding.coverSquareBtn.setOnClickListener { MusicPrefs.setCoverCircle(this, false); refreshCoverShapeButtons() }
        binding.coverCircleBtn.setOnClickListener { MusicPrefs.setCoverCircle(this, true); refreshCoverShapeButtons() }
        refreshCoverShapeButtons()
        binding.coverAnimSwitch.setOnCheckedChangeListener(null)
        binding.coverAnimSwitch.isChecked = MusicPrefs.coverAnim(this)
        binding.coverAnimSwitch.setOnCheckedChangeListener { _, checked -> MusicPrefs.setCoverAnim(this, checked) }
    }

    /** Selected shape = accent-filled button, the other stays outlined (same look as the clock style). */
    private fun refreshCoverShapeButtons() {
        val circle = MusicPrefs.coverCircle(this)
        val accentColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorAccent)
        val onAccentColor = MaterialColors.getColor(binding.root, R.attr.colorOnAccent)
        val defaultTextColor = MaterialColors.getColor(binding.root, R.attr.colorTextPrimary)
        listOf(binding.coverSquareBtn to !circle, binding.coverCircleBtn to circle).forEach { (button, selected) ->
            if (selected) {
                button.backgroundTintList = ColorStateList.valueOf(accentColor)
                button.setTextColor(onAccentColor)
            } else {
                button.backgroundTintList = null
                button.setTextColor(defaultTextColor)
            }
        }
    }

    // === SCREENSAVER DISPLAY: background photo / video / animated ========================

    /** Background video for the Screensaver page. Doesn't touch EffectService
     *  at all, so -- like language/appearance above -- it's wired up straight
     *  from onCreate() instead of waiting for onEngineReady(). */
    private fun setupScreensaverSection() {
        refreshScreensaverStatus()
        binding.chooseScreensaverVideoBtn.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*", "image/*"))
        }
        binding.removeScreensaverVideoBtn.setOnClickListener {
            releaseGrant(ScreensaverPrefs.videoUri(this))
            ScreensaverPrefs.setMedia(this, null, ScreensaverPrefs.TYPE_VIDEO)
            refreshScreensaverStatus()
        }
        binding.animatedRainBtn.setOnClickListener { selectAnimated(ScreensaverPrefs.STYLE_RAIN) }
        binding.animatedNightSkyBtn.setOnClickListener { selectAnimated(ScreensaverPrefs.STYLE_NIGHT_SKY) }
        binding.animatedStarfieldBtn.setOnClickListener { selectAnimated(ScreensaverPrefs.STYLE_STARFIELD) }
    }

    private fun selectAnimated(style: String) {
        releaseGrant(ScreensaverPrefs.videoUri(this))
        ScreensaverPrefs.setAnimated(this, style)
        refreshScreensaverStatus()
    }

    private fun releaseGrant(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Grant was never persisted / already gone -- nothing to release.
        }
    }

    /** Photo or video? Some providers (cloud/gallery apps) return a null or
     *  generic MIME type from getType(); the old code treated that as
     *  "video", so those photos went to the VideoView and just failed. Falls
     *  back to the file extension, then to actually probing the bytes. */
    private fun detectMediaType(uri: Uri): String {
        fun classify(mime: String?): String? = when {
            mime == null -> null
            mime.startsWith("image/") -> ScreensaverPrefs.TYPE_IMAGE
            mime.startsWith("video/") -> ScreensaverPrefs.TYPE_VIDEO
            else -> null
        }
        classify(contentResolver.getType(uri))?.let { return it }

        val ext = displayNameOf(uri).substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            classify(MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext))?.let { return it }
        }
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) ScreensaverPrefs.TYPE_IMAGE
            else ScreensaverPrefs.TYPE_VIDEO
        } catch (e: Exception) {
            ScreensaverPrefs.TYPE_VIDEO
        }
    }

    private fun refreshScreensaverStatus() {
        val mediaType = ScreensaverPrefs.mediaType(this)
        val uriString = ScreensaverPrefs.videoUri(this)

        binding.screensaverVideoStatusText.text = when {
            mediaType == ScreensaverPrefs.TYPE_ANIMATED ->
                getString(R.string.animated_selected_desc, animatedStyleLabel(ScreensaverPrefs.animatedStyle(this)))
            uriString.isNullOrBlank() -> getString(R.string.no_video_selected)
            mediaType == ScreensaverPrefs.TYPE_IMAGE ->
                getString(R.string.image_selected_desc, displayNameOf(Uri.parse(uriString)))
            else -> getString(R.string.video_selected_desc, displayNameOf(Uri.parse(uriString)))
        }

        val hasBackground = ScreensaverPrefs.hasBackground(this)
        binding.removeScreensaverVideoBtn.isEnabled = hasBackground
        binding.removeScreensaverVideoBtn.alpha = if (hasBackground) 1.0f else 0.5f

        highlightSelectedAnimatedButton(mediaType, ScreensaverPrefs.animatedStyle(this))
    }

    /** Fills in the currently-selected animated style's button (if any) with
     *  the app's active accent color so it reads as "active", and resets the
     *  others back to plain outlined buttons. Resolves colors through the
     *  current theme (same attrs the layout itself uses) so this keeps
     *  matching Light/Dark mode and whichever accent sub-theme is active,
     *  instead of a color baked in at build time. */
    private fun highlightSelectedAnimatedButton(mediaType: String, style: String) {
        val buttons = mapOf(
            ScreensaverPrefs.STYLE_RAIN to binding.animatedRainBtn,
            ScreensaverPrefs.STYLE_NIGHT_SKY to binding.animatedNightSkyBtn,
            ScreensaverPrefs.STYLE_STARFIELD to binding.animatedStarfieldBtn
        )
        val selected = if (mediaType == ScreensaverPrefs.TYPE_ANIMATED) style else null
        val accentColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorAccent)
        val onAccentColor = MaterialColors.getColor(binding.root, R.attr.colorOnAccent)
        val defaultTextColor = MaterialColors.getColor(binding.root, R.attr.colorTextPrimary)
        buttons.forEach { (buttonStyle, button) ->
            if (buttonStyle == selected) {
                button.backgroundTintList = ColorStateList.valueOf(accentColor)
                button.iconTint = ColorStateList.valueOf(onAccentColor)
            } else {
                button.backgroundTintList = null
                button.iconTint = ColorStateList.valueOf(defaultTextColor)
            }
        }
    }

    private fun animatedStyleLabel(style: String) = when (style) {
        ScreensaverPrefs.STYLE_RAIN -> getString(R.string.animated_rain)
        ScreensaverPrefs.STYLE_STARFIELD -> getString(R.string.animated_starfield)
        else -> getString(R.string.animated_night_sky)
    }

    /** Best-effort human-readable filename for the status line -- falls back
     *  to the raw Uri if the provider doesn't expose OpenableColumns.DISPLAY_NAME. */
    private fun displayNameOf(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment ?: uri.toString()
        } catch (e: Exception) {
            uri.lastPathSegment ?: uri.toString()
        }
    }

    // === SCREENSAVER DISPLAY: clock style (digital / analog) =============================

    private fun setupClockStyleSection() {
        binding.clockDigitalBtn.setOnClickListener { selectClockStyle(ScreensaverPrefs.CLOCK_DIGITAL) }
        binding.clockAnalogBtn.setOnClickListener { selectClockStyle(ScreensaverPrefs.CLOCK_ANALOG) }
        refreshClockStyleButtons()
    }

    private fun selectClockStyle(style: String) {
        ScreensaverPrefs.setClockStyle(this, style)
        refreshClockStyleButtons()
    }

    /** Same idea as [highlightSelectedAnimatedButton]: fill the selected
     *  button with the theme's accent color, leave the other outlined. */
    private fun refreshClockStyleButtons() {
        val selected = ScreensaverPrefs.clockStyle(this)
        val accentColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorAccent)
        val onAccentColor = MaterialColors.getColor(binding.root, R.attr.colorOnAccent)
        val defaultTextColor = MaterialColors.getColor(binding.root, R.attr.colorTextPrimary)
        mapOf(
            ScreensaverPrefs.CLOCK_DIGITAL to binding.clockDigitalBtn,
            ScreensaverPrefs.CLOCK_ANALOG to binding.clockAnalogBtn
        ).forEach { (style, button) ->
            if (style == selected) {
                button.backgroundTintList = ColorStateList.valueOf(accentColor)
                button.setTextColor(onAccentColor)
            } else {
                button.backgroundTintList = null
                button.setTextColor(defaultTextColor)
            }
        }
    }

    // === ONLINE MUSIC SOURCE: YouTube API key ============================================

    /** Same as the greeting field: restore first, then save every edit immediately. */
    private fun setupOnlineMusicSection() {
        binding.youtubeKeyInput.setText(MusicPrefs.youtubeKey(this))
        binding.youtubeKeyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                MusicPrefs.setYoutubeKey(this@SettingsActivity, s?.toString().orEmpty())
            }
        })
        setupAiSection()
        setupDiscordSection()
        setupSpotifySection()
        setupPrivateSection()
        setupKeyLinks()
        setupOnlineAudio()
        setupLastFmSection()
        bindPref(binding.acrHostInput, MusicPrefs.acrHost(this)) { MusicPrefs.setAcrHost(this, it) }
        bindPref(binding.acrKeyInput, MusicPrefs.acrKey(this)) { MusicPrefs.setAcrKey(this, it) }
        bindPref(binding.acrSecretInput, MusicPrefs.acrSecret(this)) { MusicPrefs.setAcrSecret(this, it) }
    }

    // === AI: provider + key + model =======================================================

    private fun aiProviderButtons() = mapOf(
        AiProvider.GEMINI to binding.aiProviderGemini,
        AiProvider.OPENAI to binding.aiProviderOpenai,
        AiProvider.ANTHROPIC to binding.aiProviderAnthropic
    )

    /** Provider buttons first, then the key / model fields of the chosen provider (each provider keeps
     *  its own key and model). Edits are saved as you type; any change drops the cached AI picks. */
    /** Opens [url] in the browser; with no browser installed the address is shown instead. */
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, url, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Online music: audio quality, which tabs Search shows, Jamendo key. */
    private fun setupOnlineAudio() {
        val quality = findViewById<TextView>(R.id.audioQualityValue)
        val tabs = findViewById<TextView>(R.id.searchTabsValue)
        val jamendo = findViewById<TextView>(R.id.jamendoKeyValue)
        fun refresh() {
            quality.text = StreamQuality.summary(this)
            tabs.text = SearchTabs.summary(this)
            jamendo.text = if (OnlineKeys.jamendo(this).isBlank()) getString(R.string.jamendo_key_not_set) else getString(R.string.jamendo_key_set)
        }
        refresh()
        findViewById<View>(R.id.audioQualityRow).setOnClickListener { StreamQuality.showDialog(this) { refresh() } }
        findViewById<View>(R.id.searchTabsRow).setOnClickListener { SearchTabs.showDialog(this) { refresh() } }
        findViewById<View>(R.id.jamendoKeyRow).setOnClickListener { OnlineKeys.showJamendoDialog(this) { refresh() } }
    }

    /** "Get a key" buttons of the other online services (the AI one follows the chosen provider, see refreshAiFields). */
    private fun setupKeyLinks() {
        binding.youtubeGetKeyLink.text = getString(R.string.get_key_youtube)
        binding.youtubeGetKeyLink.setOnClickListener {
            openLink("https://console.cloud.google.com/apis/library/youtube.googleapis.com")
        }
        binding.lastfmGetKeyLink.text = getString(R.string.get_key_lastfm)
        binding.lastfmGetKeyLink.setOnClickListener { openLink("https://www.last.fm/api/account/create") }
        binding.acrGetKeyLink.text = getString(R.string.get_key_acr)
        binding.acrGetKeyLink.setOnClickListener { openLink("https://console.acrcloud.com") }
    }

    // ------------------------------------------------------------------ Spotify

    private fun setupPrivateSection() {
        binding.privateSwitch.isChecked = MusicPrefs.privateMode(this)
        binding.privateSwitch.setOnCheckedChangeListener { _, on ->
            MusicPrefs.setPrivateMode(this, on)
            android.widget.Toast.makeText(this, if (on) R.string.private_mode_on else R.string.private_mode_off, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSpotifySection() {
        binding.spotifyClientInput.setText(SpotifyAuth.clientId(this))
        binding.spotifyClientInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                SpotifyAuth.setClientId(this@SettingsActivity, s?.toString().orEmpty())
                refreshSpotifyStatus()
            }
        })
        binding.spotifyDashLink.text = getString(R.string.spotify_dashboard)
        binding.spotifyDashLink.setOnClickListener { openLink("https://developer.spotify.com/dashboard") }
        binding.spotifyEqSwitch.isChecked = SpotifyAuth.eqEnabled(this)
        binding.spotifyEqSwitch.setOnCheckedChangeListener { _, on -> SpotifyAuth.setEqEnabled(this, on) }
        binding.spotifyConnectButton.setOnClickListener {
            when {
                SpotifyAuth.isConnected(this) -> { SpotifyAuth.disconnect(this); refreshSpotifyStatus() }
                SpotifyAuth.clientId(this).isBlank() ->
                    android.widget.Toast.makeText(this, R.string.spotify_need_connect, android.widget.Toast.LENGTH_LONG).show()
                else -> try { startActivity(SpotifyAuth.loginIntent(this)) } catch (e: Exception) {
                    android.widget.Toast.makeText(this, getString(R.string.spotify_error, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        binding.spotifyImportButton.setOnClickListener { startSpotifyImport() }
        binding.spotifyPrevButton.setOnClickListener { SpotifyRemote.previous(this) }
        binding.spotifyToggleButton.setOnClickListener { SpotifyRemote.playPause(this) }
        binding.spotifyNextButton.setOnClickListener { SpotifyRemote.next(this) }
        refreshSpotifyStatus()
    }

    private fun refreshSpotifyStatus() {
        if (SpotifyImport.running) return // the import shows its own progress here
        val connected = SpotifyAuth.isConnected(this)
        binding.spotifyConnectButton.setText(if (connected) R.string.spotify_disconnect else R.string.spotify_connect)
        binding.spotifyStatus.setText(
            when {
                SpotifyAuth.clientId(this).isBlank() -> R.string.spotify_status_need_id
                connected -> R.string.spotify_status_connected
                else -> R.string.spotify_status_ready
            }
        )
    }

    private fun spotifyToast(msg: String) =
        android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()

    /** Lists the Spotify playlists, lets the user tick which to copy, then imports them in the background. */
    private fun startSpotifyImport() {
        if (!SpotifyAuth.isConnected(this)) { spotifyToast(getString(R.string.spotify_need_connect)); return }
        if (SpotifyImport.running) { spotifyToast(getString(R.string.spotify_import_busy)); return }
        val app = applicationContext
        Thread {
            val lists = try { SpotifyApi.playlists(app) } catch (e: Exception) {
                runOnUiThread { spotifyToast(getString(R.string.spotify_error, e.message ?: "")) }
                return@Thread
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                // Row 0 is "Liked songs", then the playlists.
                val names = arrayOf(getString(R.string.spotify_liked_pick)) + lists.map { it.name }.toTypedArray()
                val checked = BooleanArray(names.size) { true }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.spotify_import_pick)
                    .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton(R.string.spotify_import_start) { _, _ ->
                        val picks = lists.filterIndexed { i, _ -> checked[i + 1] }
                        runSpotifyImport(picks, checked[0])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    private fun runSpotifyImport(picks: List<SpotifyApi.Pl>, liked: Boolean) {
        if (picks.isEmpty() && !liked) return
        val app = applicationContext
        val likedName = getString(R.string.spotify_liked)
        Thread {
            val result = try {
                SpotifyImport.run(app, picks, liked, likedName) { done, total, name ->
                    runOnUiThread { if (!isFinishing) binding.spotifyStatus.text = getString(R.string.spotify_import_progress, done, total, name) }
                }
            } catch (e: Exception) {
                runOnUiThread { spotifyToast(getString(R.string.spotify_error, e.message ?: "")); refreshSpotifyStatus() }
                return@Thread
            }
            runOnUiThread {
                spotifyToast(getString(R.string.spotify_import_done, result.playlists, result.songs, result.missed))
                if (!isFinishing) refreshSpotifyStatus()
            }
        }.start()
    }

    private fun setupDiscordSection() {
        binding.discordSwitch.setOnCheckedChangeListener(null)
        binding.discordSwitch.isChecked = DiscordPresence.enabled(this)
        binding.discordAppIdInput.setText(DiscordPresence.appId(this))
        binding.discordPortalLink.text = getString(R.string.discord_get_id)
        binding.discordPortalLink.setOnClickListener { openLink("https://discord.com/developers/applications") }
        fun status() {
            binding.discordStatus.setText(
                when {
                    DiscordPresence.appId(this).isBlank() -> R.string.discord_status_need_id
                    DiscordPresence.enabled(this) && !DiscordPresence.sdkAvailable() -> R.string.discord_status_no_sdk
                    else -> R.string.discord_status_ready
                }
            )
        }
        status()
        binding.discordSwitch.setOnCheckedChangeListener { _, checked ->
            DiscordPresence.setEnabled(this, checked)
            DiscordPresence.refresh(this)
            status()
        }
        binding.discordAppIdInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                DiscordPresence.setAppId(this@SettingsActivity, s?.toString().orEmpty())
                DiscordPresence.refresh(this@SettingsActivity)
                status()
            }
        })
    }

    private fun setupAiSection() {
        refreshAiFields() // before the watchers exist, so restoring the text saves nothing
        bindAiWatcher(binding.aiKeyInput) { MusicPrefs.setAiKey(this, it); AiLoad.reset() }
        bindAiWatcher(binding.aiModelInput) { MusicPrefs.setAiModel(this, it); AiLoad.reset() }
        binding.aiBalanceSwitch.setOnCheckedChangeListener(null)
        binding.aiBalanceSwitch.isChecked = MusicPrefs.aiBalance(this)
        binding.aiBalanceSwitch.setOnCheckedChangeListener { _, checked ->
            MusicPrefs.setAiBalance(this, checked)
            AiRecommender.clearCache(this)
        }
        aiProviderButtons().forEach { (provider, button) ->
            button.setOnClickListener {
                MusicPrefs.setAiProvider(this, provider)
                AiRecommender.clearCache(this)
                refreshAiFields()
            }
        }
    }

    private fun bindAiWatcher(field: android.widget.EditText, save: (String) -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                save(s?.toString().orEmpty())
                AiRecommender.clearCache(this@SettingsActivity) // a different key / model: fetch fresh picks
            }
        })
    }

    private fun refreshAiFields() {
        val provider = MusicPrefs.aiProvider(this)
        binding.aiKeyInput.setText(MusicPrefs.aiKey(this, provider))
        binding.aiKeyInput.setHint(provider.keyHintRes)
        binding.aiGetKeyLink.text = getString(R.string.ai_get_key, provider.label)
        binding.aiGetKeyLink.setOnClickListener { openLink(provider.keyUrl) }
        binding.aiModelInput.setText(MusicPrefs.aiModelCustom(this, provider))
        binding.aiModelInput.hint = getString(R.string.ai_model_hint, provider.defaultModel)
        val accentColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorAccent)
        val onAccentColor = MaterialColors.getColor(binding.root, R.attr.colorOnAccent)
        val defaultTextColor = MaterialColors.getColor(binding.root, R.attr.colorTextPrimary)
        aiProviderButtons().forEach { (p, button) ->
            if (p == provider) {
                button.backgroundTintList = ColorStateList.valueOf(accentColor)
                button.setTextColor(onAccentColor)
            } else {
                button.backgroundTintList = null
                button.setTextColor(defaultTextColor)
            }
        }
    }

    // === LAST.FM: scrobbling + taste ======================================================

    private var lastFmBusy = false

    private fun setupLastFmSection() {
        bindPref(binding.lastfmKeyInput, LastFm.apiKey(this)) { LastFm.setApiKey(this, it) }
        bindPref(binding.lastfmSecretInput, LastFm.apiSecret(this)) { LastFm.setApiSecret(this, it) }

        // The two switches get their listeners in refreshLastFmStatus().
        binding.lastfmConnectBtn.setOnClickListener {
            if (LastFm.hasPendingAuth(this)) finishLastFmLogin() else startLastFmLogin()
        }
        binding.lastfmDisconnectBtn.setOnClickListener {
            if (LastFm.isConnected(this)) {
                LastFm.disconnect(this)
                Toast.makeText(this, R.string.lastfm_disconnected_toast, Toast.LENGTH_SHORT).show()
            } else {
                LastFm.cancelAuth(this) // "Cancel" while waiting for the browser approval
            }
            refreshLastFmStatus()
        }
        refreshLastFmStatus()
    }

    private fun lastFmError(e: Exception): String = when (e) {
        is LastFm.LastFmException ->
            if (LastFm.errorRes(e) == R.string.lastfm_err_generic) getString(R.string.lastfm_err_generic, e.message.orEmpty())
            else getString(LastFm.errorRes(e))
        else -> getString(R.string.lastfm_err_generic, e.message.orEmpty())
    }

    /** Step 1: get a token and send the user to last.fm to approve it. */
    private fun startLastFmLogin() {
        if (lastFmBusy) return
        if (!LastFm.hasKeys(this)) {
            Toast.makeText(this, R.string.lastfm_need_keys, Toast.LENGTH_LONG).show()
            return
        }
        lastFmBusy = true
        binding.lastfmConnectBtn.isEnabled = false
        Thread {
            var url: String? = null
            var error = ""
            try {
                url = LastFm.startAuth(this)
            } catch (e: Exception) {
                error = lastFmError(e)
            }
            runOnUiThread {
                lastFmBusy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                binding.lastfmConnectBtn.isEnabled = true
                val authUrl = url // copy: `url` is written from the worker thread, so it cannot be smart-cast
                if (authUrl != null) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(authUrl)))
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.lastfm_err_no_browser, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
                refreshLastFmStatus()
            }
        }.start()
    }

    /** Step 2: the user says they approved it in the browser; swap the token for a session. */
    private fun finishLastFmLogin() {
        if (lastFmBusy) return
        lastFmBusy = true
        binding.lastfmConnectBtn.isEnabled = false
        Thread {
            var error = ""
            try {
                LastFm.finishAuth(this)
            } catch (e: Exception) {
                error = lastFmError(e)
            }
            runOnUiThread {
                lastFmBusy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                binding.lastfmConnectBtn.isEnabled = true
                if (error.isEmpty()) {
                    Toast.makeText(this, getString(R.string.lastfm_connected_toast, LastFm.username(this)), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
                refreshLastFmStatus()
            }
        }.start()
    }

    private fun refreshLastFmStatus() {
        val connected = LastFm.isConnected(this)
        val pending = !connected && LastFm.hasPendingAuth(this)
        binding.lastfmConnectedGroup.visibility = if (connected) View.VISIBLE else View.GONE
        binding.lastfmConnectBtn.visibility = if (connected) View.GONE else View.VISIBLE
        binding.lastfmConnectBtn.setText(if (pending) R.string.lastfm_finish_btn else R.string.lastfm_connect_btn)
        binding.lastfmDisconnectBtn.visibility = if (connected || pending) View.VISIBLE else View.GONE
        binding.lastfmDisconnectBtn.setText(if (connected) R.string.lastfm_disconnect_btn else R.string.lastfm_cancel_btn)
        val queued = if (connected) LastFm.queueSize(this) else 0
        binding.lastfmStatusText.text = when {
            connected && queued > 0 ->
                getString(R.string.lastfm_status_on, LastFm.username(this)) + "\n" + getString(R.string.lastfm_status_queue, queued)
            connected -> getString(R.string.lastfm_status_on, LastFm.username(this))
            pending -> getString(R.string.lastfm_status_pending)
            else -> getString(R.string.lastfm_status_off)
        }
        binding.lastfmScrobbleSwitch.setOnCheckedChangeListener(null)
        binding.lastfmScrobbleSwitch.isChecked = LastFm.scrobbleOn(this)
        binding.lastfmScrobbleSwitch.setOnCheckedChangeListener { _, checked ->
            LastFm.setScrobbleOn(this, checked)
            if (checked) LastFm.flushAsync(this)
        }
        binding.lastfmTasteSwitch.setOnCheckedChangeListener(null)
        binding.lastfmTasteSwitch.isChecked = LastFm.tasteOn(this)
        binding.lastfmTasteSwitch.setOnCheckedChangeListener { _, checked ->
            LastFm.setTasteOn(this, checked)
            AiRecommender.clearCache(this)
        }
    }

    /** Restores [initial] first, then saves every edit immediately. */
    private fun bindPref(field: android.widget.EditText, initial: String, save: (String) -> Unit) {
        field.setText(initial)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = save(s?.toString().orEmpty())
        })
    }

    // === ACCOUNT: Google sign-in + Drive backup ==========================================

    private var driveBusy = false

    private fun setupDriveSection() {
        binding.driveSignInBtn.setOnClickListener {
            try {
                googleSignInLauncher.launch(DriveSync.signInClient(this).signInIntent)
            } catch (e: Throwable) {
                Toast.makeText(this, getString(R.string.drive_err_generic, e.message.orEmpty()), Toast.LENGTH_LONG).show()
            }
        }
        binding.driveSignOutBtn.setOnClickListener {
            DriveSync.signOut(this) {
                Toast.makeText(this, R.string.drive_signed_out_toast, Toast.LENGTH_SHORT).show()
                refreshDriveStatus()
            }
        }
        binding.driveSyncNowBtn.setOnClickListener { runDriveSync() }
        binding.driveImportBtn.setOnClickListener { runDriveImport() }
        binding.driveAutoSyncSwitch.setOnCheckedChangeListener { _, checked ->
            DriveSync.setAutoSync(this, checked)
        }
        refreshDriveStatus()
    }

    private fun handleGoogleSignInResult(data: Intent?) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (!DriveSync.hasDrivePermission(account)) {
                // The user unticked the Drive permission on the consent screen.
                DriveSync.signInClient(this).signOut()
                Toast.makeText(this, R.string.drive_err_no_permission, Toast.LENGTH_LONG).show()
                return
            }
            DriveSync.onSignedIn(this, account)
            Toast.makeText(this, R.string.drive_signed_in_toast, Toast.LENGTH_SHORT).show()
            refreshDriveStatus()
            runDriveSync() // first sync: merges with any backup already on Drive
        } catch (e: com.google.android.gms.common.api.ApiException) {
            when (e.statusCode) {
                12501, 16 -> // user backed out
                    Toast.makeText(this, R.string.drive_err_signin_cancelled, Toast.LENGTH_LONG).show()
                7, 15 -> // network error / timeout
                    Toast.makeText(this, R.string.drive_err_network, Toast.LENGTH_LONG).show()
                else -> {
                    // Mostly code 10 / 12500: the OAuth client in Google Cloud Console does not match this APK.
                    // Show exactly what this APK is, so it can be compared with the console.
                    val sha1 = DriveSync.signingSha1(this)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.drive_section)
                        .setMessage(getString(R.string.drive_err_signin_config, e.statusCode, packageName, sha1))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.drive_copy_sha1) { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("SHA-1", sha1))
                        }
                        .show()
                }
            }
        }
    }

    /** Manual, one-way: pulls what is on Drive into this phone (merge) and uploads nothing. */
    private fun runDriveImport() {
        if (driveBusy) return
        driveBusy = true
        binding.driveImportBtn.isEnabled = false
        DriveSync.importAsync(this) { outcome ->
            driveBusy = false
            if (isDestroyed || isFinishing) return@importAsync
            binding.driveImportBtn.isEnabled = true
            val text = if (outcome.detail.isNotEmpty() &&
                (outcome.resId == R.string.drive_err_http || outcome.resId == R.string.drive_err_generic)
            ) getString(outcome.resId, outcome.detail) else getString(outcome.resId)
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            refreshDriveStatus()
        }
    }

    private fun runDriveSync() {
        if (driveBusy) return
        driveBusy = true
        binding.driveSyncNowBtn.isEnabled = false
        binding.driveLastSyncText.visibility = View.VISIBLE
        binding.driveLastSyncText.setText(R.string.drive_syncing)
        DriveSync.syncAsync(this) { outcome ->
            driveBusy = false
            if (isDestroyed || isFinishing) return@syncAsync
            binding.driveSyncNowBtn.isEnabled = true
            val consent = outcome.consentIntent
            if (consent != null && !consentRetried) {
                // Google wants the user to approve the Drive permission: show it, then sync again (once).
                consentRetried = true
                try {
                    driveConsentLauncher.launch(consent)
                    return@syncAsync
                } catch (e: Throwable) { /* fall through to the normal error toast */ }
            }
            consentRetried = false
            val text = if (outcome.detail.isNotEmpty() &&
                (outcome.resId == R.string.drive_err_http || outcome.resId == R.string.drive_err_generic)
            ) getString(outcome.resId, outcome.detail) else getString(outcome.resId)
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            refreshDriveStatus()
        }
    }

    private fun refreshDriveStatus() {
        val signedIn = DriveSync.isSignedIn(this)
        binding.driveSignInBtn.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.driveSignedInGroup.visibility = if (signedIn) View.VISIBLE else View.GONE
        if (!signedIn) {
            binding.driveStatusText.setText(R.string.drive_status_signed_out)
            binding.driveLastSyncText.visibility = View.GONE
            return
        }
        binding.driveStatusText.text = getString(R.string.drive_status_signed_in, DriveSync.email(this))
        val last = DriveSync.lastSyncTime(this)
        binding.driveLastSyncText.visibility = View.VISIBLE
        binding.driveLastSyncText.text = if (last > 0L)
            getString(R.string.drive_last_sync, android.text.format.DateUtils.getRelativeTimeSpanString(
                last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
        else getString(R.string.drive_never_synced)
        // Set the state before the listener could fire a redundant write.
        binding.driveAutoSyncSwitch.setOnCheckedChangeListener(null)
        binding.driveAutoSyncSwitch.isChecked = DriveSync.autoSync(this)
        binding.driveAutoSyncSwitch.setOnCheckedChangeListener { _, checked -> DriveSync.setAutoSync(this, checked) }
        binding.driveIncludeLocalSwitch.setOnCheckedChangeListener(null)
        binding.driveIncludeLocalSwitch.isChecked = DriveSync.includeLocal(this)
        binding.driveIncludeLocalSwitch.setOnCheckedChangeListener { _, checked -> DriveSync.setIncludeLocal(this, checked) }
    }

    // === SCREENSAVER DISPLAY: greeting text ==============================================

    /** Fills the greeting field from prefs BEFORE attaching the watcher (so
     *  restoring the text doesn't trigger a redundant save), then saves
     *  every edit immediately -- there's no separate Save button to forget. */
    private fun setupGreetingSection() {
        val input = binding.greetingInput
        input.setText(ScreensaverPrefs.greeting(this))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                ScreensaverPrefs.setGreeting(this@SettingsActivity, s?.toString().orEmpty())
            }
        })
    }

    // === SCREENSAVER DISPLAY: now-playing title (needs notification access) ==============

    private fun setupNowPlayingSection() {
        binding.nowPlayingPermissionBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.nowplaying_settings_failed, Toast.LENGTH_LONG).show()
            }
        }
        refreshNowPlayingStatus()
    }

    private fun refreshNowPlayingStatus() {
        val granted = NowPlayingTracker.isAccessGranted(this)
        binding.nowPlayingStatusText.setText(
            if (granted) R.string.nowplaying_status_ready else R.string.nowplaying_status_need_access
        )
        binding.nowPlayingPermissionBtn.visibility = if (granted) View.GONE else View.VISIBLE
    }

    // === TASTE SIGNALS: YouTube Music on this phone + YouTube account (see TasteSignals) ===========

    private val tasteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var tasteSyncing = false

    /** Back from the Google consent screen (only shown if the YouTube read-only scope was never allowed). */
    private val tasteConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) syncTasteAccount()
        }

    private fun setupTasteSection() {
        val openAccess = {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.nowplaying_settings_failed, Toast.LENGTH_LONG).show()
            }
        }
        binding.tasteExtSwitch.isChecked = TasteSignals.externalEnabled(this)
        binding.tasteExtSwitch.setOnCheckedChangeListener { _, checked ->
            TasteSignals.setExternalEnabled(this, checked)
            if (checked && !NowPlayingTracker.isAccessGranted(this)) openAccess()
            refreshTasteStatus()
        }
        binding.tasteExtAccessBtn.setOnClickListener { openAccess() }
        binding.tasteExtClearBtn.setOnClickListener {
            TasteSignals.clearExternal(this)
            Toast.makeText(this, R.string.taste_cleared, Toast.LENGTH_SHORT).show()
            refreshTasteStatus()
        }

        binding.tasteAccSwitch.isChecked = TasteSignals.accountEnabled(this)
        binding.tasteAccSwitch.setOnCheckedChangeListener { _, checked ->
            TasteSignals.setAccountEnabled(this, checked)
            if (checked) syncTasteAccount()
            refreshTasteStatus()
        }
        binding.tasteAccClearBtn.setOnClickListener {
            TasteSignals.clearAccount(this)
            Toast.makeText(this, R.string.taste_cleared, Toast.LENGTH_SHORT).show()
            refreshTasteStatus()
        }
        refreshTasteStatus()
    }

    private fun refreshTasteStatus() {
        // 1. other apps
        val extOn = TasteSignals.externalEnabled(this)
        val granted = NowPlayingTracker.isAccessGranted(this)
        val plays = TasteSignals.externalPlays(this)
        binding.tasteExtStatus.visibility = if (extOn) View.VISIBLE else View.GONE
        binding.tasteExtAccessBtn.visibility = if (extOn && !granted) View.VISIBLE else View.GONE
        binding.tasteExtClearBtn.visibility = if (plays > 0) View.VISIBLE else View.GONE
        if (extOn) {
            binding.tasteExtStatus.text = when {
                !granted -> getString(R.string.taste_ext_need_access)
                plays == 0 -> getString(R.string.taste_ext_waiting)
                else -> getString(
                    R.string.taste_ext_status, plays, TasteSignals.externalArtistCount(this),
                    android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(TasteSignals.externalSince(this)))
                )
            }
        }

        // 2. YouTube account
        val accOn = TasteSignals.accountEnabled(this)
        val syncedAt = TasteSignals.accountSyncedAt(this)
        binding.tasteAccStatus.visibility = if (accOn) View.VISIBLE else View.GONE
        binding.tasteAccClearBtn.visibility = if (syncedAt > 0L) View.VISIBLE else View.GONE
        if (accOn) {
            binding.tasteAccStatus.text = when {
                !YouTubeAccount.isSignedIn(this) -> getString(R.string.taste_acc_need_signin)
                tasteSyncing -> getString(R.string.taste_acc_syncing)
                syncedAt == 0L -> getString(R.string.taste_acc_never)
                else -> getString(
                    R.string.taste_acc_status,
                    TasteSignals.accountLikedArtists(this), TasteSignals.accountSubscriptions(this),
                    android.text.format.DateUtils.getRelativeTimeSpanString(
                        syncedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                    )
                )
            }
        }
    }

    /** Reads likes + subscriptions in the background; shows Google's consent screen if it is still needed. */
    private fun syncTasteAccount() {
        if (tasteSyncing || !YouTubeAccount.isSignedIn(this)) { refreshTasteStatus(); return }
        tasteSyncing = true
        refreshTasteStatus()
        val app = applicationContext
        tasteExecutor.execute {
            var consent: Intent? = null
            var ok = false
            try {
                TasteSignals.syncAccount(app)
                ok = true
            } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
                consent = e.intent
            } catch (e: Exception) {
                // reported below
            }
            runOnUiThread {
                tasteSyncing = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (!ok) {
                    val intent = consent
                    if (intent != null) {
                        try { tasteConsentLauncher.launch(intent) } catch (e: Exception) { android.util.Log.d("SettingsActivity", "ignored: " + e) }
                    } else {
                        Toast.makeText(this, R.string.taste_acc_failed, Toast.LENGTH_LONG).show()
                    }
                }
                refreshTasteStatus()
            }
        }
    }

    // === SCREENSAVER DISPLAY: auto-open while charging (needs notification + full-screen permissions) ===

    /** On/off switch for opening the Screensaver over the lock screen while
     *  charging. Turning it on also (re)starts EffectService, which is what
     *  listens for the charger, and walks the user through the two
     *  permissions the lock-screen launch needs. */
    private fun setupChargingSection() {
        val sw = binding.chargingScreensaverSwitch
        sw.isChecked = ScreensaverPrefs.autoOnCharge(this)
        sw.setOnCheckedChangeListener { _, checked ->
            ScreensaverPrefs.setAutoOnCharge(this, checked)
            syncServiceForeground()
            if (checked) requestChargingPermissions()
            refreshChargingStatus()
        }
        binding.chargingPermissionBtn.setOnClickListener { requestChargingPermissions() }
        refreshChargingStatus()
    }

    /** On/off switch for the ongoing effects notification (current preset + prev/next/off
     *  buttons). The service re-posts its notification right away via the sync action. */
    private fun setupPresetNotifSection() {
        val sw = binding.presetNotifSwitch
        sw.isChecked = ScreensaverPrefs.presetNotification(this)
        sw.setOnCheckedChangeListener { _, checked ->
            ScreensaverPrefs.setPresetNotification(this, checked)
            syncServiceForeground()
        }
    }

    private fun syncServiceForeground() {
        // Plain startService: the app is in the foreground here, and this must not
        // force a foreground notification when the effects are off.
        startService(
            Intent(this, EffectService::class.java).setAction(EffectService.ACTION_SYNC_FOREGROUND)
        )
    }

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    /** Android 14+ lets users revoke "full-screen notifications" per app, and
     *  they're off by default for non-calling/alarm apps. Below 14 it's
     *  granted by the manifest permission alone. */
    private fun fullScreenAllowed(): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun refreshChargingStatus() {
        if (!::binding.isInitialized) return
        val on = ScreensaverPrefs.autoOnCharge(this)
        val statusRes = when {
            !on -> null
            !notificationsAllowed() -> R.string.charging_status_need_notif
            !fullScreenAllowed() -> R.string.charging_status_need_fullscreen
            else -> R.string.charging_status_ready
        }
        binding.chargingStatusText.visibility = if (statusRes == null) View.GONE else View.VISIBLE
        if (statusRes != null) binding.chargingStatusText.setText(statusRes)
        val needsPermission = on && (!notificationsAllowed() || !fullScreenAllowed())
        binding.chargingPermissionBtn.visibility = if (needsPermission) View.VISIBLE else View.GONE
    }

    /** Asks for whichever permission is still missing, one at a time
     *  (notifications first, then full-screen notifications). */
    private fun requestChargingPermissions() {
        when {
            !notificationsAllowed() -> {
                val canPrompt = Build.VERSION.SDK_INT >= 33 &&
                    !ScreensaverPrefs.notifPermissionAsked(this) &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (canPrompt) {
                    ScreensaverPrefs.setNotifPermissionAsked(this)
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Already denied once (or blocked in settings): the system
                    // won't show the prompt again, so go to the settings page.
                    openSettingsPage(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }
            }
            !fullScreenAllowed() -> openSettingsPage(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    // === ADVANCED: auto preset per output + root mode (need the bound EffectService) =============================

    private fun em() = effectManager!!

    private fun onEngineReady() {
        setupOutputPresets()
        setupRootModeSwitch()
    }

    // --- Auto preset per output (speaker / jack / Bluetooth): moved here from the Equalizer screen ---

    private fun setupOutputPresets() {
        val sw = binding.outputPresetSwitch
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = OutputPresets.enabled(this)
        binding.outputPresetRows.visibility = if (sw.isChecked) View.VISIBLE else View.GONE
        buildOutputRows()
        sw.setOnCheckedChangeListener { _, on ->
            OutputPresets.setEnabled(this, on)
            if (on) OutputPresets.seedCurrent(this, em())
            binding.outputPresetRows.visibility = if (on) View.VISIBLE else View.GONE
            buildOutputRows()
        }
    }

    private fun buildOutputRows() {
        val box = binding.outputPresetRows
        box.removeAllViews()
        val now = OutputPresets.current(this)
        for (o in OutputPresets.Output.values()) {
            val row = layoutInflater.inflate(R.layout.item_output_preset, box, false)
            val label = getString(o.labelRes) + if (o == now) "  \u2022 " + getString(R.string.out_active) else ""
            row.findViewById<TextView>(R.id.outLabel).text = label
            row.findViewById<TextView>(R.id.outValue).text =
                OutputPresets.presetFor(this, o) ?: getString(R.string.out_no_change)
            row.setOnClickListener { chooseOutputPreset(o) }
            box.addView(row)
        }
    }

    private fun chooseOutputPreset(o: OutputPresets.Output) {
        val names = Presets.ALL.map { it.name }
        val items = (listOf(getString(R.string.out_no_change)) + names).toTypedArray()
        val current = OutputPresets.presetFor(this, o)
        val checked = if (current == null) 0 else names.indexOf(current).let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(this)
            .setTitle(o.labelRes)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                OutputPresets.setPresetFor(this, o, if (which == 0) null else names[which - 1])
                buildOutputRows()
                // Chosen for the output that is playing now: switch to it right away.
                if (o == OutputPresets.current(this)) effectService?.refreshOutputPreset(force = true)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupRootModeSwitch() {
        binding.rootSwitch.isChecked = em().isRootMode
        binding.rootStatusText.text = if (RootUtils.isDeviceRooted())
            getString(R.string.root_detected) else getString(R.string.root_not_detected)

        binding.rootSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!RootUtils.isDeviceRooted()) {
                    Toast.makeText(this, R.string.root_not_detected, Toast.LENGTH_LONG).show()
                    binding.rootSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                val granted = RootUtils.runAsRoot("id")
                if (!granted) {
                    Toast.makeText(this, R.string.root_permission_denied, Toast.LENGTH_LONG).show()
                    binding.rootSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                RootUtils.installEarlyBootHook()
            }
            em().isRootMode = checked
            // Re-apply loudness at whichever ceiling now applies -- setLoudness()
            // never shrinks the stored preference itself, only what's actually
            // sent to the effect (see AudioEffectManager doc), so MainActivity's
            // slider picks the right value/range back up next time it's shown.
            em().setLoudness(em().prefs.getInt("loudness_mb", 0))
            effectService?.syncWatchdog()
        }

        binding.checkEffectsBtn.setOnClickListener {
            if (!em().isRootMode) {
                Toast.makeText(this, R.string.enable_root_mode_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, R.string.diag_checking, Toast.LENGTH_SHORT).show()
            Thread {
                val dump = RootUtils.audioFlingerDump()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val r = em().diagnose(dump)
                    val msg = getString(when (r.verdict) {
                        AudioEffectManager.Diagnosis.NOT_CREATED -> R.string.diag_not_created
                        AudioEffectManager.Diagnosis.UNHEALTHY_FIXED -> R.string.diag_fixed
                        AudioEffectManager.Diagnosis.NOT_IN_SERVER -> R.string.diag_not_in_server
                        AudioEffectManager.Diagnosis.NO_DUMP -> R.string.diag_no_dump
                        AudioEffectManager.Diagnosis.OK -> R.string.diag_ok
                    }) + "\n\n" + getString(
                        R.string.diag_details_fmt, r.session,
                        getString(if (r.enabled) R.string.diag_on else R.string.diag_off), r.serverInstances
                    )
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.diag_title)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }.start()
        }

        binding.restartAudioServerBtn.setOnClickListener {
            if (!em().isRootMode) {
                Toast.makeText(this, R.string.enable_root_mode_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = RootUtils.restartAudioServer()
            Toast.makeText(this, if (ok) R.string.audioserver_restarted else R.string.action_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // === SHARED helpers ==================================================================

    private fun openSettingsPage(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.charging_settings_failed, Toast.LENGTH_LONG).show()
        }
    }

}
