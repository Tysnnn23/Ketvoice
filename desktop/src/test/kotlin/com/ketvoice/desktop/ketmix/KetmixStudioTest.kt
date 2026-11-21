package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.Levels
import com.ketvoice.app.automix.MapPointsBackend
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixPresetStore
import com.ketvoice.app.automix.MixShare
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.WaveStore
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.stems.StemLibrary
import com.ketvoice.app.stems.StemMask
import com.ketvoice.app.stems.StemStore
import com.ketvoice.desktop.audio.AudioFixtures
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.CaptureSink
import com.ketvoice.desktop.audio.FfmpegAudioEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ketmix Studio on the PC (state, presets, share codes, preview through the mixer) and « Voix prêtes à l'avance ». */
class KetmixStudioTest {
    private val rate = FfmpegAudioEngine.RATE
    private val a = Track("studio-a", "Premier", "Artiste A", "", durationMs = 40_000)
    private val b = Track("studio-b", "Second", "Artiste B", "", durationMs = 40_000)

    @Before fun stores() {
        System.setProperty("ketvoice.dataDir", File("build/studio-data").absolutePath)
        MixPointStore.use(MapPointsBackend()); MixPresetStore.use(MapPointsBackend()); WaveStore.use(null)
    }

    @After fun forget() {
        MixPointStore.use(null); MixPresetStore.use(null); StudioPreview.stop(); Player.resetForTest()
    }

    companion object {
        /** A groove: a tone with a kick on every beat at 120 BPM, so the shared analysis finds a grid. */
        fun groove(name: String, hz: Double, ms: Int = 40_000): File {
            val rate = FfmpegAudioEngine.RATE
            val beat = rate / 2
            val pcm = AudioFixtures.pcm(rate, ms, signal = { i ->
                val t = i % beat
                .25 * sin(2 * PI * hz * i / rate) + .45 * exp(-t / (rate * .04)) * sin(2 * PI * 55.0 * t / rate)
            })
            return AudioFixtures.encode(name, "flac", AudioFixtures.wav(name, rate, pcm))
        }
    }

    private fun loaded(): StudioState {
        val files = mapOf(a.id to groove("studio-a", 440.0), b.id to groove("studio-b", 660.0))
        AppSettings.ketmixAnalysis.value = 0
        val s = StudioState(a, b, intensity = 1, defaultStyle = MixStyle.Auto)
        runBlocking { s.load { t -> AudioSource.LocalFile(files.getValue(t.id).path) } }
        return s
    }

    @Test fun bothEndsAreReadAndTheMouseEditsUndoAndSave() {
        val s = loaded()
        assertNotNull(s.out.wave); assertNotNull(s.into.wave)
        assertTrue(s.out.analysis?.hasBeat == true, "the outgoing grid is found")
        assertEquals(40_000L, s.out.durationMs)
        val auto = s.plan()
        assertTrue(auto.mixes)
        // The box dragged 3 s earlier, magnet on: the exit lands on the grid (a beat is 500 ms).
        s.moveExit(26_030, msPerPx = 20f)
        val moved = s.plan()
        assertEquals(0L, (moved.outMs - s.out.analysis!!.beatAtMs.toLong()).mod(500L).let { minOf(it, 500 - it) } / 10, "exit on a beat")
        assertTrue(s.dirty && s.canUndo)
        s.style(MixStyle.Fade); s.resize(6_000); assertEquals(6_000L, s.plan().overlapMs, "a fade takes the length asked for")
        s.moveEntry(4_000, msPerPx = 20f)
        assertTrue(s.choice.entryMs in 3_700L..4_300L)
        // Undo walks back (the drags within 600 ms are one step), redo forward.
        val edited = s.choice
        s.undo(); assertNotEquals(edited, s.choice); assertTrue(s.canRedo)
        s.redo(); assertEquals(edited, s.choice)
        // « + Point », its levels, a drag, then delete.
        val k = s.addKey()
        assertEquals(0, k); assertEquals(0, s.selectedKey)
        s.setLevels(0, outgoing = Levels(voice = 20), incoming = Levels(music = 70))
        assertEquals(20, s.choice.keys[0].out.voice); assertEquals(70, s.choice.keys[0].into.music)
        s.moveKey(0, 1_000, msPerPx = 1f)
        s.magnet = false; s.moveKey(0, 1_234, msPerPx = 1f); assertEquals(1_234L, s.choice.keys[0].atMs)
        assertEquals(1, s.addKey(4_000))
        s.removeKey(1); assertEquals(1, s.choice.keys.size)
        // « Enregistrer »: the points go to each title, the rest to the pair; Ketmix's plan takes the keyframes.
        s.save()
        assertFalse(s.dirty)
        assertEquals(s.choice.exitMs, MixPointStore.exitOf(a.id, 40_000))
        assertEquals(s.choice.entryMs, MixPointStore.entryOf(b.id, 40_000))
        val pair = MixPointStore.pair(a.id, b.id)!!
        assertEquals(6_000L, pair.overlapMs); assertEquals(s.choice.keys, pair.keys)
        assertEquals(s.choice.keys, StudioState(a, b, 40_000, 40_000).choice.keys, "a new Studio opens on what was saved")
    }

    @Test fun presetsAreThePhonesAndShareCodesRoundTrip() {
        // The real preferences file the phone uses, `ketmix-presets-v1`, with its own format.
        MixPresetStore.use(null); MixPresetStore.init(android.content.Context.app)
        android.content.Context.app.getSharedPreferences(MixPresetStore.PREFS, 0).edit().clear().commit()
        MixPresetStore.use(null); MixPresetStore.init(android.content.Context.app)
        val s = loaded()
        s.style(MixStyle.Echo); s.resize(8_000); s.addKey(); s.setLevels(0, outgoing = Levels(music = 40))
        val saved = s.saveAsPreset("  Écho long  ")!!
        assertEquals("Écho long", saved.name)
        val raw = android.content.Context.app.getSharedPreferences(MixPresetStore.PREFS, 0).getString(saved.id, null)!!
        assertTrue(raw.startsWith("1\tÉcho long\t"), raw)
        assertEquals(listOf("Écho long"), MixPresetStore.all().map { it.name })
        // Applied to another pair: style and length, keyframes scaled to its blend.
        val other = StudioState(b, a, 40_000, 40_000, intensity = 1, defaultStyle = MixStyle.Auto)
        other.applyPreset(MixPresetStore.all().single())
        assertEquals(MixStyle.Echo, other.choice.style); assertEquals(8_000L, other.choice.overlapMs)
        assertEquals(40, other.choice.keys.single().out.music)
        // « Partager ce mix » → « Importer un mix ».
        val text = s.shareText()
        val found = MixShare.find(text)!!
        assertTrue(text.contains("KM1."))
        assertEquals(a.id, found.from.id); assertEquals(s.choice.pairChoice, found.choice)
        val fresh = StudioState(a, b, 40_000, 40_000)
        assertTrue(fresh.importShared(found)); assertEquals(s.choice.exitMs, fresh.choice.exitMs)
        assertNull(MixShare.find("pas un code"))
        MixPresetStore.delete(saved.id); assertTrue(MixPresetStore.all().isEmpty())
    }

    @Test fun listenPlaysTheTransitionThroughTheMixer() {
        val s = loaded()
        val sink = CaptureSink()
        StudioPreview.engineFactory = { FfmpegAudioEngine(sink) }
        try {
            val plan = s.plan()
            StudioPreview.start(plan, s.out.source!!, s.into.source!!)
            var sawPlaying = false; var last = -1L
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                val st = StudioPreview.state.value
                if (st is StudioPreview.State.Playing) { sawPlaying = true; last = st.clockMs }
                if (sawPlaying && st == StudioPreview.State.Idle) break
                assertNotEquals<StudioPreview.State>(StudioPreview.State.Failed, st)
                Thread.sleep(5)
            }
            assertTrue(sawPlaying)
            assertEquals(StudioPreview.State.Idle, StudioPreview.state.value, "the preview ends by itself")
            // The last clock seen depends on polling under load; the rendered sound below proves the whole blend played.
            assertTrue(last > plan.outMs, "the playhead reached the blend on the outgoing clock: $last")
            val pcm = sink.samples()
            // About lead + blend + tail of sound, with both tones heard.
            val seconds = pcm.size / 2 / rate.toDouble()
            assertTrue(seconds > (StudioPreview.LEAD_MS + plan.overlapMs) / 1000.0, "rendered $seconds s")
            val left = AudioFixtures.left(pcm)
            val endHz = AudioFixtures.frequency(left, rate, left.size - rate, left.size - rate / 2)
            assertTrue(endHz in 600.0..720.0, "the incoming title is heard at the end: $endHz Hz")
        } finally { StudioPreview.engineFactory = { FfmpegAudioEngine() } }
    }

    @Test fun clockFollowsTheIncomingDeckAfterTheHandOver() {
        val plan = com.ketvoice.app.automix.MixPlan(com.ketvoice.app.automix.MixKind.Crossfade, outMs = 30_000, inMs = 2_000, overlapMs = 8_000, rate = 1f)
        assertEquals(29_000L, StudioPreview.clockOf(plan, 29_000, promoted = false))
        assertEquals(35_000L, StudioPreview.clockOf(plan, 7_000, promoted = true))
    }

    @Test fun libraryVoicesQuickModelFirstAtMost300AndOnlyOnMains() = runBlocking {
        StemStore.init(java.nio.file.Files.createTempDirectory("studio-stems").toFile())
        val items = (0 until 320).map { StemLibrary.LibraryItem("lib-$it", "/x/$it.flac", 200_000) }
        val order = mutableListOf<Pair<String, Int>>()
        var mains = true
        val keep = arrayOf(StemLibrary.items, StemLibrary.charging, StemLibrary.held, StemLibrary.separate, StemLibrary.modelReady)
        val library = AppSettings.ketmixLibrary.value
        StemLibrary.items = { items }; StemLibrary.charging = { mains }; StemLibrary.held = { false }; StemLibrary.modelReady = { true }
        StemLibrary.testQuality = StemMask.HIGH
        StemLibrary.separate = { item, q, _ -> order += item.id to q; if (order.size == 450) mains = false; tinyMask(q) }
        AppSettings.ketmixLibrary.value = true
        try {
            StemLibrary.run()
            // The quick model over the 300 most recent first, then the best one, until the power went.
            assertEquals(300, order.count { it.second == StemMask.FAST })
            assertEquals(items.take(300).map { it.id }, order.take(300).map { it.first })
            assertTrue(order.drop(300).all { it.second == StemMask.HIGH })
            assertEquals(150, order.count { it.second == StemMask.HIGH }, "stops when the computer leaves mains power")
            assertFalse(order.any { it.first == "lib-310" })
            assertEquals(StemMask.HIGH, StemStore.wholeQuality("lib-0")); assertEquals(StemMask.FAST, StemStore.wholeQuality("lib-299"))
            // Off by the setting: nothing.
            order.clear(); mains = true; AppSettings.ketmixLibrary.value = false
            StemLibrary.run(); assertTrue(order.isEmpty())
        } finally {
            StemLibrary.items = keep[0] as () -> List<StemLibrary.LibraryItem>
            @Suppress("UNCHECKED_CAST") run {
                StemLibrary.charging = keep[1] as () -> Boolean; StemLibrary.held = keep[2] as () -> Boolean
                StemLibrary.separate = keep[3] as (StemLibrary.LibraryItem, Int, () -> Boolean) -> StemMask?
                StemLibrary.modelReady = keep[4] as () -> Boolean
            }
            StemLibrary.testQuality = null; AppSettings.ketmixLibrary.value = library
        }
    }

    private fun tinyMask(quality: Int): StemMask {
        val frames = 4
        val half = com.ketvoice.app.stems.StemDsp.NET_BINS / 2
        return StemMask(0L, frames, ByteArray(frames * half), ByteArray(frames), ByteArray(frames), quality)
    }
}
