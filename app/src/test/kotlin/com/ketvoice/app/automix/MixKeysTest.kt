package com.ketvoice.app.automix

import com.ketvoice.app.stems.StemDsp
import com.ketvoice.app.stems.StemMask
import com.ketvoice.app.stems.VoiceStage
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** 7.6 (voice76): volume keyframes of the Ketmix editor: interpolation, storage, and what the decks do with them. */
class MixKeysTest {
    private val plan = MixPlan(MixKind.Crossfade, outMs = 180_000, inMs = 1_000, overlapMs = 8_000, gain = 1f)
    private val keys = listOf(
        LevelKey(2_000, out = Levels(voice = 0, instruments = 100, music = 100), into = Levels(voice = 100, instruments = 50, music = 100)),
        LevelKey(6_000, out = Levels(voice = 0, instruments = 100, music = 40), into = Levels(voice = 100, instruments = 100, music = 100)))

    @Test fun levelsEaseBetweenKeyframesWithAnchors() {
        // The outgoing title starts the blend as recorded, reaches each keyframe, keeps the last.
        assertEquals(100f, MixKeys.valueAt(keys, 8_000, true, 0) { it.voice }, .01f)
        assertEquals(50f, MixKeys.valueAt(keys, 8_000, true, 1_000) { it.voice }, .5f)
        assertEquals(0f, MixKeys.valueAt(keys, 8_000, true, 2_000) { it.voice }, .01f)
        assertEquals(70f, MixKeys.valueAt(keys, 8_000, true, 4_000) { it.music }, .5f)
        assertEquals(40f, MixKeys.valueAt(keys, 8_000, true, 7_900) { it.music }, .01f)
        // Smooth: eased, so the first steps from a keyframe are small.
        val step = MixKeys.valueAt(keys, 8_000, true, 2_100) { it.music } - MixKeys.valueAt(keys, 8_000, true, 2_000) { it.music }
        assertTrue("step $step", abs(step) < 1f)
        // The incoming title holds its first keyframe from its start, then glides back to itself after the blend.
        assertEquals(50f, MixKeys.valueAt(keys, 8_000, false, 0) { it.instruments }, .01f)
        assertEquals(100f, MixKeys.valueAt(keys, 8_000, false, 6_000) { it.instruments }, .01f)
        val single = listOf(LevelKey(4_000, into = Levels(music = 20)))
        assertEquals(20f, MixKeys.valueAt(single, 8_000, false, 8_000) { it.music }, .01f)
        assertEquals(100f, MixKeys.valueAt(single, 8_000, false, 8_000 + MixKeys.RETURN_MS) { it.music }, .01f)
    }

    @Test fun placingMovingAndFindingKeyframes() {
        val placed = MixKeys.place(emptyList(), 3_000, 8_000)
        assertEquals(1, placed.size)
        assertEquals(Levels(), placed[0].out)
        // A keyframe placed between two starts from what is heard there.
        val between = MixKeys.place(keys, 4_000, 8_000)
        assertEquals(3, between.size)
        assertEquals(70f, between[1].out.music.toFloat(), 1f)
        assertSame(keys, MixKeys.place(keys, 2_050, 8_000))
        assertEquals(8_000L, MixKeys.place(emptyList(), 20_000, 8_000)[0].atMs)
        // Moved, a keyframe stays inside the blend and away from its neighbours.
        assertEquals(6_000L - MixKeys.MIN_GAP_MS, MixKeys.move(keys, 0, 7_000, 8_000)[0].atMs)
        assertEquals(0L, MixKeys.move(keys, 0, -500, 8_000)[0].atMs)
        assertEquals(1, MixKeys.near(keys, 6_100, 200))
        assertEquals(-1, MixKeys.near(keys, 4_000, 200))
    }

    @Test fun storageRoundTripsAndReadsTheOldFormat() {
        val choice = PairChoice(MixStyle.values()[2], 9_000, MixShape(VolumeCurve.Linear), keys)
        val text = MixPointStore.encodePair(choice)
        assertTrue(text, text.contains("|v2|"))
        assertEquals(choice, MixPointStore.decodePair(text))
        // Keyframes alone are a choice too.
        val alone = PairChoice(keys = keys)
        assertEquals(alone, MixPointStore.decodePair(MixPointStore.encodePair(alone)))
        // Choices saved before 7.6 (and before 6.9) read as they did, without keyframes; without keyframes the old format is written.
        assertEquals(PairChoice(MixStyle.values()[3], 8_000), MixPointStore.decodePair("3,8000"))
        assertEquals(PairChoice(MixStyle.values()[3], 8_000, MixShape.of(5)!!), MixPointStore.decodePair("3,8000,5"))
        assertEquals("3,8000,5", MixPointStore.encodePair(PairChoice(MixStyle.values()[3], 8_000, MixShape.of(5)!!)))
        assertNull(MixPointStore.decodePair("-1,-1"))
        assertNull(MixPointStore.decodePair("-1,-1|v2|"))
        assertNull(MixPointStore.decodePair("3,8000|v2|2000,101,0,0,0,0,0"))
        assertNull(MixPointStore.decodePair("3,8000|v2|abc"))
        val key = MixPointStore.pairKey("a", "b")
        assertTrue(MixPointStore.validEntry(key, text))
        val store = MapPointsBackend()
        MixPointStore.use(store)
        try {
            MixPointStore.setPair("a", "b", choice)
            assertEquals(keys, MixPointStore.pair("a", "b")?.keys)
            assertEquals(keys, MixEditor.remembered("a", 200_000, "b", 200_000, pair = true).keys)
        } finally { MixPointStore.use(null) }
    }

    @Test fun theDecksCarryTheKeyframes() {
        val keyed = plan.copy(keys = keys)
        assertNull(DeckAutomation.outgoing(plan).levels)
        val out = DeckAutomation.outgoing(keyed).levels!!
        val into = DeckAutomation.incoming(keyed).levels!!
        assertEquals(1f, out.music.at(180_000_000L), 1e-4f)
        assertEquals(MixKeys.gain(40f), out.music.at(187_900_000L), 1e-3f)
        assertEquals(0f, out.voice.at(183_000_000L), 1e-4f)
        assertEquals(MixKeys.gain(50f), into.instruments.at(1_000_000L), 1e-3f)
        // The incoming automation lasts until the title is itself again.
        assertTrue(DeckAutomation.incoming(keyed).endUs >= (1_000L + 8_000L + MixKeys.RETURN_MS) * 1000L)
        // Through the editor's plan too.
        val side = MixEditor.Side("a", 200_000)
        assertNotNull(MixEditor.plan(side, MixEditor.Side("b", 200_000), MixEditor.Choice(keys = keys), 1, MixStyle.Auto).keys)
    }

    private fun tone(rate: Int, frames: Int): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) { val v = (sin(2 * PI * 330 * i / rate) * .5 * 32767).toInt().toShort(); b.putShort(v); b.putShort(v) }
        b.flip(); return b
    }

    @Test fun theDeckRampsTheMusicLevelWithoutClicks() {
        // A short PCM fixture: two seconds of a tone from the start of the blend; « Musique » down to 0 at one second.
        val rate = 44_100
        val frames = rate * 2
        val keyed = MixPlan(MixKind.Crossfade, outMs = 0, inMs = 0, overlapMs = 2_000, keys = listOf(LevelKey(1_000, out = Levels(music = 0))))
        val flat = DeckAutomation(Curve.flat(1f), Curve.flat(0f), levels = MixKeys.curves(keyed, true))
        val dsp = DeckDsp(rate, 2)
        val output = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val input = tone(rate, frames)
        // In buffers of 512 frames, as a deck hands them over.
        var at = 0
        while (at < frames) {
            val n = minOf(512, frames - at)
            val slice = input.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(at * 4); limit((at + n) * 4) }
            dsp.process(slice, output, at * 1_000_000L / rate, flat)
            at += n
        }
        output.flip()
        val out = ShortArray(frames * 2) { output.short }
        fun rms(from: Int, until: Int): Double { var s = 0.0; for (i in from until until) s += out[2 * i].toDouble() * out[2 * i]; return sqrt(s / (until - from)) }
        val full = .5 * 32767 / sqrt(2.0)
        assertEquals(1.0, rms(0, 2_000) / full, .02)
        assertTrue(rms(rate + 2_000, frames) < 30.0)
        // Half way to the keyframe: 50 %, a quarter of the amplitude (the control is squared).
        assertEquals(.25, rms(rate / 2 - 1_000, rate / 2 + 1_000) / full, .05)
        // No click: next to the tone itself, no sample jumps more than the tone's own steepest step allows.
        val toneStep = 2 * PI * 330 / rate * .5 * 32767
        var worst = 0.0
        for (i in 1 until frames) worst = maxOf(worst, abs(out[2 * i] - out[2 * (i - 1)]).toDouble())
        assertTrue("worst $worst", worst <= toneStep * 1.05 + 2)
    }

    @Test fun theVoiceStageSetsVoiceAndInstrumentsApart() {
        val stage = VoiceStage(44_100, 2)
        val frames = StemDsp.frames(StemDsp.RATE * 4)
        val q = StemMask.quantize(.25f)
        val mask = StemMask(0L, frames, ByteArray(frames * StemDsp.NET_BINS / 2) { (q or (q shl 4)).toByte() })
        val kept = MixKeysTestGains.kept(stage, mask)
        val gains = FloatArray(StemDsp.N_FFT / 2 + 1)
        // Instruments off, voice as it is: only the voice share stays.
        assertTrue(stage.gainsAt(1_000_000L, mask, 1f, gains, instruments = 0f))
        assertEquals(1f - kept, gains[100], 1e-4f)
        // Voice off, instruments at half.
        assertTrue(stage.gainsAt(1_000_000L, mask, 0f, gains, instruments = .5f))
        assertEquals(.5f * kept, gains[100], 1e-4f)
        // Both as recorded: nothing to do.
        assertFalse(stage.gainsAt(1_000_000L, mask, 1f, gains, instruments = 1f))
    }
}

/** The share of instruments one cell of a uniform separation holds, as the stage reads it. */
private object MixKeysTestGains {
    fun kept(stage: VoiceStage, mask: StemMask): Float {
        val g = FloatArray(StemDsp.N_FFT / 2 + 1)
        stage.gainsAt(1_000_000L, mask, 0f, g)
        return g[100]
    }
}
