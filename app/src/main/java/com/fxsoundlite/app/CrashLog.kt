package com.fxsoundlite.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Date

/**
 * Debug helper: remembers the stack trace of the last crash and shows it the next time the app opens,
 * so it can be copied and reported without adb. Remove the two calls (App.onCreate, MainActivity.onCreate)
 * once the crash is fixed.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE).writeText(
                    "${Date()}\nthread: ${thread.name}\n\n" + Log.getStackTraceString(error)
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Shows (once) the crash saved by the previous run, if any. */
    fun showIfAny(activity: Activity) {
        val f = File(activity.filesDir, FILE)
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        if (text.isBlank()) return
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(activity)
            .setTitle("StarrySound closed unexpectedly")
            .setView(ScrollView(activity).apply { addView(tv) })
            .setPositiveButton("Copy") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("crash", text))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
