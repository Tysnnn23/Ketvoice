package com.ketvoice.desktop.platform

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.SecureRandom

/**
 * One Ketvoice per user. The first launch holds a lock on `Data\instance.lock` and listens on a
 * loopback port written, with a random token, to `Data\instance.json` (the data folder is the
 * user's own). A later launch finds the lock taken, sends its arguments there and exits; the
 * first one brings its window forward and gets the arguments (files to open).
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val infoFile: File,
    private val token: String,
) : AutoCloseable {
    @Volatile private var handler: ((List<String>) -> Unit)? = null
    private val pending = ArrayList<List<String>>()

    init {
        Thread({ serve() }, "ketvoice-instance").apply { isDaemon = true; start() }
    }

    /** Called (on a background thread) with each later launch's arguments; earlier ones are replayed. */
    fun onActivate(block: (List<String>) -> Unit) {
        val queued = synchronized(pending) { handler = block; pending.toList().also { pending.clear() } }
        queued.forEach(block)
    }

    private fun serve() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            runCatching {
                socket.use { s ->
                    s.soTimeout = 2000
                    val line = s.getInputStream().bufferedReader(Charsets.UTF_8).readLimitedLine(MAX_MESSAGE) ?: return@use
                    val args = InstanceMessage.decode(line, token) ?: return@use
                    s.getOutputStream().write("ok\n".toByteArray())
                    val h = synchronized(pending) { handler ?: run { pending += args; null } }
                    h?.invoke(args)
                }
            }.onFailure { AppLog.w("instance", "message: $it") }
        }
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { infoFile.delete() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    sealed interface Result {
        class Primary(val instance: SingleInstance) : Result
        /** Another instance has the arguments; this launch should exit. */
        data object Forwarded : Result
        /** The lock is taken but the other instance did not answer: run anyway, alone. */
        data object Unreachable : Result
    }

    companion object {
        const val MAX_MESSAGE = 64 * 1024

        fun acquire(dataDir: File, args: List<String>, beforeSend: () -> Unit = {}): Result {
            dataDir.mkdirs()
            val lockFile = File(dataDir, "instance.lock")
            val infoFile = File(dataDir, "instance.json")
            val channel = RandomAccessFile(lockFile, "rw").channel
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null } catch (e: java.io.IOException) {
                AppLog.w("instance", "lock: $e"); null
            }
            if (lock != null) {
                val server = ServerSocket()
                server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8)
                val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                writeInfo(infoFile, server.localPort, token)
                return Result.Primary(SingleInstance(channel, lock, server, infoFile, token))
            }
            channel.close()
            // The first launch may still be starting: give it a few tries to publish its port.
            repeat(10) { attempt ->
                if (send(infoFile, args, beforeSend)) return Result.Forwarded
                Thread.sleep(if (attempt < 3) 100 else 300)
            }
            return Result.Unreachable
        }

        private fun writeInfo(file: File, port: Int, token: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("port", port).put("token", token).put("pid", ProcessHandle.current().pid()).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }

        private fun send(infoFile: File, args: List<String>, beforeSend: () -> Unit): Boolean = runCatching {
            val info = JSONObject(infoFile.readText())
            val port = info.getInt("port")
            val token = info.getString("token")
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000)
                s.soTimeout = 3000
                beforeSend()
                s.getOutputStream().write((InstanceMessage.encode(token, args) + "\n").toByteArray(Charsets.UTF_8))
                s.getOutputStream().flush()
                s.getInputStream().bufferedReader().readLine() == "ok"
            }
        }.getOrDefault(false)

        private fun java.io.BufferedReader.readLimitedLine(max: Int): String? {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c < 0) return sb.takeIf { it.isNotEmpty() }?.toString()
                if (c == '\n'.code) return sb.toString()
                if (sb.length >= max) return null
                sb.append(c.toChar())
            }
        }
    }
}

/** One line of JSON: `{"token": …, "args": […]}`. A wrong token or a malformed line is dropped. */
object InstanceMessage {
    fun encode(token: String, args: List<String>): String =
        JSONObject().put("token", token).put("args", JSONArray(args)).toString()

    fun decode(line: String, token: String): List<String>? = runCatching {
        val o = JSONObject(line)
        if (o.optString("token") != token || token.isEmpty()) return null
        val a = o.optJSONArray("args") ?: JSONArray()
        List(a.length()) { a.getString(it) }
    }.getOrNull()

    /** The arguments that name files or links to open (flags like `--autostart` are not). */
    fun openable(args: List<String>): List<String> = args.filter { it.isNotBlank() && !it.startsWith("--") }
}
