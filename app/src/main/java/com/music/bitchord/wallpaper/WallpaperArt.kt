package com.music.bitchord.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Sets a track's album artwork as the system wallpaper.
 *
 * The artwork is fetched through the app's existing Coil loader at
 * [PLAYER_ART_PX] — the same rung the full player and the home-screen widget
 * already use — so it shares a cache entry instead of pulling a second copy.
 *
 * Rather than handing the launcher a bare square to crop (which is what made it
 * show only the left/top of the sleeve), this bakes a screen-sized wallpaper:
 * the sleeve scaled to the *narrower* screen dimension and centred, over a
 * blurred and darkened copy of itself filling the rest. The whole composition
 * is then set as the visible crop, so the launcher shows it whole instead of
 * cropping to one corner.
 */
object WallpaperArt {

    /** Applies [artworkUrl] to the home and lock screen. True if either took. */
    suspend fun apply(context: Context, artworkUrl: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (artworkUrl.isNullOrBlank()) return@withContext false
            val art = load(context, artworkUrl) ?: return@withContext false
            val wallpaper = compose(context, art)
            val manager = WallpaperManager.getInstance(context)
            val cropHint = Rect(0, 0, wallpaper.width, wallpaper.height)
            var system = false
            var lock = false
            runCatching {
                manager.setBitmap(wallpaper, cropHint, true, WallpaperManager.FLAG_SYSTEM)
                system = true
            }
            runCatching {
                manager.setBitmap(wallpaper, cropHint, true, WallpaperManager.FLAG_LOCK)
                lock = true
            }
            system || lock
        }

    private fun compose(context: Context, art: Bitmap): Bitmap {
        val (width, height) = screenSize(context)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Blurred cover fill: the sleeve scaled to cover the whole surface,
        // then softened and darkened so the sharp sleeve reads on top.
        val coverScale = maxOf(width.toFloat() / art.width, height.toFloat() / art.height)
        val coverW = (art.width * coverScale).toInt().coerceAtLeast(width)
        val coverH = (art.height * coverScale).toInt().coerceAtLeast(height)
        val cover = Bitmap.createScaledBitmap(art, coverW, coverH, true)
        val cropLeft = (coverW - width) / 2
        val cropTop = (coverH - height) / 2
        val blurred = blur(Bitmap.createBitmap(cover, cropLeft, cropTop, width, height))
        canvas.drawBitmap(blurred, 0f, 0f, null)
        canvas.drawColor(BLUR_DARKEN)

        // Sharp sleeve, scaled to the narrower screen dimension and centred:
        // it spans the width in portrait, the height in landscape.
        val artSize = minOf(width, height)
        val scaled = Bitmap.createScaledBitmap(art, artSize, artSize, true)
        val left = (width - artSize) / 2f
        val top = (height - artSize) / 2f
        canvas.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    /** Cheap blur: down to a thumbnail and back up with filtering. */
    private fun blur(src: Bitmap): Bitmap {
        val w = (src.width / 12).coerceAtLeast(1)
        val h = (src.height / 12).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private suspend fun load(context: Context, artworkUrl: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(artworkUrl.artworkAt(PLAYER_ART_PX) ?: artworkUrl)
            .size(PLAYER_ART_PX)
            .allowHardware(false)
            .build()
        val result = runCatching {
            SingletonImageLoader.get(context).execute(request)
        }.getOrNull()
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    /** Darkening laid over the blurred field so the sharp sleeve pops. */
    private val BLUR_DARKEN = 0x40000000
}