package com.fxsoundlite.app

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Hand-picked radio stations taken from the StarryNight MPlayer web app
 * (https://starrynight-mplayer.vercel.app, "Koleksi"). The data lives in assets/radio_collection.json
 * (country -> genre -> stations) and is flattened here into one list of radio [Track]s.
 */
object RadioCollection {

    private class Entry(val country: String, val countryCode: String, val track: Track)

    @Volatile
    private var cache: List<Entry>? = null

    private fun load(context: Context): List<Entry> {
        cache?.let { return it }
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        try {
            val text = context.applicationContext.assets.open("radio_collection.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val countries = JSONObject(text).getJSONArray("countries")
            for (i in 0 until countries.length()) {
                val c = countries.getJSONObject(i)
                val cName = c.optString("name")
                val cCode = c.optString("id").uppercase(Locale.US)
                val genres = c.getJSONArray("genres")
                for (j in 0 until genres.length()) {
                    val g = genres.getJSONObject(j)
                    val gName = g.optString("name")
                    val stations = g.getJSONArray("stations")
                    for (k in 0 until stations.length()) {
                        val s = stations.getJSONObject(k)
                        val url = s.optString("url").trim()
                        val name = s.optString("name").trim()
                        if (!url.startsWith("http") || name.isEmpty() || !seen.add(url)) continue
                        val info = listOf(s.optString("city").trim(), cName, gName)
                            .filter { it.isNotBlank() }.joinToString(" \u00B7 ")
                        out += Entry(
                            cName, cCode,
                            Track(
                                id = 0L, title = name, artist = info, album = "", durationMs = 0L,
                                source = Track.SOURCE_RADIO, remoteId = url
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("RadioCollection", "cannot read radio_collection.json: ${e.message}")
        }
        if (out.isNotEmpty()) cache = out
        return out
    }

    /**
     * The collection's stations, those of the device's country first. With a [query] only the
     * stations whose name, city, country or genre contain it.
     */
    fun stations(context: Context, query: String = ""): List<Track> {
        val q = query.trim()
        val home = Locale.getDefault().country.uppercase(Locale.US)
        return load(context)
            .filter { q.isEmpty() || it.track.title.contains(q, true) || it.track.artist.contains(q, true) }
            .sortedBy { if (it.countryCode == home) 0 else 1 } // stable: keeps the web's order otherwise
            .map { it.track }
    }
}
