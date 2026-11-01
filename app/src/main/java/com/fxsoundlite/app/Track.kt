package com.fxsoundlite.app

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/**
 * One playable item. Local files come from the device's MediaStore ([id] is the MediaStore id);
 * YouTube items come from the online search ([remoteId] is the video id and [id] is unused).
 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val source: Int = SOURCE_LOCAL,
    val remoteId: String = "",
    val artUrl: String = "",
    /** When it was put in a playlist (ms); lets a later Drive merge tell "removed" from "added again". 0 = unknown. */
    val addedAt: Long = 0L,
    /** Local song of a synced playlist that has no matching file on this device (not persisted). */
    val missing: Boolean = false
) {
    /**
     * Only meaningful for local tracks. A song found through a folder the user picked (OTG drive / SD card the system
     * did not index) is a local track whose [remoteId] holds its document uri; every other one is a MediaStore row.
     */
    val uri: Uri get() =
        if (source == SOURCE_LOCAL && remoteId.isNotEmpty()) Uri.parse(remoteId)
        else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    /** Stable identity across sources, used to highlight the playing row. */
    val key: String get() = if (source == SOURCE_LOCAL) "L$id" else "$source:$remoteId"

    /** Identity that is the same on every device: title + artist for local songs (MediaStore ids differ per phone). */
    val syncKey: String get() = if (source == SOURCE_LOCAL) LocalMatcher.localKey(title, artist) else key

    companion object {
        const val SOURCE_LOCAL = 0
        const val SOURCE_YOUTUBE = 1
        /** Internet radio station: [remoteId] is the stream URL, [durationMs] is 0 (live). */
        const val SOURCE_RADIO = 2
        /** Audio file in the user's Google Drive: [remoteId] is the Drive file id (see DriveMusic). */
        const val SOURCE_DRIVE = 3
    }
}

/** "3:07" below one hour, "8:00:00" from one hour up (sleep videos run for hours). */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

/** Row subtitle; songs and videos from Google Drive get a "Drive" label so they stand out among the device files. */
fun Track.labeledSubtitle(ctx: android.content.Context, base: String): String =
    if (source != Track.SOURCE_DRIVE && source != Track.SOURCE_DRIVE_VIDEO) base
    else "\u2601 " + ctx.getString(R.string.source_drive) + if (base.isBlank()) "" else " \u00B7 $base"
