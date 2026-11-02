package com.fxsoundlite.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Old entry point of the wake-up alarm settings. They now live in the "Wake-up alarm" tab of [BedtimeActivity]
 * (sleep sounds and wake-up alarm are one screen); this only forwards, so the alarm-clock "show" intent that
 * [WakeAlarm] registered with the system keeps working.
 */
class AlarmActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, BedtimeActivity::class.java).putExtra(BedtimeActivity.EXTRA_TAB, BedtimeActivity.TAB_WAKE))
        finish()
    }
}
