package com.ketvoice.app.remote

import com.ketvoice.app.data.Track
import com.ketvoice.app.together.PresenceMember
import com.ketvoice.app.together.TogetherTransport
import com.ketvoice.app.together.TransportListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Phone control's wire (shared with the Windows build): sealed messages, two devices driving each other. */
class RemoteControlTest {
    private val secret = RemoteKey.generate(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) })
    private var clock = 1_000_000L

    private class FakePort(var p: LocalPlayback) : RemotePort {
        val ran = mutableListOf<RemoteOp>()
        var played: RemoteHandoff? = null
        var paused = 0
        override fun playback() = p
        override fun run(op: RemoteOp) { ran += op }
        override fun play(handoff: RemoteHandoff) { played = handoff; p = p.copy(queue = handoff.queue, index = handoff.index, playing = handoff.play) }
        override fun pause() { paused++; p = p.copy(playing = false) }
    }

    private fun tracks(n: Int, local: Int = -1) = (0 until n).map { Track(if (it == local) "local:file:/m/$it.flac" else "tidal:$it", "Title $it", "Artist", "Album") }

    /** Two devices on one in-memory room: what one sends, the other receives. */
    private inner class Room {
        val pcPort = FakePort(LocalPlayback(tracks(4), 1, true, 30_000, 200_000, .5f))
        val phonePort = FakePort(LocalPlayback(tracks(6, local = 5), 2, true, 61_000, 180_000, .8f))
        var pcDevices = emptyList<RemoteDevice>()
        var phoneDevices = emptyList<RemoteDevice>()
        val wire = mutableListOf<JSONObject>()
        lateinit var pc: RemoteSession
        lateinit var phone: RemoteSession
        init {
            pc = RemoteSession("DESKTOP-1", "pc", RemoteSeal(RemoteKey.macKey(secret), "pc-device-0001", { clock }),
                { wire += it; phone.receive(JSONObject(it.toString())); true }, pcPort, { clock }) { pcDevices = it }
            phone = RemoteSession("Pixel 9", "phone", RemoteSeal(RemoteKey.macKey(secret), "phone-device-01", { clock }),
                { wire += it; pc.receive(JSONObject(it.toString())); true }, phonePort, { clock }) { phoneDevices = it }
            val members = listOf(PresenceMember("pc-device-0001", "DESKTOP-1", false), PresenceMember("phone-device-01", "Pixel 9", false))
            pc.presence(members); phone.presence(members)
            pc.joined(); phone.joined()
        }
    }

    @Test fun theRoomIsNamedFromTheSecretWithoutShowingIt() {
        val room = RemoteKey.room(secret)
        assertTrue(room.matches(Regex("ketremote:[0-9a-f]{32}")))
        assertFalse(secret in room)
        assertNotEquals(room, RemoteKey.room(RemoteKey.generate()))
        assertTrue(RemoteKey.valid(secret))
        assertFalse(RemoteKey.valid("short"))
    }

    @Test fun onlySealedFreshUnseenMessagesOpen() {
        val a = RemoteSeal(RemoteKey.macKey(secret), "device-aaaa", { clock })
        val b = RemoteSeal(RemoteKey.macKey(secret), "device-bbbb", { clock })
        val stranger = RemoteSeal(RemoteKey.macKey(RemoteKey.generate()), "device-cccc", { clock })
        val msg = a.seal("cmd", RemoteWire.op(RemoteOp.Next), to = "device-bbbb")
        assertNotNull(b.open(JSONObject(msg.toString())))
        assertNull("a replay is dropped", b.open(JSONObject(msg.toString())))
        assertNull("another account's key", b.open(stranger.seal("cmd", RemoteWire.op(RemoteOp.Next))))
        val tampered = a.seal("cmd", RemoteWire.op(RemoteOp.Seek(1000)))
        tampered.put("b", tampered.getString("b").replace("1000", "9000"))
        assertNull("a changed body", b.open(tampered))
        assertNull("meant for another device", b.open(a.seal("cmd", RemoteWire.op(RemoteOp.Next), to = "device-cccc")))
        assertNull("its own message", a.open(a.seal("hi")))
        val old = a.seal("hi")
        clock += RemoteSeal.WINDOW_MS + 1
        assertNull("a stale message", b.open(old))
    }

    @Test fun eachDeviceSeesTheOtherAndWhatItPlays() {
        val room = Room()
        val phone = room.pcDevices.single()
        assertEquals("Pixel 9", phone.name)
        assertEquals("phone", phone.kind)
        assertEquals("Title 2", phone.now?.track?.title)
        assertEquals(61_000L, phone.now?.positionMs)
        assertEquals(listOf(3, 4, 5), phone.now?.upcoming?.map { it.index })
        clock += 4_000
        assertEquals(65_000L, phone.now?.positionAt(clock))
        assertEquals("pc", room.phoneDevices.single().kind)
        assertEquals("Title 1", room.phoneDevices.single().now?.track?.title)
    }

    @Test fun commandsReachTheOtherDeviceOnly() {
        val room = Room()
        room.pc.control("phone-device-01", RemoteOp.Toggle)
        room.pc.control("phone-device-01", RemoteOp.Seek(90_000))
        room.pc.control("phone-device-01", RemoteOp.Volume(.25f))
        room.phone.control("pc-device-0001", RemoteOp.Jump(3))
        assertEquals(listOf(RemoteOp.Toggle, RemoteOp.Seek(90_000), RemoteOp.Volume(.25f)), room.phonePort.ran)
        assertEquals(listOf<RemoteOp>(RemoteOp.Jump(3)), room.pcPort.ran)
        // Replaying what went over the wire changes nothing.
        room.wire.toList().forEach { room.phone.receive(JSONObject(it.toString())) }
        assertEquals(3, room.phonePort.ran.size)
    }

    @Test fun playbackMovesBothWays() {
        val room = Room()
        // « Reprendre ici » on the PC: the phone hands over its queue (without its own files) and pauses.
        room.pc.takeFrom("phone-device-01")
        val taken = room.pcPort.played!!
        assertEquals(listOf("tidal:0", "tidal:1", "tidal:2", "tidal:3", "tidal:4"), taken.queue.map { it.id })
        assertEquals(2, taken.index)
        assertEquals(61_000L, taken.positionMs)
        assertEquals(1, room.phonePort.paused)
        // « Transférer la lecture » from the PC to the phone.
        room.pcPort.p = room.pcPort.p.copy(playing = true, positionMs = 12_000)
        assertTrue(room.pc.sendTo("phone-device-01"))
        assertEquals(2, room.phonePort.played!!.index)
        assertEquals(12_000L, room.phonePort.played!!.positionMs)
        assertEquals(1, room.pcPort.paused)
        assertFalse(room.pcDevices.isEmpty())
    }

    @Test fun aLocalFileIsNeverHandedOver() {
        val p = LocalPlayback(tracks(3, local = 1), 1, true, 0, 0, 1f)
        assertNull(RemoteWire.handoff(p))
    }

    @Test fun theControlJoinsTheRoomOfTheSecretAndLeaves() {
        var joinedRoom: String? = null
        var listener: TransportListener? = null
        var closed = false
        RemoteControl.transportFactory = { room, _, _, l, _ ->
            joinedRoom = room; listener = l
            object : TogetherTransport { override fun start() {}; override fun send(payload: JSONObject) = true; override fun close() { closed = true } }
        }
        try {
            RemoteControl.start(secret, "pc-device-0001", "DESKTOP-1", "pc", FakePort(LocalPlayback(emptyList(), -1, false, 0, 0, 1f)), { it.run() }, { clock })
            assertEquals(RemoteKey.room(secret), joinedRoom)
            listener!!.onJoined()
            assertTrue(RemoteControl.online.value)
            listener!!.onPresence(listOf(PresenceMember("phone-device-01", "Pixel 9", false)))
            assertEquals("Pixel 9", RemoteControl.devices.value.single().name)
            RemoteControl.stop()
            assertTrue(closed)
            assertTrue(RemoteControl.devices.value.isEmpty())
        } finally {
            RemoteControl.stop()
        }
    }
}
