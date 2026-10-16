package com.fxsoundlite.app

import android.content.Context

/**
 * Persists the content:// Uri of the video the user picked in Settings to
 * use as the Screensaver page's looping background. Deliberately standalone
 * (not part of AudioEffectManager) -- it has nothing to do with the audio
 * engine and both SettingsActivity and ScreensaverFragment need to read/
 * write it without binding to EffectService first.
 *
 * Shares the same SharedPreferences file as AudioEffectManager purely so the
 * app doesn't scatter prefs files; the key namespace ("screensaver_") keeps
 * it from colliding with anything audio-related.
 */
object ScreensaverPrefs {
    private const val PREFS = "fxsoundlite_prefs"
    private const val KEY_VIDEO_URI = "screensaver_video_uri"
    private const val KEY_MEDIA_TYPE = "screensaver_media_type"
    private const val KEY_ANIMATED_STYLE = "screensaver_animated_style"
    private const val KEY_CLOCK_STYLE = "screensaver_clock_style"
    private const val KEY_GREETING = "screensaver_greeting"
    private const val KEY_AUTO_ON_CHARGE = "screensaver_auto_on_charge"
    private const val KEY_NOTIF_PERM_ASKED = "screensaver_notif_perm_asked"
    private const val KEY_PRESET_NOTIF = "preset_notification_enabled"

    /** Max characters accepted for the greeting (also enforced in the
     *  Settings EditText via maxLength). */
    const val GREETING_MAX_LENGTH = 80

    const val TYPE_VIDEO = "video"
    const val TYPE_IMAGE = "image"
    /** One of the built-in, code-drawn backgrounds (see [SleepBackgroundView])
     *  instead of a picked photo/video -- no Uri applies in this case. */
    const val TYPE_ANIMATED = "animated"

    const val CLOCK_DIGITAL = "digital"
    const val CLOCK_ANALOG = "analog"

    const val STYLE_RAIN = "rain"
    const val STYLE_NIGHT_SKY = "night_sky"
    const val STYLE_STARFIELD = "starfield"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun videoUri(context: Context): String? = prefs(context).getString(KEY_VIDEO_URI, null)

    /** "video", "image" or "animated" -- defaults to "video" for anything
     *  saved before this field existed, matching the app's original
     *  (video-only) behavior. */
    fun mediaType(context: Context): String =
        prefs(context).getString(KEY_MEDIA_TYPE, TYPE_VIDEO) ?: TYPE_VIDEO

    fun setMedia(context: Context, uriString: String?, type: String) {
        prefs(context).edit()
            .putString(KEY_VIDEO_URI, uriString)
            .putString(KEY_MEDIA_TYPE, type)
            .apply()
    }

    /** Switches the Screensaver page to one of the built-in, code-drawn
     *  animated backgrounds (Rain / Night Sky / Starfield) instead
     *  of a picked photo/video. Clears any previously picked media Uri
     *  since it no longer applies once this is active. */
    fun setAnimated(context: Context, style: String) {
        prefs(context).edit()
            .putString(KEY_VIDEO_URI, null)
            .putString(KEY_MEDIA_TYPE, TYPE_ANIMATED)
            .putString(KEY_ANIMATED_STYLE, style)
            .apply()
    }

    /** Which animated style is selected -- only meaningful when
     *  [mediaType] is [TYPE_ANIMATED]. Defaults to Night Sky. */
    fun animatedStyle(context: Context): String =
        prefs(context).getString(KEY_ANIMATED_STYLE, STYLE_NIGHT_SKY) ?: STYLE_NIGHT_SKY

    fun hasVideo(context: Context): Boolean = !videoUri(context).isNullOrBlank()

    /** True if the Screensaver page currently has *any* non-default
     *  background configured -- a picked photo/video, or an animated style --
     *  as opposed to falling back to the plain background color. */
    fun hasBackground(context: Context): Boolean =
        hasVideo(context) || mediaType(context) == TYPE_ANIMATED

    /** Which clock face the Screensaver shows: [CLOCK_DIGITAL] (default) or [CLOCK_ANALOG]. */
    fun clockStyle(context: Context): String =
        prefs(context).getString(KEY_CLOCK_STYLE, CLOCK_DIGITAL) ?: CLOCK_DIGITAL

    fun setClockStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_CLOCK_STYLE, style).apply()
    }

    /** Short greeting shown under the clock/date on the Screensaver page.
     *  Empty (the default) means "don't show anything". */
    fun greeting(context: Context): String =
        prefs(context).getString(KEY_GREETING, "")?.trim().orEmpty()

    fun setGreeting(context: Context, text: String) {
        prefs(context).edit()
            .putString(KEY_GREETING, text.trim().take(GREETING_MAX_LENGTH))
            .apply()
    }

    /** Auto-open the Screensaver over the lock screen while the phone is
     *  charging (see [ChargingScreensaver]). Off by default. */
    fun autoOnCharge(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ON_CHARGE, false)

    fun setAutoOnCharge(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ON_CHARGE, enabled).apply()
    }

    /** Whether the ongoing effects notification (preset name + prev/next/off buttons) is
     *  shown. On by default. When off, EffectService keeps only a minimal silent
     *  placeholder, since Android requires a foreground service to have a notification. */
    fun presetNotification(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRESET_NOTIF, true)

    fun setPresetNotification(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRESET_NOTIF, enabled).apply()
    }

    /** True once the notification-permission prompt has been shown, so a
     *  later tap goes to the system settings page instead (Android stops
     *  showing the prompt after it has been denied). */
    fun notifPermissionAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_PERM_ASKED, false)

    fun setNotifPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIF_PERM_ASKED, true).apply()
    }
}
