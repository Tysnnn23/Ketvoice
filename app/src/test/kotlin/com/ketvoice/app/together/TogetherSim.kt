package com.ketvoice.app.together

import com.ketvoice.app.data.Track
import org.json.JSONObject
import java.util.PriorityQueue
import java.util.Random

/** A player that only keeps time: what [TogetherSession] sees of Ketvoice's player in host tests. */
class FakePlayer(private val clock: () -> Long, var loadDelayMs: Long = 0L, var seekLatencyMs: Long = 0L) : PlayerPort {
    val queue = ArrayList<Pair<String, Track>>()
    var index = -1
    private var basePos = 0L
    private var baseAt = 0L
    private var readyAt = 0L
    var wanted = false
    var failed = false
    val calls = ArrayList<String>()
    private var serial = 0

    val current: Track? get() = queue.getOrNull(index)?.second
    private fun playing(now: Long) = wanted && now >= readyAt && current != null
    fun position(now: Long = clock()): Long =
        if (playing(now)) basePos + (now - maxOf(baseAt, readyAt)) else basePos

    private fun freeze(now: Long) { basePos = position(now); baseAt = now }

    override fun view(): PlayerView {
        val now = clock()
        return PlayerView(current, queue.getOrNull(index)?.first, position(now), wanted, playing(now),
            wanted && now < readyAt, failed, current?.durationMs ?: 0L, queue.drop(index + 1))
    }

    override fun load(track: Track, queue: List<Track>, positionMs: Long, playing: Boolean) {
        calls += "load ${track.id} @$positionMs ${if (playing) "play" else "pause"}"
        this.queue.clear(); queue.forEach { this.queue += "e${++serial}" to it }; index = 0
        basePos = positionMs; baseAt = clock(); readyAt = clock() + loadDelayMs; wanted = playing
    }

    fun hostPlay(tracks: List<Track>, at: Int = 0, positionMs: Long = 0L) {
        queue.clear(); tracks.forEach { queue += "h${++serial}" to it }; index = at
        basePos = positionMs; baseAt = clock(); readyAt = clock(); wanted = true
    }

    override fun setPlaying(on: Boolean) { calls += if (on) "play" else "pause"; freeze(clock()); wanted = on; readyAt = clock() }
    override fun seek(positionMs: Long) { calls += "seek $positionMs"; basePos = positionMs; baseAt = clock(); readyAt = clock() + seekLatencyMs }
    override fun next() { calls += "next"; if (index < queue.lastIndex) { index++; basePos = 0; baseAt = clock(); readyAt = clock() } }
    override fun previous() { calls += "previous"; basePos = 0; baseAt = clock() }
    override fun enqueue(track: Track): String? { val id = "q${++serial}"; queue += id to track; calls += "enqueue ${track.id}"; return id }
}

/**
 * A room in a simulated world: one time line, messages that take [latency] (with jitter) to arrive, phones whose
 * clocks are far apart, and the JSON written and read again on the way like on the real relay.
 */
class TogetherSim(seed: Long = 7L) {
    var t = 0L
        private set
    private var serial = 0L
    private data class Event(val at: Long, val serial: Long, val action: () -> Unit)
    private val events = PriorityQueue<Event>(compareBy<Event>({ it.at }, { it.serial }))
    val random = Random(seed)
    var latency: () -> Long = { 60L + random.nextInt(40) }
    val nodes = ArrayList<Node>()
    /** Every broadcast seen, for the rate checks. */
    val wire = ArrayList<Pair<Long, JSONObject>>()

    inner class Node(val name: String, val clockOffset: Long, role: TogetherRole, code: String, fingerprint: String? = null,
                     policy: Policy = Policy(), key: HostKey? = if (role == TogetherRole.Host) HostKey.generate() else null) {
        val id = TogetherWire.memberId(random)
        fun now() = t + clockOffset
        val player = FakePlayer(::now)
        var joined = false
        val uis = ArrayList<TogetherUi>()
        val session = TogetherSession(role, id, name, code, player, ::send, { uis += it }, key, fingerprint, policy, random = random)
        val ui get() = session.ui

        fun send(payload: JSONObject): Boolean {
            if (!joined) return false
            val text = payload.toString()
            wire += t to JSONObject(text)
            nodes.filter { it !== this && it.joined }.forEach { other ->
                at(t + latency()) { if (other.joined) other.session.receive(JSONObject(text), other.now()) }
            }
            return true
        }

        fun join() { joined = true; session.joined(now()); presenceChanged() }
        fun drop() { joined = false; session.down(now()); presenceChanged() }
    }

    fun at(time: Long, action: () -> Unit) { events += Event(time, serial++, action) }

    fun add(node: Node): Node {
        nodes += node
        fun tick() { if (!node.session.ended) node.session.tick(node.now()); at(t + 500) { tick() } }
        at(t + random.nextInt(500)) { tick() }
        return node
    }

    fun presenceChanged() {
        val members = nodes.filter { it.joined }.map { PresenceMember(it.id, it.name, it.session.role == TogetherRole.Host) }
        nodes.filter { it.joined }.forEach { n -> at(t + latency()) { if (n.joined) n.session.presence(members, n.now()) } }
    }

    fun run(ms: Long) {
        val end = t + ms
        while (true) {
            val next = events.peek() ?: break
            if (next.at > end) break
            events.poll()
            t = next.at
            next.action()
        }
        t = end
    }
}
