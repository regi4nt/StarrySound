# Track.kt (kept outside the zip) — one edit for SD card / OTG support

Replace the `uri` getter in `Track`:

    /** Only meaningful for local tracks. A song of a picked OTG / SD folder keeps its document uri in remoteId. */
    val uri: Uri get() =
        if (source == SOURCE_LOCAL && remoteId.isNotEmpty()) Uri.parse(remoteId)
        else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

Nothing else changes in Track.kt (no new constant, no new field).
