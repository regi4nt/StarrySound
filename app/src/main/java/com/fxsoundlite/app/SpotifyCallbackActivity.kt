package com.fxsoundlite.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Receives starrysound://spotify-callback from the browser, finishes the sign-in and returns to Settings. */
class SpotifyCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val app = applicationContext
        if (data == null) { finish(); return }
        Thread {
            val msg = try {
                SpotifyAuth.finishLogin(app, data)
                app.getString(R.string.spotify_login_ok)
            } catch (e: Exception) {
                app.getString(R.string.spotify_login_failed, e.message ?: "")
            }
            runOnUiThread {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }.start()
    }
}
