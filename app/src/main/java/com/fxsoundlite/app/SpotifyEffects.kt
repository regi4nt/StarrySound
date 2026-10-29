package com.fxsoundlite.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import androidx.core.content.ContextCompat

/**
 * Moves StarrySound's equalizer chain onto the audio session of the Spotify app.
 *
 * Spotify announces its audio session with the standard OPEN / CLOSE_AUDIO_EFFECT_CONTROL_SESSION broadcasts (the way
 * system equalizers work). Those are implicit broadcasts, so the receiver is registered at runtime by
 * [EffectService], which keeps running while the effects are on. While StarrySound plays its own music it keeps the
 * chain; when Spotify's session closes the chain returns to the global mix. If Spotify was already playing before
 * [EffectService] started, nothing is heard until the next track / session change.
 */
object SpotifyEffects {
    private var receiver: BroadcastReceiver? = null
    private var session = 0

    fun register(svc: EffectService) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handle(svc, intent)
        }
        val f = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        try { ContextCompat.registerReceiver(svc, r, f, ContextCompat.RECEIVER_EXPORTED); receiver = r } catch (_: Exception) { }
    }

    fun unregister(svc: EffectService) {
        receiver?.let { try { svc.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
        session = 0
    }

    private fun handle(svc: EffectService, i: Intent) {
        if (i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) != SpotifyAuth.PKG) return
        val sid = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (sid <= 0) return
        val mgr = svc.effectManager
        if (i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
            if (!SpotifyAuth.eqEnabled(svc) || MusicService.instance?.isPlaying == true) return // our own player keeps the chain
            if (mgr.rebind(sid)) session = sid
        } else if (sid == session) {
            session = 0
            if (mgr.boundSession == sid) mgr.rebind(AudioEffectManager.GLOBAL_SESSION)
        }
    }
}
