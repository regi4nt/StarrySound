package com.fxsoundlite.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/**
 * Bedtime sleep timer (like the Bedtime sounds in Google Clock): after the chosen time the music
 * volume fades out smoothly, the music is paused, and the volume is put back where it was.
 * Pauses whatever [MusicService] is playing (device music).
 *
 * Runs as a small foreground service so the countdown survives the screen turning off and the app
 * being closed. The remaining time is also exposed through [remainingMs] for the Bedtime sheet.
 */
class SleepTimerService : Service() {

    companion object {
        const val ACTION_START = "com.fxsoundlite.app.action.SLEEP_TIMER_START"
        const val ACTION_CANCEL = "com.fxsoundlite.app.action.SLEEP_TIMER_CANCEL"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SOURCE = "source"

        private const val CHANNEL_ID = "sleep_timer"
        private const val NOTIF_ID = 4207
        private const val MAX_FADE_MS = 30_000L

        /** elapsedRealtime() when the music is paused; 0 when no timer runs. */
        @Volatile private var endAt = 0L

        /** Which [Track] SOURCE_* the timer will pause when it ends. */
        @Volatile private var source = Track.SOURCE_LOCAL

        fun currentSource(): Int = source

        fun isRunning(): Boolean = remainingMs() > 0

        /** Milliseconds until the music stops, 0 when no timer is running. */
        fun remainingMs(): Long {
            val e = endAt
            return if (e == 0L) 0L else (e - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

        fun start(context: Context, minutes: Int, playingSource: Int = Track.SOURCE_LOCAL) {
            source = playingSource
            val i = Intent(context, SleepTimerService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_MINUTES, minutes).putExtra(EXTRA_SOURCE, playingSource)
            context.applicationContext.startForegroundService(i)
        }

        fun cancel(context: Context) {
            // Plain startService on purpose: if the service isn't running there is nothing to cancel,
            // and we must not start a foreground service just to stop it again.
            if (!isRunning()) return
            context.applicationContext.startService(
                Intent(context, SleepTimerService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var originalVolume = -1
    private var fading = false

    private val startFade = Runnable { beginFade() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 30).coerceIn(1, 720)
                source = intent.getIntExtra(EXTRA_SOURCE, Track.SOURCE_LOCAL)
                begin(minutes * 60_000L)
            }
            else -> finishWithoutPausing() // ACTION_CANCEL or a stray restart
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        restoreVolume()
        endAt = 0L
        wakeLock?.let { if (it.isHeld) it.release() }
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ timer

    private fun begin(totalMs: Long) {
        handler.removeCallbacksAndMessages(null)
        restoreVolume() // a re-start during a fade must not remember the faded-down volume
        fading = false
        endAt = SystemClock.elapsedRealtime() + totalMs

        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarrySound:sleeptimer")
            .also { it.setReferenceCounted(false); wakeLock = it }
        wl.acquire(totalMs + 2 * 60_000L)

        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(totalMs),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            // Couldn't promote: the timer still runs while the app process lives.
        }
        handler.postDelayed(startFade, (totalMs - fadeMsFor(totalMs)).coerceAtLeast(0L))
    }

    private fun fadeMsFor(totalMs: Long): Long = minOf(MAX_FADE_MS, totalMs / 3)

    private fun beginFade() {
        val am = getSystemService(AudioManager::class.java)
        val start = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalVolume = start
        fading = true
        val fadeMs = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        if (start <= 1) { // nothing audible to fade
            pauseThenFinish()
            return
        }
        val stepMs = (fadeMs / start).coerceAtLeast(200L)
        var level = start
        val step = object : Runnable {
            override fun run() {
                level--
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceAtLeast(0), 0)
                } catch (e: SecurityException) {
                    // Do-not-disturb policies can refuse volume changes; just pause below.
                    level = 0
                }
                if (level > 0) handler.postDelayed(this, stepMs) else pauseThenFinish()
            }
        }
        handler.postDelayed(step, stepMs)
    }

    private fun pauseThenFinish() {
        // YouTube / device music is played by MusicService itself: pause it directly (no network needed).
        try {
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_PAUSE))
        } catch (e: Exception) {
            sendMediaPauseKey()
        }
        // Let the pause settle before the volume comes back, so no blip of music is heard.
        handler.postDelayed({ finishWithoutPausing() }, 800)
    }

    private fun sendMediaPauseKey() {
        try {
            val am = getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) {
            // nothing more we can do
        }
    }

    private fun restoreVolume() {
        if (fading && originalVolume >= 0) {
            try {
                getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            } catch (e: Exception) {
                // ignore
            }
        }
        fading = false
        originalVolume = -1
    }

    /** Ends the service. Cancelling mid-fade also puts the volume back (in onDestroy). */
    private fun finishWithoutPausing() {
        handler.removeCallbacksAndMessages(null)
        endAt = 0L
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(totalMs: Long): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.sleep_timer_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, SleepTimerService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(getString(R.string.sleep_timer_notif_title))
            .setContentText(getString(R.string.sleep_timer_notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // The system draws a live countdown to the moment the music stops.
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + totalMs)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_pause),
                    getString(R.string.sleep_timer_cancel), cancel
                ).build()
            )
            .build()
    }
}
