package com.ketvoice.app.ui.library

import com.ketvoice.app.data.Track
import java.text.Collator
import java.util.Locale

object AlbumOrder {
    val labels = listOf("Récents", "Titre A–Z", "Artiste A–Z")
    fun sort(tracks: List<Track>, order: Int, locale: Locale = Locale.getDefault()): List<Track> {
        if (order !in 1..2) return tracks
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return tracks.sortedWith { a, b ->
            val first = if (order == 1) collator.compare(a.album, b.album) else collator.compare(a.artist, b.artist)
            if (first != 0) first else if (order == 2) collator.compare(a.album, b.album) else 0
        }
    }
}
