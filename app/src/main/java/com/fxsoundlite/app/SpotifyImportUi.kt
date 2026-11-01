package com.fxsoundlite.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * "Import from Spotify" of the playlist page's menu. Logged in to Spotify: tick the playlists to copy. Not logged in
 * (a free account cannot get a Client ID): paste the link of a public playlist instead. Songs are matched to YouTube.
 */
object SpotifyImportUi {
    fun start(a: Activity, onDone: () -> Unit) {
        if (SpotifyImport.running) { toast(a, a.getString(R.string.spotify_import_busy)); return }
        if (SpotifyAuth.isConnected(a)) pickFromAccount(a, onDone) else askLink(a, onDone)
    }

    private fun toast(c: Context, m: String) = Toast.makeText(c.applicationContext, m, Toast.LENGTH_LONG).show()

    private fun pickFromAccount(a: Activity, onDone: () -> Unit) {
        val app = a.applicationContext
        Thread {
            val lists = try { SpotifyApi.playlists(app) } catch (e: Exception) {
                a.runOnUiThread { if (!a.isFinishing) askLink(a, onDone) } // the account route failed: fall back to a link
                return@Thread
            }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                // Row 0 is "Liked songs", then the playlists.
                val names = arrayOf(a.getString(R.string.spotify_liked_pick)) + lists.map { it.name }.toTypedArray()
                val checked = BooleanArray(names.size) { true }
                AlertDialog.Builder(a)
                    .setTitle(R.string.spotify_import_pick)
                    .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton(R.string.spotify_import_start) { _, _ ->
                        val picks = lists.filterIndexed { i, _ -> checked[i + 1] }
                        if (picks.isEmpty() && !checked[0]) return@setPositiveButton
                        val likedName = a.getString(R.string.spotify_liked)
                        runJob(a, onDone) { progress -> SpotifyImport.run(app, picks, checked[0], likedName, progress) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    private fun askLink(a: Activity, onDone: () -> Unit) {
        val input = EditText(a).apply {
            hint = a.getString(R.string.spotify_link_hint)
            setSingleLine()
            val clip = try {
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            } catch (e: Exception) { "" }
            if (SpotifyEmbed.idFrom(clip) != null) setText(clip) // a copied playlist link is filled in already
        }
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val box = FrameLayout(a).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(a)
            .setTitle(R.string.spotify_link_title)
            .setMessage(R.string.spotify_link_msg)
            .setView(box)
            .setPositiveButton(R.string.spotify_import_start) { _, _ ->
                val link = input.text.toString()
                val app = a.applicationContext
                runJob(a, onDone) { progress ->
                    val pl = SpotifyEmbed.fetch(link)
                    SpotifyImport.runSongs(app, pl.name, pl.songs, progress)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Runs [job] in the background behind a small progress dialog, then reports the result and calls [onDone]. */
    private fun runJob(a: Activity, onDone: () -> Unit, job: (progress: (Int, Int, String) -> Unit) -> SpotifyImport.Result) {
        val dialog = AlertDialog.Builder(a).setMessage(R.string.playlist_import_fetching).setCancelable(false).create()
        dialog.show()
        Thread {
            try {
                val r = job { done, total, name ->
                    a.runOnUiThread { if (!a.isFinishing) dialog.setMessage(a.getString(R.string.spotify_import_progress, done, total, name)) }
                }
                a.runOnUiThread {
                    try { dialog.dismiss() } catch (e: Exception) { android.util.Log.d("SpotifyImportUi", "ignored: $e") }
                    toast(a, a.getString(R.string.spotify_import_done, r.playlists, r.songs, r.missed))
                    onDone()
                }
            } catch (e: Exception) {
                a.runOnUiThread {
                    try { dialog.dismiss() } catch (x: Exception) { android.util.Log.d("SpotifyImportUi", "ignored: $x") }
                    toast(a, a.getString(R.string.spotify_link_error, e.message ?: ""))
                }
            }
        }.start()
    }
}
