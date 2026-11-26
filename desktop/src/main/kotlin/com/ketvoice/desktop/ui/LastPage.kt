package com.ketvoice.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.asSkiaBitmap
import com.ketvoice.desktop.player.Session

/**
 * Near-instant start (Windows 2.0): the page the window showed last, opened again at start ([startRoute]) and followed
 * while the window lives ([Remember]). Only pages that need nothing fetched to name them come back (the sidebar's
 * pages, Settings, the equalizer, a playlist of the library); any other page brings back its section.
 */
object LastPage {
    fun key(route: Route): String? = when (route) {
        Route.Home -> "home"; Route.Explore -> "explore"; Route.Search -> null; Route.RecentlyAdded -> "recent"
        Route.Artists -> "artists"; Route.Albums -> "albums"; Route.Songs -> "songs"; Route.Downloads -> "downloads"
        Route.Favorites -> "favorites"; Route.Stats -> "stats"; Route.Settings -> "settings"; Route.Equalizer -> "equalizer"
        is Route.Playlist -> "playlist:${route.id}"
        else -> null
    }

    fun route(key: String?, playlistExists: (String) -> Boolean = { id -> com.ketvoice.desktop.library.DesktopLibrary.playlists.value.any { it.id == id } }): Route? = when {
        key == null -> null
        key.startsWith("playlist:") -> key.removePrefix("playlist:").takeIf(playlistExists)?.let { Route.Playlist(it) }
        else -> when (key) {
            "home" -> Route.Home; "explore" -> Route.Explore; "recent" -> Route.RecentlyAdded; "artists" -> Route.Artists
            "albums" -> Route.Albums; "songs" -> Route.Songs; "downloads" -> Route.Downloads; "favorites" -> Route.Favorites
            "stats" -> Route.Stats; "settings" -> Route.Settings; "equalizer" -> Route.Equalizer
            else -> null
        }
    }

    /**
     * The cover at [url] as the window shows it, encoded (PNG) for the next start; asked a few seconds after the title
     * changed, once it has been drawn. Null while it is not decoded.
     */
    suspend fun coverBytes(url: String?): ByteArray? {
        kotlinx.coroutines.delay(3_000)
        val image = Covers.cached(url) ?: return null
        // Covers are now decoded at the screen's size (up to 3000 px): the first frame's stand-in needs far less.
        return runCatching {
            org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap()).use { full ->
                val long = maxOf(full.width, full.height)
                if (long <= 720) return@use full.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
                val w = full.width * 720 / long; val h = full.height * 720 / long
                org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { s ->
                    s.canvas.drawImageRect(full, org.jetbrains.skia.Rect.makeWH(full.width.toFloat(), full.height.toFloat()),
                        org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), org.jetbrains.skia.SamplingMode.MITCHELL, null, true)
                    s.makeImageSnapshot().use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes }
                }
            }
        }.getOrNull()
    }

    /** The page to open the window on: the last one, else the layout's first. */
    fun startRoute(): Route = runCatching { route(Session.restored?.page) }.getOrNull() ?: DeskLayout.startRoute()

    /** Remembers the page [nav] shows (its section when the page itself cannot come back). */
    @Composable
    fun Remember(nav: Navigator) {
        LaunchedEffect(nav) {
            snapshotFlow { key(nav.current) ?: key(nav.section) }.collect { Session.page = it }
        }
    }
}
