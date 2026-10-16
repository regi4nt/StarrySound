package com.fxsoundlite.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.util.Calendar

/**
 * Wake-up alarm that rings with a song: the settings (one alarm), the next-ring maths and the
 * scheduling through [AlarmManager.setAlarmClock] (exact, survives Doze, needs no extra permission).
 * The alarm itself fires into [AlarmReceiver] -> [WakeAlarmActivity]. Re-armed after a reboot by [BootReceiver].
 */
object WakeAlarm {
    private const val PREFS = "wake_alarm"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_DAYS = "days"
    private const val KEY_TRACK = "track"
    private const val KEY_RANDOM = "random_liked"
    private const val KEY_SNOOZE = "snooze_min"
    private const val KEY_MODE = "sound_mode"
    private const val KEY_TONE = "tone_uri"

    const val MODE_SONG = 0
    /** Rings with a tone from the device's alarm sounds instead of a song. */
    const val MODE_TONE = 1

    const val EXTRA_SNOOZED = "snoozed"
    private const val RC_MAIN = 41
    private const val RC_SNOOZE = 42
    private const val RC_SHOW = 43

    val SNOOZE_CHOICES = intArrayOf(5, 10, 15)

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = p(c).getBoolean(KEY_ENABLED, false)
    fun hour(c: Context) = p(c).getInt(KEY_HOUR, 7)
    fun minute(c: Context) = p(c).getInt(KEY_MINUTE, 0)

    /** Bit 0 = Monday ... bit 6 = Sunday. 0 = ring once, then switch off. */
    fun days(c: Context) = p(c).getInt(KEY_DAYS, 0)
    fun snoozeMin(c: Context) = p(c).getInt(KEY_SNOOZE, 10)
    fun randomLiked(c: Context) = p(c).getBoolean(KEY_RANDOM, true)

    fun track(c: Context): Track? {
        val raw = p(c).getString(KEY_TRACK, null) ?: return null
        return try { PlaylistStore.trackFromJson(JSONObject(raw)) } catch (e: Exception) { null }
    }

    fun setTime(c: Context, h: Int, m: Int) {
        p(c).edit().putInt(KEY_HOUR, h).putInt(KEY_MINUTE, m).apply()
        rearm(c)
    }

    fun setDays(c: Context, mask: Int) {
        p(c).edit().putInt(KEY_DAYS, mask).apply()
        rearm(c)
    }

    fun setSnoozeMin(c: Context, m: Int) = p(c).edit().putInt(KEY_SNOOZE, m).apply()

    fun setTrack(c: Context, t: Track) =
        p(c).edit().putString(KEY_TRACK, PlaylistStore.trackToJson(t).toString()).putBoolean(KEY_RANDOM, false).apply()

    fun soundMode(c: Context) = p(c).getInt(KEY_MODE, MODE_SONG)
    fun setSoundMode(c: Context, mode: Int) = p(c).edit().putInt(KEY_MODE, mode).apply()

    /** The chosen device alarm tone, or null for the system default alarm sound. */
    fun toneUri(c: Context): android.net.Uri? = p(c).getString(KEY_TONE, null)?.let { android.net.Uri.parse(it) }
    fun setToneUri(c: Context, uri: android.net.Uri?) =
        p(c).edit().apply { if (uri == null) remove(KEY_TONE) else putString(KEY_TONE, uri.toString()) }.apply()

    /** Uri to ring with: the chosen tone, else the system default alarm sound. */
    fun effectiveToneUri(c: Context): android.net.Uri? =
        toneUri(c) ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

    fun toneTitle(c: Context): String = try {
        android.media.RingtoneManager.getRingtone(c, effectiveToneUri(c))?.getTitle(c).orEmpty()
    } catch (e: Exception) { "" }

    fun setRandomLiked(c: Context) = p(c).edit().putBoolean(KEY_RANDOM, true).apply()

    fun setEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean(KEY_ENABLED, on).apply()
        rearm(c)
    }

    private fun rearm(c: Context) { if (isEnabled(c)) schedule(c) else cancel(c) }

    /** Next time the alarm rings, in ms since the epoch. */
    fun nextTrigger(c: Context, now: Long = System.currentTimeMillis()): Long {
        val days = days(c)
        for (d in 0..7) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, hour(c))
                set(Calendar.MINUTE, minute(c))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now) continue
            val bit = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon = 0 ... Sun = 6
            if (days == 0 || days and (1 shl bit) != 0) return cal.timeInMillis
        }
        return now + 24 * 3600_000L
    }

    private fun alarmManager(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun fireIntent(c: Context, snoozed: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            c, if (snoozed) RC_SNOOZE else RC_MAIN,
            Intent(c, AlarmReceiver::class.java).putExtra(EXTRA_SNOOZED, snoozed),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun showIntent(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, RC_SHOW, Intent(c, AlarmActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun set(c: Context, at: Long, snoozed: Boolean) {
        try {
            alarmManager(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(c)), fireIntent(c, snoozed))
        } catch (e: Exception) {
            android.util.Log.w("WakeAlarm", "could not schedule", e)
        }
    }

    /** (Re)arms the alarm for its next ring; does nothing when it is switched off. */
    fun schedule(c: Context) {
        val app = c.applicationContext
        if (!isEnabled(app)) return
        set(app, nextTrigger(app), snoozed = false)
    }

    fun cancel(c: Context) {
        val app = c.applicationContext
        alarmManager(app).cancel(fireIntent(app, false))
        alarmManager(app).cancel(fireIntent(app, true))
    }

    /** Rings again in [snoozeMin] minutes (the regular alarm stays armed). */
    fun snooze(c: Context) {
        val app = c.applicationContext
        set(app, System.currentTimeMillis() + snoozeMin(app) * 60_000L, snoozed = true)
    }

    /** Called when the regular alarm fired: a one-time alarm switches itself off, a repeating one is armed again. */
    fun onFired(c: Context) {
        val app = c.applicationContext
        if (days(app) == 0) p(app).edit().putBoolean(KEY_ENABLED, false).apply() else schedule(app)
    }
}
