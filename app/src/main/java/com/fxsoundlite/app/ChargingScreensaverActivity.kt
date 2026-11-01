package com.fxsoundlite.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen host for the existing ScreensaverFragment, shown over the lock
 * screen while charging (launched by [ChargingScreensaver]).
 *
 * It never dismisses the keyguard: touching the screen (or unplugging the
 * charger) just closes this Activity and the normal lock screen is right
 * behind it, so the phone stays locked the whole time.
 */
class ChargingScreensaverActivity : AppCompatActivity() {

    companion object {
        /** Lets [ChargingScreensaver] avoid stacking a second copy. */
        @Volatile
        var isShowing = false
            private set
    }

    private val unplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)

        // Opened late (e.g. the charger was already pulled) -> nothing to show.
        if (!ChargingScreensaver.isCharging(this)) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_charging_screensaver)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        AutoFullscreen.setCutoutMode(this, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        isShowing = true
        ChargingScreensaver.cancelNotification(this)
        registerReceiver(unplugReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
    }

    /** Classic screensaver behavior: any touch closes it. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) finish()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        if (isShowing) {
            isShowing = false
            try {
                unregisterReceiver(unplugReceiver)
            } catch (e: IllegalArgumentException) {
                // Never registered (finished early in onCreate).
            }
        }
        super.onDestroy()
    }
}
