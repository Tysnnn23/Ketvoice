package com.ketvoice.app.data

import com.ketvoice.app.sync.Canonical
import com.ketvoice.app.sync.SyncDoc
import com.ketvoice.app.sync.SyncMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom sources through the account's settings document: only addresses, on/off and order travel. */
class CustomSourceSyncTest {
    private val a = "https://a.example.com/manifest.json"
    private val b = "https://b.example.com/source.json"
    private val c = "https://c.example.com/x.json"

    private fun source(url: String, enabled: Boolean = true, json: String = """{"name":"secret-ish","token":"abc"}""") =
        CustomSource(CustomSources.idOf(url), url, "Name of $url", url.substringBeforeLast('/'), enabled, quality = "low",
            manifestJson = json, checkedAt = 42L, error = "boom")

    private fun texts(map: Map<String, org.json.JSONObject>) = map.mapValues { Canonical.text(it.value) }

    @Test
    fun onlyAddressesFlagsAndRanksTravel() {
        val entries = texts(CustomSourceSync.entries(listOf(source(a), source(b, enabled = false)), emptyMap(), 0L))
        assertEquals(setOf("customSource.${CustomSources.idOf(a)}", "customSource.${CustomSources.idOf(b)}"), entries.keys)
        assertEquals("""{"enabled":true,"rank":0,"url":"$a"}""", entries.getValue("customSource.${CustomSources.idOf(a)}"))
        assertEquals("""{"enabled":false,"rank":1,"url":"$b"}""", entries.getValue("customSource.${CustomSources.idOf(b)}"))
        val all = entries.values.joinToString()
        listOf("token", "abc", "manifestJson", "quality", "boom", "checkedAt", "Name of").forEach { assertFalse(it, it in all) }
    }

    @Test
    fun roundTripThroughTheSettingsDocumentReachesAnotherDevice() {
        // Device A (the PC) has three sources; B (the phone) has only b, switched on, and its own extra.
        val pc = listOf(source(c), source(a, enabled = false), source(b))
        val phone = listOf(source(b, enabled = true))
        val pcDoc = SyncMerge.stamp(SyncDoc(), texts(CustomSourceSync.entries(pc, emptyMap(), 0L)), now = 1_000L)
        val phoneDoc = SyncMerge.stamp(SyncDoc(), texts(CustomSourceSync.entries(phone, emptyMap(), 0L)), now = 500L)
        val merged = SyncMerge.merge(phoneDoc, SyncMerge.decode(SyncMerge.encode(pcDoc)))
        val plan = CustomSourceSync.plan(phone, merged.live)
        assertEquals(listOf(CustomSources.idOf(c), CustomSources.idOf(a)), plan.add.map { it.first })
        assertEquals(listOf(c, a), plan.add.map { it.second.url })
        assertFalse(plan.add.single { it.second.url == a }.second.enabled)
        assertEquals(listOf(c, a, b).map(CustomSources::idOf), plan.order)
        assertTrue(plan.remove.isEmpty())
        // Once adopted, the phone sends back exactly what the account holds: no churn.
        val adopted = CustomSourceSync.ordered(phone + plan.add.map { (id, e) -> source(e.url, e.enabled).copy(id = id) }, plan.order)
        assertEquals(merged.live, texts(CustomSourceSync.entries(adopted, emptyMap(), 0L)))
        assertTrue(CustomSourceSync.plan(adopted, merged.live).isEmpty)
    }

    @Test
    fun aRemovalTravelsAndANewerReAddWins() {
        val idB = CustomSources.idOf(b)
        val removedHere = mapOf(idB to (b to 10_000L))
        val pcDoc = SyncMerge.stamp(SyncDoc(), texts(CustomSourceSync.entries(listOf(source(a)), removedHere, now = 11_000L)), now = 11_000L)
        assertEquals("""{"removed":true,"url":"$b"}""", pcDoc.live["customSource.$idB"])
        val phone = listOf(source(a), source(b))
        val plan = CustomSourceSync.plan(phone, pcDoc.live)
        assertEquals(setOf(idB), plan.remove)
        // A removal older than 90 days is no longer sent.
        assertNull(CustomSourceSync.entries(emptyList(), removedHere, now = 10_000L + CustomSourceSync.REMOVED_KEEP_MS)["customSource.$idB"])
        // Re-added later on the phone: its live value is newer and replaces the removal everywhere.
        val phoneDoc = SyncMerge.stamp(pcDoc, texts(CustomSourceSync.entries(phone, emptyMap(), 0L)), now = 20_000L)
        val merged = SyncMerge.merge(pcDoc, phoneDoc)
        assertFalse(CustomSourceSync.decode(merged.live.getValue("customSource.$idB"))!!.removed)
    }

    @Test
    fun badOrForeignItemsAreIgnored() {
        val values = mapOf(
            "customSource.${CustomSources.idOf(a)}" to """{"url":"http://a.example.com/manifest.json"}""",
            "customSource.deadbeef0000" to """{"url":"$b","enabled":true,"rank":0}""", // id does not match the address
            "customSource.${CustomSources.idOf(c)}" to "not json",
            "themeMode" to "2",
        )
        assertTrue(CustomSourceSync.plan(emptyList(), values).isEmpty)
        assertNull(CustomSourceSync.decode("""{"url":"https://x.example.com/${"a".repeat(600)}.json"}"""))
    }

    @Test
    fun customSlotsKeepTheBuiltInSourcesInPlace() {
        val order = listOf("cs:1", "api:um", "cs:2", "youtube", "api:last")
        assertEquals(listOf("cs:2", "api:um", "cs:1", "youtube", "api:last"), SourcePriority.placeCustom(order, listOf("cs:2", "cs:1")))
        assertEquals(listOf("cs:2", "api:um", "cs:1", "youtube", "api:last", "cs:3"),
            SourcePriority.placeCustom(order, listOf("cs:2", "cs:3")))
    }

    @Test
    fun removedListSurvivesItsOwnFormat() {
        val removed = mapOf("abc" to (a to 5L))
        assertEquals(removed, CustomSourceSync.readRemoved(CustomSourceSync.writeRemoved(removed)))
        assertTrue(CustomSourceSync.readRemoved("garbage").isEmpty())
    }
}
