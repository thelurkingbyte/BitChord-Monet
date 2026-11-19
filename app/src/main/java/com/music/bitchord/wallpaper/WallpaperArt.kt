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
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Sets a track's album artwork as the system wallpaper.
 *
 * Three strategies (see [AppSettings.wallpaperStrategy]):
 *   A — full art as wallpaper (reliable fallback).
 *   B — instant in-app palette (app theme only; system still wallpaper-derived).
 *   C — tiny dominant-colour seed first, then full art (fastest system re-derive; default).
 *
 * Two styles (see [AppSettings.wallpaperStyle]):
 *   0 — sharp sleeve on a blurred fill, centred on the narrower axis (current).
 *   1 — uniform blur over the whole cover, centred (the middle of the image shows).
 *
 * The blur radius (see [AppSettings.blurRadius], 0–50, default 10) applies to both.
 *
 * [snapshotOriginal] / [restoreOriginal] back the "restore wallpaper on pause" feature:
 * the current wallpaper is saved once (where readable) and re-applied on pause/ended.
 */
object WallpaperArt {

    /** Applies [artworkUrl] to the home and lock screen. True if either took. */
    suspend fun apply(context: Context, artworkUrl: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (artworkUrl.isNullOrBlank()) return@withContext false
            val art = load(context, artworkUrl) ?: return@withContext false
            val manager = WallpaperManager.getInstance(context)
            val strategy = AppSettings.wallpaperStrategy.value
            val style = AppSettings.wallpaperStyle.value
            val blurRadius = AppSettings.blurRadius.value

            // Strategy B is app-level only; the app theme is already artwork-driven,
            // so just re-publish the palette by returning success (system colors follow
            // the wallpaper set by A/C if autoWallpaper is on). Still set the wallpaper
            // so the system has something to derive from.
            if (strategy == 0) {
                // A: full art, with optional verification/retry.
                return@withContext setWithVerify(context, manager, compose(context, art, style, blurRadius))
            }

            if (strategy == 2) {
                // C: seed the dominant colour first, then the full art. The seed
                // guarantees Android sees a change and re-derives Monet immediately.
                runCatching {
                    val seed = seedBitmap(art)
                    manager.setBitmap(seed, Rect(0, 0, seed.width, seed.height), true, WallpaperManager.FLAG_SYSTEM)
                    manager.setBitmap(seed, Rect(0, 0, seed.width, seed.height), true, WallpaperManager.FLAG_LOCK)
                }
            }

            // A/C both end with the full art.
            setWithVerify(context, manager, compose(context, art, style, blurRadius))
        }

    /** Sets the wallpaper with a short verification: re-set once if colors didn't change (API 27+). */
    private fun setWithVerify(context: Context, manager: WallpaperManager, wallpaper: Bitmap): Boolean {
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
        if (!system && !lock) return false

        // Strategy A can no-op if the bitmap is considered unchanged; re-set once
        // after a beat so the system re-derives. (Best-effort; not all OEMs expose
        // getWallpaperColors, so this is a cheap nudge rather than a guarantee.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                val before = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                val handler = android.os.Handler(context.mainLooper)
                handler.postDelayed({
                    runCatching {
                        val after = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                        if (before == null || after == null) {
                            manager.setBitmap(wallpaper, cropHint, true, WallpaperManager.FLAG_SYSTEM)
                        }
                    }
                }, 400L)
            }
        }
        return system || lock
    }

    private fun compose(context: Context, art: Bitmap, style: Int, blurRadius: Int): Bitmap {
        val (width, height) = screenSize(context)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        if (style == 1) {
            // Mode 2: uniform blur over the whole cover, centred (middle of image shows).
            val coverScale = maxOf(width.toFloat() / art.width, height.toFloat() / art.height)
            val coverW = (art.width * coverScale).toInt().coerceAtLeast(width)
            val coverH = (art.height * coverScale).toInt().coerceAtLeast(height)
            val cover = Bitmap.createScaledBitmap(art, coverW, coverH, true)
            val cropLeft = (coverW - width) / 2
            val cropTop = (coverH - height) / 2
            val blurred = blur(Bitmap.createBitmap(cover, cropLeft, cropTop, width, height), blurRadius)
            canvas.drawBitmap(blurred, 0f, 0f, null)
            return output
        }

        // Mode 1 (current): blurred fill + sharp sleeve centred on the narrower axis.
        val coverScale = maxOf(width.toFloat() / art.width, height.toFloat() / art.height)
        val coverW = (art.width * coverScale).toInt().coerceAtLeast(width)
        val coverH = (art.height * coverScale).toInt().coerceAtLeast(height)
        val cover = Bitmap.createScaledBitmap(art, coverW, coverH, true)
        val cropLeft = (coverW - width) / 2
        val cropTop = (coverH - height) / 2
        val blurred = blur(Bitmap.createBitmap(cover, cropLeft, cropTop, width, height), blurRadius)
        canvas.drawBitmap(blurred, 0f, 0f, null)
        canvas.drawColor(BLUR_DARKEN)
        val fade = Paint()
        fade.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0x66000000, 0x00000000, 0x00000000, 0x66000000),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade)

        val artSize = minOf(width, height)
        val scaled = Bitmap.createScaledBitmap(art, artSize, artSize, true)
        val left = (width - artSize) / 2f
        val top = (height - artSize) / 2f
        canvas.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    /** A 2×2 bitmap of the artwork's dominant colour, for the strategy-C seed. */
    private fun seedBitmap(art: Bitmap): Bitmap {
        val palette = Palette.from(art).clearFilters().generate()
        val rgb = palette.getDominantColor(0xFF141619.toInt())
        val seed = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        seed.eraseColor(rgb)
        return seed
    }

    private fun blur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val w = (src.width / 8).coerceAtLeast(1)
        val h = (src.height / 8).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val blurred = boxBlur(small, radius.coerceAtMost(24))
        return Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
    }

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)
        val r = radius.coerceAtLeast(1)
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
        val out = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
                val from = maxOf(0, y - r)
                val to = minOf(h - 1, y + r)
                for (yy in from..to) {
                    val p = temp[yy * w + x]
                    a += (p ushr 24) and 0xFF
                    rr += (p usr 16) and 0xFF
                    gg += (p usr 8) and 0xFF
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

    /**
     * Saves the current wallpaper once (where readable) so it can be restored on
     * pause/ended. Best-effort: on OEMs that block [WallpaperManager.getBitmap]
     * this is a no-op and [restoreOriginal] falls back to a neutral default.
     */
    fun snapshotOriginal(context: Context): Boolean = runCatching {
        val manager = WallpaperManager.getInstance(context)
        val bmp = manager.getBitmap() ?: return false
        val file = java.io.File(context.filesDir, "original_wallpaper.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    /** Re-applies the saved original wallpaper (or a neutral default if none was saved). */
    fun restoreOriginal(context: Context) = runCatching {
        val manager = WallpaperManager.getInstance(context)
        val file = java.io.File(context.filesDir, "original_wallpaper.png")
        val bmp = if (file.exists()) {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        } ?: neutralWallpaper(context)
        val cropHint = Rect(0, 0, bmp.width, bmp.height)
        manager.setBitmap(bmp, cropHint, true, WallpaperManager.FLAG_SYSTEM)
        manager.setBitmap(bmp, cropHint, true, WallpaperManager.FLAG_LOCK)
    }.getOrDefault(false)

    private fun neutralWallpaper(context: Context): Bitmap {
        val (width, height) = screenSize(context)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xFF141619.toInt(), 0xFF0B0B0D.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bmp
    }

    private val BLUR_DARKEN = 0x33000000
}