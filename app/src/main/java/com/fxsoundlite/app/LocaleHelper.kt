package com.fxsoundlite.app

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Thin wrapper around AndroidX's per-app language API so the rest of the app
 * doesn't need to know how locale switching is implemented.
 *
 * We rely on AppCompatDelegate.setApplicationLocales() (AppCompat 1.6+)
 * instead of hand-rolling attachBaseContext()/Configuration overrides:
 * - On Android 13+ it's backed by the platform's own per-app language
 *   storage (shows up in system Settings > Apps > FXSound Lite > Language).
 * - On Android 8-12 (our minSdk 26 floor) AppCompat stores the choice itself
 *   and restores it on the next launch -- see the
 *   AppLocalesMetadataHolderService + autoStoreLocales meta-data entry in
 *   AndroidManifest.xml, which is required for that persistence to work.
 * - Calling it also recreates any current AppCompatActivity automatically,
 *   so screens re-read strings immediately without a manual restart.
 */
object LocaleHelper {

    const val TAG_SYSTEM_DEFAULT = ""
    const val TAG_ENGLISH = "en"
    const val TAG_INDONESIAN = "id"

    /** Empty string means "follow system language" (the default before the
     *  user picks anything, since getApplicationLocales() starts out empty). */
    fun currentTag(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')

    fun apply(languageTag: String) {
        val locales = if (languageTag.isEmpty())
            LocaleListCompat.getEmptyLocaleList()
        else
            LocaleListCompat.forLanguageTags(languageTag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** setApplicationLocales() only wraps Activity contexts on API < 33 (it's
     *  applied via an AppCompat lifecycle callback tied to Activity, not a
     *  process-wide override) -- on those versions a Service reading its own
     *  getString() still gets the system language, not the user's in-app
     *  choice. That's why EffectService's notification text ignored the
     *  language picked in Settings. Build a Context wrapped with the current
     *  choice and read strings from that instead wherever there's no
     *  Activity in the picture. On API 33+ this is a harmless no-op since
     *  the platform already applies the per-app locale everywhere. */
    fun localizedContext(base: Context): Context {
        val tag = currentTag()
        val locale = if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
