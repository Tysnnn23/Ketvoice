package com.ketvoice.app.ui.friends

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.ketvoice.app.account.PublicProfiles
import com.ketvoice.app.account.RemoteAvatars
import com.ketvoice.app.data.AppText
import com.ketvoice.app.player.Player
import com.ketvoice.app.sync.FeedItem
import com.ketvoice.app.sync.FeedKind
import com.ketvoice.app.sync.FeedState
import com.ketvoice.app.sync.FriendsFeed
import com.ketvoice.app.ui.account.ProfileUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** The friends pages that are open, the last on top: the feed and the lists of followers, follows and blocks. */
object FriendsUi {
    enum class Page { Feed, Lists }
    val pages = mutableStateListOf<Page>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun openFeed() { pages.remove(Page.Feed); pages.add(Page.Feed) }
    fun openLists() { pages.remove(Page.Lists); pages.add(Page.Lists) }
    fun back() { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) }
    fun closeAll() { pages.clear() }

    /** A line touched: a title plays; a playlist plays as its author shows it now (or its profile opens). */
    fun open(item: FeedItem) {
        val track = item.track
        if (item.kind == FeedKind.Listening && track != null) { Player.setShuffle(false); Player.playQueue(listOf(track)); return }
        val wanted = item.playlist?.id ?: return
        scope.launch {
            val playlist = runCatching { PublicProfiles.lookup(item.handle) }.getOrNull()?.playlists?.firstOrNull { it.id == wanted }
            if (playlist != null && playlist.tracks.isNotEmpty()) { Player.setShuffle(false); Player.playQueue(playlist.tracks) }
            else ProfileUi.open(item.handle)
        }
    }

    fun react(item: FeedItem, reaction: String) { scope.launch { FriendsFeed.react(item.id, reaction) } }
}

/** Over the account sheet and under a profile page: opened by [FriendsUi]. */
@Composable
fun FriendsHost(modifier: Modifier = Modifier) {
    val top = FriendsUi.pages.lastOrNull() ?: return
    BackHandler { FriendsUi.back() }
    when (top) {
        FriendsUi.Page.Feed -> FriendsFeedPage(modifier.zIndex(8.3f))
        FriendsUi.Page.Lists -> FollowListsPage(modifier.zIndex(8.3f))
    }
}

/** The feed as the page shows it, following the account (an empty state while signed out). */
@Composable
internal fun feedState(): FeedState {
    val model by FriendsFeed.current.collectAsState()
    return (model?.state ?: FriendsFeed.idle).collectAsState().value
}

private const val JPEG = ".jpg"

/** A public profile's photo copy (read with the listener's session, cached), or [preset] in host renders. */
@Composable
internal fun publicAvatar(ref: String?, preset: File? = null): File? {
    val loaded by produceState(preset, ref, preset) {
        if (preset == null && ref != null) value = RemoteAvatars.get(RemoteAvatars.PUBLIC, ref + JPEG)
    }
    return loaded
}

/** The words of a feed line, apart so they can be tested. */
internal object FeedWords {
    private const val MINUTE = 60_000L

    /** What happened, after the name: « a publié une playlist », « a ajouté 3 titres », « écoute ». */
    fun action(item: FeedItem, now: Long): String = when (item.kind) {
        FeedKind.Published -> AppText.t("a publié une playlist")
        FeedKind.Added -> if (item.titles <= 1) AppText.t("a ajouté 1 titre") else AppText.t("a ajouté ${item.titles} titres")
        FeedKind.Listening -> if (item.atMs > 0 && now - item.atMs < 30 * MINUTE) AppText.t("écoute") else AppText.t("a écouté")
    }

    /** What it is about: the playlist's name, or the title and its artist. */
    fun subject(item: FeedItem): String = item.playlist?.name
        ?: item.track?.let { if (it.artist.isBlank()) it.title else it.title + " · " + it.artist }.orEmpty()

    /** How long ago, as a list writes it: « à l'instant », « il y a 5 min », « il y a 3 h », « il y a 2 j ». */
    fun ago(atMs: Long, now: Long): String {
        if (atMs <= 0) return ""
        val minutes = ((now - atMs) / MINUTE).coerceAtLeast(0)
        return when {
            minutes < 1 -> AppText.t("à l'instant")
            minutes < 60 -> AppText.t("il y a $minutes min")
            minutes < 24 * 60 -> AppText.t("il y a ${minutes / 60} h")
            else -> AppText.t("il y a ${minutes / (24 * 60)} j")
        }
    }

    /** What the reaction button says to a screen reader. */
    fun reactionName(key: String): String = AppText.t(when (key) {
        "heart" -> "J'adore"
        "fire" -> "En feu"
        "clap" -> "Bravo"
        "wow" -> "Waouh"
        else -> "Ça s'écoute"
    })
}
