package com.fxsoundlite.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Song detection from a hummed or sung clip, through ACRCloud's identify API (the user's own
 * project credentials from Settings; its "Humming" bucket matches melodies, the music bucket
 * matches the recorded song). All methods block: call from a background thread.
 */
object SongRecognizer {
    class Match(val title: String, val artist: String, val album: String, val score: Int, val art: String = "")

    /** ACRCloud clip: 8 kHz, up to [MAX_SECONDS]. */
    const val SAMPLE_RATE = 8000
    const val MAX_SECONDS = 12
    const val MIN_SECONDS = 3

    /** Records mono 16-bit little-endian PCM at [rate] Hz until [maxSeconds] pass or [shouldStop]
     *  returns true. [onTick] gets the seconds recorded so far. Null when shorter than [minSeconds]
     *  or when the microphone can't be opened. */
    @SuppressLint("MissingPermission")
    fun recordPcm(rate: Int, maxSeconds: Int, minSeconds: Int, shouldStop: () -> Boolean, onTick: (Int) -> Unit): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        val pcm = ByteArrayOutputStream()
        val buf = ByteArray(minBuf)
        val maxBytes = rate * 2 * maxSeconds
        var lastTick = -1
        try {
            rec.startRecording()
            while (pcm.size() < maxBytes && !shouldStop()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                pcm.write(buf, 0, n)
                val sec = pcm.size() / (rate * 2)
                if (sec != lastTick) { lastTick = sec; onTick(sec) }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        val data = pcm.toByteArray()
        return if (data.size < rate * 2 * minSeconds) null else data
    }

    /** ACRCloud recording: a WAV at 8 kHz. */
    fun record(shouldStop: () -> Boolean, onTick: (Int) -> Unit): ByteArray? =
        recordPcm(SAMPLE_RATE, MAX_SECONDS, MIN_SECONDS, shouldStop, onTick)?.let { wav(it) }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) { for (i in 0..3) out.write(v shr (8 * i) and 0xFF) }
        fun short(v: Int) { for (i in 0..1) out.write(v shr (8 * i) and 0xFF) }
        out.write("RIFF".toByteArray()); int(36 + pcm.size)
        out.write("WAVEfmt ".toByteArray()); int(16); short(1); short(1)
        int(SAMPLE_RATE); int(SAMPLE_RATE * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** Sends [wav] to ACRCloud. Empty list = no match; throws [ApiException] on failure. */
    fun identify(host: String, accessKey: String, secret: String, wav: ByteArray): List<Match> {
        val cleanHost = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val ts = (System.currentTimeMillis() / 1000).toString()
        val toSign = "POST\n/v1/identify\n$accessKey\naudio\n1\n$ts"
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
        val signature = Base64.encodeToString(mac.doFinal(toSign.toByteArray()), Base64.NO_WRAP)

        val boundary = "----StarrySound" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("access_key", accessKey)
        field("sample_bytes", wav.size.toString())
        field("timestamp", ts)
        field("signature", signature)
        field("data_type", "audio")
        field("signature_version", "1")
        body.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"sample\"; filename=\"sample.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray()
        )
        body.write(wav)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val bytes = body.toByteArray()

        val text = try {
            val conn = URL("https://$cleanHost/v1/identify").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 25_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            throw ApiException(RemoteEngine.ERR_NETWORK, e.message ?: "network error")
        }

        val json = try { JSONObject(text) } catch (e: Exception) {
            throw ApiException(RemoteEngine.ERR_GENERIC, "Bad response")
        }
        val status = json.optJSONObject("status")
        val code = status?.optInt("code", -1) ?: -1
        if (code == 1001) return emptyList() // nothing recognised
        if (code != 0) throw ApiException(RemoteEngine.ERR_GENERIC, status?.optString("msg").orEmpty().ifBlank { "Error $code" })

        val meta = json.optJSONObject("metadata") ?: return emptyList()
        val out = ArrayList<Match>()
        // Real recordings first, then humming matches (they carry a similarity score).
        for (key in listOf("music", "humming")) {
            val arr = meta.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                val artists = o.optJSONArray("artists")
                val artist = if (artists != null && artists.length() > 0)
                    (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }
                        .filter { it.isNotBlank() }.joinToString(", ")
                else ""
                val album = o.optJSONObject("album")?.optString("name").orEmpty()
                if (out.none { it.title.equals(title, true) && it.artist.equals(artist, true) })
                    out.add(Match(title, artist, album, o.optInt("score", 0)))
            }
        }
        return out
    }
}
