package com.fxsoundlite.app

import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/** Free API keys for Online-tab sources that need one (Settings > Online music). */
object OnlineKeys {
    private const val PREFS = "online_keys"
    private const val K_JAMENDO = "jamendo_client_id"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun jamendo(c: Context): String = prefs(c).getString(K_JAMENDO, "").orEmpty().trim()
    fun setJamendo(c: Context, v: String) { prefs(c).edit().putString(K_JAMENDO, v.trim()).apply() }

    /** Asks for the Jamendo client id (free at developer.jamendo.com). [onSaved] runs after OK. */
    fun showJamendoDialog(context: Context, onSaved: () -> Unit = {}) {
        val input = EditText(context).apply {
            setText(jamendo(context)); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            hint = context.getString(R.string.jamendo_key_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.jamendo_key_title)
            .setMessage(R.string.jamendo_key_msg)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> setJamendo(context, input.text.toString()); onSaved() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
