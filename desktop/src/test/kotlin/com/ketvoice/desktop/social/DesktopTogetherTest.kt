package com.ketvoice.desktop.social

import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.together.FakePlayer
import com.ketvoice.app.together.HostKey
import com.ketvoice.app.together.Policy
import com.ketvoice.app.together.PresenceMember
import com.ketvoice.app.together.TogetherPhase
import com.ketvoice.app.together.TogetherRole
import com.ketvoice.app.together.TogetherSession
import com.ketvoice.app.together.TogetherTransport
import com.ketvoice.app.together.TogetherUi
import com.ketvoice.app.together.TogetherWire
import com.ketvoice.app.together.TransportListener
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.FakeAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Listening rooms on Windows: this PC's real [Player] (over a [FakeAudioEngine]) in a room with a phone (the shared
 * [TogetherSession] over the shared test [FakePlayer]), on one simulated time line with a relay that delays and
 * re-reads every message. The protocol itself is the phone's, tested by the shared TogetherSessionTest.
 */
class DesktopTogetherTest {
    private val engine = FakeAudioEngine(240_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val song = Track("yt:abcdefghijk", "Owned QA tone", "QA", "Tests", 240_000)
    private val second = Track("yt:bcdefghijkl", "Second tone", "QA", "Tests", 200_000)
    private val third = Track("kv:um:12345", "Third tone", "QA", "Tests", 180_000)

    /** The simulated world: its time, the messages on their way, who is in the room. */
    private var t = 0L
    private val queue = ArrayList<Triple<Long, String, () -> Unit>>()
    private val members = LinkedHashMap<String, Pair<String, TransportListener>>()
    private val hosts = HashSet<String>()

    private inner class Relay(val me: String, val name: String, val host: Boolean, val listener: TransportListener) : TogetherTransport {
        var joined = false
        override fun start() { joined = true; members[me] = name to listener; if (host) hosts += me; later { listener.onJoined() }; presence() }
        override fun send(payload: JSONObject): Boolean {
            if (!joined) return false
            val text = payload.toString()
            members.filterKeys { it != me }.values.forEach { (_, l) -> later { l.onBroadcast(JSONObject(text)) } }
            return true
        }
        override fun close() { joined = false; members.remove(me); presence() }
    }

    private fun later(action: () -> Unit) { queue += Triple(t + 80, "m", action) }
    private fun presence() {
        val list = members.map { (id, v) -> PresenceMember(id, v.first, id in hosts) }
        members.values.forEach { (_, l) -> later { l.onPresence(list) } }
    }

    /** The phone in the room: a session over the shared FakePlayer, on its own clock. */
    private inner class Phone(role: TogetherRole, code: String = "ABC234", key: HostKey? = if (role == TogetherRole.Host) HostKey.generate() else null,
                              fingerprint: String? = null, policy: Policy = Policy()) {
        val offset = 9_000_000L
        fun now() = t + offset
        val player = FakePlayer(::now)
        val id = TogetherWire.memberId()
        var ui = TogetherUi()
        lateinit var relay: Relay
        val session = TogetherSession(role, id, if (role == TogetherRole.Host) "Hôte" else "Invité", code, player, { relay.send(it) }, { ui = it },
            key, fingerprint, policy)
        init {
            relay = Relay(id, if (role == TogetherRole.Host) "Hôte" else "Invité", role == TogetherRole.Host, object : TransportListener {
                override fun onJoined() = session.joined(now())
                override fun onDown(reason: String) = session.down(now())
                override fun onRefused(reason: String) = session.finish("refused", now(), false)
                override fun onBroadcast(payload: JSONObject) = session.receive(payload, now())
                override fun onPresence(members: List<PresenceMember>) = session.presence(members, now())
            })
        }
        fun start() = relay.start()
    }

    private var phone: Phone? = null

    @BeforeTest fun setUp() {
        Player.resetForTest()
        Player.autoplayCandidates = { emptyList() }
        Player.attach(engine, { AudioSource.Url("https://fixture.test/${it.id}.flac") }, scope)
        DesktopTogether.reset()
        DesktopTogether.post = { it.run() }
        DesktopTogether.clock = { t + 5_000 }
        DesktopTogether.port = DesktopPort
        DesktopTogether.transports = { _, me, meta, listener -> Relay(me, meta.optString("n"), meta.optString("r") == "h", listener) }
    }

    @AfterTest fun tearDown() { DesktopTogether.reset(); Player.resetForTest(); scope.cancel() }

    /** Runs the world for [ms]: messages, the engine's time, both sessions' ticks twice a second. */
    private fun run(ms: Long) {
        val end = t + ms
        while (t < end) {
            t += 20
            engine.advance(20)
            val due = queue.filter { it.first <= t }
            queue.removeAll(due.toSet())
            due.forEach { it.third() }
            if (t % 500 == 0L) { phone?.session?.tick(phone!!.now()); DesktopTogether.tick() }
            settle()
        }
    }

    /** The Player resolves a title on its own coroutine: wait for it, in real time, before the world moves on. */
    private fun settle() {
        val deadline = System.currentTimeMillis() + 2_000
        while (Player.loading.value && System.currentTimeMillis() < deadline) Thread.sleep(2)
    }

    private fun pcHost(): Phone {
        Player.playQueue(listOf(song, second, third), 0); settle()
        Player.seekTo(30_000)
        DesktopTogether.create("PC")
        run(2_000)
        val ui = DesktopTogether.ui.value
        val guest = Phone(TogetherRole.Guest, ui.code, fingerprint = ui.fingerprint).also { phone = it; it.start() }
        return guest
    }

    @Test fun aPcGuestPlaysTheHostsTitleWhereTheHostIs() {
        val host = Phone(TogetherRole.Host).also { phone = it }
        host.player.hostPlay(listOf(song, second, third), positionMs = 30_000)
        host.start()
        run(1_000)
        DesktopTogether.join("ABC234", "PC", host.session.ui.fingerprint)
        run(10_000)
        val ui = DesktopTogether.ui.value
        assertEquals(TogetherPhase.Live, ui.phase, ui.toString())
        assertEquals(song.id, Player.state.value.current?.id)
        assertTrue(Player.state.value.isPlaying)
        val drift = Player.positionNow() - host.player.position()
        assertTrue(abs(drift) < 1_000, "drift $drift")
        assertEquals(listOf(second.id, third.id), ui.upcoming.mapNotNull { it.ref?.id })
        assertTrue(DesktopTogether.guest && com.ketvoice.desktop.ketmix.DesktopAutomix.held(), "a guest's Ketmix waits")
    }

    @Test fun aGuestThatDriftsIsBroughtBack() {
        aPcGuestPlaysTheHostsTitleWhereTheHostIs()
        val host = phone!!
        engine.seekTo(engine.positionMs() + 4_000)   // this PC jumps ahead (a hiccup)
        run(6_000)
        val drift = Player.positionNow() - host.player.position()
        assertTrue(abs(drift) < 1_000, "drift after correction $drift")
        assertTrue(engine.calls.count { it.startsWith("seekTo") } >= 2, engine.calls.takeLast(10).toString())
    }

    @Test fun theHostDrivesAndAGuestAsks() {
        aPcGuestPlaysTheHostsTitleWhereTheHostIs()
        val host = phone!!
        // Only the host controls: « Suivant » on the PC is refused, nothing moves here.
        Player.next(); settle()
        assertEquals(song.id, Player.state.value.current?.id)
        assertEquals("Seul l'hôte contrôle la lecture", DesktopTogether.ui.value.notice)
        // The host moves on: the PC follows.
        host.player.next()
        run(4_000)
        assertEquals(second.id, Player.state.value.current?.id)
        // Everyone controls: the PC's « Suivant » goes to the host, which plays the next title for everyone.
        host.session.setPolicy(Policy(everyoneControls = true), host.now())
        run(1_000)
        Player.next(); settle()
        run(4_000)
        assertTrue("next" in host.player.calls, host.player.calls.toString())
        assertEquals(third.id, Player.state.value.current?.id)
    }

    @Test fun aPcHostsAPhone() {
        val guest = pcHost()
        run(10_000)
        assertEquals(TogetherPhase.Live, guest.ui.phase, guest.ui.toString())
        assertEquals(song.id, guest.player.current?.id)
        assertTrue(abs(guest.player.position() - Player.positionNow()) < 1_000)
        assertEquals(2, DesktopTogether.ui.value.members.size)
        // A reaction from the phone reaches the PC.
        guest.session.react("🔥", guest.now())
        run(1_000)
        assertEquals("🔥", DesktopTogether.ui.value.reaction?.emoji)
        // The host takes the phone out; the phone cannot come back in.
        DesktopTogether.remove(guest.id)
        run(2_000)
        assertEquals("removed", guest.ui.endReason)
    }

    @Test fun endingTheRoomEndsItForEveryoneAndGivesThePlayerBack() {
        val guest = pcHost()
        run(8_000)
        DesktopTogether.leave()
        run(1_000)
        assertEquals(TogetherPhase.Ended, DesktopTogether.ui.value.phase)
        assertEquals("hostEnded", guest.ui.endReason)
        assertEquals(null, Player.transportGate)
        DesktopTogether.dismiss()
        assertEquals(TogetherPhase.Idle, DesktopTogether.ui.value.phase)
    }

    @Test fun aGuestGetsItsPlayerModesBack() {
        Player.setShuffle(true); Player.setRepeat(false, true)
        val host = Phone(TogetherRole.Host).also { phone = it }
        host.player.hostPlay(listOf(song), positionMs = 0)
        host.start()
        DesktopTogether.join("ABC234", "PC", host.session.ui.fingerprint)
        run(3_000)
        assertFalse(Player.state.value.shuffle || Player.state.value.repeatAll, "a guest follows the host's order")
        DesktopTogether.leave()
        assertTrue(Player.state.value.shuffle && Player.state.value.repeatAll)
    }

    @Test fun aRoomIsJoinedByCodeOrLink() {
        assertTrue(DesktopTogether.joinTyped("abc 234"))
        assertEquals("ABC234", DesktopTogether.ui.value.code)
        DesktopTogether.reset()
        assertTrue(DesktopTogether.joinTyped("https://tysnnn23.github.io/Ketvoice/listen.html?track=ABC234-ABCDEFGH&title=Ketogether&kind=together"))
        assertEquals("ABCDEFGH", DesktopTogether.ui.value.fingerprint)
        DesktopTogether.reset()
        assertFalse(DesktopTogether.joinTyped("bonjour"))
        assertNotNull(com.ketvoice.app.social.FriendsNow.roomWire("ABC234", "ABCDEFGH"))
    }
}
