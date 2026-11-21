package com.ketvoice.desktop.ui

import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lyrics for the fixtures' fourth title of « Marée basse » (the RecordingPlayer's, a minute in): a duet, word timed. */
class FixedLyricsFeed(private val lines: List<MusicApi.LyricLine> = LyricsFixtures.duet) : LyricsFeed {
    private val _state = MutableStateFlow(LyricsRepo.State())
    override val state: StateFlow<LyricsRepo.State> = _state
    val loads = mutableListOf<String>()
    override fun load(track: Track, refresh: Boolean) {
        loads += track.id + if (refresh) ":refresh" else ""
        _state.value = LyricsRepo.State(loading = false, source = "LRCLIB", lines = lines, isTimed = true,
            wordTimed = lines.any { it.words.size >= 2 }, trackId = track.id)
    }
}

object LyricsFixtures {
    private fun line(at: Long, text: String, singer: String, en: String, wordMs: Long = 600, held: Long = 0): MusicApi.LyricLine {
        var t = at
        val parts = text.split(" ")
        val words = parts.mapIndexed { i, w ->
            val len = if (i == parts.lastIndex && held > 0) held else wordMs
            MusicApi.LyricWord(t, if (i < parts.lastIndex) "$w " else w, t + len).also { t += len }
        }
        return MusicApi.LyricLine(at, text, words, translations = mapOf("en" to en), singer = singer)
    }

    val duet = listOf(
        line(48_000, "On a longé la mer sans un mot", "Nora Vale", "We walked along the sea without a word"),
        line(52_000, "Les phares dormaient sous la pluie", "Nora Vale", "The lighthouses slept under the rain"),
        line(56_000, "Et la nuit tenait nos mains", "Nora Vale", "And the night held our hands"),
        line(60_000, "Je garde ta voix dans le vent", "Nora Vale", "I keep your voice in the wind", wordMs = 380, held = 2_400),
        line(64_500, "Et moi je réponds de l'autre rive", "Kadi", "And I answer from the other shore"),
        line(68_500, "よあけまで うたおう", "Kadi", "Let us sing until dawn"),
        line(72_000, "Nuit claire, reste encore", "Nora Vale", "Clear night, stay a while"),
        line(76_000, "Nuit claire, reste encore", "Kadi", "Clear night, stay a while"),
        line(80_000, "Le jour viendra bien assez tôt", "Nora Vale", "Day will come soon enough"),
    )
}
