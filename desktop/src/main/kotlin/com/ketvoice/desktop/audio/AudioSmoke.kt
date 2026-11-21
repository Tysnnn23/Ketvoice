package com.ketvoice.desktop.audio

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** `--smoke`: a 1 s tone written as WAV, played through the whole engine into a [NullSink]. */
object AudioSmoke {
    fun run(): String {
        val rate = 44_100
        val pcm = ByteArray(rate * 4)
        for (i in 0 until rate) {
            val v = (12_000 * sin(2 * PI * 440.0 * i / rate)).toInt()
            for (c in 0..1) { pcm[i * 4 + c * 2] = v.toByte(); pcm[i * 4 + c * 2 + 1] = (v shr 8).toByte() }
        }
        val wav = File.createTempFile("ketvoice-smoke", ".wav")
        try {
            CaptureSink.writeWav(wav, pcm, rate, 2)
            val sink = NullSink()
            val engine = FfmpegAudioEngine(sink)
            try {
                val (format, end) = runBlocking {
                    val format = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is AudioEvent.Format } }
                    val end = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is AudioEvent.Ended || it is AudioEvent.Error } }
                    engine.load(AudioSource.LocalFile(wav.path))
                    withTimeout(15_000) { format.await() as AudioEvent.Format to end.await() }
                }
                check(end is AudioEvent.Ended) { "audio: $end" }
                val ms = sink.frames * 1000 / FfmpegAudioEngine.RATE
                check(ms in 990..1020) { "audio: $ms ms decoded" }
                return "audio ok (${format.codec} ${format.sampleRate} Hz to ${FfmpegAudioEngine.RATE} Hz, $ms ms)"
            } finally { engine.release() }
        } finally { wav.delete() }
    }
}
