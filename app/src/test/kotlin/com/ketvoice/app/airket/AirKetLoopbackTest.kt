package com.ketvoice.app.airket

import com.ketvoice.app.handoff.LanBoundary
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random

/** The real protocol over 127.0.0.1 (the only place loopback is allowed: [AirKetGuard.loopbackForTests], debug builds only). */
class AirKetLoopbackTest {
    private val loopback = LanBoundary(InetAddress.getByName("127.0.0.1"), 8)
    private val closers = mutableListOf<AutoCloseable>()
    private val dirs = mutableListOf<File>()
    private val backup = """{"kind":"ketvoice-personal-backup","version":1}""".toByteArray()
    private val fileA = ByteArray(130_000).also { Random(1).nextBytes(it) }
    private val fileB = ByteArray(3).also { it[0] = 7 }
    private val entries = listOf(AirKetEntry(AirKetEntry.Kind.Audio, "a.mp3", fileA.size.toLong()), AirKetEntry(AirKetEntry.Kind.Lrc, "a.lrc", fileB.size.toLong()))

    private val source = object : AirKetSource {
        override fun hello() = JSONObject().put("name", "Old phone").put("backupBytes", backup.size)
        override fun document(id: String): ByteArray? = when (id) {
            "backup" -> backup
            "manifest" -> AirKetManifest(entries).encode()
            else -> null
        }
        override fun file(id: String): AirKetFile? = when (id) {
            entries[0].id -> AirKetFile(fileA.size.toLong()) { from -> ByteArrayInputStream(fileA, from.toInt(), fileA.size - from.toInt()) }
            entries[1].id -> AirKetFile(fileB.size.toLong()) { from -> ByteArrayInputStream(fileB, from.toInt(), fileB.size - from.toInt()) }
            else -> null
        }
    }

    @Before fun allowLoopback() { AirKetGuard.loopbackForTests = true }
    @After fun cleanUp() {
        AirKetGuard.loopbackForTests = false
        closers.forEach { runCatching { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun sender(code: String = "246810", clock: () -> Long = System::currentTimeMillis, states: MutableList<AirKetState> = mutableListOf()) =
        AirKetSender(loopback, code, source, clock, onState = { synchronized(states) { states += it } }).also { closers += it }

    private fun connect(port: Int, code: String) = AirKetClient.connect(loopback, InetAddress.getByName("127.0.0.1"), port, code).also { closers += it }

    private fun staging() = AirKetStaging(createTempDir("airket-loop")).also { dirs += it.dir }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!condition()) { check(System.currentTimeMillis() < until) { "Timed out: $what" }; Thread.sleep(10) }
    }

    @Test fun aBackupAndTwoFilesCrossIntactAndVerified() {
        val states = mutableListOf<AirKetState>()
        val server = sender(states = states)
        val client = connect(server.port, "246810")
        assertEquals("Old phone", client.hello.getString("name"))
        assertArrayEquals(backup, client.document("backup"))
        val manifest = AirKetManifest.decode(client.document("manifest")!!)
        assertEquals(entries, manifest.entries)
        val staging = staging()
        var progress = 0L
        assertEquals(Fetch.Ok, client.file(entries[0], staging.part(entries[0])) { progress += it })
        assertEquals(Fetch.Ok, client.file(entries[1], staging.part(entries[1])) { progress += it })
        assertEquals(fileA.size + fileB.size.toLong(), progress)
        assertArrayEquals(fileA, staging.part(entries[0]).readBytes())
        assertArrayEquals(fileB, staging.part(entries[1]).readBytes())
        assertNull(client.document("nothing"))
        assertEquals(Fetch.Missing, client.file(AirKetEntry(AirKetEntry.Kind.Audio, "none.mp3", 1), staging.part(entries[0])) {})
        client.close()
        waitFor("done") { synchronized(states) { AirKetState.Done in states } }
    }

    @Test fun aWrongCodeNeverRevealsAnythingAndTheFifthLocksTheSender() {
        val states = mutableListOf<AirKetState>()
        val server = sender(states = states)
        repeat(4) { n ->
            val refused = runCatching { connect(server.port, "000000") }.exceptionOrNull()
            assertEquals(AirKetRefused.Reason.WrongCode, (refused as AirKetRefused).reason)
            waitFor("try ${n + 1}") { server.wrongTries == n + 1 }
        }
        // the right code still works after four wrong ones
        connect(server.port, "246810").close()
        val locked = sender("111111")
        repeat(AirKetCode.MAX_WRONG) { runCatching { connect(locked.port, "999999") }; Thread.sleep(30) }
        waitFor("locked") { locked.currentState == AirKetState.Locked }
        val after = runCatching { connect(locked.port, "111111") }.exceptionOrNull()
        assertNotNull("a locked sender must refuse even the right code", after)
    }

    @Test fun anExpiredCodeIsRefusedAndTheSenderCloses() {
        var now = 1_000_000L
        val states = mutableListOf<AirKetState>()
        val server = sender(clock = { now }, states = states)
        assertTrue(server.millisLeft in 1..AirKetCode.LIFETIME_MS)
        now += AirKetCode.LIFETIME_MS + 1
        assertNotNull(runCatching { connect(server.port, "246810") }.exceptionOrNull())
        waitFor("expired") { synchronized(states) { AirKetState.Expired in states } }
        assertEquals(0, server.millisLeft)
    }

    @Test fun anAlteredFrameOnTheWireIsRefusedByTheReceiver() {
        val server = sender()
        val proxy = flippingProxy(server.port, flipAt = 1500)
        val client = connect(proxy, "246810")
        val staging = staging()
        val outcome = runCatching { client.file(entries[0], staging.part(entries[0])) {} }
        assertTrue("a flipped byte must not be accepted", outcome.isFailure)
        assertFalse("nothing unverified may be left as a finished file", staging.verified(entries[0]).exists())
    }

    /** Relays bytes to the sender, changing one byte of what the sender says after [flipAt] bytes. */
    private fun flippingProxy(target: Int, flipAt: Int): Int {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { closers += it }
        Thread {
            val client = listener.accept().also { closers += it }
            val upstream = Socket(InetAddress.getByName("127.0.0.1"), target).also { closers += it }
            Thread { runCatching { client.getInputStream().copyTo(upstream.getOutputStream()) } }.apply { isDaemon = true }.start()
            runCatching {
                var seen = 0
                val buffer = ByteArray(4096)
                while (true) {
                    val n = upstream.getInputStream().read(buffer); if (n < 0) break
                    if (seen <= flipAt && flipAt < seen + n) buffer[flipAt - seen] = (buffer[flipAt - seen].toInt() xor 0x55).toByte()
                    seen += n
                    client.getOutputStream().write(buffer, 0, n); client.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true }.start()
        return listener.localPort
    }

    @Test fun aTransferResumesFromThePartAndABadPrefixIsCaughtByTheDigest() {
        val server = sender()
        val client = connect(server.port, "246810")
        val staging = staging()
        val e = entries[0]
        // resume: the first 70 000 bytes are already there
        staging.part(e).writeBytes(fileA.copyOf(70_000))
        var asked = 0L
        assertEquals(Fetch.Ok, client.file(e, staging.part(e)) { asked += it })
        assertEquals(fileA.size - 70_000L, asked)
        assertArrayEquals(fileA, staging.part(e).readBytes())
        // a part that is not the start of the file: the whole file fails its SHA-256 and is dropped, then fetched again whole
        staging.part(e).writeBytes(ByteArray(70_000) { 9 })
        assertEquals(Fetch.Bad, client.file(e, staging.part(e)) {})
        assertFalse(staging.part(e).exists())
        assertEquals(Fetch.Ok, client.file(e, staging.part(e)) {})
        assertArrayEquals(fileA, staging.part(e).readBytes())
        // a part that is already complete is just verified
        assertEquals(Fetch.Ok, client.file(e, staging.part(e)) {})
    }

    @Test fun aPublicOrOtherSubnetPeerAndLoopbackOutsideTheTestHookAreRefused() {
        val lan = LanBoundary(InetAddress.getByName("192.168.1.10"), 24)
        assertTrue(AirKetGuard.accepts(lan, InetAddress.getByName("192.168.1.77")))
        assertFalse(AirKetGuard.accepts(lan, InetAddress.getByName("8.8.8.8")))
        assertFalse(AirKetGuard.accepts(lan, InetAddress.getByName("192.168.2.77")))
        assertFalse(AirKetGuard.accepts(lan, InetAddress.getByName("169.254.1.1")))
        assertFalse(AirKetGuard.accepts(lan, InetAddress.getByName("127.0.0.1")))
        AirKetGuard.loopbackForTests = false
        assertFalse(AirKetGuard.accepts(loopback, InetAddress.getByName("127.0.0.1")))
        assertTrue(runCatching { AirKetSender(loopback, "000000", source) }.isFailure)
        assertTrue(runCatching { AirKetClient.connect(loopback, InetAddress.getByName("127.0.0.1"), 40000, "000000") }.isFailure)
    }
}
