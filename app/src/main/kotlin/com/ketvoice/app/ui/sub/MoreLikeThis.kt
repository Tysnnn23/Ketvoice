package com.ketvoice.app.ui.sub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.AutoplayRules
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TrackRow
import com.ketvoice.app.ui.common.pick
import com.ketvoice.app.ui.common.collectPick
import com.ketvoice.app.ui.motion.StaggeredReveal

/**
 * « Titres recommandés » under an album or a playlist (7.2, as Spotify closes a page): titles of the same
 * artist and style, picked the way autoplay picks what follows (Player.autoplayCandidates, AutoplayRules),
 * then the downloads alike with no network. Asked once the section comes near the screen (it is a lazy
 * list item), kept for the session per page.
 */
internal object MoreLikeThis {
    const val COUNT = 8
    private val cache = object : LinkedHashMap<String, List<Track>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Track>>?) = size > 24
    }

    /** Host renders lay their suggestions here: tests never reach the network. */
    internal fun prime(key: String, tracks: List<Track>) = synchronized(cache) { cache[key] = tracks }

    /** The suggestions for a page of [tracks]: never one of its titles, a blocked artist or a title twice. */
    suspend fun load(key: String, tracks: List<Track>): List<Track> {
        synchronized(cache) { cache[key] }?.let { return it }
        val seeds = tracks.filter { it.title.isNotBlank() }.distinctBy { AutoplayRules.artistKey(it) }.take(2)
        val seed = seeds.firstOrNull() ?: return emptyList()
        val pool = ArrayList<Track>()
        for (lead in seeds) {
            pool += runCatching { Player.autoplayCandidates(lead) }.getOrDefault(emptyList())
            if (pool.size >= COUNT * 2) break
        }
        var picked = AutoplayRules.pick(pool, seed, tracks, emptySet(), { t: Track -> ArtistPrefs.allows(t) }, { null }, batch = COUNT)
        if (picked.isEmpty()) picked = runCatching {
            val downloads = Downloader.items.value.map { it.track }
            val facts = com.ketvoice.app.smartlib.SmartFacts.build(downloads + seed)
            AutoplayRules.offline(seed, downloads, facts, tracks, emptySet(), { t: Track -> ArtistPrefs.allows(t) }, kotlin.random.Random(key.hashCode()))
                .take(COUNT)
        }.getOrDefault(emptyList())
        if (picked.isNotEmpty()) synchronized(cache) { cache[key] = picked }
        return picked
    }
}

/** The section itself: a header, then the suggested titles; a tap plays them from there, « + » adds one to a playlist. */
@Composable
internal fun MoreLikeThisSection(key: String, tracks: List<Track>, fromPlaylist: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val suggested by produceState<List<Track>?>(null, key, tracks.size) {
        value = if (tracks.isEmpty()) emptyList() else runCatching { MoreLikeThis.load(key, tracks) }.getOrDefault(emptyList())
    }
    val shown = suggested ?: return
    if (shown.isEmpty()) return
    androidx.compose.runtime.LaunchedEffect(shown) { ArtworkRepo.enrich(shown) }
    val art by ArtworkRepo.covers.collectAsState()
    val playingId by androidx.compose.runtime.remember { Player.state.pick { it.current?.id } }.collectPick()
    val isPlaying by androidx.compose.runtime.remember { Player.state.pick { it.isPlaying } }.collectPick()
    Column(modifier.fillMaxWidth().padding(top = 18.dp).testTag("more-like-this")) {
        com.ketvoice.app.ui.home.EditorialHeader("Titres recommandés",
            subtitle = com.ketvoice.app.data.AppText.t(if (fromPlaylist) "D'après cette playlist" else "D'après cet album"))
        shown.forEachIndexed { index, track ->
            StaggeredReveal(order = index.coerceAtMost(5)) {
                TrackRow(track, com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl),
                    playingId == track.id, isPlaying, { Player.setShuffle(false); Player.playQueue(shown, index) },
                    Modifier.testTag("more-like-this-row")) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) {
                        AppUi.addTracksToPlaylist.value = listOf(track)
                    }.testTag("more-like-this-add"), contentAlignment = Alignment.Center) {
                        Icon(KetIcons.Plus, "Ajouter à une playlist", tint = theme.secondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
