package com.fxsoundlite.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.fxsoundlite.app.databinding.FragmentScreensaverBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Page 1 of MainActivity's ViewPager2 -- reached by swiping right from the
 * Home controls, or by tapping the screensaver icon in Home's header.
 *
 * Shows the device's system clock (12h/24h follows the system setting) and
 * date over a looping, muted video the user picked in Settings. With no
 * video picked yet it just falls back to the app's normal background color
 * so the clock is still usable.
 *
 * The clock tick and "keep screen on while looking at it" behavior are tied
 * to this fragment's own onResume()/onPause() rather than to MainActivity,
 * because ViewPager2's FragmentStateAdapter only resumes the page currently
 * on screen -- adjacent/offscreen pages are held at STARTED, not RESUMED --
 * so this naturally starts/stops exactly when the page is actually visible.
 */
class ScreensaverFragment : Fragment() {

    companion object {
        /** Show only the chosen background (video / photo / animated style): no clock, date, battery or lock button.
         *  Used behind the wake-up alarm screen, which draws its own time and buttons on top. */
        const val ARG_BACKGROUND_ONLY = "background_only"

        fun backgroundOnly() = ScreensaverFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_BACKGROUND_ONLY, true) }
        }
    }

    private val bgOnly get() = arguments?.getBoolean(ARG_BACKGROUND_ONLY, false) == true

    private var _binding: FragmentScreensaverBinding? = null
    private val binding get() = _binding!!

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            // Aligning to the next second boundary (rather than a flat 1000ms
            // interval from whenever onResume() happened to fire) keeps the
            // displayed seconds from visibly drifting/skipping over time.
            val millisToNextSecond = 1000 - (System.currentTimeMillis() % 1000)
            tickHandler.postDelayed(this, millisToNextSecond)
        }
    }

    /** Feeds the "Title • Artist" label at the bottom; only alive while this page is visible. */
    private var nowPlaying: NowPlayingTracker? = null

    /** Keeps the battery indicator above the title live; registered only while visible. */
    private var batteryReceiver: BroadcastReceiver? = null

    private var appliedUri: String? = null
    private var appliedType: String? = null
    private var appliedAnimatedStyle: String? = null

    /** Bumped on every background (re)load and on onDestroyView so a slow
     *  photo decode that finishes late can't overwrite a newer pick. */
    private var loadToken = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScreensaverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (bgOnly) {
            binding.screensaverTopBlock.visibility = View.GONE
            binding.screensaverBottomBlock.visibility = View.GONE
            binding.screensaverLockButton.visibility = View.GONE
            binding.screensaverPlayerButton.visibility = View.GONE
            return
        }
        binding.screensaverPlayerButton.setOnClickListener {
            // The full player closes itself when this app has no current track; say so instead of a silent no-op.
            if (MusicService.instance?.current == null) {
                Toast.makeText(requireContext(), R.string.music_nothing_playing, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
            // Opened from the charging screensaver (over the lock screen): the player must show above the keyguard too.
            if (activity is ChargingScreensaverActivity) intent.putExtra(NowPlayingActivity.EXTRA_OVER_LOCK, true)
            startActivity(intent)
            activity?.overridePendingTransition(R.anim.slide_up, R.anim.stay)
        }
        setupLockButton()
        // Don't load media here: this fragment is created once and kept alive
        // for the whole ViewPager2 lifetime (offscreenPageLimit = 1), so this
        // only ever runs once per app session. Loading is instead driven from
        // onResume() -- see refreshBackgroundIfChanged() -- which reruns every
        // time this page actually becomes visible, including right after the
        // user picks a new photo/video in Settings and swipes/taps back here.
    }

    override fun onResume() {
        super.onResume()
        // Classic screensaver behavior: don't let the display sleep while
        // it's the thing actually showing on screen.
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!bgOnly) {
            (activity as? MainActivity)?.let { applyLock(it.screensaverLocked) }
            applyClockStyle() // picks up a change made in Settings
            tickRunnable.run() // updates immediately, then keeps itself ticking
            updateGreeting() // picks up an edit made in Settings
        }
        refreshBackgroundIfChanged()
        if (!bgOnly) {
            nowPlaying = NowPlayingTracker(requireContext()) { text -> updateNowPlaying(text) }
                .also { it.start() }
            startBatteryUpdates()
        }
        if (binding.screensaverVideo.visibility == View.VISIBLE) {
            binding.screensaverVideo.start()
        }
        if (binding.screensaverAnimatedBg.visibility == View.VISIBLE) {
            binding.screensaverAnimatedBg.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!bgOnly) requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tickHandler.removeCallbacks(tickRunnable)
        nowPlaying?.stop()
        nowPlaying = null
        stopBatteryUpdates()
        if (binding.screensaverVideo.visibility == View.VISIBLE) {
            binding.screensaverVideo.pause()
        }
        binding.screensaverAnimatedBg.stop() // cheap to restart in onResume; no reason to keep ticking off-screen
    }
    override fun onDestroyView() {
        super.onDestroyView()
        loadToken++ // invalidate any in-flight photo decode
        tickHandler.removeCallbacks(tickRunnable)
        binding.screensaverVideo.stopPlayback()
        binding.screensaverImage.setImageDrawable(null)
        binding.screensaverAnimatedBg.stop()
        _binding = null
    }

    // ------------------------------------------------------------------ swipe / touch lock

    private var lastLockHintAt = 0L

    /** Tap the lock icon = lock swipe + touch; long-press it = unlock. Only offered on the main
     *  screen: the charging screensaver (over the lock screen) closes on any touch by design. */
    private fun setupLockButton() {
        val host = activity as? MainActivity
        if (host == null) {
            binding.screensaverLockButton.visibility = View.GONE
            // Charging screensaver: no buttons at all, any touch closes it.
            binding.screensaverPlayerButton.visibility = View.GONE
            return
        }
        binding.screensaverLockButton.setOnClickListener {
            if (!host.screensaverLocked) {
                host.setScreensaverLocked(true)
                applyLock(true)
                Toast.makeText(requireContext(), R.string.screensaver_locked_toast, Toast.LENGTH_SHORT).show()
            }
        }
        binding.screensaverLockButton.setOnLongClickListener {
            if (host.screensaverLocked) {
                host.setScreensaverLocked(false)
                applyLock(false)
                Toast.makeText(requireContext(), R.string.screensaver_unlocked_toast, Toast.LENGTH_SHORT).show()
                true
            } else false
        }
        // While locked, any touch outside the lock icon is swallowed (with a throttled hint).
        binding.screensaverTouchShield.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()
                if (now - lastLockHintAt > 2500) {
                    lastLockHintAt = now
                    Toast.makeText(requireContext(), R.string.screensaver_locked_hint, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        applyLock(host.screensaverLocked)
    }

    private fun applyLock(locked: Boolean) {
        if (_binding == null) return
        binding.screensaverTouchShield.visibility = if (locked) View.VISIBLE else View.GONE
        binding.screensaverLockButton.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        binding.screensaverLockButton.imageTintList =
            android.content.res.ColorStateList.valueOf(if (locked) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
    }

    private fun updateGreeting() {
        if (_binding == null) return
        val text = ScreensaverPrefs.greeting(requireContext())
        binding.screensaverGreeting.text = text
        binding.screensaverGreeting.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startBatteryUpdates() {
        if (batteryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = updateBattery(intent)
        }
        batteryReceiver = receiver
        // ACTION_BATTERY_CHANGED is sticky: the return value is the current state,
        // so the indicator is correct immediately, then follows every change.
        val sticky = requireContext().registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateBattery(sticky)
    }

    private fun stopBatteryUpdates() {
        val receiver = batteryReceiver ?: return
        batteryReceiver = null
        try {
            requireContext().unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
    }

    private fun updateBattery(intent: Intent?) {
        if (_binding == null || intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val percent = (level * 100 / scale).coerceIn(0, 100)
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        binding.screensaverBatteryIcon.setState(percent, charging)
        binding.screensaverBatteryText.text =
            if (charging) "$percent% \u00B7 ${getString(R.string.battery_charging)}" else "$percent%"
    }

    private fun updateNowPlaying(text: String?) {
        if (_binding == null) return
        binding.screensaverNowPlaying.text = text.orEmpty()
        binding.screensaverNowPlaying.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun applyClockStyle() {
        if (_binding == null) return
        val analog = ScreensaverPrefs.clockStyle(requireContext()) == ScreensaverPrefs.CLOCK_ANALOG
        binding.screensaverClock.visibility = if (analog) View.GONE else View.VISIBLE
        binding.screensaverAnalogClock.visibility = if (analog) View.VISIBLE else View.GONE
    }

    private fun updateClock() {
        if (_binding == null) return
        val now = Calendar.getInstance()
        if (binding.screensaverAnalogClock.visibility == View.VISIBLE) {
            binding.screensaverAnalogClock.setTime(now)
        }
        val timePattern = if (DateFormat.is24HourFormat(requireContext())) "HH:mm:ss" else "h:mm:ss a"
        binding.screensaverClock.text = SimpleDateFormat(timePattern, Locale.getDefault()).format(now.time)
        binding.screensaverDate.text =
            SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now.time)
        updateAlarm(now)
    }

    /** Nearest wake-up alarm set in the app, shown to the right of the date; hidden when none is on. */
    private fun updateAlarm(now: Calendar) {
        val ctx = context ?: return
        if (!WakeAlarm.isEnabled(ctx)) {
            binding.screensaverAlarmBox.visibility = View.GONE
            return
        }
        val at = Calendar.getInstance().apply { timeInMillis = WakeAlarm.nextTrigger(ctx, now.timeInMillis) }
        val timePattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
        var text = SimpleDateFormat(timePattern, Locale.getDefault()).format(at.time)
        // More than a day away (e.g. a weekday-only alarm over the weekend): say which day.
        if (at.timeInMillis - now.timeInMillis > 24 * 3600_000L) {
            text = SimpleDateFormat("EEE", Locale.getDefault()).format(at.time) + " " + text
        }
        binding.screensaverAlarm.text = text
        binding.screensaverAlarmBox.visibility = View.VISIBLE
    }

    private fun refreshBackgroundIfChanged() {
        val uriString = ScreensaverPrefs.videoUri(requireContext())
        val type = ScreensaverPrefs.mediaType(requireContext())
        val animatedStyle = ScreensaverPrefs.animatedStyle(requireContext())
        // Nothing picked/changed since we last loaded -> leave the currently
        // displayed background alone (the caller's own start()/pause() calls
        // handle simply resuming it). Comparing the Uri, type AND animated
        // style matters: picking a new image over an old image with the exact
        // same filename still changes the Uri (a fresh content:// each pick),
        // and switching between two animated styles changes neither Uri nor
        // type, only the style.
        if (uriString == appliedUri && type == appliedType && animatedStyle == appliedAnimatedStyle) return
        appliedUri = uriString
        appliedType = type
        appliedAnimatedStyle = animatedStyle
        setupBackground()
    }

    private fun setupBackground() {
        loadToken++
        // Release whatever was previously showing before loading the new
        // pick -- otherwise switching video->image (or picking a new video)
        // can leave the old VideoView's MediaPlayer alive and decoding in the
        // background even though it's no longer visible.
        binding.screensaverVideo.stopPlayback()
        binding.screensaverImage.setImageDrawable(null)
        binding.screensaverAnimatedBg.stop()

        // Reset EVERY layer first. The animated view sits above the
        // VideoView/ImageView in the layout, so if it were left visible
        // from an earlier animated pick it would completely cover a newly
        // picked photo/video. Each setup* below re-shows only its own layer.
        binding.screensaverVideo.visibility = View.GONE
        binding.screensaverImage.visibility = View.GONE
        binding.screensaverAnimatedBg.visibility = View.GONE
        binding.screensaverFallbackBg.visibility = View.VISIBLE
        // Drop the previous video's fixed cover-size so it can't distort the
        // next one before that one reports its own dimensions.
        binding.screensaverVideo.layoutParams = binding.screensaverVideo.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        if (ScreensaverPrefs.mediaType(requireContext()) == ScreensaverPrefs.TYPE_ANIMATED) {
            setupAnimated()
            return
        }

        val uriString = ScreensaverPrefs.videoUri(requireContext())
        if (uriString.isNullOrBlank()) {
            showFallback()
            return
        }
        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            showFallback()
            return
        }

        if (ScreensaverPrefs.mediaType(requireContext()) == ScreensaverPrefs.TYPE_IMAGE) {
            setupImage(uri)
        } else {
            setupVideo(uri)
        }
    }

    private fun setupAnimated() {
        binding.screensaverFallbackBg.visibility = View.GONE
        binding.screensaverVideo.visibility = View.GONE
        binding.screensaverImage.visibility = View.GONE
        val bg = binding.screensaverAnimatedBg
        bg.style = when (ScreensaverPrefs.animatedStyle(requireContext())) {
            ScreensaverPrefs.STYLE_RAIN -> SleepBackgroundView.Style.RAIN
            ScreensaverPrefs.STYLE_STARFIELD -> SleepBackgroundView.Style.STARFIELD
            else -> SleepBackgroundView.Style.NIGHT_SKY
        }
        bg.visibility = View.VISIBLE
        bg.start() // safe even before the view has a size yet -- it seeds lazily once it does
    }

    /** Decodes the photo off the main thread, downsampled to roughly the
     *  screen size and with its EXIF rotation applied. The previous
     *  ImageView.setImageURI() decoded the full-resolution photo on the UI
     *  thread, which froze the app for large photos, threw OOM /
     *  "Canvas: trying to draw too large bitmap" on 12MP+ camera shots, and
     *  showed some phone photos sideways. */
    private fun setupImage(uri: Uri) {
        val token = loadToken
        val appContext = requireContext().applicationContext
        val dm = resources.displayMetrics
        val reqW = dm.widthPixels
        val reqH = dm.heightPixels
        Thread {
            val bitmap = decodeScaledBitmap(appContext, uri, reqW, reqH)
            tickHandler.post {
                if (_binding == null || token != loadToken) return@post // stale / view gone
                if (bitmap == null) {
                    showFallback()
                    notifyLoadFailed()
                    return@post
                }
                binding.screensaverImage.setImageBitmap(bitmap)
                binding.screensaverVideo.visibility = View.GONE
                binding.screensaverAnimatedBg.visibility = View.GONE
                binding.screensaverImage.visibility = View.VISIBLE
                binding.screensaverFallbackBg.visibility = View.GONE
            }
        }.start()
    }

    private fun decodeScaledBitmap(context: android.content.Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // ImageDecoder applies EXIF orientation by itself.
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val sample = sampleSizeFor(info.size.width, info.size.height, reqW, reqH)
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                }
            } else {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, reqW, reqH)
                }
                val decoded = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return null
                val orientation = try {
                    resolver.openInputStream(uri)?.use {
                        ExifInterface(it).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                        )
                    } ?: ExifInterface.ORIENTATION_NORMAL
                } catch (e: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }
                applyExifOrientation(decoded, orientation)
            }
        } catch (e: Throwable) {
            // Unreadable / revoked permission / corrupt / unsupported / OOM.
            null
        }
    }

    /** Largest power-of-two shrink that still leaves the image at least as
     *  big as the screen in both dimensions (centerCrop needs that). */
    private fun sampleSizeFor(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
        return sample
    }

    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (e: Throwable) {
            src
        }
    }

    private fun setupVideo(uri: Uri) {
        val videoView = binding.screensaverVideo
        binding.screensaverImage.visibility = View.GONE
        binding.screensaverAnimatedBg.visibility = View.GONE
        // Keep the plain background visible until the first frame is ready,
        // so there's no black flash while the video loads.
        binding.screensaverFallbackBg.visibility = View.VISIBLE
        videoView.visibility = View.VISIBLE
        // VideoView grabs audio focus on start(), which pauses/ducks the music even when the video
        // itself is muted. A background video must never touch audio focus.
        videoView.setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_NONE)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f) // muted: the video's own sound is never played
            scaleVideoToCoverScreen(mp.videoWidth, mp.videoHeight)
            if (_binding != null) binding.screensaverFallbackBg.visibility = View.GONE
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            // Picked video became unreadable/unsupported (moved, revoked
            // permission, codec issue, etc.) -- degrade to the plain
            // background instead of leaving a dead black rectangle.
            showFallback()
            notifyLoadFailed()
            true
        }
        try {
            videoView.setVideoURI(uri)
        } catch (e: Exception) {
            showFallback()
            notifyLoadFailed()
        }
    }

    private fun notifyLoadFailed() {
        if (_binding == null) return
        Toast.makeText(requireContext(), R.string.background_load_failed, Toast.LENGTH_LONG).show()
    }

    private fun showFallback() {
        _binding ?: return
        binding.screensaverVideo.visibility = View.GONE
        binding.screensaverImage.visibility = View.GONE
        binding.screensaverAnimatedBg.visibility = View.GONE
        binding.screensaverFallbackBg.visibility = View.VISIBLE
    }

    /** Mimics CSS "background-size: cover": scales the VideoView so the video
     *  fully fills the screen (cropping overflow) instead of Android's
     *  default letterboxed fit-inside sizing. */
    private fun scaleVideoToCoverScreen(videoWidth: Int, videoHeight: Int) {
        if (_binding == null || videoWidth <= 0 || videoHeight <= 0) return
        val videoView = binding.screensaverVideo
        val parent = view as? ViewGroup ?: return
        val screenWidth = parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenHeight = parent.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val scale = maxOf(
            screenWidth.toFloat() / videoWidth,
            screenHeight.toFloat() / videoHeight
        )
        val params = videoView.layoutParams
        params.width = (videoWidth * scale).toInt()
        params.height = (videoHeight * scale).toInt()
        videoView.layoutParams = params
    }
}
