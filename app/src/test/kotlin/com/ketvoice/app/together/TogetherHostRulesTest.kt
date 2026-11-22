package com.ketvoice.app.together

import com.ketvoice.app.data.Track
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** « Ketogether pour l'hôte » (8.4): party size, removing a listener, « absent, place gardée », the activity and the sync status. */
class TogetherHostRulesTest {
    private val song = Track("yt:abcdefghijk", "Owned QA tone", "QA", "Tests", 240_000)
    private val second = Track("yt:bcdefghijkl", "Second tone", "QA", "Tests", 200_000)

    private fun host(sim: TogetherSim, policy: Policy = Policy()): TogetherSim.Node {
        val host = sim.add(sim.Node("Hôte", 9_000_000, TogetherRole.Host, "ABC234", policy = policy))
        host.player.hostPlay(listOf(song, second), positionMs = 30_000)
        host.join()
        sim.run(2_000)
        return host
    }

    private fun guest(sim: TogetherSim, name: String): TogetherSim.Node =
        sim.add(sim.Node(name, 123_456, TogetherRole.Guest, "ABC234")).also { it.join() }

    @Test fun theHostSetsHowManyPeopleTheSessionHolds() {
        val sim = TogetherSim()
        val host = host(sim, Policy(limit = 2))
        val first = guest(sim, "Léa")
        sim.run(6_000)
        val late = guest(sim, "Tom")
        sim.run(6_000)
        assertEquals(TogetherPhase.Live, first.ui.phase)
        assertEquals(TogetherPhase.Ended, late.ui.phase)
        assertEquals("full", late.ui.endReason)
        assertEquals(listOf("Hôte", "Léa"), host.ui.members.map { it.name })
        // Guests read the limit too; it is held between 2 and 10 whatever arrives.
        assertEquals(2, first.ui.policy.limit)
        assertEquals(2, Policy.limit(0)); assertEquals(10, Policy.limit(99))
    }

    @Test fun theHostRemovesAListenerWhoCannotComeBack() {
        val sim = TogetherSim()
        val host = host(sim, Policy(everyoneControls = true))
        val guest = guest(sim, "Tom")
        sim.run(6_000)
        host.session.remove(guest.id, host.now())
        sim.run(1_500)
        assertEquals(TogetherPhase.Ended, guest.ui.phase)
        assertEquals("removed", guest.ui.endReason)
        assertEquals(Activity("removed", "Tom"), host.ui.log.first())
        // A request forged afterwards from that member is not obeyed.
        guest.joined = true
        guest.send(TogetherWire.wrap(guest.id, TogetherMsg.Request("next")))
        sim.run(1_500)
        assertFalse(host.player.calls.contains("next"))
        assertTrue(host.ui.members.none { it.id == guest.id })
    }

    @Test fun aListenerWhoDropsBrieflyKeepsTheirPlace() {
        val sim = TogetherSim()
        val host = host(sim, Policy(limit = 2))
        val guest = guest(sim, "Léa")
        sim.run(6_000)
        guest.drop()
        sim.run(2_000)
        val away = host.ui.members.single { it.id == guest.id }
        assertTrue(away.away)
        assertEquals(Activity("away", "Léa"), host.ui.log.first())
        // Their place is kept: someone new is not let in meanwhile.
        val other = guest(sim, "Tom")
        sim.run(4_000)
        assertEquals("full", other.ui.endReason)
        guest.join()
        sim.run(3_000)
        assertFalse(host.ui.members.single { it.id == guest.id }.away)
        assertEquals(TogetherPhase.Live, guest.ui.phase)
        assertTrue(host.ui.log.any { it.kind == "back" && it.name == "Léa" })
        // Gone for longer than the place is kept: they leave the list.
        guest.drop()
        sim.run(TogetherSession.AWAY_KEEP_MS + 5_000)
        assertTrue(host.ui.members.none { it.id == guest.id })
        assertEquals(Activity("leave", "Léa"), host.ui.log.first())
    }

    @Test fun theActivitySaysWhoPausedSkippedAndAddedOnceEach() {
        val sim = TogetherSim()
        val host = host(sim, Policy(everyoneControls = true, everyoneAdds = true))
        val guest = guest(sim, "Tom")
        sim.run(6_000)
        assertTrue(guest.session.transport("pause", 0, guest.now()))
        sim.run(2_000)
        assertEquals(1, host.ui.log.count { it.kind == "pause" })
        assertEquals("Tom", host.ui.log.first { it.kind == "pause" }.name)
        host.player.setPlaying(true)                       // the host plays again on their phone
        sim.run(1_500)
        assertEquals(Activity("play", "Hôte"), host.ui.log.first())
        val pick = Track("yt:cdefghijklm", "Proposed tone", "QA", "", 150_000)
        guest.session.propose(pick, guest.now())
        sim.run(1_500)
        assertEquals(Activity("add", "Tom", "Proposed tone"), host.ui.log.first())
        host.player.next()                                 // a skip of the host, mid-title
        sim.run(1_500)
        assertEquals(Activity("next", "Hôte"), host.ui.log.first())
        // The guests see the same activity.
        sim.run(1_500)
        assertEquals(host.ui.log, guest.ui.log)
        assertTrue(host.ui.log.size <= TogetherCodec.MAX_LOG)
    }

    @Test fun theSyncIsMeasuredOnBothSides() {
        val sim = TogetherSim()
        val host = host(sim)
        val guest = guest(sim, "Léa")
        sim.run(45_000)
        assertNotNull(guest.ui.driftMs)
        assertTrue(kotlin.math.abs(guest.ui.driftMs!!) < 450)
        val reported = host.ui.members.single { it.id == guest.id }.syncMs
        assertNotNull(reported)
        assertTrue(reported!! in 0..450)
    }

    @Test fun theNewFieldsTravelAndOldMessagesStillRead() {
        val state = TogetherMsg.State("sid1", 3, 99, "Hôte", null, null, true, 0, true, false, emptyList(), emptyList(),
            Policy(limit = 4), 7, away = listOf("guest00001" to "Léa"),
            log = listOf(Activity("add", "Tom", "Song"), Activity("pause", "Léa")))
        assertEquals(state, TogetherCodec.decode(TogetherCodec.encode(state)))
        val remove = TogetherMsg.Remove("sid1", 4, "guest00001", "removed")
        assertEquals(remove, TogetherCodec.decode(TogetherCodec.encode(remove)))
        assertEquals(TogetherMsg.Ping(5, -120), TogetherCodec.decode(TogetherCodec.encode(TogetherMsg.Ping(5, -120))))
        // A State of 8.3 (no limit, away list or activity) reads as before.
        val old = JSONObject(TogetherCodec.encode(state)).apply { remove("lim"); remove("aw"); remove("log") }
        val read = TogetherCodec.decode(old.toString()) as TogetherMsg.State
        assertEquals(Policy.MAX_LIMIT, read.policy.limit)
        assertTrue(read.away.isEmpty() && read.log.isEmpty())
        // An unknown reason or activity is refused.
        assertThrows(Exception::class.java) { TogetherCodec.decode(TogetherCodec.encode(remove).replace("removed", "banned")) }
    }
}
