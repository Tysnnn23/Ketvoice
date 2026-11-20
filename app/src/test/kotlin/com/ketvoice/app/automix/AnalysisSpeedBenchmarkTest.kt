package com.ketvoice.app.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/**
 * 7.5 « l'analyse aussi »: where the time of Ketmix's reading of a title goes, stage by stage, on the longest
 * stretch it reads (« Maximum », 75 s) of a 44.1 kHz stereo title, fed the way the silent deck feeds it.
 * Timed for real on this JVM (median of 5 after a warm-up); the results must not change.
 */
class AnalysisSpeedBenchmarkTest {
    private val rate = 44_100
    private val seconds = 75

    /** 124 BPM: a kick on each beat, a hat on each half beat, an A minor chord held. */
    private val pcm: ByteBuffer by lazy {
        val frames = rate * seconds
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val beat = 60.0 / 124
        var noise = 12345L
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val sinceBeat = t % beat
            val sinceHat = t % (beat / 2)
            noise = noise * 6364136223846793005L + 1442695040888963407L
            val white = ((noise ushr 33).toInt() and 0xFFFF) / 32768.0 - 1.0
            val kick = sin(2 * PI * (55 + 90 * exp(-sinceBeat * 40)) * sinceBeat) * exp(-sinceBeat * 9)
            val hat = white * exp(-sinceHat * 120) * .25
            val chord = (sin(2 * PI * 220.0 * t) + sin(2 * PI * 261.63 * t) + sin(2 * PI * 329.63 * t)) * .08
            val v = ((kick * .6 + hat + chord) * 20_000).toInt().coerceIn(-32768, 32767).toShort()
            buffer.putShort(v); buffer.putShort(v)
        }
        buffer.flip(); buffer
    }

    private fun chunks(): List<ByteBuffer> {
        val out = ArrayList<ByteBuffer>()
        var at = 0
        val step = 4096 * 4
        while (at < pcm.limit()) {
            val end = minOf(pcm.limit(), at + step)
            out += pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(at); limit(end) }.slice().order(ByteOrder.LITTLE_ENDIAN)
            at = end
        }
        return out
    }

    private fun median(block: () -> Unit): Double {
        block()
        val times = (0 until 5).map { val s = System.nanoTime(); block(); (System.nanoTime() - s) / 1e6 }.sorted()
        return times[2]
    }

    /** The phase tables give what the direct sum gives (float rounding aside), for the rates phones decode at. */
    @Test fun thePhaseTablesResampleAsBefore() {
        val signal = FloatArray(200_003) { i -> (sin(i * .013) * .6 + sin(i * .37) * .3).toFloat() }
        for ((from, to) in listOf(44_100 to 22_050, 48_000 to 22_050, 44_100 to 16_000, 96_000 to 44_100, 22_050 to 44_100)) {
            val fast = com.ketvoice.app.stems.Resampler.resample(signal, from, to)
            val direct = com.ketvoice.app.stems.Resampler.resampleDirect(signal, from, to)
            assertEquals(direct.size, fast.size)
            val worst = direct.indices.maxOf { kotlin.math.abs(direct[it] - fast[it]) }
            println("resample-equal: $from -> $to worst difference $worst")
            assertTrue("$from -> $to differs by $worst", worst < 1e-5f)
        }
    }

    @Test fun timeEachStageOfAReading() {
        val parts = chunks()
        var envelope: OnsetEnvelope? = null
        val onsets = median { val t = OnsetTracker(rate, 2, withKey = false); parts.forEach { t.accept16(it) }; envelope = t.snapshot() }
        var keyed: OnsetEnvelope? = null
        val withKey = median { val t = OnsetTracker(rate, 2, withKey = true); parts.forEach { t.accept16(it) }; keyed = t.snapshot() }
        var beatPcm: BeatPcm? = null
        val beatFeed = median { val b = BeatPcm(rate, 2, 0L); parts.forEach { b.accept16(it) }; beatPcm = b }
        var window: FloatArray? = null
        val resample = median { window = beatPcm!!.window(true)!!.first }
        val spectrogram = median { BeatSpectrogram.compute(window!!) }
        var analysis: SegmentAnalysis? = null
        val beats = median { analysis = BeatAnalysis.analyze(keyed!!, 0L, thorough = true) }
        val total = withKey + beatFeed + resample + spectrogram + beats
        val a = analysis!!
        println("analysis-speed: 75 s 44.1 kHz stereo — onsets ${"%.1f".format(onsets)} ms, onsets+key ${"%.1f".format(withKey)} ms " +
            "(key ${"%.1f".format(withKey - onsets)}), beat PCM ${"%.1f".format(beatFeed)} ms, resample to 22.05 kHz ${"%.1f".format(resample)} ms, " +
            "mel spectrogram ${"%.1f".format(spectrogram)} ms, beats/bars/structure ${"%.1f".format(beats)} ms; total ${"%.1f".format(total)} ms")
        println("analysis-result: bpm ${a.bpm} key ${a.key} beatAt ${a.beatAtMs} bar ${a.barOffset} conf ${a.confidence} loudness ${a.loudnessDb} frames ${envelope!!.flux.size}")
        assertEquals(124f, a.bpm, 1.5f)
    }
}
