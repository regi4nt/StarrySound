package com.fxsoundlite.app

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Download a song or a video from a pasted link (tile under "Detect song" on Home). "Check" resolves the link
 * ([LinkDownloader.resolve]); the result lists what can be saved (the song, and the video qualities), and tapping one
 * starts the download in the background, so the next link can be pasted right away.
 */
class LinkDownloadActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var dismissed = false
    private var token = 0

    private val input by lazy { findViewById<EditText>(R.id.linkInput) }
    private val progress by lazy { findViewById<View>(R.id.linkProgress) }
    private val message by lazy { findViewById<TextView>(R.id.linkMessage) }
    private val result by lazy { findViewById<View>(R.id.linkResult) }
    private val options by lazy { findViewById<LinearLayout>(R.id.linkOptions) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_link_download)
        AppNavBar.attach(this)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.linkPaste).setOnClickListener { paste(go = true) }
        findViewById<View>(R.id.linkGo).setOnClickListener { check() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { check(); true } else false
        }
        // A shared link ("Share" from another app) or a link on the clipboard is taken over right away.
        val shared = if (intent?.action == android.content.Intent.ACTION_SEND) intent.getStringExtra(android.content.Intent.EXTRA_TEXT) else null
        if (!shared.isNullOrBlank()) { input.setText(shared); check() }
    }

    override fun onDestroy() {
        dismissed = true
        token++
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun paste(go: Boolean) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = try { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (e: Exception) { null }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.link_download_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        input.setText(text.trim())
        input.setSelection(input.text.length)
        if (go) check()
    }

    private fun showMessage(text: String?) {
        message.text = text.orEmpty()
        message.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun check() {
        val link = LinkDownloader.linkIn(input.text.toString())
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        result.visibility = View.GONE
        if (link == null) {
            showMessage(getString(R.string.link_download_invalid))
            return
        }
        showMessage(null)
        progress.visibility = View.VISIBLE
        val my = ++token
        try {
            io.execute {
                val info = try { LinkDownloader.resolve(link) } catch (e: Throwable) { android.util.Log.d("LinkDownload", "resolve failed: $e"); null }
                handler.post {
                    if (dismissed || my != token) return@post
                    progress.visibility = View.INVISIBLE
                    if (info == null || info.options.isEmpty()) {
                        showMessage(getString(if (info == null) R.string.link_download_unreadable else R.string.link_download_nothing))
                    } else show(info)
                }
            }
        } catch (e: Exception) {
            progress.visibility = View.INVISIBLE
        }
    }

    private fun show(info: LinkDownloader.Info) {
        findViewById<TextView>(R.id.linkTitle).text = info.title
        findViewById<TextView>(R.id.linkUploader).text = info.uploader
        options.removeAllViews()
        val dp = resources.displayMetrics.density
        for (o in info.options) {
            val b = TextView(this).apply {
                text = (if (o.video) getString(R.string.link_download_video) else getString(R.string.link_download_song)) + "  " + o.label
                setBackgroundResource(if (o.video) R.drawable.chip_bg else R.drawable.eq_button_bg)
                setTextColor(themeColor(if (o.video) R.attr.colorTextPrimary else R.attr.colorOnAccent))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    LinkDownloader.start(this@LinkDownloadActivity, info, o)
                }
            }
            options.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (46 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        }
        result.visibility = View.VISIBLE
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
