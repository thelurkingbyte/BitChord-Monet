package com.music.bitchord.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sets a track's album artwork as the system wallpaper.
 *
 * The artwork is fetched through the app's existing Coil loader at
 * [PLAYER_ART_PX] — the same rung the full player and the home-screen widget
 * already use — so it shares a cache entry instead of pulling a second copy
 * over the network. Everything runs off the main thread, because
 * [WallpaperManager.setBitmap] decodes and writes.
 *
 * Both the home and lock surfaces are attempted. They are separate surfaces:
 * some OEMs refuse the lock screen, so the home screen succeeding is still a
 * result worth reporting.
 */
object WallpaperArt {

    /** Applies [artworkUrl] to the home and lock screen. True if either took. */
    suspend fun apply(context: Context, artworkUrl: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (artworkUrl.isNullOrBlank()) return@withContext false
            val bitmap = load(context, artworkUrl) ?: return@withContext false
            val manager = WallpaperManager.getInstance(context)
            var system = false
            var lock = false
            runCatching {
                manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                system = true
            }
            runCatching {
                manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                lock = true
            }
            system || lock
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
}