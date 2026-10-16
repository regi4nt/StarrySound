package com.fxsoundlite.app

import android.content.Context
import java.util.Locale

/**
 * "Smart shuffle": finds songs that fit the theme of what is being shuffled, to slip in between the queue's own
 * songs (like Spotify's Smart Shuffle). With an AI key (Settings > AI) the AI picks songs of the same mood, genre
 * and language; without one (or when it fails) more songs by the same artists come from a YouTube search.
 * Blocking: call from a background thread. Returns an empty list when nothing could be found (offline...).
 */
object SmartShuffle {
    private const val MIN_MS = 60_000L
    private const val MAX_MS = 9 * 60_000L

    fun suggest(c: Context, seeds: List<Track>, exclude: Set<String>, want: Int): List<Track> {
        val app = c.applicationContext
        if (seeds.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Track>()

        if (AiRecommender.hasKey(app)) {
            try {
                AiRecommender.similarTo(app, seeds, exclude, want).forEach { out[it.key] = it }
            } catch (e: Exception) {
                android.util.Log.w("SmartShuffle", "AI suggestions failed", e)
            }
        }
        if (out.size >= want) return out.values.take(want)

        // No AI (or it failed): more songs by the artists in the queue.
        val seedTitles = seeds.map { norm(it.title) }.filter { it.length > 3 }
        val artists = seeds.map { clean(it.artist) }
            .filter { it.isNotBlank() && !it.startsWith("<") }
            .distinct().shuffled().take(3)
        for (a in artists) {
            if (out.size >= want) break
            val found = try {
                YouTubeApi.search(app, "$a official audio", maxResults = 10)
            } catch (e: Exception) {
                continue
            }
            val key = a.lowercase(Locale.ROOT)
            found.asSequence()
                .filter { it.durationMs in MIN_MS..MAX_MS && it.key !in exclude && it.key !in out }
                .filter { t -> val n = norm(t.title); seedTitles.none { n.contains(it) } }
                .filter { t -> val ch = clean(t.artist).lowercase(Locale.ROOT); ch.contains(key) || key.contains(ch) || norm(t.title).contains(norm(a)) }
                .shuffled().take(2).forEach { if (out.size < want) out[it.key] = it }
        }
        return out.values.take(want)
    }

    private fun clean(s: String): String =
        s.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun norm(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[\\(\\[].*?[\\)\\]]"), "").replace(Regex("[^a-z0-9 ]"), "").trim()
}
