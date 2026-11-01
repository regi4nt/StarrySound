package com.fxsoundlite.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fxsoundlite.app.databinding.ActivityWebVideoBinding

/**
 * The Web tab: a small browser for watching videos on websites. Type an address (or words, which search for
 * videos), play the video in the page, and use the page player's full-screen button for a big picture.
 * The start page lists the saved sites and the recently opened ones.
 */
class WebVideoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val SEARCH = "https://duckduckgo.com/?iax=videos&ia=videos&q="
        private val BLOCKED_SCHEMES = listOf("javascript:", "file:", "content:", "intent:", "data:", "about:", "blob:")

        fun intent(c: Context, url: String? = null): Intent =
            Intent(c, WebVideoActivity::class.java).apply { if (url != null) putExtra(EXTRA_URL, url) }

        /** An address as typed -> what to load: a full link as is, a bare host gets https, anything else is a video search. */
        fun normalize(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            val lower = t.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) return t
            if (BLOCKED_SCHEMES.none { lower.startsWith(it) } && !t.contains(' ') && (t.contains('.') || lower.startsWith("localhost"))) {
                return "https://$t"
            }
            return SEARCH + Uri.encode(t)
        }
    }

    private lateinit var binding: ActivityWebVideoBinding
    private var loading = false
    private var onHome = true
    private var pageTitle = ""
    private var currentUrl = ""
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWebVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The page has its own sound: never play it on top of the music.
        MusicService.instance?.let { if (it.isPlaying) it.pause() }

        setupWebView()
        binding.webClose.setOnClickListener { finish() }
        binding.webReload.setOnClickListener {
            if (loading) binding.webView.stopLoading() else if (!onHome) binding.webView.reload()
        }
        binding.webSave.setOnClickListener { toggleSaved() }
        binding.webAddress.setOnEditorActionListener { v, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go(v.text.toString())
                true
            } else false
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideCustomView()
                !onHome && binding.webView.canGoBack() -> binding.webView.goBack()
                !onHome -> showHome()
                else -> finish()
            }
        }

        showHome()
        intent.getStringExtra(EXTRA_URL)?.let { go(it) }
    }

    // ------------------------------------------------------------------ web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.webView
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        // Embedded video players (a player on one site, served by another) need third-party cookies.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return !(scheme == "http" || scheme == "https") // never hand the page over to other apps
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url == "about:blank") return
                loading = true
                currentUrl = url
                setAddress(url)
                binding.webReload.setImageResource(R.drawable.ic_close)
                updateSaveIcon()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == "about:blank") { view.clearHistory(); return }
                loading = false
                binding.webReload.setImageResource(R.drawable.ic_refresh)
                pageTitle = view.title.orEmpty()
                currentUrl = url
                WebSites.addRecent(this@WebVideoActivity, pageTitle, url)
                updateSaveIcon()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    loading = false
                    binding.webReload.setImageResource(R.drawable.ic_refresh)
                    Toast.makeText(this@WebVideoActivity, R.string.web_load_failed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel() // a page with a broken certificate is not opened
                Toast.makeText(this@WebVideoActivity, R.string.web_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.webProgress.progress = newProgress
                binding.webProgress.visibility = if (newProgress < 100 && !onHome) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                binding.webFullscreen.addView(
                    view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                binding.webFullscreen.visibility = View.VISIBLE
                binding.webMain.visibility = View.GONE
                enterFullscreen()
            }

            override fun onHideCustomView() = hideCustomView()

            // Some WebView versions crash when a page asks for the default poster and gets none.
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun enterFullscreen() {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        binding.webFullscreen.removeView(v)
        binding.webFullscreen.visibility = View.GONE
        binding.webMain.visibility = View.VISIBLE
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // With "Automatic full screen" on, the bars stay hidden; otherwise they come back.
        if (!MusicPrefs.autoFullscreen(this)) {
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ address + pages

    private fun go(input: String) {
        val url = normalize(input) ?: return
        hideKeyboard()
        onHome = false
        binding.webHome.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.loadUrl(url)
    }

    private fun showHome() {
        onHome = true
        loading = false
        currentUrl = ""
        pageTitle = ""
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank") // also stops any video still playing
        binding.webView.visibility = View.GONE
        binding.webProgress.visibility = View.GONE
        binding.webReload.setImageResource(R.drawable.ic_refresh)
        binding.webAddress.setText("")
        updateSaveIcon()
        binding.webHome.visibility = View.VISIBLE
        buildHome()
    }

    private fun setAddress(url: String) {
        if (!binding.webAddress.hasFocus()) binding.webAddress.setText(url)
    }

    private fun updateSaveIcon() {
        val saved = currentUrl.isNotEmpty() && WebSites.isSaved(this, currentUrl)
        binding.webSave.setImageResource(if (saved) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        binding.webSave.alpha = if (onHome || currentUrl.isEmpty()) 0.4f else 1f
        binding.webSave.isEnabled = !onHome && currentUrl.isNotEmpty()
    }

    private fun toggleSaved() {
        if (onHome || currentUrl.isEmpty()) return
        val title = binding.webView.title.orEmpty().ifBlank { pageTitle }
        val nowSaved = WebSites.toggleSaved(this, title, currentUrl)
        updateSaveIcon()
        Toast.makeText(this, if (nowSaved) R.string.web_site_saved else R.string.web_site_removed, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ start page

    private fun buildHome() {
        val list = binding.webHomeList
        list.removeAllViews()
        val saved = WebSites.saved(this)
        val recent = WebSites.recent(this).filter { r -> saved.none { it.url == r.url } }
        if (saved.isEmpty() && recent.isEmpty()) {
            list.addView(binding.webHomeHint)
            return
        }
        if (saved.isNotEmpty()) addSection(list, R.string.web_home_saved, saved, true)
        if (recent.isNotEmpty()) addSection(list, R.string.web_home_recent, recent, false)
    }

    private fun addSection(list: ViewGroup, titleRes: Int, sites: List<WebSites.Site>, isSaved: Boolean) {
        (layoutInflater.inflate(R.layout.item_web_section, list, false) as TextView).also {
            it.setText(titleRes)
            list.addView(it)
        }
        for (s in sites) {
            val row = layoutInflater.inflate(R.layout.item_web_site, list, false)
            row.findViewById<TextView>(R.id.webSiteTitle).text = s.title
            row.findViewById<TextView>(R.id.webSiteUrl).text = s.url
            row.setOnClickListener { go(s.url) }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.web_remove_title)
                    .setMessage(s.title)
                    .setPositiveButton(R.string.web_remove) { _, _ ->
                        if (isSaved) WebSites.removeSaved(this, s.url) else WebSites.removeRecent(this, s.url)
                        buildHome()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            list.addView(row)
        }
    }

    private fun hideKeyboard() {
        binding.webAddress.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.webAddress.windowToken, 0)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause() // a page must not keep playing behind another app
        super.onPause()
    }

    override fun onDestroy() {
        hideCustomView()
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }
}
