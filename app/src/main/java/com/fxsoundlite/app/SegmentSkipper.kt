package com.fxsoundlite.app

import org.json.JSONArray
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Finds the parts of a YouTube video that are not the song itself (intro, outro, sponsor, self-promo,
 * "non-music" sections) from the community SponsorBlock database, so MusicService can jump over them.
 *
 * Privacy: only the first 4 hex characters of the SHA-256 of the video id are sent (SponsorBlock's
 * hash-prefix API); the server answers for every video sharing that prefix and we pick ours locally.
 */
object SegmentSkipper {

    class Segment(val startMs: Int, val endMs: Int, val category: String)

    private const val BASE = "https://sponsor.ajay.app/api/skipSegments"
    private val CATEGORIES = listOf("music_offtopic", "intro", "outro", "sponsor", "selfpromo", "interaction")

    /** Small in-memory cache so replaying / going back to a song does not hit the network again. */
    private val cache = object : LinkedHashMap<String, List<Segment>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Segment>>?) = size > 40
    }

    /** Blocking: call from a background thread. Returns an empty list when there is nothing to skip or on any error. */
    fun fetch(videoId: String): List<Segment> {
        if (videoId.isBlank()) return emptyList()
        synchronized(cache) { cache[videoId]?.let { return it } }
        return try {
            val hash = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val cats = URLEncoder.encode(JSONArray(CATEGORIES).toString(), "UTF-8")
            val actions = URLEncoder.encode("[\"skip\"]", "UTF-8")
            val r = Http.request(
                "GET", "$BASE/${hash.substring(0, 4)}?categories=$cats&actionTypes=$actions",
                mapOf("User-Agent" to "StarrySound"), readTimeoutMs = 8_000, connectTimeoutMs = 6_000
            )
            val result = when (r.code) {
                200 -> parse(r.body, videoId)
                404 -> emptyList() // nothing known about any video with this prefix
                else -> return emptyList() // server trouble: do not cache, try again next time
            }
            synchronized(cache) { cache[videoId] = result }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parse(body: String, videoId: String): List<Segment> {
        val out = ArrayList<Segment>()
        val videos = JSONArray(body)
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (v.optString("videoID") != videoId) continue
            val segs = v.optJSONArray("segments") ?: continue
            for (j in 0 until segs.length()) {
                val s = segs.optJSONObject(j) ?: continue
                if (s.optString("actionType", "skip") != "skip") continue
                val range = s.optJSONArray("segment") ?: continue
                val start = (range.optDouble(0, 0.0) * 1000).toInt()
                val end = (range.optDouble(1, 0.0) * 1000).toInt()
                if (end - start < 1_000) continue
                // A "segment" covering most of the video means the whole thing is off-topic: leave it alone.
                val videoMs = (s.optDouble("videoDuration", 0.0) * 1000).toInt()
                if (videoMs > 0 && (end - start) > videoMs * 0.6) continue
                out.add(Segment(start, end, s.optString("category")))
            }
        }
        out.sortBy { it.startMs }
        // Merge overlapping / touching segments so one jump clears all of them.
        val merged = ArrayList<Segment>()
        for (s in out) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs <= last.endMs + 500) {
                merged[merged.lastIndex] = Segment(last.startMs, maxOf(last.endMs, s.endMs), last.category)
            } else merged.add(s)
        }
        return merged
    }
}
