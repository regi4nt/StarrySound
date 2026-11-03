package com.fxsoundlite.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Calendar

/**
 * Handles the "Appearance" section in Settings: Light/Dark/System mode, plus
 * an accent color sub-theme -- "Default" (black in Light mode, white in Dark
 * mode -- see accent_default/_default_on in colors.xml + values-night), plus
 * Red, Yellow, Blue, Orange, Green, Purple.
 *
 * Mode (light/dark/system) is delegated to AppCompatDelegate's night mode --
 * paired with the day/night-qualified palettes in themes.xml, this recreates
 * the whole app UI with the right base colors automatically, the same way
 * LocaleHelper.apply() recreates it for language.
 *
 * Accent color isn't something AppCompatDelegate knows about, so it's stored
 * in SharedPreferences ourselves and applied as a ThemeOverlay on each
 * Activity's theme -- see applyAccent(), which every Activity must call in
 * onCreate() before setContentView() so ?attr/colorAccent resolves correctly
 * on first inflation.
 */
object ThemeHelper {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_AUTO = "theme_auto_daynight"

    /** Auto day / night: light from [DAY_START_HOUR] until [NIGHT_START_HOUR], dark the rest of the day. */
    const val DAY_START_HOUR = 6
    const val NIGHT_START_HOUR = 18

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    enum class Accent(val tag: String, val overlayStyleRes: Int) {
        DEFAULT("default", R.style.ThemeOverlay_Accent_Default),
        RED("red", R.style.ThemeOverlay_Accent_Red),
        YELLOW("yellow", R.style.ThemeOverlay_Accent_Yellow),
        BLUE("blue", R.style.ThemeOverlay_Accent_Blue),
        ORANGE("orange", R.style.ThemeOverlay_Accent_Orange),
        GREEN("green", R.style.ThemeOverlay_Accent_Green),
        PURPLE("purple", R.style.ThemeOverlay_Accent_Purple),
        // Accents of the scene themes (shown as extra swatches; picking a scene selects its own one).
        RAIN("rain", R.style.ThemeOverlay_Accent_SceneRain),
        NIGHT_SKY("night_sky", R.style.ThemeOverlay_Accent_SceneNightsky),
        STARFIELD("starfield", R.style.ThemeOverlay_Accent_SceneStarfield),
        WATER("water", R.style.ThemeOverlay_Accent_SceneWater);

        companion object {
            fun fromTag(tag: String?): Accent = entries.firstOrNull { it.tag == tag } ?: DEFAULT
        }
    }

    /**
     * App themes matching the four animated Screensaver backgrounds (Rain / Night Sky / Starfield / Night Water).
     * Each one repaints the whole UI with a dark palette taken from its background and selects its own [accent].
     * They are always dark (the backgrounds are night scenes), whatever the Light / Dark / Auto setting says.
     */
    enum class Scene(val tag: String, val overlayStyleRes: Int, val accent: Accent, val bgRes: Int, val labelRes: Int) {
        RAIN("rain", R.style.ThemeOverlay_Scene_Rain, Accent.RAIN, R.color.scene_rain_bg, R.string.animated_rain),
        NIGHT_SKY("night_sky", R.style.ThemeOverlay_Scene_Nightsky, Accent.NIGHT_SKY, R.color.scene_nightsky_bg, R.string.animated_night_sky),
        STARFIELD("starfield", R.style.ThemeOverlay_Scene_Starfield, Accent.STARFIELD, R.color.scene_starfield_bg, R.string.animated_starfield),
        WATER("water", R.style.ThemeOverlay_Scene_Water, Accent.WATER, R.color.scene_water_bg, R.string.animated_water);

        companion object {
            fun fromTag(tag: String?): Scene? = entries.firstOrNull { it.tag == tag }
        }
    }

    private const val KEY_SCENE = "scene_theme"

    /** The active scene theme, or null for the normal Light / Dark palette. */
    fun currentScene(context: Context): Scene? = Scene.fromTag(prefs(context).getString(KEY_SCENE, null))

    /** Selects a scene theme (with its matching accent) or, with null, goes back to the normal palette. */
    fun setScene(context: Context, scene: Scene?) {
        val e = prefs(context).edit()
        if (scene == null) e.remove(KEY_SCENE) else { e.putString(KEY_SCENE, scene.tag).putString(KEY_ACCENT, scene.accent.tag) }
        e.apply()
        applyMode(context) // a scene forces dark; leaving it restores the saved mode
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun currentMode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_DARK) ?: MODE_DARK

    fun currentAccent(context: Context): Accent =
        Accent.fromTag(prefs(context).getString(KEY_ACCENT, Accent.DEFAULT.tag))

    /** Pushes the saved mode into AppCompatDelegate. Safe to call from every
     *  Activity's onCreate() (cheap no-op if unchanged) so mode is always
     *  correct even if the app process was restarted mid-session. */
    fun applyMode(context: Context, mode: String = currentMode(context)) {
        AppCompatDelegate.setDefaultNightMode(
            when {
                currentScene(context) != null -> AppCompatDelegate.MODE_NIGHT_YES
                isAuto(context) -> if (isNightNow()) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                else -> when (mode) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            }
        )
    }

    /** "Auto day / night" switch: when on, it overrides the System / Light / Dark choice by the clock. */
    fun isAuto(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
        applyMode(context)
    }

    fun isNightNow(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h < DAY_START_HOUR || h >= NIGHT_START_HOUR
    }

    private fun msUntilNextSwitch(): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val h = now.get(Calendar.HOUR_OF_DAY)
            when {
                h < DAY_START_HOUR -> set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
                h < NIGHT_START_HOUR -> set(Calendar.HOUR_OF_DAY, NIGHT_START_HOUR)
                else -> { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, DAY_START_HOUR) }
            }
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(1000L) + 500L
    }

    /** While [activity] is on screen with auto day / night on: re-checks the clock when it comes back to the
     *  front and again exactly at 06:00 / 18:00, so the theme flips even if the app stays open overnight. */
    fun watchAuto(activity: AppCompatActivity) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!isAuto(activity)) return
                applyMode(activity) // no-op unless the mode changed; then AppCompat recreates the screens
                handler.postDelayed(this, msUntilNextSwitch())
            }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                handler.removeCallbacks(tick)
                if (isAuto(activity)) { applyMode(activity); handler.postDelayed(tick, msUntilNextSwitch()) }
            }
            override fun onPause(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
        })
    }

    /** Saves the choice and applies it. AppCompatDelegate recreates any
     *  active AppCompatActivity automatically, same as LocaleHelper.apply(). */
    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, mode).apply()
        applyMode(context, mode)
    }

    fun setAccent(context: Context, accent: Accent) {
        prefs(context).edit().putString(KEY_ACCENT, accent.tag).apply()
    }

    /** Must be called in every Activity's onCreate(), before setContentView(). */
    fun applyAccent(activity: Activity) {
        currentScene(activity)?.let { activity.theme.applyStyle(it.overlayStyleRes, true) } // palette first...
        activity.theme.applyStyle(currentAccent(activity).overlayStyleRes, true) // ...then the accent on top (a scene stores its own)
    }
}
