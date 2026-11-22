package com.ketvoice.app.together

import com.ketvoice.app.data.Track
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.math.abs

/** What the session needs of the player; [Ketogether] binds it to [com.ketvoice.app.player.Player], tests to a fake. */
interface PlayerPort {
    fun view(): PlayerView
    fun load(track: Track, queue: List<Track>, positionMs: Long, playing: Boolean)
    fun setPlaying(on: Boolean)
    fun seek(positionMs: Long)
    fun next()
    fun previous()
    /** Adds a title at the end of the queue; its entry id, or null. */
    fun enqueue(track: Track): String?
}

data class PlayerView(
    val current: Track?, val entry: String?, val positionMs: Long,
    /** The listener wants it to play (Player's playWhenReady). */
    val playWhenReady: Boolean,
    /** It really plays: the position moves. */
    val playing: Boolean,
    val buffering: Boolean, val failed: Boolean, val durationMs: Long,
    val upcoming: List<Pair<String, Track>> = emptyList()
)

/** Someone in the channel, as presence reports them. Presence is not signed: it only names people. */
data class PresenceMember(val id: String, val name: String, val host: Boolean)

/**
 * One Ketogether session seen from this phone, host or guest. It has no thread or timer of its own: [Ketogether]
 * feeds it the transport's events and calls [tick] twice a second, always with the time of a monotonic clock, so
 * the whole protocol runs the same in host tests with a fake transport and a fake player.
 */
class TogetherSession(
    val role: TogetherRole,
    val me: String,
    private val myName: String,
    val code: String,
    private val port: PlayerPort,
    private val send: (JSONObject) -> Boolean,
    private val onUi: (TogetherUi) -> Unit,
    private val hostKey: HostKey? = null,
    private val expectedFingerprint: String? = null,
    initialPolicy: Policy = Policy(),
    private val shareable: (Track) -> TrackRef? = { TrackRef.of(it) },
    private val random: java.util.Random = SecureRandom()
) {
    init { require((role == TogetherRole.Host) == (hostKey != null)) }

    val sid: String = if (role == TogetherRole.Host) TogetherWire.memberId(random) else ""
    var ui = TogetherUi(TogetherPhase.Connecting, role, code, hostKey?.fingerprint ?: expectedFingerprint.orEmpty(),
        members = listOf(Member(me, myName, role == TogetherRole.Host, me = true)),
        hostName = if (role == TogetherRole.Host) myName else "", policy = initialPolicy)
        private set
    val ended: Boolean get() = ui.phase == TogetherPhase.Ended

    /** Set when another host already uses this code: [Ketogether] starts again with a new one. */
    var collision = false
        private set

    // ---------------------------------------------------------------- shared
    private val outbound = RateBucket(10.0, 8.0)
    private val inbound = HashMap<String, RateBucket>()
    private var presence: List<PresenceMember> = emptyList()
    private var joinedAt: Long? = null
    private var downSince: Long? = null
    private var noticeUntil = 0L
    private var reactionSerial = 0L
    private val lastReactionBy = HashMap<String, Long>()

    private fun publish(next: TogetherUi) { ui = next; onUi(next) }

    private fun notice(text: String, now: Long) { noticeUntil = now + NOTICE_MS; publish(ui.copy(notice = text)) }

    private fun out(msg: TogetherMsg, now: Long, force: Boolean = false): Boolean {
        if (!force && !outbound.take(now)) return false
        return runCatching { send(TogetherWire.wrap(me, msg, hostKey)) }.getOrDefault(false)
    }

    /** The transport joined the room (again, after a reconnection). */
    fun joined(now: Long) {
        if (ended) return
        joinedAt = now; downSince = null
        if (role == TogetherRole.Host) {
            stateDue = true
            if (presence.isEmpty() && aloneSince == null) aloneSince = now
            if (ui.phase == TogetherPhase.Reconnecting) publish(ui.copy(phase = TogetherPhase.Live))
        } else {
            out(TogetherMsg.Hello(myName), now, force = true)
            pingsLeft = BURST_PINGS; nextPingAt = now
            if (lastHostAt == null) lastHostAt = now
            if (ui.phase == TogetherPhase.Reconnecting) publish(ui.copy(phase = if (hostId != null) TogetherPhase.Live else TogetherPhase.Connecting))
        }
    }

    /** The connection dropped; the transport reconnects on its own. */
    fun down(now: Long) {
        if (ended) return
        if (downSince == null) downSince = now
        if (ui.phase != TogetherPhase.Connecting || role == TogetherRole.Guest) publish(ui.copy(phase = TogetherPhase.Reconnecting))
    }

    fun presence(members: List<PresenceMember>, now: Long) {
        if (ended) return
        presence = members.filter { it.id != me }.take(MAX_MEMBERS)
        if (role == TogetherRole.Host && ui.phase == TogetherPhase.Connecting && presence.any { it.host }) collision = true
        if (role == TogetherRole.Host) {
            if (presence.isEmpty()) { if (aloneSince == null) aloneSince = now } else aloneSince = null
            seatPresence(now)
        }
        publishMembers()
    }

    private fun publishMembers() {
        val mine = Member(me, myName, role == TogetherRole.Host, me = true, syncMs = if (role == TogetherRole.Guest) drift.lastDrift?.let(::abs) else null)
        val others = presence.filter { !blocked(it.id) }
            .map { Member(it.id, it.name, host = role == TogetherRole.Guest && it.id == hostId, syncMs = seats[it.id]?.syncMs) }
        // 8.4: who dropped a moment ago keeps a place, shown « absent ».
        val away = (if (role == TogetherRole.Host) seats.filter { it.value.awaySince != null }.map { it.key to it.value.name } else guestAway)
            .filter { (id, _) -> id != me && presence.none { it.id == id } }.map { (id, name) -> Member(id, name, host = false, away = true) }
        val all = (listOf(mine) + others + away).sortedWith(compareByDescending<Member> { it.host }.thenByDescending { it.me }.thenBy { it.away })
        publish(ui.copy(members = all, hostName = if (role == TogetherRole.Guest) (others.firstOrNull { it.host }?.name ?: ui.hostName) else myName))
    }

    /** A broadcast of the room. Anything malformed, too frequent or not for this role is dropped. */
    fun receive(payload: JSONObject, now: Long) {
        if (ended) return
        val envelope = runCatching { TogetherWire.unwrap(payload) }.getOrNull() ?: return
        if (envelope.from == me) return
        if (inbound.size > MAX_MEMBERS * 4 && envelope.from !in inbound) return
        if (!inbound.getOrPut(envelope.from) { RateBucket(12.0, 4.0) }.take(now)) return
        val msg = runCatching { TogetherCodec.decode(envelope.body) }.getOrNull() ?: return
        val signedByHost = envelope.signature != null
        when (msg) {
            is TogetherMsg.React -> reaction(envelope.from, msg.emoji, now)
            is TogetherMsg.State, is TogetherMsg.Tick, is TogetherMsg.Pong, is TogetherMsg.End, is TogetherMsg.Answer -> {
                if (!signedByHost) return
                if (role == TogetherRole.Host) {
                    // Another host signs in this room: the code is taken. Once live, it is only ignored.
                    if (ui.phase == TogetherPhase.Connecting && envelope.key != hostKey?.publicKey) collision = true
                    return
                }
                if (!trustHost(envelope)) return
                hostMessage(envelope.from, msg, now)
            }
            is TogetherMsg.Remove -> if (signedByHost && role == TogetherRole.Guest && trustHost(envelope)) hostMessage(envelope.from, msg, now)
            else -> if (role == TogetherRole.Host && !signedByHost && !blocked(envelope.from)) guestMessage(envelope.from, msg, now)
        }
    }

    private fun reaction(from: String, emoji: String, now: Long) {
        if (emoji !in TogetherCodec.reactions) return
        val last = lastReactionBy[from]
        if (last != null && now - last < REACTION_GAP_MS) return
        lastReactionBy[from] = now
        val name = if (from == me) myName else presence.firstOrNull { it.id == from }?.name ?: names[from] ?: "?"
        publish(ui.copy(reaction = Reaction(emoji, name, now, ++reactionSerial)))
    }

    fun react(emoji: String, now: Long) {
        if (ended || emoji !in TogetherCodec.reactions) return
        if (out(TogetherMsg.React(emoji), now)) reaction(me, emoji, now)
    }

    /** Called twice a second. */
    fun tick(now: Long) {
        if (ended) return
        if (ui.notice != null && now >= noticeUntil) publish(ui.copy(notice = null))
        if (role == TogetherRole.Host) hostTick(now) else guestTick(now)
    }

    // ---------------------------------------------------------------- host
    private var seq = 0L
    private var stateDue = true
    private var lastStateAt = Long.MIN_VALUE / 2
    private var lastSentAt = Long.MIN_VALUE / 2
    private var lastSent: Anchor? = null
    private var lastSentQv = 0
    private var lastSentPlaying = false
    private var aloneSince: Long? = null
    private val by = HashMap<String, String>()
    private val names = HashMap<String, String>()
    private val pending = ArrayList<Proposal>()

    private fun hostView(view: PlayerView): HostSnapshot {
        val current = view.current
        val ref = current?.let(shareable)
        val upcoming = view.upcoming.take(TogetherCodec.MAX_UPCOMING).map { (entry, track) -> QueueItem(entry, shareable(track), by[entry]) }
        val buffering = view.playWhenReady && (view.buffering || !view.playing)
        val anchorEntry = view.entry?.takeIf { it.length <= 40 }
        val qv = listOf(anchorEntry, ref?.id, current != null && ref == null, upcoming.map { Triple(it.entry, it.ref?.id, it.by) },
            pending.map { it.id }, ui.policy).hashCode()
        return HostSnapshot(ref, anchorEntry, current != null && ref == null, upcoming, buffering, qv)
    }

    private class HostSnapshot(val ref: TrackRef?, val entry: String?, val unavailable: Boolean, val upcoming: List<QueueItem>,
                               val buffering: Boolean, val qv: Int)

    private fun hostTick(now: Long) {
        val since = downSince
        if (since != null && now - since > HOST_OFFLINE_END_MS) { finish("connection", now, announce = false); return }
        dropLongAway(now)
        aloneSince?.let { if (now - it > EMPTY_ROOM_MS) { finish("expired", now, announce = true); return } }
        if (ui.phase == TogetherPhase.Connecting) {
            val joined = joinedAt ?: return
            if (now - joined < COLLISION_WINDOW_MS || collision) return
            publish(ui.copy(phase = TogetherPhase.Live))
            stateDue = true
        }
        if (downSince != null) return
        val view = port.view()
        val snap = hostView(view)
        val previous = lastSent
        val jumped = previous != null && previous.entry == snap.entry && previous.playing && view.playWhenReady &&
            kotlin.math.abs(view.positionMs - previous.positionAt(now)) > JUMP_MS
        val changed = previous == null || previous.entry != snap.entry || previous.playing != view.playWhenReady ||
            previous.buffering != snap.buffering || snap.qv != lastSentQv || jumped ||
            (!view.playWhenReady && kotlin.math.abs(view.positionMs - previous.positionMs) > JUMP_MS)
        noteHostChanges(view, snap, now)
        publishHost(view, snap)
        when {
            (stateDue || changed) && now - lastStateAt >= MIN_STATE_GAP_MS -> sendState(view, snap, now)
            now - lastStateAt >= FULL_STATE_MS -> sendState(view, snap, now)
            now - lastSentAt >= TICK_MS -> {
                val msg = TogetherMsg.Tick(sid, ++seq, now, snap.entry, view.positionMs.coerceIn(0, 86_400_000),
                    view.playWhenReady, snap.buffering, snap.qv)
                if (out(msg, now)) { lastSentAt = now; lastSent = Anchor(snap.entry, view.positionMs, now, view.playWhenReady, snap.buffering) }
            }
        }
    }

    private fun publishHost(view: PlayerView, snap: HostSnapshot) {
        val next = ui.copy(nowPlaying = snap.ref, available = !snap.unavailable, playing = view.playWhenReady,
            upcoming = snap.upcoming, pending = pending.toList())
        if (next != ui) publish(next)
    }

    private fun sendState(view: PlayerView, snap: HostSnapshot, now: Long) {
        val msg = TogetherMsg.State(sid, ++seq, now, myName, snap.ref, snap.entry, !snap.unavailable,
            view.positionMs.coerceIn(0, 86_400_000), view.playWhenReady, snap.buffering, snap.upcoming, pending.toList(), ui.policy, snap.qv,
            seats.filter { it.value.awaySince != null }.map { it.key to it.value.name }, ui.log)
        if (out(msg, now)) {
            stateDue = false; lastStateAt = now; lastSentAt = now; lastSentQv = snap.qv; lastSentPlaying = view.playWhenReady
            lastSent = Anchor(snap.entry, view.positionMs, now, view.playWhenReady, snap.buffering)
        }
    }

    private fun guestMessage(from: String, msg: TogetherMsg, now: Long) {
        when (msg) {
            is TogetherMsg.Hello -> { names[from] = msg.name; stateDue = true }
            is TogetherMsg.Ping -> {
                out(TogetherMsg.Pong(sid, ++seq, from, msg.client, now), now)
                seats[from]?.let { seat -> msg.driftMs?.let { seat.syncMs = abs(it); publishMembers() } }
            }
            is TogetherMsg.Request -> {
                // A guest may only ask for playback, and only when the host lets everyone control.
                if (!ui.policy.everyoneControls) return
                // 8.4: said in the activity, once (the change it makes is not said again as the host's).
                record(msg.action, nameOf(from), now = now)
                cause = now + CAUSE_MS
                when (msg.action) {
                    "play" -> port.setPlaying(true)
                    "pause" -> port.setPlaying(false)
                    "next" -> port.next()
                    "previous" -> port.previous()
                    "seek" -> {
                        val duration = port.view().durationMs
                        port.seek(if (duration > 0) msg.positionMs.coerceIn(0, duration) else msg.positionMs)
                    }
                }
                stateDue = true
            }
            is TogetherMsg.Propose -> {
                val name = names[from] ?: presence.firstOrNull { it.id == from }?.name ?: "?"
                if (pending.any { it.id == msg.id }) return
                if (ui.policy.everyoneAdds) {
                    val entry = port.enqueue(msg.ref.toTrack())
                    if (entry != null) { by[entry] = name; record("add", name, msg.ref.title, now) }
                    out(TogetherMsg.Answer(sid, ++seq, from, msg.id, entry != null), now)
                } else {
                    if (pending.size >= TogetherCodec.MAX_PENDING || pending.count { it.from == from } >= MAX_PENDING_PER_MEMBER) {
                        out(TogetherMsg.Answer(sid, ++seq, from, msg.id, false), now); return
                    }
                    pending += Proposal(msg.id, from, name, msg.ref)
                }
                stateDue = true
            }
            else -> Unit
        }
    }

    fun accept(proposal: String, now: Long) = decide(proposal, true, now)
    fun refuse(proposal: String, now: Long) = decide(proposal, false, now)

    private fun decide(id: String, accepted: Boolean, now: Long) {
        if (role != TogetherRole.Host) return
        val p = pending.firstOrNull { it.id == id } ?: return
        pending.remove(p)
        val entry = if (accepted) port.enqueue(p.ref.toTrack()) else null
        if (entry != null) { by[entry] = p.fromName; record("add", p.fromName, p.ref.title, now) }
        out(TogetherMsg.Answer(sid, ++seq, p.from, p.id, entry != null), now, force = true)
        stateDue = true
    }

    fun setPolicy(policy: Policy, now: Long) {
        if (role != TogetherRole.Host || ended) return
        publish(ui.copy(policy = policy))
        if (policy.everyoneAdds) pending.toList().forEach { decide(it.id, true, now) }
        stateDue = true
    }

    /** The host passes over a title the guests cannot have. */
    fun skip() { if (role == TogetherRole.Host) port.next() }

    // ---------------------------------------------------------------- host: places, removals, activity (8.4)
    private class Seat(val name: String, var awaySince: Long? = null, var syncMs: Long? = null)
    /** The guests who hold a place, in their order of arrival, present or away. */
    private val seats = LinkedHashMap<String, Seat>()
    /** Taken out by the host (`removed`) or refused because the session was full (`full`), with when that was last said. */
    private val refused = HashMap<String, Pair<String, Long>>()
    private val activity = ArrayDeque<Activity>()
    /** Until when a change of the player was asked by a guest, and already said in the activity. */
    private var cause = Long.MIN_VALUE
    private var seenEntry: String? = null
    private var seenPlaying: Boolean? = null
    private var seenPosition = 0L
    private var seenDuration = 0L

    private fun blocked(id: String): Boolean = id in refused

    private fun nameOf(id: String): String = seats[id]?.name ?: names[id] ?: presence.firstOrNull { it.id == id }?.name ?: "?"

    private fun record(kind: String, name: String, title: String? = null, now: Long) {
        activity.addFirst(Activity(kind, name, title))
        while (activity.size > TogetherCodec.MAX_LOG) activity.removeLast()
        publish(ui.copy(log = activity.toList()))
        stateDue = true
    }

    /** Who arrived takes a place if one is free; who went away keeps theirs a while; who comes back finds it. */
    private fun seatPresence(now: Long) {
        val here = presence.filter { !it.host }
        for (p in here) {
            if (blocked(p.id)) { tellRefused(p.id, now); continue }
            val seat = seats[p.id]
            when {
                seat == null && seats.size + 1 >= ui.policy.limit -> { refused[p.id] = "full" to Long.MIN_VALUE / 2; tellRefused(p.id, now) }
                seat == null -> { seats[p.id] = Seat(p.name); record("join", p.name, now = now) }
                seat.awaySince != null -> { seat.awaySince = null; record("back", seat.name, now = now) }
            }
        }
        seats.forEach { (id, seat) -> if (seat.awaySince == null && here.none { it.id == id }) { seat.awaySince = now; record("away", seat.name, now = now) } }
    }

    private fun dropLongAway(now: Long) {
        val gone = seats.filter { (_, seat) -> seat.awaySince?.let { now - it > AWAY_KEEP_MS } == true }
        gone.forEach { (id, seat) -> seats.remove(id); record("leave", seat.name, now = now) }
        if (gone.isNotEmpty()) publishMembers()
    }

    private fun tellRefused(id: String, now: Long) {
        val (reason, at) = refused[id] ?: return
        if (now - at < REMOVE_RESEND_MS) return
        if (out(TogetherMsg.Remove(sid, ++seq, id, reason), now, force = true)) refused[id] = reason to now
    }

    /** The host takes [memberId] out of the session: their phone leaves it and they cannot come back into it. */
    fun remove(memberId: String, now: Long) {
        if (role != TogetherRole.Host || ended || memberId == me) return
        val name = nameOf(memberId)
        refused[memberId] = "removed" to Long.MIN_VALUE / 2
        seats.remove(memberId)
        pending.removeAll { it.from == memberId }
        tellRefused(memberId, now)
        record("removed", name, now = now)
        publishMembers()
    }

    /** The host's own pauses, resumes and skips, said in the activity (a guest's request already was). */
    private fun noteHostChanges(view: PlayerView, snap: HostSnapshot, now: Long) {
        val wasPlaying = seenPlaying
        if (wasPlaying != null && now > cause) {
            val skipped = seenEntry != null && snap.entry != null && seenEntry != snap.entry &&
                seenDuration > 0 && seenPosition < seenDuration - NATURAL_END_MS
            when {
                skipped -> record("next", myName, now = now)
                seenEntry == snap.entry && wasPlaying != view.playWhenReady -> record(if (view.playWhenReady) "play" else "pause", myName, now = now)
            }
        } else if (wasPlaying != null && (seenEntry != snap.entry || wasPlaying != view.playWhenReady)) cause = Long.MIN_VALUE
        seenPlaying = view.playWhenReady; seenEntry = snap.entry; seenPosition = view.positionMs; seenDuration = view.durationMs
    }

    // ---------------------------------------------------------------- guest
    private val clock = ClockOffset()
    private val drift = DriftJudge()
    private var hostKeyPinned: String? = null
    private var hostId: String? = null
    private var hostSid: String? = null
    private var lastSeq = -1L
    private var lastHostAt: Long? = null
    private var anchor: Anchor? = null
    private var hostQv = 0
    private var pingsLeft = 0
    private var nextPingAt = Long.MAX_VALUE
    private var helloAt = Long.MIN_VALUE / 2
    private var appliedEntry: String? = null
    private var appliedId: String? = null
    private var appliedPlaying: Boolean? = null
    private var loadedAt = 0L
    private var gaveUp: String? = null
    private var localWanted = false
    private val mine = HashMap<Long, Long>()
    private var guestAway: List<Pair<String, String>> = emptyList()

    private fun trustHost(envelope: TogetherWire.Envelope): Boolean {
        val key = envelope.key ?: return false
        val signature = envelope.signature ?: return false
        val pinned = hostKeyPinned
        if (pinned != null && key != pinned) return false
        if (pinned == null && expectedFingerprint != null && Fingerprint.of(key) != expectedFingerprint) return false
        if (pinned != null && envelope.from != hostId) return false
        if (!HostKey.verify(key, envelope.body, signature)) return false
        if (pinned == null) { hostKeyPinned = key; hostId = envelope.from; publishMembers() }
        return true
    }

    private fun hostMessage(from: String, msg: TogetherMsg, now: Long) {
        val (sid, seq) = when (msg) {
            is TogetherMsg.State -> msg.sid to msg.seq
            is TogetherMsg.Tick -> msg.sid to msg.seq
            is TogetherMsg.Pong -> msg.sid to msg.seq
            is TogetherMsg.End -> msg.sid to msg.seq
            is TogetherMsg.Answer -> msg.sid to msg.seq
            is TogetherMsg.Remove -> msg.sid to msg.seq
            else -> return
        }
        if (hostSid == null) hostSid = sid
        if (sid != hostSid || seq <= lastSeq) return // an old message played again
        lastSeq = seq
        lastHostAt = now
        when (msg) {
            is TogetherMsg.State -> {
                clock.provisional(msg.at, now)
                setAnchor(Anchor(msg.entry, msg.positionMs, msg.at, msg.playing, msg.buffering))
                hostQv = msg.qv
                val wasLive = ui.phase == TogetherPhase.Live
                guestAway = msg.away
                publish(ui.copy(phase = if (downSince == null) TogetherPhase.Live else ui.phase, hostName = msg.hostName,
                    nowPlaying = msg.current, available = msg.available, playing = msg.playing,
                    upcoming = msg.upcoming, pending = msg.pending, policy = msg.policy, log = msg.log))
                publishMembers()
                applyHost(now)
            }
            is TogetherMsg.Tick -> {
                clock.provisional(msg.at, now)
                if (msg.entry == anchor?.entry) setAnchor(Anchor(msg.entry, msg.positionMs, msg.at, msg.playing, msg.buffering))
                if (msg.qv != hostQv || msg.entry != anchor?.entry) askState(now)
                if (ui.playing != msg.playing) publish(ui.copy(playing = msg.playing))
                applyHost(now)
            }
            is TogetherMsg.Pong -> if (msg.to == me && mine.remove(msg.client) != null && msg.client <= now) {
                clock.sample(msg.client, msg.host, now)
                publish(ui.copy(offsetMs = clock.offset, rttMs = clock.rtt))
            }
            is TogetherMsg.Answer -> if (msg.to == me) notice(if (msg.accepted) "Titre ajouté à Ketogether" else "L'hôte n'a pas ajouté ce titre", now)
            is TogetherMsg.End -> finish(if (msg.reason == "expired") "expired" else "hostEnded", now, announce = false)
            is TogetherMsg.Remove -> if (msg.to == me) finish(msg.reason, now, announce = false)
            else -> Unit
        }
    }

    private fun setAnchor(next: Anchor) {
        val previous = anchor
        // A jump of the host (a seek, a resume after buffering) is followed at once, without waiting for confirmation.
        if (previous != null && previous.entry == next.entry) {
            val predicted = previous.positionAt(next.at)
            if (kotlin.math.abs(next.positionMs - predicted) > JUMP_MS || previous.buffering != next.buffering) drift.force()
        }
        anchor = next
    }

    private fun askState(now: Long) {
        if (now - helloAt < HELLO_GAP_MS) return
        helloAt = now
        out(TogetherMsg.Hello(myName), now)
    }

    private fun guestTick(now: Long) {
        val since = lastHostAt
        if (since != null) {
            val silent = now - since
            if (silent > HOST_LOST_MS) { finish(if (hostId == null) "notFound" else "hostLost", now, announce = false); return }
            if (hostId == null && silent > NOT_FOUND_MS && presence.none { it.host }) { finish("notFound", now, announce = false); return }
            if (hostId != null && silent > HOST_SILENT_MS && ui.notice == null && downSince == null) notice("L'hôte ne répond plus…", now)
        }
        if (downSince == null && now >= nextPingAt) {
            if (out(TogetherMsg.Ping(now, drift.lastDrift), now)) {
                mine[now] = now
                mine.keys.filter { now - it > ClockOffset.MAX_RTT_MS }.forEach { mine.remove(it) }
            }
            if (pingsLeft > 0) pingsLeft--
            nextPingAt = now + if (pingsLeft > 0) BURST_GAP_MS else PING_EVERY_MS
        }
        applyHost(now)
    }

    /** Brings this phone to where the host is. */
    private fun applyHost(now: Long) {
        val a = anchor ?: return
        val view = port.view()
        // The listener played again here after pausing on this phone: catch up with the host at once.
        if (view.playWhenReady && !localWanted) drift.force()
        localWanted = view.playWhenReady
        val ref = ui.nowPlaying
        if (ref == null || !ui.available) {
            if (appliedEntry != a.entry || appliedId != null) {
                appliedEntry = a.entry; appliedId = null; appliedPlaying = false
                if (view.playWhenReady) port.setPlaying(false)
            }
            return
        }
        val hostNow = clock.hostNow(now) ?: return
        val duration = ref.durationMs.takeIf { it > 0 } ?: view.durationMs
        val expected = a.positionAt(hostNow).let { if (duration > 0) it.coerceIn(0, duration) else it }
        if (view.current?.id != ref.id) {
            if (appliedId == ref.id && appliedEntry == a.entry) {
                // Asked already: give it time, then say so once instead of asking again and again.
                if (now - loadedAt > LOAD_GIVE_UP_MS && gaveUp != a.entry) { gaveUp = a.entry; notice("Ce titre ne se lance pas sur cet appareil", now) }
                return
            }
            val queue = listOf(ref.toTrack()) + ui.upcoming.mapNotNull { it.ref?.toTrack() }
            port.load(ref.toTrack(), queue, if (a.playing) expected + drift.leadMs else expected, a.playing)
            appliedEntry = a.entry; appliedId = ref.id; appliedPlaying = a.playing; loadedAt = now
            localWanted = a.playing
            drift.loaded(now)
            return
        }
        if (appliedId != ref.id || appliedEntry != a.entry) {
            // The same title already plays here (this phone moved on by itself, or the host played it again).
            appliedEntry = a.entry; appliedId = ref.id
            drift.loaded(now - 1_000)
        }
        if (appliedPlaying != a.playing) {
            appliedPlaying = a.playing
            if (view.playWhenReady != a.playing) port.setPlaying(a.playing)
            localWanted = a.playing
            if (a.playing) drift.force()
            return
        }
        if (view.failed) {
            if (gaveUp != a.entry) { gaveUp = a.entry; notice("Ce titre ne se lance pas sur cet appareil", now) }
            return
        }
        if (!a.playing || a.buffering || !view.playing || view.buffering) return
        val target = drift.judge(view.positionMs, expected, now, duration)
        if (drift.lastDrift != ui.driftMs) { publish(ui.copy(driftMs = drift.lastDrift)); publishMembers() }
        if (target != null) port.seek(target)
    }

    /**
     * A guest's transport button. True when the session took it (a request to the host, or a refusal); false lets
     * the player act on this phone alone (play and pause, when the host keeps the controls).
     */
    fun transport(action: String, positionMs: Long, now: Long): Boolean {
        if (role != TogetherRole.Guest || ended) return false
        if (ui.policy.everyoneControls) {
            if (!out(TogetherMsg.Request(action, positionMs.coerceIn(0, 86_400_000)), now)) notice("Patientez un instant", now)
            return true
        }
        if (action == "play" || action == "pause") return false
        notice("Seul l'hôte contrôle la lecture", now)
        return true
    }

    /** « Ajouter à Ketogether »: the host adds the title; a guest proposes it. */
    fun propose(track: Track, now: Long): Boolean {
        if (ended) return false
        val ref = shareable(track)
        if (ref == null) { notice("Ce titre ne peut pas être partagé", now); return false }
        if (role == TogetherRole.Host) {
            if (port.enqueue(track) == null) return false
            record("add", myName, ref.title, now)
            stateDue = true
            notice("Titre ajouté à Ketogether", now)
            return true
        }
        val id = TogetherWire.memberId(random).take(12)
        if (!out(TogetherMsg.Propose(id, ref), now)) { notice("Patientez un instant", now); return false }
        notice(if (ui.policy.everyoneAdds) "Titre proposé" else "Titre proposé · en attente de l'hôte", now)
        return true
    }

    /** Ends this phone's part. The host's end is sent to everyone. */
    fun finish(reason: String, now: Long, announce: Boolean = true) {
        if (ended) return
        if (role == TogetherRole.Host && announce) out(TogetherMsg.End(sid, ++seq, reason), now, force = true)
        publish(ui.copy(phase = TogetherPhase.Ended, endReason = reason, notice = null))
    }

    companion object {
        const val MAX_MEMBERS = 32
        const val NOTICE_MS = 4_000L
        const val REACTION_GAP_MS = 700L
        const val COLLISION_WINDOW_MS = 1_500L
        const val MIN_STATE_GAP_MS = 250L
        const val TICK_MS = 5_000L
        const val FULL_STATE_MS = 30_000L
        const val JUMP_MS = 1_500L
        const val EMPTY_ROOM_MS = 30 * 60_000L
        const val HOST_OFFLINE_END_MS = 10 * 60_000L
        const val MAX_PENDING_PER_MEMBER = 5
        const val BURST_PINGS = 4
        const val BURST_GAP_MS = 1_500L
        const val PING_EVERY_MS = 30_000L
        const val HELLO_GAP_MS = 3_000L
        const val HOST_SILENT_MS = 20_000L
        const val HOST_LOST_MS = 90_000L
        const val NOT_FOUND_MS = 12_000L
        const val LOAD_GIVE_UP_MS = 20_000L
        /** How long a guest who dropped keeps their place (8.4, « absent, place gardée »). */
        const val AWAY_KEEP_MS = 2 * 60_000L
        const val REMOVE_RESEND_MS = 5_000L
        const val CAUSE_MS = 2_000L
        const val NATURAL_END_MS = 4_000L
    }
}
