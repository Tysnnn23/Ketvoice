package com.ketvoice.app.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A place in Ketvoice TV. The four sections of the side menu are roots; the others open over them. */
sealed interface TvRoute {
    /** A stable key: the page's remembered state (focus, scroll) is kept under it while it is in the stack. */
    val key: String

    data object Home : TvRoute { override val key = "home" }
    data object Search : TvRoute { override val key = "search" }
    data object Library : TvRoute { override val key = "library" }
    data object Settings : TvRoute { override val key = "settings" }

    /**
     * A list of titles: an album, a playlist, a personal mix, the favourites, the downloads or the history.
     * [kind] follows the phone's `CatalogRequest` kinds ("album", "mix") plus "playlist", "favorites", "downloads", "history".
     */
    data class Collection(val kind: String, val id: String, val title: String, val cover: String? = null) : TvRoute {
        override val key = "collection:$kind:$id"
    }

    data class Artist(val id: String, val name: String, val picture: String? = null) : TvRoute {
        override val key = "artist:$id:${name.lowercase()}"
    }

    data object NowPlaying : TvRoute { override val key = "now-playing" }
    data object SignIn : TvRoute { override val key = "sign-in" }

    /** « Mode cadre d'art » (1.1.0): the covers of the listener's albums, one after the other, over the whole screen. */
    data object ArtFrame : TvRoute { override val key = "art-frame" }

    /** « Replay » of [month] ("2026-11"), or the year's « Wrapped » when [month] is null (1.1.0), as full-screen slides. */
    data class Replay(val month: String?) : TvRoute { override val key = "replay:${month ?: "year"}" }

    val isRoot: Boolean get() = this in ROOTS

    /** Drawn over the whole screen, without the side menu. */
    val immersive: Boolean get() = this == NowPlaying || this == ArtFrame || this is Replay

    companion object {
        /**
         * The side menu's sections, top to bottom. Lazy: the routes are objects of this same interface, which the JVM
         * may still be initialising when the companion is.
         */
        val ROOTS: List<TvRoute> by lazy { listOf(Search, Home, Library, Settings) }
    }
}

/**
 * The back stack of Ketvoice TV. Back goes to the previous page, then from a section to Home, then leaves the app
 * ([back] answers false). Opening the page already on top does nothing; the stack never grows past [MAX_DEPTH]
 * (the oldest pages above the root are dropped first).
 */
class TvNavigator(start: TvRoute = TvRoute.Home) {
    var stack: List<TvRoute> by mutableStateOf(listOf(start))
        private set

    val current: TvRoute get() = stack.last()

    /** The section of the side menu the current page belongs to. */
    val section: TvRoute get() = stack.first()

    /** Pages that left the stack since the last call: their remembered state can be let go. */
    private val dropped = mutableListOf<TvRoute>()

    fun open(route: TvRoute) {
        if (route == current) return
        if (route.isRoot) { section(route); return }
        // A page opened again from deeper in the stack comes back to the top rather than appearing twice.
        val without = stack.filterIndexed { i, r -> i == 0 || r != route }
        dropped += stack - without.toSet()
        var next = without + route
        while (next.size > MAX_DEPTH) { dropped += next[1]; next = next.take(1) + next.drop(2) }
        stack = next
    }

    /** A section of the side menu: its root page, the pages opened in the previous section are closed. */
    fun section(root: TvRoute) {
        require(root.isRoot) { "Not a section: $root" }
        if (stack.size == 1 && current == root) return
        dropped += stack.filter { it != root }
        stack = listOf(root)
    }

    /** Back: true when it was handled here, false when the app should close. */
    fun back(): Boolean {
        if (stack.size > 1) { dropped += stack.last(); stack = stack.dropLast(1); return true }
        if (current != TvRoute.Home) { dropped += current; stack = listOf(TvRoute.Home); return true }
        return false
    }

    /** Pages closed since the last call (their saved state is let go), oldest first. */
    fun takeDropped(): List<TvRoute> = dropped.toList().also { dropped.clear() }.filter { it !in stack }

    companion object {
        const val MAX_DEPTH = 8
    }
}
