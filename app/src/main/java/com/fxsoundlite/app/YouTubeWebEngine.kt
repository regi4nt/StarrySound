package com.fxsoundlite.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Last-resort backend: opens the real youtube.com watch page (like a browser would) in a hidden
 * WebView and drives its <video> element with JavaScript. Because it is YouTube's own page there
 * is no embed check (error 153). Ads are skipped when possible. Background playback depends on
 * the WebView staying alive, which Android does not always allow with the screen off.
 */
class YouTubeWebEngine(
    context: Context,
    private val handler: Handler,
    private val cb: RemoteEngine.Callback
) : TimedEngine() {

    companion object {
        private const val TAG = "YouTubeWeb"
        private const val START_TIMEOUT_MS = 25_000L
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        /** Reads the state of the page's video; also skips ads and keeps it playing when wanted. */
        private fun pollJs(wantPlay: Boolean) = """
            (function(){
              var v=document.querySelector('video'); if(!v) return '';
              var skip=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');
              if(skip) skip.click();
              var ad=!!document.querySelector('.ad-showing');
              if(ad){ v.muted=true; try{v.playbackRate=16;}catch(e){} } else { v.muted=false; try{v.playbackRate=1;}catch(e){} }
              if($wantPlay && v.paused && !v.ended) v.play();
              return JSON.stringify({p:v.paused,t:v.currentTime,d:v.duration||0,e:v.ended,a:ad,r:v.readyState});
            })()
        """.trimIndent()
    }

    override val needsAudioFocus = true

    private val appContext = context.applicationContext
    private var web: WebView? = null
    private var released = false
    private var started = false
    private var token = 0
    private var volume = 1f
    private var wantPlay = true

    private val startTimeout = Runnable {
        if (released || started) return@Runnable
        Log.w(TAG, "no playback after $START_TIMEOUT_MS ms")
        fail(RemoteEngine.ERR_YT_TIMEOUT)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            val w = web
            if (w != null) {
                val my = token
                try {
                    w.evaluateJavascript(pollJs(wantPlay)) { raw -> if (my == token) onPoll(raw) }
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed", e)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun onPoll(raw: String?) {
        if (released || raw.isNullOrBlank() || raw == "null") return
        // evaluateJavascript returns the JSON-encoded result: a quoted, escaped string.
        val json = try { JSONObject(org.json.JSONTokener(raw).nextValue() as? String ?: return) } catch (e: Exception) { return }
        if (json.optBoolean("a")) return // an ad is playing: ignore its clock
        val pos = (json.optDouble("t", 0.0) * 1000).toInt()
        val d = (json.optDouble("d", 0.0) * 1000).toInt()
        if (d > 0 && d != dur && d < 24 * 3600 * 1000) { dur = d; cb.onStateChanged() }
        if (json.optBoolean("e")) {
            setClock(0, false); started = false; handler.removeCallbacks(poll)
            cb.onEnded()
            return
        }
        val paused = json.optBoolean("p")
        if (!paused && json.optInt("r") >= 3) {
            val first = !started
            started = true
            handler.removeCallbacks(startTimeout)
            setClock(pos, true)
            if (first) cb.onStarted() else if (!playing) cb.onStateChanged()
        } else if (paused && started && !wantPlay) {
            setClock(pos, false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun load(track: Track) {
        token++
        started = false
        wantPlay = true
        dur = track.durationMs.toInt()
        setClock(0, true)
        handler.removeCallbacks(poll); handler.removeCallbacks(startTimeout)
        destroyWeb()
        try {
            val w = WebView(appContext)
            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = UA
                cacheMode = WebSettings.LOAD_NO_CACHE // streamed media must not pile up in the cache folder
            }
            CookieManager.getInstance().setAcceptCookie(true)
            // A cookie that skips the EU consent wall.
            CookieManager.getInstance().setCookie("https://m.youtube.com", "SOCS=CAI; Path=/; Secure")
            w.webChromeClient = WebChromeClient()
            w.webViewClient = WebViewClient()
            // Never attached to a window: give it a real size so the page lays out as a phone.
            w.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            w.layout(0, 0, 480, 800)
            w.onResume(); w.resumeTimers()
            web = w
            w.loadUrl("https://m.youtube.com/watch?v=${track.remoteId}")
            handler.postDelayed(startTimeout, START_TIMEOUT_MS)
            handler.postDelayed(poll, 1500)
        } catch (e: Throwable) {
            Log.w(TAG, "could not create WebView", e)
            handler.post { if (!released) fail(RemoteEngine.ERR_YT_CONFIG) }
        }
    }

    private fun fail(code: Int) {
        handler.removeCallbacks(poll); handler.removeCallbacks(startTimeout)
        destroyWeb()
        setClock(basePos, false)
        cb.onError(code)
    }

    private fun run(js: String) {
        try { web?.evaluateJavascript(js, null) } catch (e: Exception) { android.util.Log.d("YouTubeWebEngine", "ignored: " + e) }
    }

    override fun play() {
        wantPlay = true
        setClock(basePos, true)
        run("var v=document.querySelector('video'); if(v) v.play();")
    }

    override fun pause() {
        wantPlay = false
        setClock(position, false)
        run("var v=document.querySelector('video'); if(v) v.pause();")
    }

    override fun seekTo(ms: Int) {
        setClock(ms, playing)
        run("var v=document.querySelector('video'); if(v) v.currentTime=${ms / 1000.0};")
    }

    override fun setVolume(v: Float) {
        volume = v
        run("var v=document.querySelector('video'); if(v) v.volume=${v.coerceIn(0f, 1f)};")
    }

    override fun release() {
        released = true
        token++
        handler.removeCallbacks(poll); handler.removeCallbacks(startTimeout)
        destroyWeb()
    }

    private fun destroyWeb() {
        web?.let {
            try { it.stopLoading(); it.loadUrl("about:blank"); it.onPause(); it.destroy() } catch (e: Exception) { android.util.Log.d("YouTubeWebEngine", "ignored: " + e) }
        }
        web = null
    }
}
