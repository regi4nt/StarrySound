# Track.kt (not in the zip) — add one constant

In `Track`'s companion object, next to SOURCE_LOCAL / SOURCE_YOUTUBE / SOURCE_RADIO / SOURCE_DRIVE:

    const val SOURCE_ONLINE = <next unused number>   // Online-tab song (Internet Archive / Audius / Jamendo / ccMixter / Openverse): remoteId = https audio link

`Track.key` must stay unique per song (for Online songs `remoteId` is the audio URL, so source + remoteId works).
