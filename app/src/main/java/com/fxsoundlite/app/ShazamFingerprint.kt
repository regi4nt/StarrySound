package com.fxsoundlite.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Builds the audio "signature" that Shazam's recogniser expects, from 16 kHz mono 16-bit PCM:
 * a spectrogram (2048-point FFT every 128 samples), local spectral peaks in four frequency
 * bands, and the binary container that carries them. Pure Kotlin (no Android classes), written
 * from the publicly documented format of the signature, so it can be unit-tested on a plain JVM.
 */
object ShazamFingerprint {
    const val SAMPLE_RATE = 16000

    private const val N = 2048
    private const val BINS = 1025
    private const val RING = 256

    // Hann window as used by the format (zero ends omitted: 1..N of N+1 points).
    private val window = FloatArray(N) { i -> (0.5 * (1.0 - cos(2.0 * PI * (i + 1) / (N + 1)))).toFloat() }

    // FFT tables.
    private val cosT = FloatArray(N / 2) { k -> cos(2.0 * PI * k / N).toFloat() }
    private val sinT = FloatArray(N / 2) { k -> (-sin(2.0 * PI * k / N)).toFloat() }
    private val rev = IntArray(N).also { r ->
        val bits = 11
        for (i in 0 until N) {
            var x = i; var y = 0
            for (b in 0 until bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }

    private val NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val OTHERS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    /** One spectral peak: FFT pass number, magnitude and (frequency bin * 64 + correction). */
    class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    class Signature(val numberSamples: Int, val bands: Array<List<Peak>>)

    /** Zero-pads to at least 12 s (like the reference client), then fingerprints it. */
    fun sign(pcm: ShortArray): Signature {
        val minLen = 12 * SAMPLE_RATE
        val data = if (pcm.size >= minLen) pcm else pcm.copyOf(minLen)
        return Generator(data).run()
    }

    private class Generator(private val pcm: ShortArray) {
        private val ring = ShortArray(N)
        private var ringIdx = 0
        private val re = FloatArray(N)
        private val im = FloatArray(N)
        private val fft = Array(RING) { FloatArray(BINS) }
        private var fftIdx = 0
        private val spread = Array(RING) { FloatArray(BINS) }
        private var spreadIdx = 0
        private var spreadDone = 0
        private val peaks = Array(4) { ArrayList<Peak>() }

        fun run(): Signature {
            var pos = 0
            while (pos + 128 <= pcm.size) {
                doFft(pos)
                doPeakSpreading()
                spreadDone++
                if (spreadDone >= 46) doPeakRecognition()
                pos += 128
            }
            return Signature(pcm.size, Array(4) { peaks[it] })
        }

        private fun doFft(pos: Int) {
            for (i in 0 until 128) ring[ringIdx + i] = pcm[pos + i]
            ringIdx = (ringIdx + 128) and (N - 1)
            for (i in 0 until N) {
                re[rev[i]] = ring[(i + ringIdx) and (N - 1)].toFloat() * window[i]
            }
            java.util.Arrays.fill(im, 0f)
            // Iterative radix-2 FFT (inputs were stored bit-reversed above).
            var size = 2
            while (size <= N) {
                val half = size / 2
                val step = N / size
                var start = 0
                while (start < N) {
                    var k = 0
                    for (j in start until start + half) {
                        val wr = cosT[k]; val wi = sinT[k]
                        val tr = re[j + half] * wr - im[j + half] * wi
                        val ti = re[j + half] * wi + im[j + half] * wr
                        re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                        re[j] += tr; im[j] += ti
                        k += step
                    }
                    start += size
                }
                size *= 2
            }
            val out = fft[fftIdx]
            for (i in 0 until BINS) {
                out[i] = max((re[i] * re[i] + im[i] * im[i]) / (1 shl 17).toFloat(), 1e-10f)
            }
            fftIdx = (fftIdx + 1) and (RING - 1)
        }

        private fun doPeakSpreading() {
            val real = fft[(fftIdx - 1) and (RING - 1)]
            val cur = spread[spreadIdx]
            System.arraycopy(real, 0, cur, 0, BINS)
            // Frequency-domain spreading.
            for (p in 0..1022) cur[p] = max(max(cur[p], cur[p + 1]), cur[p + 2])
            // Time-domain spreading into the previous 1, 3 and 6 passes.
            val copy = cur.copyOf()
            for (former in intArrayOf(1, 3, 6)) {
                val f = spread[(spreadIdx - former) and (RING - 1)]
                for (p in 0..1024) f[p] = max(f[p], copy[p])
            }
            spreadIdx = (spreadIdx + 1) and (RING - 1)
        }

        private fun doPeakRecognition() {
            val a = fft[(fftIdx - 46) and (RING - 1)]
            val b = spread[(spreadIdx - 49) and (RING - 1)]
            for (bin in 10..1014) {
                val v = a[bin]
                if (v < 1f / 64f || v < b[bin - 1]) continue
                var maxN = 0f
                for (o in NEIGHBORS) maxN = max(maxN, b[bin + o])
                if (v <= maxN) continue
                var maxO = maxN
                for (o in OTHERS) maxO = max(maxO, spread[(spreadIdx + o) and (RING - 1)][bin - 1])
                if (v <= maxO) continue

                val pass = spreadDone - 46
                val mag = max(ln(v), 1f / 64f) * 1477.3f + 6144f
                val before = max(ln(a[bin - 1]), 1f / 64f) * 1477.3f + 6144f
                val after = max(ln(a[bin + 1]), 1f / 64f) * 1477.3f + 6144f
                val var1 = mag * 2f - before - after
                val var2 = (after - before) * 32f / var1
                val corrected = (bin * 64 + var2.toInt()) and 0xFFFF
                val hz = corrected.toFloat() * (16000f / 2f / 1024f / 64f)
                val band = when (hz.toInt()) {
                    in 250..519 -> 0
                    in 520..1449 -> 1
                    in 1450..3499 -> 2
                    in 3500..5500 -> 3
                    else -> continue
                }
                peaks[band].add(Peak(pass, mag.toInt().coerceIn(0, 65535), corrected))
            }
        }
    }

    /** Binary signature container (little endian, CRC-32 protected). */
    fun encode(sig: Signature): ByteArray {
        val body = ByteArrayOutputStream()
        for ((band, list) in sig.bands.withIndex()) {
            if (list.isEmpty()) continue
            val pk = ByteBuffer.allocate(list.size * 5 + list.size * 5 + 8).order(ByteOrder.LITTLE_ENDIAN)
            var last = 0
            for (p in list) {
                if (p.pass - last >= 255) {
                    pk.put(0xFF.toByte()); pk.putInt(p.pass)
                    last = p.pass
                }
                pk.put((p.pass - last).toByte())
                pk.putShort(p.magnitude.toShort())
                pk.putShort(p.bin.toShort())
                last = p.pass
            }
            val len = pk.position()
            val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            hdr.putInt(0x60030040 + band); hdr.putInt(len)
            body.write(hdr.array())
            body.write(pk.array(), 0, len)
            repeat((4 - len % 4) % 4) { body.write(0) }
        }
        val payload = body.toByteArray()

        val out = ByteBuffer.allocate(48 + 8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0xCAFE2580.toInt())          // magic
        out.putInt(0)                           // crc32, filled in below
        out.putInt(payload.size + 8)            // size after the 48-byte header
        out.putInt(0x94119C00.toInt())          // magic 2
        out.putInt(0); out.putInt(0); out.putInt(0)
        out.putInt(3 shl 27)                    // 16000 Hz
        out.putInt(0); out.putInt(0)
        out.putInt(sig.numberSamples + (SAMPLE_RATE * 0.24f).toInt())
        out.putInt((15 shl 19) + 0x40000)
        out.putInt(0x40000000)
        out.putInt(payload.size + 8)
        out.put(payload)
        val bytes = out.array()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc.toInt())
        return bytes
    }
}
