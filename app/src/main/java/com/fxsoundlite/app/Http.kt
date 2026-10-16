package com.fxsoundlite.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/** Minimal blocking HTTP helper (no OkHttp, to keep the APK tiny). Call from a background thread. */
object Http {
    class Response(val code: Int, val body: String)

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        readTimeoutMs: Int = 15_000,
        connectTimeoutMs: Int = 10_000
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (method == "POST" || method == "PUT") {
                val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            NetStatus.ok() // a server answered
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } catch (e: java.io.IOException) {
            NetStatus.fail(e)
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private const val IMG_UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val IMG_MAX_SIDE = 1024

    /** Downloads and decodes a cover. Follows redirects (also http <-> https, which HttpURLConnection does not do
     *  by itself), sends a browser User-Agent, checks the status code, shrinks huge images, and tries twice. */
    fun bitmap(url: String): Bitmap? {
        val clean = url.trim().let { if (it.startsWith("//")) "https:$it" else it }
        if (clean.isEmpty()) return null
        repeat(2) {
            val bmp = try { fetchBitmap(clean) } catch (e: Exception) { null }
            if (bmp != null) return bmp
        }
        return null
    }

    private fun fetchBitmap(startUrl: String): Bitmap? {
        var target = startUrl
        for (hop in 0..5) {
            val conn = URL(target).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", IMG_UA)
                conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    target = URL(URL(target), loc).toString()
                    continue
                }
                if (code !in 200..299) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                return decodeScaled(bytes)
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= IMG_MAX_SIDE || bounds.outHeight / (sample * 2) >= IMG_MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Failure from an online API. [code] is one of the [RemoteEngine] ERR_* constants. */
class ApiException(val code: Int, message: String) : Exception(message)
