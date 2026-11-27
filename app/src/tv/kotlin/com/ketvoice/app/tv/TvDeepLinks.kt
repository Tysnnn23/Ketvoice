package com.ketvoice.app.tv

import android.content.Intent
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Opens what a `ketvoice://tv/…` link from the TV's home screen asks for ([TvLinks]). */
object TvDeepLinks {
    /** True when [intent] was one of Ketvoice TV's links (handled once: a recreated activity does not open it again). */
    fun handle(intent: Intent, actions: TvActions, scope: CoroutineScope): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val link = TvLinks.parse(intent.dataString) ?: return false
        intent.action = null
        intent.data = null
        when (link) {
            is TvLink.Open -> actions.nav.open(link.route)
            is TvLink.Resume -> scope.launch { resume(link.trackId, link.atMs, actions) }
        }
        return true
    }

    /**
     * « Continuer l'écoute »: the saved session comes back when the queue first connects; when it holds that title,
     * it plays on from its place. Otherwise the title is found in the history or the downloads and played from [atMs].
     */
    private suspend fun resume(trackId: String?, atMs: Long, actions: TvActions) {
        withTimeoutOrNull(2_500) { Player.state.first { it.current != null } }
        val state = Player.state.value
        val current = state.current
        if (current != null && (trackId == null || current.id == trackId)) {
            if (!state.playWhenReady) Player.toggle()
        } else {
            val track = trackId?.let(::find) ?: return
            Player.playTrack(track, listOf(track), 0, startPositionMs = atMs)
        }
        actions.nav.open(TvRoute.NowPlaying)
    }

    private fun find(id: String): Track? = ListeningHistory.tracks.value.firstOrNull { it.id == id }
        ?: ListeningHistory.knownTracks.value.firstOrNull { it.id == id }
        ?: Downloader.itemFor(id)?.track
}
