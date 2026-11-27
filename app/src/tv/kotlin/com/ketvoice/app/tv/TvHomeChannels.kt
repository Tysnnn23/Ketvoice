package com.ketvoice.app.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Writes [TvChannelPlan] to the TV's home screen (androidx.tvprovider): « Continuer l'écoute » in Watch Next and a
 * channel of playlists, mixes and recent albums. It follows playback and the library, a few seconds after they settle,
 * on a background thread, and only while the listener is signed in and the setting is on; signing out (or turning it
 * off) removes everything. A channel the listener hid stays hidden: its programmes are not touched any more.
 * TVs without the provider (some boxes) simply get nothing.
 */
object TvHomeChannels {
    const val DEBOUNCE_MS = 4_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val poke = MutableStateFlow(0)
    @Volatile private var started = false
    /** What was written last (this process): the same picture is not written again. */
    @Volatile private var lastPrograms: List<TvProgramSpec>? = null
    @Volatile private var lastWatch: TvWatchNextSpec? = null
    @Volatile private var cleared = false

    private data class Snapshot(val playing: Pair<String?, Boolean>, val signedIn: Boolean, val enabled: Boolean)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch { runCatching { watch(app) } }
    }

    /** Asks for a write now (after the usual pause), e.g. when the setting changed. */
    fun refresh(context: Context) { start(context); poke.value++ }

    @OptIn(FlowPreview::class)
    private suspend fun watch(app: Context) {
        val playing = Player.state.map { it.current?.id to it.playWhenReady }.distinctUntilChanged()
        val signedIn = AccountSession.state.map { it.signedIn }.distinctUntilChanged()
        combine(playing, signedIn, TvPrefs.homeChannels, ListeningHistory.tracks, PlaylistStore.lists) { p, s, e, _, _ -> Snapshot(p, s, e) }
            .combine(poke) { snapshot, _ -> snapshot }
            .debounce(DEBOUNCE_MS)
            .collectLatest { snapshot -> runCatching { write(app, snapshot) } }
    }

    private fun write(app: Context, snapshot: Snapshot) {
        if (!snapshot.signedIn || !snapshot.enabled) { clear(app); return }
        cleared = false
        writeWatchNext(app)
        writeChannel(app)
    }

    // ------------------------------------------------------------------ Watch Next

    private fun writeWatchNext(app: Context) {
        val state = Player.state.value
        val wanted = TvChannelPlan.watchNext(state.current, Player.positionMs.value, ListeningHistory.tracks.value.firstOrNull(),
            ArtworkRepo::coverForTrack)
        if (wanted == lastWatch) return
        val (delete, write) = TvChannelPlan.watchNextOps(existingWatchNext(app), wanted)
        delete.forEach { id -> app.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
        if (wanted != null && write != null) {
            val program = watchNextProgram(wanted)
            val helper = PreviewChannelHelper(app)
            if (write.updateId != null) helper.updateWatchNextProgram(program, write.updateId) else helper.publishWatchNextProgram(program)
        }
        lastWatch = wanted
    }

    @SuppressLint("RestrictedApi")
    private fun existingWatchNext(app: Context): List<TvExistingProgram> = buildList {
        app.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = WatchNextProgram.fromCursor(c)
                add(TvExistingProgram(p.id, p.internalProviderId.orEmpty(), p.isBrowsable))
            }
        }
    }

    // The setters are inherited from tvprovider's library-private base builder: lint flags their documented public use.
    @SuppressLint("RestrictedApi")
    private fun watchNextProgram(spec: TvWatchNextSpec): WatchNextProgram {
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setType(TvContractCompat.WatchNextPrograms.TYPE_TRACK)
            .setTitle(spec.title)
            .setDescription(spec.artist)
            .setAuthor(spec.artist)
            .setIntentUri(Uri.parse(spec.link))
            .setInternalProviderId(spec.key)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setDurationMillis(spec.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastPlaybackPositionMillis(spec.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
        spec.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
        return builder.build()
    }

    // ------------------------------------------------------------------ Channel

    private fun writeChannel(app: Context) {
        val helper = PreviewChannelHelper(app)
        val channelId = channel(app, helper) ?: return
        val wanted = programs()
        if (wanted == lastPrograms) return
        val ops = TvChannelPlan.ops(existingPrograms(app, channelId), wanted)
        ops.delete.forEach { runCatching { helper.deletePreviewProgram(it) } }
        val order = wanted.withIndex().associate { (i, s) -> s.key to TvChannelPlan.weight(i, wanted.size) }
        ops.update.forEach { (id, spec) -> runCatching { helper.updatePreviewProgram(id, previewProgram(channelId, spec, order[spec.key] ?: 1)) } }
        ops.insert.forEach { spec -> runCatching { helper.publishPreviewProgram(previewProgram(channelId, spec, order[spec.key] ?: 1)) } }
        lastPrograms = wanted
    }

    /** The channel's programmes from the listener's library, as Home builds its rows. */
    private fun programs(): List<TvProgramSpec> {
        val recent = ListeningHistory.tracks.value
        val known = ListeningHistory.knownTracks.value
        val counts = ListeningHistory.playCounts.value
        val favorites = AppSettings.favoriteIds.value
        val cover = { t: com.ketvoice.app.data.Track -> ArtworkRepo.coverForTrack(t) }
        val playlists = TvRows.libraryCards(emptyList(), emptyList(), emptyList(), PlaylistStore.lists.value, cover)
        val mixes = PersonalMixes.propose(known, recent, counts, favorites).map(TvRows::mix)
        val albums = TvRows.localAlbums(recent, cover)
        return TvChannelPlan.programs(playlists, mixes, albums, AppText::t)
    }

    /** Ketvoice's channel: made the first time (the TV shows a first channel by itself), null while the listener hides it. */
    private fun channel(app: Context, helper: PreviewChannelHelper): Long? {
        val found = helper.allChannels.firstOrNull { it.internalProviderId == TvChannelPlan.CHANNEL_KEY }
        if (found != null) return found.id.takeIf { found.isBrowsable }
        val logo = androidx.core.content.ContextCompat.getDrawable(app, com.ketvoice.app.R.mipmap.ic_launcher_vicecity)?.toBitmap(160, 160)
        val builder = PreviewChannel.Builder()
            .setDisplayName("Ketvoice")
            .setDescription(AppText.t("Vos playlists, mixes et albums récents"))
            .setAppLinkIntentUri(Uri.parse(TvLinks.home()))
            .setInternalProviderId(TvChannelPlan.CHANNEL_KEY)
        logo?.let(builder::setLogo)
        return helper.publishDefaultChannel(builder.build())
    }

    @SuppressLint("RestrictedApi")
    private fun existingPrograms(app: Context, channelId: Long): List<TvExistingProgram> = buildList {
        app.contentResolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION,
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = PreviewProgram.fromCursor(c)
                add(TvExistingProgram(p.id, p.internalProviderId.orEmpty(), p.isBrowsable))
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun previewProgram(channelId: Long, spec: TvProgramSpec, weight: Int): PreviewProgram = PreviewProgram.Builder()
        .setChannelId(channelId)
        .setType(when (spec.kind) {
            TvProgramKind.Album -> TvContractCompat.PreviewPrograms.TYPE_ALBUM
            TvProgramKind.Playlist, TvProgramKind.Mix -> TvContractCompat.PreviewPrograms.TYPE_PLAYLIST
        })
        .setTitle(spec.title)
        .setDescription(spec.description)
        .setPosterArtUri(Uri.parse(spec.poster))
        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
        .setIntentUri(Uri.parse(spec.link))
        .setInternalProviderId(spec.key)
        .setWeight(weight)
        .build()

    // ------------------------------------------------------------------ Sign-out

    /** Everything Ketvoice put on the home screen goes (the channel itself stays, empty, with the listener's choice to show it). */
    private fun clear(app: Context) {
        if (cleared) return
        existingWatchNext(app).filter { it.key.startsWith(TvChannelPlan.WATCH_NEXT_PREFIX) }
            .forEach { app.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it.id), null, null) }
        val helper = PreviewChannelHelper(app)
        helper.allChannels.firstOrNull { it.internalProviderId == TvChannelPlan.CHANNEL_KEY }?.let { channel ->
            existingPrograms(app, channel.id).forEach { runCatching { helper.deletePreviewProgram(it.id) } }
        }
        lastPrograms = null; lastWatch = null; cleared = true
    }
}
