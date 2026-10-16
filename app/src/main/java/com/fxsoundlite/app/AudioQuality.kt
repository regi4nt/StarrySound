package com.fxsoundlite.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.util.Locale

/** What the file itself says about its audio: format, bitrate, sample rate, bit depth. */
data class AudioInfo(
    val codec: String,          // "FLAC", "MP3", "AAC", "ALAC", "WAV", "OPUS" ...
    val lossless: Boolean,
    val sampleRate: Int,        // Hz, 0 = unknown
    val bitDepth: Int,          // 0 = unknown (lossy codecs have none)
    val channels: Int,          // 0 = unknown
    val bitrateKbps: Int        // average, 0 = unknown
) {
    /** "FLAC · 24-bit / 96 kHz" or "MP3 · 320 kbps" — the short line shown under the title. */
    fun badge(): String {
        val parts = ArrayList<String>()
        parts += codec
        if (lossless) {
            val rate = khz(sampleRate)
            if (bitDepth > 0 && rate.isNotEmpty()) parts += "$bitDepth-bit / $rate"
            else if (rate.isNotEmpty()) parts += rate
            else if (bitDepth > 0) parts += "$bitDepth-bit"
            else if (bitrateKbps > 0) parts += "$bitrateKbps kbps"
        } else {
            if (bitrateKbps > 0) parts += "$bitrateKbps kbps"
            khz(sampleRate).takeIf { it.isNotEmpty() }?.let { parts += it }
        }
        return parts.joinToString(" · ")
    }

    /** True for lossless files above CD quality (more than 16-bit or above 44.1 kHz). */
    val hiRes: Boolean get() = lossless && (bitDepth > 16 || sampleRate > 48000)

    /** Multi-line text for the "Song info" dialog. */
    fun details(): String {
        val rows = ArrayList<String>()
        rows += "$codec (" + (if (lossless) "lossless" else "lossy") + ")"
        if (sampleRate > 0) rows += khz(sampleRate)
        if (bitDepth > 0) rows += "$bitDepth-bit"
        when (channels) { 1 -> rows += "Mono"; 2 -> rows += "Stereo"; in 3..16 -> rows += "$channels ch" }
        if (bitrateKbps > 0) rows += "$bitrateKbps kbps"
        return rows.joinToString(" · ")
    }

    private fun khz(hz: Int): String {
        if (hz <= 0) return ""
        val k = hz / 1000.0
        return (if (hz % 1000 == 0) String.format(Locale.US, "%.0f", k) else String.format(Locale.US, "%.1f", k)) + " kHz"
    }
}

/** Reads [AudioInfo] from a local audio file. Blocking: call from a background thread. */
object AudioQuality {

    private val cache = object : LinkedHashMap<String, AudioInfo?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, AudioInfo?>?) = size > 200
    }

    fun cached(track: Track): AudioInfo? = synchronized(cache) { cache[track.key] }
    fun isCached(track: Track): Boolean = synchronized(cache) { cache.containsKey(track.key) }

    private val RADIO_Q = Regex("(?i)\\b(mp3|aac\\+?|ogg|flac|opus|wma)\\b(?:\\s+(\\d+)k)?")

    /** A radio station's codec / bitrate from its subtitle ("Country · FLAC 1411k"); null when it names none. */
    fun radioInfo(track: Track): AudioInfo? {
        if (track.source != Track.SOURCE_RADIO) return null
        val m = RADIO_Q.find(track.artist) ?: return null
        val codec = m.groupValues[1].uppercase(Locale.US)
        return AudioInfo(
            codec = codec, lossless = codec == "FLAC", sampleRate = 0, bitDepth = 0, channels = 0,
            bitrateKbps = m.groupValues[2].toIntOrNull() ?: 0
        )
    }

    /** Remembers what a source already told us (server metadata, a downloaded Drive file, a radio station). */
    fun put(track: Track, info: AudioInfo?) { synchronized(cache) { cache[track.key] = info } }

    /** Same as [probe], for a song that is a plain file on disk (a cached Drive download). */
    fun probeFile(context: Context, track: Track, file: java.io.File): AudioInfo? {
        synchronized(cache) { cache[track.key]?.let { return it } }
        val info = try { read(context, Uri.fromFile(file), track.durationMs) } catch (e: Throwable) { null }
        if (info != null) put(track, info)
        return info
    }

    fun probe(context: Context, track: Track): AudioInfo? {
        if (track.source != Track.SOURCE_LOCAL) return null
        synchronized(cache) { if (cache.containsKey(track.key)) return cache[track.key] }
        val info = try { read(context, track.uri, track.durationMs) } catch (e: Throwable) { null }
        synchronized(cache) { cache[track.key] = info }
        return info
    }

    private fun read(context: Context, uri: Uri, durationMsHint: Long): AudioInfo? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { fmt = f; break }
            }
            fmt ?: return null
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty().lowercase(Locale.US)
            var rate = intOf(fmt, MediaFormat.KEY_SAMPLE_RATE)
            var channels = intOf(fmt, MediaFormat.KEY_CHANNEL_COUNT)
            var depth = 0
            if (Build.VERSION.SDK_INT >= 24 && fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                depth = when (fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> if (Build.VERSION.SDK_INT >= 31 && fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED) 24
                        else if (Build.VERSION.SDK_INT >= 31 && fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_32BIT) 32
                        else 0
                }
            }
            val codec: String
            var lossless = true
            when {
                mime.contains("flac") -> {
                    codec = "FLAC"
                    // The decoder reports 16-bit; the real depth/rate are in the FLAC STREAMINFO block.
                    flacStreamInfo(context, uri)?.let { (r, c, b) -> rate = r; channels = c; depth = b }
                }
                mime.contains("alac") -> codec = "ALAC"
                mime.contains("raw") || mime.contains("wav") || mime.contains("x-wav") -> codec = "WAV"
                mime.contains("mpeg") && !mime.contains("mp4a") -> { codec = "MP3"; lossless = false }
                mime.contains("mp4a") || mime.contains("aac") -> { codec = "AAC"; lossless = false }
                mime.contains("opus") -> { codec = "OPUS"; lossless = false }
                mime.contains("vorbis") -> { codec = "OGG"; lossless = false }
                mime.contains("amr") -> { codec = "AMR"; lossless = false }
                else -> { codec = mime.substringAfter('/').uppercase(Locale.US); lossless = false }
            }
            if (!lossless) depth = 0
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else durationMsHint * 1000
            var kbps = intOf(fmt, MediaFormat.KEY_BIT_RATE) / 1000
            if (kbps <= 0 && durUs > 0) {
                val size = fileSize(context, uri)
                if (size > 0) kbps = (size * 8.0 / (durUs / 1_000_000.0) / 1000.0).toInt()
            }
            return AudioInfo(codec, lossless, rate, depth, channels, kbps)
        } finally {
            try { ex.release() } catch (_: Exception) { }
        }
    }

    private fun intOf(f: MediaFormat, key: String) = if (f.containsKey(key)) f.getInteger(key) else 0

    private fun fileSize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
            }
        } catch (_: Exception) { }
        return try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L } catch (_: Exception) { 0L }
    }

    /** (sampleRate, channels, bitsPerSample) from the first FLAC metadata block, or null if it isn't plain FLAC. */
    private fun flacStreamInfo(context: Context, uri: Uri): Triple<Int, Int, Int>? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val h = ByteArray(8192)
                var n = 0
                while (n < 4096) { val r = s.read(h, n, h.size - n); if (r <= 0) break; n += r }
                var p = 0
                // Skip an ID3v2 tag if the file has one in front of "fLaC".
                if (n > 10 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
                    val size = ((h[6].toInt() and 0x7F) shl 21) or ((h[7].toInt() and 0x7F) shl 14) or
                        ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F)
                    p = 10 + size
                    if (p + 42 > n) {
                        // Tag larger than what we buffered: skip forward in the stream instead.
                        context.contentResolver.openInputStream(uri)?.use { s2 ->
                            var left = p.toLong()
                            while (left > 0) { val k = s2.skip(left); if (k <= 0) break; left -= k }
                            val b = ByteArray(42); var m = 0
                            while (m < 42) { val r = s2.read(b, m, 42 - m); if (r <= 0) break; m += r }
                            return parse(b, 0, m)
                        }
                        return null
                    }
                }
                parse(h, p, n)
            }
        } catch (_: Exception) { null }
    }

    private fun parse(b: ByteArray, o: Int, n: Int): Triple<Int, Int, Int>? {
        if (n - o < 42) return null
        if (b[o] != 'f'.code.toByte() || b[o + 1] != 'L'.code.toByte() || b[o + 2] != 'a'.code.toByte() || b[o + 3] != 'C'.code.toByte()) return null
        // Block header (4 bytes, type 0 = STREAMINFO), then 34 bytes; sample rate starts 10 bytes into STREAMINFO.
        if ((b[o + 4].toInt() and 0x7F) != 0) return null
        val i = o + 4 + 4 + 10
        val rate = ((b[i].toInt() and 0xFF) shl 12) or ((b[i + 1].toInt() and 0xFF) shl 4) or ((b[i + 2].toInt() and 0xFF) shr 4)
        val ch = ((b[i + 2].toInt() shr 1) and 0x07) + 1
        val bps = (((b[i + 2].toInt() and 0x01) shl 4) or ((b[i + 3].toInt() and 0xFF) shr 4)) + 1
        return Triple(rate, ch, bps)
    }
}
