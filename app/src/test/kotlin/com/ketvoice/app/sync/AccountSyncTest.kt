package com.ketvoice.app.sync

import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.PlayerUiState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The account's sync: merge, tombstones, conflicts, the serialisation of each kind, « Reprendre ici ». */
class AccountSyncTest {
    private fun doc(vararg items: Pair<String, SyncItem>) = SyncDoc(items.toMap())
    private fun live(value: String, at: Long) = SyncItem(value, at)

    @Test fun mergeIsTheUnionAndTheNewestWinsPerItem() {
        val local = doc("a" to live("1", 10), "b" to live("\"phone\"", 30))
        val cloud = doc("b" to live("\"tablet\"", 20), "c" to live("3", 5))
        val merged = SyncMerge.merge(local, cloud)
        assertEquals(mapOf("a" to "1", "b" to "\"phone\"", "c" to "3"), merged.live)
        assertEquals("\"tablet\"", SyncMerge.merge(doc("b" to live("\"phone\"", 10)), cloud).live["b"])
    }

    @Test fun onATieTheCloudWinsSoEveryDeviceLandsOnTheSameValue() {
        val merged = SyncMerge.merge(doc("theme" to live("1", 0)), doc("theme" to live("2", 0)))
        assertEquals("2", merged.live["theme"])
    }

    @Test fun aRemovalTravelsAsATombstoneAndANewerAddBringsItBack() {
        val removed = SyncMerge.merge(doc("x" to SyncItem.tombstone(50)), doc("x" to live("1", 40)))
        assertFalse("x" in removed.live)
        assertTrue(removed.items.getValue("x").deleted)
        val readded = SyncMerge.merge(doc("x" to SyncItem.tombstone(50)), doc("x" to live("1", 60)))
        assertEquals("1", readded.live["x"])
    }

    @Test fun stampingMarksOnlyWhatChangedOnThisDevice() {
        val previous = doc("keep" to live("1", 10), "edit" to live("1", 10), "gone" to live("1", 10), "dead" to SyncItem.tombstone(5))
        val stamped = SyncMerge.stamp(previous, mapOf("keep" to "1", "edit" to "2", "new" to "3"), now = 100)
        assertEquals(live("1", 10), stamped.items["keep"])
        assertEquals(live("2", 100), stamped.items["edit"])
        assertEquals(SyncItem.tombstone(100), stamped.items["gone"])
        assertEquals(SyncItem.tombstone(5), stamped.items["dead"])
        assertEquals(live("3", 100), stamped.items["new"])
    }

    @Test fun aDevicesFirstSyncAddsWhatOnlyItHadButLetsTheAccountWinConflicts() {
        val first = SyncMerge.stamp(SyncDoc(), mapOf("themeMode" to "0", "onlyHere" to "true"), now = 1_000, initial = true)
        assertTrue(first.items.values.all { it.updatedAt == 0L })
        val merged = SyncMerge.merge(first, doc("themeMode" to live("2", 500)))
        assertEquals(mapOf("themeMode" to "2", "onlyHere" to "true"), merged.live)
    }

    @Test fun oldTombstonesAreForgotten() {
        val now = SyncMerge.TOMBSTONE_KEEP_MS + 10_000
        val pruned = SyncMerge.prune(doc("old" to SyncItem.tombstone(1), "recent" to SyncItem.tombstone(now - 5), "v" to live("1", 1)), now)
        assertEquals(setOf("recent", "v"), pruned.items.keys)
    }

    @Test fun theWireFormatRoundTripsAndIgnoresKeyOrder() {
        val original = doc("pl:1" to live(Canonical.text(JSONObject().put("name", "A").put("entries", org.json.JSONArray())), 7),
            "gone" to SyncItem.tombstone(9))
        val decoded = SyncMerge.decode(JSONObject(SyncMerge.encode(original).toString()))
        assertEquals(original, decoded)
        // Postgres jsonb gives keys back in its own order: the canonical text is the same.
        assertEquals(Canonical.text(JSONObject("""{"b":1,"a":{"d":[1,"x"],"c":null}}""")),
            Canonical.text(JSONObject("""{"a":{"c":null,"d":[1,"x"]},"b":1}""")))
        assertEquals("""{"a":{"c":null,"d":[1,"x"]},"b":1}""", Canonical.text(JSONObject("""{"b":1,"a":{"d":[1,"x"],"c":null}}""")))
    }

    @Test fun badCloudRowsAreLeftOutInsteadOfBreakingTheSync() {
        val raw = JSONObject().put("items", JSONObject().put("ok", JSONObject().put("t", 3).put("v", 1))
            .put("noTime", JSONObject().put("v", 1)).put("noValue", JSONObject().put("t", 3)).put("notAnObject", 5))
        assertEquals(mapOf("ok" to "1"), SyncMerge.decode(raw).live)
        assertEquals(SyncDoc(), SyncMerge.decode(null))
    }

    /** An in-memory `ketvoice_library` row with the server's revision rule. */
    private class FakeCloud : CloudPort {
        val rows = HashMap<String, Pair<JSONObject, Long>>()
        var raceOnce: (() -> Unit)? = null
        var writes = 0
        override suspend fun fetch(kind: String) = rows[kind]?.let { CloudDoc(JSONObject(it.first.toString()), it.second, "", "") }
        override suspend fun insert(kind: String, doc: JSONObject, deviceId: String): Boolean {
            raceOnce?.also { raceOnce = null }?.invoke()
            if (kind in rows) return false
            rows[kind] = doc to 1L; writes++; return true
        }
        override suspend fun update(kind: String, doc: JSONObject, revision: Long, deviceId: String): Boolean {
            raceOnce?.also { raceOnce = null }?.invoke()
            val current = rows[kind] ?: return false
            if (current.second != revision) return false
            rows[kind] = doc to revision + 1; writes++; return true
        }
    }

    private class Device(val cloud: FakeCloud, var clock: Long) {
        var previous: SyncDoc? = null
        var values: Map<String, String> = emptyMap()
        fun sync(kind: String = "favorites") = runBlocking {
            val merged = SyncRound(cloud, "device-0001", { clock }).run(kind, previous, values)
            previous = merged; values = merged.live
        }
    }

    @Test fun twoDevicesConvergeOnAdditionsAndRemovals() {
        val cloud = FakeCloud()
        val phone = Device(cloud, 1_000).apply { values = mapOf("song:a" to "1") }
        val tablet = Device(cloud, 1_000).apply { values = mapOf("song:b" to "1") }
        phone.sync(); tablet.sync(); phone.sync()
        assertEquals(setOf("song:a", "song:b"), phone.values.keys)
        assertEquals(phone.values, tablet.values)
        // Removed on the tablet: the phone learns of it instead of bringing it back.
        tablet.clock = 2_000; tablet.values = tablet.values - "song:a"; tablet.sync()
        phone.clock = 3_000; phone.sync()
        assertEquals(setOf("song:b"), phone.values.keys)
    }

    @Test fun aWriteThatLostTheRaceIsMergedAgainNotLost() {
        val cloud = FakeCloud()
        val phone = Device(cloud, 1_000).apply { values = mapOf("a" to "1") }
        phone.sync()
        val tablet = Device(cloud, 1_100).apply { previous = phone.previous; values = phone.values + ("t" to "1") }
        phone.clock = 1_200; phone.values = phone.values + ("p" to "1")
        // The tablet writes between the phone's read and the phone's write.
        cloud.raceOnce = { tablet.sync() }
        phone.sync()
        assertEquals(setOf("a", "p", "t"), phone.values.keys)
        assertEquals(setOf("a", "p", "t"), SyncMerge.decode(cloud.rows.getValue("favorites").first).live.keys)
    }

    @Test fun nothingIsWrittenWhenTheCloudAlreadyHoldsTheSameLibrary() {
        val cloud = FakeCloud()
        val phone = Device(cloud, 1_000).apply { values = mapOf("a" to "1") }
        phone.sync(); val writes = cloud.writes
        phone.clock = 9_000; phone.sync()
        assertEquals(writes, cloud.writes)
    }

    @Test fun playlistsKeepTheirEntriesTheirOrderAndNoStreamAddress() {
        val track = Track("tidal:1", "Song", "Artist", "Album", streamUrl = "https://cdn.example/?token=SECRET",
            downloadUrl = "https://cdn.example/d?sig=SECRET", canvasUrl = "https://canvas.example/x?t=SECRET", coverUrl = "https://img.example/c.jpg")
        val first = LocalPlaylist("p1", "Route", listOf(PlaylistEntry("e2", "Two", "B"), PlaylistEntry("e1", "Song", "Artist", track = track)), createdAt = 5, pinned = true)
        val second = LocalPlaylist("p2", "Soir", createdAt = 9)
        val values = mapOf("pl:p1" to Canonical.text(PlaylistsKind.encode(first)), "pl:p2" to Canonical.text(PlaylistsKind.encode(second)),
            PlaylistsKind.ORDER to Canonical.text(org.json.JSONArray(listOf("p2", "p1"))))
        assertFalse(values.values.any { "SECRET" in it })
        val restored = PlaylistsKind.ordered(values)
        assertEquals(listOf("p2", "p1"), restored.map { it.id })
        assertEquals(listOf("e2", "e1"), restored[1].entries.map { it.id })
        assertTrue(restored[1].pinned)
        assertEquals("https://img.example/c.jpg", restored[1].entries[1].track?.coverUrl)
        // Encoding what was decoded gives the same text: no device sees a change that did not happen.
        assertEquals(values["pl:p1"], Canonical.text(PlaylistsKind.encode(restored[1])))
    }

    @Test fun settingsTravelAsCheckedValuesOnly() {
        assertEquals(2, SettingsKind.parse("themeMode", "2"))
        assertEquals(4294912341L, SettingsKind.parse("accentArgb", "4294912341"))
        assertEquals(true, SettingsKind.parse("syncedLyrics", "true"))
        assertNull(SettingsKind.parse("themeMode", "99"))
        assertNull(SettingsKind.parse("betterLyricsKey", "\"SECRET\""))
        assertFalse("highPerformanceMode" in SettingsKind.keys)
        assertFalse("cacheBytes" in SettingsKind.keys)
    }

    @Test fun localFilesStayOutOfTheAccount() {
        assertFalse(FavoritesKind.syncable("local:content://media/1"))
        assertTrue(FavoritesKind.syncable("tidal:1"))
    }

    @Test fun nowPlayingCarriesTheQueueAroundTheTitleWithoutLocalFiles() {
        val queue = (0 until 80).map { Track(if (it == 3) "local:$it" else "t:$it", "Title $it", "A", "") }
        val state = PlayerUiState(queue = queue, index = 40, isPlaying = true, positionMs = 61_000)
        val json = NowPlayingDoc.encode(state, "device-0001", "Pixel 9", at = 1_000)!!
        val session = NowPlayingDoc.decode(JSONObject(json.toString()))!!
        assertEquals("t:40", session.track.id)
        assertEquals(NowPlayingDoc.QUEUE_LIMIT, session.queue.size)
        assertTrue(session.queue.none { it.id.startsWith("local:") })
        assertEquals("Pixel 9", session.deviceName)
        assertNull(NowPlayingDoc.encode(state.copy(index = 3), "device-0001", "Pixel 9", 1_000))
    }

    @Test fun resumingPicksUpWhereTheOtherDeviceIsNow() {
        val track = Track("t:1", "T", "A", "", durationMs = 200_000)
        val playing = RemoteSession("other-device", "Quest", listOf(track), 0, 60_000, playing = true, at = 1_000_000)
        assertEquals(90_000, playing.positionAt(1_030_000))
        assertTrue(playing.live(1_030_000))
        assertFalse(playing.live(1_000_000 + 120_000))
        assertEquals(199_000, playing.positionAt(1_000_000 + 3_600_000))
        assertEquals(60_000, playing.copy(playing = false).positionAt(1_030_000))
    }

    @Test fun theOfferIsOnlyForAnotherRecentDeviceThatIsNotHeardHere() {
        val track = Track("t:1", "T", "A", "")
        val session = RemoteSession("other-device", "Quest", listOf(track), 0, 0, true, at = 1_000)
        val idle = PlayerUiState()
        assertNotNull(NowPlayingSync.offer(session, "this-device", 2_000, idle, 0))
        assertNull(NowPlayingSync.offer(session, "other-device", 2_000, idle, 0))
        assertNull(NowPlayingSync.offer(session, "this-device", 1_000 + NowPlayingSync.OFFER_FOR_MS, idle, 0))
        assertNull(NowPlayingSync.offer(session, "this-device", 2_000, idle, dismissedAt = 1_000))
        assertNull(NowPlayingSync.offer(session, "this-device", 2_000, PlayerUiState(queue = listOf(Track("t:9", "X", "", "")), index = 0, isPlaying = true), 0))
    }

    @Test fun devicesAreNamedByTheirModel() {
        assertEquals("Google Pixel 9", DeviceIdentity.label("Google", "Pixel 9"))
        assertEquals("SM-X710", DeviceIdentity.label("", "SM-X710"))
        assertEquals("Honor Magic6", DeviceIdentity.label("honor", "Magic6"))
        assertEquals("HONOR Magic6 Pro", DeviceIdentity.label("HONOR", "HONOR Magic6 Pro"))
        assertEquals("Meta Quest 3", DeviceIdentity.label("Oculus", "Quest 3"))
        assertEquals("Appareil", DeviceIdentity.label("", ""))
    }
}
