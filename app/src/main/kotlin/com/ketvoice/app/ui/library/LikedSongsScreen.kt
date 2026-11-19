package com.ketvoice.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.sub.AlbumPresentation

/** How Favoris can be sorted: the latest liked first (as they were added), by title, by artist. */
internal object LikedOrder {
    val labels = listOf("Récents", "Titre A–Z", "Artiste A–Z")

    fun arrange(tracks: List<Track>, likedOrder: Collection<String>, query: String, order: Int): List<Track> {
        val q = query.trim()
        val found = if (q.isEmpty()) tracks else tracks.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        return when (order) {
            1 -> found.sortedWith(compareBy(collator) { it.title })
            2 -> found.sortedWith(compareBy<Track, String>(collator) { it.artist }.thenBy(collator) { it.title })
            else -> {
                val rank = HashMap<String, Int>(likedOrder.size * 2).apply { likedOrder.forEachIndexed { i, id -> put(id, i) } }
                found.sortedByDescending { rank[it.id] ?: -1 }
            }
        }
    }
}

/**
 * Favoris (7.2), after Apple Music's and Spotify's liked songs: the heart cover, the name, how many titles
 * and how long, Play and Shuffle, then a search, the sorts and the titles.
 */
@Composable
internal fun LikedSongsScreen(tracks: List<Track>) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    val playingId by remember { Player.state.pick { it.current?.id } }.collectPick()
    val isPlaying by remember { Player.state.pick { it.isPlaying } }.collectPick()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableIntStateOf(0) }
    val liked = AppSettings.favoriteIds.value
    val shown = remember(tracks, liked, query, order) { LikedOrder.arrange(tracks, liked, query, order) }
    val list = rememberLazyListState()
    val collapse by remember { derivedStateOf {
        val hero = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "liked-hero" }
        if (hero == null) if (list.firstVisibleItemIndex > 0) 1f else 0f
        else ((-hero.offset).toFloat() / (hero.size * .75f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    } }
    val title = AppText.t("Favoris")
    val meta = listOfNotNull(AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres"),
        AlbumPresentation.totalDuration(tracks)?.let(AppText::t)).joinToString(" · ")
    fun play() { if (shown.isNotEmpty()) { Player.setShuffle(false); Player.playQueue(shown, 0) } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp
    Box(Modifier.fillMaxSize().pageBackground(theme)) {
        LazyColumn(Modifier.fillMaxSize().testTag("liked-list"), state = list,
            contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp + 20.dp)) {
            item(key = "liked-hero", contentType = "liked-hero") {
                CollectionHero(cover = null, top = top, title = title, titleTag = "liked-title", meta = meta,
                    artworkTag = "liked-cover", shrink = { collapse },
                    beside = com.ketvoice.app.ui.adaptive.LocalLargeScreen.current != null,
                    artwork = { LikedCover() }) {
                    CollectionActionPair(::play, { if (shown.isNotEmpty()) Player.playShuffled(shown) },
                        Modifier.widthIn(max = 440.dp).fillMaxWidth(), enabled = tracks.isNotEmpty(),
                        playTag = "liked-play", shuffleTag = "liked-shuffle", minHeight = 50.dp)
                }
            }
            if (tracks.size > 1) item(key = "liked-tools", contentType = "liked-tools") {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactSearchField(query, { query = it }, { keyboard?.hide() },
                        Modifier.padding(horizontal = pageMargin).testTag("liked-search"))
                    LazyRow(Modifier.testTag("liked-sort"), contentPadding = PaddingValues(horizontal = pageMargin),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(LikedOrder.labels) { index, label -> CollectionFilter(label, order == index, { order = index }) }
                    }
                }
            }
            if (tracks.isEmpty()) item(key = "liked-empty") {
                EmptyState("Aucun titre dans cette collection.", "Ajoutez des titres avec l'étoile du lecteur pour les retrouver ici.",
                    "Rechercher des titres", icon = KetIcons.Heart, onAction = ::openTrackSearch)
            } else if (shown.isEmpty()) item(key = "liked-none") {
                EmptyState("Aucun résultat", "Essayez un autre titre ou artiste.", "Effacer", icon = KetIcons.Search, onAction = { query = "" })
            }
            itemsIndexed(shown, key = { _, t -> t.id }, contentType = { _, _ -> "liked-track" }) { index, track ->
                SwipeTrackActions(track, if (Motion.reduced) Modifier else Modifier.animateItem()) {
                    TrackRow(track, MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl), playingId == track.id, isPlaying,
                        { Player.setShuffle(false); Player.playQueue(shown, index) })
                }
            }
        }
        CollectionTopBar(title, collapse, { AppUi.closeOverlay() }, onPlay = ::play, titleTag = "liked-bar-title")
    }
}
