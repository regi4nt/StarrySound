// Ganti HANYA bagian awal apply() dan clear() di DiscordPresence.kt.
// Sisanya (hitung start/end, nativeInit, nativeUpdate, try/catch) biarkan seperti semula.

private fun apply() {
    val ctx = app ?: return
    // LAMA: if (!DiscordNative.available) return   <-- ini memicu loadLibrary tiap launch
    val id = appId(ctx).toLongOrNull()
    if (!enabled(ctx) || MusicPrefs.privateMode(ctx) || id == null || id <= 0L) {
        clear()
        return
    }
    if (!DiscordNative.ensureLoaded()) return
    // ... lanjut: ambil MusicService/Track, nativeInit(id), nativeUpdate(...)
}

private fun clear() {
    if (!shown) return
    shown = false
    lastKey = ""
    if (DiscordNative.available) DiscordNative.nativeClear()
}

// SettingsActivity.setupDiscordSection$status:
//   val ready = if (switchOn && appIdNotBlank) DiscordNative.ensureLoaded() else true
//   teks = when { !ready -> discord_status_no_sdk; appIdBlank -> discord_status_need_id; else -> discord_status_ready }
