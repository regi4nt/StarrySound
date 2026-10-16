package com.fxsoundlite.app

import java.io.DataOutputStream

/**
 * Minimal root shell helper. Deliberately not using a third-party root library
 * (libsu, RootTools) to keep the APK tiny — a handful of Runtime.exec calls is
 * enough for what we actually need.
 */
object RootUtils {

    /** Quick check: does a "su" binary exist and answer? Cheap, no permission prompt triggered. */
    fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/data/local/bin/su", "/data/local/xbin/su", "/su/bin/su"
        )
        return paths.any { java.io.File(it).exists() }
    }

    /**
     * Runs a command as root. Returns true if the shell accepted it (exit code 0).
     * This is where the actual Superuser permission prompt from Magisk/SuperSU appears
     * the first time it's called.
     */
    fun runAsRoot(command: String): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("$command\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    /**
     * Kills the audioserver system process. On Android, init immediately restarts it
     * (it's a service defined in init.rc), which forces all audio effect chains to
     * reload cleanly. Useful if effects "stick" or a previous crash left stale state.
     * Genuinely requires root — this is not something a normal app is allowed to do.
     */
    fun restartAudioServer(): Boolean =
        runAsRoot("pkill -f audioserver || killall audioserver")

    /**
     * Writes a flag file read by BootReceiver so effect settings re-apply as early as
     * possible after boot (before the user opens the app), instead of waiting for
     * Android's normal BOOT_COMPLETED broadcast ordering.
     */
    fun installEarlyBootHook(): Boolean =
        runAsRoot("echo 1 > /data/local/tmp/fxsoundlite_autostart")

    /** Runs a command as root and returns what it printed (stderr dropped, capped at [maxChars]); null on failure/empty. */
    fun runAsRootForOutput(command: String, maxChars: Int = 4_000_000): String? {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("$command 2>/dev/null\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            val sb = StringBuilder()
            process.inputStream.bufferedReader().use { r ->
                val buf = CharArray(8192)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    if (sb.length < maxChars) sb.append(buf, 0, n) // keep draining so the process never blocks
                }
            }
            process.waitFor()
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            null
        } finally {
            process?.destroy()
        }
    }

    /** Dump of Android's audio server (includes every effect instance that is alive). */
    fun audioFlingerDump(): String? = runAsRootForOutput("dumpsys media.audio_flinger")
}
