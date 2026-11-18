package com.music.bitchord.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
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
 * properly blurred and darkened copy of itself filling the rest, with a soft
 * fade at the top and bottom. The whole composition is then set as the visible
 * crop, so the launcher shows it whole instead of cropping to one corner.
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
        // then properly blurred and darkened so the sharp sleeve reads on top.
        val coverScale = maxOf(width.toFloat() / art.width, height.toFloat() / art.height)
        val coverW = (art.width * coverScale).toInt().coerceAtLeast(width)
        val coverH = (art.height * coverScale).toInt().coerceAtLeast(height)
        val cover = Bitmap.createScaledBitmap(art, coverW, coverH, true)
        val cropLeft = (coverW - width) / 2
        val cropTop = (coverH - height) / 2
        val blurred = blur(Bitmap.createBitmap(cover, cropLeft, cropTop, width, height))
        canvas.drawBitmap(blurred, 0f, 0f, null)

        // Darken and fade the blurred field (darkest at the top and bottom,
        // clear behind the sleeve) so it reads as a backdrop, not a flat tint.
        canvas.drawColor(BLUR_DARKEN)
        val fade = Paint()
        fade.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0x66000000, 0x00000000, 0x00000000, 0x66000000),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade)

        // Sharp sleeve, scaled to the narrower screen dimension and centred:
        // it spans the width in portrait, the height in landscape.
        val artSize = minOf(width, height)
        val scaled = Bitmap.createScaledBitmap(art, artSize, artSize, true)
        val left = (width - artSize) / 2f
        val top = (height - artSize) / 2f
        canvas.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    /**
     * A real blur: down to a thumbnail, box-blur its pixels, then back up.
     * The down/up scaling alone only softens; the box blur is what actually
     * smears the sleeve into a backdrop.
     */
    private fun blur(src: Bitmap): Bitmap {
        val w = (src.width / 8).coerceAtLeast(1)
        val h = (src.height / 8).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val blurred = boxBlur(small, 6)
        return Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
    }

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)
        val r = radius.coerceAtLeast(1)
        // Horizontal pass.
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
                val from = maxOf(0, x - r)
                val to = minOf(w - 1, x + r)
                for (xx in from..to) {
                    val p = pixels[row + xx]
                    a += (p ushr 24) and 0xFF
                    rr += (p ushr 16) and 0xFF
                    gg += (p ushr 8) and 0xFF
                    bb += p and 0xFF
                    n++
                }
                temp[row + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
            }
        }
        // Vertical pass.
        val out = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
                val from = maxOf(0, y - r)
                val to = minOf(h - 1, y + r)
                for (yy in from..to) {
                    val p = temp[yy * w + x]
                    a += (p ushr 24) and 0xFF
                    rr += (p ushr 16) and 0xFF
                    gg += (p ushr 8) and 0xFF
                    bb += p and 0xFF
                    n++
                }
                out[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
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
    private val BLUR_DARKEN = 0x33000000
}