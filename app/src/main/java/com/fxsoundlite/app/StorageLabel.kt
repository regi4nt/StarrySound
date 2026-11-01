package com.fxsoundlite.app

import android.content.Context

/** Small "where is this file" tag shown in a song row: nothing for the phone's own storage, "SD card" / "OTG" otherwise. */
object StorageLabel {
    fun withBadge(ctx: Context, t: Track, base: String): String {
        if (t.source != Track.SOURCE_LOCAL && t.source != Track.SOURCE_VIDEO) return base
        val kind = MusicLibrary.storageKind(t)
        if (kind == StorageKind.DEVICE) return base
        val badge = ctx.getString(StorageKind.labelRes(kind))
        return if (base.isBlank()) badge else "$badge \u00B7 $base"
    }
}
