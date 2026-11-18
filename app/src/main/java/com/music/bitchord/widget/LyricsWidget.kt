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
 * The home-screen lyrics widget: the current line, with the lines either side
 * of it dimmed, under the track's title and artist.
 *
 * Fed by [com.music.bitchord.playback.PlaybackService], which already works out
 * the current line for the media notification's subtitle. The service pushes
 * only when the line (or the track) changes, so the widget is not redrawn on
 * every tick of the lyric clock — see `publishLyricsWidget` there.
 */
class LyricsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraws every instance of this widget from the last saved snapshot. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, LyricsWidget::class.java))
                }.getOrNull() ?: return@launch
                if (ids.isEmpty()) return@launch
                render(app, manager, ids)
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snapshot = LyricsWidgetSnapshot.load(context)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
                views.setTextViewText(R.id.lyrics_title, snapshot.title)
                views.setTextViewText(R.id.lyrics_artist, snapshot.artist)
                setLine(views, R.id.lyrics_previous, snapshot.previous)
                setLine(views, R.id.lyrics_current, snapshot.current)
                setLine(views, R.id.lyrics_next, snapshot.next)
                views.setOnClickPendingIntent(R.id.lyrics_root, open)
                manager.updateAppWidget(id, views)
            }
        }

        private fun setLine(views: RemoteViews, id: Int, text: String?) {
            views.setTextViewText(id, text.orEmpty())
            views.setViewVisibility(id, if (text.isNullOrBlank()) View.GONE else View.VISIBLE)
        }
    }
}