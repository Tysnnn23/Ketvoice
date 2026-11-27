package com.ketvoice.app.tv

import com.ketvoice.app.account.AccountProfile
import com.ketvoice.app.account.AccountState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** « Qui écoute ? »: each profile's data kept apart, the switch at start, sessions moved without a copy too many. */
class TvProfileStoreTest {
    private lateinit var data: File
    private lateinit var store: TvProfileStore
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"

    private class MapSlots(val map: MutableMap<String, String> = linkedMapOf()) : TvSessionSlots {
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String) { map[name] = value }
        override fun remove(name: String) { map.remove(name) }
    }

    /** A session's text names its user, as AccountSession.encode does. */
    private fun session(user: String) = "session-of:$user"
    private val userOf: (String) -> String? = { it.removePrefix("session-of:").takeIf { u -> TvProfileBook.validId(u) } }

    @Before fun setUp() {
        data = Files.createTempDirectory("tv-profiles").toFile()
        store = TvProfileStore(File(data, TvProfiles.STORE_DIR))
    }

    @After fun tearDown() {
        data.deleteRecursively()
        TvProfiles.showForTest(TvProfileBook())
        TvProfiles.picking.value = false
    }

    private fun write(path: String, text: String) = File(data, path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun read(path: String): String? = File(data, path).takeIf { it.isFile }?.readText()

    /** A's data in the app's folders: its own settings, library and downloads, and what the whole TV shares. */
    private fun aIsMounted() {
        write("shared_prefs/ketvoice_settings.xml", "A settings")
        write("shared_prefs/listening-history.xml", "A history")
        write("shared_prefs/ketmix-points-v1.xml", "A mix points")
        write("files/playlists-v1.json", "A playlists")
        write("files/downloads/t1.flac", "A download")
        write("files/account/avatar-1.jpg", "A photo")
        write("files/playback-session-v1.json", "A queue")
        write("shared_prefs/private-sessions.xml", "sessions")
        write("shared_prefs/ketvoice-device.xml", "device id")
        write("shared_prefs/androidx.work.util.preferences.xml", "work")
        write("shared_prefs/tv-device.xml", "offer flags")
        write("files/songs-v2/cache.bin", "stream cache")
        write("files/canvas/c.mp4", "canvas")
        write("files/automix-analysis.json", "measurements")
        write("files/beat/model/ketmix-beat-small0.tflite", "model")
        write("databases/exoplayer_internal.db", "db")
    }

    @Test fun theBookSurvivesARestartAndADamagedFileReadsEmpty() {
        val book = TvProfileBook(listOf(TvProfile(a, "Léa", "lea@example.com"), TvProfile(b, "Sam")), mounted = a, pending = b, skipPicker = true)
        store.save(book)
        assertEquals(book, TvProfileStore(store.root).load())
        File(store.root, "book.json").writeText("{not json")
        assertEquals(TvProfileBook(), store.load())
        // Foreign ids and control characters are left out.
        val parsed = TvProfileBook.parse("""{"mounted":"../x","profiles":[{"id":"../../etc","name":"x"},{"id":"$a","name":"Lé\u0007a"}]}""")
        assertEquals(listOf(TvProfile(a, "Léa")), parsed.profiles)
        assertEquals(TvProfiles.NOBODY, parsed.mounted)
    }

    @Test fun puttingAProfileAwayMovesOnlyItsOwnData() {
        aIsMounted()
        TvDataMount(data).unmount(store.area(a))
        // A's own data left the app's folders, into its area.
        assertNull(read("shared_prefs/ketvoice_settings.xml"))
        assertNull(read("files/playlists-v1.json"))
        assertNull(read("files/downloads/t1.flac"))
        assertEquals("A settings", File(store.area(a), "shared_prefs/ketvoice_settings.xml").readText())
        assertEquals("A download", File(store.area(a), "files/downloads/t1.flac").readText())
        assertEquals("A mix points", File(store.area(a), "shared_prefs/ketmix-points-v1.xml").readText())
        // Switching clears the queue.
        assertFalse(File(store.area(a), "files/playback-session-v1.json").exists())
        // What the TV shares stays where it is.
        assertEquals("sessions", read("shared_prefs/private-sessions.xml"))
        assertEquals("device id", read("shared_prefs/ketvoice-device.xml"))
        assertEquals("work", read("shared_prefs/androidx.work.util.preferences.xml"))
        assertEquals("offer flags", read("shared_prefs/tv-device.xml"))
        assertEquals("stream cache", read("files/songs-v2/cache.bin"))
        assertEquals("canvas", read("files/canvas/c.mp4"))
        assertEquals("measurements", read("files/automix-analysis.json"))
        assertEquals("model", read("files/beat/model/ketmix-beat-small0.tflite"))
        assertEquals("db", read("databases/exoplayer_internal.db"))
    }

    @Test fun switchingSwapsDataAndSessionsAndNeverMixesThem() {
        aIsMounted()
        // B was added earlier: its data waits in its area, its session in its slot.
        File(store.area(b), "shared_prefs").mkdirs()
        File(store.area(b), "shared_prefs/ketvoice_settings.xml").writeText("B settings")
        File(store.area(b), "files").mkdirs()
        File(store.area(b), "files/playlists-v1.json").writeText("B playlists")
        val slots = MapSlots(linkedMapOf(TvSessions.ACTIVE to session(a), TvSessions.slot(b) to session(b)))
        val book = TvProfileBook(listOf(TvProfile(a, "Léa"), TvProfile(b, "Sam")), mounted = a, pending = b, skipPicker = true)
        val after = TvProfileSwitch.run(book, store, TvDataMount(data), slots, userOf)

        assertEquals(b, after.mounted)
        assertNull(after.pending)
        assertTrue("no picker right after a switch", after.skipPicker)
        assertEquals(after, store.load())
        assertEquals("B settings", read("shared_prefs/ketvoice_settings.xml"))
        assertEquals("B playlists", read("files/playlists-v1.json"))
        assertNull("A's downloads are not B's", read("files/downloads/t1.flac"))
        assertNull("nor its history", read("shared_prefs/listening-history.xml"))
        assertFalse("B's area is in use, not kept twice", store.area(b).exists())
        assertEquals("A download", File(store.area(a), "files/downloads/t1.flac").readText())
        // One copy of each session: B's is the one in use, A's waits in its slot.
        assertEquals(mapOf(TvSessions.ACTIVE to session(b), TvSessions.slot(a) to session(a)), slots.map)

        // And back to A: everything as it was, B's data put away in turn.
        val back = TvProfileSwitch.run(after.copy(pending = a), store, TvDataMount(data), slots, userOf)
        assertEquals(a, back.mounted)
        assertEquals("A settings", read("shared_prefs/ketvoice_settings.xml"))
        assertEquals("A download", read("files/downloads/t1.flac"))
        assertEquals("B settings", File(store.area(b), "shared_prefs/ketvoice_settings.xml").readText())
        assertEquals(mapOf(TvSessions.ACTIVE to session(a), TvSessions.slot(b) to session(b)), slots.map)
    }

    @Test fun aSwitchInterruptedHalfWayFinishesAtTheNextStart() {
        aIsMounted()
        val slots = MapSlots(linkedMapOf(TvSessions.ACTIVE to session(a), TvSessions.slot(b) to session(b)))
        // The sessions were swapped and the TV went off before the files moved.
        TvSessions.swap(slots, b, userOf)
        TvSessions.swap(slots, b, userOf)
        assertEquals(mapOf(TvSessions.ACTIVE to session(b), TvSessions.slot(a) to session(a)), slots.map)
        val book = TvProfileBook(listOf(TvProfile(a), TvProfile(b)), mounted = a, pending = b)
        TvProfileSwitch.run(book, store, TvDataMount(data), slots, userOf)
        assertEquals(mapOf(TvSessions.ACTIVE to session(b), TvSessions.slot(a) to session(a)), slots.map)
        assertNull(read("files/playlists-v1.json"))
        assertEquals("A playlists", File(store.area(a), "files/playlists-v1.json").readText())
    }

    @Test fun removingTheProfileInUseDeletesItsDataAndSessionAtTheRestart() {
        aIsMounted()
        store.avatar(a).apply { parentFile?.mkdirs(); writeText("photo") }
        // Signed out on the server first: its session is already gone from the store.
        val slots = MapSlots(linkedMapOf(TvSessions.slot(b) to session(b)))
        val book = TvProfileBook(listOf(TvProfile(a), TvProfile(b)), mounted = a, pending = b, drop = a)
        val after = TvProfileSwitch.run(book, store, TvDataMount(data), slots, userOf)
        assertEquals(listOf(TvProfile(b)), after.profiles)
        assertEquals(b, after.mounted)
        assertFalse(store.area(a).exists())
        assertFalse(store.avatar(a).exists())
        assertNull(read("files/downloads/t1.flac"))
        assertEquals(mapOf(TvSessions.ACTIVE to session(b)), slots.map)
        // The TV's shared files are untouched.
        assertEquals("stream cache", read("files/songs-v2/cache.bin"))
    }

    @Test fun removingTheLastProfileLeavesTheSignedOutData() {
        aIsMounted()
        val slots = MapSlots()
        val after = TvProfileSwitch.run(TvProfileBook(listOf(TvProfile(a)), mounted = a, pending = TvProfiles.NOBODY, drop = a),
            store, TvDataMount(data), slots, userOf)
        assertEquals(TvProfiles.NOBODY, after.mounted)
        assertTrue(after.profiles.isEmpty())
        assertNull(read("shared_prefs/ketvoice_settings.xml"))
        assertTrue(slots.map.isEmpty())
    }

    @Test fun theFirstSignInMakesThisDataItsProfileAndThePickerNeedsTwo() {
        TvProfiles.showForTest(TvProfileBook())
        TvProfiles.remember(AccountState(a, "lea@example.com"), AccountProfile("Léa"), null)
        val book = TvProfiles.book.value
        assertEquals(a, book.mounted)
        assertEquals(listOf(TvProfile(a, "Léa", "lea@example.com")), book.profiles)
        TvProfiles.launch()
        assertFalse("one profile: no picker", TvProfiles.picking.value)
        // Another account's state never takes over a profile's data.
        TvProfiles.remember(AccountState(b, "sam@example.com"), AccountProfile("Sam"), null)
        assertEquals(listOf(TvProfile(a, "Léa", "lea@example.com")), TvProfiles.book.value.profiles)
        TvProfiles.showForTest(book.upsert(TvProfile(b, "Sam")))
        TvProfiles.launch()
        assertTrue("two profiles: « Qui écoute ? » at launch", TvProfiles.picking.value)
        TvProfiles.picking.value = false
        TvProfiles.showForTest(TvProfiles.book.value.copy(skipPicker = true))
        TvProfiles.launch()
        assertFalse("not right after a switch", TvProfiles.picking.value)
        assertFalse(TvProfiles.book.value.skipPicker)
    }
}
