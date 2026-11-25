package com.ketvoice.desktop.connect

import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID

/*
 * Discord Rich Presence over Discord's local IPC, in pure Kotlin: no native library. The Discord app listens on the
 * named pipe `\\.\pipe\discord-ipc-0` (up to 9) on Windows, a Unix socket elsewhere. Each frame is a little-endian
 * opcode, a little-endian length and that many bytes of UTF-8 JSON. Only the public application id is sent.
 */

/** Discord IPC frames: `[op: int32 LE][length: int32 LE][JSON]`. */
object DiscordFrame {
    const val HANDSHAKE = 0
    const val FRAME = 1
    const val CLOSE = 2
    const val PING = 3
    const val PONG = 4
    const val MAX_BYTES = 64 * 1024

    fun encode(op: Int, json: JSONObject): ByteArray {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_BYTES) { "frame too large" }
        return ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).put(body).array()
    }

    /** The opcode and body length of an 8-byte header. */
    fun header(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size == 8)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return b.int to b.int
    }

    fun handshake(clientId: String): ByteArray = encode(HANDSHAKE, JSONObject().put("v", 1).put("client_id", clientId))

    fun setActivity(activity: JSONObject?, pid: Long, nonce: String = UUID.randomUUID().toString()): ByteArray =
        encode(FRAME, JSONObject().put("cmd", "SET_ACTIVITY").put("nonce", nonce)
            .put("args", JSONObject().put("pid", pid).put("activity", activity ?: JSONObject.NULL)))
}

/** One open pipe or socket to the Discord app. */
interface IpcPipe : Closeable {
    fun write(bytes: ByteArray)
    /** Exactly [count] bytes, or null when they did not come within [timeoutMs]. */
    fun read(count: Int, timeoutMs: Long): ByteArray?
}

/** Where the Discord app may listen on this system, first to last. */
object DiscordPipes {
    fun candidates(windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
                   env: (String) -> String? = System::getenv): List<String> {
        if (windows) return (0..9).map { "\\\\.\\pipe\\discord-ipc-$it" }
        val roots = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").mapNotNull { env(it)?.takeIf(String::isNotBlank) } + "/tmp"
        val dirs = roots.distinct().flatMap { listOf(it, "$it/app/com.discordapp.Discord", "$it/snap.discord") }
        return dirs.flatMap { dir -> (0..9).map { "$dir/discord-ipc-$it" } }
    }

    fun open(path: String): IpcPipe? = runCatching {
        if (path.startsWith("\\\\.\\pipe\\")) WindowsPipe(RandomAccessFile(path, "rw"))
        else if (File(path).exists()) UnixPipe(SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(path)) })
        else null
    }.getOrNull()
}

/**
 * A Windows named pipe opened as a file. A blocked read would also block writes on the same handle, so reads only
 * start once the bytes are there (`length()` of a pipe is what is waiting in it).
 */
private class WindowsPipe(private val file: RandomAccessFile) : IpcPipe {
    override fun write(bytes: ByteArray) = file.write(bytes)

    override fun read(count: Int, timeoutMs: Long): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        while (runCatching { file.length() }.getOrDefault(count.toLong()) < count) {
            if (System.currentTimeMillis() > end) return null
            Thread.sleep(20)
        }
        return ByteArray(count).also(file::readFully)
    }

    override fun close() { runCatching { file.close() } }
}

private class UnixPipe(private val channel: SocketChannel) : IpcPipe {
    init { channel.configureBlocking(false) }

    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) if (channel.write(buffer) == 0) Thread.sleep(5)
    }

    override fun read(count: Int, timeoutMs: Long): ByteArray? {
        val buffer = ByteBuffer.allocate(count)
        val end = System.currentTimeMillis() + timeoutMs
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw java.io.EOFException()
            if (buffer.hasRemaining()) { if (System.currentTimeMillis() > end) return null; Thread.sleep(10) }
        }
        return buffer.array()
    }

    override fun close() { runCatching { channel.close() } }
}

/**
 * A connection to the Discord app as the Ketvoice application. [connect] tries every pipe and keeps the first that
 * answers the handshake with READY; [send] sets (or with null clears) the activity and reads Discord's answer.
 * Not thread-safe: one worker thread drives it.
 */
class DiscordIpc(private val clientId: String, private val opener: (String) -> IpcPipe? = DiscordPipes::open,
                 private val paths: () -> List<String> = { DiscordPipes.candidates() }) : Closeable {
    private var pipe: IpcPipe? = null
    /** The Discord user's name from READY, when it gave one. */
    var user: String? = null
        private set
    val connected: Boolean get() = pipe != null

    fun connect(): Boolean {
        if (pipe != null) return true
        for (path in paths()) {
            val p = opener(path) ?: continue
            val ready = runCatching { p.write(DiscordFrame.handshake(clientId)); readFrame(p) }.getOrNull()
            if (ready != null && ready.optString("evt") == "READY") {
                pipe = p
                user = ready.optJSONObject("data")?.optJSONObject("user")?.let { it.optString("global_name").ifBlank { it.optString("username") } }
                    ?.takeIf { it.isNotBlank() }
                return true
            }
            p.close()
        }
        return false
    }

    /** True when Discord took it. Any failure closes the connection (Discord was closed): [connect] again later. */
    fun send(activity: JSONObject?, pid: Long = ProcessHandle.current().pid()): Boolean {
        val p = pipe ?: return false
        val answer = runCatching { p.write(DiscordFrame.setActivity(activity, pid)); readFrame(p) }.getOrNull()
        if (answer == null) { close(); return false }
        return answer.optString("evt") != "ERROR"
    }

    /** The next JSON frame, answering pings on the way; null on timeout, a close or anything malformed. */
    private fun readFrame(p: IpcPipe): JSONObject? {
        repeat(4) {
            val head = p.read(8, 4_000) ?: return null
            val (op, length) = DiscordFrame.header(head)
            if (length !in 0..DiscordFrame.MAX_BYTES) return null
            val body = String(p.read(length, 4_000) ?: return null, Charsets.UTF_8)
            when (op) {
                DiscordFrame.PING -> p.write(DiscordFrame.encode(DiscordFrame.PONG, JSONObject(body)))
                DiscordFrame.FRAME -> return JSONObject(body)
                else -> return null
            }
        }
        return null
    }

    override fun close() {
        pipe?.close()
        pipe = null
        user = null
    }
}
