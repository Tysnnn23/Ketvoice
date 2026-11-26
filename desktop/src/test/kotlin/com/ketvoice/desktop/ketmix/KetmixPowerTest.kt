package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.KetmixTaste
import com.ketvoice.app.stems.OrtAccel
import com.ketvoice.app.stems.StemModel
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KetmixPowerTest {
    private val tower = KetmixPower.Machine(battery = false, ramMb = 32_768, cores = 16, gpus = listOf("Microsoft Basic Display Adapter", "NVIDIA GeForce RTX 4070"))
    private val laptop = KetmixPower.Machine(battery = true, ramMb = 32_768, cores = 16, gpus = listOf("Intel(R) Iris(R) Xe Graphics"))
    private val smallTower = KetmixPower.Machine(battery = false, ramMb = 8_192, cores = 4)

    @Test fun autoGivesDesktopsMaximumAndLaptopsTodaysModels() {
        assertEquals(KetmixPower.Tier.Maximum, KetmixPower.tier(KetmixPower.AUTO, tower))
        assertEquals(KetmixPower.Tier.Balanced, KetmixPower.tier(KetmixPower.AUTO, laptop))
        // A desktop without the memory for the large models stays on today's in Auto.
        assertEquals(KetmixPower.Tier.Balanced, KetmixPower.tier(KetmixPower.AUTO, smallTower))
        assertEquals(KetmixPower.Tier.Balanced, KetmixPower.tier(KetmixPower.AUTO, tower.copy(cores = 2)))
    }

    @Test fun theListenerCanForceEitherTier() {
        assertEquals(KetmixPower.Tier.Maximum, KetmixPower.tier(KetmixPower.MAXIMUM, laptop))
        assertEquals(KetmixPower.Tier.Balanced, KetmixPower.tier(KetmixPower.BALANCED, tower))
        assertEquals(KetmixPower.Tier.Balanced, KetmixPower.tier(7, laptop))
    }

    @Test fun largeModelsOnlyForMaximumAndTheStudioVoiceNeedsMemory() {
        assertEquals(emptySet(), KetmixPower.models(KetmixPower.Tier.Balanced, tower))
        assertEquals(PcModel.values().toSet(), KetmixPower.models(KetmixPower.Tier.Maximum, tower))
        val tight = KetmixPower.models(KetmixPower.Tier.Maximum, tower.copy(ramMb = 6_000))
        assertFalse(PcModel.VoiceStudio in tight)
        assertTrue(PcModel.BeatFull in tight && PcModel.Key in tight && PcModel.Sound in tight)
    }

    @Test fun gpuRuntimeOnlyForARealCardOnMaximum() {
        assertEquals("NVIDIA GeForce RTX 4070", tower.gpu)
        assertTrue(KetmixPower.wantsGpu(KetmixPower.Tier.Maximum, tower))
        assertFalse(KetmixPower.wantsGpu(KetmixPower.Tier.Balanced, tower))
        assertFalse(KetmixPower.wantsGpu(KetmixPower.Tier.Maximum, tower.copy(gpus = listOf("Microsoft Remote Display Adapter"))))
    }

    @Test fun aSystemBatteryMakesALaptop() {
        assertTrue(StemModel.batteryPresent(1))       // high
        assertTrue(StemModel.batteryPresent(8))       // charging
        assertFalse(StemModel.batteryPresent(128))    // no system battery
        assertFalse(StemModel.batteryPresent(255))    // unknown: counts as a desktop
        assertEquals("PC fixe · 32 Go · 16 cœurs · NVIDIA GeForce RTX 4070", KetmixPower.describe(tower))
        assertEquals("PC portable · 8 Go · 4 cœurs", KetmixPower.describe(smallTower.copy(battery = true)))
    }

    @Test fun pinsMatchTheWorkflowsExpectedDigests() {
        val pins = File("../tools/models/expected-pc.sha256").readLines().filter { it.isNotBlank() }
            .associate { line -> line.split(Regex("\\s+")).let { it[1] to it[0] } }
        for (pack in PcModels.all) for (part in pack.parts) assertEquals(pins[part.name], part.sha256, part.name)
        val workflow = File("../.github/workflows/publish-ketmix-pc-models.yml").readText()
        assertTrue(PcModels.TAG in workflow)
        for (pack in PcModels.all) for (part in pack.parts) assertTrue(part.name in workflow, part.name)
    }

    @Test fun gpuCheckComparesWithTolerance() {
        assertTrue(OrtAccel.agree(floatArrayOf(1f, -2f, 3f), floatArrayOf(1.0005f, -2.001f, 3f)))
        assertFalse(OrtAccel.agree(floatArrayOf(1f, 2f), floatArrayOf(1f, 2.5f)))
        assertFalse(OrtAccel.agree(floatArrayOf(), floatArrayOf()))
        assertEquals(emptyList(), OrtAccel.gpuDevices(emptyList()))
        assertEquals(Runtime.getRuntime().availableProcessors(), OrtAccel.threads(background = false))
        assertTrue(OrtAccel.threads(background = true) >= 1)
        assertTrue(OrtAccel::class.java.getResource(OrtAccel.PROBE) != null)
    }
}

class KetmixTasteTest {
    @Test fun repeatedDislikesLeaveAKindOut() {
        var w = emptyMap<com.ketvoice.app.automix.MixKind, KetmixTaste.Weight>()
        repeat(2) { w = KetmixTaste.rate(w, com.ketvoice.app.automix.MixKind.Echo, up = false) }
        assertEquals(emptySet(), KetmixTaste.avoid(w), "two votes are not enough")
        w = KetmixTaste.rate(w, com.ketvoice.app.automix.MixKind.Echo, up = false)
        assertEquals(setOf(com.ketvoice.app.automix.MixKind.Echo), KetmixTaste.avoid(w))
        // Likes bring it back.
        repeat(3) { w = KetmixTaste.rate(w, com.ketvoice.app.automix.MixKind.Echo, up = true) }
        assertEquals(emptySet(), KetmixTaste.avoid(w))
    }

    @Test fun plainWaysAcrossAreNeverAvoided() {
        var w = emptyMap<com.ketvoice.app.automix.MixKind, KetmixTaste.Weight>()
        repeat(6) { w = KetmixTaste.rate(w, com.ketvoice.app.automix.MixKind.Crossfade, up = false) }
        assertEquals(emptySet(), KetmixTaste.avoid(w))
    }

    @Test fun weightsSurviveTheirTextForm() {
        var w = emptyMap<com.ketvoice.app.automix.MixKind, KetmixTaste.Weight>()
        w = KetmixTaste.rate(w, com.ketvoice.app.automix.MixKind.Rise, up = true)
        w = KetmixTaste.rate(w, com.ketvoice.app.automix.MixKind.Beatmatch, up = false)
        val back = KetmixTaste.decode(KetmixTaste.encode(w))
        assertEquals(w.keys, back.keys)
        for (k in w.keys) { assertEquals(w.getValue(k).votes, back.getValue(k).votes); assertEquals(w.getValue(k).score, back.getValue(k).score, 1e-4f) }
        assertEquals(emptyMap(), KetmixTaste.decode("nonsense;Echo:x:1;Unknown:0.5:2"))
    }

    @Test fun ratingsCountOnlyWhenLearningIsOn() {
        KetmixFeedback.resetForTest()
        KetmixPcSettings.learns.value = false
        val plan = com.ketvoice.app.automix.MixPlan(com.ketvoice.app.automix.MixKind.Echo, outMs = 1000, overlapMs = 4000)
        KetmixFeedback.planned(plan, null, null)
        KetmixFeedback.rate(false)
        assertEquals(null, KetmixFeedback.transition.value?.rating)
        try {
            KetmixPcSettings.learns.value = true
            repeat(3) { KetmixFeedback.planned(plan, null, null); KetmixFeedback.rate(false) }
            assertEquals(false, KetmixFeedback.transition.value?.rating)
            assertEquals(setOf(com.ketvoice.app.automix.MixKind.Echo), KetmixFeedback.avoid())
            KetmixFeedback.reset()
            assertEquals(emptySet(), KetmixFeedback.avoid())
        } finally { KetmixPcSettings.learns.value = false; KetmixFeedback.resetForTest() }
    }
}

class IdleWatchTest {
    private fun s(idleMs: Long = 10 * 60_000L, fullScreen: Boolean = false, cpu: Double = .05, mains: Boolean = true) =
        IdleWatch.Snapshot(idleMs, fullScreen, cpu, mains)

    @Test fun startsOnlyWhenThePcIsFree() {
        assertTrue(IdleWatch.idle(s()))
        assertFalse(IdleWatch.idle(s(idleMs = 60_000L)), "someone used the PC a minute ago")
        assertFalse(IdleWatch.idle(s(fullScreen = true)), "a game or a film is on")
        assertFalse(IdleWatch.idle(s(cpu = .6)), "another program is busy")
        assertFalse(IdleWatch.idle(s(mains = false)), "on battery")
    }

    @Test fun stopsAsSoonAsTheListenerIsBack() {
        assertFalse(IdleWatch.interrupted(s()))
        assertTrue(IdleWatch.interrupted(s(idleMs = 300L)))
        assertTrue(IdleWatch.interrupted(s(fullScreen = true)))
        assertTrue(IdleWatch.interrupted(s(mains = false)))
        // Ketvoice's own work loads the processor: that never stops it.
        assertFalse(IdleWatch.interrupted(s(cpu = 1.0)))
        assertTrue(IdleWatch.fullScreenState(3) && IdleWatch.fullScreenState(2) && !IdleWatch.fullScreenState(5))
    }

    @Test fun libraryPreparationPausesInstantlyAndResumesLater() {
        val dir = kotlin.io.path.createTempDirectory("facts").toFile()
        TrackFactsStore.folder = dir
        val items0 = LibraryPrep.items; val enabled0 = LibraryPrep.enabled; val decode0 = LibraryPrep.decode
        val analyse0 = LibraryPrep.analyse; val studio0 = LibraryPrep.studioReady; val facts0 = LibraryPrep.factsModels
        try {
            val items = (1..4).map { com.ketvoice.app.stems.StemLibrary.LibraryItem("t$it", "/x/$it.flac", 200_000L) }
            var away = true
            var analysed = 0
            LibraryPrep.items = { items }
            LibraryPrep.enabled = { true }
            LibraryPrep.decode = { ShortArray(16) }
            LibraryPrep.studioReady = { false }
            LibraryPrep.factsModels = { true }
            LibraryPrep.analyse = { _, d, stopped ->
                analysed++
                if (analysed == 2) away = false // the listener moves the mouse during the second title
                if (stopped()) null else TrackFacts(d, doubleArrayOf(), emptyList(), -1, 0f, floatArrayOf(), null)
            }
            assertEquals(1, LibraryPrep.runOnce { !away })
            assertTrue(LibraryPrep.progress.value.paused)
            assertEquals(2, analysed, "nothing more is started once the listener is back")
            away = true
            assertEquals(3, LibraryPrep.runOnce { !away }, "the next idle time takes it up again")
            assertTrue(TrackFactsStore.has("t4"))
        } finally {
            LibraryPrep.items = items0; LibraryPrep.enabled = enabled0; LibraryPrep.decode = decode0
            LibraryPrep.analyse = analyse0; LibraryPrep.studioReady = studio0; LibraryPrep.factsModels = facts0
            TrackFactsStore.folder = null; TrackFactsStore.clearCache(); dir.deleteRecursively()
        }
    }
}
