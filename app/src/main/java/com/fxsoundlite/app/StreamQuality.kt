package com.fxsoundlite.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Streaming audio quality (Settings > Online music > Audio quality).
 * Applies to YouTube streams and to the radio station lists; local files are always played as they are.
 */
object StreamQuality {

    enum class Level(val tag: String) {
        /** Follows Data saver: Low while it is on, High otherwise (the behaviour before this setting existed). */
        AUTO(""),
        /** Smallest stream (about 48-70 kbps), radio up to 96 kbps. */
        LOW("l"),
        /** About 128 kbps, radio up to 160 kbps. */
        NORMAL("n"),
        /** Highest bitrate available. */
        HIGH("h");

        companion object {
            fun from(name: String?): Level = values().firstOrNull { it.name == name } ?: AUTO
        }
    }

    private const val PREFS = "stream_quality"
    private const val KEY = "level"

    /** Bitrate YouTube streams are matched to for [Level.NORMAL] (kbps). */
    const val NORMAL_TARGET_KBPS = 128
    private const val NORMAL_MAX_KBPS = 160
    const val RADIO_MAX_LOW = 96
    const val RADIO_MAX_NORMAL = 160

    fun level(context: Context): Level =
        Level.from(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun setLevel(context: Context, level: Level) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, level.name).apply()
    }

    /** The level actually in force: [Level.AUTO] resolved through Data saver. Never returns AUTO. */
    fun effective(context: Context): Level = when (val l = level(context)) {
        Level.AUTO -> if (MusicPrefs.saver(context)) Level.LOW else Level.HIGH
        else -> l
    }

    /** Highest kbps a radio station may have in lists, or 0 for no limit. */
    fun radioMaxKbps(context: Context): Int = when (effective(context)) {
        Level.LOW -> RADIO_MAX_LOW
        Level.NORMAL -> RADIO_MAX_NORMAL
        else -> 0
    }

    /** Picks the stream bitrate (kbps) for [level] out of [available] (values <= 0 = unknown are ignored). Null if none is known. */
    fun pickBitrate(level: Level, available: List<Int>): Int? {
        val known = available.filter { it > 0 }
        if (known.isEmpty()) return null
        return when (level) {
            Level.LOW -> (known.minOrNull() ?: known[0])
            Level.HIGH, Level.AUTO -> (known.maxOrNull() ?: known[0])
            // Closest to 128 kbps without going above 160; if everything is bigger, take the smallest.
            Level.NORMAL -> known.filter { it <= NORMAL_MAX_KBPS }.maxOrNull() ?: (known.minOrNull() ?: known[0])
        }
    }

    private fun labelRes(l: Level) = when (l) {
        Level.AUTO -> R.string.audio_quality_auto
        Level.LOW -> R.string.audio_quality_low
        Level.NORMAL -> R.string.audio_quality_normal
        Level.HIGH -> R.string.audio_quality_high
    }

    /** Short text for the settings row, e.g. "Automatic". */
    fun summary(context: Context): String = context.getString(labelRes(level(context)))

    /**
     * Shows the chooser. [onChanged] runs after a new level was saved (use it to refresh the row summary).
     * The change applies to the next song that starts; the song playing now is not interrupted.
     */
    fun showDialog(context: Context, onChanged: () -> Unit = {}) {
        val levels = Level.values()
        val items = arrayOf(
            context.getString(R.string.audio_quality_auto_desc),
            context.getString(R.string.audio_quality_low_desc),
            context.getString(R.string.audio_quality_normal_desc),
            context.getString(R.string.audio_quality_high_desc)
        )
        val labels = Array(levels.size) { context.getString(labelRes(levels[it])) + "\n" + items[it] }
        AlertDialog.Builder(context)
            .setTitle(R.string.audio_quality_title)
            .setSingleChoiceItems(labels, levels.indexOf(level(context))) { d, which ->
                setLevel(context, levels[which])
                d.dismiss()
                onChanged()
                android.widget.Toast.makeText(context, R.string.audio_quality_applies_next, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
