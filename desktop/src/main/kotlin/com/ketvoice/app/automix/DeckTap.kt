package com.ketvoice.app.automix

import java.nio.ByteBuffer

/**
 * The tap a deck's sound is read through (same contract as in the Android `DeckMixProcessor.kt`,
 * which also holds Media3 code and so is not shared). The desktop mixer of Phase 2 feeds it.
 */
internal interface PcmTap {
    /** [buffer] is interleaved 16-bit PCM whose first frame is at [positionUs] in the title. Must not consume it. */
    fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int)
}
