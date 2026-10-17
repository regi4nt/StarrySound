package com.fxsoundlite.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Google sign-in + backup/sync of the user's data to Google Drive.
 *
 * What is synced: playlists, liked songs, YouTube play history and listening statistics
 * (see [buildSnapshot]). API keys are deliberately NOT uploaded.
 *
 * Two things that make merging safe:
 *   - deletions are recorded (PlaylistStore "deleted"), so a playlist or song removed on one
 *     device is not brought back by the union with another device's older copy;
 *   - songs from the phone's storage are matched across devices by title + artist, not by
 *     MediaStore id (LocalMatcher); ones not present on a device stay in the list, greyed out.
 *
 * Where it is stored: the hidden, app-private "appDataFolder" of the user's Drive (scope
 * drive.appdata). The user's own files are never read, and the backup does not show up in the
 * Drive file list; it only counts toward their quota (a few KB).
 *
 * How sync decides (see [syncBlocking]): the snapshot carries a random revision id. We remember
 * the revision we last uploaded/applied, and whether anything changed locally since then.
 *   - only local changed  -> upload
 *   - only Drive changed  -> replace local data with Drive's
 *   - both changed / first sync on this device -> merge (union) both, then upload
 */
object DriveSync {
    const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

    private const val FILE_NAME = "starrysound_backup.json"
    private const val SNAPSHOT_VERSION = 1
    private const val UPLOAD_DELAY_MS = 20_000L
    private const val STARTUP_MIN_INTERVAL_MS = 5 * 60_000L

    private const val PREFS = "drive_sync_prefs"
    private const val K_SIGNED_IN = "signed_in"
    private const val K_AUTO = "auto_sync"
    private const val K_LOCAL = "include_local"      // back up songs that live on this device (off by default)
    private const val K_EMAIL = "email"
    private const val K_NAME = "name"
    private const val K_CHANGE = "last_change"      // local time of the last data change
    private const val K_SYNCED_AT = "synced_at"     // local time (start) of the last good sync
    private const val K_REV = "last_rev"            // snapshot revision last uploaded/applied
    private const val K_LAST_OK = "last_ok_time"    // for the "last synced" text

    /** Result of one sync run; [resId] is a string resource describing it. */
    class Outcome(
        val ok: Boolean, @StringRes val resId: Int, val detail: String = "",
        /** Set when Google still needs the user to approve the Drive permission: launch it, then sync again. */
        val consentIntent: Intent? = null
    )

    private class DriveException(@StringRes val resId: Int, val detail: String = "") : Exception(detail)

    private class RemoteFile(val id: String)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingUpload: Runnable? = null
    @Volatile private var cachedToken: String? = null
    @Volatile private var lastStartupSync = 0L

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ account

    fun signInClient(c: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_DRIVE_APPDATA))
            .build()
        return GoogleSignIn.getClient(c, gso)
    }

    fun isSignedIn(c: Context): Boolean = p(c).getBoolean(K_SIGNED_IN, false)
    fun email(c: Context): String = p(c).getString(K_EMAIL, "").orEmpty()
    fun displayName(c: Context): String = p(c).getString(K_NAME, "").orEmpty()
    // Off by default: nothing is uploaded or pulled until the user taps "Sync now" / "Import from Drive".
    fun autoSync(c: Context): Boolean = p(c).getBoolean(K_AUTO, false)
    fun includeLocal(c: Context): Boolean = p(c).getBoolean(K_LOCAL, false)
    fun setIncludeLocal(c: Context, v: Boolean) = p(c).edit().putBoolean(K_LOCAL, v).apply()
    fun setAutoSync(c: Context, v: Boolean) = p(c).edit().putBoolean(K_AUTO, v).apply()
    fun lastSyncTime(c: Context): Long = p(c).getLong(K_LAST_OK, 0L)

    /** True when [account] really holds the Drive app-data permission (the user may untick it). */
    fun hasDrivePermission(account: GoogleSignInAccount): Boolean =
        GoogleSignIn.hasPermissions(account, Scope(SCOPE_DRIVE_APPDATA))

    fun onSignedIn(c: Context, account: GoogleSignInAccount) {
        // A new account must not inherit the previous account's sync state.
        val prevEmail = email(c)
        val edit = p(c).edit()
        if (prevEmail != account.email.orEmpty()) edit.remove(K_REV).remove(K_SYNCED_AT).remove(K_LAST_OK)
        edit.putBoolean(K_SIGNED_IN, true)
            .putString(K_EMAIL, account.email.orEmpty())
            .putString(K_NAME, account.displayName.orEmpty())
            .apply()
    }

    /** Signs out locally. Data already on Drive is kept; local data is kept too. */
    fun signOut(c: Context, done: () -> Unit) {
        val app = c.applicationContext
        pendingUpload?.let { main.removeCallbacks(it) }
        pendingUpload = null
        cachedToken = null
        DriveMusic.clear(app)
        p(app).edit().clear().apply()
        try {
            signInClient(app).signOut().addOnCompleteListener { done() }
        } catch (e: Throwable) {
            done()
        }
    }

    // ------------------------------------------------------------------ triggers

    /** Called by the stores whenever user data changes. Cheap; uploads after a short quiet period. */
    fun markDirty(c: Context) {
        val app = c.applicationContext
        val prefs = p(app)
        if (!prefs.getBoolean(K_SIGNED_IN, false)) return
        prefs.edit().putLong(K_CHANGE, System.currentTimeMillis()).apply()
        if (!prefs.getBoolean(K_AUTO, false)) return
        main.post {
            pendingUpload?.let { main.removeCallbacks(it) }
            val r = Runnable { syncAsync(app, null) }
            pendingUpload = r
            main.postDelayed(r, UPLOAD_DELAY_MS)
        }
    }

    /** Called when the app comes to the foreground: pulls changes made on another device. */
    fun syncOnStartup(c: Context) {
        val app = c.applicationContext
        if (!isSignedIn(app) || !autoSync(app)) return
        val now = System.currentTimeMillis()
        if (now - lastStartupSync < STARTUP_MIN_INTERVAL_MS) return
        lastStartupSync = now
        syncAsync(app, null)
    }

    /** Runs a sync on a background thread; [onDone] (optional) is called on the main thread. */
    fun syncAsync(c: Context, onDone: ((Outcome) -> Unit)?) {
        val app = c.applicationContext
        io.execute {
            val outcome = try {
                syncBlocking(app)
            } catch (e: DriveException) {
                Outcome(false, e.resId, e.detail)
            } catch (e: UserRecoverableAuthException) {
                // Not a failure: Google just wants the user to tap "Allow". Hand the screen to the UI.
                cachedToken = null
                Outcome(false, R.string.drive_err_reauth, consentIntent = e.intent)
            } catch (e: java.io.IOException) {
                Outcome(false, R.string.drive_err_network)
            } catch (e: Exception) {
                Outcome(false, R.string.drive_err_generic, e.message.orEmpty())
            }
            if (onDone != null) main.post { onDone(outcome) }
        }
    }

    /**
     * One-way, user-triggered import: merges what is on Drive into this phone and uploads nothing.
     * Nothing is overwritten (union); device songs are only taken if "include device songs" is on.
     */
    fun importAsync(c: Context, onDone: (Outcome) -> Unit) {
        val app = c.applicationContext
        io.execute {
            val outcome = try {
                if (!isSignedIn(app)) Outcome(false, R.string.drive_err_not_signed_in)
                else {
                    val remote = findFile(app)
                    if (remote == null) Outcome(false, R.string.drive_import_none)
                    else {
                        applySnapshot(app, JSONObject(download(app, remote)), merge = true)
                        Outcome(true, R.string.drive_import_done)
                    }
                }
            } catch (e: DriveException) {
                Outcome(false, e.resId, e.detail)
            } catch (e: UserRecoverableAuthException) {
                cachedToken = null
                Outcome(false, R.string.drive_err_reauth, consentIntent = e.intent)
            } catch (e: java.io.IOException) {
                Outcome(false, R.string.drive_err_network)
            } catch (e: Exception) {
                Outcome(false, R.string.drive_err_generic, e.message.orEmpty())
            }
            main.post { onDone(outcome) }
        }
    }

    // ------------------------------------------------------------------ the sync itself

    private fun syncBlocking(c: Context): Outcome {
        val prefs = p(c)
        if (!prefs.getBoolean(K_SIGNED_IN, false)) return Outcome(false, R.string.drive_err_not_signed_in)

        val startedAt = System.currentTimeMillis()
        val localDirty = prefs.getLong(K_CHANGE, 0L) > prefs.getLong(K_SYNCED_AT, 0L)
        val knownRev = prefs.getString(K_REV, null)

        val remote = findFile(c)
        if (remote == null) {
            upload(c, null, startedAt)
            return Outcome(true, R.string.drive_sync_uploaded)
        }

        val snap = JSONObject(download(c, remote))
        val remoteRev = snap.optString("rev")
        val remoteChanged = remoteRev != knownRev

        return when {
            !remoteChanged && !localDirty -> {
                prefs.edit().putLong(K_SYNCED_AT, startedAt).putLong(K_LAST_OK, System.currentTimeMillis()).apply()
                Outcome(true, R.string.drive_sync_up_to_date)
            }
            !remoteChanged -> {
                upload(c, remote, startedAt)
                Outcome(true, R.string.drive_sync_uploaded)
            }
            !localDirty && knownRev != null -> {
                applySnapshot(c, snap, merge = false)
                prefs.edit().putString(K_REV, remoteRev).putLong(K_SYNCED_AT, startedAt)
                    .putLong(K_LAST_OK, System.currentTimeMillis()).apply()
                Outcome(true, R.string.drive_sync_downloaded)
            }
            else -> { // first sync on this device, or both sides changed: union, then upload
                applySnapshot(c, snap, merge = true)
                upload(c, remote, startedAt)
                Outcome(true, R.string.drive_sync_merged)
            }
        }
    }

    // ------------------------------------------------------------------ snapshot

    private fun buildSnapshot(c: Context, rev: String): JSONObject = JSONObject()
        .put("v", SNAPSHOT_VERSION)
        .put("rev", rev)
        .put("savedAt", System.currentTimeMillis())
        .put("playlists", PlaylistStore.exportJson(c).let { if (includeLocal(c)) it else stripLocal(it) })
        .put("history", MusicPrefs.exportHistory(c))
        .put("online_history", MusicPrefs.exportOnlineHistory(c))
        .put("stats", StatsStore.exportJson(c))

    private fun isLocalTrack(o: JSONObject) = o.optInt("src", Track.SOURCE_LOCAL) == Track.SOURCE_LOCAL

    private fun filterTracks(a: JSONArray?, keep: (JSONObject) -> Boolean): JSONArray {
        val out = JSONArray()
        if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (keep(it)) out.put(it) }
        return out
    }

    /** Playlists / liked songs without the songs that live on this phone (MediaStore files). */
    private fun stripLocal(pl: JSONObject): JSONObject {
        val lists = JSONArray()
        val src = pl.optJSONArray("lists")
        if (src != null) for (i in 0 until src.length()) {
            val l = src.optJSONObject(i) ?: continue
            lists.put(JSONObject(l.toString()).put("tracks", filterTracks(l.optJSONArray("tracks")) { !isLocalTrack(it) }))
        }
        return JSONObject(pl.toString()).put("lists", lists).put("liked", filterTracks(pl.optJSONArray("liked")) { !isLocalTrack(it) })
    }

    /**
     * Drive's copy without device songs: when a replace-style download would wipe this phone's own
     * songs from its playlists, put them back (by playlist id / liked), so they are never lost.
     */
    private fun keepLocalSongs(c: Context, remote: JSONObject): JSONObject {
        val mine = PlaylistStore.exportJson(c)
        val localByList = HashMap<String, JSONArray>()
        mine.optJSONArray("lists")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                localByList[it.optString("id")] = filterTracks(it.optJSONArray("tracks")) { t -> isLocalTrack(t) }
            }
        }
        val lists = JSONArray()
        val src = remote.optJSONArray("lists")
        if (src != null) for (i in 0 until src.length()) {
            val l = src.optJSONObject(i) ?: continue
            val tr = filterTracks(l.optJSONArray("tracks")) { true }
            localByList[l.optString("id")]?.let { for (j in 0 until it.length()) tr.put(it.get(j)) }
            lists.put(JSONObject(l.toString()).put("tracks", tr))
        }
        val liked = filterTracks(remote.optJSONArray("liked")) { true }
        filterTracks(mine.optJSONArray("liked")) { isLocalTrack(it) }.let { for (j in 0 until it.length()) liked.put(it.get(j)) }
        return JSONObject(remote.toString()).put("lists", lists).put("liked", liked)
    }

    private fun applySnapshot(c: Context, snap: JSONObject, merge: Boolean) {
        snap.optJSONObject("playlists")?.let { pl ->
            val keepLocal = !includeLocal(c)
            // Without the opt-in, device songs in the file (e.g. from an older backup) are ignored too.
            var data = if (keepLocal) stripLocal(pl) else pl
            if (keepLocal && !merge) data = keepLocalSongs(c, data)
            PlaylistStore.importJson(c, data, merge)
        }
        snap.optJSONArray("history")?.let { MusicPrefs.importHistory(c, it, merge) }
        snap.optJSONArray("online_history")?.let { MusicPrefs.importOnlineHistory(c, it, merge) }
        snap.optJSONObject("stats")?.let { StatsStore.importJson(c, it, merge) }
        // Songs from another phone are matched to this phone's files by title + artist (see LocalMatcher);
        // refresh its file index here, on the sync thread, rather than on the first screen that reads a playlist.
        try { LocalMatcher.refresh(c) } catch (_: Exception) { }
    }

    private fun upload(c: Context, existing: RemoteFile?, startedAt: Long) {
        val rev = UUID.randomUUID().toString()
        val json = buildSnapshot(c, rev).toString()
        val r = if (existing == null) {
            val boundary = "starry" + UUID.randomUUID().toString().replace("-", "")
            val meta = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder")).toString()
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$json\r\n--$boundary--"
            call(c, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
                body, "multipart/related; boundary=$boundary")
        } else {
            // HttpURLConnection has no PATCH; Google's APIs accept this override header on POST.
            call(c, "POST", "https://www.googleapis.com/upload/drive/v3/files/${existing.id}?uploadType=media",
                json, "application/json; charset=UTF-8", mapOf("X-HTTP-Method-Override" to "PATCH"))
        }
        if (r.code !in 200..299) throw httpError(r)
        p(c).edit().putString(K_REV, rev).putLong(K_SYNCED_AT, startedAt)
            .putLong(K_LAST_OK, System.currentTimeMillis()).apply()
    }

    // ------------------------------------------------------------------ Drive REST

    private fun findFile(c: Context): RemoteFile? {
        val q = URLEncoder.encode("name='$FILE_NAME'", "UTF-8")
        val r = call(c, "GET",
            "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1&fields=files(id)&q=$q")
        if (r.code !in 200..299) throw httpError(r)
        val files = JSONObject(r.body).optJSONArray("files") ?: return null
        val id = files.optJSONObject(0)?.optString("id").orEmpty()
        return if (id.isBlank()) null else RemoteFile(id)
    }

    private fun download(c: Context, f: RemoteFile): String {
        val r = call(c, "GET", "https://www.googleapis.com/drive/v3/files/${f.id}?alt=media")
        if (r.code !in 200..299) throw httpError(r)
        return r.body
    }

    private fun httpError(r: Http.Response): DriveException = when {
        r.code == 401 -> DriveException(R.string.drive_err_reauth)
        r.code == 403 && r.body.contains("accessNotConfigured") -> DriveException(R.string.drive_err_api_disabled)
        r.code == 403 && r.body.contains("storageQuotaExceeded") -> DriveException(R.string.drive_err_quota)
        else -> DriveException(R.string.drive_err_http, "HTTP ${r.code}${googleMessage(r.body)}")
    }

    /** Google's own explanation (error.message) so a failure says why, not just the status code. */
    private fun googleMessage(body: String): String = try {
        val m = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        if (m.isBlank()) "" else ": " + m.take(140)
    } catch (e: Exception) { "" }

    /**
     * SHA-1 of the certificate this APK is actually signed with. The Android OAuth client in Google
     * Cloud Console must list exactly this value (plus the package name), otherwise sign-in fails
     * with code 10. Shown in the error message so the mismatch is visible instead of guessed at.
     */
    fun signingSha1(c: Context): String = try {
        val pm = c.packageManager
        val cert = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo
            (if (si?.hasMultipleSigners() == true) si.apkContentsSigners else si?.signingCertificateHistory)
                ?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        }
        if (cert == null) "?" else MessageDigest.getInstance("SHA-1").digest(cert)
            .joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) { "?" }

    /** Authenticated request; on 401 the cached token is discarded and the call retried once. */
    private fun call(
        c: Context, method: String, url: String, body: String? = null,
        contentType: String? = null, extra: Map<String, String> = emptyMap()
    ): Http.Response {
        fun go(refresh: Boolean) = Http.request(
            method, url, mapOf("Authorization" to "Bearer ${token(c, refresh)}") + extra, body, contentType
        )
        val first = go(false)
        return if (first.code == 401) go(true) else first
    }

    private fun token(c: Context, refresh: Boolean): String {
        val account = GoogleSignIn.getLastSignedInAccount(c)?.account
            ?: throw DriveException(R.string.drive_err_reauth)
        if (refresh) cachedToken?.let { try { GoogleAuthUtil.clearToken(c, it) } catch (ignored: Exception) {} }
        val t = GoogleAuthUtil.getToken(c, account, "oauth2:$SCOPE_DRIVE_APPDATA")
        cachedToken = t
        return t
    }
}
