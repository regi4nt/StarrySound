package com.fxsoundlite.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Which online tabs the Search screen shows (Settings > Online music > Search tabs).
 * The Device tab is always there (Drive songs are part of it); YouTube, Online and Radio can be switched off. All are on by default.
 */
object SearchTabs {

    enum class Tab(val key: String, val labelRes: Int) {
        YOUTUBE("youtube", R.string.source_youtube),
        ONLINE("online", R.string.source_online), // Internet Archive (ArchiveApi), right next to YouTube
        RADIO("radio", R.string.source_radio)
    }

    private const val PREFS = "search_tabs"
    private const val PREFIX = "show_"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isShown(context: Context, tab: Tab): Boolean = prefs(context).getBoolean(PREFIX + tab.key, true)

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
