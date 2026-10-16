package com.fxsoundlite.app

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import java.util.Locale

/**
 * Data used by this app while Data saver is on (shown next to the saver button).
 * Counts everything the app sends and receives (all of its network traffic, not only audio) since
 * the saver was switched on or the counter was last reset. The baseline is kept across restarts
 * (the system's counter only starts again at 0 after a reboot, which is detected).
 */
object DataUsage {
    private const val PREFS = "data_usage"
    private const val KEY_BASE = "base_bytes"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Total bytes of this app since boot, or -1 when the device cannot tell. */
    private fun total(): Long {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return -1L
        return rx + tx
    }

    /** Bytes used since the counter started (it starts now if it was not running); -1 = unsupported. */
    fun used(c: Context): Long {
        val now = total()
        if (now < 0) return -1L
        val p = prefs(c)
        val base = p.getLong(KEY_BASE, -1L)
        if (base < 0 || now < base) { // first use, or the phone rebooted
            p.edit().putLong(KEY_BASE, now).apply()
            return 0L
        }
        return now - base
    }

    /** Starts counting from zero. */
    fun reset(c: Context) {
        val now = total()
        if (now >= 0) prefs(c).edit().putLong(KEY_BASE, now).apply()
    }

    /** Saver is off: forget the baseline, the next time it is on counts from zero again. */
    fun clear(c: Context) {
        val p = prefs(c)
        if (p.contains(KEY_BASE)) p.edit().remove(KEY_BASE).apply()
    }

    fun format(bytes: Long): String = when {
        bytes < 1024L * 1024L -> String.format(Locale.US, "%d KB", bytes / 1024L)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
