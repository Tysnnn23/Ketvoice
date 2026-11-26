package com.ketvoice.app.automix

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DeckDspTest {
    private val beat = MixPlan(MixKind.Beatmatch, outMs = 180_000, inMs = 500, overlapMs = 16_000, rate = .96f,
        swapMs = 188_000, gain = .8f, beatMs = 500f)
    private val fade = MixPlan(MixKind.Crossfade, outMs = 190_000, inMs = 300, overlapMs = 6_000, gain = .9f)

    private fun tone(hz: Double, rate: Int, seconds: Float, channels: Int = 1, amplitude: Float = .5f): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * 2 * channels).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) repeat(channels) { c ->
            buffer.putShort((sin(2 * PI * hz * i / rate) * amplitude * 32767 * (if (c == 1) -1 else 1)).toInt().toShort())
        }
        buffer.flip(); return buffer
    }
    private fun run(dsp: DeckDsp, input: ByteBuffer, startUs: Long, automation: DeckAutomation?): ShortArray {
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        dsp.process(input, output, startUs, automation)
        output.flip()
        return ShortArray(output.remaining() / 2) { output.short }
    }
    private fun rms(samples: ShortArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (samples.size - from))
    }
    private fun db(ratio: Double) = 20 * log10(ratio)

    @Test fun theMidBandIsTakenDownAndTheBassLeftAlone() {
        val rate = 44_100
        val eq = DeckAutomation(Curve.flat(1f), Curve.flat(0f), midDb = Curve.flat(-8f))
        val flat = DeckAutomation(Curve.flat(1f), Curve.flat(0f))
        fun level(hz: Double, automation: DeckAutomation): Double =
            rms(run(DeckDsp(rate, 1), tone(hz, rate, 1f), 0L, automation), from = rate / 2)
        val mid = db(level(1_000.0, eq) / level(1_000.0, flat))
        val bass = db(level(80.0, eq) / level(80.0, flat))
        assertEquals("1 kHz down by the curve's 8 dB", -8.0, mid, .6)
        assertTrue("80 Hz barely touched: $bass dB", abs(bass) < 1.0)
    }

    @Test fun aCurveIsFlatOutsideItsPointsAndStraightBetweenThem() {
        val curve = Curve(longArrayOf(1_000, 2_000, 4_000), floatArrayOf(0f, 1f, .5f))
        assertEquals(0f, curve.at(0), 0f); assertEquals(0f, curve.at(1_000), 0f)
        assertEquals(.5f, curve.at(1_500), 1e-6f); assertEquals(1f, curve.at(2_000), 1e-6f)
        assertEquals(.75f, curve.at(3_000), 1e-6f); assertEquals(.5f, curve.at(9_000), 0f)
        // Asked out of order too.
        assertEquals(.25f, curve.at(1_250), 1e-6f); assertEquals(.625f, curve.at(3_500), 1e-6f); assertEquals(.1f, curve.at(1_100), 1e-6f)
        assertEquals(4_000L, curve.endUs)
    }

    @Test fun aCrossfadeHandsTheSoundOverWithoutAHoleOrAnOverload() {
        val out = DeckAutomation.outgoing(fade); val into = DeckAutomation.incoming(fade)
        assertEquals(1f, out.gain.at(189_000_000), 0f); assertEquals(1f, out.gain.at(190_000_000), 1e-4f)
        assertEquals(0f, out.gain.at(196_000_000), 1e-4f); assertEquals(0f, out.gain.at(199_000_000), 1e-6f)
        assertEquals(0f, into.gain.at(0), 0f); assertEquals(0f, into.gain.at(300_000), 1e-4f)
        assertEquals("The incoming title is held at its matched level", .9f, into.gain.at(6_300_000), 1e-3f)
        assertEquals("and comes back to its own level afterwards", 1f, into.gain.at(6_300_000 + 8_000_000), 1e-4f)
        for (step in 0..60) {
            val a = out.gain.at(190_000_000L + step * 100_000L)
            val b = into.gain.at(300_000L + step * 100_000L) / .9f
            assertTrue("never past full scale by more than 2 dB (${a + b})", a + b <= 1.26f)
            assertTrue("never a hole (${a * a + b * b})", a * a + b * b >= .68f)
        }
        assertEquals("No bass filter in a plain crossfade", 0f, out.highPassHz.at(193_000_000), 0f)
        assertEquals(0f, into.highPassHz.at(3_000_000), 0f)
    }

    @Test fun inABeatMixTheBassChangesHandsOnTheBeat() {
        val out = DeckAutomation.outgoing(beat); val into = DeckAutomation.incoming(beat)
        // Positions in the incoming title run 0.96 times as fast as the outgoing clock.
        fun inAt(outMs: Long) = ((500 + (outMs - 180_000) * .96) * 1000).toLong()
        assertEquals(1f, out.gain.at(180_000_000), 1e-3f)
        assertEquals(.86f, out.gain.at(188_000_000), 2e-3f)
        assertEquals(0f, out.gain.at(196_000_000), 1e-3f)
        assertEquals(0f, into.gain.at(inAt(180_000)), 1e-3f)
        assertEquals(.86f * .8f, into.gain.at(inAt(188_000)), 3e-3f)
        assertEquals(.8f, into.gain.at(inAt(196_000)), 2e-3f)
        assertEquals(1f, into.gain.at(inAt(196_000) + 8_000_000), 1e-3f)
        // Before the swap the outgoing title keeps its bass and the incoming one has none; after, the reverse.
        assertEquals(0f, out.highPassHz.at(187_000_000), 0f)
        assertEquals(DeckAutomation.BASS_HZ, into.highPassHz.at(inAt(187_000)), .5f)
        assertEquals(DeckAutomation.BASS_HZ, out.highPassHz.at(188_250_000), 1f)
        assertEquals(0f, into.highPassHz.at(inAt(189_000)), 0f)
        // Once its bass is gone the outgoing title thins out into the air, up to about 1.1 kHz.
        assertTrue(out.highPassHz.at(192_000_000) in 400f..900f)
        assertEquals(1_100f, out.highPassHz.at(196_000_000), 2f)
        assertEquals("...and no low-pass in this colour", 0f, out.lowPassHz.at(192_000_000), 0f)
        // The incoming title comes in highs first, then mids, and its bass on the swap.
        assertEquals(1_400f, into.highPassHz.at(0), 1f)
        assertTrue(into.highPassHz.at(inAt(182_000)) in 320f..1_380f)
        assertEquals(DeckAutomation.BASS_HZ, into.highPassHz.at(inAt(186_000)), 1f)
        assertEquals("no low-pass on the incoming title", 0f, into.lowPassHz.at(inAt(183_000)), 0f)
        var before = Float.MAX_VALUE
        for (ms in 180_000L..189_000L step 100) { val hz = into.highPassHz.at(inAt(ms)).let { if (it == 0f) 0f else it }; assertTrue(hz <= before + .01f); before = hz }
        val middle = out.highPassHz.at(188_000_000)
        assertTrue("The filters cross during one beat around the swap ($middle)", middle > 60f && middle < 150f)
        assertEquals(middle, into.highPassHz.at(inAt(188_000)), 6f)
        assertTrue(out.endUs >= 196_000_000 && into.endUs >= inAt(196_000) + 8_000_000 - 1_000)
    }

    @Test fun theOtherColourOfABeatMixSinksInsteadOfRising() {
        val plan = beat.copy(variant = 1)
        val out = DeckAutomation.outgoing(plan); val into = DeckAutomation.incoming(plan)
        assertEquals("The bass still changes hands", DeckAutomation.BASS_HZ, out.highPassHz.at(190_000_000), 1f)
        assertEquals(0f, out.lowPassHz.at(187_000_000), 0f)
        assertTrue("then the top end closes (${out.lowPassHz.at(193_000_000)})", out.lowPassHz.at(193_000_000) in 2_000f..12_000f)
        assertEquals(1_400f, out.lowPassHz.at(196_000_000), 2f)
        assertEquals("The incoming title is not low-passed in this colour", 0f, into.lowPassHz.at(2_000_000), 0f)
        // Keys that clash: the outgoing title is thinned further.
        assertEquals(2_400f, DeckAutomation.outgoing(beat.copy(clash = true)).highPassHz.at(196_000_000), 4f)
    }

    @Test fun anEchoCatchesTheLastBeatThenTheTitleIsCut() {
        val plan = MixPlan(MixKind.Echo, outMs = 185_000, inMs = 500, overlapMs = 2_000, gain = .9f, beatMs = 500f)
        val out = DeckAutomation.outgoing(plan); val into = DeckAutomation.incoming(plan)
        assertTrue(out.usesEcho); assertFalse(out.usesReverb)
        assertEquals("Repeats every half beat", 250_000L, out.echoDelayUs)
        assertEquals(0f, out.echo.at(184_400_000), 0f)
        assertEquals("The last beat goes into the echo", 1f, out.echo.at(184_800_000), 1e-3f)
        // 9.1.1: the send and the title close over 60 ms, eased (a cut inside the music clicked before).
        assertEquals("Nothing after the cut does", 0f, out.echo.at(185_060_000), 1e-3f)
        assertEquals(1f, out.dry.at(185_000_000), 1e-3f); assertEquals(0f, out.dry.at(185_060_000), 1e-3f)
        assertEquals(1f, out.gain.at(185_700_000), 1e-3f); assertEquals(0f, out.gain.at(187_000_000), 1e-3f)
        // The next title is there at once, at its matched level.
        assertEquals(0f, into.gain.at(500_000), 1e-3f); assertEquals(.9f, into.gain.at(540_000), 1e-3f)
        assertEquals(.9f, into.gain.at(2_500_000), 1e-3f); assertEquals(1f, into.gain.at(2_500_000 + 8_000_000), 1e-3f)

        // Heard: clicks every 400 ms, cut at 1 s. The click at 0.8 s keeps ringing every 250 ms, quieter each
        // time; the clicks the title would have played at 1.2 s, 1.6 s… are not heard.
        val rate = 44_100
        val automation = DeckAutomation(Curve.flat(1f), Curve.flat(0f),
            dry = Curve(longArrayOf(1_000_000, 1_028_000), floatArrayOf(1f, 0f)),
            echo = Curve(longArrayOf(500_000, 625_000, 1_000_000, 1_008_000), floatArrayOf(0f, 1f, 1f, 0f)),
            echoDelayUs = 250_000, echoFeedback = .6f)
        val frames = rate * 4
        val input = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val inBeat = i % (rate * 2 / 5)
            input.putShort(if (inBeat < 400) (sin(2 * PI * 1_000.0 * inBeat / rate) * 20_000).toInt().toShort() else 0)
        }
        input.flip()
        val samples = run(DeckDsp(rate, 1), input, 0, automation)
        fun peak(fromMs: Int, toMs: Int): Int { var top = 0; for (i in fromMs * rate / 1000 until toMs * rate / 1000) top = maxOf(top, abs(samples[i].toInt())); return top }
        assertTrue("The title itself is heard until the cut (${peak(795, 815)})", peak(795, 815) > 15_000)
        val first = peak(1_045, 1_075); val second = peak(1_295, 1_325); val third = peak(1_545, 1_575)
        assertTrue("first repeat $first", first > 6_000)
        assertTrue("each repeat is quieter: $first, $second, $third", second < first * .8 && third < second * .8 && third > 100)
        assertTrue("The title is gone after the cut (${peak(1_195, 1_225)}, ${peak(1_995, 2_025)})", peak(1_195, 1_225) < first / 4 && peak(1_995, 2_025) < first / 4)
        assertTrue("and the tail dies (${peak(3_600, 4_000)})", peak(3_600, 4_000) < 200)
    }

    @Test fun twoTitlesCanCrossBySpectrumInsteadOfByLevel() {
        val plan = MixPlan(MixKind.Filter, outMs = 190_000, inMs = 300, overlapMs = 8_000, gain = 1f, hold = .55f)
        val out = DeckAutomation.outgoing(plan); val into = DeckAutomation.incoming(plan)
        assertEquals("The outgoing title keeps its level while it dies away by itself", 1f, out.gain.at(194_000_000), 1e-3f)
        assertEquals(0f, out.gain.at(198_000_000), 1e-3f)
        assertEquals(0f, out.highPassHz.at(189_000_000), 0f)
        // 7.6: it keeps its body for the first half and rises into the air in the second, while the next one has opened.
        assertTrue("it keeps its body half-way", out.highPassHz.at(194_000_000) in 30f..150f)
        assertTrue("then rises into the air", out.highPassHz.at(196_500_000) in 250f..900f)
        assertEquals(1_500f, out.highPassHz.at(198_000_000), 3f)
        assertEquals("the next one grows from its low end", 380f, into.lowPassHz.at(300_000), 2f)
        assertTrue(into.lowPassHz.at(4_000_000) in 1_000f..12_000f)
        assertEquals("and ends wide open", 0f, into.lowPassHz.at(7_000_000), 0f)
        assertEquals(1f, into.gain.at(8_300_000), 1e-3f)
        // The other colour: the outgoing title sinks, the next one comes down from the top.
        val other = plan.copy(variant = 1)
        assertEquals(260f, DeckAutomation.outgoing(other).lowPassHz.at(198_000_000), 2f)
        assertEquals(0f, DeckAutomation.outgoing(other).highPassHz.at(195_000_000), 0f)
        assertEquals(1_200f, DeckAutomation.incoming(other).highPassHz.at(300_000), 2f)
        assertEquals(0f, DeckAutomation.incoming(other).highPassHz.at(7_500_000), 0f)
        assertEquals(190_000_000L + 8_000_000L, out.endUs)
    }

    @Test fun theLowPassTakesTheTopAndLeavesTheRest() {
        val automation = DeckAutomation(Curve.flat(1f), Curve.flat(0f), Curve.flat(400f))
        val rate = 48_000
        val skip = rate / 5
        fun ratio(hz: Double) = rms(run(DeckDsp(rate, 1), tone(hz, rate, 1f), 0, automation), skip) / rms(run(DeckDsp(rate, 1), tone(hz, rate, 1f), 0, null), skip)
        assertTrue("4 kHz is taken away (${db(ratio(4_000.0))} dB)", db(ratio(4_000.0)) < -30)
        assertEquals("80 Hz passes", 0.0, db(ratio(80.0)), .4)
        assertEquals("-3 dB at the cut-off", -3.0, db(ratio(400.0)), .6)
        // A filter that joins a sound in motion does not click: a constant stays a constant.
        val late = DeckAutomation(Curve.flat(1f), Curve.flat(0f), Curve(longArrayOf(500_000, 500_001), floatArrayOf(0f, 18_000f)))
        val constant = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(rate) { putShort(12_000) }; flip() }
        val through = run(DeckDsp(rate, 1), constant, 0, late)
        for (i in 1 until through.size) assertTrue("step at $i: ${through[i - 1]} -> ${through[i]}", abs(through[i] - through[i - 1]) <= 40)
    }

    @Test fun aTitleWithoutABeatDissolvesIntoItsReverberation() {
        val plan = MixPlan(MixKind.Wash, outMs = 190_000, inMs = 300, overlapMs = 8_000, gain = 1f)
        val out = DeckAutomation.outgoing(plan); val into = DeckAutomation.incoming(plan)
        assertTrue(out.usesReverb); assertFalse(out.usesEcho)
        assertEquals(0f, out.reverb.at(190_000_000), 1e-3f); assertEquals(1f, out.reverb.at(193_000_000), 1e-3f)
        assertEquals(1f, out.dry.at(191_000_000), 1e-3f); assertEquals(0f, out.dry.at(195_000_000), 1e-3f)
        assertEquals(1f, out.gain.at(194_000_000), 1e-3f); assertEquals(0f, out.gain.at(198_000_000), 1e-3f)
        assertEquals("The next title rises without its bass at first", 220f, into.highPassHz.at(300_000), 2f)
        assertEquals(0f, into.highPassHz.at(6_000_000), 0f)

        // Heard: with the dry sound closed, a tone that stops leaves a tail that fades and never overloads.
        val rate = 44_100
        val wet = DeckAutomation(Curve.flat(1f), Curve.flat(0f), dry = Curve.flat(0f), reverb = Curve(longArrayOf(0, 1), floatArrayOf(1f, 1f)))
        val frames = rate * 5
        val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = if (i < rate) ((sin(2 * PI * 330.0 * i / rate) * .5 + sin(2 * PI * 880.0 * i / rate) * .3) * 16_000).toInt() else 0
            input.putShort(v.toShort()); input.putShort(v.toShort())
        }
        input.flip()
        val samples = run(DeckDsp(rate, 2), input, 0, wet)
        fun level(fromMs: Int, toMs: Int): Double { var sum = 0.0; var n = 0; for (i in fromMs * rate / 1000 * 2 until toMs * rate / 1000 * 2) { sum += samples[i].toDouble() * samples[i]; n++ }; return sqrt(sum / n) }
        val sounding = level(500, 1_000); val justAfter = level(1_050, 1_300); val later = level(2_500, 3_000); val gone = level(4_500, 5_000)
        assertTrue("the room sounds about as loud as the title did ($sounding)", sounding in 3_000.0..12_000.0)
        assertTrue("a tail is left when the sound stops ($justAfter)", justAfter > sounding * .08)
        assertTrue("it fades ($later, $gone)", later < justAfter * .3 && gone < 30.0)
        assertTrue(samples.none { abs(it.toInt()) >= 32_767 })
        // The two sides are not the same signal: the room has width.
        var different = 0
        for (i in rate until rate * 2) if (abs(samples[i * 2] - samples[i * 2 + 1]) > 50) different++
        assertTrue("stereo tail ($different)", different > rate / 10)
    }

    @Test fun aBuildThinsTheTitleAndSwellsANoiseThenStops() {
        val rate = 44_100
        val plan = MixPlan(MixKind.Rise, outMs = 10_000, inMs = 0, overlapMs = 1_000, beatMs = 500f, buildMs = 4_000)
        val out = DeckAutomation.outgoing(plan)
        assertTrue(out.usesNoise)
        assertEquals("nothing before the build", 0f, out.noise.at(5_000_000), 0f)
        assertEquals(0f, out.highPassHz.at(5_900_000), 0f)
        assertEquals("loudest at the cut", DeckAutomation.RISE_NOISE, out.noise.at(10_000_000), 1e-4f)
        assertTrue("the filter climbs", out.highPassHz.at(9_900_000) > 1_500f)
        assertEquals("the title itself stops on the cut", 0f, out.dry.at(10_030_000), 0f)
        // Silence in: the noise alone comes out, swelling towards the cut, gone after it.
        val dsp = DeckDsp(rate, 2)
        fun peak(fromMs: Long, lengthMs: Long): Int {
            val frames = (rate * lengthMs / 1000).toInt()
            val input = java.nio.ByteBuffer.allocate(frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val output = java.nio.ByteBuffer.allocate(frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            dsp.process(input, output, fromMs * 1000, out); output.flip()
            var top = 0; while (output.hasRemaining()) top = maxOf(top, abs(output.short.toInt())); return top
        }
        val early = peak(6_500, 200); val late = peak(9_700, 200); val after = peak(10_100, 200)
        assertTrue("swells ($early -> $late)", late > early * 3 && late > 500)
        assertTrue("never louder than its share of full scale ($late)", late < 32_768 * DeckAutomation.RISE_NOISE * 3)
        assertEquals("gone after the cut", 0, after)
        assertFalse(DeckAutomation.incoming(plan).usesNoise)
    }

    @Test fun aDeckThatIsHeardNeverChangesSpeed() {
        // The incoming deck is given one speed while still silent: in time with the outgoing title for a blend, its own otherwise.
        assertEquals(.98f, DeckSync.speedOf(beat.copy(kind = MixKind.Beatmatch, rate = .98f), 1f), 1e-6f)
        assertEquals("a title left 2 % fast by the previous blend", 1.0f, DeckSync.speedOf(beat.copy(kind = MixKind.Beatmatch, rate = .98f), 1.02f), .002f)
        assertEquals(1f, DeckSync.speedOf(beat.copy(kind = MixKind.Echo, rate = 1f), 1.02f), 0f)
        assertEquals(1f, DeckSync.speedOf(beat.copy(kind = MixKind.Filter), 1.03f), 0f)
        // Corrections stop early enough that the last one is played out of the audio buffer before the title is heard:
        // a cut is heard at once, a blend once its rising level means something.
        val cut = beat.copy(kind = MixKind.Echo)
        assertFalse(DeckSync.settled(cut, cut.inMs - 2_000)); assertTrue(DeckSync.settled(cut, cut.inMs - DeckSync.SETTLE_BEFORE_MS))
        assertTrue(DeckSync.heard(cut, cut.inMs)); assertFalse(DeckSync.heard(cut, cut.inMs - 1))
        val blend = beat.copy(kind = MixKind.Beatmatch, overlapMs = 32_000, rate = 1f)
        assertFalse("the first beats of a blend are still under -20 dB", DeckSync.heard(blend, blend.inMs + 2_000))
        assertTrue(DeckSync.heard(blend, blend.inMs + 4_000))
        assertFalse(DeckSync.settled(blend, blend.inMs + 1_000)); assertTrue(DeckSync.settled(blend, blend.inMs + 3_000))
        assertEquals(1.064f, DeckSync.stepped(1f / .94f), 1e-6f)
        assertEquals(1f, DeckSync.stepped(1.0004f), 0f)
    }

    @Test fun withoutAutomationTheSoundIsUntouched() {
        val dsp = DeckDsp(44_100, 2)
        val input = tone(440.0, 44_100, .1f, 2)
        val expected = ShortArray(input.remaining() / 2) { input.duplicate().order(ByteOrder.LITTLE_ENDIAN).getShort(it * 2) }
        assertArrayEquals(expected, run(dsp, input, 5_000_000, null))
        assertTrue(run(dsp, tone(440.0, 44_100, .1f, 2), 0, DeckAutomation.MUTE).all { it.toInt() == 0 })
    }

    @Test fun theLevelMovesWithoutSteps() {
        val dsp = DeckDsp(48_000, 1)
        val automation = DeckAutomation(Curve(longArrayOf(0, 1_000_000), floatArrayOf(0f, 1f)), Curve.flat(0f))
        val constant = ByteBuffer.allocate(48_000 * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(48_000) { putShort(20_000) }; flip() }
        // In two pieces, as buffers arrive.
        val first = constant.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { limit(30_000) }.slice()
        val second = constant.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(30_000) }.slice()
        val samples = run(dsp, first, 0, automation) + run(dsp, second, 15_000 * 1_000_000L / 48_000, automation)
        assertEquals(48_000, samples.size)
        assertTrue(samples.first() < 30); assertEquals(20_000.0, samples.last().toDouble(), 30.0)
        assertEquals(10_000.0, samples[24_000].toDouble(), 40.0)
        for (i in 1 until samples.size) assertTrue("step at $i", abs(samples[i] - samples[i - 1]) <= 2)
    }

    @Test fun theHighPassTakesTheBassAndLeavesTheRest() {
        val automation = DeckAutomation(Curve.flat(1f), Curve.flat(DeckAutomation.BASS_HZ))
        val rate = 44_100
        val skip = rate / 5
        val bass = rms(run(DeckDsp(rate, 1), tone(60.0, rate, 1f), 0, automation), skip) / rms(run(DeckDsp(rate, 1), tone(60.0, rate, 1f), 0, null), skip)
        val middle = rms(run(DeckDsp(rate, 1), tone(1_000.0, rate, 1f), 0, automation), skip) / rms(run(DeckDsp(rate, 1), tone(1_000.0, rate, 1f), 0, null), skip)
        val edge = rms(run(DeckDsp(rate, 1), tone(300.0, rate, 1f), 0, automation), skip) / rms(run(DeckDsp(rate, 1), tone(300.0, rate, 1f), 0, null), skip)
        assertTrue("60 Hz is taken away (${db(bass)} dB)", db(bass) < -24)
        assertEquals("1 kHz passes", 0.0, db(middle), .4)
        assertEquals("-3 dB at the cut-off", -3.0, db(edge), .6)
        // Two channels keep their own filter memory: opposite signals stay opposite.
        val stereo = run(DeckDsp(rate, 2), tone(1_000.0, rate, .2f, 2), 0, automation)
        for (i in 2_000 until stereo.size step 2) assertTrue(abs(stereo[i] + stereo[i + 1]) <= 2)
    }

    @Test fun theIncomingTitleIsNudgedBackInTime() {
        // Exactly where it should be: the outgoing title is 3 s into the mix, the incoming one 3 s × 0.96 into its part.
        assertEquals(0.0, DeckSync.errorMs(beat, 183_000, 500 + 2_880), 1e-3)
        assertEquals("48 ms of the title ahead is 50 ms on the clock", 50.0, DeckSync.errorMs(beat, 183_000, 500 + 2_880 + 48), 1e-3)
        assertEquals(-100.0, DeckSync.errorMs(beat, 183_100, 500 + 2_880), 1e-3)
        assertEquals("A few milliseconds are left alone", .96f, DeckSync.speed(.96f, 3.0, audible = false), 0f)
        assertTrue("Ahead: slower", DeckSync.speed(.96f, 30.0, audible = false) < .96f)
        assertTrue("Behind: faster", DeckSync.speed(.96f, -30.0, audible = false) > .96f)
        assertEquals("Never more than 4 %", .96f * .96f, DeckSync.speed(.96f, 900.0, audible = false), .002f)
        assertEquals("While heard, small errors are not chased", 1f, DeckSync.speed(1f, 10.0, audible = true), 0f)
        assertTrue(DeckSync.speed(1f, 30.0, audible = true) > DeckSync.speed(1f, 30.0, audible = false))
    }

    @Test fun aVoiceThatEndsStepsBackUnderTheIncomingTitle() {
        // The blend starts 2 s before the last words end (at 182 s); the voice band of the centre goes down over them.
        val plan = beat.copy(outMs = 180_000, sungUntilMs = 182_000)
        val out = DeckAutomation.outgoing(plan)
        assertTrue(out.usesVocalCut)
        assertEquals(0f, out.vocalCut.at(180_000_000), 1e-3f)
        assertEquals(DeckAutomation.VOICE_DOWN, out.vocalCut.at(182_000_000), 1e-3f)
        assertFalse(DeckAutomation.outgoing(beat).usesVocalCut)
        assertFalse(DeckAutomation.incoming(plan).usesVocalCut)

        // Heard: a 1 kHz tone in the centre (the "voice") is taken down; the same tone on one side only, and a 60 Hz bass, are kept.
        val rate = 44_100
        val cut = DeckAutomation(Curve.flat(1f), Curve.flat(0f), vocalCut = Curve(longArrayOf(0, 1), floatArrayOf(.8f, .8f)))
        fun stereo(hz: Double, left: Float, right: Float): ByteBuffer {
            val frames = rate
            val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) { val v = sin(2 * PI * hz * i / rate) * 12_000
                buffer.putShort((v * left).toInt().toShort()); buffer.putShort((v * right).toInt().toShort()) }
            buffer.flip(); return buffer
        }
        val skip = rate / 2
        fun ratio(input: () -> ByteBuffer) = rms(run(DeckDsp(rate, 2), input(), 0, cut), skip) / rms(run(DeckDsp(rate, 2), input(), 0, null), skip)
        assertTrue("centred voice band down (${db(ratio { stereo(1_000.0, 1f, 1f) })} dB)", db(ratio { stereo(1_000.0, 1f, 1f) }) < -9)
        assertTrue("the bass stays (${db(ratio { stereo(60.0, 1f, 1f) })} dB)", db(ratio { stereo(60.0, 1f, 1f) }) > -2.5)
        assertTrue("a side instrument mostly stays (${db(ratio { stereo(1_000.0, 1f, 0f) })} dB)", db(ratio { stereo(1_000.0, 1f, 0f) }) > -6)
        // Mono has no centre to tell apart: untouched.
        val mono = run(DeckDsp(rate, 1), tone(1_000.0, rate, .5f), 0, cut)
        assertTrue(rms(mono, rate / 10) > 0.9 * rms(run(DeckDsp(rate, 1), tone(1_000.0, rate, .5f), 0, null), rate / 10))
    }
}
