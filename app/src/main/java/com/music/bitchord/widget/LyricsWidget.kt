package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * The home-screen lyrics widgets: a window of lyric lines with the current one
 * highlighted, and a TTML-style karaoke sweep drawn into a bitmap for the
 * current line (RemoteViews strips spans, so the sung portion is painted in
 * accent colour and the rest dimmed).
 *
 * Two providers, [LyricsWidget5] and [LyricsWidget7], differing only in how
 * many lines they show.
 *
 * There is deliberately no title or artist here — the app's media widgets
 * already carry those, and stacking them on the lyrics widget wasted the room
 * the lines want.
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
            val start = when {
                lineCount >= 7 || lines.size <= lineCount -> 0
                else -> (current - lineCount / 2).coerceIn(0, lines.size - lineCount)
            }
            val currentSp = if (lineCount == 7) CURRENT_SP_7 else CURRENT_SP_5
            val otherSp = if (lineCount == 7) OTHER_SP_7 else OTHER_SP_5
            val density = context.resources.displayMetrics.density
            for (id in ids) {
                val views = RemoteViews(context.packageName, layout)
                for (i in 0 until lineCount) {
                    val viewId = LINE_IDS[i]
                    val imgId = LINE_IMG_IDS[i]
                    val lineIndex = start + i
                    if (lineIndex in lines.indices) {
                        val isCurrent = lineIndex == current
                        if (isCurrent) {
                            // Karaoke sweep: render the current line as a bitmap.
                            val bmp = renderKaraokeLine(
                                text = lines[lineIndex],
                                progress = snapshot.currentProgress,
                                textSizeSp = currentSp,
                                density = density,
                            )
                            if (bmp != null) {
                                views.setImageViewBitmap(imgId, bmp)
                                views.setViewVisibility(imgId, View.VISIBLE)
                                views.setViewVisibility(viewId, View.GONE)
                            } else {
                                // Fallback: plain highlighted text.
                                views.setTextViewText(viewId, lines[lineIndex])
                                views.setViewVisibility(viewId, View.VISIBLE)
                                views.setInt(viewId, "setTextColor", CURRENT_COLOR)
                                views.setFloat(viewId, "setTextSize", currentSp)
                                views.setViewVisibility(imgId, View.GONE)
                            }
                        } else {
                            views.setTextViewText(viewId, lines[lineIndex])
                            views.setViewVisibility(viewId, View.VISIBLE)
                            views.setInt(viewId, "setTextColor", OTHER_COLOR)
                            views.setFloat(viewId, "setTextSize", otherSp)
                            views.setViewVisibility(imgId, View.GONE)
                        }
                    } else {
                        views.setViewVisibility(viewId, View.GONE)
                        views.setViewVisibility(imgId, View.GONE)
                    }
                }
                views.setOnClickPendingIntent(R.id.lyrics_root, open)
                manager.updateAppWidget(id, views)
            }
        }

        /**
         * Renders one lyric line into a bitmap with the sung portion in the
         * accent colour and the rest dimmed — the TTML karaoke sweep. Returns
         * null if the text is empty so the caller can fall back to a TextView.
         */
        private fun renderKaraokeLine(
            text: String,
            progress: Float,
            textSizeSp: Float,
            density: Float,
        ): Bitmap? {
            if (text.isBlank()) return null
            val textSizePx = textSizeSp * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = textSizePx
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val fullWidth = paint.measureText(text)
            val widthPx = ceil(fullWidth).toInt().coerceAtLeast(1) + 4
            val heightPx = (textSizePx * 1.4).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // Sung portion in accent (white), rest dimmed.
            val sungChars = (text.length * progress.coerceIn(0f, 1f)).toInt().coerceIn(0, text.length)
            val sung = text.substring(0, sungChars)
            val rest = text.substring(sungChars)
            var x = 2f
            val baseline = heightPx - textSizePx * 0.3f
            if (sung.isNotEmpty()) {
                paint.color = CURRENT_COLOR
                canvas.drawText(sung, x, baseline, paint)
                x += paint.measureText(sung)
            }
            if (rest.isNotEmpty()) {
                paint.color = OTHER_COLOR
                canvas.drawText(rest, x, baseline, paint)
            }
            return bmp
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

        private val LINE_IMG_IDS = intArrayOf(
            R.id.lyrics_line_1_img,
            R.id.lyrics_line_2_img,
            R.id.lyrics_line_3_img,
            R.id.lyrics_line_4_img,
            R.id.lyrics_line_5_img,
            R.id.lyrics_line_6_img,
            R.id.lyrics_line_7_img,
        )

        private const val CURRENT_COLOR = Color.WHITE
        private const val OTHER_COLOR = 0x99FFFFFF.toInt()
        private const val CURRENT_SP_5 = 18f
        private const val OTHER_SP_5 = 14f
        // The seven-line widget is 1.25× the five-line one, active and inactive.
        private const val CURRENT_SP_7 = 22.5f
        private const val OTHER_SP_7 = 17.5f
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