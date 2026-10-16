package com.fxsoundlite.app

/**
 * Preset data ported from real FXSound (desktop) exported .fac preset files.
 *
 * Each preset stores its 10-band parametric EQ curve as (frequencyHz, gainDb)
 * points, exactly as exported by FXSound. Android's built-in Equalizer effect
 * usually reports a DIFFERENT band count/frequency layout per device (often 5
 * bands), so we can't just copy the 10 values 1:1 onto the device's bands.
 * Instead AudioEffectManager resamples this curve at whatever center
 * frequencies the device's real Equalizer reports, using log-frequency
 * interpolation -- the standard way to redraw an EQ curve onto a different
 * band layout without changing its shape.
 *
 * The "Main" sliders in the .fac format were reverse-engineered by comparing
 * a parsed file against a screenshot of the real FXSound desktop UI showing
 * the "General" preset (Clarity=4, Ambience=0, Surround=2, Dynamic Boost=5,
 * Bass Boost=5). Dividing each raw Main value by 12 reproduced all five
 * displayed values exactly:
 *   Main 0 (raw 50 -> 4)  = Clarity
 *   Main 1 (raw 20 -> 2)  = Surround Sound
 *   Main 2 (raw  0 -> 0)  = Ambience        (0 in every single built-in
 *                                             preset -- FXSound ships them
 *                                             all with Ambience off)
 *   Main 3                = unidentified; not shown in the main effects
 *                                             panel, left unused here
 *   Main 4 (raw 60 -> 5)  = Dynamic Boost   (this is the only field seen
 *                                             going above 120, e.g. 115 for
 *                                             the Transcription preset)
 *   Main 5 (raw 60 -> 5)  = Bass Boost
 *
 * Raw values run roughly 0-120 for a "0-10" display; we rescale that onto
 * Android's native ranges (BassBoost/Virtualizer strength is 0-1000,
 * LoudnessEnhancer gain is in millibels) with the same 120 -> 1000 factor,
 * so "Dynamic Boost 10" and "Bass Boost 10" line up with roughly the top of
 * Android's own range instead of a made-up number.
 */
object Presets {

    data class EqPoint(val freqHz: Double, val gainDb: Double)

    data class Preset(
        val name: String,
        val curve: List<EqPoint>,
        val bassBoost0to1000: Short,
        val virtualizer0to1000: Short,
        val loudnessMb: Int,
        val ambience0to1000: Short,
        val clarity0to100: Int
    )

    val ALL = listOf(
        Preset("Default", listOf(EqPoint(62.5, 0.0), EqPoint(121.5, 0.0), EqPoint(225.0, 0.0), EqPoint(416.5, 0.0), EqPoint(770.5, 0.0), EqPoint(1425.0, 0.0), EqPoint(2645.0, 0.0), EqPoint(4895.0, 0.0), EqPoint(9060.0, 0.0), EqPoint(13885.0, 0.0)), 0, 0, 0, 0, 0),
        // "Auto" is a placeholder entry so it renders as a chip right next to
        // Default, in the same order/selection mechanism as every other preset.
        // Its field values below are never actually sent to the audio engine --
        // MainActivity special-cases the click (name == "Auto") and calls
        // AudioEffectManager.applyAutoPreset() instead of applyPreset(this),
        // which builds the real curve/strengths at tap-time based on the
        // device's detected performance tier (see AudioEffectManager.kt).
        Preset("Auto", listOf(EqPoint(62.5, 0.0), EqPoint(121.5, 0.0), EqPoint(225.0, 0.0), EqPoint(416.5, 0.0), EqPoint(770.5, 0.0), EqPoint(1425.0, 0.0), EqPoint(2645.0, 0.0), EqPoint(4895.0, 0.0), EqPoint(9060.0, 0.0), EqPoint(13885.0, 0.0)), 0, 0, 0, 0, 0),
        Preset("General", listOf(EqPoint(62.5, 0.0), EqPoint(115.0, 0.0), EqPoint(250.0, 1.0), EqPoint(450.0, 2.0), EqPoint(630.0, 0.0), EqPoint(1250.0, -1.0), EqPoint(2700.0, 0.0), EqPoint(5300.0, -1.0), EqPoint(7500.0, -2.0), EqPoint(13000.0, 0.0)), 500, 167, 500, 0, 42),
        Preset("Music", listOf(EqPoint(62.5, 0.0), EqPoint(110.0, 2.0), EqPoint(250.0, 2.0), EqPoint(370.0, 1.0), EqPoint(650.0, 0.0), EqPoint(1200.0, 0.0), EqPoint(2130.0, 0.0), EqPoint(4550.0, -1.0), EqPoint(6850.0, 0.0), EqPoint(16000.0, 2.0)), 500, 292, 167, 0, 42),
        Preset("Voice", listOf(EqPoint(62.5, 0.0), EqPoint(115.734, -4.0), EqPoint(214.311, -2.0), EqPoint(396.85, 2.0), EqPoint(734.867, 4.0), EqPoint(1360.79, 5.0), EqPoint(3430.8, 3.0), EqPoint(5250.0, 3.0), EqPoint(6300.0, 5.0), EqPoint(11770.0, -11.0)), 0, 0, 792, 0, 60),
        Preset("Volume Boost", listOf(EqPoint(62.5, 0.0), EqPoint(101.0, 3.0), EqPoint(240.0, 2.0), EqPoint(396.85, 2.0), EqPoint(734.867, 0.0), EqPoint(1360.79, 0.0), EqPoint(2519.84, 1.0), EqPoint(4670.0, 1.0), EqPoint(11760.0, 2.0), EqPoint(16000.0, 2.0)), 292, 167, 858, 0, 27),
        Preset("Gaming", listOf(EqPoint(62.5, 0.0), EqPoint(128.75, 0.0), EqPoint(238.311, 2.0), EqPoint(444.0, 2.0), EqPoint(805.0, 2.0), EqPoint(1360.79, 0.0), EqPoint(2519.84, -1.0), EqPoint(4400.12, -1.0), EqPoint(7930.48, 2.0), EqPoint(12570.0, 2.0)), 292, 0, 708, 0, 29),
        Preset("Classic Processing", listOf(EqPoint(62.5, 0.0), EqPoint(115.734, 0.0), EqPoint(214.311, 0.0), EqPoint(396.85, 0.0), EqPoint(734.867, 0.0), EqPoint(1360.79, 0.0), EqPoint(2519.84, 0.0), EqPoint(4666.12, 0.0), EqPoint(8640.48, 0.0), EqPoint(13500.0, 0.0)), 583, 292, 500, 0, 50),
        Preset("Light Processing", listOf(EqPoint(62.5, 0.0), EqPoint(115.734, -1.0), EqPoint(214.311, 1.0), EqPoint(396.85, 1.0), EqPoint(734.867, -1.0), EqPoint(1360.79, -1.0), EqPoint(2519.84, -2.0), EqPoint(4666.12, -1.0), EqPoint(8640.48, -1.0), EqPoint(13600.0, 1.0)), 167, 0, 42, 0, 21),
        Preset("Bass Boost", listOf(EqPoint(62.5, 0.0), EqPoint(98.0, 3.0), EqPoint(158.3, 3.0), EqPoint(345.0, 2.0), EqPoint(541.867, 1.0), EqPoint(1170.0, -1.0), EqPoint(2519.84, -1.0), EqPoint(4666.12, -1.0), EqPoint(8640.48, -1.0), EqPoint(14650.0, 0.0)), 625, 292, 167, 0, 25),
        Preset("Streaming Video", listOf(EqPoint(62.5, 0.0), EqPoint(115.734, 0.0), EqPoint(214.311, 0.0), EqPoint(396.85, 0.0), EqPoint(734.867, 1.0), EqPoint(1360.79, 1.0), EqPoint(2519.84, 1.0), EqPoint(5350.0, -1.0), EqPoint(8640.48, 0.0), EqPoint(13800.0, 2.0)), 292, 292, 450, 0, 29),
        Preset("Movies", listOf(EqPoint(62.5, 0.0), EqPoint(115.734, 0.0), EqPoint(250.0, 2.0), EqPoint(396.85, 0.0), EqPoint(734.867, 2.0), EqPoint(1360.79, 2.0), EqPoint(2519.84, 1.0), EqPoint(5350.0, -1.0), EqPoint(8640.48, 0.0), EqPoint(13800.0, 2.0)), 292, 417, 708, 0, 50),
        Preset("TV", listOf(EqPoint(62.5, 0.0), EqPoint(115.734, 0.0), EqPoint(250.0, 1.0), EqPoint(396.85, 0.0), EqPoint(734.867, 1.0), EqPoint(1360.79, 0.0), EqPoint(2519.84, 1.0), EqPoint(5350.0, -1.0), EqPoint(8640.48, -1.0), EqPoint(13800.0, 2.0)), 375, 417, 500, 0, 42),
        Preset("Transcription", listOf(EqPoint(62.5, 0.0), EqPoint(86.0, -12.0), EqPoint(250.0, 7.0), EqPoint(293.0, 2.0), EqPoint(615.0, -1.0), EqPoint(1320.0, 7.0), EqPoint(3430.0, 0.0), EqPoint(4630.0, 10.0), EqPoint(6360.0, 3.0), EqPoint(11770.0, -12.0)), 625, 0, 958, 0, 83),
        // Dolby-style: wide, spacious and clean -- strong Surround, a gentle low shelf, slightly
        // dipped low mids (less muddiness), clear dialogue range and some air on top. Inspired by
        // that kind of tuning; it is NOT licensed Dolby technology.
        Preset("Dolby Style", listOf(EqPoint(62.5, 2.0), EqPoint(115.0, 2.0), EqPoint(250.0, 0.0), EqPoint(450.0, -1.0), EqPoint(800.0, 0.0), EqPoint(1400.0, 1.0), EqPoint(2700.0, 2.0), EqPoint(5000.0, 1.0), EqPoint(8500.0, 2.0), EqPoint(14000.0, 3.0)), 375, 750, 500, 0, 50),
        // DTS-style: punchy and bright -- tight bass, forward mids/presence, crisp highs, higher
        // loudness, medium Surround. Inspired by that kind of tuning; NOT licensed DTS technology.
        Preset("DTS Style", listOf(EqPoint(62.5, 3.0), EqPoint(110.0, 4.0), EqPoint(240.0, 1.0), EqPoint(430.0, -1.0), EqPoint(780.0, 0.0), EqPoint(1400.0, 2.0), EqPoint(2800.0, 3.0), EqPoint(5200.0, 3.0), EqPoint(9000.0, 2.0), EqPoint(14000.0, 2.0)), 542, 500, 708, 0, 58),
    )

    fun byName(name: String?): Preset = ALL.find { it.name == name } ?: ALL[0]
}
