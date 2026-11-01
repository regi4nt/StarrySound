package com.fxsoundlite.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sites of the Web tab's start page: the ones the person saved with the star, and the last few pages
 * that were opened. Plain SharedPreferences, newest first.
 */
object WebSites {

    data class Site(val title: String, val url: String)

    private const val PREFS = "web_sites"
    private const val KEY_SAVED = "saved"
    private const val KEY_RECENT = "recent"
    private const val RECENT_MAX = 8
    private const val SAVED_MAX = 100

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(c: Context, key: String): List<Site> {
        val raw = prefs(c).getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull {
                val o = a.optJSONObject(it) ?: return@mapNotNull null
                val url = o.optString("u")
                if (url.isBlank()) null else Site(o.optString("t").ifBlank { url }, url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(c: Context, key: String, list: List<Site>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
        prefs(c).edit().putString(key, a.toString()).apply()
    }

    fun saved(c: Context): List<Site> = read(c, KEY_SAVED)
    fun recent(c: Context): List<Site> = read(c, KEY_RECENT)

    fun isSaved(c: Context, url: String): Boolean = saved(c).any { it.url == url }

    /** Saves the page, or removes it when it is saved already. Returns true when it is saved afterwards. */
    fun toggleSaved(c: Context, title: String, url: String): Boolean {
        val list = saved(c)
        return if (list.any { it.url == url }) {
            write(c, KEY_SAVED, list.filter { it.url != url })
            false
        } else {
            write(c, KEY_SAVED, (listOf(Site(title.ifBlank { url }, url)) + list).take(SAVED_MAX))
            true
        }
    }

    fun removeSaved(c: Context, url: String) = write(c, KEY_SAVED, saved(c).filter { it.url != url })

    fun addRecent(c: Context, title: String, url: String) {
        write(c, KEY_RECENT, (listOf(Site(title.ifBlank { url }, url)) + recent(c).filter { it.url != url }).take(RECENT_MAX))
    }

    fun removeRecent(c: Context, url: String) = write(c, KEY_RECENT, recent(c).filter { it.url != url })
}
