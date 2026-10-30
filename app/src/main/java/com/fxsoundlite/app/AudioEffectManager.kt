package com.fxsoundlite.app

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import kotlin.math.ln
import kotlin.math.max

/**
 * Central controller for all audio effects.
 *
 * Design notes (read this before assuming it does more than it does):
 * - We attach every effect to the GLOBAL output mix by using audioSessionId = 0.
 *   On stock / AOSP-based Android 7+ this makes the effect apply system-wide
 *   (music apps, YouTube, games, etc.) WITHOUT root — this is the same trick
 *   apps like "Music Volume EQ" use. Some heavily-modified OEM ROMs (rare)
 *   ignore session-0 effects; there is no universal non-root fix for that.
 * - "Extended range" boost (LoudnessEnhancer beyond +12 dB) does NOT technically
 *   require root either — it's an Android API capability. We gate it behind
 *   Root Mode purely as a safety switch (extreme gain = distortion risk), so the
 *   user explicitly opts into "I know this can clip audio."
 * - What genuinely NEEDS root here: RootUtils.restartAudioServer() (kills the
 *   audioserver process so effects reload cleanly if a device gets stuck) and
 *   writing the boot-reapply flag earlier in the boot sequence.
 * - Exactly ONE instance of this class should exist for the whole app — see
 *   EffectService, which owns it and is bound to by the UI. Two independent
 *   instances fighting over the same session-0 effects is what made presets
 *   look like they "did nothing" before.
 *
 * See Presets.kt for how the .fac preset fields (Clarity, Ambience, Surround,
 * Dynamic Boost, Bass Boost) were matched to these Android effects — it was
 * verified against a screenshot of the real desktop app's "General" preset,
 * not just guessed.
 */
class AudioEffectManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioEffectManager"
        private const val PREFS = "fxsoundlite_prefs"
        const val GLOBAL_SESSION = 0

        // Non-root safe ceiling for loudness gain (millibels). 1200 mB = +12 dB
        const val LOUDNESS_SAFE_MAX_MB = 1200
        // Root-mode ceiling. 3000 mB = +30 dB (built-in limiter still protects against hard clipping)
        const val LOUDNESS_EXTENDED_MAX_MB = 3000

        // DynamicsProcessing (Volume Leveling) is only available from Android 9 (API 28).
        private const val DP_CHANNEL_COUNT = 2
    }

    /** Audio session the EQ / bass / virtualizer / loudness / leveling chain is attached to:
     *  [GLOBAL_SESSION] (system-wide mix) by default, or an app-owned session while MusicService
     *  plays a local file (see [rebind]). Many modern devices ignore session-0 effects, which is
     *  why the in-app player gets its own session. */
    var boundSession: Int = GLOBAL_SESSION
        private set

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var reverb: EnvironmentalReverb? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isRootMode: Boolean
        get() = prefs.getBoolean("root_mode", false)
        set(value) = prefs.edit().putBoolean("root_mode", value).apply()

    var isEnabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** Number of EQ bands available on this device (varies, usually 5). */
    fun bandCount(): Short = equalizer?.numberOfBands ?: 5

    fun bandLevelRangeMb(): IntArray {
        val range = equalizer?.bandLevelRange
        return intArrayOf(range?.get(0)?.toInt() ?: -1500, range?.get(1)?.toInt() ?: 1500)
    }

    fun bandFrequencyHz(band: Short): Int =
        (equalizer?.getCenterFreq(band) ?: 0) / 1000

    /** Call once, e.g. from the foreground service, so effects survive app UI being closed. */
    fun initialize() {
        try {
            equalizer = Equalizer(0, boundSession).apply { enabled = isEnabled }
            bassBoost = BassBoost(0, boundSession).apply { enabled = isEnabled }
            virtualizer = Virtualizer(0, boundSession).apply { enabled = isEnabled }
            loudness = LoudnessEnhancer(boundSession).apply { enabled = false }
            // EnvironmentalReverb is an auxiliary effect: it always stays on the global mix
            // and is created only once, so it survives rebind().
            if (reverb == null) {
                try {
                    reverb = EnvironmentalReverb(0, GLOBAL_SESSION).apply { enabled = false }
                } catch (e: Exception) {
                    // Some OEM audio HALs don't ship EnvironmentalReverb at all; Ambience
                    // just becomes a no-op on those devices instead of crashing the app.
                    Log.w(TAG, "EnvironmentalReverb unavailable on this device: ${e.message}")
                    reverb = null
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    dynamicsProcessing = buildDynamicsProcessing()
                } catch (e: Exception) {
                    // A handful of OEM audio HALs advertise API 28+ but don't actually
                    // implement DynamicsProcessing; Volume Leveling just becomes a
                    // no-op on those devices instead of crashing the app.
                    Log.w(TAG, "DynamicsProcessing unavailable on this device: ${e.message}")
                    dynamicsProcessing = null
                }
            }
            restoreSavedState()
            Log.i(TAG, "Audio effects initialized on global session")
        } catch (e: Exception) {
            // Some OEM audio HALs refuse session 0 effect creation entirely.
            Log.e(TAG, "Failed to init global session effects: ${e.message}")
        }
    }

    /**
     * True while the effect objects exist, still answer the audio server and still match the master
     * switch. False after the audio server died / restarted, the system reclaimed the effects, or
     * another app took control of them. Main thread only.
     */
    fun isHealthy(): Boolean {
        val eq = equalizer ?: return false
        return try {
            eq.hasControl() && eq.enabled == isEnabled && (bassBoost?.enabled ?: isEnabled) == isEnabled
        } catch (e: Exception) {
            false
        }
    }

    /** Throws the effect chain away and builds it again from the saved settings (same session). */
    fun reapply(): Boolean {
        releaseSessionEffects()
        try { reverb?.release() } catch (e: Exception) { android.util.Log.d("AudioEffectManager", "ignored: " + e) }
        reverb = null
        initialize()
        notifyWidgets()
        return equalizer != null
    }

    /** What [diagnose] concluded; the UI turns each into a message. */
    enum class Diagnosis { NOT_CREATED, UNHEALTHY_FIXED, NOT_IN_SERVER, OK, NO_DUMP }

    class DiagnosisResult(val verdict: Diagnosis, val session: Int, val enabled: Boolean, val serverInstances: Int)

    /**
     * Compares what the app believes (effects created, healthy) with what the audio server reports in
     * [dump] (a root `dumpsys media.audio_flinger`). Best effort: the dump layout differs per Android
     * version, so effects are recognised by their type UUID. Main thread only.
     */
    fun diagnose(dump: String?): DiagnosisResult {
        val instances = if (dump == null) 0 else
            Regex("0bed4300-ddd6-11db-8f34-0002a5d5c51b|0634f220-ddd4-11db-a0fc-0002a5d5c51b", RegexOption.IGNORE_CASE)
                .findAll(dump).count()
        var fixed = false
        if (equalizer == null || !isHealthy()) {
            if (!reapply()) return DiagnosisResult(Diagnosis.NOT_CREATED, boundSession, isEnabled, instances)
            fixed = true
        }
        val verdict = when {
            fixed -> Diagnosis.UNHEALTHY_FIXED
            dump == null -> Diagnosis.NO_DUMP
            isEnabled && instances == 0 -> Diagnosis.NOT_IN_SERVER
            else -> Diagnosis.OK
        }
        return DiagnosisResult(verdict, boundSession, isEnabled, instances)
    }

    /** Releases everything that lives on [boundSession] (all but the global reverb). */
    private fun releaseSessionEffects() {
        try { equalizer?.release() } catch (e: Exception) { android.util.Log.d("AudioEffectManager", "ignored: " + e) }
        try { bassBoost?.release() } catch (e: Exception) { android.util.Log.d("AudioEffectManager", "ignored: " + e) }
        try { virtualizer?.release() } catch (e: Exception) { android.util.Log.d("AudioEffectManager", "ignored: " + e) }
        try { loudness?.release() } catch (e: Exception) { android.util.Log.d("AudioEffectManager", "ignored: " + e) }
        try { dynamicsProcessing?.release() } catch (e: Exception) { android.util.Log.d("AudioEffectManager", "ignored: " + e) }
        equalizer = null; bassBoost = null; virtualizer = null; loudness = null
        dynamicsProcessing = null
    }

    fun release() {
        releaseSessionEffects()
        try { reverb?.release() } catch (e: Exception) { android.util.Log.d("AudioEffectManager", "ignored: " + e) }
        reverb = null
    }

    /**
     * Moves the effect chain to [sessionId] (or back to [GLOBAL_SESSION]) and restores all saved
     * settings on it. Returns true when the effects now live on the requested session; if they
     * cannot be created there it falls back to the global mix and returns false. Main thread only,
     * like every other call on this class.
     */
    fun rebind(sessionId: Int): Boolean {
        if (sessionId == boundSession && equalizer != null) return true
        releaseSessionEffects()
        boundSession = sessionId
        initialize()
        if (sessionId != GLOBAL_SESSION && equalizer == null) {
            Log.w(TAG, "Effects unavailable on session $sessionId, falling back to global mix")
            releaseSessionEffects()
            boundSession = GLOBAL_SESSION
            initialize()
            return false
        }
        return equalizer != null
    }

    fun setMasterEnabled(on: Boolean) {
        isEnabled = on
        equalizer?.enabled = on
        bassBoost?.enabled = on
        virtualizer?.enabled = on
        loudness?.enabled = on && (prefs.getInt("loudness_mb", 0) > 0)
        reverb?.enabled = on && (prefs.getInt("ambience", 0) > 0)
        dynamicsProcessing?.enabled = on && (prefs.getInt("volume_leveling", 0) > 0)
        notifyWidgets()
    }

    /** Pushes a fresh RemoteViews update to every home-screen widget instance
     *  so its power toggle / preset label stay in sync with reality no
     *  matter what changed them (in-app UI, the widget itself, or state
     *  restored at boot). Cheap no-op if no widget is currently placed. */
    private fun notifyWidgets() {
        EqWidgetProvider.updateAllWidgets(context)
        onStateChanged?.invoke()
    }

    /** Fired whenever power or the active preset changes, from any source. Only
     *  EffectService sets it (on the one manager it owns) so the ongoing
     *  notification can show the current preset and stay in sync. */
    var onStateChanged: (() -> Unit)? = null

    /** Manual band change (user dragged a band slider). [levelMb] is the level actually heard, i.e.
     *  it already includes Master Gain, so the master-gain-free "base" is derived from it. */
    fun setBandLevel(band: Short, levelMb: Short) {
        writeBand(band, levelMb.toInt())
        prefs.edit().putInt("base_band_$band", levelMb.toInt() - masterGainMb).apply()
    }

    /** Sends one band to the engine and stores what is heard. Does NOT touch the base level. */
    private fun writeBand(band: Short, levelMb: Int) {
        try {
            val range = bandLevelRangeMb()
            val v = levelMb.coerceIn(range[0], range[1])
            equalizer?.setBandLevel(band, v.toShort())
            prefs.edit().putInt("band_$band", v).apply()
        } catch (e: Exception) {
            Log.e(TAG, "setBandLevel failed: ${e.message}")
        }
    }

    /** The band's level WITHOUT Master Gain, never clamped, so Master Gain stays fully reversible
     *  (older installs have no base value yet: derive it from the stored heard level). */
    private fun baseLevelMb(i: Int): Int =
        prefs.getInt("base_band_$i", prefs.getInt("band_$i", 0) - masterGainMb)

    fun setBassBoost(strength0to1000: Short) {
        try {
            bassBoost?.setStrength(strength0to1000)
            prefs.edit().putInt("bass", strength0to1000.toInt()).apply()
        } catch (e: Exception) { Log.e(TAG, "bassBoost failed: ${e.message}") }
    }

    fun setVirtualizer(strength0to1000: Short) {
        try {
            virtualizer?.setStrength(strength0to1000)
            prefs.edit().putInt("virtualizer", strength0to1000.toInt()).apply()
        } catch (e: Exception) { Log.e(TAG, "virtualizer failed: ${e.message}") }
    }

    /** gainMb is the user's INTENDED target, stored as-is (only bounded by the
     *  absolute extended ceiling) so switching Root Mode off and back on never
     *  erases a value the user set while it was on. Only the amount actually
     *  sent to the LoudnessEnhancer is capped to whichever ceiling applies
     *  right now — the stored preference itself is never silently downgraded. */
    fun setLoudness(gainMb: Int) {
        val stored = gainMb.coerceIn(0, LOUDNESS_EXTENDED_MAX_MB)
        prefs.edit().putInt("loudness_mb", stored).apply()
        val applied = stored.coerceIn(0, currentLoudnessCeilingMb())
        try {
            loudness?.setTargetGain(applied)
            loudness?.enabled = applied > 0 && isEnabled
        } catch (e: Exception) { Log.e(TAG, "loudness failed: ${e.message}") }
    }

    fun currentLoudnessCeilingMb(): Int =
        if (isRootMode) LOUDNESS_EXTENDED_MAX_MB else LOUDNESS_SAFE_MAX_MB

    /** What the Loudness slider should actually show right now: the user's
     *  stored value, clamped to the ceiling in effect for the current Root
     *  Mode state (never clamped in storage — see setLoudness() doc). */
    fun loudnessDisplayMb(): Int =
        prefs.getInt("loudness_mb", 0).coerceIn(0, currentLoudnessCeilingMb())

    /**
     * "Ambience" (FXSound's term for a light reverb send that adds room/space).
     * Approximated with Android's EnvironmentalReverb: strength0to1000 controls
     * both room level and reverb level together, kept deliberately subtle since
     * a global-mix reverb is easy to overdo.
     */
    fun setAmbience(strength0to1000: Short) {
        val s = strength0to1000.coerceIn(0, 1000)
        try {
            reverb?.let {
                // roomLevel: -9000..0 mB, reverbLevel: -9000..2000 mB (typical ranges)
                it.roomLevel = (-9000 + (s / 1000f) * 6000).toInt().toShort()
                it.reverbLevel = (-9000 + (s / 1000f) * 7000).toInt().toShort()
                it.decayTime = 900 + (s / 1000f * 600).toInt()
                it.enabled = s > 0 && isEnabled
            }
            prefs.edit().putInt("ambience", s.toInt()).apply()
        } catch (e: Exception) { Log.e(TAG, "ambience failed: ${e.message}") }
    }

    /** DynamicsProcessing needs Android 9 (API 28)+; UI should hide/disable
     *  the Volume Leveling control on older devices instead of showing a
     *  slider that silently does nothing. */
    fun isVolumeLevelingSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * "Volume Leveling" — light single-band dynamics compression on the global
     * mix so quiet passages (dialogue) and loud ones (music swells, ads) end up
     * closer in perceived loudness, instead of the user constantly reaching for
     * the volume rocker. This is NOT the same as Loudness Boost (LoudnessEnhancer
     * above, a flat gain) or Bass Boost/Dynamic Boost from presets — those raise
     * level, this narrows the *range* between loud and quiet.
     *
     * strength0to100 sweeps from a gentle compressor (1.5:1 @ -12dB) to a fairly
     * aggressive one (6:1 @ -30dB) with proportional makeup gain, so louder
     * strengths noticeably flatten dynamics rather than just adding volume.
     * The safety limiter configured in buildDynamicsProcessing() always stays on
     * to catch any peak the makeup gain would otherwise push into clipping.
     */
    fun setVolumeLeveling(strength0to100: Short) {
        val s = strength0to100.coerceIn(0, 100)
        if (isVolumeLevelingSupported()) {
            try {
                dynamicsProcessing?.let { dp ->
                    val t = s / 100f
                    val ratio = 1.5f + t * 4.5f        // 1.5:1 .. 6:1
                    val thresholdDb = -12f - t * 18f    // -12dB .. -30dB (lower = compresses more of the range)
                    val makeupDb = t * 6f                // up to +6dB post gain to bring quiet parts back up
                    for (ch in 0 until DP_CHANNEL_COUNT) {
                        val mbc = dp.getMbcBandByChannelIndex(ch, 0)
                        mbc.isEnabled = s > 0
                        mbc.attackTime = 15f
                        mbc.releaseTime = 150f
                        mbc.ratio = ratio
                        mbc.threshold = thresholdDb
                        mbc.kneeWidth = 6f
                        mbc.noiseGateThreshold = -60f  // keep background noise/silence from being pumped up
                        mbc.expanderRatio = 1f
                        mbc.preGain = 0f
                        mbc.postGain = makeupDb
                        dp.setMbcBandByChannelIndex(ch, 0, mbc)
                    }
                    dp.enabled = s > 0 && isEnabled
                }
            } catch (e: Exception) { Log.e(TAG, "volumeLeveling failed: ${e.message}") }
        }
        prefs.edit().putInt("volume_leveling", s.toInt()).apply()
    }

    /** Single full-band multiband-compressor (mbc bandCount=1) plus a limiter
     *  used purely as a safety ceiling — see setVolumeLeveling() doc. */
    private fun buildDynamicsProcessing(): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
            DP_CHANNEL_COUNT,
            false, 0,   // pre-EQ: unused, we don't touch tonal balance here
            true, 1,    // MBC: 1 band = simple full-spectrum compressor
            false, 0,   // post-EQ: unused
            true        // limiter: always configured as a safety net
        ).build()

        val dp = DynamicsProcessing(0, boundSession, config)
        for (ch in 0 until DP_CHANNEL_COUNT) {
            val limiter = dp.getLimiterByChannelIndex(ch)
            limiter.isEnabled = true
            limiter.attackTime = 3f
            limiter.releaseTime = 100f
            limiter.ratio = 10f
            limiter.threshold = -1f
            limiter.postGain = 0f
            dp.setLimiterByChannelIndex(ch, limiter)
        }
        dp.enabled = false // stays off until the user turns Volume Leveling on
        return dp
    }

    /** Log-frequency-interpolate a preset's curve onto this device's actual EQ bands. */
    fun applyPreset(preset: Presets.Preset) {
        val bands = bandCount()
        val masterGain = masterGainMb
        for (i in 0 until bands) {
            val freqHz = bandFrequencyHz(i.toShort()).toDouble()
            var gainDb = interpolateCurve(preset.curve, freqHz)
            // Approximate "Clarity" (no direct Android equivalent) as a gentle
            // extra lift on the top two device bands, proportional to its 0-100 value.
            if (i >= bands - 2) {
                gainDb += (preset.clarity0to100 / 100.0) * 2.0
            }
            val baseMb = (gainDb * 100).toInt()
            prefs.edit().putInt("base_band_$i", baseMb).apply()
            writeBand(i.toShort(), baseMb + masterGain)
        }
        setBassBoost(preset.bassBoost0to1000)
        setVirtualizer(preset.virtualizer0to1000)
        setLoudness(preset.loudnessMb)
        setAmbience(preset.ambience0to1000)
        prefs.edit().putString("last_preset", preset.name).apply()
        OutputPresets.remember(context, preset.name) // "Auto preset per output" (no-op while off)
        notifyWidgets()
    }

    /**
     * Rough device capability tier used only to pick sensible "Auto" preset
     * strengths -- this is NOT a benchmark, just cheap signals (RAM, CPU core
     * count, the OS's own low-RAM flag) that are readily available without
     * extra permissions and correlate well enough with how much headroom a
     * device has for continuous global-session audio DSP.
     */
    enum class DeviceTier { LOW, MID, HIGH }

    fun detectDeviceTier(): DeviceTier {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            val cores = Runtime.getRuntime().availableProcessors()
            when {
                am.isLowRamDevice || totalRamGb < 3.0 || cores <= 4 -> DeviceTier.LOW
                totalRamGb < 6.0 || cores <= 6 -> DeviceTier.MID
                else -> DeviceTier.HIGH
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device tier detection failed, defaulting to MID: ${e.message}")
            DeviceTier.MID
        }
    }

    /**
     * Auto preset tuning per tier. Deliberately conservative -- none of these
     * ever approach the effects' real maximums (bass/virtualizer/ambience cap
     * at 1000, loudness safe ceiling is 1200 mB, clarity caps at 100). The
     * goal is the best-sounding result a given device can drive comfortably,
     * not the loudest/heaviest possible chain:
     *  - LOW tier: gentle EQ lift only, bass boost kept light, Virtualizer
     *    and Ambience (EnvironmentalReverb) turned OFF entirely since they're
     *    the two priciest DSP blocks to keep running continuously on the
     *    global session, and Volume Leveling (DynamicsProcessing) is skipped
     *    below in applyAutoPreset() for the same reason.
     *  - MID tier: a moderate, well-rounded curve with every effect on but
     *    at modest strength.
     *  - HIGH tier: a fuller curve and stronger (still not maxed) strengths,
     *    since capable devices can drive the whole chain without strain.
     */
    private fun autoPresetFor(tier: DeviceTier): Presets.Preset = when (tier) {
        DeviceTier.LOW -> Presets.Preset(
            "Auto",
            listOf(
                Presets.EqPoint(62.5, 0.0), Presets.EqPoint(121.5, 0.0), Presets.EqPoint(225.0, 0.0),
                Presets.EqPoint(416.5, 0.5), Presets.EqPoint(770.5, 0.5), Presets.EqPoint(1425.0, 1.0),
                Presets.EqPoint(2645.0, 1.0), Presets.EqPoint(4895.0, 0.5), Presets.EqPoint(9060.0, 0.5),
                Presets.EqPoint(13885.0, 0.0)
            ),
            bassBoost0to1000 = 100, virtualizer0to1000 = 0, loudnessMb = 150,
            ambience0to1000 = 0, clarity0to100 = 15
        )
        DeviceTier.MID -> Presets.Preset(
            "Auto",
            listOf(
                Presets.EqPoint(62.5, 0.0), Presets.EqPoint(121.5, 0.5), Presets.EqPoint(225.0, 1.0),
                Presets.EqPoint(416.5, 1.0), Presets.EqPoint(770.5, 0.5), Presets.EqPoint(1425.0, 0.0),
                Presets.EqPoint(2645.0, 0.5), Presets.EqPoint(4895.0, 1.0), Presets.EqPoint(9060.0, 1.0),
                Presets.EqPoint(13885.0, 0.5)
            ),
            bassBoost0to1000 = 250, virtualizer0to1000 = 150, loudnessMb = 300,
            ambience0to1000 = 100, clarity0to100 = 30
        )
        DeviceTier.HIGH -> Presets.Preset(
            "Auto",
            listOf(
                Presets.EqPoint(62.5, 0.0), Presets.EqPoint(121.5, 1.0), Presets.EqPoint(225.0, 1.5),
                Presets.EqPoint(416.5, 1.0), Presets.EqPoint(770.5, 0.5), Presets.EqPoint(1425.0, 0.0),
                Presets.EqPoint(2645.0, 0.5), Presets.EqPoint(4895.0, 1.5), Presets.EqPoint(9060.0, 2.0),
                Presets.EqPoint(13885.0, 1.0)
            ),
            bassBoost0to1000 = 350, virtualizer0to1000 = 250, loudnessMb = 450,
            ambience0to1000 = 150, clarity0to100 = 45
        )
    }

    /**
     * Applies the "Auto" preset: detects this device's performance tier and
     * routes through the normal applyPreset() pipeline (same EQ interpolation,
     * bass/virtualizer/loudness/ambience application, and pref persistence as
     * every hand-authored preset), then separately tunes Volume Leveling --
     * which lives outside Preset's fields -- for that tier. LOW tier leaves it
     * off since DynamicsProcessing is the single heaviest effect block here.
     */
    fun applyAutoPreset() {
        val tier = detectDeviceTier()
        applyPreset(autoPresetFor(tier))
        val volumeLevelingStrength: Short = when (tier) {
            DeviceTier.LOW -> 0
            DeviceTier.MID -> 15
            DeviceTier.HIGH -> 30
        }
        if (isVolumeLevelingSupported()) setVolumeLeveling(volumeLevelingStrength)
        // applyPreset() above already stamped "last_preset" with the tier's
        // real preset name and pushed a widget refresh; overwrite it with
        // "Auto" (matching what the app's own preset chips show) and push once more.
        prefs.edit().putString("last_preset", "Auto").putString("auto_detected_tier", tier.name).apply()
        OutputPresets.remember(context, "Auto")
        notifyWidgets()
    }

    /**
     * "Master Gain" (from the real app's separate Equalizer tab — a flat
     * offset applied on top of whatever curve is currently active, NOT part
     * of the per-preset .fac data). Implemented as a delta shift on top of
     * whatever band levels are already in effect, so it works the same
     * whether the current curve came from a preset or manual dragging.
     */
    var masterGainMb: Int
        get() = prefs.getInt("master_gain_mb", 0)
        private set(value) = prefs.edit().putInt("master_gain_mb", value).apply()

    fun setMasterGain(newMb: Int) {
        // Read the bases BEFORE changing the stored master gain (the migration fallback in
        // baseLevelMb() depends on the old value), then persist them so they are fixed from now on.
        val bases = (0 until bandCount().toInt()).map { baseLevelMb(it) }
        val ed = prefs.edit()
        bases.forEachIndexed { i, b -> ed.putInt("base_band_$i", b) }
        ed.apply()
        masterGainMb = newMb
        bases.forEachIndexed { i, b -> writeBand(i.toShort(), b + newMb) }
    }

    /**
     * Linear interpolation in log-frequency space between the two nearest curve
     * points (standard technique for redrawing an EQ curve at different band
     * positions without warping its shape). Clamps to the nearest edge value
     * outside the curve's own frequency range.
     */
    private fun interpolateCurve(curve: List<Presets.EqPoint>, freqHz: Double): Double {
        if (curve.isEmpty()) return 0.0
        val f = max(20.0, freqHz)
        if (f <= curve.first().freqHz) return curve.first().gainDb
        if (f >= curve.last().freqHz) return curve.last().gainDb
        for (i in 0 until curve.size - 1) {
            val a = curve[i]; val b = curve[i + 1]
            if (f in a.freqHz..b.freqHz) {
                val logA = ln(a.freqHz); val logB = ln(b.freqHz); val logF = ln(f)
                val t = if (logB > logA) (logF - logA) / (logB - logA) else 0.0
                return a.gainDb + t * (b.gainDb - a.gainDb)
            }
        }
        return 0.0
    }

    private fun restoreSavedState() {
        val bands = bandCount()
        for (i in 0 until bands) {
            val saved = prefs.getInt("band_$i", 0)
            try { equalizer?.setBandLevel(i.toShort(), saved.toShort()) } catch (e: Exception) { android.util.Log.d("AudioEffectManager", "ignored: " + e) }
        }
        setBassBoost(prefs.getInt("bass", 0).toShort())
        setVirtualizer(prefs.getInt("virtualizer", 0).toShort())
        setLoudness(prefs.getInt("loudness_mb", 0))
        setAmbience(prefs.getInt("ambience", 0).toShort())
        setVolumeLeveling(prefs.getInt("volume_leveling", 0).toShort())
        setMasterEnabled(isEnabled)
    }
}
