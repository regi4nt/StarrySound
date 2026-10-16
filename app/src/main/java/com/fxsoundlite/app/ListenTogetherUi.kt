package com.fxsoundlite.app

import android.app.Activity
import android.content.Intent

/**
 * Entry point of "Listen together": opens the full-screen, Discord-style room screen
 * ([ListenTogetherActivity]). [joinCode] (from an invite link) pre-fills and auto-joins that room.
 */
object ListenTogetherUi {

    fun show(activity: Activity, joinCode: String? = null) {
        val i = Intent(activity, ListenTogetherActivity::class.java)
        if (!joinCode.isNullOrBlank()) i.putExtra(ListenTogetherActivity.EXTRA_JOIN, joinCode)
        activity.startActivity(i)
        activity.overridePendingTransition(R.anim.slide_up, R.anim.stay)
    }
}
