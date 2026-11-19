package com.ketvoice.app.player

/**
 * What may use the network before a title's first sound (7.2). The first sound waits for the first half
 * second of audio; everything else that downloads at the same time shares the connection with it. Small
 * answers (lyrics, a cover) go at once; a Canvas video only when it is already on the device; the whole
 * file for the cache and the next titles only once the sound is heard.
 */
internal object StartupOrder {
    /** The longest the deferred loads wait for the first sound. */
    const val WAIT_FOR_SOUND_MS = 4_000L

    enum class Load { Lyrics, Artwork, Canvas, WholeFile, NextTitles }

    fun mayLoadBeforeSound(load: Load, onDevice: Boolean): Boolean = when (load) {
        Load.Lyrics, Load.Artwork -> true
        Load.Canvas -> onDevice
        Load.WholeFile, Load.NextTitles -> false
    }
}
