package com.fxsoundlite.app

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock

/**
 * Settings for the online music source: the YouTube Data API key and the last chosen source.
 * Kept in their own file so they are easy to wipe independently.
 */
object MusicPrefs {
    private const val PREFS = "online_music_prefs"
    private const val KEY_YT_KEY = "youtube_api_key"
    private const val KEY_ACR_HOST = "acr_host"
    private const val KEY_ACR_KEY = "acr_access_key"
    private const val KEY_ACR_SECRET = "acr_access_secret"
    private const val KEY_AI_KEY = "gemini_api_key"        // kept under its old name so existing Gemini keys still work
    private const val KEY_OPENAI_KEY = "openai_api_key"
    private const val KEY_ANTHROPIC_KEY = "anthropic_api_key"
    private const val KEY_AI_PROVIDER = "ai_provider"
    private const val KEY_AI_BALANCE = "ai_balance"
    private const val KEY_AI_MODEL_PREFIX = "ai_model_"
    private const val KEY_LAST_SOURCE = "last_source"
    private const val KEY_SAVER = "saver_mode"
    private const val KEY_AUDIO_LANG = "yt_audio_lang"
    private const val KEY_TRANS_LANG = "lyrics_trans_lang"
    private const val KEY_SAVER_AUTO = "saver_auto_mobile"
    private const val KEY_RADIO_SOURCE = "radio_source"
    private const val KEY_SHOUTCAST = "shoutcast_api_key"
    private const val KEY_YT_HISTORY = "youtube_history"
    private const val KEY_ONLINE_HISTORY = "online_history"
    const val HISTORY_MAX = 10
    private const val KEY_PLAYER_BG = "player_bg_screensaver"
    private const val KEY_COVER_ANIM = "player_cover_anim"
    private const val KEY_COVER_CIRCLE = "player_cover_circle"

    /** Full-screen player background: false = plain theme colour (default), true = the Screensaver's background. */
    fun playerBgScreensaver(c: Context): Boolean = p(c).getBoolean(KEY_PLAYER_BG, false)
    fun setPlayerBgScreensaver(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_PLAYER_BG, v).apply()

    /** Cover frame of the full-screen player: false = square (default), true = circle. */
    fun coverAnim(c: Context): Boolean = p(c).getBoolean(KEY_COVER_ANIM, true)
    fun setCoverAnim(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_COVER_ANIM, v).apply()
    fun coverCircle(c: Context): Boolean = p(c).getBoolean(KEY_COVER_CIRCLE, false)
    fun setCoverCircle(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_COVER_CIRCLE, v).apply()

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val KEY_PRIVATE_MODE = "private_mode"
    private const val KEY_QUICK_LOCK = "quick_vertical_lock"
    private const val KEY_QUICK_LYRICS = "quick_lyrics"

    /** Quick page lock: while on, Quick only scrolls up / down and a sideways swipe no longer leaves the page. */
    fun quickLock(c: Context): Boolean = p(c).getBoolean(KEY_QUICK_LOCK, false)
    fun setQuickLock(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_QUICK_LOCK, v).apply()
    /** Lyrics under the cover in the Quick tab (on by default); switched in the reel's three-dot menu. */
    fun quickLyrics(c: Context): Boolean = p(c).getBoolean(KEY_QUICK_LYRICS, true)
    fun setQuickLyrics(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_QUICK_LYRICS, v).apply()

    /** Private mode: while on, nothing that is played is remembered (stats, recent / YouTube history,
     *  Last.fm scrobbles). Kept across restarts; never synced to Drive. */
    /** Search page: Device and YouTube results open as video instead of audio. */
    fun videoMode(c: Context): Boolean = p(c).getBoolean("video_mode", false)
    fun setVideoMode(c: Context, v: Boolean) = p(c).edit().putBoolean("video_mode", v).apply()

    /** Id of the playlist the user last started playing from the Playlist page; it is pinned on top of the overview. */
    fun lastPlayedList(c: Context): String? = p(c).getString("last_played_list", null)
    fun setLastPlayedList(c: Context, id: String?) = p(c).edit().putString("last_played_list", id).apply()

    fun privateMode(c: Context): Boolean = p(c).getBoolean(KEY_PRIVATE_MODE, false)
    fun setPrivateMode(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_PRIVATE_MODE, v).apply()

    // AI service for the "AI picks" shelf on Home and AI playlists (AiRecommender): Gemini, OpenAI or Anthropic,
    // each with the user's own API key and an optional model name. Optional; never synced to Drive.
    fun aiProvider(c: Context): AiProvider = AiProvider.fromId(p(c).getString(KEY_AI_PROVIDER, null))
    fun setAiProvider(c: Context, v: AiProvider) = p(c).edit().putString(KEY_AI_PROVIDER, v.id).apply()

    /** Spread AI requests over every service that has a key (see [AiLoad]). On by default. */
    fun aiBalance(c: Context): Boolean = p(c).getBoolean(KEY_AI_BALANCE, true)
    fun setAiBalance(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_AI_BALANCE, v).apply()

    private fun aiKeyName(v: AiProvider) = when (v) {
        AiProvider.GEMINI -> KEY_AI_KEY
        AiProvider.OPENAI -> KEY_OPENAI_KEY
        AiProvider.ANTHROPIC -> KEY_ANTHROPIC_KEY
    }

    /** Key of [provider] (default: the selected one). */
    fun aiKey(c: Context, provider: AiProvider = aiProvider(c)): String =
        p(c).getString(aiKeyName(provider), "").orEmpty().trim()
    fun setAiKey(c: Context, v: String, provider: AiProvider = aiProvider(c)) =
        p(c).edit().putString(aiKeyName(provider), v.trim()).apply()

    /** What the user typed in the model field ("" = use the default). */
    fun aiModelCustom(c: Context, provider: AiProvider = aiProvider(c)): String =
        p(c).getString(KEY_AI_MODEL_PREFIX + provider.id, "").orEmpty().trim()
    fun setAiModel(c: Context, v: String, provider: AiProvider = aiProvider(c)) =
        p(c).edit().putString(KEY_AI_MODEL_PREFIX + provider.id, v.trim()).apply()
    /** The model actually requested: the custom name, else the provider's default. */
    fun aiModel(c: Context, provider: AiProvider = aiProvider(c)): String =
        aiModelCustom(c, provider).ifEmpty { provider.defaultModel }

    fun youtubeKey(c: Context): String = p(c).getString(KEY_YT_KEY, "").orEmpty().trim()
    fun setYoutubeKey(c: Context, v: String) = p(c).edit().putString(KEY_YT_KEY, v.trim()).apply()

    // ACRCloud credentials for song detection by humming (SongRecognizer). Optional.
    fun acrHost(c: Context): String = p(c).getString(KEY_ACR_HOST, "").orEmpty().trim()
    fun setAcrHost(c: Context, v: String) = p(c).edit().putString(KEY_ACR_HOST, v.trim()).apply()
    fun acrKey(c: Context): String = p(c).getString(KEY_ACR_KEY, "").orEmpty().trim()
    fun setAcrKey(c: Context, v: String) = p(c).edit().putString(KEY_ACR_KEY, v.trim()).apply()
    fun acrSecret(c: Context): String = p(c).getString(KEY_ACR_SECRET, "").orEmpty().trim()
    fun setAcrSecret(c: Context, v: String) = p(c).edit().putString(KEY_ACR_SECRET, v.trim()).apply()
    fun acrConfigured(c: Context): Boolean = acrHost(c).isNotBlank() && acrKey(c).isNotBlank() && acrSecret(c).isNotBlank()

    /** Removes whatever an older version stored for Spotify (login tokens, client id). */
    fun purgeLegacySpotify(c: Context) {
        val prefs = p(c)
        if (!prefs.all.keys.any { it.startsWith("spotify_") }) return
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("spotify_") }.forEach { remove(it) } }.apply()
    }

    fun lastSource(c: Context): Int =
        p(c).getInt(KEY_LAST_SOURCE, Track.SOURCE_LOCAL)
            .takeIf { it == Track.SOURCE_LOCAL || it == Track.SOURCE_YOUTUBE || it == Track.SOURCE_RADIO || it == Track.SOURCE_DRIVE }
            ?: Track.SOURCE_LOCAL
    fun setLastSource(c: Context, v: Int) = p(c).edit().putInt(KEY_LAST_SOURCE, v).apply()

    /** Country of the Radio tab's first list: a 2-letter code, "" = worldwide. Default: the device's country. */
    fun radioCountry(c: Context): String =
        p(c).getString("radio_country", null) ?: java.util.Locale.getDefault().country.lowercase(java.util.Locale.ROOT).takeIf { it.length == 2 }.orEmpty()
    fun setRadioCountry(c: Context, code: String) = p(c).edit().putString("radio_country", code).apply()

    /** Which radio directory the Radio tab uses (one of RadioApi.SRC_*). */
    fun radioSource(c: Context): Int = p(c).getInt(KEY_RADIO_SOURCE, RadioApi.SRC_BROWSER)
    fun setRadioSource(c: Context, v: Int) = p(c).edit().putInt(KEY_RADIO_SOURCE, v).apply()

    /** Free Shoutcast developer key (the Shoutcast directory cannot be used without one). */
    fun shoutcastKey(c: Context): String = p(c).getString(KEY_SHOUTCAST, "").orEmpty().trim()
    fun setShoutcastKey(c: Context, v: String) = p(c).edit().putString(KEY_SHOUTCAST, v.trim()).apply()

    /** "Hemat" mode: saves battery and mobile data (low-bitrate audio, no thumbnails, fewer
     *  fallbacks and refreshes). Off = normal mode. */
    /** Preferred YouTube audio (dub) language code, e.g. "en", "id"; "" = the video's original audio. */
    fun audioLang(c: Context): String = p(c).getString(KEY_AUDIO_LANG, "") ?: ""
    fun setAudioLang(c: Context, v: String) = p(c).edit().putString(KEY_AUDIO_LANG, v).apply()

    /** Language the lyrics are translated to: "id" (default) or "en". */
    fun lyricsTransLang(c: Context): String = p(c).getString(KEY_TRANS_LANG, "id").let { if (it == "en") "en" else "id" }
    fun setLyricsTransLang(c: Context, v: String) = p(c).edit().putString(KEY_TRANS_LANG, v).apply()

    /** Effective data-saver state: switched on by hand, or automatically while on mobile data / a metered network. */
    fun saver(c: Context): Boolean = saverManual(c) || (saverAuto(c) && onMeteredNetwork(c))
    /** The saver switch as the user set it (without the automatic part). */
    fun saverManual(c: Context): Boolean = p(c).getBoolean(KEY_SAVER, false)
    fun setSaver(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_SAVER, v).apply()
    /** "Save data automatically on mobile data" (off by default). */
    // YouTube search filters (Search page > YouTube tab).
    fun ytLocal(c: Context): Boolean = p(c).getBoolean("yt_local_only", false)
    fun setYtLocal(c: Context, v: Boolean) = p(c).edit().putBoolean("yt_local_only", v).apply()
    fun ytNewest(c: Context): Boolean = p(c).getBoolean("yt_newest_first", false)
    fun setYtNewest(c: Context, v: Boolean) = p(c).edit().putBoolean("yt_newest_first", v).apply()
    fun ytBlockShorts(c: Context): Boolean = p(c).getBoolean("yt_block_shorts", true)
    fun setYtBlockShorts(c: Context, v: Boolean) = p(c).edit().putBoolean("yt_block_shorts", v).apply()

    /** Player / mini player take their accent from the cover of the playing song (on by default). */
    fun coverTheme(c: Context): Boolean = p(c).getBoolean("cover_theme", true)
    fun setCoverTheme(c: Context, v: Boolean) = p(c).edit().putBoolean("cover_theme", v).apply()

    /** Jump over intro / outro / sponsor / non-music parts of YouTube songs (SponsorBlock data). Off by default: it contacts a third-party server. */
    fun skipSegments(c: Context): Boolean = p(c).getBoolean("skip_segments", false)
    fun setSkipSegments(c: Context, v: Boolean) = p(c).edit().putBoolean("skip_segments", v).apply()

    fun saverAuto(c: Context): Boolean = p(c).getBoolean(KEY_SAVER_AUTO, false)
    fun setSaverAuto(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_SAVER_AUTO, v).apply()

    // saver() is asked very often (list rows, progress ticks): the network state is looked up at most every 5 s.
    @Volatile private var meteredAt = -10_000L
    @Volatile private var meteredNow = false

    private fun onMeteredNetwork(c: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - meteredAt < 5_000L) return meteredNow
        meteredNow = try {
            c.applicationContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        } catch (_: Exception) { false }
        meteredAt = now
        return meteredNow
    }

    private fun parseHistory(arr: org.json.JSONArray): List<Track> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else Track(
                id = 0L,
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                durationMs = o.optLong("dur"),
                source = Track.SOURCE_YOUTUBE,
                remoteId = id,
                artUrl = o.optString("art")
            )
        }

    /** The last [HISTORY_MAX] YouTube songs that were played, newest first. */
    fun youtubeHistory(c: Context): List<Track> {
        val raw = p(c).getString(KEY_YT_HISTORY, null) ?: return emptyList()
        return try { parseHistory(org.json.JSONArray(raw)) } catch (e: Exception) { emptyList() }
    }

    /** History as JSON for the Drive backup. */
    fun exportHistory(c: Context): org.json.JSONArray =
        try { org.json.JSONArray(p(c).getString(KEY_YT_HISTORY, null) ?: "[]") } catch (e: Exception) { org.json.JSONArray() }

    /** Applies a Drive snapshot (merge = local first, then what only Drive has). Never marks data as changed. */
    fun importHistory(c: Context, arr: org.json.JSONArray, merge: Boolean) {
        val incoming = parseHistory(arr)
        val result = if (merge) (youtubeHistory(c) + incoming).distinctBy { it.remoteId } else incoming
        saveYoutubeHistory(c, result.take(HISTORY_MAX))
    }

    private fun saveYoutubeHistory(c: Context, list: List<Track>) {
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("id", it.remoteId).put("title", it.title).put("artist", it.artist)
                    .put("album", it.album).put("dur", it.durationMs).put("art", it.artUrl)
            )
        }
        p(c).edit().putString(KEY_YT_HISTORY, arr.toString()).apply()
    }

    /** Records a played YouTube song at the top of the history (a repeat just moves it up). */
    fun addYoutubeHistory(c: Context, t: Track) {
        if (privateMode(c)) return
        if (t.source != Track.SOURCE_YOUTUBE || t.remoteId.isBlank()) return
        saveYoutubeHistory(c, (listOf(t) + youtubeHistory(c).filter { it.remoteId != t.remoteId }).take(HISTORY_MAX))
        DriveSync.markDirty(c)
    }

    // Online-tab history (Internet Archive, Audius, Jamendo, ccMixter): same idea, own list. remoteId = audio link.
    fun onlineHistory(c: Context): List<Track> {
        val raw = p(c).getString(KEY_ONLINE_HISTORY, null) ?: return emptyList()
        return try { parseHistory(org.json.JSONArray(raw)).map { it.copy(source = Track.SOURCE_ONLINE) } } catch (e: Exception) { emptyList() }
    }

    private fun saveOnlineHistory(c: Context, list: List<Track>) {
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("id", it.remoteId).put("title", it.title).put("artist", it.artist)
                    .put("album", it.album).put("dur", it.durationMs).put("art", it.artUrl)
            )
        }
        p(c).edit().putString(KEY_ONLINE_HISTORY, arr.toString()).apply()
    }

    /** Records a played Online-tab song at the top of its history (a repeat just moves it up). */
    fun addOnlineHistory(c: Context, t: Track) {
        if (privateMode(c)) return
        if (t.source != Track.SOURCE_ONLINE || t.remoteId.isBlank()) return
        saveOnlineHistory(c, (listOf(t) + onlineHistory(c).filter { it.remoteId != t.remoteId }).take(HISTORY_MAX))
        DriveSync.markDirty(c)
    }

    fun removeOnlineHistory(c: Context, remoteId: String) {
        saveOnlineHistory(c, onlineHistory(c).filter { it.remoteId != remoteId })
        DriveSync.markDirty(c)
    }

    /** Online history as JSON for the Drive backup. */
    fun exportOnlineHistory(c: Context): org.json.JSONArray =
        try { org.json.JSONArray(p(c).getString(KEY_ONLINE_HISTORY, null) ?: "[]") } catch (e: Exception) { org.json.JSONArray() }

    /** Applies a Drive snapshot (merge = local first, then what only Drive has). Never marks data as changed. */
    fun importOnlineHistory(c: Context, arr: org.json.JSONArray, merge: Boolean) {
        val incoming = parseHistory(arr).map { it.copy(source = Track.SOURCE_ONLINE) }
        val result = if (merge) (onlineHistory(c) + incoming).distinctBy { it.remoteId } else incoming
        saveOnlineHistory(c, result.take(HISTORY_MAX))
    }

    fun clearOnlineHistory(c: Context) {
        p(c).edit().remove(KEY_ONLINE_HISTORY).apply()
        DriveSync.markDirty(c)
    }

    /** Removes one song from the YouTube history. */
    fun removeYoutubeHistory(c: Context, remoteId: String) {
        saveYoutubeHistory(c, youtubeHistory(c).filter { it.remoteId != remoteId })
        DriveSync.markDirty(c)
    }

    /** Empties the whole YouTube history. */
    fun clearYoutubeHistory(c: Context) {
        p(c).edit().remove(KEY_YT_HISTORY).apply()
        DriveSync.markDirty(c)
    }

    // ---- "Recently played" shelf on Home: every source (device, YouTube, radio), newest first.
    private const val KEY_RECENT = "recent_played"
    private const val RECENT_MAX = 30

    fun recentPlayed(c: Context): List<Track> {
        val raw = p(c).getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Track(
                    id = o.optLong("id"), title = o.optString("title"), artist = o.optString("artist"),
                    album = o.optString("album"), durationMs = o.optLong("dur"),
                    source = o.optInt("src"), remoteId = o.optString("rid"), artUrl = o.optString("art")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun addRecent(c: Context, t: Track) {
        if (privateMode(c) || t.title.isBlank()) return
        val list = (listOf(t) + recentPlayed(c).filter { it.key != t.key }).take(RECENT_MAX)
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(
                org.json.JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                    .put("album", it.album).put("dur", it.durationMs).put("src", it.source)
                    .put("rid", it.remoteId).put("art", it.artUrl)
            )
        }
        p(c).edit().putString(KEY_RECENT, arr.toString()).apply()
    }
}
