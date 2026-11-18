package com.music.bitchord.widget

import android.content.Context

/**
 * The last lyric window pushed to the home-screen lyrics widgets.
 *
 * [lines] is a small window of lines around the current one (up to seven), and
 * [currentIndex] is the index of the current line within that window. Both the
 * five-line and seven-line widgets render from this same snapshot; the five-line
 * one takes a five-line slice centred on [currentIndex].
 *
 * Persisted for the same reason [MediaWidgetSnapshot] is: a widget outlives the
 * playback service, so its first update after a reboot has only this file to
 * draw from.
 */
internal data class LyricsWidgetSnapshot(
    val mediaId: String?,
    val lines: List<String>,
    val currentIndex: Int,
) {
    val hasTrack: Boolean get() = mediaId != null

    companion object {
        val EMPTY = LyricsWidgetSnapshot(null, emptyList(), -1)

        fun save(context: Context, snapshot: LyricsWidgetSnapshot) {
            prefs(context).edit()
                .putString(KEY_MEDIA_ID, snapshot.mediaId)
                .putString(KEY_LINES, snapshot.lines.joinToString("\n"))
                .putInt(KEY_CURRENT, snapshot.currentIndex)
                .apply()
        }

        fun load(context: Context): LyricsWidgetSnapshot {
            val prefs = prefs(context)
            val mediaId = prefs.getString(KEY_MEDIA_ID, null) ?: return EMPTY
            val raw = prefs.getString(KEY_LINES, "").orEmpty()
            val lines = if (raw.isEmpty()) emptyList() else raw.split("\n")
            return LyricsWidgetSnapshot(
                mediaId = mediaId,
                lines = lines,
                currentIndex = prefs.getInt(KEY_CURRENT, -1),
            )
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences("bitchord_lyrics_widget", Context.MODE_PRIVATE)

        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_LINES = "lines"
        private const val KEY_CURRENT = "current_index"
    }
}