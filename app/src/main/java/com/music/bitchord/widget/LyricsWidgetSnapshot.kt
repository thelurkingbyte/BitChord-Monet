package com.music.bitchord.widget

import android.content.Context

/**
 * The last lyric state pushed to the home-screen lyrics widget.
 *
 * Persisted for the same reason [MediaWidgetSnapshot] is: a widget outlives the
 * playback service, so its first update after a reboot — or a launcher redraw
 * with the app's process dead — has only this file to draw from.
 */
internal data class LyricsWidgetSnapshot(
    val mediaId: String?,
    val title: String,
    val artist: String,
    val previous: String?,
    val current: String?,
    val next: String?,
) {
    val hasTrack: Boolean get() = mediaId != null

    companion object {
        val EMPTY = LyricsWidgetSnapshot(null, "", "", null, null, null)

        fun save(context: Context, snapshot: LyricsWidgetSnapshot) {
            prefs(context).edit()
                .putString(KEY_MEDIA_ID, snapshot.mediaId)
                .putString(KEY_TITLE, snapshot.title)
                .putString(KEY_ARTIST, snapshot.artist)
                .putString(KEY_PREVIOUS, snapshot.previous)
                .putString(KEY_CURRENT, snapshot.current)
                .putString(KEY_NEXT, snapshot.next)
                .apply()
        }

        fun load(context: Context): LyricsWidgetSnapshot {
            val prefs = prefs(context)
            val mediaId = prefs.getString(KEY_MEDIA_ID, null) ?: return EMPTY
            return LyricsWidgetSnapshot(
                mediaId = mediaId,
                title = prefs.getString(KEY_TITLE, "").orEmpty(),
                artist = prefs.getString(KEY_ARTIST, "").orEmpty(),
                previous = prefs.getString(KEY_PREVIOUS, null),
                current = prefs.getString(KEY_CURRENT, null),
                next = prefs.getString(KEY_NEXT, null),
            )
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences("bitchord_lyrics_widget", Context.MODE_PRIVATE)

        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_PREVIOUS = "previous"
        private const val KEY_CURRENT = "current"
        private const val KEY_NEXT = "next"
    }
}