package com.fxsoundlite.app

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP backend for NewPipeExtractor (plain HttpURLConnection, no OkHttp, to keep the APK small). */
object NewPipeDownloader : Downloader() {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.httpMethod()
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
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
            val code = conn.responseCode
            NetStatus.ok() // a server answered
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val headers = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) if (k != null) headers[k] = v
            return Response(code, conn.responseMessage, headers, body, conn.url.toString())
        } catch (e: java.io.IOException) {
            NetStatus.fail(e)
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
