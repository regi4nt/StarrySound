package com.fxsoundlite.app

/** A video file on the device (Device tab in video mode): [Track.remoteId] is its content:// uri. Never goes into the audio queue. */
val Track.Companion.SOURCE_VIDEO: Int get() = 101
