package com.ketvoice.desktop.connect

import com.ketvoice.app.data.Track
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Optional
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** « Connexions » on Windows: Discord's IPC frames and session, the activity shown, profiles' folders, small helpers. */
class ConnectTest {
    /** A pipe that answers from a script and records what was written. */
    private class FakePipe(vararg answers: ByteArray?) : IpcPipe {
        val written = ByteArrayOutputStream()
        private val queue = ArrayDeque(answers.toList())
        private var pending = ByteArray(0)
        var closed = false

        override fun write(bytes: ByteArray) { written.write(bytes) }

        override fun read(count: Int, timeoutMs: Long): ByteArray? {
            if (pending.isEmpty()) pending = queue.removeFirstOrNull() ?: return null
            val out = pending.copyOfRange(0, count)
            pending = pending.copyOfRange(count, pending.size)
            return out
        }

        override fun close() { closed = true }

        /** Every frame written: opcode and JSON. */
        fun frames(): List<Pair<Int, JSONObject>> {
            val bytes = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val out = ArrayList<Pair<Int, JSONObject>>()
            while (bytes.remaining() >= 8) {
                val op = bytes.int
                val body = ByteArray(bytes.int).also { bytes.get(it) }
                out += op to JSONObject(String(body, Charsets.UTF_8))
            }
            return out
        }
    }

    private fun frame(op: Int, json: JSONObject) = DiscordFrame.encode(op, json)
    private val ready = frame(DiscordFrame.FRAME, JSONObject().put("cmd", "DISPATCH").put("evt", "READY")
        .put("data", JSONObject().put("user", JSONObject().put("username", "kev").put("global_name", "Kev"))))

    @AfterTest fun tearDown() { ShareLinks.provider = { null } }

    @Test fun framesAreLittleEndianOpcodeLengthAndJson() {
        val bytes = DiscordFrame.handshake("1566454532890820668")
        assertEquals(listOf<Byte>(0, 0, 0, 0), bytes.take(4))
        val (op, length) = DiscordFrame.header(bytes.copyOfRange(0, 8))
        assertEquals(DiscordFrame.HANDSHAKE, op)
        assertEquals(bytes.size - 8, length)
        val body = JSONObject(String(bytes, 8, length, Charsets.UTF_8))
        assertEquals(1, body.getInt("v"))
        assertEquals("1566454532890820668", body.getString("client_id"))
        // A known frame byte for byte: op 1, length 2, "{}".
        assertEquals(listOf(1, 0, 0, 0, 2, 0, 0, 0, '{'.code, '}'.code), DiscordFrame.encode(1, JSONObject()).map { it.toInt() })
        val clear = JSONObject(String(DiscordFrame.setActivity(null, 42, "n-1").drop(8).toByteArray(), Charsets.UTF_8))
        assertEquals("SET_ACTIVITY", clear.getString("cmd"))
        assertTrue(clear.getJSONObject("args").isNull("activity"))
        assertEquals(42, clear.getJSONObject("args").getLong("pid"))
    }

    @Test fun theSessionTakesTheFirstPipeThatAnswersAndSendsActivities() {
        val silent = FakePipe(null)
        val discord = FakePipe(ready, frame(1, JSONObject().put("cmd", "SET_ACTIVITY").put("evt", JSONObject.NULL)),
            frame(DiscordFrame.PING, JSONObject().put("x", 1)) + frame(1, JSONObject().put("cmd", "SET_ACTIVITY").put("evt", "ERROR")))
        val pipes = mapOf("p0" to null, "p1" to silent, "p2" to discord)
        val ipc = DiscordIpc("123", { pipes[it] }, { listOf("p0", "p1", "p2") })
        assertTrue(ipc.connect())
        assertTrue(silent.closed)
        assertEquals("Kev", ipc.user)
        assertTrue(ipc.send(JSONObject().put("type", 2), pid = 7))
        // A ping on the way is answered with a pong; an ERROR answer is a refusal, not a lost connection.
        assertFalse(ipc.send(JSONObject().put("type", 2), pid = 7))
        assertTrue(ipc.connected)
        val frames = discord.frames()
        assertEquals(DiscordFrame.HANDSHAKE, frames[0].first)
        assertEquals("SET_ACTIVITY", frames[1].second.getString("cmd"))
        assertEquals(DiscordFrame.PONG, frames[3].first)
        // Discord gone: the next send fails and closes, ready to connect again later.
        assertFalse(ipc.send(null))
        assertFalse(ipc.connected)
    }

    @Test fun noDiscordMeansNoConnection() {
        val ipc = DiscordIpc("123", { null }, { DiscordPipes.candidates(windows = true) })
        assertFalse(ipc.connect())
        assertEquals((0..9).map { "\\\\.\\pipe\\discord-ipc-$it" }, DiscordPipes.candidates(windows = true))
        val unix = DiscordPipes.candidates(windows = false) { if (it == "XDG_RUNTIME_DIR") "/run/user/1000" else null }
        assertEquals("/run/user/1000/discord-ipc-0", unix.first())
        assertTrue("/tmp/discord-ipc-9" in unix)
    }

    @Test fun theActivityShowsTheTitleOrHidesIt() {
        val t = Track("t:1", "Midnight City", "M83", "Hurry Up", durationMs = 240_000, coverUrl = "https://img.example/cover.jpg")
        val shown = DiscordActivity.build(t, 60_000, 0, 1_000_000, hideTitle = false, shareUrl = "https://ketvoice-web.vercel.app/s/abc")
        assertEquals(DiscordActivity.LISTENING, shown.getInt("type"))
        assertEquals("Midnight City", shown.getString("details"))
        assertEquals("M83", shown.getString("state"))
        assertEquals(940_000, shown.getJSONObject("timestamps").getLong("start"))
        assertEquals(1_180_000, shown.getJSONObject("timestamps").getLong("end"))
        assertEquals("https://img.example/cover.jpg", shown.getJSONObject("assets").getString("large_image"))
        assertEquals("https://ketvoice-web.vercel.app/s/abc", shown.getJSONArray("buttons").getJSONObject(0).getString("url"))
        val hidden = DiscordActivity.build(t, 60_000, 0, 1_000_000, hideTitle = true, shareUrl = null)
        assertFalse(hidden.toString().contains("Midnight") || hidden.toString().contains("M83") || hidden.has("assets") || hidden.has("buttons"))
        val plain = DiscordActivity.build(t.copy(coverUrl = "http://insecure/c.jpg", title = "A"), 0, 0, 0, false, null)
        assertFalse(plain.getJSONObject("assets").has("large_image"))
        assertFalse(plain.has("buttons"))
        assertEquals(2, plain.getString("details").length)
    }

    @Test fun presenceIsSentAgainOnlyWhenWhatShowsChanges() {
        val a = DiscordActivity.Key("t:1", 10_000, false, null)
        assertTrue(DiscordPresence.same(a, a.copy(startedAt = 11_500)))
        assertFalse(DiscordPresence.same(a, a.copy(startedAt = 20_000)))
        assertFalse(DiscordPresence.same(a, a.copy(trackId = "t:2")))
        assertFalse(DiscordPresence.same(a, a.copy(hide = true)))
        assertFalse(DiscordPresence.same(null, a))
    }

    @Test fun shareLinksAreOnlyHttps() {
        val t = Track("t:1", "T", "A", "")
        assertNull(ShareLinks.trackUrl(t))
        ShareLinks.provider = { "javascript:alert(1)" }
        assertNull(ShareLinks.trackUrl(t))
        ShareLinks.provider = { error("not ready") }
        assertNull(ShareLinks.trackUrl(t))
        ShareLinks.provider = { "https://ketvoice-web.vercel.app/t/${it.id}" }
        assertEquals("https://ketvoice-web.vercel.app/t/t:1", ShareLinks.trackUrl(t))
    }

    @Test fun profilesHaveTheirOwnFoldersAndShareTheAudioCache() {
        val root = Files.createTempDirectory("kv-profiles").toFile()
        val before = System.getProperty("ketvoice.dataDir")
        val cacheBefore = com.ketvoice.desktop.audio.StreamCache.dir
        try {
            assertEquals(root, LocalProfiles.applyAtStart(emptyList(), root))
            assertEquals(LocalProfiles.MAIN, LocalProfiles.current)
            assertEquals(before, System.getProperty("ketvoice.dataDir"))
            val kid = LocalProfiles.create(" Léa\u0000 ", LocalProfiles.COLOURS[2], root)
            assertEquals("Léa", kid.name)
            assertTrue(File(root, "profiles/${kid.id}").isDirectory)
            val book = LocalProfiles.load(root)
            assertEquals(listOf(LocalProfiles.MAIN, kid.id), book.profiles.map { it.id })
            // A relaunch on that profile: its own folder, the main profile's audio cache.
            assertEquals(File(root, "profiles/${kid.id}"), LocalProfiles.applyAtStart(listOf("--profile=${kid.id}"), root))
            assertEquals(File(root, "profiles/${kid.id}").absolutePath, System.getProperty("ketvoice.dataDir"))
            assertEquals(File(root, "cache/audio"), com.ketvoice.desktop.audio.StreamCache.dir)
            assertFalse(LocalProfiles.delete(kid.id, root)) // the one in use stays
            assertFalse(LocalProfiles.delete(LocalProfiles.MAIN, root))
            LocalProfiles.applyAtStart(listOf("--profile=unknown1"), root)
            assertEquals(LocalProfiles.MAIN, LocalProfiles.current)
            LocalProfiles.rename(kid.id, "Léa B", LocalProfiles.COLOURS[3], root)
            assertEquals("Léa B", LocalProfiles.load(root).profiles[1].name)
            assertTrue(LocalProfiles.delete(kid.id, root))
            assertFalse(File(root, "profiles/${kid.id}").exists())
            // A damaged book still has the main profile, active.
            File(root, "profiles.json").writeText("{not json")
            assertEquals(LocalProfiles.Book(LocalProfiles.MAIN, listOf(LocalProfiles.Profile(LocalProfiles.MAIN, "Principal", LocalProfiles.COLOURS[0]))),
                LocalProfiles.load(root))
        } finally {
            if (before == null) System.clearProperty("ketvoice.dataDir") else System.setProperty("ketvoice.dataDir", before)
            com.ketvoice.desktop.audio.StreamCache.dir = cacheBefore
            root.deleteRecursively()
        }
    }

    @Test fun aSwitchRelaunchesTheSameProgramOnTheOtherProfile() {
        val info = object : ProcessHandle.Info {
            override fun command() = Optional.of("C:\\Users\\me\\AppData\\Local\\Ketvoice\\Ketvoice.exe")
            override fun commandLine() = Optional.empty<String>()
            override fun arguments() = Optional.of(arrayOf("--autostart", "--profile=old00000"))
            override fun startInstant() = Optional.empty<java.time.Instant>()
            override fun totalCpuDuration() = Optional.empty<java.time.Duration>()
            override fun user() = Optional.empty<String>()
        }
        assertEquals(listOf("C:\\Users\\me\\AppData\\Local\\Ketvoice\\Ketvoice.exe", "--autostart", "--profile=abcd1234"),
            LocalProfiles.relaunchCommand("abcd1234", info))
    }

    @Test fun theSessionIdComesFromTheAccessToken() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"u","session_id":"8b0a1c2e-1111-4222-8333-944445555666"}""".toByteArray())
        assertEquals("8b0a1c2e-1111-4222-8333-944445555666", LiveSync.jwtClaim("h.$payload.s", "session_id"))
        assertNull(LiveSync.jwtClaim("h.$payload.s", "missing"))
        assertNull(LiveSync.jwtClaim("nojwt", "session_id"))
    }

    @Test fun deletingTheAccountNeedsTheTypedWord() {
        assertTrue(MyData.confirmed(" supprimer "))
        assertFalse(MyData.confirmed("SUPPRIME"))
        assertEquals("ketvoice-mes-donnees-2026-11-25.json", MyData.defaultFile(java.time.LocalDate.of(2026, 11, 25)).name)
        assertNotNull(MyData.thisPc().optJSONObject("scrobbles_waiting"))
        assertFalse(MyData.thisPc().toString().contains("token"))
    }
}
