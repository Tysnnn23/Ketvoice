package com.ketvoice.app.together

/**
 * The host's clock seen from a guest. Realtime gives no server time, so each guest measures the host's clock
 * itself, the NTP way: a ping carries the guest's time, the pong adds the host's; the exchange with the shortest
 * round trip is the most exact, and its middle is when the host read its clock. Both sides use a monotonic clock
 * (elapsedRealtime), so a change of the phone's time never moves the music.
 */
class ClockOffset(private val keep: Int = 8) {
    private data class Sample(val offset: Long, val rtt: Long)
    private val samples = ArrayDeque<Sample>()
    private var provisional: Long? = null

    /** Host clock minus guest clock, from the best exchange; null before the first one. */
    val offset: Long? get() = samples.minByOrNull { it.rtt }?.offset ?: provisional
    val rtt: Long? get() = samples.minByOrNull { it.rtt }?.rtt
    val measured: Boolean get() = samples.isNotEmpty()

    fun sample(sentAt: Long, hostAt: Long, receivedAt: Long): Boolean {
        val rtt = receivedAt - sentAt
        if (rtt < 0 || rtt > MAX_RTT_MS) return false
        samples.addLast(Sample(hostAt - (sentAt + rtt / 2), rtt))
        while (samples.size > keep) samples.removeFirst()
        return true
    }

    /** Before any pong: assume a message took [ONE_WAY_GUESS_MS] to come. Replaced by the first real exchange. */
    fun provisional(hostAt: Long, receivedAt: Long) {
        if (samples.isEmpty()) provisional = hostAt + ONE_WAY_GUESS_MS - receivedAt
    }

    fun hostNow(localNow: Long): Long? = offset?.let { localNow + it }

    fun reset() { samples.clear(); provisional = null }

    companion object {
        const val MAX_RTT_MS = 10_000L
        const val ONE_WAY_GUESS_MS = 60L
    }
}

/** Where the host is: its position at a moment of its clock, and whether it plays. */
data class Anchor(val entry: String?, val positionMs: Long, val at: Long, val playing: Boolean, val buffering: Boolean) {
    fun positionAt(hostNow: Long): Long = if (playing && !buffering) positionMs + (hostNow - at).coerceAtLeast(0) else positionMs
}

/**
 * When a guest's player has drifted from the host's: only by a seek, never by changing the speed (that would be
 * heard). A drift must last [confirm] checks in a row above [thresholdMs]; after a load or a seek the player settles
 * first. Each seek lands [leadMs] ahead, the time a seek takes on this phone, learned from what is left after one.
 */
class DriftJudge(
    val thresholdMs: Long = 450L,
    private val confirm: Int = 2,
    private val settleMs: Long = 2_500L,
    private val cooldownMs: Long = 4_000L
) {
    var leadMs: Long = 150L
        private set
    private var over = 0
    private var settledAt = Long.MIN_VALUE
    private var lastSeekAt = Long.MIN_VALUE / 2
    private var measuringLead = false
    private var forced = false
    var lastDrift: Long? = null
        private set

    /** A title was loaded at [now]: it settles before any correction. */
    fun loaded(now: Long) { settledAt = now + settleMs; over = 0; measuringLead = false; lastDrift = null }

    /** The host jumped (a seek, a resume): the next check corrects at once, without waiting for confirmation. */
    fun force() { forced = true }

    /**
     * The position to seek to, or null to leave the player alone. [local] is the guest's position, [expected] the
     * host's position at the same instant.
     */
    fun judge(local: Long, expected: Long, now: Long, durationMs: Long): Long? {
        val drift = local - expected
        lastDrift = drift
        if (now < settledAt) return null
        if (measuringLead) {
            // What is left after a seek tells how long a seek takes here: half of it is learned each time.
            measuringLead = false
            if (kotlin.math.abs(drift) < 3_000) leadMs = (leadMs - drift / 2).coerceIn(0L, MAX_LEAD_MS)
        }
        val far = kotlin.math.abs(drift) > thresholdMs
        if (!far) { over = 0; forced = false; return null }
        if (!forced && now - lastSeekAt < cooldownMs) return null
        over++
        if (!forced && over < confirm) return null
        val target = expected + leadMs
        if (durationMs > 0 && target >= durationMs - 1_000) return null
        over = 0; forced = false
        lastSeekAt = now; settledAt = now + SEEK_SETTLE_MS; measuringLead = true
        return target.coerceAtLeast(0L)
    }

    companion object {
        const val MAX_LEAD_MS = 1_500L
        const val SEEK_SETTLE_MS = 1_500L
    }
}

/** A token bucket: at most [capacity] at once, refilled at [perSecond]. */
class RateBucket(private val capacity: Double, private val perSecond: Double) {
    private var tokens = capacity
    private var at = Long.MIN_VALUE

    fun take(now: Long, cost: Double = 1.0): Boolean {
        if (at != Long.MIN_VALUE) tokens = (tokens + (now - at).coerceAtLeast(0) / 1000.0 * perSecond).coerceAtMost(capacity)
        at = now
        if (tokens < cost) return false
        tokens -= cost
        return true
    }
}
