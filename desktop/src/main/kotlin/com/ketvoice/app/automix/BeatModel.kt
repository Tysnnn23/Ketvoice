package com.ketvoice.app.automix

import java.nio.ByteBuffer

/**
 * Ketmix's optional rhythm model on the desktop. The Android app runs Beat This! through LiteRT,
 * which has no desktop build; Ketvoice for Windows will run it through ONNX Runtime (Phase 3).
 * Until then the desktop is an install without the model: [usable] is false, so Ketmix keeps its
 * own beat analysis, exactly as an Android phone that never downloaded the model does.
 */
internal object BeatModel {
    fun usable(): Boolean = false

    /** Without the model the signal analysis is the result. */
    fun refine(signal: SegmentAnalysis, pcm: BeatPcm, tail: Boolean): SegmentAnalysis = signal
}

/** Collects the PCM the model reads. With no model on the desktop yet, nothing is kept. */
internal class BeatPcm(@Suppress("unused") private val rate: Int, @Suppress("unused") private val channels: Int,
                       @Suppress("unused") private val firstUs: Long) {
    fun accept16(buffer: ByteBuffer) = Unit
}
