package com.fxsoundlite.app

import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Storage" dialog of the Device tab: which storages are plugged in (phone, SD card, OTG drive), how many songs each
 * one gives, and the folders the user added by hand. Adding a folder is how an OTG drive that the system did not
 * index gets read; that part is the system file picker, launched by the caller through [show]'s `pickFolder`.
 */
object StorageUi {

    private fun themeColor(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    fun show(ctx: Context, tracks: List<Track>, pickFolder: () -> Unit, onChanged: () -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        }
        fun title(res: Int) = root.addView(TextView(ctx).apply {
            setText(res)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(ctx, R.attr.colorTextSecondary))
            setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
        })
        fun line(text: String, secondary: Boolean = false) = root.addView(TextView(ctx).apply {
            this.text = text
            textSize = if (secondary) 13f else 15f
            setTextColor(themeColor(ctx, if (secondary) R.attr.colorTextSecondary else R.attr.colorTextPrimary))
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        })

        val local = tracks.filter { it.source == Track.SOURCE_LOCAL }
        title(R.string.storage_detected)
        val vols = Storages.volumes(ctx)
        for (v in vols) {
            val n = local.count { MusicLibrary.storageKind(it) == v.kind }
            val name = ctx.getString(StorageKind.labelRes(v.kind)).let { base ->
                if (v.label.isBlank() || v.label.equals(base, ignoreCase = true) || v.kind == StorageKind.DEVICE) base else "$base (${v.label})"
            }
            val state = ctx.getString(if (v.mounted) R.string.storage_mounted else R.string.storage_not_mounted)
            line("$name \u00B7 $state \u00B7 " + ctx.resources.getQuantityString(R.plurals.music_song_count, n, n))
        }
        if (vols.none { it.kind != StorageKind.DEVICE }) line(ctx.getString(R.string.storage_none), secondary = true)

        val trees = FolderLibrary.trees(ctx)
        if (trees.isNotEmpty()) {
            title(R.string.storage_folders)
            for (t in trees) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ctx).apply {
                    text = FolderLibrary.label(t)
                    textSize = 15f
                    setTextColor(themeColor(ctx, R.attr.colorTextPrimary))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(ctx).apply {
                    text = "\u2715"
                    textSize = 18f
                    setTextColor(themeColor(ctx, R.attr.colorTextSecondary))
                    setPadding((14 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
                    contentDescription = ctx.getString(R.string.playlist_delete)
                    setOnClickListener {
                        AlertDialog.Builder(ctx)
                            .setMessage(ctx.getString(R.string.storage_remove_confirm, FolderLibrary.label(t)))
                            .setPositiveButton(R.string.playlist_delete) { _, _ ->
                                FolderLibrary.removeTree(ctx, t)
                                onChanged()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                })
                root.addView(row)
            }
        }
        line(ctx.getString(R.string.storage_hint), secondary = true)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.storage_title)
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton(R.string.storage_add_folder) { _, _ -> pickFolder() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
