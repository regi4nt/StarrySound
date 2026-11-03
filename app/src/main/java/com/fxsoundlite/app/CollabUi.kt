package com.fxsoundlite.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Dialogs for shared playlists (see [CollabPlaylist]): make one, manage / share it, join one with a code. */
object CollabUi {

    private fun toast(c: Context, res: Int, vararg args: Any) =
        Toast.makeText(c, c.getString(res, *args), Toast.LENGTH_LONG).show()

    private fun failText(f: CollabPlaylist.Fail) = when (f) {
        CollabPlaylist.Fail.NOT_CONFIGURED -> R.string.collab_not_configured
        CollabPlaylist.Fail.NETWORK -> R.string.collab_network
        CollabPlaylist.Fail.NOT_FOUND -> R.string.collab_not_found
        CollabPlaylist.Fail.ALREADY -> R.string.collab_already
    }

    /** Entry point from the long-press menu of a playlist row (Playlist tab). */
    fun manage(c: Context, pl: Playlist, onChanged: () -> Unit) {
        if (CollabPlaylist.isShared(c, pl.id)) {
            // Fresh numbers first (people, who added what), then show them.
            CollabPlaylist.refresh(c, pl.id) { showShared(c, pl, onChanged) }
        } else {
            AlertDialog.Builder(c)
                .setTitle(R.string.collab_menu)
                .setMessage(R.string.collab_make_msg)
                .setPositiveButton(R.string.collab_make_btn) { _, _ -> make(c, pl, onChanged) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun make(c: Context, pl: Playlist, onChanged: () -> Unit) {
        Toast.makeText(c, R.string.collab_creating, Toast.LENGTH_SHORT).show()
        CollabPlaylist.share(c, pl.id) { fail, code, skipped ->
            if (fail != null) { toast(c, failText(fail)); return@share }
            toast(c, R.string.collab_created, code)
            if (skipped > 0) toast(c, R.string.collab_skipped, skipped)
            onChanged()
            showShared(c, pl, onChanged)
        }
    }

    private fun showShared(c: Context, pl: Playlist, onChanged: () -> Unit) {
        val code = CollabPlaylist.codeOf(c, pl.id)
        if (code.isEmpty()) return // left / closed while the dialog was loading
        val owner = CollabPlaylist.isOwner(c, pl.id)
        val info = CollabPlaylist.info(pl.id)
        val text = StringBuilder(c.getString(R.string.collab_info, code, info?.members ?: 1))
        info?.contributors?.takeIf { it.isNotEmpty() }?.let { list ->
            text.append("\n\n").append(c.getString(R.string.collab_contributors))
            list.take(10).forEach { (name, n) ->
                text.append("\n\u2022 ").append(name.ifBlank { c.getString(R.string.collab_anon) }).append(": ").append(n)
            }
        }
        AlertDialog.Builder(c)
            .setTitle(pl.name)
            .setMessage(text.toString())
            .setPositiveButton(R.string.collab_share_btn) { _, _ -> shareCode(c, pl.name, code) }
            .setNeutralButton(R.string.collab_copy_btn) { _, _ ->
                (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("code", code))
                Toast.makeText(c, R.string.collab_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(if (owner) R.string.collab_stop_btn else R.string.collab_leave_btn) { _, _ ->
                AlertDialog.Builder(c)
                    .setMessage(if (owner) R.string.collab_stop_confirm else R.string.collab_leave_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        CollabPlaylist.leave(c, pl.id, stopForEveryone = owner) {
                            Toast.makeText(c, R.string.collab_left, Toast.LENGTH_SHORT).show()
                            onChanged()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun shareCode(c: Context, name: String, code: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, c.getString(R.string.collab_share_text, name, code))
        }
        val chooser = Intent.createChooser(send, null)
        if (c !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(chooser)
    }

    /** "Join a shared playlist": asks for the code, then adds the playlist to this phone. */
    fun promptJoin(c: Context, onJoined: (String) -> Unit) {
        val input = EditText(c).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = c.getString(R.string.collab_code_hint)
        }
        val pad = (20 * c.resources.displayMetrics.density).toInt()
        val box = FrameLayout(c).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(c)
            .setTitle(R.string.collab_join_menu)
            .setView(box)
            .setPositiveButton(R.string.collab_join_btn) { _, _ ->
                CollabPlaylist.join(c, input.text.toString()) { fail, id ->
                    when (fail) {
                        null -> {
                            val name = PlaylistStore.get(c, id)?.name.orEmpty()
                            toast(c, R.string.collab_joined, name)
                            onJoined(id)
                        }
                        CollabPlaylist.Fail.ALREADY -> { toast(c, R.string.collab_already); onJoined(id) }
                        else -> fail?.let { toast(c, failText(it)) }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
