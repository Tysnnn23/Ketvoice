@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

object SonicMeter : TeeAudioProcessor.AudioBufferSink {
    private val analyzer = SonicAnalyzer()
    private val _levels = MutableStateFlow(SonicLevels())
    val levels: StateFlow<SonicLevels> = _levels
    @Volatile private var observed = false
    /** Who is listening: the meter runs while at least one of them is (the backdrop, the sonic canvas, the living artwork). */
    private val watchers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var resetRequested = true
    @Volatile private var supported = false
    @Volatile private var lastData = 0L
    fun observe(on: Boolean) { if (!on) watchers.clear(); observed = on; if (!on) reset() }
    fun observe(who: String, on: Boolean) {
        if (on) watchers.add(who) else watchers.remove(who)
        val any = watchers.isNotEmpty()
        if (any != observed) { observed = any; if (!any) reset() }
    }
    fun reset() { resetRequested = true; lastData = 0; _levels.value = SonicLevels() }
    fun expire() { if (System.nanoTime() - lastData > 450_000_000L) _levels.value = SonicLevels() }
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        supported = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT
        analyzer.configure(sampleRateHz, channelCount, encoding == C.ENCODING_PCM_FLOAT)
        reset()
    }
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!observed || !supported) return
        if (resetRequested) { analyzer.reset(); resetRequested = false }
        analyzer.accept(buffer)?.let {
            lastData = System.nanoTime()
            _levels.value = it
        }
    }
}
