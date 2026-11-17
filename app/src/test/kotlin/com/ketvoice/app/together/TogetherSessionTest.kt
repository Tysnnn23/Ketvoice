package com.ketvoice.app.together

import com.ketvoice.app.data.Track
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The Ketogether protocol between a host and guests, over a simulated room (no network, no phone). */
class TogetherSessionTest {
    private val song = Track("yt:abcdefghijk", "Owned QA tone", "QA", "Tests", 240_000)
    private val second = Track("yt:bcdefghijkl", "Second tone", "QA", "Tests", 200_000)
    private val third = Track("kv:um:12345", "Third tone", "QA", "Tests", 180_000)
    private val local = Track("local:42", "Personal recording", "Me", "", 120_000)

    private fun room(policy: Policy = Policy(), seed: Long = 7L): Triple<TogetherSim, TogetherSim.Node, TogetherSim.Node> {
        val sim = TogetherSim(seed)
        val host = sim.add(sim.Node("Hôte", 9_000_000, TogetherRole.Host, "ABC234", policy = policy))
        host.player.hostPlay(listOf(song, second, third), positionMs = 30_000)
        host.join()
        sim.run(2_000)
        val guest = sim.add(sim.Node("Invité", 123_456, TogetherRole.Guest, "ABC234"))
        guest.join()
        return Triple(sim, host, guest)
    }

    private fun drift(host: TogetherSim.Node, guest: TogetherSim.Node): Long = guest.player.position() - host.player.position()

    @Test fun aGuestPlaysTheHostsTitleAtTheSamePlace() {
        val (sim, host, guest) = room()
        sim.run(8_000)
        assertEquals(TogetherPhase.Live, guest.ui.phase)
        assertEquals(song.id, guest.player.current?.id)
        assertTrue(guest.player.wanted)
        assertTrue("drift ${drift(host, guest)}", kotlin.math.abs(drift(host, guest)) < 250)
        // The shared queue reaches the guest, with the host's next titles.
        assertEquals(listOf(second.id, third.id), guest.ui.upcoming.map { it.ref?.id })
        assertEquals(listOf(song.id, second.id, third.id), guest.player.queue.map { it.second.id })
        assertEquals("Hôte", guest.ui.hostName)
        assertTrue(guest.ui.members.any { it.host && it.name == "Hôte" })
        // The clocks are 9 s apart: the guest measured that from its pings.
        assertEquals((9_000_000L - 123_456L).toDouble(), guest.ui.offsetMs!!.toDouble(), 60.0)
    }

    @Test fun aSlowSeekIsLearnedAndTheGuestStaysInTime() {
        val (sim, host, guest) = room()
        guest.player.loadDelayMs = 1_800
        guest.player.seekLatencyMs = 350
        sim.run(30_000)
        val seeks = guest.player.calls.count { it.startsWith("seek") }
        assertTrue("seeks $seeks", seeks in 1..4)
        assertTrue("drift ${drift(host, guest)}", kotlin.math.abs(drift(host, guest)) < 450)
        // Steady afterwards: no correction for a whole minute.
        val before = guest.player.calls.size
        sim.run(60_000)
        assertEquals(before, guest.player.calls.size)
    }

    @Test fun pauseSeekAndNextOfTheHostReachTheGuestPromptly() {
        val (sim, host, guest) = room()
        sim.run(6_000)
        host.player.setPlaying(false)
        sim.run(1_000)
        assertFalse(guest.player.wanted)
        host.player.setPlaying(true)
        host.player.seek(120_000)
        sim.run(1_500)
        assertTrue(guest.player.wanted)
        assertTrue("drift ${drift(host, guest)}", kotlin.math.abs(drift(host, guest)) < 450)
        host.player.next()
        sim.run(1_500)
        assertEquals(second.id, guest.player.current?.id)
    }

    @Test fun theHostSendsAHeartbeatAndStaysWithinTheRate() {
        val (sim, host, _) = room()
        sim.run(10_000)
        val from = sim.t
        sim.run(60_000)
        val mine = sim.wire.filter { it.first >= from && it.second.getString("f") == host.id }
        val ticks = mine.count { JSONObject(it.second.getString("b")).getString("t") == "tick" }
        val states = mine.count { JSONObject(it.second.getString("b")).getString("t") == "state" }
        assertTrue("ticks $ticks", ticks in 9..13)
        assertTrue("states $states", states in 1..3)
        // Never more than ten messages in any second, from anyone.
        val perSecond = sim.wire.groupBy { it.first / 1000 }.values.maxOf { it.size }
        assertTrue("per second $perSecond", perSecond <= 10)
    }

    @Test fun clockOffsetKeepsTheShortestExchange() {
        val clock = ClockOffset()
        // Host clock = guest clock + 5 000. A slow, lopsided exchange first, then a quick one.
        clock.sample(sentAt = 1_000, hostAt = 6_900, receivedAt = 1_950)
        clock.sample(sentAt = 2_000, hostAt = 7_020, receivedAt = 2_040)
        assertEquals(5_000L, clock.offset)
        assertEquals(40L, clock.rtt)
        assertFalse(clock.sample(3_000, 0, 2_000)) // received before sent
        assertEquals(15_000L, clock.hostNow(10_000))
    }

    @Test fun driftNeedsConfirmationUnlessTheHostJumped() {
        val judge = DriftJudge()
        judge.loaded(0)
        assertNull("settling", judge.judge(10_000, 9_000, 1_000, 200_000))
        assertNull("first time over", judge.judge(10_000, 9_000, 3_000, 200_000))
        assertEquals(9_000L + judge.leadMs, judge.judge(10_000, 9_000, 3_500, 200_000))
        // Right after a seek nothing is corrected again, unless forced by a jump of the host.
        assertNull(judge.judge(20_000, 9_000, 5_100, 200_000))
        judge.force()
        assertNotNull(judge.judge(20_000, 9_000, 5_200, 200_000))
        // Small differences are left alone.
        val calm = DriftJudge(); calm.loaded(0)
        repeat(5) { assertNull(calm.judge(10_300, 10_000, 5_000L + it * 500, 200_000)) }
        // Never past the end of the title.
        val late = DriftJudge(); late.loaded(0); late.force()
        assertNull(late.judge(100, 199_500, 5_000, 200_000))
    }

    @Test fun guestsControlOnlyWhenTheHostLetsThem() {
        val (sim, host, guest) = room()
        sim.run(5_000)
        assertFalse(guest.session.transport("pause", 0, guest.now())) // pause on this phone only
        assertTrue(guest.session.transport("next", 0, guest.now()))  // refused, with a notice
        assertEquals("Seul l'hôte contrôle la lecture", guest.ui.notice)
        sim.run(1_000)
        assertFalse(host.player.calls.contains("next"))
        // A forged request (as if allowed) does nothing either.
        guest.send(TogetherWire.wrap(guest.id, TogetherMsg.Request("next")))
        sim.run(1_000)
        assertFalse(host.player.calls.contains("next"))

        host.session.setPolicy(Policy(everyoneControls = true), host.now())
        sim.run(1_500)
        assertTrue(guest.ui.policy.everyoneControls)
        assertTrue(guest.session.transport("next", 0, guest.now()))
        sim.run(1_500)
        assertTrue(host.player.calls.contains("next"))
        assertEquals(second.id, guest.player.current?.id)
        assertTrue(guest.session.transport("seek", 90_000, guest.now()))
        sim.run(2_000)
        assertTrue(host.player.calls.contains("seek 90000"))
    }

    @Test fun proposalsJoinTheQueueOrWaitForTheHost() {
        val (sim, host, guest) = room(Policy(everyoneAdds = true))
        sim.run(5_000)
        val pick = Track("yt:cdefghijklm", "Proposed tone", "QA", "", 150_000)
        assertTrue(guest.session.propose(pick, guest.now()))
        sim.run(1_500)
        assertTrue(host.player.calls.contains("enqueue ${pick.id}"))
        assertEquals("Invité", host.ui.upcoming.last().by)
        assertEquals("Titre ajouté à Ketogether", guest.ui.notice)
        assertEquals(pick.id, guest.ui.upcoming.last().ref?.id)
        // A local file cannot be proposed: it never leaves the phone.
        assertFalse(guest.session.propose(local, guest.now()))

        host.session.setPolicy(Policy(everyoneAdds = false), host.now())
        sim.run(1_000)
        val other = Track("yt:defghijklmn", "Waiting tone", "QA", "", 150_000)
        guest.session.propose(other, guest.now())
        sim.run(1_500)
        assertEquals(listOf(other.id), host.ui.pending.map { it.ref.id })
        assertEquals(listOf(other.id), guest.ui.pending.map { it.ref.id })
        host.session.accept(host.ui.pending.single().id, host.now())
        sim.run(1_500)
        assertTrue(host.player.calls.contains("enqueue ${other.id}"))
        assertTrue(guest.ui.pending.isEmpty())
        // At most five waiting from one guest.
        repeat(7) { guest.session.propose(Track("yt:efghijklm${'a' + it}o", "Tone $it", "QA", ""), guest.now()); sim.run(600) }
        sim.run(1_000)
        assertEquals(5, host.ui.pending.size)
    }

    @Test fun aTitleOnlyTheHostHasIsShownUnavailableAndCanBeSkipped() {
        val (sim, host, guest) = room()
        sim.run(5_000)
        host.player.hostPlay(listOf(local, second), positionMs = 0)
        sim.run(1_500)
        assertFalse(guest.ui.available)
        assertNull(guest.ui.nowPlaying)
        assertFalse(guest.player.wanted)
        assertFalse(host.ui.available)
        // Nothing of the local title travelled.
        assertTrue(sim.wire.none { it.second.toString().contains("Personal recording") || it.second.toString().contains("local:42") })
        host.session.skip()
        sim.run(1_500)
        assertTrue(guest.ui.available)
        assertEquals(second.id, guest.player.current?.id)
        assertTrue(guest.player.wanted)
    }

    @Test fun whenTheHostLeavesTheSessionEndsForEveryone() {
        val (sim, host, guest) = room()
        sim.run(5_000)
        host.session.finish("ended", host.now())
        sim.run(1_000)
        assertEquals(TogetherPhase.Ended, guest.ui.phase)
        assertEquals("hostEnded", guest.ui.endReason)
        // The guest keeps the music it plays: the session ends, not the playback.
        assertTrue(guest.player.wanted)
    }

    @Test fun aSilentHostIsWaitedForThenGivenUp() {
        val (sim, host, guest) = room()
        sim.run(5_000)
        host.joined = false
        sim.run(25_000)
        assertEquals("L'hôte ne répond plus…", guest.ui.notice)
        assertEquals(TogetherPhase.Live, guest.ui.phase)
        sim.run(80_000)
        assertEquals(TogetherPhase.Ended, guest.ui.phase)
        assertEquals("hostLost", guest.ui.endReason)
    }

    @Test fun aWrongCodeFindsNoSession() {
        val sim = TogetherSim()
        val guest = sim.add(sim.Node("Invité", 0, TogetherRole.Guest, "ZZZ999"))
        guest.join()
        sim.run(15_000)
        assertEquals("notFound", guest.ui.endReason)
    }

    @Test fun aReconnectedGuestAsksAgainAndCatchesUp() {
        val (sim, host, guest) = room()
        sim.run(6_000)
        guest.drop()
        assertEquals(TogetherPhase.Reconnecting, guest.ui.phase)
        host.player.next()
        sim.run(8_000)
        assertEquals(song.id, guest.player.current?.id) // it heard nothing meanwhile
        guest.join()
        sim.run(2_000)
        assertEquals(TogetherPhase.Live, guest.ui.phase)
        assertEquals(second.id, guest.player.current?.id)
    }

    @Test fun anotherMemberCannotPretendToBeTheHost() {
        val (sim, host, guest) = room()
        sim.run(5_000)
        val intruder = sim.add(sim.Node("Intrus", 0, TogetherRole.Host, "ABC234"))
        intruder.joined = true
        val forged = TogetherMsg.State(host.session.sid, 9_999, intruder.now(), "Hôte", TrackRef.of(third), "zz", true, 0, true,
            false, emptyList(), emptyList(), Policy(true, true), 1)
        // Its own key, then the host's member id with its own key, then an unsigned copy: all ignored.
        intruder.send(TogetherWire.wrap(intruder.id, forged, HostKey.generate()))
        intruder.send(TogetherWire.wrap(host.id, forged, HostKey.generate()))
        intruder.send(TogetherWire.wrap(host.id, forged))
        sim.run(2_000)
        assertEquals(song.id, guest.player.current?.id)
        assertFalse(guest.ui.policy.everyoneControls)
    }

    @Test fun aLinkNamesTheHostFromTheFirstMessage() {
        val sim = TogetherSim()
        val key = HostKey.generate()
        val host = sim.add(sim.Node("Hôte", 9_000, TogetherRole.Host, "ABC234", key = key))
        host.player.hostPlay(listOf(song))
        host.join()
        sim.run(2_000)
        val guest = sim.add(sim.Node("Invité", 0, TogetherRole.Guest, "ABC234", fingerprint = key.fingerprint))
        guest.join()
        // Someone else in the room answers before the host, with a well signed state of its own key.
        val forged = TogetherMsg.State("fake", 1, 0, "Faux", TrackRef.of(third), "zz", true, 0, true, false,
            emptyList(), emptyList(), Policy(), 1)
        guest.session.receive(TogetherWire.wrap("impostor0001", forged, HostKey.generate()), guest.now())
        assertNull(guest.player.current)
        sim.run(5_000)
        // Its key is not the one the link names: the guest waited for the real host.
        assertEquals(song.id, guest.player.current?.id)
        assertEquals("Hôte", guest.ui.hostName)

        // Without a link (a typed code) the first well signed host is trusted, then kept.
        val typed = sim.add(sim.Node("Code", 0, TogetherRole.Guest, "ABC234"))
        typed.join()
        sim.run(5_000)
        assertEquals(song.id, typed.player.current?.id)
    }

    @Test fun replayedMessagesAreIgnored() {
        val (sim, host, guest) = room()
        sim.run(5_000)
        val oldState = sim.wire.last { it.second.getString("f") == host.id && JSONObject(it.second.getString("b")).getString("t") == "state" }.second
        host.player.next()
        sim.run(2_000)
        assertEquals(second.id, guest.player.current?.id)
        guest.session.receive(oldState, guest.now())
        sim.run(1_000)
        assertEquals(second.id, guest.player.current?.id)
    }

    @Test fun malformedAndFloodingMessagesAreDropped() {
        val (sim, host, guest) = room()
        sim.run(5_000)
        val junk = listOf(JSONObject(), JSONObject().put("v", 1), JSONObject().put("v", 2).put("f", "abcdefgh12").put("b", "{}"),
            JSONObject().put("v", 1).put("f", "../../etc").put("b", "{\"t\":\"hello\",\"n\":\"x\"}"),
            JSONObject().put("v", 1).put("f", "abcdefgh12").put("b", "not json"),
            JSONObject().put("v", 1).put("f", "abcdefgh12").put("b", "{\"t\":\"react\",\"e\":\"💣\"}"),
            JSONObject().put("v", 1).put("f", "abcdefgh12").put("b", "{\"t\":\"req\",\"a\":\"rm -rf\"}"),
            JSONObject().put("v", 1).put("f", "abcdefgh12").put("b", "x".repeat(20_000)))
        junk.forEach { host.session.receive(it, host.now()); guest.session.receive(it, guest.now()) }
        // A flood of hellos from one member: most are dropped before reaching the session.
        repeat(200) { host.session.receive(TogetherWire.wrap("floodfloodflood1", TogetherMsg.Hello("Flood")), host.now()) }
        sim.run(3_000)
        assertEquals(TogetherPhase.Live, guest.ui.phase)
        assertEquals(song.id, guest.player.current?.id)
        val perSecond = sim.wire.filter { it.first > sim.t - 3_000 }.groupBy { it.first / 1000 }.values.maxOf { it.size }
        assertTrue("per second $perSecond", perSecond <= 10)
    }

    @Test fun reactionsAreSharedButNotRepeatedTooFast() {
        val (sim, host, guest) = room()
        sim.run(5_000)
        guest.session.react("🔥", guest.now())
        guest.session.react("🔥", guest.now())
        sim.run(500)
        assertEquals("🔥", host.ui.reaction?.emoji)
        assertEquals("Invité", host.ui.reaction?.name)
        assertEquals(1, host.uis.mapNotNull { it.reaction?.serial }.distinct().size)
    }

    @Test fun aCodeAlreadyHostedIsGivenUpBeforeGoingLive() {
        val sim = TogetherSim()
        val first = sim.add(sim.Node("Premier", 0, TogetherRole.Host, "ABC234"))
        first.player.hostPlay(listOf(song)); first.join()
        sim.run(3_000)
        val second = sim.add(sim.Node("Second", 0, TogetherRole.Host, "ABC234"))
        second.join()
        sim.run(1_000)
        assertTrue(second.session.collision)
        assertEquals(TogetherPhase.Connecting, second.ui.phase)
        assertFalse(first.session.collision)
    }

    @Test fun anEmptyRoomExpires() {
        val sim = TogetherSim()
        val host = sim.add(sim.Node("Hôte", 0, TogetherRole.Host, "ABC234"))
        host.player.hostPlay(listOf(song)); host.join()
        sim.run(31 * 60_000L)
        assertEquals("expired", host.ui.endReason)
    }

    @Test fun aGuestPausingHereCatchesUpWhenPlayingAgain() {
        val (sim, host, guest) = room()
        sim.run(6_000)
        // Pause on this phone only (the host keeps the controls), as the notification would.
        guest.player.setPlaying(false)
        sim.run(20_000)
        assertFalse(guest.player.wanted)
        guest.player.setPlaying(true)
        sim.run(1_500)
        assertTrue("drift ${drift(host, guest)}", kotlin.math.abs(drift(host, guest)) < 450)
    }
}
