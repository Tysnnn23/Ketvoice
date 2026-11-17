package com.ketvoice.app.together

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What the session hears from the room. Called on the thread [RealtimeTransport] was given (the main thread). */
interface TransportListener {
    fun onJoined()
    fun onDown(reason: String)
    /** The server will not let this phone in (a project setting, an invalid key): reconnecting would not help. */
    fun onRefused(reason: String)
    fun onBroadcast(payload: JSONObject)
    fun onPresence(members: List<PresenceMember>)
}

interface TogetherTransport {
    fun start()
    /** Sends a broadcast to the others of the room; false when not joined (it is not queued). */
    fun send(payload: JSONObject): Boolean
    fun close()
}

/** Frames of Supabase Realtime, Phoenix's JSON protocol 1.0.0 (`{topic, event, payload, ref, join_ref}`). */
object PhoenixFrames {
    const val MAX_FRAME = 64_000
    data class Frame(val topic: String, val event: String, val payload: JSONObject, val ref: String?)

    fun join(topic: String, ref: String, presenceKey: String, token: String): String = JSONObject()
        .put("topic", topic).put("event", "phx_join").put("ref", ref).put("join_ref", ref)
        .put("payload", JSONObject().put("access_token", token).put("config", JSONObject()
            .put("broadcast", JSONObject().put("self", false).put("ack", false))
            .put("presence", JSONObject().put("key", presenceKey))
            .put("private", false))).toString()

    fun leave(topic: String, ref: String, joinRef: String): String = JSONObject()
        .put("topic", topic).put("event", "phx_leave").put("ref", ref).put("join_ref", joinRef).put("payload", JSONObject()).toString()

    fun heartbeat(ref: String): String = JSONObject()
        .put("topic", "phoenix").put("event", "heartbeat").put("ref", ref).put("payload", JSONObject()).toString()

    fun broadcast(topic: String, ref: String, joinRef: String, event: String, payload: JSONObject): String = JSONObject()
        .put("topic", topic).put("event", "broadcast").put("ref", ref).put("join_ref", joinRef)
        .put("payload", JSONObject().put("type", "broadcast").put("event", event).put("payload", payload)).toString()

    fun track(topic: String, ref: String, joinRef: String, meta: JSONObject): String = JSONObject()
        .put("topic", topic).put("event", "presence").put("ref", ref).put("join_ref", joinRef)
        .put("payload", JSONObject().put("type", "presence").put("event", "track").put("payload", meta)).toString()

    fun parse(text: String): Frame? = runCatching {
        if (text.length > MAX_FRAME) return null
        val o = JSONObject(text)
        Frame(o.getString("topic"), o.getString("event"), o.optJSONObject("payload") ?: JSONObject(),
            o.opt("ref")?.takeIf { it != JSONObject.NULL }?.toString())
    }.getOrNull()
}

/** Who is in the room, from Phoenix presence (`presence_state` replaces, `presence_diff` adds and removes). */
class PresenceBook {
    private val metas = LinkedHashMap<String, MutableList<JSONObject>>()
    private val key = Regex("[A-Za-z0-9_-]{8,40}")

    fun state(payload: JSONObject) {
        metas.clear()
        add(payload)
    }

    fun diff(payload: JSONObject) {
        payload.optJSONObject("joins")?.let(::add)
        payload.optJSONObject("leaves")?.let { leaves ->
            leaves.keys().forEach { k ->
                val gone = leaves.optJSONObject(k)?.optJSONArray("metas") ?: return@forEach
                val refs = (0 until gone.length()).mapNotNull { gone.optJSONObject(it)?.optString("phx_ref") }.toSet()
                metas[k]?.let { list -> list.removeAll { it.optString("phx_ref") in refs }; if (list.isEmpty()) metas.remove(k) }
            }
        }
    }

    private fun add(source: JSONObject) {
        source.keys().forEach { k ->
            if (!key.matches(k) || (metas.size >= MAX && k !in metas)) return@forEach
            val list = source.optJSONObject(k)?.optJSONArray("metas") ?: return@forEach
            val target = metas.getOrPut(k) { ArrayList() }
            for (i in 0 until list.length()) {
                val meta = list.optJSONObject(i) ?: continue
                val ref = meta.optString("phx_ref")
                target.removeAll { it.optString("phx_ref") == ref }
                target += meta
            }
            if (target.isEmpty()) metas.remove(k)
        }
    }

    fun members(): List<PresenceMember> = metas.mapNotNull { (k, list) ->
        val meta = list.lastOrNull() ?: return@mapNotNull null
        PresenceMember(k, Names.clean(meta.optString("n")) ?: "?", meta.optString("r") == "h")
    }

    companion object { const val MAX = 64 }
}

object Backoff {
    private val steps = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    fun delay(attempt: Int, random: java.util.Random): Long {
        val base = steps[attempt.coerceIn(0, steps.lastIndex)]
        return (base * (0.8 + 0.4 * random.nextDouble())).toLong()
    }
}

/**
 * One room of Supabase Realtime over a WebSocket: a public broadcast and presence channel `realtime:ketogether:<code>`,
 * no table. It keeps the Phoenix heartbeat, reconnects with a growing, jittered delay, joins the room again and tracks
 * this phone's presence again; the session then asks the host for the state (see [TogetherSession.joined]).
 */
class RealtimeTransport(
    private val endpoint: String,
    private val apiKey: String,
    room: String,
    private val presenceKey: String,
    private val presenceMeta: JSONObject,
    private val listener: TransportListener,
    private val deliver: (Runnable) -> Unit,
    private val client: OkHttpClient,
    private val random: java.util.Random = java.util.Random()
) : TogetherTransport {
    private val topic = "realtime:$room"
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ketogether-realtime").apply { isDaemon = true }
    }
    private val refs = AtomicLong(0)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var joinRef: String? = null
    @Volatile private var joined = false
    @Volatile private var closed = false
    private var generation = 0
    private var attempt = 0
    private var joinErrors = 0
    private var heartbeatRef: String? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var timeout: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null
    private val book = PresenceBook()

    private fun ref() = refs.incrementAndGet().toString()

    override fun start() { worker.execute { connect() } }

    private fun connect() {
        if (closed) return
        val gen = ++generation
        val url = "$endpoint?apikey=$apiKey&vsn=1.0.0"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { worker.execute { opened(gen, webSocket) } }
            override fun onMessage(webSocket: WebSocket, text: String) { worker.execute { message(gen, text) } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { worker.execute { drop(gen, "closed $code") } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                worker.execute { drop(gen, t.javaClass.simpleName + (response?.code?.let { " $it" } ?: "")) }
            }
        })
    }

    private fun opened(gen: Int, ws: WebSocket) {
        if (gen != generation || closed) return
        val ref = ref()
        joinRef = ref
        ws.send(PhoenixFrames.join(topic, ref, presenceKey, apiKey))
        timeout?.cancel(false)
        timeout = worker.schedule({ if (gen == generation && !joined) drop(gen, "join timeout") }, JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun message(gen: Int, text: String) {
        if (gen != generation || closed) return
        val frame = PhoenixFrames.parse(text) ?: return
        if (frame.topic == "phoenix") {
            if (frame.event == "phx_reply" && frame.ref == heartbeatRef) heartbeatRef = null
            return
        }
        if (frame.topic != topic) return
        when (frame.event) {
            "phx_reply" -> if (frame.ref != null && frame.ref == joinRef && !joined) {
                if (frame.payload.optString("status") == "ok") joinedOk(gen)
                else joinRefused(gen, frame.payload.optJSONObject("response")?.optString("reason").orEmpty())
            }
            "broadcast" -> if (joined && frame.payload.optString("event") == TogetherWire.EVENT) {
                val inner = frame.payload.optJSONObject("payload") ?: return
                deliver(Runnable { if (!closed) listener.onBroadcast(inner) })
            }
            "presence_state" -> { book.state(frame.payload); presenceChanged() }
            "presence_diff" -> { book.diff(frame.payload); presenceChanged() }
            "phx_error", "phx_close" -> drop(gen, frame.event)
            "system" -> if (frame.payload.optString("status") == "error") drop(gen, "system " + frame.payload.optString("message").take(80))
        }
    }

    private fun presenceChanged() {
        val members = book.members()
        deliver(Runnable { if (!closed) listener.onPresence(members) })
    }

    private fun joinedOk(gen: Int) {
        joined = true; attempt = 0; joinErrors = 0
        timeout?.cancel(false)
        val join = joinRef ?: return
        socket?.send(PhoenixFrames.track(topic, ref(), join, presenceMeta))
        heartbeatRef = null
        heartbeat?.cancel(false)
        heartbeat = worker.scheduleWithFixedDelay({
            if (gen != generation) return@scheduleWithFixedDelay
            if (heartbeatRef != null) { drop(gen, "heartbeat timeout"); return@scheduleWithFixedDelay }
            val ref = ref(); heartbeatRef = ref
            socket?.send(PhoenixFrames.heartbeat(ref))
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        deliver(Runnable { if (!closed) listener.onJoined() })
    }

    private fun joinRefused(gen: Int, reason: String) {
        joinErrors++
        val permanent = refusal(reason) || joinErrors >= MAX_JOIN_ERRORS
        if (permanent) {
            closed = true
            stopSocket()
            deliver(Runnable { listener.onRefused(reason.ifBlank { "join refused" }) })
            worker.shutdown()
        } else drop(gen, "join error $reason")
    }

    private fun drop(gen: Int, reason: String) {
        if (gen != generation || closed) return
        generation++ // anything still arriving from this socket is ignored
        val wasJoined = joined
        stopSocket()
        if (wasJoined || attempt == 0) deliver(Runnable { if (!closed) listener.onDown(reason) })
        val wait = Backoff.delay(attempt++, random)
        reconnect?.cancel(false)
        reconnect = worker.schedule({ connect() }, wait, TimeUnit.MILLISECONDS)
    }

    private fun stopSocket() {
        joined = false; joinRef = null; heartbeatRef = null
        heartbeat?.cancel(false); timeout?.cancel(false)
        socket?.cancel(); socket = null
    }

    override fun send(payload: JSONObject): Boolean {
        if (!joined || closed) return false
        val join = joinRef ?: return false
        val text = PhoenixFrames.broadcast(topic, ref(), join, TogetherWire.EVENT, payload)
        if (text.length > PhoenixFrames.MAX_FRAME) return false
        return socket?.send(text) == true
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { worker.execute {
            val ws = socket
            val join = joinRef
            if (ws != null && join != null && joined) ws.send(PhoenixFrames.leave(topic, ref(), join))
            joined = false
            heartbeat?.cancel(false); timeout?.cancel(false); reconnect?.cancel(false)
            ws?.close(1000, "bye")
            socket = null
            worker.schedule({ worker.shutdownNow() }, 2, TimeUnit.SECONDS)
        } }
    }

    companion object {
        const val HEARTBEAT_MS = 25_000L
        const val JOIN_TIMEOUT_MS = 10_000L
        const val MAX_JOIN_ERRORS = 5

        /** Join refusals that come from the project's settings, not from a passing problem. */
        fun refusal(reason: String): Boolean {
            val r = reason.lowercase()
            return listOf("unauthorized", "not allowed", "permission", "private", "invalid", "jwt", "forbidden").any { it in r }
        }
    }
}
