package com.fxsoundlite.app

/**
 * JNI bridge to the Discord Social SDK (see app/src/main/cpp/discord/README.md). The native library is
 * only built when the SDK has been dropped into that folder; without it [available] stays false and
 * every call is skipped, so the app builds and runs normally.
 */
object DiscordNative {
    val available: Boolean = try {
        System.loadLibrary("starry_discord")
        true
    } catch (_: Throwable) {
        false
    }

    /** Sets the Discord Application ID and starts the callback pump. */
    @JvmStatic external fun nativeInit(appId: Long): Boolean

    /** Shows "Listening to [name]" with [title] / [artist]; [startSec] / [endSec] are Unix seconds ([endSec] <= 0: no end). */
    @JvmStatic external fun nativeUpdate(
        name: String, title: String, artist: String,
        startSec: Long, endSec: Long, largeImage: String, largeText: String
    )

    @JvmStatic external fun nativeClear()
}
