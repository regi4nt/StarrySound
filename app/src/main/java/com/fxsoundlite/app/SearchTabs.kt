package com.fxsoundlite.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Which online tabs the Search screen shows (Settings > Online music > Search tabs).
 * The Device tab is always there; the Drive tab (right of it), YouTube, TV, Web, Online, Radio and Spotify can be switched off. YouTube, TV and Radio are on by default; Web, Online and Spotify stay hidden until switched on here.
 */
object SearchTabs {

    enum class Tab(val key: String, val labelRes: Int, val defaultOn: Boolean = true) {
        DRIVE("drive", R.string.source_drive), // Google Drive songs / videos, right of Device
        YOUTUBE("youtube", R.string.source_youtube),
        TV("tv", R.string.source_tv), // live TV, only in video mode, right of YouTube
        WEB("web", R.string.source_web, false), // browser for videos on websites, only in video mode, right of TV; hidden until switched on here
        RADIO("radio", R.string.source_radio),
        SPOTIFY("spotify", R.string.source_spotify, false), // opens the Spotify player screen, right of Radio; not shown in video mode
        ONLINE("online", R.string.source_online, false) // Internet Archive / Audius / Jamendo / ccMixter, right of Radio
    }

    private const val PREFS = "search_tabs"
    private const val PREFIX = "show_"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isShown(context: Context, tab: Tab): Boolean = prefs(context).getBoolean(PREFIX + tab.key, tab.defaultOn)

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
