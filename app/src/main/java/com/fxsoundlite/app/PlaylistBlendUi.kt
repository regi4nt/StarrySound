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

/** Dialogs for Blend (see [PlaylistBlend]): start one, join with a code, see the match and manage it. */
object PlaylistBlendUi {

    private fun toast(c: Context, res: Int, vararg args: Any) =
        Toast.makeText(c, c.getString(res, *args), Toast.LENGTH_LONG).show()

    private fun failText(f: CollabPlaylist.Fail) = when (f) {
        CollabPlaylist.Fail.NOT_CONFIGURED -> R.string.collab_not_configured
        CollabPlaylist.Fail.NETWORK -> R.string.collab_network
        CollabPlaylist.Fail.NOT_FOUND -> R.string.blend_not_found
        CollabPlaylist.Fail.ALREADY -> R.string.blend_already
        CollabPlaylist.Fail.DENIED -> R.string.collab_denied
    }

    /** Entry point from the "+" menu: start a new Blend or join a friend's. [onOpen] gets the blend playlist id. */
    fun start(c: Context, onOpen: (String) -> Unit) {
        AlertDialog.Builder(c)
            .setTitle(R.string.blend_menu)
            .setMessage(R.string.blend_intro)
            .setPositiveButton(R.string.blend_new_btn) { _, _ -> create(c, onOpen) }
            .setNeutralButton(R.string.blend_join_btn) { _, _ -> promptJoin(c, onOpen) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun create(c: Context, onOpen: (String) -> Unit) {
        Toast.makeText(c, R.string.blend_creating, Toast.LENGTH_SHORT).show()
        PlaylistBlend.create(c) { fail, code, id ->
            if (fail != null) { toast(c, failText(fail)); return@create }
            onOpen(id)
            showCode(c, code)
        }
    }

    /** The code of a fresh Blend, with a share button for the friend. */
    private fun showCode(c: Context, code: String) {
        AlertDialog.Builder(c)
            .setTitle(R.string.blend_menu)
            .setMessage(c.getString(R.string.blend_created_msg, code))
            .setPositiveButton(R.string.collab_share_btn) { _, _ -> shareCode(c, code) }
            .setNeutralButton(R.string.collab_copy_btn) { _, _ -> copy(c, code) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun copy(c: Context, code: String) {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("code", code))
        Toast.makeText(c, R.string.collab_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareCode(c: Context, code: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, c.getString(R.string.blend_share_text, code))
        }
        val chooser = Intent.createChooser(send, null)
        if (c !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(chooser)
    }

    private fun promptJoin(c: Context, onOpen: (String) -> Unit) {
        val input = EditText(c).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = c.getString(R.string.blend_code_hint)
        }
        val pad = (20 * c.resources.displayMetrics.density).toInt()
        val box = FrameLayout(c).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(c)
            .setTitle(R.string.blend_join_btn)
            .setView(box)
            .setPositiveButton(R.string.collab_join_btn) { _, _ ->
                Toast.makeText(c, R.string.blend_mixing, Toast.LENGTH_SHORT).show()
                PlaylistBlend.join(c, input.text.toString()) { fail, id ->
                    when (fail) {
                        null -> {
                            val pct = PlaylistBlend.percentOf(c, id)
                            toast(c, R.string.blend_joined, pct.coerceAtLeast(0))
                            onOpen(id)
                        }
                        CollabPlaylist.Fail.ALREADY -> { toast(c, R.string.blend_already); onOpen(id) }
                        else -> toast(c, failText(fail))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Long-press / "⋮" entry of a blend playlist: refresh it first, then show match, people and actions. */
    fun manage(c: Context, pl: Playlist, onChanged: () -> Unit) {
        Toast.makeText(c, R.string.blend_mixing, Toast.LENGTH_SHORT).show()
        PlaylistBlend.refresh(c, pl.id) { sum ->
            onChanged()
            if (sum == null && !PlaylistBlend.isBlend(c, pl.id)) { toast(c, R.string.blend_ended); return@refresh }
            if (sum == null) toast(c, R.string.collab_network)
            showInfo(c, pl.id, onChanged)
        }
    }

    private fun showInfo(c: Context, id: String, onChanged: () -> Unit) {
        val code = PlaylistBlend.codeOf(c, id)
        if (code.isEmpty()) return
        val sum = PlaylistBlend.summary(id)
        val pct = sum?.percent ?: PlaylistBlend.percentOf(c, id)
        val text = StringBuilder()
        if (sum != null && sum.names.size < 2 || pct <= 0 && sum == null) text.append(c.getString(R.string.blend_desc_waiting))
        else text.append(c.getString(R.string.blend_match, pct))
        sum?.let {
            text.append("\n\n").append(c.getString(R.string.blend_people, it.names.joinToString(", ")))
            if (it.sharedArtists.isNotEmpty()) text.append("\n").append(c.getString(R.string.blend_shared, it.sharedArtists.joinToString(", ")))
        }
        text.append("\n\n").append(c.getString(R.string.blend_code_line, code))
        AlertDialog.Builder(c)
            .setTitle(R.string.blend_menu)
            .setMessage(text.toString())
            .setPositiveButton(R.string.collab_share_btn) { _, _ -> shareCode(c, code) }
            .setNeutralButton(R.string.collab_copy_btn) { _, _ -> copy(c, code) }
            .setNegativeButton(R.string.collab_leave_btn) { _, _ ->
                AlertDialog.Builder(c)
                    .setMessage(R.string.blend_leave_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        PlaylistBlend.leave(c, id) {
                            Toast.makeText(c, R.string.blend_left, Toast.LENGTH_SHORT).show()
                            onChanged()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }
}
