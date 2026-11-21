package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.BEAT_ONNX_BYTES
import com.ketvoice.app.automix.BeatModel
import com.ketvoice.app.automix.BeatSpectrogram
import com.ketvoice.app.automix.OnnxBeatNet
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The beat model's download only keeps the pinned bytes; with the real file (opt-in, never downloaded by the
 * tests: `KETVOICE_BEAT_ONNX=/path/to/small0.onnx`), ONNX Runtime runs it and finds the beats of a click track.
 */
class BeatModelOnnxTest {
    private val dir = File("build/beat-model-test").apply { deleteRecursively(); mkdirs() }

    @After fun tearDown() { BeatModel.folder = null }

    @Test fun downloadKeepsOnlyThePinnedFile() {
        BeatModel.folder = dir
        assertFalse(BeatModel.download(fetch = { java.io.ByteArrayInputStream(ByteArray(1_000) { 7 }) }))
        assertFalse(BeatModel.ready)
        assertFalse(File(dir, "ketmix-beat-small0.onnx.part").exists())
        assertFalse(BeatModel.download(fetch = { null }))
    }

    @Test fun realModelFindsTheBeatsOfAClickTrack() {
        val path = System.getenv("KETVOICE_BEAT_ONNX")
        assumeTrue("set KETVOICE_BEAT_ONNX to run the real model", path != null && File(path).isFile)
        BeatModel.folder = dir
        assertTrue(BeatModel.download(fetch = { File(path!!).inputStream() }), "the pinned SHA-256 did not match")
        assertTrue(BeatModel.ready && BeatModel.file().length() == BEAT_ONNX_BYTES)
        val rate = BeatSpectrogram.RATE
        val beat = rate / 2 // 120 BPM
        val samples = FloatArray(rate * 30) { i ->
            val t = i % beat
            (.6 * exp(-t / (rate * .03)) * sin(2 * PI * 60.0 * t / rate) + .2 * exp(-t / (rate * .005)) * sin(2 * PI * 3_000.0 * t / rate)).toFloat()
        }
        val times = OnnxBeatNet(BeatModel.file()).use { BeatModel.predict(it, samples) }
        val gaps = times.beats.toList().zipWithNext { a, b -> b - a }
        println("ONNX beat model: ${times.beats.size} beats, ${times.downbeats.size} downbeats, median gap ${gaps.sorted().getOrNull(gaps.size / 2)}")
        assertTrue(times.beats.size in 25..62, "beats: ${times.beats.size}") // a bare click track: the network is sure of part of it
        val median = gaps.sorted()[gaps.size / 2]
        assertTrue(abs(median - .5) < .02 || abs(median - 500.0) < 20, "median gap $median")
    }
}
