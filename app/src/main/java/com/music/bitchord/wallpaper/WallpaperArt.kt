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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Sets a track's album artwork as the system wallpaper.
 *
 * Three strategies (see [AppSettings.wallpaperStrategy]):
 *   A — full art as wallpaper (reliable fallback).
 *   B — dominant-colour seed first, delay ~350 ms, then full art (fast system re-derive; default).
 *   C — root method (best-effort `su`; falls back to B if unavailable).
 *
 * Two styles (see [AppSettings.wallpaperStyle]):
 *   0 — sharp sleeve on a blurred fill, centred on the narrower axis (current).
 *   1 — uniform blur over the whole cover, centred (the middle of the image shows).
 *
 * The blur radius (see [AppSettings.blurRadius], 0–10, default 2) applies to both.
 *
 * [snapshotOriginal] / [restoreOriginal] back the "restore wallpaper on pause" feature.
 */
object WallpaperArt {

    /** Single-thread dispatcher so wallpaper work can't starve playback. */
    private val wallpaperDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wallpaper").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + wallpaperDispatcher)

    /** Applies [artworkUrl] to the home and lock screen. True if either took. */
    suspend fun apply(context: Context, artworkUrl: String?): Boolean =
        withContext(wallpaperDispatcher) {
            if (artworkUrl.isNullOrBlank()) return@withContext false
            val art = load(context, artworkUrl) ?: return@withContext false
            val manager = WallpaperManager.getInstance(context)
            val strategy = AppSettings.wallpaperStrategy.value
            val style = AppSettings.wallpaperStyle.value
            val blurRadius = AppSettings.blurRadius.value

            // Strategy C: root method (best-effort). Falls back to B if root unavailable.
            if (strategy == 2 && tryRootWallpaper(context, art)) {
                return@withContext true
            }

            // Strategy B (and C fallback): seed the dominant colour, wait for the
            // system to sample it, then set the full art.
            if (strategy == 1 || strategy == 2) {
                val seed = seedBitmap(art)
                runCatching {
                    manager.setBitmap(seed, Rect(0, 0, seed.width, seed.height), true, WallpaperManager.FLAG_SYSTEM)
                    manager.setBitmap(seed, Rect(0, 0, seed.width, seed.height), true, WallpaperManager.FLAG_LOCK)
                }
                // Let the wallpaper analyzer sample the seed before overwriting.
                delay(350L)
            }

            // A/B/C all end with the full art.
            setWithVerify(context, manager, compose(context, art, style, blurRadius))
        }

    /**
     * Best-effort root method: set the wallpaper via `su -c`. Returns true only
     * if the command succeeded. On non-rooted devices this fails and the caller
     * falls back to strategy B.
     */
    private fun tryRootWallpaper(context: Context, art: Bitmap): Boolean {
        return runCatching {
            val file = java.io.File(context.cacheDir, "wallpaper_root.png").apply {
                outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val cmd = "su -c 'cmd wallpaper /data/local/tmp/wallpaper_root.png 2>/dev/null || " +
                "wallpaper /data/local/tmp/wallpaper_root.png 2>/dev/null'"
            // Copy to world-readable tmp, then set as root.
            Runtime.getRuntime().exec("su -c 'cp ${file.absolutePath} /data/local/tmp/wallpaper_root.png && chmod 644 /data/local/tmp/wallpaper_root.png'").waitFor()
            val proc = Runtime.getRuntime().exec("su -c 'cmd wallpaper /data/local/tmp/wallpaper_root.png'")
            proc.waitFor()
            proc.exitValue() == 0
        }.getOrDefault(false)
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

        // Best-effort: if colors didn't change, re-set once after a beat.
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

    /** A 2×2 bitmap of the artwork's dominant colour, for the strategy-B seed. */
    private fun seedBitmap(art: Bitmap): Bitmap {
        val palette = Palette.from(art).clearFilters().generate()
        val rgb = palette.getDominantColor(0xFF141619.toInt())
        val seed = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        seed.eraseColor(rgb)
        return seed
    }

    /** O(n) two-pass sliding-window box blur. Radius 0–10; 0 returns src unchanged. */
    private fun blur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)
        val r = radius.coerceIn(1, 10)
        // Horizontal pass.
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            for (x in -r..r) {
                val xx = x.coerceIn(0, w - 1)
                val p = pixels[row + xx]
                a += (p ushr 24) and 0xFF; rr += (p ushr 16) and 0xFF
                gg += (p ushr 8) and 0xFF; bb += p and 0xFF
            }
            val win = 2 * r + 1
            for (x in 0 until w) {
                temp[row + x] = ((a / win) shl 24) or ((rr / win) shl 16) or ((gg / win) shl 8) or (bb / win)
                val out = (x - r).coerceIn(0, w - 1)
                val inn = (x + r + 1).coerceIn(0, w - 1)
                val po = pixels[row + out]; val pi = pixels[row + inn]
                a += (pi ushr 24) and 0xFF; rr += (pi ushr 16) and 0xFF; gg += (pi ushr 8) and 0xFF; bb += pi and 0xFF
                a -= (po ushr 24) and 0xFF; rr -= (po ushr 16) and 0xFF; gg -= (po ushr 8) and 0xFF; bb -= po and 0xFF
            }
        }
        // Vertical pass.
        val out = IntArray(w * h)
        for (x in 0 until w) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            for (y in -r..r) {
                val yy = y.coerceIn(0, h - 1)
                val p = temp[yy * w + x]
                a += (p ushr 24) and 0xFF; rr += (p ushr 16) and 0xFF
                gg += (p ushr 8) and 0xFF; bb += p and 0xFF
            }
            val win = 2 * r + 1
            for (y in 0 until h) {
                out[y * w + x] = ((a / win) shl 24) or ((rr / win) shl 16) or ((gg / win) shl 8) or (bb / win)
                val outI = (y - r).coerceIn(0, h - 1)
                val inI = (y + r + 1).coerceIn(0, h - 1)
                val po = temp[outI * w + x]; val pi = temp[inI * w + x]
                a += (pi ushr 24) and 0xFF; rr += (pi ushr 16) and 0xFF; gg += (pi ushr 8) and 0xFF; bb += pi and 0xFF
                a -= (po ushr 24) and 0xFF; rr -= (po ushr 16) and 0xFF; gg -= (po ushr 8) and 0xFF; bb -= po and 0xFF
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
     * pause/ended. Best-effort: on OEMs that block reading this is a no-op and
     * [restoreOriginal] falls back to a neutral default.
     */
    fun snapshotOriginal(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val manager = WallpaperManager.getInstance(context)
        val pfd = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) ?: return false
        pfd.use {
            android.graphics.BitmapFactory.decodeStream(
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it),
            )
        }?.let { bmp ->
            val file = java.io.File(context.filesDir, "original_wallpaper.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return true
        }
        false
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