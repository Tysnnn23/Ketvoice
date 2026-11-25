package com.ketvoice.desktop.lyrics

import com.ketvoice.app.data.MusicApi
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordAlignerTest {
    /**
     * A model whose attention points each word at the moment it is sung ([sung], seconds into the song). The voice holds
     * its own time in seconds, so the window's start is known from the samples the encoder gets.
     */
    private class FakeModel(private val sung: Map<String, Double>, private val probability: Float = .9f) : AlignModel {
        private val ids = HashMap<String, Int>()
        private val words = HashMap<Int, String>()
        override val heads = 2
        override fun tokens(text: String): List<Int> = listOf(ids.getOrPut(text.trim()) { (1_000 + ids.size).also { words[it] = text.trim() } })
        override fun encode(window: FloatArray) = floatArrayOf(window[0])
        override fun decode(tokens: LongArray, features: FloatArray): AlignModel.Decoded {
            val offset = features[0].toDouble()
            val t = tokens.size
            val cross = FloatArray(heads * t * AlignMath.AUDIO_FRAMES)
            for (h in 0 until heads) for (r in 0 until t) {
                // A position attends to the audio of the token it predicts (the next one), as Whisper's decoder does:
                // from that word's start to the next word's.
                fun at(i: Int) = tokens.getOrNull(i)?.let { words[it.toInt()] }?.let { (sung.getValue(it) - offset) * AlignMath.FRAMES_PER_SECOND }
                val from = at(r + 1) ?: if (r < 3) 0.0 else 1_400.0
                val to = at(r + 2) ?: (from + 25)
                for (f in 0 until AlignMath.AUDIO_FRAMES) cross[(h * t + r) * AlignMath.AUDIO_FRAMES + f] = if (f >= from && f < to) 8f else 0f
            }
            val lang = FloatArray(51_865).also { it[WhisperTokens.languageToken("en")!!] = 3f }
            return AlignModel.Decoded(cross, FloatArray(t - 1) { probability }, lang)
        }
        override fun close() {}
    }

    private fun voice(seconds: Int) = FloatArray(seconds * AlignMath.RATE) { it.toFloat() / AlignMath.RATE }

    private val sung = mapOf("hello" to 10.0, "there" to 10.6, "world" to 11.3, "second" to 14.0, "verse" to 14.5, "comes" to 15.4,
        "and" to 30.0, "the" to 30.4, "end" to 31.1)
    private val lines = listOf(MusicApi.LyricLine(10_000, "hello there world"), MusicApi.LyricLine(14_000, "second verse comes"),
        MusicApi.LyricLine(30_000, "and the end"))

    @Test fun lineTimedLyricsGetTheirWordsTimed() {
        val out = assertNotNull(WordAligner(FakeModel(sung)).align(lines, voice(40)))
        for (line in out) {
            assertTrue(line.words.size == 3, "$line")
            for (w in line.words) assertTrue(abs(w.startMs - sung.getValue(w.text.trim()) * 1000) <= 60, "${w.text} at ${w.startMs}")
        }
        // Line times are the source's.
        assertEquals(lines.map { it.timeMs }, out.map { it.timeMs })
    }

    @Test fun untimedLyricsGetLineAndWordTimes() {
        val plain = lines.map { it.copy(timeMs = -1) }
        val out = assertNotNull(WordAligner(FakeModel(sung)).align(plain, voice(40)))
        out.forEachIndexed { i, line ->
            assertTrue(abs(line.timeMs - lines[i].timeMs) <= 600, "line $i at ${line.timeMs}")
            assertTrue(line.words.drop(1).all { w -> abs(w.startMs - sung.getValue(w.text.trim()) * 1000) <= 60 }, "$line")
        }
    }

    @Test fun lyricsThatAreNotSungAreRefused() {
        assertNull(WordAligner(FakeModel(sung, probability = .01f)).align(lines, voice(40)))
    }

    /**
     * The real model on Whisper's own test clip, when it is on this machine (never in CI): KETVOICE_ALIGN_DIR holds the
     * three files of `ketvoice-align-v1`, KETVOICE_ALIGN_CLIP a 16 kHz mono float32 little-endian dump of jfk.flac.
     * The expected starts are those of whisper.timing.find_alignment on that clip (export_whisper.py --fixtures).
     */
    @Test fun realModelOnTheReferenceClip() {
        val dir = System.getenv("KETVOICE_ALIGN_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val clip = System.getenv("KETVOICE_ALIGN_CLIP")?.let(::File)?.takeIf { it.isFile } ?: return
        val bytes = java.nio.ByteBuffer.wrap(clip.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val voice = FloatArray(bytes.remaining()).also { bytes.get(it) }
        val text = "And so my fellow Americans ask not what your country can do for you ask what you can do for your country"
        val expected = listOf(0.0, 0.52, 0.86, 1.2, 1.52, 2.12, 3.7, 4.24, 5.54, 5.78, 6.28, 6.62, 6.86, 7.08, 7.44, 8.46, 8.76, 9.08, 9.36, 9.58, 9.78, 9.94)
        WhisperModel(File(dir, "ketvoice-align-encoder.onnx"), File(dir, "ketvoice-align-decoder.onnx"), File(dir, "ketvoice-align-vocab.tiktoken"), 4).use { m ->
            val out = assertNotNull(WordAligner(m).align(listOf(MusicApi.LyricLine(0, text)), voice))
            val starts = out[0].words.map { it.startMs / 1000.0 }
            println("jfk.flac in Kotlin: " + out[0].words.joinToString(" ") { "${it.text.trim()}@${it.startMs / 1000.0}" })
            starts.forEachIndexed { i, s -> assertTrue(abs(s - expected[i]) <= .045, "word $i at $s, whisper ${expected[i]}") }
        }
    }
}
