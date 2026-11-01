package com.fxsoundlite.app

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Collections
import java.util.WeakHashMap

/**
 * "Automatic full screen": while the setting is on, every page of the app hides the status and navigation bars
 * (a swipe from the screen edge shows them for a moment). One lifecycle hook covers all Activities, so a new
 * page needs no code of its own.
 *
 * Pages that already manage their own bars (the video player, the charging screensaver, the alarm) are left alone.
 */
object AutoFullscreen {

    private val own = setOf<Class<*>>(
        VideoPlayerActivity::class.java,
        ChargingScreensaverActivity::class.java,
        WakeAlarmActivity::class.java
    )

    /** Pages whose bars this object hid: only those are given their bars back when the setting goes off. */
    private val hidden: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())
    private val watched: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = watch(activity)
            override fun onActivityResumed(activity: Activity) = apply(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** A dialog, the keyboard or the notification shade can bring the bars back: hide them again once the page has focus. */
    private fun watch(a: Activity) {
        if (a.javaClass in own || !watched.add(a)) return
        a.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) apply(a)
        }
    }

    /** Brings the page in line with the setting; also called right after the switch in Settings changes. */
    fun apply(a: Activity) {
        if (a.javaClass in own || a.isFinishing || a.isDestroyed) return
        val w = a.window
        val controller = WindowInsetsControllerCompat(w, w.decorView)
        if (MusicPrefs.autoFullscreen(a)) {
            setCutoutMode(a, true)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            hidden.add(a)
        } else if (hidden.remove(a)) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            setCutoutMode(a, false)
        }
    }

    /** With a camera cut-out the picture otherwise stops below it (a black strip) once the status bar is gone. */
    fun setCutoutMode(a: Activity, fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT < 28) return
        val lp = a.window.attributes
        lp.layoutInDisplayCutoutMode = if (fullscreen) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        a.window.attributes = lp
    }
}
