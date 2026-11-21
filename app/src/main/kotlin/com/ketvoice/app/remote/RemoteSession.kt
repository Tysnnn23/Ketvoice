package com.ketvoice.app.remote

import com.ketvoice.app.together.PresenceMember
import org.json.JSONObject

/** This device's player, as phone control drives it. Called on the thread the session runs on. */
interface RemotePort {
    fun playback(): LocalPlayback
    fun run(op: RemoteOp)
    fun play(handoff: RemoteHandoff)
    fun pause()
}

/**
 * Phone control seen from one device: who else of the account is in the room, what each plays, and the
 * commands between them. It has no thread or socket of its own ([RemoteControl] gives it both), so host
 * tests drive it directly. Everything that goes out is sealed by [seal]; everything that comes in is opened
 * by it first, so a message without the account's key, stale or replayed, does nothing.
 */
class RemoteSession(
    private val name: String,
    private val kind: String,
    private val seal: RemoteSeal,
    private val send: (JSONObject) -> Boolean,
    private val port: RemotePort,
    private val clock: () -> Long,
    private val publish: (List<RemoteDevice>) -> Unit
) {
    private val devices = LinkedHashMap<String, RemoteDevice>()
    private var present = emptyMap<String, String>()
    private var lastKey: String? = null
    private var lastSentAt = 0L
    private var lastSent: LocalPlayback? = null

    fun joined() {
        send(seal.seal("hi"))
        announce(force = true)
    }

    fun presence(members: List<PresenceMember>) {
        present = members.filter { it.id != seal.me }.associate { it.id to it.name }
        devices.keys.retainAll(present.keys)
        present.forEach { (id, n) -> if (id !in devices) devices[id] = RemoteDevice(id, n, "phone", null) }
        emit()
    }

    fun receive(payload: JSONObject) {
        val body = seal.open(payload) ?: return
        val from = body.getString("f")
        when (body.optString("t")) {
            "hi" -> announce(force = true)
            "st" -> RemoteWire.device(from, body, present[from])?.let { devices[from] = it; emit() }
            "cmd" -> RemoteWire.op(body)?.let { port.run(it); announce(force = true) }
            "take" -> {
                val handoff = RemoteWire.handoff(port.playback()) ?: return
                if (send(seal.seal("give", RemoteWire.handoff(handoff), to = from))) { port.pause(); announce(force = true) }
            }
            "give" -> RemoteWire.handoff(body)?.let { port.play(it); announce(force = true) }
        }
    }

    /** The player changed here (a title, play/pause, a seek, the volume, the queue). */
    fun localChanged() = announce(force = false)

    /** Every few seconds: the state again now and then, so a seek shows and a late device catches up. */
    fun tick() = announce(force = false)

    fun control(device: String, op: RemoteOp): Boolean = send(seal.seal("cmd", RemoteWire.op(op), to = device))

    /** « Lire sur … »: the other device takes the queue where it stands; this one pauses. */
    fun sendTo(device: String): Boolean {
        val handoff = RemoteWire.handoff(port.playback()) ?: return false
        if (!send(seal.seal("give", RemoteWire.handoff(handoff), to = device))) return false
        port.pause()
        announce(force = true)
        return true
    }

    /** « Reprendre ici »: asks the other device for its queue; it answers with a handoff and pauses. */
    fun takeFrom(device: String): Boolean = send(seal.seal("take", to = device))

    private fun announce(force: Boolean) {
        if (present.isEmpty() && !force) return
        val p = port.playback()
        val now = clock()
        val key = listOf(p.current?.id, p.playing, p.index, (p.volume * 50).toInt(), p.queue.size,
            p.queue.getOrNull(p.index + 1)?.id).joinToString("|")
        val previous = lastSent
        val jumped = previous != null && previous.playing == p.playing &&
            kotlin.math.abs(p.positionMs - (previous.positionMs + if (p.playing) now - lastSentAt else 0L)) > SEEK_SLACK_MS
        if (!force && key == lastKey && !jumped && now - lastSentAt < REFRESH_MS) return
        if (send(seal.seal("st", RemoteWire.state(name, kind, p, now)))) {
            lastKey = key; lastSentAt = now; lastSent = p
        }
    }

    private fun emit() = publish(devices.values.toList())

    companion object {
        const val REFRESH_MS = 15_000L
        const val SEEK_SLACK_MS = 2_500L
    }
}
