package com.fxsoundlite.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.fxsoundlite.app.databinding.ActivityMainBinding

/**
 * Thin shell hosting a ViewPager2 with five pages (tabs switch instantly; only the Screensaver
 * page slides in/out and can be swiped away):
 *   0) HomeFragment       -- the EQ/presets/etc. controls (this used to be
 *                            everything MainActivity did directly).
 *   1) QuickFragment      -- reels-style feed of chorus previews (no mini player here).
 *   2) MusicFragment      -- local music library + player (MusicService).
 *   3) PlaylistFragment   -- create / search playlists, liked songs.
 *   4) ScreensaverFragment -- system clock over a looping background video.
 *
 * All the actual UI logic lives in those fragments now; this class only
 * owns things that must survive regardless of which page is showing: the
 * effect service's lifecycle-independent startup, and the accent-color
 * recreate() check.
 */
class MainActivity : AppCompatActivity(), MusicService.Listener {

    companion object {
        /** How long the mini player keeps the previous song's colour while the new cover is still loading. */
        private const val MINI_KEEP_MS = 2500L
        /** Sent by the widget's Screensaver shortcut: open straight on the Screensaver page. */
        const val ACTION_OPEN_SCREENSAVER = "com.fxsoundlite.app.ACTION_OPEN_SCREENSAVER"
        /** Sent by the launcher shortcuts (long-press on the app icon): Quick / Playlist pages. */
        const val ACTION_OPEN_QUICK = "com.fxsoundlite.app.ACTION_OPEN_QUICK"
        const val ACTION_OPEN_PLAYLIST = "com.fxsoundlite.app.ACTION_OPEN_PLAYLIST"

        /** Sent by the music notification: open straight on the Music page. */
        const val ACTION_OPEN_MUSIC = "com.fxsoundlite.app.ACTION_OPEN_MUSIC"

        /** Sent by the music-search widget: open the Music page with the search box focused.
         *  EXTRA_SOURCE (Track.SOURCE_*, or MusicSearchRequest.LAST) picks the source tab. */
        const val ACTION_OPEN_MUSIC_SEARCH = "com.fxsoundlite.app.ACTION_OPEN_MUSIC_SEARCH"
        const val EXTRA_SOURCE = "com.fxsoundlite.app.EXTRA_SOURCE"

        /** Widget request waiting for MusicFragment (it may not exist yet on a cold start);
         *  the fragment consumes it, see MusicFragment.applyLaunchRequest(). */
        @Volatile var pendingMusicSearch: Int? = null

        const val PAGE_HOME = 0
        const val PAGE_MUSIC = 1
        const val PAGE_PLAYLIST = 2
        const val PAGE_QUICK = 3
        const val PAGE_SCREENSAVER = 4
    }

    private lateinit var binding: ActivityMainBinding

    /** Page currently shown in the pager; the mini player is hidden on the Screensaver page. */
    private var currentPage = PAGE_HOME

    /** Screensaver "lock swipe & touch": while true the pager can't be swiped and Back is ignored.
     *  In memory only, and cleared whenever the Screensaver page is left. */
    var screensaverLocked = false
        private set

    private val lockBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            android.widget.Toast.makeText(
                this@MainActivity, R.string.screensaver_locked_hint, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Quick and Screensaver are full-screen (no nav bar), so those two pages are left by swiping. */
    private fun swipeEnabled() = currentPage == PAGE_QUICK || (currentPage == PAGE_SCREENSAVER && !screensaverLocked)

    /** Pages visited before the current one (most recent last): Back walks them in reverse, down to Home. */
    private val pageHistory = ArrayList<Int>()
    private var goingBack = false

    private val pageBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // An opened playlist closes first (back to the playlist list); only then Back changes page.
            if (currentPage == PAGE_PLAYLIST &&
                (supportFragmentManager.findFragmentByTag("f$PAGE_PLAYLIST") as? PlaylistFragment)?.consumeBack() == true
            ) return
            var target = PAGE_HOME
            while (pageHistory.isNotEmpty()) {
                val p = pageHistory.removeAt(pageHistory.size - 1)
                if (p != currentPage) { target = p; break }
            }
            if (target == currentPage) { isEnabled = false; return }
            goingBack = true
            binding.mainPager.setCurrentItem(target, target == PAGE_SCREENSAVER || currentPage == PAGE_SCREENSAVER)
        }
    }

    fun setScreensaverLocked(locked: Boolean) {
        screensaverLocked = locked
        lockBackCallback.isEnabled = locked
        binding.mainPager.isUserInputEnabled = swipeEnabled()
    }

    // The accent ThemeOverlay is applied once in onCreate(), before
    // setContentView() -- it can't be swapped on already-inflated views.
    // SettingsActivity recreates ITSELF when the user picks a new accent,
    // but MainActivity underneath is never told, so coming back here left
    // every screen still painted with the old accent color until the app
    // was killed and relaunched. Track what was applied at creation time so
    // onResume() can notice a mismatch and recreate this Activity too.
    private var appliedAccent: ThemeHelper.Accent = ThemeHelper.Accent.DEFAULT

    // Without notification permission (Android 13+) the ongoing notification, and
    // its On/Off button, are hidden from the shade even though the service runs.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        ThemeHelper.watchAuto(this)
        appliedAccent = ThemeHelper.currentAccent(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideNavWhileKeyboardOpen()
        binding.mainPager.adapter = PagerAdapter(this)
        // Both pages are cheap (no heavy media until a video is actually
        // picked), so keep the screensaver page alive even when it isn't
        // showing -- avoids a black flash from tearing it down/rebuilding
        // the VideoView on every swipe.
        binding.mainPager.offscreenPageLimit = 4
        onBackPressedDispatcher.addCallback(this, pageBackCallback)
        onBackPressedDispatcher.addCallback(this, lockBackCallback)
        setupBottomNav()
        startEffectService()
        askNotificationPermissionOnce()
        handleLaunchIntent(intent)
        CrashLog.showIfAny(this)
        UpdateChecker.checkIfDue(this)
        setupMiniPlayer()
    }

    // ------------------------------------------------------------------ mini player

    private val miniHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var miniAccent: Int? = null
    private var miniTrackKey: String? = null
    private var miniTrackSince = 0L
    private val miniRefresh = Runnable { onMusicStateChanged() }
    private val miniTick = object : Runnable {
        override fun run() {
            updateMiniProgress()
            if (MusicService.instance?.isPlaying == true) miniHandler.postDelayed(this, if (MusicPrefs.saver(this@MainActivity)) 2000L else 1000L)
        }
    }

    private fun setupMiniPlayer() {
        // Tapping the bar (anywhere except its buttons, which consume their own clicks) opens the
        // full-screen player.
        binding.miniPlayer.setOnClickListener {
            startActivity(Intent(this, NowPlayingActivity::class.java))
            overridePendingTransition(R.anim.slide_up, R.anim.stay)
        }
        binding.miniAddPlaylist.setOnClickListener {
            MusicService.instance?.current?.let { t -> PlaylistUi.addToPlaylist(this, t) }
        }
        binding.miniPlay.setOnClickListener { MusicService.instance?.togglePlayPause() }
        MusicService.addGlobalListener(this)
        onMusicStateChanged()
    }

    override fun onMusicStateChanged() {
        if (!::binding.isInitialized) return
        val s = MusicService.instance
        val t = s?.current
        // No mini player on the Screensaver (full-screen) nor on Quick (its own full-page player UI).
        if (s == null || t == null || currentPage == PAGE_SCREENSAVER || currentPage == PAGE_QUICK) {
            binding.miniPlayer.visibility = android.view.View.GONE
            miniHandler.removeCallbacks(miniTick)
            return
        }
        binding.miniPlayer.visibility = if (keyboardOpen) android.view.View.GONE else android.view.View.VISIBLE
        binding.miniTitle.text = t.title
        binding.miniArtist.text = t.artist
        binding.miniPlay.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        CoverUi.apply(binding.miniArt, s.artwork, MusicPrefs.saver(this), 8)
        // The bar is never transparent: while the next cover is still loading the previous colour stays for
        // a moment, and a cover without a usable colour (grey, black & white, none at all, or the cover
        // theme switched off) gets a plain solid bar instead of the page showing through.
        if (t.key != miniTrackKey) { miniTrackKey = t.key; miniTrackSince = android.os.SystemClock.elapsedRealtime() }
        val coverTheme = MusicPrefs.coverTheme(this)
        val found = CoverColors.accentFor(this, s.artwork)
        val accent = when {
            found != null -> found.also { miniAccent = it }
            coverTheme && miniAccent != null &&
                android.os.SystemClock.elapsedRealtime() - miniTrackSince < MINI_KEEP_MS -> {
                miniHandler.removeCallbacks(miniRefresh)
                miniHandler.postDelayed(miniRefresh, MINI_KEEP_MS + 100)
                miniAccent // still loading the new cover
            }
            else -> null
        }
        applyMiniTint(accent)
        updateMiniProgress()
        miniHandler.removeCallbacks(miniTick)
        if (s.isPlaying) miniHandler.postDelayed(miniTick, if (MusicPrefs.saver(this)) 2000L else 1000L)
    }

    /** Colours the mini player with the cover's accent (rounded tinted bar, text / icons switched to black or white
     *  for contrast); with no accent it is a plain solid bar in the theme's raised-surface colour. */
    private fun applyMiniTint(accent: Int?) {
        val b = binding
        if (accent == null) {
            b.miniPlayer.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(CoverColors.themeColor(this@MainActivity, R.attr.colorSurfaceRaised))
                cornerRadius = 14f * resources.displayMetrics.density
            }
            val primary = CoverColors.themeColor(this, R.attr.colorTextPrimary)
            val secondary = CoverColors.themeColor(this, R.attr.colorTextSecondary)
            b.miniTitle.setTextColor(primary)
            b.miniArtist.setTextColor(secondary)
            b.miniPlay.imageTintList = android.content.res.ColorStateList.valueOf(primary)
            b.miniAddPlaylist.imageTintList = android.content.res.ColorStateList.valueOf(secondary)
            b.miniProgress.progressTintList = android.content.res.ColorStateList.valueOf(primary)
            b.miniProgress.progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(CoverColors.themeColor(this, R.attr.colorTrackBg))
            return
        }
        val bg = CoverColors.barBg(this, accent)
        val on = CoverColors.onColor(bg)
        val dim = (on and 0x00FFFFFF) or 0xB3000000.toInt()
        b.miniPlayer.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg)
            cornerRadius = 14f * resources.displayMetrics.density
        }
        b.miniTitle.setTextColor(on)
        b.miniArtist.setTextColor(dim)
        b.miniPlay.imageTintList = android.content.res.ColorStateList.valueOf(on)
        b.miniAddPlaylist.imageTintList = android.content.res.ColorStateList.valueOf(dim)
        b.miniProgress.progressTintList = android.content.res.ColorStateList.valueOf(on)
        b.miniProgress.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf((on and 0x00FFFFFF) or 0x33000000)
    }

    private fun updateMiniProgress() {
        val s = MusicService.instance ?: return
        val d = s.duration
        binding.miniProgress.progress = if (d > 0) (s.position.toLong() * 1000 / d).toInt().coerceIn(0, 1000) else 0
    }

    override fun onDestroy() {
        MusicService.removeGlobalListener(this)
        miniHandler.removeCallbacks(miniTick)
        miniHandler.removeCallbacks(miniRefresh)
        super.onDestroy()
    }

    /** launchMode is singleTop, so tapping the widget's Screensaver button while the app is
     *  already open arrives here instead of onCreate(). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Jumps to the Screensaver page when launched from the widget shortcut. The action is
     *  cleared afterwards so a later recreate() (rotation, accent change) doesn't jump again. */
    private fun handleLaunchIntent(launchIntent: Intent?) {
        val launch = launchIntent ?: return
        val page = when (launch.action) {
            ACTION_OPEN_SCREENSAVER -> PAGE_SCREENSAVER
            ACTION_OPEN_QUICK -> PAGE_QUICK
            ACTION_OPEN_PLAYLIST -> PAGE_PLAYLIST
            Intent.ACTION_VIEW -> {
                // A Listen together invite (starrysound://room?id=ABC123): open the dialog and join.
                val uri = launch.data
                if (uri?.host == "room") {
                    val roomId = uri.getQueryParameter("id").orEmpty()
                    launch.action = null
                    launch.data = null
                    binding.mainPager.post { ListenTogetherUi.show(this, roomId) }
                    return
                }
                // A shared playlist link (starrysound://playlist?d=...): import it on the Playlist page.
                val link = launch.dataString.orEmpty()
                if (!PlaylistIo.isShareLink(link)) return
                PlaylistFragment.pendingImport = link
                PAGE_PLAYLIST
            }
            ACTION_OPEN_MUSIC -> PAGE_MUSIC
            ACTION_OPEN_MUSIC_SEARCH -> {
                pendingMusicSearch = launch.getIntExtra(EXTRA_SOURCE, MusicSearchRequest.LAST)
                PAGE_MUSIC
            }
            else -> return
        }
        launch.action = null
        launch.data = null
        launch.removeExtra(EXTRA_SOURCE)
        binding.mainPager.post {
            binding.mainPager.setCurrentItem(page, false)
            // Fragment already alive (app was open): hand it the request now. Otherwise it
            // picks the pending request up itself when its view is created.
            (supportFragmentManager.findFragmentByTag("f$PAGE_MUSIC") as? MusicFragment)
                ?.applyLaunchRequest()
            // Same for a shared playlist link; if the page is not resumed yet it imports in onResume.
            (supportFragmentManager.findFragmentByTag("f$PAGE_PLAYLIST") as? PlaylistFragment)?.let {
                it.consumePendingImport()
                it.consumePendingOpen()
            }
        }
    }

    /** Lets a page (e.g. Home's tiles) jump to another page of the pager. */
    fun openPage(page: Int) = goToPage(page)

    /** Jumps to the Playlist page and opens the list [id] there (e.g. Liked songs), without playing it. */
    fun openPlaylist(id: String) {
        PlaylistFragment.pendingOpen = id
        goToPage(PAGE_PLAYLIST)
        (supportFragmentManager.findFragmentByTag("f$PAGE_PLAYLIST") as? PlaylistFragment)?.consumePendingOpen()
    }

    /** Switches page. Home / Search / Playlist change instantly (no slide); the slide effect is
     *  kept only for moves to or from the Screensaver page. */
    private fun goToPage(page: Int) {
        // Tapping the Quick tab while already on it = refresh the feed with new songs.
        if (page == PAGE_QUICK && currentPage == PAGE_QUICK) {
            (supportFragmentManager.findFragmentByTag("f$PAGE_QUICK") as? QuickFragment)?.refresh()
            return
        }
        val animate = page == PAGE_SCREENSAVER || currentPage == PAGE_SCREENSAVER
        binding.mainPager.setCurrentItem(page, animate)
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ScreensaverPrefs.notifPermissionAsked(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ScreensaverPrefs.setNotifPermissionAsked(this)
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        TasteSignals.refreshIfStale(this) // YouTube account likes / subscriptions, at most once a day
        // Back from Settings: pick up a changed/removed custom cover on the mini player.
        onMusicStateChanged()
        // Pulls playlist/history changes made on another device (throttled, no-op when signed out).
        DriveSync.syncOnStartup(this)
        // Catches returning from Settings after the accent was changed there.
        if (ThemeHelper.currentAccent(this) != appliedAccent) {
            recreate()
        }
    }

    /** Bottom nav bar mirrors the ViewPager2's two pages: tapping a tab jumps
     *  to that page (same destination a swipe would reach), and swiping
     *  updates which tab is shown as selected. */
    private fun setupBottomNav() {
        binding.navHomeTab.setOnClickListener { goToPage(PAGE_HOME) }
        binding.navQuickTab.setOnClickListener { goToPage(PAGE_QUICK) }
        binding.navMusicTab.setOnClickListener { goToPage(PAGE_MUSIC) }
        binding.navPlaylistTab.setOnClickListener { goToPage(PAGE_PLAYLIST) }
        binding.navScreensaverTab.setOnClickListener { goToPage(PAGE_SCREENSAVER) }
        // Finger swiping is only enabled on the full-screen pages (Quick, Screensaver).
        binding.mainPager.isUserInputEnabled = false

        binding.mainPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (!goingBack && position != currentPage) {
                    pageHistory.remove(position) // no loops: a page appears once in the trail
                    pageHistory.add(currentPage)
                }
                goingBack = false
                currentPage = position
                pageBackCallback.isEnabled = position != PAGE_HOME // on Home, Back leaves the app
                if (position != PAGE_SCREENSAVER) setScreensaverLocked(false) // never leave a stuck lock
                binding.mainPager.isUserInputEnabled = swipeEnabled()
                setNavSelection(position)
                // Quick and Screensaver are full-screen: no nav bar there (swipe to leave).
                applyNavVisibility()
                onMusicStateChanged() // show/hide the mini player for this page
            }
        })
        setNavSelection(0)
    }

    private fun setNavSelection(position: Int) {
        val home = position == PAGE_HOME
        val quick = position == PAGE_QUICK
        val music = position == PAGE_MUSIC
        val playlist = position == PAGE_PLAYLIST
        val saver = position == PAGE_SCREENSAVER
        binding.navHomeTab.isSelected = home
        binding.navHomeIcon.isSelected = home
        binding.navHomeLabel.isSelected = home
        binding.navQuickTab.isSelected = quick
        binding.navQuickIcon.isSelected = quick
        binding.navQuickLabel.isSelected = quick
        binding.navMusicTab.isSelected = music
        binding.navMusicIcon.isSelected = music
        binding.navMusicLabel.isSelected = music
        binding.navPlaylistTab.isSelected = playlist
        binding.navPlaylistIcon.isSelected = playlist
        binding.navPlaylistLabel.isSelected = playlist
        binding.navScreensaverTab.isSelected = saver
        binding.navScreensaverIcon.isSelected = saver
        binding.navScreensaverLabel.isSelected = saver
    }

    private fun startEffectService() {
        // Plain startService: the service itself goes foreground (notification) only if effects are on.
        startService(Intent(this, EffectService::class.java))
    }

    private class PagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 5
        override fun createFragment(position: Int): Fragment = when (position) {
            PAGE_HOME -> HomeFragment()
            PAGE_QUICK -> QuickFragment()
            PAGE_MUSIC -> MusicFragment()
            PAGE_PLAYLIST -> PlaylistFragment()
            else -> ScreensaverFragment()
        }
    }

    /** Tapping anywhere outside the focused text field closes the keyboard (and drops focus).
     *  The touch is still passed on, so the tapped button/list item keeps working. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is android.widget.EditText) {
                val loc = IntArray(2)
                focused.getLocationOnScreen(loc)
                val x = ev.rawX
                val y = ev.rawY
                val inside = x >= loc[0] && x <= loc[0] + focused.width &&
                    y >= loc[1] && y <= loc[1] + focused.height
                if (!inside) {
                    focused.clearFocus()
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** With the keyboard open the window resizes; keep the nav bar (and mini player) out of the way
     *  instead of letting it ride up above the keyboard. */
    private var keyboardOpen = false

    /** Nav bar is shown only on pages that have one (not Quick / Screensaver) and while the keyboard is closed. */
    private fun applyNavVisibility() {
        val hasNav = currentPage != PAGE_SCREENSAVER && currentPage != PAGE_QUICK
        val v = if (hasNav && !keyboardOpen) android.view.View.VISIBLE else android.view.View.GONE
        binding.bottomNav.visibility = v
        binding.bottomNavDivider.visibility = v
    }

    private val keyboardHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val keyboardClosedRunnable = Runnable { commitKeyboardOpen(false) }

    private fun commitKeyboardOpen(open: Boolean) {
        if (open == keyboardOpen) return
        keyboardOpen = open
        applyNavVisibility()
        // Hides the mini player now, or brings it back (if something is queued) afterwards.
        onMusicStateChanged()
    }

    /** Single entry point for keyboard state. Opening applies immediately (so nothing rides up above
     *  the keyboard); closing is debounced, because during the IME animation / window resize the
     *  state can briefly read "closed" and would make the nav bar + mini player blink back in. */
    private fun setKeyboardOpen(open: Boolean) {
        if (open) {
            keyboardHandler.removeCallbacks(keyboardClosedRunnable)
            commitKeyboardOpen(true)
        } else if (keyboardOpen) {
            keyboardHandler.removeCallbacks(keyboardClosedRunnable)
            keyboardHandler.postDelayed(keyboardClosedRunnable, 150L)
        }
    }

    private fun hideNavWhileKeyboardOpen() {
        val root = binding.root
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: IME insets are reliable and arrive BEFORE the window is re-laid out.
            // This is the only detector here -- a second one reacting to every layout pass (including
            // the one caused by hiding the nav itself) is what made the bars flicker.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                setKeyboardOpen(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
                androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets)
            }
        } else {
            // Android 8-10: IME insets aren't reported, so compare the visible window frame with the
            // full screen height instead. Only runs on these versions.
            val rect = android.graphics.Rect()
            root.viewTreeObserver.addOnGlobalLayoutListener {
                root.getWindowVisibleDisplayFrame(rect)
                val screenH = root.rootView.height
                val hidden = screenH - rect.bottom
                setKeyboardOpen(hidden > screenH * 0.15f)
            }
        }
    }

    override fun onPause() {
        keyboardHandler.removeCallbacks(keyboardClosedRunnable)
        super.onPause()
    }
}
