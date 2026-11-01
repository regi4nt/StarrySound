package com.fxsoundlite.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Which online tabs the Search screen shows (Settings > Online music > Search tabs).
 * The Device tab is always there; the Drive tab (right of it), YouTube, Radio, TV, Web and Spotify (in that order) can be switched off. YouTube, TV and Radio are on by default; Web and Spotify stay hidden until switched on here.
 * One Web switch drives both Web tabs: the video-mode website browser and the music-mode Internet Archive / Audius / Jamendo / ccMixter tab.
 */
object SearchTabs {

    enum class Tab(val key: String, val labelRes: Int, val defaultOn: Boolean = true) {
        DRIVE("drive", R.string.source_drive), // Google Drive songs / videos, right of Device
        YOUTUBE("youtube", R.string.source_youtube),
        RADIO("radio", R.string.source_radio), // music mode only, right of YouTube
        TV("tv", R.string.source_tv), // live TV, only in video mode, right of Radio
        WEB("web", R.string.source_web, false), // one switch for both Web tabs: video mode = browser for videos on websites; music mode = Internet Archive / Audius / Jamendo / ccMixter; hidden until switched on here
        SPOTIFY("spotify", R.string.source_spotify, false) // searches Spotify like YouTube; songs play in the StarrySound player; last tab, not shown in video mode
    }

    private const val PREFS = "search_tabs"
    private const val PREFIX = "show_"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isShown(context: Context, tab: Tab): Boolean {
        val p = prefs(context)
        // The old separate "Online" switch was merged into Web: if Web was never set but Online was on, keep it on.
        if (tab == Tab.WEB && !p.contains(PREFIX + tab.key) && p.contains(PREFIX + "online")) {
            return p.getBoolean(PREFIX + "online", false)
        }
        return p.getBoolean(PREFIX + tab.key, tab.defaultOn)
    }

    fun setShown(context: Context, tab: Tab, shown: Boolean) {
        prefs(context).edit().putBoolean(PREFIX + tab.key, shown).apply()
    }

    /** The online tabs to show, in display order. The Search screen should build its tab bar from this (after the Device tab). */
    fun visible(context: Context): List<Tab> = Tab.values().filter { isShown(context, it) }

    /** Short text for the settings row, e.g. "Drive, YouTube, Radio". */
    fun summary(context: Context): String {
        val v = visible(context)
        return if (v.isEmpty()) context.getString(R.string.search_tabs_none)
        else v.joinToString(", ") { context.getString(it.labelRes) }
    }

    /** Multi-choice chooser. Changes are saved as you tick; [onChanged] runs when the dialog closes. */
    fun showDialog(context: Context, onChanged: () -> Unit = {}) {
        val tabs = Tab.values()
        val labels = Array(tabs.size) { context.getString(tabs[it].labelRes) }
        val checked = BooleanArray(tabs.size) { isShown(context, tabs[it]) }
        AlertDialog.Builder(context)
            .setTitle(R.string.search_tabs_title)
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                checked[which] = on
                setShown(context, tabs[which], on)
            }
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { onChanged() }
            .show()
    }
}
