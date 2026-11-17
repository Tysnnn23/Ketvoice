package com.ketvoice.app.airket

import com.ketvoice.app.handoff.LanBoundary
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** What the sender hands out: a summary, two documents held in memory and files read from disk. */
internal interface AirKetSource {
    fun hello(): JSONObject
    fun document(id: String): ByteArray?
    fun file(id: String): AirKetFile?
}

internal class AirKetFile(val size: Long, val open: (Long) -> InputStream) {
    val sha256: String by lazy { open(0).use { AirKetDigest.of(it) } }
}

internal enum class AirKetState { Waiting, Paired, Sending, Done, Expired, Locked, Cancelled, Failed }

/**
 * The sender's server. One receiver at a time; a handshake that fails or stalls counts as a wrong try, the
 * fifth closes the server for good; the code stops working after [AirKetCode.LIFETIME_MS] unless a receiver
 * paired before. See AirKetProtocol.kt for the protocol.
 */
internal class AirKetSender(private val boundary: LanBoundary, private val code: String, private val source: AirKetSource,
                            private val clock: () -> Long = System::currentTimeMillis,
                            private val onState: (AirKetState) -> Unit = {},
                            private val onSent: (Long) -> Unit = {}) : AutoCloseable {
    private val server: ServerSocket
    val port: Int
    @Volatile private var expiresAt = clock() + AirKetCode.LIFETIME_MS
    private val wrong = AtomicInteger()
    @Volatile private var closed = false
    @Volatile private var paired = false
    @Volatile private var state = AirKetState.Waiting
    @Volatile private var active: Socket? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ketvoice-airket-send").apply { isDaemon = true } }

    init {
        AirKetGuard.requireUsable(boundary)
        server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(boundary.address, 0), 4) }
        port = server.localPort
        Thread({ acceptLoop() }, "ketvoice-airket-accept").apply { isDaemon = true; start() }
    }

    val wrongTries: Int get() = wrong.get()
    val currentState: AirKetState get() = state
    val millisLeft: Long get() = (expiresAt - clock()).coerceAtLeast(0)

    private fun set(next: AirKetState) { state = next; onState(next) }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { if (closed) return else continue }
            if (!AirKetGuard.accepts(boundary, socket.inetAddress)) { runCatching { socket.close() }; continue }
            if (!paired && clock() > expiresAt) { runCatching { socket.close() }; expire(); continue }
            if (active != null) { runCatching { socket.close() }; continue }
            active = socket
            runCatching { worker.execute { session(socket) } }.onFailure { active = null; runCatching { socket.close() } }
        }
    }

    private fun expire() { if (!paired && !closed) { set(AirKetState.Expired); close() } }

    /** Called by a timer: closes the sender when the code ran out. */
    fun tick() { if (!paired && clock() > expiresAt) expire() }

    private fun session(socket: Socket) {
        try {
            socket.soTimeout = AirKetFrames.HANDSHAKE_TIMEOUT_MS
            val channel = try { AirKetHandshake.serve(socket.getInputStream().buffered(), socket.getOutputStream().buffered(), code) }
            catch (_: Exception) { failedTry(); return }
            socket.soTimeout = AirKetFrames.SOCKET_TIMEOUT_MS * 3
            paired = true; set(AirKetState.Paired)
            channel.send(AirKetFrames.HELLO, source.hello())
            serve(channel)
        } catch (_: Exception) {
            // handled below
        } finally {
            // Free the slot before the socket closes: the receiver's next try must not find the sender busy.
            active = null
            runCatching { socket.close() }
            // A session that broke off leaves the code working for another [AirKetCode.LIFETIME_MS]: the receiver resumes.
            if (paired && !closed && state != AirKetState.Done) { paired = false; expiresAt = clock() + AirKetCode.LIFETIME_MS; set(AirKetState.Failed) }
        }
    }

    private fun failedTry() {
        if (wrong.incrementAndGet() >= AirKetCode.MAX_WRONG) { set(AirKetState.Locked); close() }
    }

    private fun serve(channel: FrameChannel) {
        while (!closed) {
            val (type, body) = try { channel.receive() } catch (_: java.io.EOFException) { return }
            when (type) {
                AirKetFrames.BYE -> { set(AirKetState.Done); return }
                AirKetFrames.DOC, AirKetFrames.FILE -> {
                    set(AirKetState.Sending)
                    val request = JSONObject(String(body, Charsets.UTF_8))
                    if (!answer(channel, type == AirKetFrames.DOC, request.getString("id"), request.optLong("offset", 0))) return
                }
                else -> { channel.send(AirKetFrames.ERROR, JSONObject().put("reason", "unexpected")); return }
            }
        }
    }

    private fun answer(channel: FrameChannel, doc: Boolean, id: String, requested: Long): Boolean {
        val file = if (doc) source.document(id)?.let { bytes -> AirKetFile(bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) } }
            else source.file(id)
        if (file == null) { channel.send(AirKetFrames.ERROR, JSONObject().put("reason", "missing")); return true }
        val from = if (requested in 0..file.size) requested else 0L
        channel.send(AirKetFrames.BEGIN, JSONObject().put("id", id).put("size", file.size).put("sha256", file.sha256).put("offset", from))
        file.open(from).use { input ->
            val buffer = ByteArray(AirKetFrames.CHUNK)
            while (!closed) {
                val n = input.read(buffer)
                if (n < 0) break
                channel.send(AirKetFrames.DATA, buffer, n)
                onSent(n.toLong())
            }
        }
        if (closed) return false
        channel.send(AirKetFrames.END)
        return true
    }

    /** Stops listening and ends any session; the receiver keeps what it already verified. */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        runCatching { active?.close() }
        worker.shutdownNow()
        if (state == AirKetState.Waiting || state == AirKetState.Paired || state == AirKetState.Sending) set(AirKetState.Cancelled)
    }
}

internal sealed interface Fetch {
    data object Ok : Fetch
    /** The bytes did not match their SHA-256 (or the size): the part is gone, the file can be asked again. */
    data object Bad : Fetch
    data object Missing : Fetch
}

/** The receiver's side of a session. */
internal class AirKetClient private constructor(private val socket: Socket, private val channel: FrameChannel, val hello: JSONObject) : AutoCloseable {
    @Volatile private var cancelled = false

    fun document(id: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val result = fetch(AirKetFrames.DOC, id, 0, MessageDigest.getInstance("SHA-256"), out, {})
        return if (result == Fetch.Ok) out.toByteArray() else null
    }

    /** Fetches [entry] into its staging part (appending after what is there), hashing as it goes. */
    fun file(entry: AirKetEntry, part: File, onBytes: (Long) -> Unit): Fetch {
        val digest = MessageDigest.getInstance("SHA-256")
        var from = if (part.isFile) part.length().coerceAtMost(entry.size) else 0L
        if (from > 0) part.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var left = from
            while (left > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); if (n < 0) break; digest.update(buffer, 0, n); left -= n }
            if (left > 0) from = 0
        }
        if (from == 0L) { digest.reset(); part.delete() }
        part.parentFile?.mkdirs()
        val result = java.io.FileOutputStream(part, true).buffered().use { out -> fetch(AirKetFrames.FILE, entry.id, from, digest, out, onBytes, part, entry.size) }
        if (result != Fetch.Ok) part.delete()
        return result
    }

    private fun fetch(type: Int, id: String, offset: Long, digest: MessageDigest, out: java.io.OutputStream, onBytes: (Long) -> Unit,
                      part: File? = null, expectedSize: Long = -1): Fetch {
        channel.send(type, JSONObject().put("id", id).put("offset", offset))
        val (kind, body) = channel.receive()
        if (kind == AirKetFrames.ERROR) return Fetch.Missing
        require(kind == AirKetFrames.BEGIN)
        val begin = JSONObject(String(body, Charsets.UTF_8))
        val size = begin.getLong("size")
        require(begin.getString("id") == id && size in 0..AirKetEntry.MAX_FILE && (expectedSize < 0 || size == expectedSize))
        if (begin.getLong("offset") != offset) return Fetch.Bad
        var received = offset
        while (true) {
            if (cancelled) throw java.util.concurrent.CancellationException()
            val (frame, payload) = channel.receive()
            if (frame == AirKetFrames.END) break
            require(frame == AirKetFrames.DATA && received + payload.size <= size)
            out.write(payload); digest.update(payload); received += payload.size; onBytes(payload.size.toLong())
        }
        out.flush()
        return if (received == size && Hkdf.hex(digest.digest()) == begin.getString("sha256")) Fetch.Ok else Fetch.Bad
    }

    /** Ends the session politely and drops the connection; what was verified stays in the staging folder. */
    fun cancel() { cancelled = true; runCatching { socket.close() } }

    override fun close() {
        runCatching { channel.send(AirKetFrames.BYE) }
        runCatching { socket.close() }
    }

    companion object {
        /** Connects to a sender found on the Wi-Fi, proves the code and reads its summary. */
        fun connect(boundary: LanBoundary, host: InetAddress, port: Int, code: String): AirKetClient {
            require(AirKetGuard.accepts(boundary, host) && port in 1024..65535)
            val socket = Socket()
            try {
                socket.bind(InetSocketAddress(boundary.address, 0))
                socket.connect(InetSocketAddress(host, port), 4000)
                socket.soTimeout = AirKetFrames.SOCKET_TIMEOUT_MS
                val channel = AirKetHandshake.join(socket.getInputStream().buffered(), socket.getOutputStream().buffered(), code)
                return AirKetClient(socket, channel, channel.receiveJson(AirKetFrames.HELLO))
            } catch (e: Exception) { runCatching { socket.close() }; throw e }
        }
    }
}
