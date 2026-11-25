package com.ketvoice.app.sync

import com.ketvoice.app.data.Track
import com.ketvoice.app.player.PlayerUiState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Live sync between devices: the device rows, merging them with the older single document, the offer, when to write. */
class PlaybackSyncTest {
    private val track = Track("t:1", "Title", "Artist", "", durationMs = 200_000)
    private val other = Track("t:2", "Other", "Artist", "", durationMs = 180_000)

    private fun session(id: String, at: Long, t: Track = track, playing: Boolean = true) =
        RemoteSession(id, "Device", listOf(t), 0, 30_000, playing, at)

    @Test fun aDeviceRowCarriesItsStateBothWays() {
        val playing = NowPlayingDoc.encode(PlayerUiState(queue = listOf(track, other), index = 1, isPlaying = true, positionMs = 12_000),
            "pc-device-0001", "DESKTOP-1", 5_000)
        val row = DeviceRow.encode("pc-device-0001", "DESKTOP-1\u0007", "pc", 6_000, "8b0a1c2e-1111-4222-8333-944445555666", playing)
        assertEquals("DESKTOP-1", row.getString("name"))
        val back = DeviceRow.decode(JSONObject(row.toString()).put("revoked_at", JSONObject.NULL))!!
        assertEquals("pc", back.kind)
        assertEquals(6_000, back.lastSeen)
        assertEquals("t:2", back.session!!.track.id)
        assertEquals(12_000, back.session!!.positionMs)
        assertFalse(back.revoked)
        assertTrue(DeviceRow.decode(JSONObject(row.toString()).put("revoked_at", "2026-11-25T10:00:00Z"))!!.revoked)
    }

    @Test fun aRowWithoutPlaybackStillListsTheDevice() {
        val row = DeviceRow.encode("phone-device-01", "Pixel 9", "phone", 1_000, null, null)
        assertTrue(row.isNull("session_id"))
        val back = DeviceRow.decode(row)!!
        assertNull(back.session)
        assertNull(DeviceRow.decode(JSONObject().put("device_id", "x")))
    }

    @Test fun mergingKeepsTheNewestSessionAndAddsThePhoneOfTheSingleDocument() {
        val pc = DeviceState("pc-device-0001", "DESKTOP-1", "pc", 10_000, session("pc-device-0001", 9_000))
        val older = pc.copy(lastSeen = 4_000, session = session("pc-device-0001", 3_000, other))
        val phoneDoc = session("phone-device-01", 20_000, other)
        val merged = PlaybackMerge.devices(listOf(older, pc), phoneDoc)
        assertEquals(listOf("phone-device-01", "pc-device-0001"), merged.map { it.deviceId })
        assertEquals("t:1", merged[1].session!!.track.id)
        assertEquals("phone", merged[0].kind)
        // The document of a device that has a row keeps the row's name and kind.
        val named = PlaybackMerge.devices(listOf(pc), session("pc-device-0001", 30_000, other))
        assertEquals(1, named.size)
        assertEquals("pc", named[0].kind)
        assertEquals("t:2", named[0].session!!.track.id)
    }

    @Test fun theOfferIsTheNewestOtherRecentDeviceNotHeardHere() {
        val now = 100_000_000L
        val devices = listOf(
            DeviceState("phone-device-01", "Pixel", "phone", now - 60_000, session("phone-device-01", now - 60_000)),
            DeviceState("pc-device-0002", "Bureau", "pc", now - 3_600_000, session("pc-device-0002", now - 3_600_000, other)),
            DeviceState("pc-device-0001", "Ici", "pc", now, session("pc-device-0001", now)))
        assertEquals("phone-device-01", PlaybackMerge.offer(devices, "pc-device-0001", now, false, null)!!.deviceId)
        assertNull(PlaybackMerge.offer(devices, "pc-device-0001", now, localPlaying = true, localTrackId = null))
        assertEquals("pc-device-0002", PlaybackMerge.offer(devices, "pc-device-0001", now, false, "t:1")!!.deviceId)
        assertEquals("pc-device-0002", PlaybackMerge.offer(devices, "pc-device-0001", now, false, null, dismissedAt = now - 60_000)!!.deviceId)
        val stale = devices.map { it.copy(session = it.session!!.copy(at = now - PlaybackMerge.OFFER_FOR_MS - 1)) }
        assertNull(PlaybackMerge.offer(stale, "pc-device-0001", now, false, null))
        val revoked = devices.map { it.copy(revoked = true) }
        assertNull(PlaybackMerge.offer(revoked, "pc-device-0001", now, false, null))
    }

    @Test fun aStateIsWrittenOnChangesAndAtIntervals() {
        val start = PublishPolicy.Mark("t:1", true, 0, 0)
        assertTrue(PublishPolicy.due(null, start))
        assertFalse(PublishPolicy.due(start, PublishPolicy.Mark("t:1", true, 5_000, 5_000)))
        assertTrue(PublishPolicy.due(start, PublishPolicy.Mark("t:1", true, 15_000, 15_000)))
        assertTrue(PublishPolicy.due(start, PublishPolicy.Mark("t:2", true, 1_000, 1_000)))
        assertTrue(PublishPolicy.due(start, PublishPolicy.Mark("t:1", false, 1_000, 1_000)))
        // A seek is a change; a paused title is written again only every 5 minutes.
        assertTrue(PublishPolicy.due(start, PublishPolicy.Mark("t:1", true, 90_000, 2_000)))
        val paused = PublishPolicy.Mark("t:1", false, 40_000, 0)
        assertFalse(PublishPolicy.due(paused, paused.copy(at = 60_000)))
        assertTrue(PublishPolicy.due(paused, paused.copy(at = PublishPolicy.IDLE_EVERY_MS)))
        assertTrue(PublishPolicy.due(paused, paused.copy(positionMs = 100_000, at = 1_000)))
    }
}
