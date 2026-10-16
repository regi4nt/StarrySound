package com.fxsoundlite.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update from GitHub Releases. The CI workflow publishes every build as a release tagged
 * "build-<N>" with one .apk asset, where N is the versionCode. At most one check per 12 hours;
 * "Later" hides that build until a newer one appears. Does nothing when UPDATE_REPO is empty.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_build"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class Release(val build: Int, val apkUrl: String)

    fun checkIfDue(activity: Activity) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
        io.execute {
            val rel = try { latest(repo) } catch (e: Exception) { Log.w(TAG, "check failed", e); null }
            if (rel != null) prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (rel == null || rel.build <= BuildConfig.VERSION_CODE) return@execute
            if (rel.build == prefs.getInt(KEY_SKIPPED, 0)) return@execute
            main.post { if (!activity.isFinishing && !activity.isDestroyed) ask(activity, rel) }
        }
    }

    /** Manual check from Settings: ignores the 12 h throttle and the "Later" choice, and always reports back. */
    fun checkNow(activity: Activity) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) {
            Toast.makeText(activity, R.string.about_update_no_repo, Toast.LENGTH_LONG).show()
            return
        }
        io.execute {
            val rel = try { latest(repo) } catch (e: Exception) { Log.w(TAG, "manual check failed", e); null }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                when {
                    rel == null -> Toast.makeText(activity, R.string.about_update_check_failed, Toast.LENGTH_LONG).show()
                    rel.build > BuildConfig.VERSION_CODE -> ask(activity, rel)
                    else -> Toast.makeText(activity, R.string.about_up_to_date, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun latest(repo: String): Release? {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val build = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) return Release(build, a.getString("browser_download_url"))
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    private fun ask(activity: Activity, rel: Release) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(R.string.update_message, rel.build))
            .setPositiveButton(R.string.update_install) { _, _ -> download(activity, rel) }
            .setNegativeButton(R.string.update_later) { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SKIPPED, rel.build).apply()
            }
            .show()
    }

    private fun download(activity: Activity, rel: Release) {
        val app = activity.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            )
            return
        }
        Toast.makeText(app, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val f = File(dir, "StarrySound-${rel.build}.apk")
                val c = URL(rel.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                try {
                    if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                    c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                } finally { c.disconnect() }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", f)
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                main.post { app.startActivity(i) }
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                main.post { Toast.makeText(app, R.string.update_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }
}
