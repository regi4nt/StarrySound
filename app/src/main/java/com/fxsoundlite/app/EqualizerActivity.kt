package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fxsoundlite.app.databinding.ActivityEqualizerBinding

/**
 * Full equalizer screen -- presets, Master Gain, the per-band sliders and the
 * sound-effect sliders (Bass Boost, Surround, Loudness/volume amplifier,
 * Ambience, Volume Leveling).
 * Opened from the EQ button on Home (HomeFragment); the back arrow returns
 * there. Like SettingsActivity it binds to the already-running EffectService
 * instead of creating its own AudioEffectManager (there must only ever be
 * one alive -- see AudioEffectManager's class doc).
 */
class EqualizerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEqualizerBinding
    private var effectManager: AudioEffectManager? = null
    private var effectService: EffectService? = null
    private var bound = false

    // Kept so Master Gain can refresh existing sliders in place instead of
    // tearing down and rebuilding the whole EQ row on every drag tick.
    private data class BandRow(val band: Short, val seekBar: SeekBar, val valueLabel: TextView)
    private val bandRows = mutableListOf<BandRow>()

    private val powerListener: (Boolean) -> Unit = { on ->
        // Off/On pressed on the notification or widget: mirror it on the switch (its listener does the rest).
        if (binding.masterSwitch.isChecked != on) binding.masterSwitch.isChecked = on else applyPowerVisualState(on)
    }

    /** Preset currently highlighted on screen; lets [presetListener] ignore changes
     *  this screen made itself and redraw only for outside ones (notification/widget). */
    private var shownPreset: String? = null
    private val presetListener: () -> Unit = {
        val now = effectManager?.prefs?.getString("last_preset", "Flat")
        if (effectManager != null && now != shownPreset) {
            buildEqBands()          // band sliders
            refreshEffectSliders()  // bass / surround / loudness / ambience / leveling
            setupPresets()          // chip highlight (also updates shownPreset)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EffectService.LocalBinder
            effectService = binder.getService()
            effectManager = effectService?.effectManager
            bound = true
            onEngineReady()
            // Dim/undim if "Off"/"On" is pressed on the notification or widget meanwhile.
            effectService?.addPowerListener(powerListener)
            effectService?.addPresetListener(presetListener)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            effectService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        binding = ActivityEqualizerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, EffectService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        effectService?.removePowerListener(powerListener)
        effectService?.removePresetListener(presetListener)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun onEngineReady() {
        setupMasterGain()
        buildEqBands()
        setupEffectSliders()
        setupPresets()
        setupMasterToggle()
        applyPowerVisualState(em().isEnabled)
    }

    private fun setupMasterToggle() {
        binding.masterSwitch.setOnCheckedChangeListener(null)
        binding.masterSwitch.isChecked = em().isEnabled
        binding.masterSwitch.setOnCheckedChangeListener { _, checked ->
            em().setMasterEnabled(checked)
            applyPowerVisualState(checked)
            // Off = no notification (effects aren't running); on = restore it.
            effectService?.setNotificationVisible(checked)
        }
    }

    private fun em() = effectManager!!

    /** Same dim-and-disable treatment Home gives its controls when effects are off. */
    private fun applyPowerVisualState(on: Boolean) {
        if (!::binding.isInitialized) return
        binding.controlsPanel.alpha = if (on) 1.0f else 0.4f
        setEnabledRecursive(binding.controlsPanel, on)
    }

    private fun setEnabledRecursive(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        // Walk every ViewGroup (each band's SeekBar sits inside a FrameLayout).
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setEnabledRecursive(view.getChildAt(i), enabled)
        }
    }

    private fun setupMasterGain() {
        // Centered slider: progress 0..2400 maps to -1200..+1200 mB (-12..+12 dB)
        binding.masterGainSeek.max = 2400
        val currentMb = em().masterGainMb
        binding.masterGainSeek.progress = currentMb + 1200
        binding.masterGainValueLabel.text = formatMasterGainLabel(currentMb)
        binding.masterGainSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val mb = progress - 1200
                binding.masterGainValueLabel.text = formatMasterGainLabel(mb)
                if (fromUser) {
                    em().setMasterGain(mb)
                    refreshEqBandsInPlace()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun formatMasterGainLabel(mb: Int): String {
        val db = mb / 100.0
        val sign = if (db > 0) "+" else ""
        return "$sign${"%.1f".format(db)} dB"
    }

    private fun buildEqBands() {
        val bands = em().bandCount()
        val range = em().bandLevelRangeMb() // [minMb, maxMb]
        binding.eqContainer.removeAllViews()
        bandRows.clear()

        for (i in 0 until bands) {
            val band = i.toShort()
            val freqHz = em().bandFrequencyHz(band)
            val freqLabel = if (freqHz < 1000) "${freqHz}Hz" else "${freqHz / 1000}kHz"

            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_eq_band, binding.eqContainer, false)
            val freqText = itemView.findViewById<TextView>(R.id.bandFreqLabel)
            val valueText = itemView.findViewById<TextView>(R.id.bandValueLabel)
            val seekBar = itemView.findViewById<SeekBar>(R.id.bandSeek)
            freqText.text = freqLabel

            val span = range[1] - range[0] // e.g. 3000 for -1500..1500
            seekBar.max = span
            val saved = em().prefs.getInt("band_$i", 0)
            seekBar.progress = saved - range[0]
            valueText.text = formatDb(saved)

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val levelMb = progress + range[0]
                    valueText.text = formatDb(levelMb)
                    if (fromUser) em().setBandLevel(band, levelMb.toShort())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            // These band sliders are rotated 270° to read as vertical faders, so a
            // drag on them IS a vertical drag gesture — exactly what the enclosing
            // (vertical) ScrollView also watches for. Without this, the ScrollView
            // wins the gesture on the very first move and the thumb never budges.
            // Telling the parent chain not to intercept while the finger is down
            // hands the whole gesture to the SeekBar; releasing lets scrolling
            // resume normally everywhere else on the screen.
            seekBar.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false // let the SeekBar's own touch handling still run
            }

            binding.eqContainer.addView(itemView)
            bandRows.add(BandRow(band, seekBar, valueText))
        }
    }

    /** Refreshes existing EQ sliders from current stored band levels without
     *  rebuilding the row — used after Master Gain shifts every band. */
    private fun refreshEqBandsInPlace() {
        val range = em().bandLevelRangeMb()
        bandRows.forEach { row ->
            val saved = em().prefs.getInt("band_${row.band}", 0)
            row.seekBar.progress = saved - range[0]
            row.valueLabel.text = formatDb(saved)
        }
    }

    private fun formatDb(mb: Int): String {
        val db = mb / 100.0
        val sign = if (db > 0) "+" else ""
        return "$sign${"%.1f".format(db)}"
    }

    // ---- Sound effects: Bass Boost, Surround, Loudness, Ambience, Volume Leveling ----

    private fun setupEffectSliders() {
        // Bass Boost (0..1000 -> 0..100 %)
        binding.bassSeek.max = 1000
        binding.bassSeek.setOnSeekBarChangeListener(simpleListener { v ->
            binding.bassValueLabel.text = "${v / 10}%"
            em().setBassBoost(v.toShort())
        })

        // Surround / Virtualizer (0..1000 -> 0..100 %)
        binding.virtualizerSeek.max = 1000
        binding.virtualizerSeek.setOnSeekBarChangeListener(simpleListener { v ->
            binding.virtualizerValueLabel.text = "${v / 10}%"
            em().setVirtualizer(v.toShort())
        })

        // Loudness / volume amplifier. Ceiling depends on Root Mode (+12 dB, or +30 dB with root).
        binding.loudnessSeek.max = em().currentLoudnessCeilingMb()
        binding.loudnessSeek.setOnSeekBarChangeListener(simpleListener { v ->
            binding.loudnessValueLabel.text = "+${v / 100} dB"
            em().setLoudness(v)
        })

        // Ambience (0..1000 -> 0..100 %)
        binding.ambienceSeek.max = 1000
        binding.ambienceSeek.setOnSeekBarChangeListener(simpleListener { v ->
            binding.ambienceValueLabel.text = "${v / 10}%"
            em().setAmbience(v.toShort())
        })

        // Volume Leveling (0..100 %), needs Android 9+; otherwise show a note instead of a dead slider.
        if (em().isVolumeLevelingSupported()) {
            binding.volumeLevelingSeek.max = 100
            binding.volumeLevelingSeek.setOnSeekBarChangeListener(simpleListener { v ->
                binding.volumeLevelingValueLabel.text = "$v%"
                em().setVolumeLeveling(v.toShort())
            })
        } else {
            binding.volumeLevelingRow.isEnabled = false
            binding.volumeLevelingSeek.isEnabled = false
            binding.volumeLevelingSeek.visibility = View.GONE
            binding.volumeLevelingUnsupportedText.visibility = View.VISIBLE
        }

        refreshEffectSliders()
    }

    /** Re-reads every effect value from storage and moves the sliders to match.
     *  Used on first show and after a preset is applied. Setting progress in code
     *  does not touch the engine (the listeners only act on user drags). */
    private fun refreshEffectSliders() {
        val bass = em().prefs.getInt("bass", 0)
        binding.bassSeek.progress = bass
        binding.bassValueLabel.text = "${bass / 10}%"

        val virt = em().prefs.getInt("virtualizer", 0)
        binding.virtualizerSeek.progress = virt
        binding.virtualizerValueLabel.text = "${virt / 10}%"

        val loud = em().loudnessDisplayMb()
        binding.loudnessSeek.progress = loud
        binding.loudnessValueLabel.text = "+${loud / 100} dB"

        val amb = em().prefs.getInt("ambience", 0)
        binding.ambienceSeek.progress = amb
        binding.ambienceValueLabel.text = "${amb / 10}%"

        if (em().isVolumeLevelingSupported()) {
            val vl = em().prefs.getInt("volume_leveling", 0)
            binding.volumeLevelingSeek.progress = vl
            binding.volumeLevelingValueLabel.text = "$vl%"
        }
    }

    private fun simpleListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun setupPresets() {
        binding.presetChipContainer.removeAllViews()
        val lastPreset = em().prefs.getString("last_preset", "Flat")
        shownPreset = lastPreset
        val inflater = LayoutInflater.from(this)
        val chips = mutableListOf<View>()

        Presets.ALL.forEach { preset ->
            val chip = inflater.inflate(R.layout.item_preset_chip, binding.presetChipContainer, false)
            val label = chip.findViewById<TextView>(R.id.chipLabel)
            label.text = preset.name
            chip.isSelected = preset.name == lastPreset
            chip.setOnClickListener {
                // "Auto" isn't a fixed curve like the others -- it's computed
                // per-device (see AudioEffectManager.applyAutoPreset()) so it
                // lands on the best-sounding, non-maxed-out settings this
                // specific device can comfortably drive.
                shownPreset = preset.name // our own change: no redraw needed
                if (preset.name == "Auto") em().applyAutoPreset() else em().applyPreset(preset)
                chips.forEach { c -> c.isSelected = false }
                chip.isSelected = true
                buildEqBands() // refresh sliders to reflect preset
                refreshEffectSliders() // presets also change bass/surround/loudness/ambience
            }
            binding.presetChipContainer.addView(chip)
            chips.add(chip)
        }
    }
}
