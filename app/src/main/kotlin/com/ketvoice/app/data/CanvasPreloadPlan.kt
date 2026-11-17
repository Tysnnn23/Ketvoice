package com.ketvoice.app.data

/**
 * What the Canvas preload fetches next and when it stops (6.2.3). Pure: no clock, no network, no files; the runner
 * ([CanvasPreload]) gives it the time and the conditions of the moment and does what it answers.
 *
 * One plan is one run: the titles are handed out once each, a few per minute, at most [maxPerRun], and the run ends
 * when the listener turns it off, when the network is no longer unmetered or is another one than the run started on,
 * or when the app has been neither in front nor charging for too long.
 */
class CanvasPreloadPlan(
    titles: List<Track>,
    private val maxPerRun: Int = MAX_PER_RUN,
    private val intervalMs: Long = INTERVAL_MS,
    private val maxIdleMs: Long = MAX_IDLE_MS
) {
    /** The state of the world at the moment of the question. [network] identifies the connection (null when unknown). */
    data class Conditions(
        val enabled: Boolean,
        /** The player's or the album page's Canvas is on: with both off, nothing is fetched. */
        val canvasOn: Boolean,
        val unmetered: Boolean,
        /** The app is in front, or the device is charging. */
        val inFrontOrCharging: Boolean,
        val network: String? = null
    )

    enum class Reason { Disabled, CanvasOff, NotUnmetered, NetworkChanged, Idle, Limit, Done }

    sealed interface Step {
        data class Fetch(val track: Track) : Step
        data class Wait(val ms: Long) : Step
        data class Stop(val reason: Reason) : Step
    }

    private val pending = ArrayDeque(titles.distinctBy { it.id })
    private val handed = HashSet<String>()
    private var count = 0
    private var lastFetchAt = Long.MIN_VALUE
    private var network: String? = null
    private var networkKnown = false
    private var idleSince: Long? = null

    /** How many titles were handed out so far. */
    val fetched: Int get() = count

    /**
     * The next move at [now]. [needed] says whether a title still has to be looked up (false: it has its Canvas,
     * is excluded or was tried lately): such titles are passed over without counting or waiting.
     */
    fun next(now: Long, c: Conditions, needed: (Track) -> Boolean = { true }): Step {
        stopReason(c)?.let { return Step.Stop(it) }
        if (!networkKnown) { networkKnown = true; network = c.network }
        if (!c.inFrontOrCharging) {
            val since = idleSince ?: now.also { idleSince = it }
            return if (now - since > maxIdleMs) Step.Stop(Reason.Idle) else Step.Wait(IDLE_POLL_MS)
        }
        idleSince = null
        if (count >= maxPerRun) return Step.Stop(Reason.Limit)
        val wait = lastFetchAt + intervalMs - now
        if (lastFetchAt != Long.MIN_VALUE && wait > 0) return Step.Wait(wait)
        val track = takeNext(needed) ?: return Step.Stop(Reason.Done)
        count++; lastFetchAt = now
        return Step.Fetch(track)
    }

    private fun stopReason(c: Conditions): Reason? = when {
        !c.enabled -> Reason.Disabled
        !c.canvasOn -> Reason.CanvasOff
        !c.unmetered -> Reason.NotUnmetered
        networkKnown && c.network != network -> Reason.NetworkChanged
        else -> null
    }

    private fun takeNext(needed: (Track) -> Boolean): Track? {
        while (pending.isNotEmpty()) {
            val track = pending.removeFirst()
            if (!handed.add(track.id)) continue
            if (needed(track)) return track
        }
        return null
    }

    companion object {
        /** At most this many titles per run. */
        const val MAX_PER_RUN = 40
        /** Four a minute at most. */
        const val INTERVAL_MS = 15_000L
        /** A run waits this long for the app to come to the front or the device to charge, then ends. */
        const val MAX_IDLE_MS = 10 * 60_000L
        const val IDLE_POLL_MS = 15_000L

        /** The titles to walk: favourites first, then the playlists' titles in their order; each title once, never a local file. */
        fun order(favorites: List<Track>, playlists: List<List<Track>>): List<Track> =
            (favorites + playlists.flatten()).filter { !it.id.startsWith("local:") }.distinctBy { it.id }
    }
}
