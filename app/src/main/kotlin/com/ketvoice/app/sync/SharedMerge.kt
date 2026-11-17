package com.ketvoice.app.sync

import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.Track

/** A title of a shared playlist as the server holds it: [pos] orders the list, [addedAt] breaks ties. */
internal data class SharedRow(val id: String, val track: Track, val addedBy: String?, val addedAt: String, val pos: Double)

/** A shared playlist as last read from the server (its titles are in order). */
internal data class SharedList(val id: String, val name: String, val owner: String, val rows: List<SharedRow>)

/** What this device knows: the lists as last read, and the writes that have not reached the server yet. */
internal data class SharedState(val user: String = "", val lists: Map<String, SharedList> = emptyMap(),
                                val ops: List<SharedOp> = emptyList())

/** A write made on this device, kept (on disk) until the server accepted it. */
internal sealed interface SharedOp {
    val list: String
    data class Create(override val list: String, val name: String) : SharedOp
    data class Add(override val list: String, val row: String, val track: Track, val pos: Double) : SharedOp
    data class Remove(override val list: String, val row: String) : SharedOp
    data class Move(override val list: String, val row: String, val pos: Double) : SharedOp
    data class Rename(override val list: String, val name: String) : SharedOp
    /** The owner deletes the playlist for everybody. */
    data class Delete(override val list: String) : SharedOp
    /** A member leaves. */
    data class Leave(override val list: String) : SharedOp
}

/**
 * The rules of a playlist several accounts edit together, as pure functions.
 *
 * Conflicts: the server orders titles by (pos, added_at, id). Two people adding at once never collide
 * (a title is placed between two neighbours with a fraction, and the same title added twice stays once);
 * two people moving the same title: the last write wins; a move of a title someone just removed is dropped;
 * a write this device has not sent yet is shown on top of what the server says, until the server accepts it.
 */
internal object SharedMerge {
    const val MAX_TRACKS = 500
    /** Neighbours closer than this are respaced instead of squeezing one more title between them. */
    const val GAP = 1e-7
    /** Sorts after any server timestamp: a title only this device has yet comes last among equals. */
    private const val LOCAL_AT = "~"

    fun ordered(rows: List<SharedRow>): List<SharedRow> = rows.sortedWith(compareBy<SharedRow>({ it.pos }, { it.addedAt }, { it.id }))

    /** The list as this device shows it: the server's, with the unsent writes on top. */
    fun display(list: SharedList, ops: List<SharedOp>): SharedList {
        var name = list.name
        var rows = list.rows
        for (op in ops) {
            if (op.list != list.id) continue
            when (op) {
                is SharedOp.Rename -> name = op.name
                is SharedOp.Add -> if (rows.size < MAX_TRACKS && rows.none { it.id == op.row || it.track.id == op.track.id })
                    rows = rows + SharedRow(op.row, op.track, null, LOCAL_AT, op.pos)
                is SharedOp.Remove -> rows = rows.filterNot { it.id == op.row }
                is SharedOp.Move -> rows = rows.map { if (it.id == op.row) it.copy(pos = op.pos) else it }
                else -> Unit
            }
        }
        return list.copy(name = name, rows = ordered(rows))
    }

    /** Where [count] titles go between two neighbours (either may be missing); null when there is no room left. */
    fun spread(before: Double?, after: Double?, count: Int): List<Double>? = when {
        count <= 0 -> emptyList()
        before == null && after == null -> List(count) { (it + 1).toDouble() }
        after == null -> List(count) { before!! + it + 1 }
        before == null -> List(count) { after - count + it }
        after - before < GAP * (count + 1) -> null
        else -> List(count) { before + (after - before) * (it + 1) / (count + 1) }
    }

    /**
     * The writes that turn [shown] into [entries] (what the listener's edit left in the playlist): removals,
     * then the fewest moves (the titles already in the right order stay where they are, the others are placed
     * between their neighbours) and the new titles, each run of them sharing the room evenly.
     */
    fun diff(shown: SharedList, entries: List<PlaylistEntry>): List<SharedOp> {
        val have = shown.rows.associateBy { it.id }
        val seen = HashSet<String>()
        val target = entries.filter { e -> e.track?.let { SharedTracks.shareable(it) } == true }
            .filter { e -> e.id in have || (seen.add(e.track!!.id) && shown.rows.none { it.track.id == e.track.id }) }
            .take(MAX_TRACKS)
        val ops = ArrayList<SharedOp>()
        shown.rows.filter { r -> target.none { it.id == r.id } }.forEach { ops += SharedOp.Remove(shown.id, it.id) }
        val index = target.withIndex().associate { it.value.id to it.index }
        val survivors = shown.rows.filter { it.id in index }
        val stable = longestIncreasing(survivors.map { index.getValue(it.id) }).map { survivors[it].id }.toSet()
        val posOf = HashMap<String, Double>()
        survivors.filter { it.id in stable }.forEach { posOf[it.id] = it.pos }
        var i = 0
        while (i < target.size) {
            if (target[i].id in stable) { i++; continue }
            var j = i
            while (j < target.size && target[j].id !in stable) j++
            val spots = spread(if (i > 0) posOf[target[i - 1].id] else null, if (j < target.size) posOf[target[j].id] else null, j - i)
                ?: return respace(shown, target, have)
            for (k in i until j) {
                val entry = target[k]
                posOf[entry.id] = spots[k - i]
                ops += if (entry.id in have) SharedOp.Move(shown.id, entry.id, spots[k - i]) else SharedOp.Add(shown.id, entry.id, entry.track!!, spots[k - i])
            }
            i = j
        }
        return ops
    }

    /** Indices of a longest strictly increasing run of [values] (the titles that need not move). */
    internal fun longestIncreasing(values: List<Int>): List<Int> {
        if (values.isEmpty()) return emptyList()
        val length = IntArray(values.size) { 1 }
        val previous = IntArray(values.size) { -1 }
        for (i in values.indices) for (j in 0 until i) {
            if (values[j] < values[i] && length[j] + 1 > length[i]) { length[i] = length[j] + 1; previous[i] = j }
        }
        var at = length.indices.maxByOrNull { length[it] }!!
        val out = ArrayList<Int>()
        while (at >= 0) { out += at; at = previous[at] }
        return out.reversed()
    }

    /** No room between two neighbours: every title gets the position of its place (1, 2, 3…). */
    private fun respace(shown: SharedList, target: List<PlaylistEntry>, have: Map<String, SharedRow>): List<SharedOp> {
        val ops = ArrayList<SharedOp>()
        shown.rows.filter { r -> target.none { it.id == r.id } }.forEach { ops += SharedOp.Remove(shown.id, it.id) }
        target.forEachIndexed { index, entry ->
            val pos = (index + 1).toDouble()
            val row = have[entry.id]
            if (row == null) ops += SharedOp.Add(shown.id, entry.id, entry.track!!, pos)
            else if (row.pos != pos) ops += SharedOp.Move(shown.id, entry.id, pos)
        }
        return ops
    }

    /** What an edit of the Library's playlists changed in the shared ones, and whether the store must be put right. */
    data class Edit(val state: SharedState, val remirror: Boolean)

    /**
     * [shown] are the lists whose row was put in the Library: only for those does a missing row mean « the
     * listener deleted it » (a list just read from the server has no row yet, and must not be taken for deleted).
     */
    fun edit(state: SharedState, lists: List<LocalPlaylist>, shown: Set<String>, sent: Collection<SharedOp> = emptyList()): Edit {
        val ops = ArrayList(state.ops)
        val kept = LinkedHashMap(state.lists)
        var remirror = false
        for ((id, list) in state.lists) {
            if (id !in shown) continue
            val current = display(list, state.ops)
            val local = lists.firstOrNull { it.id == PlaylistRules.sharedLocalId(id) }
            if (local == null) {
                ops += if (list.owner == state.user) SharedOp.Delete(id) else SharedOp.Leave(id)
                kept.remove(id)
                continue
            }
            if (local.name != current.name) { if (list.owner == state.user) ops += SharedOp.Rename(id, local.name) else remirror = true }
            ops += diff(current, local.entries)
        }
        return Edit(state.copy(lists = kept, ops = compact(ops, sent)), remirror)
    }

    /**
     * Cancels what cancels (a title added then removed before it was sent, a playlist created then deleted)
     * and keeps one write per title position. An op in [sent] is already on its way: it is never
     * cancelled, only followed by what undoes it.
     */
    fun compact(ops: List<SharedOp>, sent: Collection<SharedOp> = emptyList()): List<SharedOp> {
        val out = ArrayList<SharedOp>()
        for (op in ops) {
            when (op) {
                is SharedOp.Remove -> {
                    val add = out.indexOfFirst { it is SharedOp.Add && it.row == op.row && it !in sent }
                    if (add >= 0) { out.removeAt(add); out.removeAll { it is SharedOp.Move && it.row == op.row && it.list == op.list } }
                    else out += op
                }
                is SharedOp.Move -> {
                    val prior = out.indexOfFirst { (it is SharedOp.Add || it is SharedOp.Move) && rowOf(it) == op.row && it !in sent }
                    when (val before = out.getOrNull(prior)) {
                        is SharedOp.Add -> out[prior] = before.copy(pos = op.pos)
                        is SharedOp.Move -> out[prior] = op
                        else -> out += op
                    }
                }
                is SharedOp.Rename -> { out.removeAll { it is SharedOp.Rename && it.list == op.list && it !in sent }; out += op }
                is SharedOp.Delete, is SharedOp.Leave -> {
                    val created = out.any { it is SharedOp.Create && it.list == op.list && it !in sent }
                    out.removeAll { it.list == op.list && it !in sent }
                    if (!created) out += op
                }
                else -> out += op
            }
        }
        return out
    }

    private fun rowOf(op: SharedOp): String? = when (op) { is SharedOp.Add -> op.row; is SharedOp.Move -> op.row; else -> null }

    /** The Library's rows for the shared lists: each keeps its pin when it is already there. */
    fun mirror(state: SharedState, current: List<LocalPlaylist>): List<LocalPlaylist> =
        state.lists.values.map { display(it, state.ops) }.map { list ->
            val old = current.firstOrNull { it.id == PlaylistRules.sharedLocalId(list.id) }
            LocalPlaylist(id = PlaylistRules.sharedLocalId(list.id), name = list.name, createdAt = old?.createdAt ?: 0L,
                pinned = old?.pinned ?: false, coverUrl = null, spotifyUrl = null,
                entries = list.rows.map { PlaylistEntry(it.id, it.track.title, it.track.artist, null, it.track) })
        }
}
