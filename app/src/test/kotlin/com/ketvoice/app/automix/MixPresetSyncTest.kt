package com.ketvoice.app.automix

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ketmix Studio presets in the account's settings document (8.2 / Windows 1.1): phone and PC share them. */
class MixPresetSyncTest {
    @After fun forget() { MixPresetStore.use(null) }

    private fun preset(name: String, overlapMs: Long) = MixPreset("", name, MixStyle.Echo, overlapMs, MixShape.DEFAULT,
        listOf(LevelKey(500, Levels(music = 40))))

    @Test fun presetsTravelBetweenDevicesAndAreNeverLostToAnOlderDevice() {
        MixPresetStore.use(MapPointsBackend())
        MixPresetStore.save(preset("Écho long", 8_000)); MixPresetStore.save(preset("Court", 2_000))
        val fromPhone = MixPresetStore.syncEntries()
        assertEquals(2, fromPhone.size)
        assertTrue(fromPhone.keys.all { it.startsWith(MixPresetStore.SYNC_PREFIX) && it.length == MixPresetStore.SYNC_PREFIX.length + 16 })
        MixPresetStore.use(MapPointsBackend())
        MixPresetStore.save(preset("PC", 5_000))
        val merged = fromPhone + MixPresetStore.syncEntries()
        MixPresetStore.adoptSynced(merged)
        assertEquals(setOf("Écho long", "Court", "PC"), MixPresetStore.all().map { it.name }.toSet())
        assertEquals(merged, MixPresetStore.syncEntries())
        // An older device sends a document without presets: nothing is deleted here.
        MixPresetStore.adoptSynced(emptyMap())
        assertEquals(setOf("Écho long", "Court", "PC"), MixPresetStore.all().map { it.name }.toSet())
        // An entry this version cannot read is ignored.
        MixPresetStore.adoptSynced(MixPresetStore.syncEntries() + (MixPresetStore.SYNC_PREFIX + "0".repeat(16) to "9\tFutur\tx"))
        assertEquals(3, MixPresetStore.all().size)
    }
}
