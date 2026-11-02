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
 * Wake-up alarm settings (the "Wake-up alarm" tab of [BedtimeActivity]): time, repeat days, the song that wakes you
 * and the snooze length. The alarm itself is scheduled by [WakeAlarm] and rings in [WakeAlarmActivity].
 * Built on [root] (view_alarm_panel.xml) inside [act]; create it in the activity's onCreate (it registers result launchers).
 */
class AlarmPanel(private val act: AppCompatActivity, private val root: View) {

    private val ctx: Context get() = act
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var dismissed = false

    private val timeView by lazy { root.findViewById<TextView>(R.id.alarmTime) }
    private val statusView by lazy { root.findViewById<TextView>(R.id.alarmStatus) }
    private val switchView by lazy { root.findViewById<SwitchCompat>(R.id.alarmSwitch) }
    private val daysBox by lazy { root.findViewById<LinearLayout>(R.id.alarmDays) }
    private val repeatHint by lazy { root.findViewById<TextView>(R.id.alarmRepeatHint) }
    private val songTitle by lazy { root.findViewById<TextView>(R.id.alarmSongTitle) }
    private val songArtist by lazy { root.findViewById<TextView>(R.id.alarmSongArtist) }
    private val modeBox by lazy { root.findViewById<LinearLayout>(R.id.alarmModeChips) }
    private val snoozeBox by lazy { root.findViewById<LinearLayout>(R.id.alarmSnoozeChips) }

    private val notifPermission =
        act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the alarm still rings its song without it */ }

    // System picker of the device's alarm tones.
    private val toneLauncher =
        act.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            val data = res.data ?: return@registerForActivityResult
            WakeAlarm.setToneUri(ctx, pickedUri(data)) // null = the system default alarm sound
            refresh()
        }

    init {
        root.findViewById<View>(R.id.alarmTimeBox).setOnClickListener { pickTime() }
        switchView.setOnCheckedChangeListener { _, on -> onToggle(on) }
        root.findViewById<View>(R.id.alarmSongCard).setOnClickListener {
            if (WakeAlarm.soundMode(ctx) == WakeAlarm.MODE_TONE) pickTone() else pickSong()
        }
        root.findViewById<View>(R.id.alarmTest).setOnClickListener {
            act.startActivity(Intent(ctx, WakeAlarmActivity::class.java))
        }
        buildDayChips()
        buildModeChips()
        buildSnoozeChips()
        refresh()
    }

    fun destroy() {
        dismissed = true
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
    }

    // ------------------------------------------------------------------ state

    fun refresh() {
        timeView.text = formatTime(WakeAlarm.hour(ctx), WakeAlarm.minute(ctx))
        val on = WakeAlarm.isEnabled(ctx)
        if (switchView.isChecked != on) {
            // Setting it from code must not re-run onToggle.
            switchView.setOnCheckedChangeListener(null)
            switchView.isChecked = on
            switchView.setOnCheckedChangeListener { _, v -> onToggle(v) }
        }
        statusView.text = if (on) act.getString(R.string.alarm_next, untilText(WakeAlarm.nextTrigger(ctx)))
        else act.getString(R.string.alarm_off_status)

        val days = WakeAlarm.days(ctx)
        for (i in 0 until daysBox.childCount) daysBox.getChildAt(i).isSelected = days and (1 shl i) != 0
        repeatHint.text = act.getString(if (days == 0) R.string.alarm_repeat_once else R.string.alarm_repeat_days)

        val toneMode = WakeAlarm.soundMode(ctx) == WakeAlarm.MODE_TONE
        for (i in 0 until modeBox.childCount) modeBox.getChildAt(i).isSelected = (i == WakeAlarm.MODE_TONE) == toneMode
        val track = WakeAlarm.track(ctx)
        if (toneMode) {
            songTitle.text = WakeAlarm.toneTitle(ctx).ifBlank { act.getString(R.string.alarm_mode_tone) }
            songArtist.text = act.getString(R.string.alarm_mode_tone)
        } else if (WakeAlarm.randomLiked(ctx) || track == null) {
            songTitle.text = act.getString(R.string.alarm_song_random)
            songArtist.text = act.getString(R.string.stats_liked)
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
        val base = if (h > 0) act.getString(R.string.stats_fmt_hour_min, h, m) else act.getString(R.string.stats_fmt_min, m)
        return if (d > 0) act.getString(R.string.alarm_days_prefix, d) + " " + base else base
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
            val nm = act.getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                Toast.makeText(ctx, R.string.alarm_need_fullscreen, Toast.LENGTH_LONG).show()
                try {
                    act.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${act.packageName}"))
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
        val gap = (8 * act.resources.displayMetrics.density).toInt()
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
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, act.getString(R.string.alarm_tone_picker_title))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, WakeAlarm.effectiveToneUri(ctx))
        try { toneLauncher.launch(i) } catch (e: Exception) { android.util.Log.d("AlarmActivity", "ignored: " + e) }
    }

    private fun buildSnoozeChips() {
        snoozeBox.removeAllViews()
        for (m in WakeAlarm.SNOOZE_CHOICES) {
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, snoozeBox, false) as TextView
            chip.text = act.getString(R.string.alarm_snooze_min, m)
            chip.setOnClickListener { WakeAlarm.setSnoozeMin(ctx, m); refresh() }
            snoozeBox.addView(chip)
        }
    }

    // ------------------------------------------------------------------ song picker

    private class Entry(val label: String, val sub: String, val track: Track?) // track == null: random from Liked songs

    private fun pickSong() {
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val search = EditText(ctx).apply {
            setHint(R.string.alarm_pick_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val listView = ListView(ctx)
        box.addView(search)
        box.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * act.resources.displayMetrics.density).toInt()))

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
                v.root.findViewById<TextView>(android.R.id.text1).apply { text = e.label; setTextColor(themeColor(R.attr.colorTextPrimary)) }
                v.root.findViewById<TextView>(android.R.id.text2).apply { text = e.sub; setTextColor(themeColor(R.attr.colorTextSecondary)) }
                return v
            }
        }
        listView.adapter = adapter

        fun rebuild() {
            val q = search.text.toString().trim().lowercase(Locale.getDefault())
            val list = ArrayList<Entry>()
            if (q.isEmpty()) list += Entry(act.getString(R.string.alarm_song_random), act.getString(R.string.stats_liked), null)
            val local = library.values.filter { q.isEmpty() || (it.title + " " + it.artist).lowercase(Locale.getDefault()).contains(q) }
            (local.take(200) + online.filter { o -> local.none { it.key == o.key } })
                .forEach { list += Entry(it.title, it.artist, it) }
            shown = list
            adapter.clear(); adapter.addAll(list); adapter.notifyDataSetChanged()
        }
        rebuild()

        if (MusicLibrary.canRead(ctx)) {
            try {
                io.execute {
                    val device = try { MusicLibrary.load(act.applicationContext) } catch (e: Exception) { emptyList() }
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
                        val res = try { YouTubeApi.search(act.applicationContext, q, category = null, longOnly = false) } catch (e: Exception) { emptyList() }
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
        act.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
