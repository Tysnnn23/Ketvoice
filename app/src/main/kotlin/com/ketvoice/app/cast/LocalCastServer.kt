package com.ketvoice.app.cast

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Downloads and local music for the Cast device (7.5): a tiny HTTP server on the phone, bound to its Wi-Fi
 * address only (never every interface), alive only while casting. Every address carries a random 128-bit token
 * and an id the phone handed out; anything else is a plain 404. GET and HEAD, with byte ranges for seeking.
 */
class LocalCastServer(private val address: InetAddress, private val open: (String) -> Opened?) {
    class Opened(val length: Long, val stream: InputStream)
    private data class Entry(val uri: String, val contentType: String)

    val token: String = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
    private val entries = ConcurrentHashMap<String, Entry>()
    private val ids = ConcurrentHashMap<String, String>()
    private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "ketvoice-cast-file").apply { isDaemon = true } }

    val boundAddress: InetAddress get() = address
    val port: Int get() = socket?.localPort ?: -1

    fun start(): LocalCastServer {
        val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(address, 0), 8) }
        socket = server
        Thread({ acceptLoop(server) }, "ketvoice-cast-server").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        entries.clear(); ids.clear()
        workers.shutdownNow()
    }

    /** The address the receiver fetches for [uri]; the same file keeps the same address for this session. */
    fun publish(uri: String, contentType: String): String {
        val id = ids.getOrPut(uri) { hex(ByteArray(8).also { SecureRandom().nextBytes(it) }) }
        entries[id] = Entry(uri, contentType)
        val host = address.hostAddress
        return "http://$host:$port/$token/$id"
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            runCatching { workers.execute { client.use(::serve) } }.onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val request = reader.readLine()?.split(' ') ?: return
        var range: String? = null
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("range:", ignoreCase = true)) range = header.substringAfter(':').trim()
        }
        val out = client.getOutputStream()
        val method = request.getOrNull(0).orEmpty()
        if (method != "GET" && method != "HEAD") return status(out, 405, "Method Not Allowed")
        val entry = idFor(request.getOrNull(1).orEmpty(), token)?.let(entries::get) ?: return status(out, 404, "Not Found")
        val opened = runCatching { open(entry.uri) }.getOrNull() ?: return status(out, 404, "Not Found")
        opened.stream.use { input -> send(out, input, opened.length, entry.contentType, parseRange(range, opened.length), method == "HEAD") }
    }

    private fun send(out: OutputStream, input: InputStream, length: Long, type: String, range: LongRange?, headOnly: Boolean) {
        val head = StringBuilder()
        if (range != null) head.append("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes ${range.first}-${range.last}/$length\r\n")
        else head.append("HTTP/1.1 200 OK\r\n")
        val count = range?.let { it.last - it.first + 1 } ?: length
        head.append("Content-Type: $type\r\nAccept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n")
        if (count >= 0) head.append("Content-Length: $count\r\n")
        out.write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
        if (headOnly) return out.flush()
        range?.let { skipFully(input, it.first) }
        copy(input, out, count)
        out.flush()
    }

    private fun copy(input: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = if (count < 0) Long.MAX_VALUE else count
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            left -= read
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) { if (input.read() < 0) return; left-- } else left -= skipped
        }
    }

    private fun status(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    companion object {
        private val idPattern = Regex("[0-9a-f]{16}")

        /** The id in "/<token>/<id>", or null: wrong token, extra segments, dots, encoded characters, bad id. */
        fun idFor(path: String, token: String): String? {
            val clean = path.substringBefore('?').substringBefore('#')
            val parts = clean.split('/')
            if (parts.size != 3 || parts[0].isNotEmpty()) return null
            if (!MessageDigest.isEqual(parts[1].toByteArray(), token.toByteArray())) return null
            return parts[2].takeIf { idPattern.matches(it) }
        }

        /** "bytes=a-b", "bytes=a-" or "bytes=-n"; null for no range, several ranges or one outside the file. */
        fun parseRange(header: String?, length: Long): LongRange? {
            if (header == null || length <= 0 || !header.startsWith("bytes=") || ',' in header) return null
            val spec = header.removePrefix("bytes=").trim()
            val start = spec.substringBefore('-').trim()
            val end = spec.substringAfter('-', "").trim()
            val range = when {
                start.isEmpty() -> end.toLongOrNull()?.takeIf { it > 0 }?.let { maxOf(0, length - it)..(length - 1) }
                else -> start.toLongOrNull()?.let { s -> s..(end.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)) }
            } ?: return null
            return range.takeIf { it.first in 0 until length && it.last >= it.first }
        }

        /** The phone's Wi-Fi (or Ethernet) IPv4 address: private, not loopback, not a mobile-data interface. */
        fun pickAddress(candidates: List<Pair<String, InetAddress>>): InetAddress? {
            val usable = candidates.filter { (name, ip) ->
                ip is Inet4Address && ip.isSiteLocalAddress && !ip.isLoopbackAddress &&
                    listOf("rmnet", "ccmni", "pdp", "v4-rmnet", "tun", "ppp").none { name.startsWith(it) }
            }
            return (usable.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") || it.first.startsWith("ap") }
                ?: usable.firstOrNull())?.second
        }

        fun lanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { nic -> nic.inetAddresses.toList().map { nic.name to it } }
        }.getOrNull()?.let(::pickAddress)

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
