package com.fxsoundlite.app

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout

/**
 * The bottom navigation bar on the screens opened on top of the main pager (Settings, Web, Equalizer, Song detect,
 * Download, Sleep / Wake-up, ...): the same five tabs as in [MainActivity]; a tap goes to that page there.
 *
 * Call [attach] right after setContentView(). The screen's own layout is wrapped (it keeps all the free space) and
 * the bar sits under it. Like on the main screen the bar steps aside while the keyboard is open, and [setShown]
 * hides it for a full-screen mode (e.g. a fullscreen video in the Web browser).
 */
object AppNavBar {
    private const val HOST_TAG = "app_nav_host"

    fun attach(a: Activity) {
        val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(HOST_TAG) != null) return // already there
        val screen = content.getChildAt(0) ?: return
        content.removeView(screen)

        val host = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; tag = HOST_TAG }
        host.addView(screen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LayoutInflater.from(a).inflate(R.layout.view_bottom_nav, host, false)
        host.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun tab(id: Int, page: Int) = bar.findViewById<View>(id).setOnClickListener { open(a, page) }
        tab(R.id.navHomeTab, MainActivity.PAGE_HOME)
        tab(R.id.navQuickTab, MainActivity.PAGE_QUICK)
        tab(R.id.navMusicTab, MainActivity.PAGE_MUSIC)
        tab(R.id.navPlaylistTab, MainActivity.PAGE_PLAYLIST)
        tab(R.id.navScreensaverTab, MainActivity.PAGE_SCREENSAVER)

        // Keyboard open: the window shrinks and the bar would ride up above it, so it steps aside (as on the main screen).
        var fullscreenHidden = false
        host.setTag(R.id.appNavBar, { hidden: Boolean -> fullscreenHidden = hidden; update(host, bar, fullscreenHidden) })
        host.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!host.isAttachedToWindow) return
                update(host, bar, fullscreenHidden)
            }
        })
    }

    /** Hides / shows the bar for a full-screen mode of the screen (the keyboard rule still applies on top). */
    @Suppress("UNCHECKED_CAST")
    fun setShown(a: Activity, shown: Boolean) {
        val host = a.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag<View>(HOST_TAG) ?: return
        (host.getTag(R.id.appNavBar) as? (Boolean) -> Unit)?.invoke(!shown)
    }

    private fun update(host: View, bar: View, hidden: Boolean) {
        val visible = Rect()
        host.getWindowVisibleDisplayFrame(visible)
        val screenH = host.rootView.height
        val keyboard = screenH > 0 && screenH - visible.bottom > screenH * 0.15f
        val v = if (hidden || keyboard) View.GONE else View.VISIBLE
        if (bar.visibility != v) bar.visibility = v
    }

    private fun open(a: Activity, page: Int) {
        a.startActivity(
            Intent(a, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        a.finish()
    }
}
