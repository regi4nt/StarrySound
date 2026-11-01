package com.fxsoundlite.app

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Lock screen of the app lock (see [AppLock]). It asks for the fingerprint / face / screen lock by itself, and
 * the button asks again after a cancel. Back does not open the app: it sends the app to the background.
 */
class AppLockActivity : AppCompatActivity() {

    private var prompting = false
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        AppLock.lockPageShown(true)
        if (!AppLock.available(this)) { // the phone has no lock any more: do not shut the person out
            AppLock.markUnlocked()
            finish()
            return
        }
        setContentView(R.layout.activity_app_lock)
        findViewById<View>(R.id.appLockUnlock).setOnClickListener { prompt() }
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
    }

    override fun onStart() {
        super.onStart()
        cancelled = false // leaving and coming back asks again
    }

    override fun onResume() {
        super.onResume()
        if (!prompting && !cancelled && !isFinishing) prompt()
    }

    private fun prompt() {
        if (prompting) return
        prompting = true
        AppLock.authenticate(this, getString(R.string.app_lock_prompt_title), getString(R.string.app_lock_prompt_subtitle)) { ok ->
            prompting = false
            if (ok) {
                AppLock.markUnlocked()
                finish()
            } else {
                cancelled = true // no endless re-prompting after a cancel; the button asks again
            }
        }
    }

    override fun onDestroy() {
        AppLock.lockPageShown(false)
        super.onDestroy()
    }
}
