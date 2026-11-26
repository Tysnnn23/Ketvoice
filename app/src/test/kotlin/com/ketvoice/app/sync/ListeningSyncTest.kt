package com.ketvoice.app.sync

import com.ketvoice.app.data.DayStat
import com.ketvoice.app.data.DeviceListening
import com.ketvoice.app.data.Heard
import com.ketvoice.app.data.ListeningShare
import com.ketvoice.app.data.MonthStat
import com.ketvoice.app.data.Track
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 9.2 « tout synchroniser »: listening summed per device, recents interleaved, Discord's choice and links. */
class ListeningSyncTest {
    private val today = LocalDate.of(2026, 11, 26)
    private fun t(id: String, title: String = "Titre $id", artist: String = "Artiste") =
        Track(id, title, artist, "Album", coverUrl = "https://example.com/$id.jpg")
    private fun device(counts: Map<String, Int>, ms: Long, recent: List<Heard> = emptyList()) = DeviceListening(
        recent = recent, known = recent.map { it.track }, counts = counts, listenedMs = ms,
        days = mapOf(today.toString() to DayStat(ms, counts, List(24) { if (it == 20) ms else 0L }, 1, 2)),
        years = mapOf("2026" to DayStat(ms, counts)),
        months = mapOf("2026-11" to MonthStat(ms, counts, List(24) { 0L }, listOf("Artiste"), recent.associate { it.track.id to it.track })))

    @Test fun eachDeviceIsCountedOnceAndTheFiguresAreSummed() {
        val phone = device(mapOf("a" to 2, "b" to 1), 60_000)
        val pc = device(mapOf("a" to 3), 30_000)
        val all = ListeningShare.combine(phone, listOf(pc))
        assertEquals(mapOf("a" to 5, "b" to 1), all.counts)
        assertEquals(90_000L, all.listenedMs)
        val day = all.days.getValue(today.toString())
        assertEquals(90_000L, day.ms); assertEquals(90_000L, day.hours[20]); assertEquals(4, day.mixes)
        assertEquals(mapOf("a" to 5, "b" to 1), all.years.getValue("2026").plays)
        // The same artist discovered on both devices is one discovery of the month.
        assertEquals(listOf("Artiste"), all.months.getValue("2026-11").newArtists)
        // Alone, a device's own figures are shown as they are.
        assertTrue(ListeningShare.combine(phone, emptyList()) === phone)
    }

    @Test fun recentsOfSeveralDevicesInterleaveByTimeEachSongOnce() {
        val phone = device(emptyMap(), 0, listOf(Heard(t("p1"), 300), Heard(t("same", "Song", "X"), 100)))
        val pc = device(emptyMap(), 0, listOf(Heard(t("c1"), 400), Heard(t("other-id", "Song", "X"), 200)))
        val ids = ListeningShare.combine(phone, listOf(pc)).recent.map { it.track.id }
        assertEquals(listOf("c1", "p1", "other-id"), ids)
    }

    @Test fun aDeviceListeningTravelsUnderItsOwnKeysAndComesBackWhole() {
        val own = device(mapOf("a" to 2), 45_000, listOf(Heard(t("a"), 1_000)))
        val values = ListeningKind.encode("phone-0001", own, today)
        assertTrue(values.keys.all { it.startsWith("dev:phone-0001:") })
        assertTrue("dev:phone-0001:m:2026-11" in values)
        val back = ListeningKind.decode(values, except = "pc-000001").getValue("phone-0001")
        assertEquals(own.counts, back.counts)
        assertEquals(own.listenedMs, back.listenedMs)
        assertEquals(own.days, back.days)
        assertEquals(listOf("a"), back.recent.map { it.track.id })
        assertEquals(1_000L, back.recent.single().at)
        assertEquals(2, back.months.getValue("2026-11").plays["a"])
        // A device never reads its own keys as another device's.
        assertTrue(ListeningKind.decode(values, except = "phone-0001").isEmpty())
    }

    @Test fun localFilesAndStreamAddressesStayOnTheDevice() {
        val local = Track("local:42", "Mon fichier", "Moi", "", streamUrl = "content://x", coverUrl = "file:///cover.jpg")
        assertNull(ListeningShare.shareable(local))
        val song = ListeningShare.shareable(t("a").copy(streamUrl = "https://stream", coverUrl = "file:///x.jpg"))!!
        assertNull(song.streamUrl); assertNull(song.coverUrl)
    }

    @Test fun theOtherDevicesKeysAreKeptSoNothingTravelsAsARemoval() {
        val other = ListeningKind.encode("pc-000001", device(mapOf("x" to 1), 1_000), today)
        val previous = SyncMerge.stamp(SyncDoc(), other, now = 10)
        val mine = ListeningKind.encode("phone-0001", device(mapOf("a" to 1), 2_000), today)
        val snapshot = ListeningKind.values(previous.live, "phone-0001", mine)
        val stamped = SyncMerge.stamp(previous, snapshot, now = 20)
        assertTrue(stamped.items.values.none { it.deleted })
        assertTrue(other.keys.all { stamped.items.getValue(it).updatedAt == 10L })
    }

    /** An in-memory `ketvoice_library` row with the server's revision rule. */
    private class FakeCloud : CloudPort {
        val docs = HashMap<String, Pair<String, Long>>()
        override suspend fun fetch(kind: String) = docs[kind]?.let { CloudDoc(JSONObject(it.first), it.second, "", "") }
        override suspend fun insert(kind: String, doc: JSONObject, deviceId: String) =
            if (kind in docs) false else { docs[kind] = doc.toString() to 1L; true }
        override suspend fun update(kind: String, doc: JSONObject, revision: Long, deviceId: String) =
            if (docs[kind]?.second != revision) false else { docs[kind] = doc.toString() to revision + 1; true }
    }

    @Test fun twoDevicesSeeEachOthersListeningAndRoundsNeverAddItTwice() = runBlocking {
        val cloud = FakeCloud()
        var clock = 1_000L
        val phoneOwn = ListeningKind.encode("phone-0001", device(mapOf("a" to 2), 60_000), today)
        val pcOwn = ListeningKind.encode("pc-000001", device(mapOf("a" to 3), 30_000), today)
        val phone = SyncRound(cloud, "phone-0001") { clock }
        val pc = SyncRound(cloud, "pc-000001") { clock }
        var phoneDoc = phone.run("listening", null, ListeningKind.values(emptyMap(), "phone-0001", phoneOwn))
        clock += 10
        var pcDoc = pc.run("listening", null, ListeningKind.values(emptyMap(), "pc-000001", pcOwn))
        repeat(3) {
            clock += 10
            phoneDoc = phone.run("listening", phoneDoc, ListeningKind.values(phoneDoc.live, "phone-0001", phoneOwn))
            clock += 10
            pcDoc = pc.run("listening", pcDoc, ListeningKind.values(pcDoc.live, "pc-000001", pcOwn))
        }
        val atPhone = ListeningKind.decode(phoneDoc.live, except = "phone-0001").values.toList()
        val atPc = ListeningKind.decode(pcDoc.live, except = "pc-000001").values.toList()
        val own = ListeningKind.decode(phoneOwn, except = "").values.single()
        assertEquals(mapOf("a" to 5), ListeningShare.combine(own, atPhone).counts)
        assertEquals(mapOf("a" to 3), atPhone.single().counts)
        assertEquals(mapOf("a" to 2), atPc.single().counts)
        assertEquals(90_000L, ListeningShare.combine(own, atPhone).listenedMs)
    }

    @Test fun discordsChoiceFollowsTheLatestChangeAndADefaultNeverOverridesIt() {
        assertNull(ConnectionsKind.shareValue(null, here = true, chosen = false))
        assertEquals("true", ConnectionsKind.shareValue(null, here = true, chosen = true))
        assertEquals("false", ConnectionsKind.shareValue("true", here = false, chosen = false))
        assertEquals("true", ConnectionsKind.shareValue("true", here = true, chosen = false))
    }

    @Test fun eachDeviceTellsItsDiscordLinkAndKeepsTheOthers() {
        val phone = DiscordLink("Pixel 9", "android", true, "ketfan", true)
        val agreed = mapOf(ConnectionsKind.LINK_PREFIX + "phone-0001" to Canonical.text(ConnectionsKind.encode(phone)))
        val pc = DiscordLink("BUREAU", "windows", false, null, true)
        val values = ConnectionsKind.values(agreed, "pc-000001", pc, here = true, chosen = false)
        assertEquals(setOf(ConnectionsKind.LINK_PREFIX + "phone-0001", ConnectionsKind.LINK_PREFIX + "pc-000001"), values.keys)
        assertEquals(phone, ConnectionsKind.decode(values.getValue(ConnectionsKind.LINK_PREFIX + "phone-0001")))
        ConnectionsKind.adopt(values, "pc-000001")
        assertEquals(listOf(phone), ConnectionsKind.others.value)
        assertEquals(phone, ConnectionsKind.elsewhere(ConnectionsKind.others.value))
        ConnectionsKind.forget()
        assertFalse(ConnectionsKind.others.value.isNotEmpty())
        // Nothing secret can ride along: unknown fields are dropped and a broken row is left out.
        assertNull(ConnectionsKind.decode("{\"platform\":\"android\"}"))
        val decoded = ConnectionsKind.decode("{\"device\":\"Pixel\",\"linked\":true,\"token\":\"abc\"}")!!
        assertFalse("abc" in Canonical.text(ConnectionsKind.encode(decoded)))
    }

    @Test fun anEmojiOnlyPlaylistKeepsItsNameAndItsCover() {
        val list = com.ketvoice.app.data.LocalPlaylist("pl-1", "🔥", coverUrl = "https://example.com/cover.jpg",
            entries = listOf(com.ketvoice.app.data.PlaylistEntry("e1", "Song", "X", track = t("a"))))
        val back = PlaylistsKind.decode("pl-1", Canonical.text(PlaylistsKind.encode(list)))!!
        assertEquals("🔥", back.name)
        assertEquals("https://example.com/cover.jpg", back.coverUrl)
        assertEquals("https://example.com/a.jpg", back.entries.single().track?.coverUrl)
    }
}
