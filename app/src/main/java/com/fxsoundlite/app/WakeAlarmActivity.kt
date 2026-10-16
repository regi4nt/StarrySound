package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import java.util.Calendar

/**
 * Full screen shown over the lock screen when the wake-up alarm rings: plays the chosen song through
 * [MusicService] and offers Stop / Snooze. If the song cannot start (offline YouTube song, deleted file ...)
 * the system alarm sound takes over after a few seconds, so the alarm never rings silently.
 */
class WakeAlarmActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var service: MusicService? = null
    private var bound = false
    private var started = false
    private var ringtone: Ringtone? = null
    private var finished = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? MusicService.LocalBinder)?.getService()
            startPlayback()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Every few seconds: if nothing is playing any more, ring with the system alarm sound instead. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (finished) return
            if (service?.isPlaying != true && ringtone?.isPlaying != true) startRingtone()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_wake_alarm)

        // Edge to edge over the Screensaver background; the controls get the bar insets as padding so the
        // Stop button is never hidden under the gesture / navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val content = findViewById<View>(R.id.wakeContent)
        val pad = content.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.commit { replace(R.id.wakeBackground, ScreensaverFragment.backgroundOnly()) }
        }

        AlarmReceiver.cancelNotification(this) // also silences the notification's own alarm sound

        val c = Calendar.getInstance()
        findViewById<TextView>(R.id.wakeTime).text =
            String.format(java.util.Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))

        val toneMode = WakeAlarm.soundMode(this) == WakeAlarm.MODE_TONE
        val track = WakeAlarm.track(this)
        val random = WakeAlarm.randomLiked(this) || track == null
        findViewById<TextView>(R.id.wakeSong).text = when {
            toneMode -> WakeAlarm.toneTitle(this)
            random -> getString(R.string.alarm_song_random)
            else -> track?.title.orEmpty()
        }
        findViewById<TextView>(R.id.wakeArtist).text = when {
            toneMode -> getString(R.string.alarm_mode_tone)
            random -> ""
            else -> track?.artist.orEmpty()
        }

        val snoozeMin = WakeAlarm.snoozeMin(this)
        findViewById<TextView>(R.id.wakeSnooze).apply {
            text = getString(R.string.alarm_snooze_btn, snoozeMin)
            setOnClickListener { WakeAlarm.snooze(this@WakeAlarmActivity); stopAll() }
        }
        findViewById<TextView>(R.id.wakeStop).setOnClickListener { stopAll() }

        if (toneMode) {
            // A device alarm tone needs no music service: ring right away.
            startRingtone()
            handler.postDelayed(watchdog, 5_000)
        } else {
            bound = applicationContext.bindService(
                Intent(applicationContext, MusicService::class.java), connection, Context.BIND_AUTO_CREATE
            )
            // Safety net in case the service never connects or the song never starts.
            handler.postDelayed(watchdog, 20_000)
        }
    }

    private fun startPlayback() {
        if (started) return
        val svc = service ?: return
        val app = applicationContext
        val queue: List<Track> = if (WakeAlarm.randomLiked(app)) {
            PlaylistStore.liked(app).filter { !it.missing }.shuffled()
        } else {
            val t = WakeAlarm.track(app)?.let { LocalMatcher.resolve(app, it) }
            // The same song again and again, so it keeps ringing until you stop it.
            if (t == null || t.missing) emptyList() else List(40) { t }
        }
        if (queue.isEmpty()) { startRingtone(); return }
        started = true
        liftVolume()
        svc.setQueueAndPlay(queue, 0)
    }

    /** A muted media volume would mean a silent alarm: make sure it is at least audible. */
    private fun liftVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val min = (max * 0.4f).toInt().coerceAtLeast(1)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < min) am.setStreamVolume(AudioManager.STREAM_MUSIC, min, 0)
        } catch (_: Exception) {
        }
    }

    private fun startRingtone() {
        if (ringtone?.isPlaying == true) return
        try {
            // The tone chosen in the alarm settings (also the fallback when a song fails), else the system default.
            val uri = WakeAlarm.effectiveToneUri(this) ?: return
            val r = ringtone ?: RingtoneManager.getRingtone(this, uri)
                ?: RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            if (android.os.Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
        } catch (e: Exception) {
            android.util.Log.w("WakeAlarm", "fallback ringtone failed", e)
        }
    }

    private fun stopAll() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        try { ringtone?.stop() } catch (_: Exception) { }
        if (started) service?.pause()
        finish()
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        try { ringtone?.stop() } catch (_: Exception) { }
        if (bound) {
            try { applicationContext.unbindService(connection) } catch (_: Exception) { }
            bound = false
        }
        service = null
        super.onDestroy()
    }

    @Deprecated("Back must not silently leave the alarm ringing")
    override fun onBackPressed() {
        // Ignored on purpose: use Stop or Snooze.
    }
}
