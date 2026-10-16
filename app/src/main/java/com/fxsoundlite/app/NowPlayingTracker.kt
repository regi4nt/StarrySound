package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Reports the "Title - Artist" line of what is playing right now (or null when nothing is).
 *
 * Two sources, merged:
 *  1. Music played from inside StarrySound ([MusicService]): always available, no permission.
 *  2. Any other app's media session (YouTube Music, a podcast app, ...): needs the user to enable
 *     notification access for this app. Without it only source 1 works.
 *
 * Call [start] in onResume and [stop] in onPause. [onChange] is always invoked on the main thread.
 */
class NowPlayingTracker(
    context: Context,
    private val onChange: (String?) -> Unit
) : MusicService.Listener {

    companion object {
        /** True when the user has enabled notification access for this app
         *  (Settings > Notification access) -- required to read other apps' media sessions. */
        fun isAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        private const val RETRY_MS = 3000L
        private const val MAX_RETRIES = 5
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, NowPlayingListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var controllers: List<MediaController> = emptyList()
    private var started = false
    private var sessionsListening = false
    private var retries = 0

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    private val retryRunnable = Runnable { if (started && !sessionsListening) startSessions() }

    fun start() {
        if (started) return
        started = true
        retries = 0
        MusicService.addGlobalListener(this)
        startSessions()
        publish()
    }

    fun stop() {
        started = false
        handler.removeCallbacks(retryRunnable)
        MusicService.removeGlobalListener(this)
        if (sessionsListening) {
            try {
                manager?.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (e: Exception) {
                // nothing to clean up
            }
            sessionsListening = false
        }
        detachControllers()
    }

    /** Music played inside StarrySound changed (track, play / pause, stop). */
    override fun onMusicStateChanged() = publish()

    /** Hooks into other apps' media sessions; a no-op until notification access is granted. */
    private fun startSessions() {
        if (manager == null || !isAccessGranted(appContext)) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            sessionsListening = true
            attach(manager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            // Access is switched on but the system has not (re)connected our listener service,
            // typically right after the app crashed or was updated. Ask for a rebind and retry.
            sessionsListening = false
            try {
                NotificationListenerService.requestRebind(listenerComponent)
            } catch (_: Exception) {
            }
            if (retries++ < MAX_RETRIES) handler.postDelayed(retryRunnable, RETRY_MS)
        }
    }

    private fun attach(list: List<MediaController>) {
        detachControllers()
        controllers = list
        controllers.forEach { it.registerCallback(controllerCallback, handler) }
        publish()
    }

    private fun detachControllers() {
        controllers.forEach {
            try {
                it.unregisterCallback(controllerCallback)
            } catch (e: Exception) {
                // session already gone
            }
        }
        controllers = emptyList()
    }

    private fun publish() {
        if (!started) return
        val own = MusicService.nowPlayingLabel()
        val other = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }?.metadata?.let { format(it) }
        onChange(own ?: other)
    }

    private fun format(m: MediaMetadata): String? {
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) return null
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        return if (artist.isNullOrBlank()) title else "$title \u2022 $artist"
    }
}
