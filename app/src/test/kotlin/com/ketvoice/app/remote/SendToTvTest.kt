package com.ketvoice.app.remote

import com.ketvoice.app.data.Track
import com.ketvoice.app.together.PresenceMember
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * « Lire sur la TV » (Ketvoice TV 1.1, shared with the Windows build): the phone or the PC hands its title, position and
 * queue to the account's TV through phone control's room; the TV plays it and names the sender, the sender pauses;
 * another account's message or a command older than 30 s does nothing.
 */
class SendToTvTest {
    private val secret = RemoteKey.generate(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(11L) })
    private var phoneClock = 5_000_000L
    private var tvClock = 5_000_000L

    private class Port(var p: LocalPlayback) : RemotePort {
        var played: RemoteHandoff? = null
        var from: String? = null
        var paused = 0
        val ran = mutableListOf<RemoteOp>()
        override fun playback() = p
        override fun run(op: RemoteOp) { ran += op }
        override fun play(handoff: RemoteHandoff) { played = handoff; p = p.copy(queue = handoff.queue, index = handoff.index, playing = true) }
        override fun pause() { paused++; p = p.copy(playing = false) }
        override fun received(from: String) { this.from = from }
    }

    private fun tracks(n: Int) = (0 until n).map { Track("tidal:$it", "Title $it", "Artist", "Album") }

    private inner class Devices(tvSecret: String = secret) {
        val phonePort = Port(LocalPlayback(tracks(5), 2, true, 73_000, 200_000, .7f))
        val tvPort = Port(LocalPlayback(emptyList(), -1, false, 0, 0, 1f))
        var phoneSees = emptyList<RemoteDevice>()
        val wire = mutableListOf<JSONObject>()
        lateinit var phone: RemoteSession
        lateinit var tv: RemoteSession
        init {
            phone = RemoteSession("Pixel 9", "phone", RemoteSeal(RemoteKey.macKey(secret), "phone-device-01", { phoneClock }),
                { wire += it; tv.receive(JSONObject(it.toString())); true }, phonePort, { phoneClock }) { phoneSees = it }
            tv = RemoteSession("Salon", "tv", RemoteSeal(RemoteKey.macKey(tvSecret), "tv-device-0001", { tvClock }),
                { phone.receive(JSONObject(it.toString())); true }, tvPort, { tvClock }) {}
            val members = listOf(PresenceMember("phone-device-01", "Pixel 9", false), PresenceMember("tv-device-0001", "Salon", false))
            phone.presence(members); tv.presence(members)
            phone.joined(); tv.joined()
        }
    }

    @Test fun theTvShowsAsATvAndPlaysWhatThePhoneSends() {
        val pair = Devices()
        assertEquals("tv", pair.phoneSees.single().kind)
        assertTrue(pair.phone.sendTo("tv-device-0001"))
        val got = pair.tvPort.played!!
        assertEquals(listOf("tidal:0", "tidal:1", "tidal:2", "tidal:3", "tidal:4"), got.queue.map { it.id })
        assertEquals(2, got.index)
        assertEquals(73_000L, got.positionMs)
        assertEquals("the toast names the sender", "Pixel 9", pair.tvPort.from)
        assertEquals("the sender pauses", 1, pair.phonePort.paused)
    }

    @Test fun anotherAccountsTvNeverReceivesIt() {
        val pair = Devices(tvSecret = RemoteKey.generate())
        pair.phone.sendTo("tv-device-0001")
        assertNull(pair.tvPort.played)
        assertNull(pair.tvPort.from)
    }

    @Test fun aHandoffOrACommandOlderThanThirtySecondsIsIgnored() {
        val pair = Devices()
        // Sealed by the phone, delivered 31 s later (a TV that was asleep, a stuck network).
        val late = mutableListOf<JSONObject>()
        val sender = RemoteSession("Pixel 9", "phone", RemoteSeal(RemoteKey.macKey(secret), "phone-device-01", { phoneClock }),
            { late += it; true }, pair.phonePort, { phoneClock }) {}
        sender.presence(listOf(PresenceMember("tv-device-0001", "Salon", false)))
        sender.sendTo("tv-device-0001")
        sender.control("tv-device-0001", RemoteOp.Next)
        tvClock = phoneClock + RemoteSession.COMMAND_MAX_AGE_MS + 1_000
        late.forEach { pair.tv.receive(JSONObject(it.toString())) }
        assertNull(pair.tvPort.played)
        assertTrue(pair.tvPort.ran.isEmpty())
        // Within 30 s it still goes through.
        val fresh = mutableListOf<JSONObject>()
        val again = RemoteSession("Pixel 9", "phone", RemoteSeal(RemoteKey.macKey(secret), "phone-device-01", { phoneClock }),
            { fresh += it; true }, pair.phonePort, { phoneClock }) {}
        again.control("tv-device-0001", RemoteOp.Next)
        tvClock = phoneClock + 20_000
        fresh.forEach { pair.tv.receive(JSONObject(it.toString())) }
        assertEquals(listOf<RemoteOp>(RemoteOp.Next), pair.tvPort.ran)
    }
}
