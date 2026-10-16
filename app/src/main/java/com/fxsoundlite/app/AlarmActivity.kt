package com.fxsoundlite.app

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.RingtoneManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Wake-up alarm settings (opened from the Alarm tile on Home): time, repeat days, the song that wakes you
 * and the snooze length. The alarm itself is scheduled by [WakeAlarm] and rings in [WakeAlarmActivity].
 */
class AlarmActivity : AppCompatActivity() {

    private val ctx: Context get() = this
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var dismissed = false

    private val timeView by lazy { findViewById<TextView>(R.id.alarmTime) }
    private val statusView by lazy { findViewById<TextView>(R.id.alarmStatus) }
    private val switchView by lazy { findViewById<SwitchCompat>(R.id.alarmSwitch) }
    private val daysBox by lazy { findViewById<LinearLayout>(R.id.alarmDays) }
    private val repeatHint by lazy { findViewById<TextView>(R.id.alarmRepeatHint) }
    private val songTitle by lazy { findViewById<TextView>(R.id.alarmSongTitle) }
    private val songArtist by lazy { findViewById<TextView>(R.id.alarmSongArtist) }
    private val modeBox by lazy { findViewById<LinearLayout>(R.id.alarmModeChips) }
    private val snoozeBox by lazy { findViewById<LinearLayout>(R.id.alarmSnoozeChips) }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the alarm still rings its song without it */ }

    // System picker of the device's alarm tones.
    private val toneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != RESULT_OK) return@registerForActivityResult
            val data = res.data ?: return@registerForActivityResult
            WakeAlarm.setToneUri(ctx, pickedUri(data)) // null = the system default alarm sound
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        findViewById<View>(R.id.alarmTimeBox).setOnClickListener { pickTime() }
        switchView.setOnCheckedChangeListener { _, on -> onToggle(on) }
        findViewById<View>(R.id.alarmSongCard).setOnClickListener {
            if (WakeAlarm.soundMode(ctx) == WakeAlarm.MODE_TONE) pickTone() else pickSong()
        }
        findViewById<View>(R.id.alarmTest).setOnClickListener {
            startActivity(Intent(ctx, WakeAlarmActivity::class.java))
        }
        buildDayChips()
        buildModeChips()
        buildSnoozeChips()
        refresh()
    }

    override fun onDestroy() {
        dismissed = true
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ state

    private fun refresh() {
        timeView.text = formatTime(WakeAlarm.hour(ctx), WakeAlarm.minute(ctx))
        val on = WakeAlarm.isEnabled(ctx)
        if (switchView.isChecked != on) {
            // Setting it from code must not re-run onToggle.
            switchView.setOnCheckedChangeListener(null)
            switchView.isChecked = on
            switchView.setOnCheckedChangeListener { _, v -> onToggle(v) }
        }
        statusView.text = if (on) getString(R.string.alarm_next, untilText(WakeAlarm.nextTrigger(ctx)))
        else getString(R.string.alarm_off_status)

        val days = WakeAlarm.days(ctx)
        for (i in 0 until daysBox.childCount) daysBox.getChildAt(i).isSelected = days and (1 shl i) != 0
        repeatHint.text = getString(if (days == 0) R.string.alarm_repeat_once else R.string.alarm_repeat_days)

        val toneMode = WakeAlarm.soundMode(ctx) == WakeAlarm.MODE_TONE
        for (i in 0 until modeBox.childCount) modeBox.getChildAt(i).isSelected = (i == WakeAlarm.MODE_TONE) == toneMode
        val track = WakeAlarm.track(ctx)
        if (toneMode) {
            songTitle.text = WakeAlarm.toneTitle(ctx).ifBlank { getString(R.string.alarm_mode_tone) }
            songArtist.text = getString(R.string.alarm_mode_tone)
        } else if (WakeAlarm.randomLiked(ctx) || track == null) {
            songTitle.text = getString(R.string.alarm_song_random)
            songArtist.text = getString(R.string.stats_liked)
        } else {
            songTitle.text = track.title
            songArtist.text = track.artist
        }
        val snooze = WakeAlarm.snoozeMin(ctx)
        for (i in 0 until snoozeBox.childCount) snoozeBox.getChildAt(i).isSelected = WakeAlarm.SNOOZE_CHOICES[i] == snooze
    }

    private fun formatTime(h: Int, m: Int): String = String.format(Locale.US, "%02d:%02d", h, m)

    private fun untilText(at: Long): String {
        val totalMin = ((at - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0) + 1
        val d = totalMin / (24 * 60)
        val h = totalMin / 60 % 24
        val m = totalMin % 60
        val base = if (h > 0) getString(R.string.stats_fmt_hour_min, h, m) else getString(R.string.stats_fmt_min, m)
        return if (d > 0) getString(R.string.alarm_days_prefix, d) + " " + base else base
    }

    private fun onToggle(on: Boolean) {
        WakeAlarm.setEnabled(ctx, on)
        if (on) askPermissions()
        refresh()
    }

    /** Notifications (Android 13+) and full-screen alerts (Android 14+) let the alarm screen open on a locked phone. */
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                Toast.makeText(ctx, R.string.alarm_need_fullscreen, Toast.LENGTH_LONG).show()
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ time, days, snooze

    private fun pickTime() {
        TimePickerDialog(
            ctx,
            { _, h, m ->
                WakeAlarm.setTime(ctx, h, m)
                if (!WakeAlarm.isEnabled(ctx)) { WakeAlarm.setEnabled(ctx, true); askPermissions() }
                refresh()
            },
            WakeAlarm.hour(ctx), WakeAlarm.minute(ctx), DateFormat.is24HourFormat(ctx)
        ).show()
    }

    private fun buildDayChips() {
        val names = intArrayOf(
            R.string.alarm_day_mon, R.string.alarm_day_tue, R.string.alarm_day_wed, R.string.alarm_day_thu,
            R.string.alarm_day_fri, R.string.alarm_day_sat, R.string.alarm_day_sun
        )
        daysBox.removeAllViews()
        for ((i, res) in names.withIndex()) {
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, daysBox, false) as TextView
            chip.setText(res)
            chip.setOnClickListener {
                WakeAlarm.setDays(ctx, WakeAlarm.days(ctx) xor (1 shl i))
                refresh()
            }
            daysBox.addView(chip)
        }
    }

    /** Song (from the app) or a tone from the device's alarm sounds. */
    private fun buildModeChips() {
        modeBox.removeAllViews()
        val labels = intArrayOf(R.string.alarm_mode_song, R.string.alarm_mode_tone) // index == WakeAlarm.MODE_*
        val gap = (8 * resources.displayMetrics.density).toInt()
        for ((i, res) in labels.withIndex()) {
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, modeBox, false) as TextView
            chip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i == labels.lastIndex) 0 else gap
            }
            chip.gravity = android.view.Gravity.CENTER
            chip.setText(res)
            chip.setOnClickListener { WakeAlarm.setSoundMode(ctx, i); refresh() }
            modeBox.addView(chip)
        }
    }

    @Suppress("DEPRECATION")
    private fun pickedUri(data: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        else data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)

    private fun pickTone() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.alarm_tone_picker_title))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, WakeAlarm.effectiveToneUri(ctx))
        try { toneLauncher.launch(i) } catch (_: Exception) { }
    }

    private fun buildSnoozeChips() {
        snoozeBox.removeAllViews()
        for (m in WakeAlarm.SNOOZE_CHOICES) {
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, snoozeBox, false) as TextView
            chip.text = getString(R.string.alarm_snooze_min, m)
            chip.setOnClickListener { WakeAlarm.setSnoozeMin(ctx, m); refresh() }
            snoozeBox.addView(chip)
        }
    }

    // ------------------------------------------------------------------ song picker

    private class Entry(val label: String, val sub: String, val track: Track?) // track == null: random from Liked songs

    private fun pickSong() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val search = EditText(ctx).apply {
            setHint(R.string.alarm_pick_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val listView = ListView(ctx)
        box.addView(search)
        box.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * resources.displayMetrics.density).toInt()))

        // Everything the user already has: liked songs, every playlist, then the device (loaded in the background).
        val library = LinkedHashMap<String, Track>()
        PlaylistStore.liked(ctx).forEach { if (!it.missing) library.putIfAbsent(it.key, it) }
        PlaylistStore.playlists(ctx).forEach { pl -> pl.tracks.forEach { if (!it.missing) library.putIfAbsent(it.key, it) } }
        var online: List<Track> = emptyList()
        var shown: List<Entry> = emptyList()
        val adapter = object : ArrayAdapter<Entry>(ctx, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).apply { text = e.label; setTextColor(themeColor(R.attr.colorTextPrimary)) }
                v.findViewById<TextView>(android.R.id.text2).apply { text = e.sub; setTextColor(themeColor(R.attr.colorTextSecondary)) }
                return v
            }
        }
        listView.adapter = adapter

        fun rebuild() {
            val q = search.text.toString().trim().lowercase(Locale.getDefault())
            val list = ArrayList<Entry>()
            if (q.isEmpty()) list += Entry(getString(R.string.alarm_song_random), getString(R.string.stats_liked), null)
            val local = library.values.filter { q.isEmpty() || (it.title + " " + it.artist).lowercase(Locale.getDefault()).contains(q) }
            (local.take(200) + online.filter { o -> local.none { it.key == o.key } })
                .forEach { list += Entry(it.title, it.artist, it) }
            shown = list
            adapter.clear(); adapter.addAll(list); adapter.notifyDataSetChanged()
        }
        rebuild()

        if (MusicLibrary.hasPermission(ctx)) {
            try {
                io.execute {
                    val device = try { MusicLibrary.load(applicationContext) } catch (e: Exception) { emptyList() }
                    handler.post { if (!dismissed) { device.forEach { library.putIfAbsent(it.key, it) }; rebuild() } }
                }
            } catch (_: Exception) {
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { online = emptyList(); rebuild() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        // Enter on the keyboard also searches YouTube, for a song that is not in the lists yet.
        search.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            val q = search.text.toString().trim()
            if (q.length >= 2) {
                try {
                    io.execute {
                        val res = try { YouTubeApi.search(applicationContext, q, category = null, longOnly = false) } catch (e: Exception) { emptyList() }
                        handler.post { if (!dismissed && search.text.toString().trim() == q) { online = res; rebuild() } }
                    }
                } catch (_: Exception) {
                }
            }
            true
        }

        val dialog = AlertDialog.Builder(ctx).setTitle(R.string.alarm_picker_title).setView(box)
            .setNegativeButton(android.R.string.cancel, null).create()
        listView.setOnItemClickListener { _, _, pos, _ ->
            val e = shown.getOrNull(pos) ?: return@setOnItemClickListener
            if (e.track == null) WakeAlarm.setRandomLiked(ctx) else WakeAlarm.setTrack(ctx, e.track)
            dialog.dismiss()
            refresh()
        }
        dialog.show()
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
