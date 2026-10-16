package com.fxsoundlite.app

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Identifies a recording of a song that is playing nearby through Shazam's public recognition
 * endpoint: no account, API key or host to set up. It matches the recorded track itself, so it is
 * for music playing around you, not for humming. Unofficial: Shazam may change or block it.
 * Blocking: call from a background thread.
 */
object ShazamRecognizer {
    const val RATE_LIMITED = "rate_limited"
    const val RATE = ShazamFingerprint.SAMPLE_RATE
    const val MAX_SECONDS = 10
    const val MIN_SECONDS = 3

    private const val UA = "Dalvik/2.1.0 (Linux; U; Android 9; SM-G960F Build/PPR1.180610.011)"

    /** [pcm] = 16 kHz mono 16-bit little-endian. Empty list = not recognised. */
    fun identify(pcm: ByteArray): List<SongRecognizer.Match> {
        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(pcm.size / 2) { bb.getShort(it * 2) }
        val sig = ShazamFingerprint.sign(samples)
        val uri = "data:audio/vnd.shazam.sig;base64," +
            Base64.encodeToString(ShazamFingerprint.encode(sig), Base64.NO_WRAP)

        val nowMs = System.currentTimeMillis()
        val ts = nowMs and 0xFFFFFFFFL
        val body = JSONObject()
            .put("geolocation", JSONObject().put("altitude", 300).put("latitude", 45).put("longitude", 2))
            .put(
                "signature", JSONObject()
                    .put("samplems", (sig.numberSamples.toFloat() / RATE * 1000f).toInt())
                    .put("timestamp", ts)
                    .put("uri", uri)
            )
            .put("timestamp", ts)
            .put("timezone", "Europe/Paris")
            .toString().toByteArray(Charsets.UTF_8)

        val id1 = UUID.randomUUID().toString().uppercase(Locale.ROOT)
        val id2 = UUID.randomUUID().toString()
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$id1/$id2" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"

        val code: Int
        val text: String
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Content-Language", "en_US")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            throw ApiException(RemoteEngine.ERR_NETWORK, e.message ?: "network error")
        }
        if (code == 429) throw ApiException(RemoteEngine.ERR_GENERIC, RATE_LIMITED)
        if (code !in 200..299) throw ApiException(RemoteEngine.ERR_GENERIC, "HTTP $code")

        val json = try { JSONObject(text) } catch (e: Exception) {
            throw ApiException(RemoteEngine.ERR_GENERIC, "Bad response")
        }
        val track = json.optJSONObject("track") ?: return emptyList()
        val title = track.optString("title")
        if (title.isBlank()) return emptyList()
        var album = ""
        val sections = track.optJSONArray("sections")
        if (sections != null) {
            loop@ for (i in 0 until sections.length()) {
                val meta = sections.optJSONObject(i)?.optJSONArray("metadata") ?: continue
                for (j in 0 until meta.length()) {
                    val m = meta.optJSONObject(j) ?: continue
                    if (m.optString("title").equals("Album", true)) { album = m.optString("text"); break@loop }
                }
            }
        }
        val art = track.optJSONObject("images")?.optString("coverart").orEmpty()
        return listOf(SongRecognizer.Match(title, track.optString("subtitle"), album, 0, art))
    }
}
