package com.fxsoundlite.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    /**
     * True when there is anything to read: the media permission, or at least one folder the user picked for an
     * OTG / SD card (that one needs no permission, it uses the system file picker).
     */
    fun canRead(context: Context): Boolean = hasPermission(context) || FolderLibrary.hasTrees(context)

    /** A folder of the device / SD card / OTG drive that holds songs; shown as a read-only playlist on the Playlist page. */
    class DeviceFolder(val id: String, val name: String, val kind: Int, val path: String, val tracks: List<Track>)

    /** Folders of the last [load] (null until one finished) and the [version] that load started at. */
    @Volatile var folders: List<DeviceFolder>? = null
        private set
    @Volatile var foldersVersion = -1
        private set

    /** Video mode: the video permission, or at least one picked OTG / SD folder. */
    fun canReadVideo(context: Context): Boolean = hasVideoPermission(context) || FolderLibrary.hasTrees(context)

    /** Where each local song lives (device / SD card / OTG), by track id; rebuilt on every [load]. */
    @Volatile private var storageById: Map<Long, Int> = emptyMap()

    /** [StorageKind] of a local song. Songs that are not local files (YouTube, Drive, ...) count as the device. */
    fun storageKind(t: Track): Int =
        if (t.source != Track.SOURCE_LOCAL) StorageKind.DEVICE else storageById[t.id] ?: StorageKind.DEVICE

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

    /**
     * Every song on the phone, on an SD card and on an OTG drive. Blocking query: call from a background thread.
     *
     * MediaStore's "external" volume already spans all mounted volumes, so the phone's storage and any card /
     * drive the system indexed come from one query; each row is tagged with the volume it is on. Songs of a
     * picked folder (an OTG drive the system did not index) are added from [FolderLibrary].
     */
    fun load(context: Context): List<Track> {
        val result = ArrayList<Track>()
        val kinds = HashMap<Long, Int>()
        val seenPaths = HashSet<String>()
        val volumeKinds = HashMap<String, Int>() // volume key -> kind, looked up once per scan
        fun kindOf(key: String) = volumeKinds.getOrPut(key) { Storages.kindOf(context, key) }
        val startVersion = version
        // Folder of each song: "volume:relative/dir" (lower-case) -> songs; used for the folder playlists.
        val folderOf = HashMap<Long, String>()
        val folderPath = HashMap<String, String>()
        val folderVol = HashMap<String, String>()
        fun addToFolder(id: Long, volKey: String, dir: String) {
            val d = dir.trim('/')
            val k = (volKey + ":" + d).lowercase(java.util.Locale.ROOT)
            folderOf[id] = k
            folderPath.putIfAbsent(k, d)
            folderVol.putIfAbsent(k, volKey)
        }

        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            if (modern) {
                add(MediaStore.Audio.Media.VOLUME_NAME)
                add(MediaStore.Audio.Media.RELATIVE_PATH)
                add(MediaStore.Audio.Media.DISPLAY_NAME)
            } else {
                @Suppress("DEPRECATION") add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()
        // Everything the system marks as music, plus whatever the app downloaded into Music/StarrySound
        // (a fresh download may not have its duration / music flag filled in by the system scan yet).
        val base = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000)"
        @Suppress("DEPRECATION")
        val downloads = if (modern)
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
                val volCol = if (modern) c.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME) else -1
                val relCol = if (modern) c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH) else -1
                val nameCol = if (modern) c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME) else -1
                @Suppress("DEPRECATION")
                val dataCol = if (modern) -1 else c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val artist = c.getString(artistCol)
                    val id = c.getLong(idCol)
                    result.add(
                        Track(
                            id = id,
                            title = c.getString(titleCol) ?: "?",
                            artist = if (artist.isNullOrBlank() || artist == "<unknown>") "" else artist,
                            album = c.getString(albumCol) ?: "",
                            durationMs = c.getLong(durCol)
                        )
                    )
                    // Which storage is it on, and what is its path (to spot the same file again in a picked folder).
                    val volKey: String
                    if (modern) {
                        volKey = Storages.keyOfMediaStoreName(c.getString(volCol))
                        seenPaths.add(Storages.pathKey(volKey, c.getString(relCol), c.getString(nameCol)))
                        addToFolder(id, volKey, c.getString(relCol).orEmpty())
                    } else {
                        val path = c.getString(dataCol)
                        volKey = Storages.keyOfPath(path)
                        val rel = path
                            ?.replace(Regex("^/storage/emulated/\\d+/"), "")
                            ?.replace(Regex("^/(storage/[^/]+|sdcard)/"), "")
                        seenPaths.add((volKey + ":" + (rel ?: "")).lowercase(java.util.Locale.ROOT))
                        addToFolder(id, volKey, (rel ?: "").substringBeforeLast('/', ""))
                    }
                    val kind = kindOf(volKey)
                    if (kind != StorageKind.DEVICE) kinds[id] = kind
                }
            }
        } catch (e: SecurityException) {
            // Permission revoked mid-query: treat as an empty library.
        }

        // OTG / SD folders picked through the file picker (songs MediaStore does not know about).
        try {
            val trees = FolderLibrary.trees(context)
            for (t in FolderLibrary.load(context, seenPaths)) {
                result.add(t)
                try {
                    val docId = android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(t.remoteId))
                    addToFolder(
                        t.id, docId.substringBefore(':').lowercase(java.util.Locale.ROOT),
                        docId.substringAfter(':', "").substringBeforeLast('/', "")
                    )
                } catch (e: Exception) {
                    // No readable document id: the song just has no folder playlist.
                }
                val vol = trees.firstOrNull { t.remoteId.startsWith(it.toString()) }?.let { FolderLibrary.volumeKey(it) }
                val kind = if (vol == null) StorageKind.OTG else kindOf(vol)
                if (kind != StorageKind.DEVICE) kinds[t.id] = kind
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicLibrary", "folder songs skipped", e)
        }

        storageById = kinds
        result.sortBy { it.title.lowercase() }

        folders = groupFolders(context, result, folderOf, folderPath, folderVol) { kindOf(it) }
        foldersVersion = startVersion
        return result
    }

    // ------------------------------------------------------------------ plug in / pull out

    @Volatile private var watching = false

    /**
     * Refreshes the Music page when an SD card or OTG drive is inserted or removed. Call once from
     * [App.onCreate]; the receiver lives as long as the process (system broadcasts only, so it is cheap).
     */
    fun watchStorage(app: Context) {
        if (watching) return
        watching = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // The system needs a moment to index a freshly mounted volume: look again after 2 s and 10 s.
                handler.postDelayed({ notifyChanged() }, 2_000L)
                handler.postDelayed({ notifyChanged() }, 10_000L)
            }
        }
        try {
            val media = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            val usb = IntentFilter().apply {
                addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(app, receiver, media, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(app, receiver, usb, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            android.util.Log.w("MusicLibrary", "storage watcher not started", e)
        }
    }


    /** One folder playlist per directory that holds files (name = last path segment; twins get their parent added). */
    private fun groupFolders(
        context: Context, tracks: List<Track>,
        folderOf: Map<Long, String>, folderPath: Map<String, String>, folderVol: Map<String, String>,
        kindOf: (String) -> Int
    ): List<DeviceFolder> {
        val groups = LinkedHashMap<String, MutableList<Track>>()
        for (t in tracks) {
            val k = folderOf[t.id] ?: continue
            groups.getOrPut(k) { ArrayList() }.add(t)
        }
        val raw = groups.map { (k, ts) ->
            val kind = kindOf(folderVol[k].orEmpty())
            val path = folderPath[k].orEmpty()
            val storageName = context.getString(StorageKind.labelRes(kind))
            Triple(DeviceFolder(k, path.substringAfterLast('/').ifEmpty { storageName }, kind, path, ts), storageName, path)
        }
        val twins = raw.groupingBy { it.first.name.lowercase() }.eachCount()
        val collator = java.text.Collator.getInstance()
        return raw.map { (f, storageName, path) ->
            if ((twins[f.name.lowercase()] ?: 0) > 1)
                DeviceFolder(f.id, f.name + " (" + path.substringBeforeLast('/', storageName) + ")", f.kind, f.path, f.tracks)
            else f
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    /** Folders of the last [loadVideos] (video mode) and when it finished. */
    @Volatile var videoFolders: List<DeviceFolder>? = null
        private set
    @Volatile var videoFoldersAt = 0L
        private set

    /** The device's video files (video mode, Device tab). Blocking query: call from a background thread. */
    fun loadVideos(context: Context): List<Track> {
        val result = ArrayList<Track>()
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = ArrayList<String>().apply {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.TITLE)
            add(MediaStore.Video.Media.DURATION)
            if (modern) {
                add(MediaStore.Video.Media.VOLUME_NAME)
                add(MediaStore.Video.Media.RELATIVE_PATH)
                add(MediaStore.Video.Media.DISPLAY_NAME)
            } else {
                @Suppress("DEPRECATION") add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()
        val folderOf = HashMap<Long, String>()
        val folderPath = HashMap<String, String>()
        val folderVol = HashMap<String, String>()
        val volumeKinds = HashMap<String, Int>()
        val seenPaths = HashSet<String>() // files MediaStore lists: not read again from a picked folder
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val volCol = if (modern) c.getColumnIndexOrThrow(MediaStore.Video.Media.VOLUME_NAME) else -1
                val relCol = if (modern) c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH) else -1
                val nameCol = if (modern) c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME) else -1
                @Suppress("DEPRECATION")
                val dataCol = if (modern) -1 else c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val volKey: String
                    val dir: String
                    if (modern) {
                        volKey = Storages.keyOfMediaStoreName(c.getString(volCol))
                        dir = c.getString(relCol).orEmpty()
                        seenPaths.add(Storages.pathKey(volKey, dir, c.getString(nameCol)))
                    } else {
                        val path = c.getString(dataCol)
                        volKey = Storages.keyOfPath(path)
                        dir = (path ?: "")
                            .replace(Regex("^/storage/emulated/\\d+/"), "")
                            .replace(Regex("^/(storage/[^/]+|sdcard)/"), "")
                            .substringBeforeLast('/', "")
                        seenPaths.add((volKey + ":" + (path ?: "")
                            .replace(Regex("^/storage/emulated/\\d+/"), "")
                            .replace(Regex("^/(storage/[^/]+|sdcard)/"), "")).lowercase(java.util.Locale.ROOT))
                    }
                    val d = dir.trim('/')
                    val k = (volKey + ":" + d).lowercase(java.util.Locale.ROOT)
                    folderOf[id] = k
                    folderPath.putIfAbsent(k, d)
                    folderVol.putIfAbsent(k, volKey)
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
        // Video files of picked OTG / SD folders (the system did not index them).
        try {
            for (t in FolderLibrary.load(context, seenPaths, video = true)) {
                result.add(t)
                try {
                    val docId = android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(t.remoteId))
                    val vol = docId.substringBefore(':').lowercase(java.util.Locale.ROOT)
                    val d = docId.substringAfter(':', "").substringBeforeLast('/', "").trim('/')
                    val k = (vol + ":" + d).lowercase(java.util.Locale.ROOT)
                    folderOf[t.id] = k
                    folderPath.putIfAbsent(k, d)
                    folderVol.putIfAbsent(k, vol)
                } catch (e: Exception) {
                    // No readable document id: the video just has no folder playlist.
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicLibrary", "folder videos skipped", e)
        }
        result.sortBy { it.title.lowercase() }
        videoFolders = groupFolders(context, result, folderOf, folderPath, folderVol) { key ->
            volumeKinds.getOrPut(key) { Storages.kindOf(context, key) }
        }
        videoFoldersAt = System.currentTimeMillis()
        return result
    }
}
