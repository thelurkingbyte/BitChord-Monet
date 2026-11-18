package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The home-screen lyrics widgets: a window of lyric lines with the current one
 * highlighted. Two providers, [LyricsWidget5] and [LyricsWidget7], differing
 * only in how many lines they show.
 *
 * There is deliberately no title or artist here — the app's media widgets
 * already carry those, and stacking them on the lyrics widget wasted the room
 * the lines want.
 *
 * Fed by [com.music.bitchord.playback.PlaybackService], which already works out
 * the current line for the media notification's subtitle. The service pushes
 * only when the line (or the track) changes, so the widgets are not redrawn on
 * every tick of the lyric clock.
 */
abstract class LyricsWidget : AppWidgetProvider() {

    /** How many lines this provider draws. */
    protected abstract val lineCount: Int

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, lineCount)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraws every instance of both lyrics providers from the last snapshot. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                refreshProvider(app, LyricsWidget5::class.java, 5)
                refreshProvider(app, LyricsWidget7::class.java, 7)
            }
        }

        private fun refreshProvider(
            context: Context,
            provider: Class<out AppWidgetProvider>,
            lineCount: Int,
        ) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, provider))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            render(context, manager, ids, lineCount)
        }

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            lineCount: Int,
        ) {
            val snapshot = LyricsWidgetSnapshot.load(context)
            val layout = if (lineCount == 5) R.layout.widget_lyrics_5 else R.layout.widget_lyrics_7
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val lines = snapshot.lines
            val current = snapshot.currentIndex
            // The snapshot is a seven-line window. The seven-line widget shows
            // all of it; the five-line one takes a five-line slice centred on
            // the current line (clamped at the song's ends).
            val start = when {
                lineCount >= 7 || lines.size <= lineCount -> 0
                else -> (current - lineCount / 2).coerceIn(0, lines.size - lineCount)
            }
            for (id in ids) {
                val views = RemoteViews(context.packageName, layout)
                for (i in 0 until lineCount) {
                    val viewId = LINE_IDS[i]
                    val lineIndex = start + i
                    if (lineIndex in lines.indices) {
                        val isCurrent = lineIndex == current
                        views.setTextViewText(viewId, lines[lineIndex])
                        views.setViewVisibility(viewId, View.VISIBLE)
                        views.setInt(viewId, "setTextColor", if (isCurrent) CURRENT_COLOR else OTHER_COLOR)
                        views.setFloat(viewId, "setTextSize", if (isCurrent) CURRENT_SP else OTHER_SP)
                    } else {
                        views.setViewVisibility(viewId, View.GONE)
                    }
                }
                views.setOnClickPendingIntent(R.id.lyrics_root, open)
                manager.updateAppWidget(id, views)
            }
        }

        private val LINE_IDS = intArrayOf(
            R.id.lyrics_line_1,
            R.id.lyrics_line_2,
            R.id.lyrics_line_3,
            R.id.lyrics_line_4,
            R.id.lyrics_line_5,
            R.id.lyrics_line_6,
            R.id.lyrics_line_7,
        )

        private const val CURRENT_COLOR = 0xFFFFFFFF.toInt()
        private const val OTHER_COLOR = 0x99FFFFFF.toInt()
        private const val CURRENT_SP = 18f
        private const val OTHER_SP = 14f
    }
}

/** Five lyric lines. */
class LyricsWidget5 : LyricsWidget() {
    override val lineCount = 5
}

/** Seven lyric lines. */
class LyricsWidget7 : LyricsWidget() {
    override val lineCount = 7
}