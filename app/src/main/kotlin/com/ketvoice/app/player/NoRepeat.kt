package com.ketvoice.app.player

import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.Track

/**
 * « Radio sans répétition » (7.1): the infinite radio and « Lecture automatique » never bring back a
 * title heard in the last [WINDOW_MS] (by id or by recording, from [ListeningHistory.heardAt]). Deezer's
 * Flow was criticised for replaying the same titles after twenty; here, only when too few titles are
 * left ([MINIMUM]) do the ones heard longest ago come back, after every fresh one.
 */
internal object NoRepeatWindow {
    const val WINDOW_MS = 4 * 60 * 60 * 1000L
    /** Below this many fresh titles, the ones heard longest ago fill the batch up to it. */
    const val MINIMUM = 5

    /** When a title was heard within [window] before [now], or null: what the radio leaves out. */
    fun recent(heard: Map<String, Long>, now: Long, window: Long = WINDOW_MS): (Track) -> Long? = { track ->
        ListeningHistory.heardKeys(track).mapNotNull { heard[it] }.maxOrNull()?.takeIf { now - it < window }
    }

    /** The titles not heard lately, in their order, then the recently heard ones, heard longest ago first. */
    fun ordered(candidates: List<Track>, recent: (Track) -> Long?): List<Track> {
        val (stale, fresh) = candidates.partition { recent(it) != null }
        return fresh + stale.sortedBy { recent(it) ?: 0L }
    }

    /**
     * [pick] over the titles not heard lately; when that gives fewer than [minimum], over them followed by
     * the recently heard ones (oldest first), so the queue goes on instead of stopping. [pick] must keep the
     * order it is given (as [AutoplayRules.pick] does).
     */
    fun pick(candidates: List<Track>, recent: (Track) -> Long?, minimum: Int = MINIMUM, pick: (List<Track>) -> List<Track>): List<Track> {
        val fresh = candidates.filter { recent(it) == null }
        val first = pick(fresh)
        if (first.size >= minimum || fresh.size == candidates.size) return first
        return pick(ordered(candidates, recent))
    }
}
