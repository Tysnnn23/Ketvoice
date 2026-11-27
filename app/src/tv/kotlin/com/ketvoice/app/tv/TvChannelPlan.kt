package com.ketvoice.app.tv

import com.ketvoice.app.data.Track

/** A programme of Ketvoice's channel on the TV's home screen: a playlist, a mix or an album. */
enum class TvProgramKind { Playlist, Mix, Album }

data class TvProgramSpec(
    /** The programme's internal provider id: how a later update finds it again. */
    val key: String,
    val kind: TvProgramKind,
    val title: String,
    val description: String,
    /** An http(s) picture: the home screen is another app and cannot read Ketvoice's files. */
    val poster: String,
    val link: String,
)

/** « Continuer l'écoute »: the title playing or played last, at its place. */
data class TvWatchNextSpec(
    val key: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val poster: String?,
    val link: String,
    val durationMs: Long,
    val positionMs: Long,
)

/** A programme already published, as the TV's provider returns it ([browsable] false once the listener removed it). */
data class TvExistingProgram(val id: Long, val key: String, val browsable: Boolean)

/** What to do to the provider: programmes to add (with their order), to rewrite, to remove. */
data class TvChannelOps(
    val insert: List<TvProgramSpec> = emptyList(),
    val update: List<Pair<Long, TvProgramSpec>> = emptyList(),
    val delete: List<Long> = emptyList(),
) {
    val isEmpty: Boolean get() = insert.isEmpty() && update.isEmpty() && delete.isEmpty()
}

/**
 * « Chaînes sur l'accueil de la TV » (Ketvoice TV 1.1.0): what Ketvoice puts on the TV's home screen, worked out from
 * the same cards Home shows ([TvRows]). Pure: host-tested; [TvHomeChannels] writes it to the provider.
 */
object TvChannelPlan {
    const val CHANNEL_KEY = "ketvoice-tv-main"
    const val PLAYLISTS = 6
    const val MIXES = 6
    const val ALBUMS = 8
    const val MAX_PROGRAMS = 20
    const val WATCH_NEXT_PREFIX = "continue:"

    /** The channel's programmes: playlists first, then the personal mixes, then the albums played lately. */
    fun programs(playlists: List<TvItem>, mixes: List<TvItem>, albums: List<TvItem>, text: (String) -> String = { it }): List<TvProgramSpec> =
        (playlists.filter { it.kind == TvItemKind.Playlist }.take(PLAYLISTS) + mixes.take(MIXES) + albums.take(ALBUMS))
            .mapNotNull { spec(it, text) }.distinctBy { it.key }.take(MAX_PROGRAMS)

    fun spec(item: TvItem, text: (String) -> String = { it }): TvProgramSpec? {
        val poster = item.image?.takeIf(::isWebImage) ?: return null
        val link = item.route?.let(TvLinks::of) ?: return null
        val kind = when (item.kind) {
            TvItemKind.Mix -> TvProgramKind.Mix
            TvItemKind.Album -> TvProgramKind.Album
            TvItemKind.Playlist, TvItemKind.Collection -> TvProgramKind.Playlist
            else -> return null
        }
        val title = if (item.appText) text(item.title) else item.title
        val description = if (item.appText || item.kind == TvItemKind.Playlist) text(item.subtitle) else item.subtitle
        return TvProgramSpec(item.key, kind, title, description, poster, link)
    }

    fun isWebImage(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** The title to continue: [current] (with its place) when there is one, else the last one heard (from its start). */
    fun watchNext(current: Track?, positionMs: Long, last: Track?, cover: (Track) -> String?): TvWatchNextSpec? {
        val track = current ?: last ?: return null
        if (track.id.isBlank()) return null
        val duration = track.durationMs.coerceAtLeast(0L)
        val position = if (current != null) positionMs.coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE) else 0L
        return TvWatchNextSpec(WATCH_NEXT_PREFIX + track.id, track.id, track.title, track.artist,
            cover(track)?.takeIf(::isWebImage), TvLinks.resume(track.id, position), duration, position)
    }

    /**
     * Brings [existing] to [wanted]: programmes that left are removed, those still wanted rewritten, new ones added.
     * One the listener removed from the home screen (no longer browsable) is left alone while it stays wanted: it
     * never comes back by itself.
     */
    fun ops(existing: List<TvExistingProgram>, wanted: List<TvProgramSpec>): TvChannelOps {
        val wantedKeys = wanted.map { it.key }.toSet()
        val kept = HashSet<String>()
        val delete = ArrayList<Long>()
        val update = ArrayList<Pair<Long, TvProgramSpec>>()
        existing.forEach { e ->
            // A key seen twice (an interrupted earlier write) keeps only its first programme.
            if (e.key !in wantedKeys || !kept.add(e.key)) { delete += e.id; return@forEach }
            if (e.browsable) update += e.id to wanted.first { it.key == e.key }
        }
        return TvChannelOps(insert = wanted.filter { it.key !in kept }, update = update, delete = delete)
    }

    /** The order of the channel: the first programme weighs most. */
    fun weight(index: Int, count: Int): Int = (count - index).coerceAtLeast(1)

    /** The Watch Next entries to remove and whether [wanted] is to be written (inserted, or updated as [updateId]). */
    fun watchNextOps(existing: List<TvExistingProgram>, wanted: TvWatchNextSpec?): Pair<List<Long>, WatchNextWrite?> {
        val mine = existing.filter { it.key.startsWith(WATCH_NEXT_PREFIX) }
        val same = wanted?.let { w -> mine.firstOrNull { it.key == w.key } }
        val delete = mine.filter { it !== same }.map { it.id }
        val write = when {
            wanted == null -> null
            same == null -> WatchNextWrite(null)
            same.browsable -> WatchNextWrite(same.id)
            // Removed by the listener: that title stays away; a new title will come back on its own.
            else -> null
        }
        return delete to write
    }

    data class WatchNextWrite(val updateId: Long?)
}
