package com.fxsoundlite.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import androidx.core.content.ContextCompat

/**
 * Moves StarrySound's equalizer chain onto the audio session of Spotify and of any other app that announces its session.
 *
 * Spotify announces its audio session with the standard OPEN / CLOSE_AUDIO_EFFECT_CONTROL_SESSION broadcasts (the way
 * system equalizers work). Those are implicit broadcasts, so the receiver is registered at runtime by
 * [EffectService], which keeps running while the effects are on. While StarrySound plays its own music it keeps the
 * chain; when Spotify's session closes the chain returns to the global mix. If Spotify was already playing before
 * [EffectService] started, nothing is heard until the next track / session change.
 */
object SpotifyEffects {
    private var receiver: BroadcastReceiver? = null

    fun register(svc: EffectService) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handle(svc, intent)
        }
        val f = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        try { ContextCompat.registerReceiver(svc, r, f, ContextCompat.RECEIVER_EXPORTED); receiver = r } catch (e: Exception) { android.util.Log.d("SpotifyEffects", "ignored: " + e) }
    }

    fun unregister(svc: EffectService) {
        receiver?.let { try { svc.unregisterReceiver(it) } catch (e: Exception) { android.util.Log.d("SpotifyEffects", "ignored: " + e) } }
        receiver = null
        open.clear()
    }

    /** Sessions announced by other apps and still open (session id -> package), newest last. */
    private val open = LinkedHashMap<Int, String>()

    /** Other apps (YouTube, YouTube Music, VLC, ...) that announce their session get the EQ too. On by default. */
    fun externalAppsOn(c: Context): Boolean =
        c.getSharedPreferences("fxsoundlite_prefs", Context.MODE_PRIVATE).getBoolean("eq_external_apps", true)

    private fun handle(svc: EffectService, i: Intent) {
        val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
        if (pkg.isEmpty() || pkg == svc.packageName) return
        val spotify = pkg == SpotifyAuth.PKG
        if (spotify && !SpotifyAuth.eqEnabled(svc)) return
        if (!spotify && !externalAppsOn(svc)) return
        val sid = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (sid <= 0) return
        val mgr = svc.effectManager
        if (i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
            if (MusicService.instance?.isPlaying == true) return // our own player keeps the chain
            open[sid] = pkg
            mgr.rebind(sid)
        } else {
            open.remove(sid)
            if (mgr.boundSession == sid) mgr.rebind(open.keys.lastOrNull() ?: AudioEffectManager.GLOBAL_SESSION)
        }
    }
}
