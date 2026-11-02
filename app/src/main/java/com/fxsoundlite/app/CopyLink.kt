package com.fxsoundlite.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** "Copy link" inside the share sheet: an extra target next to the apps that puts the link on the clipboard. */
object CopyLink {
    fun copy(c: Context, label: String, text: String) {
        if (text.isBlank()) return
        try {
            c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
            // Android 13+ shows its own "copied" confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(c, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.d("CopyLink", "ignored: $e")
        }
    }

    /** Adds the "Copy link" target (copying [link]) to a chooser made with [Intent.createChooser]; no-op without a link. */
    fun withCopyTarget(c: Context, chooser: Intent, link: String): Intent {
        if (link.isBlank()) return chooser
        val target = Intent(c, CopyLinkActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link)
        chooser.putExtra(
            Intent.EXTRA_INITIAL_INTENTS,
            arrayOf(LabeledIntent(target, c.packageName, c.getString(R.string.copy_link_target), R.drawable.ic_copy_link_target))
        )
        return chooser
    }
}

/** Invisible: copies the link the share sheet handed over and closes. */
class CopyLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        CopyLink.copy(this, "link", text)
        finish()
    }
}
