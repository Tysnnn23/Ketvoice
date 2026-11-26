package com.ketvoice.desktop.ui

import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track

/**
 * Which picture the window shows, by the phone's rules: what the owner published from the Studio
 * ([MediaOverrides]: a title's cover, else its album's; an album's cover; an artist's portrait and page photo) over
 * what the catalogue gave, and the cover the phone found for a title without one ([ArtworkRepo]). The overrides are
 * Compose state: a cover the owner just published redraws wherever it shows.
 */
internal object CoverSources {
    fun track(track: Track?): String? = track?.let { runCatching { ArtworkRepo.coverForTrack(it) }.getOrNull() ?: it.coverUrl }

    fun album(title: String, artist: String, fallback: String?): String? = MediaOverrides.albumCover(title, artist, fallback)

    fun album(ref: AlbumRef): String? = album(ref.title, ref.artist, ref.cover)

    /** The round portrait an artist shows in lists. */
    fun artistPhoto(name: String, fallback: String?): String? = MediaOverrides.artistPhoto(name, fallback)

    /** The wide photo at the top of an artist's page. */
    fun artistHero(name: String, fallback: String?): String? = MediaOverrides.artistHero(name) ?: fallback

    /** A playlist's cover: its own (an imported playlist's artwork), else none (the mosaic of its titles shows). */
    fun playlist(cover: String?): String? = cover?.takeIf { it.isNotBlank() }
}
