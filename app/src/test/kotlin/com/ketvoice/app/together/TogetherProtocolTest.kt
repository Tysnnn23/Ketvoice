package com.ketvoice.app.together

import com.ketvoice.app.data.Track
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** Ketogether's messages, names, codes, links and the Realtime frames, without a network. */
class TogetherProtocolTest {
    private fun rejected(text: String) = assertTrue(text, runCatching { TogetherCodec.decode(text) }.isFailure)

    @Test fun onlyCatalogueTitlesTravelAndNothingPrivateWithThem() {
        val track = Track("yt:abcdefghijk", "Title‮", "Artist", "Album", 200_000, streamUrl = "https://example.invalid/token",
            downloadUrl = "https://example.invalid/cookie", coverUrl = "https://i.ytimg.com/vi/abcdefghijk/hq.jpg", sourceOverride = "cs:me")
        val ref = TrackRef.of(track)!!
        val text = ref.json().toString()
        assertFalse(text.contains("token")); assertFalse(text.contains("cookie")); assertFalse(text.contains("cs:me"))
        assertEquals("Title", ref.title)
        assertEquals(ref, TrackRef.decode(JSONObject(text)))
        assertNull(TrackRef.of(Track("local:1", "x", "y", "")))
        assertNull(TrackRef.of(Track("cs:personal:1", "x", "y", "")))
        assertNull(TrackRef.of(Track("file:///sdcard/a.mp3", "x", "y", "")))
        // Covers only from public artwork hosts.
        assertNull(TrackRef.of(track, "file:///data/cover.jpg")!!.cover)
        assertNull(TrackRef.of(track, "https://tracker.example/cover.jpg")!!.cover)
        assertNull(TrackRef.of(track, "https://ytimg.com.evil.example/x.jpg")!!.cover)
        assertEquals("https://lh3.googleusercontent.com/a", TrackRef.of(track, "https://lh3.googleusercontent.com/a")!!.cover)
        val back = TrackRef.decode(JSONObject(text)).toTrack()
        assertNull(back.streamUrl); assertNull(back.sourceOverride)
        assertEquals("upload", TrackRef("kv:upload:123e4567-e89b-12d3-a456-426614174000", "a", "b").toTrack().addon)
    }

    @Test fun everyMessageSurvivesTheTrip() {
        val ref = TrackRef("yt:abcdefghijk", "T", "A", "Al", 1000, true, null)
        val msgs = listOf(
            TogetherMsg.State("sid1", 3, 99, "Hôte", ref, "entry-1", true, 1234, true, false,
                listOf(QueueItem("e2", ref, "Léa"), QueueItem("e3", null)), listOf(Proposal("p1", "abcdefgh12", "Léa", ref)),
                Policy(true, false), 42),
            TogetherMsg.State("sid1", 4, 99, "Hôte", null, null, false, 0, false, false, emptyList(), emptyList(), Policy(), 0),
            TogetherMsg.Tick("sid1", 5, 100, "entry-1", 2000, true, true, 42),
            TogetherMsg.Pong("sid1", 6, "abcdefgh12", 10, 20), TogetherMsg.End("sid1", 7, "ended"),
            TogetherMsg.Answer("sid1", 8, "abcdefgh12", "p1", true), TogetherMsg.Hello("Léa"), TogetherMsg.Ping(77),
            TogetherMsg.Request("seek", 5000), TogetherMsg.Propose("p2", ref), TogetherMsg.React("🎶"))
        msgs.forEach { assertEquals(it, TogetherCodec.decode(TogetherCodec.encode(it))) }
    }

    @Test fun malformedMessagesAreRejected() {
        rejected("{}")
        rejected("""{"t":"exec","cmd":"x"}""")
        rejected("""{"t":"req","a":"delete"}""")
        rejected("""{"t":"react","e":"💣"}""")
        rejected("""{"t":"hello","n":"someone@example.com"}""")
        rejected("""{"t":"hello","n":""}""")
        rejected("""{"t":"prop","id":"p1","r":{"id":"local:1","ti":"x","ar":"y"}}""")
        rejected("""{"t":"prop","id":"../p","r":{"id":"yt:abcdefghijk","ti":"x","ar":"y"}}""")
        rejected("""{"t":"tick","sid":"s","seq":-1,"at":1,"pos":1,"pl":true,"bu":false,"qv":1}""")
        rejected("""{"t":"tick","sid":"s","seq":1,"at":1,"pos":1,"pl":"yes","bu":false,"qv":1}""")
        rejected("x".repeat(TogetherCodec.MAX_TEXT + 1))
        val big = TogetherCodec.encode(TogetherMsg.State("s", 1, 1, "H", null, null, true, 0, true, false,
            List(TogetherCodec.MAX_UPCOMING) { QueueItem("e$it", null) }, emptyList(), Policy(), 0))
        val tooMany = JSONObject(big).also { o -> o.getJSONArray("up").put(JSONObject().put("e", "extra")) }.toString()
        rejected(tooMany)
    }

    @Test fun namesAreCleanAndNeverAnEmail() {
        assertEquals("Léa", Names.clean("  Léa  "))
        assertNull(Names.clean("lea.martin@gmail.com"))
        assertNull(Names.clean("Léa (lea@x.fr)"))
        assertNull(Names.clean("‮​ "))
        assertEquals(Names.MAX, Names.clean("x".repeat(40))!!.length)
        assertEquals("LM", Names.initials("Léa Martin"))
        assertEquals("K", Names.initials("ketvoice"))
    }

    @Test fun roomCodesAreUnambiguousAndForgiving() {
        val random = Random(1)
        repeat(200) {
            val code = RoomCode.generate(random)
            assertEquals(6, code.length)
            assertTrue(code.none { it in "01OIL" })
        }
        assertEquals("ABC234", RoomCode.normalize("abc 234"))
        assertEquals("ABC234", RoomCode.normalize("ABC-234"))
        assertNull(RoomCode.normalize("ABO234"))
        assertNull(RoomCode.normalize("ABC23"))
        assertEquals("ABC 234", RoomCode.display("ABC234"))
        assertEquals("realtime:ketogether:ABC234".removePrefix("realtime:"), RoomCode.topic("ABC234"))
    }

    @Test fun theLinkCarriesTheCodeAndTheHostKey() {
        val key = HostKey.generate()
        val link = TogetherLink.of("ABC234", key.fingerprint)
        assertTrue(link.startsWith("https://tysnnn23.github.io/Ketvoice/listen.html?track=ABC234-"))
        assertTrue(link.endsWith("&kind=together"))
        val track = java.net.URLDecoder.decode(link.substringAfter("track=").substringBefore('&'), "UTF-8")
        assertEquals(TogetherLink.Target("ABC234", key.fingerprint), TogetherLink.parse(track))
        assertEquals(TogetherLink.Target("ABC234", null), TogetherLink.parse("abc234"))
        assertNull(TogetherLink.parse("yt:abcdefghijk"))
    }

    @Test fun hostSignaturesHoldOnlyForTheSameTextAndKey() {
        val key = HostKey.generate()
        val other = HostKey.generate()
        val text = """{"t":"tick"}"""
        val signature = key.sign(text)
        assertTrue(HostKey.verify(key.publicKey, text, signature))
        assertFalse(HostKey.verify(other.publicKey, text, signature))
        assertFalse(HostKey.verify(key.publicKey, """{"t":"tock"}""", signature))
        assertFalse(HostKey.verify("not a key", text, signature))
        assertNotEquals(key.fingerprint, other.fingerprint)
        assertEquals(Fingerprint.LENGTH, key.fingerprint.length)
    }

    @Test fun phoenixFramesFollowTheRealtimeProtocol() {
        val join = JSONObject(PhoenixFrames.join("realtime:ketogether:ABC234", "1", "member0001", "anon"))
        assertEquals("phx_join", join.getString("event"))
        assertEquals(false, join.getJSONObject("payload").getJSONObject("config").getBoolean("private"))
        assertEquals("member0001", join.getJSONObject("payload").getJSONObject("config").getJSONObject("presence").getString("key"))
        val b = JSONObject(PhoenixFrames.broadcast("realtime:x", "2", "1", "kt", JSONObject().put("v", 1)))
        assertEquals("broadcast", b.getJSONObject("payload").getString("type"))
        assertEquals("kt", b.getJSONObject("payload").getString("event"))
        // As received from the server during the check against the real project (2026-11-17).
        val frame = PhoenixFrames.parse("""{"ref":null,"event":"broadcast","payload":{"event":"kt","payload":{"v":1},"type":"broadcast"},"topic":"realtime:ketogether:TEST7449"}""")!!
        assertNull(frame.ref)
        assertEquals("broadcast", frame.event)
        assertNull(PhoenixFrames.parse("garbage"))
        assertTrue(RealtimeTransport.refusal("Unauthorized: You do not have permissions to read from this Channel topic"))
        assertFalse(RealtimeTransport.refusal("tenant events per second exceeded"))
    }

    @Test fun presenceFollowsStateAndDiffs() {
        val book = PresenceBook()
        book.diff(JSONObject("""{"joins":{"guestB0001":{"metas":[{"phx_ref":"r1","n":"Invité","r":"g"}]}},"leaves":{}}"""))
        book.state(JSONObject("""{"guestB0001":{"metas":[{"phx_ref":"r1","n":"Invité","r":"g"}]},"hostA00001":{"metas":[{"phx_ref":"r2","n":"Hôte","r":"h"}]}}"""))
        assertEquals(listOf("Invité" to false, "Hôte" to true), book.members().map { it.name to it.host })
        book.diff(JSONObject("""{"joins":{},"leaves":{"guestB0001":{"metas":[{"phx_ref":"r1"}]}}}"""))
        assertEquals(listOf("hostA00001"), book.members().map { it.id })
        book.diff(JSONObject("""{"joins":{"../bad":{"metas":[{"phx_ref":"r3","n":"x"}]},"mail000001":{"metas":[{"phx_ref":"r4","n":"me@mail.com"}]}},"leaves":{}}"""))
        assertEquals(listOf("hostA00001", "mail000001"), book.members().map { it.id })
        assertEquals("?", book.members().last().name)
    }

    @Test fun reconnectionWaitsLongerEachTimeWithJitter() {
        val random = Random(3)
        val first = Backoff.delay(0, random)
        val late = Backoff.delay(20, random)
        assertTrue(first in 800..1_200)
        assertTrue(late in 24_000..36_000)
    }
}
