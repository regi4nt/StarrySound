package com.fxsoundlite.app

import android.os.SystemClock
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * HTTP backend for NewPipeExtractor (plain HttpURLConnection, no OkHttp, to keep the APK small).
 *
 * Tuned for weak networks: connections are kept alive (a finished request leaves its socket in the pool, so the next
 * one skips DNS + TCP + TLS); a request that fails before any answer arrives (dead connect, stale pooled socket) is
 * retried once on a fresh connection instead of waiting out a long timeout; a body that crawls in is cut off.
 */
object NewPipeDownloader : Downloader() {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

    private const val CONNECT_MS = 6_000
    private const val READ_MS = 12_000
    /** A body still not complete after this long counts as failed (the stream link lookup has other chances to retry). */
    private const val BODY_BUDGET_MS = 40_000L
    private const val ATTEMPTS = 2
    private const val RETRY_PAUSE_MS = 300L

    /** The request failed before the server answered: safe to try again on a new connection. */
    private class EarlyFailure(cause: IOException) : IOException(cause)

    override fun execute(request: Request): Response {
        var attempt = 0
        while (true) {
            try {
                return attemptOnce(request)
            } catch (e: EarlyFailure) {
                attempt++
                val cause = e.cause as? IOException ?: e
                if (attempt >= ATTEMPTS) { NetStatus.fail(cause); throw cause }
                try { Thread.sleep(RETRY_PAUSE_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw cause }
            } catch (e: ReCaptchaException) {
                throw e
            } catch (e: IOException) {
                NetStatus.fail(e)
                throw e
            }
        }
    }

    private fun attemptOnce(request: Request): Response {
        val started = SystemClock.elapsedRealtime()
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        var clean = false // true only when the body was read to its end: then the socket may go back to the pool
        try {
            val code: Int
            try {
                conn.requestMethod = request.httpMethod()
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                for ((name, values) in request.headers()) {
                    conn.setRequestProperty(name, null)
                    for (v in values) conn.addRequestProperty(name, v)
                }
                request.localization()?.let {
                    if (!request.headers().keys.any { k -> k.equals("Accept-Language", true) }) {
                        conn.setRequestProperty("Accept-Language", it.languageCode)
                    }
                }
                val data = request.dataToSend()
                if (data != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(data.size)
                    conn.outputStream.use { it.write(data) }
                }
                code = conn.responseCode
            } catch (e: IOException) {
                throw EarlyFailure(e)
            }
            NetStatus.ok() // a server answered
            NetStatus.recordLatency(SystemClock.elapsedRealtime() - started)
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = readBody(stream, started + BODY_BUDGET_MS)
            val headers = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) if (k != null) headers[k] = v
            clean = true
            return Response(code, conn.responseMessage, headers, body, conn.url.toString())
        } finally {
            if (!clean) conn.disconnect()
        }
    }

    private fun readBody(stream: InputStream?, deadline: Long): String {
        if (stream == null) return ""
        stream.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (SystemClock.elapsedRealtime() > deadline) throw SocketTimeoutException("response too slow")
            }
            return out.toString("UTF-8")
        }
    }
}
