package com.fxsoundlite.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.StringRes

/**
 * "Auto preset per output": every kind of audio output keeps its own EQ preset and the app switches
 * to it when the output changes (headphones plugged in / out, Bluetooth connecting, ...).
 *
 * Three output kinds are told apart (see [current]):
 *   - [Output.BLUETOOTH]  A2DP / LE Audio / hearing aid, wins over the others when connected
 *   - [Output.WIRED]      3.5 mm jack headphones/headset and USB audio (USB-C headphones, DACs)
 *   - [Output.SPEAKER]    everything else: the phone's own speaker / earpiece
 *
 * Setup is mostly automatic: while the feature is on, the preset the user picks (chips in the
 * Equalizer, widget, notification buttons) is remembered for the output that is active at that moment
 * ([remember], called from AudioEffectManager). An output without a remembered preset is left
 * alone ("No change"). Settings > Advanced holds the switch and lets the user choose each output's preset directly.
 *
 * EffectService watches for output changes (AudioDeviceCallback) and calls [refresh].
 */
object OutputPresets {

    enum class Output(val key: String, @StringRes val labelRes: Int) {
        SPEAKER("speaker", R.string.out_speaker),
        WIRED("wired", R.string.out_wired),
        BLUETOOTH("bluetooth", R.string.out_bluetooth)
    }

    // Same file as AudioEffectManager's own settings ("last_preset" lives there too).
    private const val PREFS = "fxsoundlite_prefs"
    private const val K_ON = "outpreset_on"
    private fun key(o: Output) = "outpreset_${o.key}"

    /** Output the last [refresh] reacted to; null = not evaluated yet (first run applies the mapping). */
    @Volatile var lastOutput: Output? = null

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = p(c).getBoolean(K_ON, false)

    fun setEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean(K_ON, on).apply()
        lastOutput = null // re-evaluate from scratch next time
    }

    /** Preset name chosen for [o] ("Auto" is a valid name), or null for "No change". */
    fun presetFor(c: Context, o: Output): String? = p(c).getString(key(o), null)?.takeIf { it.isNotBlank() }

    fun setPresetFor(c: Context, o: Output, name: String?) {
        val e = p(c).edit()
        if (name.isNullOrBlank()) e.remove(key(o)) else e.putString(key(o), name)
        e.apply()
    }

    // ------------------------------------------------------------------ detection

    /** The output audio would play through right now. */
    fun current(c: Context): Output {
        val am = c.applicationContext.getSystemService(AudioManager::class.java) ?: return Output.SPEAKER
        var wired = false
        var bluetooth = false
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST -> bluetooth = true
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> wired = true
            }
        }
        return when {
            bluetooth -> Output.BLUETOOTH
            wired -> Output.WIRED
            else -> Output.SPEAKER
        }
    }

    // ------------------------------------------------------------------ behaviour

    /** Called whenever a preset is applied: with the feature on, it becomes the active output's preset. */
    fun remember(c: Context, presetName: String) {
        if (!enabled(c)) return
        try { setPresetFor(c, current(c), presetName) } catch (_: Exception) { }
    }

    /** Right after the user switches the feature on: what is playing now is this output's preset. */
    fun seedCurrent(c: Context, em: AudioEffectManager) {
        val o = current(c)
        if (presetFor(c, o) == null) {
            em.prefs.getString("last_preset", null)?.let { setPresetFor(c, o, it) }
        }
        lastOutput = o
    }

    /**
     * Applies the active output's preset when the output changed since the last call (or [force]).
     * Does nothing for an output without a remembered preset, and never re-applies a preset that is
     * already active, so manual slider tweaks are not thrown away by unrelated device events.
     */
    fun refresh(c: Context, em: AudioEffectManager, force: Boolean = false) {
        if (!enabled(c)) { lastOutput = null; return }
        val out = current(c)
        if (!force && out == lastOutput) return
        lastOutput = out
        val name = presetFor(c, out) ?: return
        if (em.prefs.getString("last_preset", null) == name) return
        if (name == "Auto") em.applyAutoPreset()
        else if (Presets.ALL.any { it.name == name }) em.applyPreset(Presets.byName(name))
    }
}
