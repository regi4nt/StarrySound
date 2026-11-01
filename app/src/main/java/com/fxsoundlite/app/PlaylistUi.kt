package com.fxsoundlite.app

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Dialogs shared by the Music page and the full-screen player: create / add / like / manage. */
object PlaylistUi {

    private fun toast(c: Context, text: String) = Toast.makeText(c, text, Toast.LENGTH_SHORT).show()

    /** Name prompt used for both "new playlist" and "rename". */
    fun promptName(c: Context, titleRes: Int, initial: String, onName: (String) -> Unit) =
        promptNameText(c, c.getString(titleRes), initial) { onName(it) }

    /** Same prompt with a ready-made title (e.g. "Import 42 songs as..."). */
    fun promptNameText(c: Context, title: String, initial: String, hintRes: Int = R.string.playlist_name_hint, onName: (String) -> Unit) {
        val input = EditText(c).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = c.getString(hintRes)
            setText(initial)
            setSelection(text.length)
        }
        val pad = (20 * c.resources.displayMetrics.density).toInt()
        val box = FrameLayout(c).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(c)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun createPlaylist(c: Context, then: (Playlist) -> Unit) {
        promptName(c, R.string.playlist_create, "") { name ->
            val pl = PlaylistStore.create(c, name)
            toast(c, c.getString(R.string.playlist_created, pl.name))
            then(pl)
        }
    }

    /** Picker: the user's playlists, with a full-width "+ New playlist" button right above Cancel. */
    fun addToPlaylist(c: Context, t: Track, onDone: () -> Unit = {}) {
        // "Liked songs" always comes first, followed by the user's own playlists.
        val likedList = Playlist(PlaylistStore.LIKED_ID, c.getString(R.string.playlist_liked), emptyList())
        val lists = listOf(likedList) + PlaylistStore.playlists(c)
        val dp = c.resources.displayMetrics.density
        var dialog: AlertDialog? = null
        val listView = android.widget.ListView(c).apply {
            adapter = android.widget.ArrayAdapter(c, android.R.layout.simple_list_item_1, lists.map { it.name })
            setOnItemClickListener { _, _, pos, _ ->
                dialog?.dismiss()
                addAndReport(c, lists[pos], t, onDone)
            }
        }
        val create = android.widget.Button(c).apply {
            text = c.getString(R.string.playlist_new_item)
            isAllCaps = false
            setOnClickListener {
                dialog?.dismiss()
                createPlaylist(c) { pl -> addAndReport(c, pl, t, onDone) }
            }
        }
        val box = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            addView(listView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(create, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(c)
            .setTitle(R.string.np_add_playlist)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Picker for "move": "Liked songs" and the user's playlists except [excludeId], plus "+ New playlist". */
    fun pickPlaylist(c: Context, title: String, excludeId: String?, onPick: (Playlist) -> Unit) {
        val likedList = Playlist(PlaylistStore.LIKED_ID, c.getString(R.string.playlist_liked), emptyList())
        val lists = (listOf(likedList) + PlaylistStore.playlists(c)).filter { it.id != excludeId }
        val dp = c.resources.displayMetrics.density
        var dialog: AlertDialog? = null
        val listView = android.widget.ListView(c).apply {
            adapter = android.widget.ArrayAdapter(c, android.R.layout.simple_list_item_1, lists.map { it.name })
            setOnItemClickListener { _, _, pos, _ ->
                dialog?.dismiss()
                onPick(lists[pos])
            }
        }
        val create = android.widget.Button(c).apply {
            text = c.getString(R.string.playlist_new_item)
            isAllCaps = false
            setOnClickListener {
                dialog?.dismiss()
                createPlaylist(c) { pl -> onPick(pl) }
            }
        }
        val box = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            addView(listView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(create, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(c)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addAndReport(c: Context, pl: Playlist, t: Track, onDone: () -> Unit) {
        val added = PlaylistStore.addTrack(c, pl.id, t)
        toast(c, c.getString(if (added) R.string.playlist_added else R.string.playlist_already, pl.name))
        onDone()
    }

    fun toggleLike(c: Context, t: Track, onDone: () -> Unit = {}) {
        val nowLiked = PlaylistStore.toggleLike(c, t)
        toast(c, c.getString(if (nowLiked) R.string.liked_on_toast else R.string.liked_off_toast))
        onDone()
    }

    /** Long-press menu of a song row. [openListId] is the playlist currently open (null elsewhere).
     *  [fromHistory] = the row is in the YouTube "recently played" list, which adds delete options. */
    fun trackActions(
        c: Context, t: Track, openListId: String?, fromHistory: Boolean = false,
        onDelete: (() -> Unit)? = null, onSelect: (() -> Unit)? = null, onMove: (() -> Unit)? = null, onChanged: () -> Unit
    ) {
        val liked = PlaylistStore.isLiked(c, t)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels += c.getString(if (liked) R.string.np_unlike else R.string.np_like)
        actions += { toggleLike(c, t, onChanged) }
        labels += c.getString(R.string.np_add_playlist)
        actions += { addToPlaylist(c, t, onChanged) }
        labels += c.getString(R.string.np_add_queue)
        actions += {
            val svc = MusicService.instance
            val added = svc?.addToQueue(t) ?: false
            toast(c, c.getString(if (added) R.string.quick_queue_added else R.string.quick_queue_already))
        }
        if (SongDownloader.canDownload(t)) {
            labels += c.getString(R.string.np_download)
            actions += { SongDownloader.download(c, t) }
        }
        if (onSelect != null) { // inside an editable playlist: start picking several songs
            labels += c.getString(R.string.select_songs)
            actions += { onSelect() }
        }
        if (onMove != null) { // inside an editable playlist: move this song to another playlist
            labels += c.getString(R.string.track_move_playlist)
            actions += { onMove() }
        }
        if (openListId != null && openListId != PlaylistStore.LIKED_ID) {
            labels += c.getString(R.string.playlist_remove_song)
            actions += {
                PlaylistStore.removeTrack(c, openListId, t)
                toast(c, c.getString(R.string.playlist_removed))
                onChanged()
            }
        }
        if (onDelete != null) { // a song file of this device: delete it from the storage
            labels += c.getString(R.string.track_delete_device)
            actions += { onDelete() }
        }
        if (fromHistory) {
            labels += c.getString(R.string.history_remove_song)
            actions += {
                if (t.source == Track.SOURCE_ONLINE) MusicPrefs.removeOnlineHistory(c, t.remoteId)
                else MusicPrefs.removeYoutubeHistory(c, t.remoteId)
                toast(c, c.getString(R.string.history_removed))
                onChanged()
            }
            labels += c.getString(R.string.history_clear_all)
            actions += {
                AlertDialog.Builder(c)
                    .setMessage(R.string.history_clear_confirm)
                    .setPositiveButton(R.string.playlist_delete) { _, _ ->
                        if (t.source == Track.SOURCE_ONLINE) MusicPrefs.clearOnlineHistory(c)
                        else MusicPrefs.clearYoutubeHistory(c)
                        toast(c, c.getString(R.string.history_cleared))
                        onChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        AlertDialog.Builder(c)
            .setTitle(t.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Export / share choices that every playlist (and "Liked songs") offers. */
    fun exportActions(
        c: Context, title: String, onExport: () -> Unit, onShare: () -> Unit,
        pinLabel: String? = null, onPin: (() -> Unit)? = null
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (pinLabel != null && onPin != null) { labels += pinLabel; actions += onPin }
        labels += c.getString(R.string.playlist_menu_export_m3u); actions += onExport
        labels += c.getString(R.string.playlist_menu_share_link); actions += onShare
        AlertDialog.Builder(c)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Menu with just the pin / unpin choice (YouTube and Drive playlists, which are read-only here). */
    fun pinOnly(c: Context, title: String, pinLabel: String, onPin: () -> Unit) {
        AlertDialog.Builder(c)
            .setTitle(title)
            .setItems(arrayOf(pinLabel)) { _, _ -> onPin() }
            .show()
    }

    /** Long-press menu of a playlist row: pin / move to folder / edit / export / share / delete. */
    fun playlistActions(
        c: Context, pl: Playlist, onEdit: (Playlist) -> Unit, onExport: (Playlist) -> Unit, onShare: (Playlist) -> Unit,
        pinLabel: String? = null, onPin: (() -> Unit)? = null, onChanged: () -> Unit
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (pinLabel != null && onPin != null) { labels += pinLabel; actions += onPin }
        labels += c.getString(R.string.folder_move); actions += { moveToFolder(c, pl, onChanged) }
        labels += c.getString(R.string.playlist_menu_edit); actions += { onEdit(pl) }
        labels += c.getString(R.string.playlist_menu_export_m3u); actions += { onExport(pl) }
        labels += c.getString(R.string.playlist_menu_share_link); actions += { onShare(pl) }
        labels += c.getString(R.string.playlist_delete)
        actions += {
            AlertDialog.Builder(c)
                .setMessage(c.getString(R.string.playlist_delete_confirm, pl.name))
                .setPositiveButton(R.string.playlist_delete) { _, _ ->
                    PlaylistStore.delete(c, pl.id)
                    onChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        AlertDialog.Builder(c)
            .setTitle(pl.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Asks for a folder name and creates the folder. */
    fun createFolder(c: Context, then: (PlaylistFolder) -> Unit) {
        promptNameText(c, c.getString(R.string.folder_new), "", R.string.folder_name_hint) { name ->
            then(PlaylistFolders.create(c, name))
        }
    }

    /**
     * Picker for a destination folder: "Move to main list" (when [currentId] is not null), every other folder,
     * and "+ New folder". [onPick] gets null for the main list.
     */
    private fun pickFolder(c: Context, title: String, currentId: String?, onPick: (PlaylistFolder?) -> Unit) {
        val names = mutableListOf<String>()
        val targets = mutableListOf<PlaylistFolder?>()
        if (currentId != null) { names += c.getString(R.string.folder_none); targets += null }
        PlaylistFolders.folders(c).filter { it.id != currentId }
            .sortedBy { it.name.lowercase() }
            .forEach { names += "\uD83D\uDCC1  " + it.name; targets += it }
        val dp = c.resources.displayMetrics.density
        var dialog: AlertDialog? = null
        val listView = android.widget.ListView(c).apply {
            adapter = android.widget.ArrayAdapter(c, android.R.layout.simple_list_item_1, names)
            setOnItemClickListener { _, _, pos, _ -> dialog?.dismiss(); onPick(targets[pos]) }
        }
        val create = android.widget.Button(c).apply {
            text = c.getString(R.string.folder_new_item)
            isAllCaps = false
            setOnClickListener { dialog?.dismiss(); createFolder(c) { f -> onPick(f) } }
        }
        val box = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            addView(listView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(create, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(c)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun movedToast(c: Context, f: PlaylistFolder?) =
        toast(c, if (f == null) c.getString(R.string.folder_moved_out) else c.getString(R.string.folder_moved, f.name))

    /** "Move to folder" for a playlist. */
    fun moveToFolder(c: Context, pl: Playlist, onChanged: () -> Unit) {
        pickFolder(c, c.getString(R.string.folder_move_title, pl.name), PlaylistFolders.folderOf(c, pl.id)) { f ->
            PlaylistFolders.move(c, pl.id, f?.id)
            movedToast(c, f)
            onChanged()
        }
    }

    /** Long-press menu of a folder row play / shuffle / pin / rename / delete (its playlists are kept). */
    fun folderActions(
        c: Context, f: PlaylistFolder, onPlay: (Boolean) -> Unit,
        pinLabel: String?, onPin: (() -> Unit)?, onChanged: () -> Unit
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels += c.getString(R.string.folder_play); actions += { onPlay(false) }
        labels += c.getString(R.string.folder_shuffle); actions += { onPlay(true) }
        if (pinLabel != null && onPin != null) { labels += pinLabel; actions += onPin }
        labels += c.getString(R.string.folder_rename)
        actions += {
            promptNameText(c, c.getString(R.string.folder_rename), f.name, R.string.folder_name_hint) { name ->
                PlaylistFolders.rename(c, f.id, name)
                onChanged()
            }
        }
        labels += c.getString(R.string.folder_delete)
        actions += {
            AlertDialog.Builder(c)
                .setMessage(c.getString(R.string.folder_delete_confirm, f.name))
                .setPositiveButton(R.string.playlist_delete) { _, _ ->
                    PlaylistFolders.delete(c, f.id) // the playlists stay and go back to the main list
                    onChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        AlertDialog.Builder(c)
            .setTitle(f.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }
}
