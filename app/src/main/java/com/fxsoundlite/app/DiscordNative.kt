package com.fxsoundlite.app

import android.util.Log

/**
 * Jembatan JNI ke libstarry_discord.so.
 * Nama package/class HARUS tetap com.fxsoundlite.app.DiscordNative
 * (simbol JNI di .so: Java_com_fxsoundlite_app_DiscordNative_*).
 *
 * Library TIDAK dimuat saat startup. Panggil ensureLoaded() hanya setelah
 * user menyalakan toggle Discord dan mengisi App ID.
 */
object DiscordNative {
    @Volatile private var tried = false
    @Volatile private var ok = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (!tried) {
            tried = true
            ok = try {
                // Initialise the SDK's Java half first: its static init loads libdiscord_partner_sdk and the
                // JNI_OnLoad then finds com.discord.* (this is why R8 must keep that package).
                Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
                System.loadLibrary("starry_discord")
                true
            } catch (t: Throwable) {
                Log.w("DiscordNative", "Discord SDK unavailable", t)
                false
            }
        }
        return ok
    }

    /** Status tanpa memicu pemuatan library. */
    val available: Boolean get() = ok

    @JvmStatic external fun nativeInit(appId: Long): Boolean

    @JvmStatic external fun nativeUpdate(
        title: String,
        state: String,
        details: String,
        startSec: Long,
        endSec: Long,
        largeImage: String,
        largeText: String
    )

    @JvmStatic external fun nativeClear()
}
