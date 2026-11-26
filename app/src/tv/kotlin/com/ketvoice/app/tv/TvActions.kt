package com.ketvoice.app.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player

/** What the TV's pages ask for: open a page, play titles, show a title's options. One per activity. */
class TvActions(val nav: TvNavigator, private val player: TvPlayback = TvPlayback.Shared) {
    /** The title whose options are shown (the menu key, or a long press of the select key), or null. */
    val menu = mutableStateOf<Track?>(null)

    /** A search asked from outside (the TV's assistant): the Search page starts from it. */
    val searchSeed = mutableStateOf("")

    fun search(query: String) {
        searchSeed.value = query.trim()
        nav.section(TvRoute.Search)
    }

    fun open(item: TvItem) {
        if (item.kind == TvItemKind.Track && item.queue.isNotEmpty()) play(item.queue, item.index)
        else item.route?.let(nav::open)
    }

    /** Plays [tracks] from [index] and shows Now Playing, as a TV music app does. */
    fun play(tracks: List<Track>, index: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        if (shuffle) player.shuffle(tracks) else player.queue(tracks, index.coerceIn(0, tracks.lastIndex))
        nav.open(TvRoute.NowPlaying)
    }

    fun options(item: TvItem) { if (item.kind == TvItemKind.Track) item.queue.getOrNull(item.index)?.let { menu.value = it } }

    /** The shared player, or a recorder in tests. */
    interface TvPlayback {
        fun queue(tracks: List<Track>, index: Int)
        fun shuffle(tracks: List<Track>)

        object Shared : TvPlayback {
            override fun queue(tracks: List<Track>, index: Int) = Player.playQueue(tracks, index)
            override fun shuffle(tracks: List<Track>) = Player.playShuffled(tracks)
        }
    }
}

val LocalTvActions = staticCompositionLocalOf<TvActions> { error("TvActions not provided") }
