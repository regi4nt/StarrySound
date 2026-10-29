package com.fxsoundlite.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** Reads the device's music files from MediaStore (no copying, no indexing of our own). */
object MusicLibrary {

    /** The runtime permission that grants read access to audio files on this Android version. */
    val requiredPermission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission) ==
            PackageManager.PERMISSION_GRANTED

    /** Same for video files (Android 13+ has a separate permission). */
    val requiredVideoPermission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasVideoPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredVideoPermission) == PackageManager.PERMISSION_GRANTED

    /** Bumped whenever the app adds a file to the library itself (a finished download). */
    @Volatile var version = 0
        private set

    /** Set by the Music page while it is on screen: called on the main thread after [notifyChanged]. */
    @Volatile var onChanged: (() -> Unit)? = null

    fun notifyChanged() {
        version++
        val cb = onChanged ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { cb() }
    }

    /** Blocking query: call from a background thread. */
    fun load(context: Context): List<Track> {
        val result = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        // Everything the system marks as music, plus whatever the app downloaded into Music/StarrySound
        // (a fresh download may not have its duration / music flag filled in by the system scan yet).
        val base = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000)"
        @Suppress("DEPRECATION")
        val downloads = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE 'Music/StarrySound/%'"
        else "${MediaStore.Audio.Media.DATA} LIKE '%/Music/StarrySound/%'"
        val selection = "$base OR $downloads"
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val artist = c.getString(artistCol)
                    result.add(
                        Track(
                            id = c.getLong(idCol),
                            title = c.getString(titleCol) ?: "?",
                            artist = if (artist.isNullOrBlank() || artist == "<unknown>") "" else artist,
                            album = c.getString(albumCol) ?: "",
                            durationMs = c.getLong(durCol)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Permission revoked mid-query: treat as an empty library.
        }
        result.sortBy { it.title.lowercase() }
        return result
    }

    /** The device's video files (video mode, Device tab). Blocking query: call from a background thread. */
    fun loadVideos(context: Context): List<Track> {
        val result = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION
        )
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result.add(
                        Track(
                            id = id,
                            title = c.getString(titleCol) ?: "?",
                            artist = "",
                            album = "",
                            durationMs = c.getLong(durCol),
                            source = Track.SOURCE_VIDEO,
                            remoteId = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Permission revoked mid-query: treat as an empty list.
        }
        result.sortBy { it.title.lowercase() }
        return result
    }
}
