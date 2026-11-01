package com.fxsoundlite.app

import android.app.Application

/**
 * Applies the saved Dark/Light/System theme mode before any Activity is
 * created, so the very first frame already has the right palette instead of
 * flashing light-then-dark (or vice versa).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeHelper.applyMode(this)
        CrashLog.install(this)
        AutoFullscreen.install(this)
        AppLock.install(this)
        MusicPrefs.purgeLegacySpotify(this)
        DiscordPresence.start(this)
        MusicLibrary.watchStorage(this)
        Thread { CacheCleaner.autoTrim(this) }.start()
        try {
            org.schabi.newpipe.extractor.NewPipe.init(NewPipeDownloader)
        } catch (e: Throwable) {
            android.util.Log.w("App", "NewPipe init failed", e)
        }
    }
}
