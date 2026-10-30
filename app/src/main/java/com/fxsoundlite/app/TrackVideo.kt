package com.fxsoundlite.app

/** A video file on the device (Device tab in video mode): [Track.remoteId] is its content:// uri. Never goes into the audio queue. */
val Track.Companion.SOURCE_VIDEO: Int get() = 101

/** A video file in Google Drive (video mode): [Track.remoteId] is the Drive file id. Never goes into the audio queue. */
val Track.Companion.SOURCE_DRIVE_VIDEO: Int get() = 102

/** A live TV channel of the TV tab (video mode): [Track.remoteId] is the stream URL (HLS). Never goes into the audio queue. */
val Track.Companion.SOURCE_TV: Int get() = 103

/** True when the video player can show this track: YouTube videos, device videos and Drive videos. */
val Track.isVideoPlayable: Boolean
    get() = (source == Track.SOURCE_YOUTUBE && remoteId.isNotBlank() && !YouTubeApi.isListRow(this)) ||
        source == Track.SOURCE_VIDEO || source == Track.SOURCE_DRIVE_VIDEO ||
        (source == Track.SOURCE_TV && remoteId.isNotBlank()) ||
        (PlayerMode.isTvAudio(this) && remoteId.isNotBlank()) // a TV channel saved as audio (playlist, mini bar): its video is the live channel
