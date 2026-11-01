package com.fxsoundlite.app

/**
 * Source id for songs of the Online tab (Internet Archive, Audius, Jamendo, ccMixter):
 * [Track.remoteId] is a plain https audio link. Declared here so Track.kt does not have to change.
 * If you prefer, move it into Track's companion object (next to SOURCE_RADIO) and delete this file:
 * a member of the companion always wins over this extension, so both can exist without a clash.
 * The number only has to differ from the other SOURCE_* values.
 */
val Track.Companion.SOURCE_ONLINE: Int get() = 100

/** Songs found with the Spotify tab: [Track.remoteId] is the Spotify uri (spotify:track:...). They play in the Spotify app, controlled from the StarrySound player (see SpotifyEngine). */
val Track.Companion.SOURCE_SPOTIFY: Int get() = 104
