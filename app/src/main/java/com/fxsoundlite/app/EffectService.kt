package com.fxsoundlite.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.content.BroadcastReceiver
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Tiny foreground service whose only job is to hold the AudioEffectManager alive
 * (Android kills AudioEffect instances when the owning process's audio session
 * context goes away / process is trimmed). Runs at IMPORTANCE_LOW with a silent
 * notification, near-zero CPU/RAM overhead — no loops, no polling.
 */
class EffectService : Service() {

    companion object {
        // Sent by EqWidgetProvider so the widget's power toggle and
        // preset row can drive the single real AudioEffectManager instance
        // this service owns, instead of the widget creating its own.
        const val ACTION_TOGGLE = "com.fxsoundlite.app.action.WIDGET_TOGGLE"
        const val ACTION_SET_PRESET = "com.fxsoundlite.app.action.WIDGET_SET_PRESET"
        const val EXTRA_PRESET_NAME = "preset_name"
        // Sent by SettingsActivity after the "auto screensaver while charging"
        // switch changes, so the service re-decides whether it must stay in
        // the foreground (it has to, to keep listening for the charger).
        // Buttons on the ongoing notification: turn all effects off / back on.
        const val ACTION_TURN_OFF = "com.fxsoundlite.app.action.NOTIF_TURN_OFF"
        const val ACTION_TURN_ON = "com.fxsoundlite.app.action.NOTIF_TURN_ON"
        // Buttons on the notification while effects are on: step to the previous / next preset.
        const val ACTION_PREV_PRESET = "com.fxsoundlite.app.action.NOTIF_PREV_PRESET"
        const val ACTION_NEXT_PRESET = "com.fxsoundlite.app.action.NOTIF_NEXT_PRESET"
        const val ACTION_SYNC_FOREGROUND = "com.fxsoundlite.app.action.SYNC_FOREGROUND"

        /** The running service (null when not running). MusicService uses it to move the effect
         *  chain onto its own audio session; both services live in the same process. */
        @Volatile
        var instance: EffectService? = null
            private set
    }

    /** Single source of truth. The Activity binds to this instance instead of
     *  creating its own — see AudioEffectManager doc: multiple AudioEffect
     *  instances of the same type fighting over the same session is what
     *  caused slider changes to silently not stick. */
    lateinit var effectManager: AudioEffectManager
        private set

    /** Listens for charger plugged in / screen on for the charging
     *  screensaver. Registered for the service's whole life (these broadcasts
     *  can't be declared in the manifest); it does nothing while the setting is off. */
    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ChargingScreensaver.onSystemEvent(context, intent.action)
        }
    }

    /** Lets the on-screen UI mirror a power change made from the notification
     *  button. Always invoked on the main thread. Several screens can be bound
     *  at once (Home stays alive underneath the Equalizer screen), so this is a
     *  listener list rather than a single slot -- otherwise one screen's
     *  onStop() would silently unhook the other. */
    private val powerListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addPowerListener(l: (Boolean) -> Unit) { powerListeners.addIfAbsent(l) }
    fun removePowerListener(l: (Boolean) -> Unit) { powerListeners.remove(l) }

    /** Same idea for the active preset: lets any open screen redraw live when it is
     *  changed from the notification buttons or the widget. Called on the main
     *  thread, after the change is fully applied and saved. */
    private val presetListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addPresetListener(l: () -> Unit) { presetListeners.addIfAbsent(l) }
    fun removePresetListener(l: () -> Unit) { presetListeners.remove(l) }

    // Audio output changes (headphones in/out, Bluetooth on/off) for "Auto preset per output".
    // Debounced: Bluetooth reports several devices within a moment.
    private val outputRefresh = Runnable { OutputPresets.refresh(this, effectManager) }
    private val outputCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = scheduleOutputRefresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = scheduleOutputRefresh()
    }

    private fun scheduleOutputRefresh() {
        mainHandler.removeCallbacks(outputRefresh)
        mainHandler.postDelayed(outputRefresh, 600)
    }

    /** Re-evaluates the output now (e.g. right after the user turned the feature on). */
    fun refreshOutputPreset(force: Boolean) {
        mainHandler.removeCallbacks(outputRefresh)
        OutputPresets.refresh(this, effectManager, force)
    }

    // Root mode only: every 30 s check that the effects still answer the audio server (it may have
    // restarted, or the system reclaimed them) and rebuild them from the saved settings if not.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!effectManager.isRootMode) return
            if (effectManager.isEnabled && !effectManager.isHealthy()) {
                android.util.Log.w("EffectService", "effects lost, re-applying")
                effectManager.reapply()
            }
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    /** Starts / stops the watchdog to match root mode (call after the switch changes). */
    fun syncWatchdog() {
        mainHandler.removeCallbacks(watchdog)
        if (effectManager.isRootMode) mainHandler.postDelayed(watchdog, 30_000L)
    }

    private val binder = LocalBinder()
    private val CHANNEL_ID = "fxsoundlite_channel"
    // Only used while effects are OFF but the service must stay foreground for the
    // charging screensaver: MIN importance = no status-bar icon, collapsed in the shade.
    private val IDLE_CHANNEL_ID = "fxsoundlite_idle_channel"
    private val NOTIF_ID = 1

    inner class LocalBinder : Binder() {
        fun getService(): EffectService = this@EffectService
    }

    override fun onCreate() {
        super.onCreate()
        effectManager = AudioEffectManager(this)
        effectManager.initialize()
        // Only show the notification while the effects are on. When they are off (e.g. only the
        // charging screensaver is enabled) the service just keeps running quietly in the
        // background with no notification. Callers that used startForegroundService() are
        // covered by ensureForegroundContract() in onStartCommand().
        setNotificationVisible(effectManager.isEnabled)
        ContextCompat.registerReceiver(
            this, chargingReceiver, ChargingScreensaver.intentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Keep the notification's preset name current no matter where the change came from
        // (in-app chips, widget, or the notification buttons themselves).
        effectManager.onStateChanged = {
            refreshNotification()
            // Posted (not called inline) so listeners see the FINAL state: "Auto" first
            // stamps the tier's preset name and only then "Auto".
            mainHandler.post { presetListeners.forEach { it() } }
        }
        instance = this
        SpotifyEffects.register(this)
        try {
            getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(outputCallback, mainHandler)
        } catch (e: Exception) { android.util.Log.d("EffectService", "ignored: " + e) }
        scheduleOutputRefresh() // first evaluation: the output that is active at start
        syncWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate() (which builds effectManager) always runs before the
        // first onStartCommand(), so it's safe to act on these here.
        when (intent?.action) {
            ACTION_TOGGLE -> setPower(!effectManager.isEnabled)
            ACTION_TURN_OFF -> setPower(false)
            ACTION_TURN_ON -> setPower(true)
            ACTION_SYNC_FOREGROUND -> setNotificationVisible(effectManager.isEnabled)
            ACTION_NEXT_PRESET -> stepPreset(+1)
            ACTION_PREV_PRESET -> stepPreset(-1)
            ACTION_SET_PRESET -> {
                val name = intent.getStringExtra(EXTRA_PRESET_NAME)
                if (name == "Auto") effectManager.applyAutoPreset()
                else effectManager.applyPreset(Presets.byName(name))
            }
        }
        ensureForegroundContract(intent)
        return START_STICKY
    }

    /** True while this service is promoted to foreground (i.e. its notification is showing). */
    private var isForeground = false

    /** A service started with startForegroundService() (widget, notification buttons, boot)
     *  must call startForeground() within a few seconds or Android crashes the app. If that
     *  happened while the effects are off, satisfy the contract and drop the notification
     *  right away. Plain startService() callers (Settings, app launch) never reach this. */
    private fun ensureForegroundContract(intent: Intent?) {
        if (isForeground || intent?.action == null || intent.action == ACTION_SYNC_FOREGROUND) return
        startForeground(NOTIF_ID, buildNotification())
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Moves to the previous/next preset in Presets.ALL (wrapping around), exactly like
     *  tapping that chip in the Equalizer -- "Auto" goes through applyAutoPreset().
     *  Only acts while effects are on: the buttons aren't shown otherwise. */
    private fun stepPreset(delta: Int) {
        if (!effectManager.isEnabled) return
        val list = Presets.ALL
        val current = effectManager.prefs.getString("last_preset", "Default")
        val idx = list.indexOfFirst { it.name == current }.let { if (it == -1) 0 else it }
        val next = list[(idx + delta + list.size) % list.size]
        if (next.name == "Auto") effectManager.applyAutoPreset() else effectManager.applyPreset(next)
    }

    /** Re-posts the ongoing notification in place (new preset name). Skipped while
     *  effects are off: then the notification is hidden (or the idle one is shown) and
     *  posting here would wrongly bring back the "active" one. */
    private fun refreshNotification() {
        if (!effectManager.isEnabled || !ScreensaverPrefs.presetNotification(this)) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    /** Single path for power changes that don't come from the in-app switch
     *  (widget, notification button): engine, notification and UI stay in sync. */
    private fun setPower(on: Boolean) {
        effectManager.setMasterEnabled(on)
        setNotificationVisible(on)
        powerListeners.forEach { it(on) }
    }

    /** Called whenever the master power switch changes, so the ongoing notification tracks
     *  whether effects are actually on. With effects off the notification is removed even if
     *  the charging screensaver is enabled: the service keeps listening for the charger as a
     *  plain background service, without any notification. */
    fun setNotificationVisible(visible: Boolean) {
        if (visible) {
            startForeground(NOTIF_ID, buildNotification())
            isForeground = true
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
    }

    private fun buildNotification(): Notification {
        // EffectService is a Service, not an Activity -- AppCompat's per-app
        // locale only auto-wraps Activity contexts below API 33, so plain
        // getString() here silently fell back to the device's system
        // language instead of whatever the user picked in Settings.
        val strings = LocaleHelper.localizedContext(this)
        val nm = getSystemService(NotificationManager::class.java)
        val on = effectManager.isEnabled
        // User turned the preset notification off in Settings: only a minimal, silent
        // placeholder stays (a foreground service must have one), no preset / buttons.
        val full = ScreensaverPrefs.presetNotification(this)

        val channelId: String
        if (on && full) {
            channelId = CHANNEL_ID
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, strings.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        } else {
            channelId = IDLE_CHANNEL_ID
            nm.createNotificationChannel(
                NotificationChannel(
                    IDLE_CHANNEL_ID, strings.getString(R.string.notif_channel_idle_name),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }

        // One button that flips the state: "Off" while effects run, "On" while they don't.
        val toggleIntent = PendingIntent.getForegroundService(
            this, if (on) 20 else 21,
            Intent(this, EffectService::class.java)
                .setAction(if (on) ACTION_TURN_OFF else ACTION_TURN_ON),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_lock_power_off),
            strings.getString(if (on) R.string.notif_action_off else R.string.notif_action_on),
            toggleIntent
        ).build()

        val builder = Notification.Builder(this, channelId)
            .setContentTitle(strings.getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (!full) {
            builder.setContentText(strings.getString(if (on) R.string.notif_running else R.string.notif_fx_off))
            return builder.build()
        }
        if (on) {
            // Effects active: show the current preset and let it be changed from here.
            val presetName = effectManager.prefs.getString("last_preset", "Default") ?: "Default"
            builder.setContentText(strings.getString(R.string.notif_running_preset, presetName))
            builder.addAction(presetAction(strings.getString(R.string.notif_action_prev_preset),
                android.R.drawable.ic_media_previous, ACTION_PREV_PRESET, 22))
            builder.addAction(presetAction(strings.getString(R.string.notif_action_next_preset),
                android.R.drawable.ic_media_next, ACTION_NEXT_PRESET, 23))
        } else {
            builder.setContentText(strings.getString(R.string.notif_fx_off))
        }
        return builder.addAction(toggleAction).build()
    }

    private fun presetAction(label: String, iconRes: Int, action: String, requestCode: Int): Notification.Action {
        val pi = PendingIntent.getForegroundService(
            this, requestCode,
            Intent(this, EffectService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, iconRes), label, pi).build()
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacks(outputRefresh)
        mainHandler.removeCallbacks(watchdog)
        try { getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(outputCallback) } catch (e: Exception) { android.util.Log.d("EffectService", "ignored: " + e) }
        effectManager.onStateChanged = null
        SpotifyEffects.unregister(this)
        try {
            unregisterReceiver(chargingReceiver)
        } catch (e: IllegalArgumentException) {
            // Not registered.
        }
        effectManager.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
