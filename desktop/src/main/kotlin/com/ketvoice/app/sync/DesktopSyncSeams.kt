package com.ketvoice.app.sync

/*
 * Desktop counterparts of the account's other live features, reduced to what the shared LibrarySync
 * and CloudLibrary call. The personal library (favourites, playlists, artists, settings) syncs for
 * real; shared playlists and « now playing » (phone control) come with Phase 4.
 */

/** Shared playlists live on their own server tables; the desktop does not open them yet. */
object SharedPlaylists {
    fun onForeground() = Unit
    fun onBackground() = Unit
}

/** The account row « now playing » is written by the phone and the web; the desktop does not publish it yet. */
object NowPlayingSync {
    const val KIND = "now_playing"
}
