package com.ketvoice.app.player

/**
 * « Fondu à la pause et à la reprise » (7.5): a pause or a resume from anywhere (the app, the notification, a
 * headset button, a widget, Android Auto) glides the volume over a fifth of a second instead of cutting the wave
 * (a click). The pause shows at once; the sound follows. Android's own interruptions (a call taking the audio
 * focus, a headset pulled out) stay immediate: the engine handles them without passing here.
 */
internal object PauseFade {
    const val MS = 200L
    const val STEP_MS = 10L

    /** The level at [t] (0..1) of a fade from [from] to [to], the equal-power curve of the skip fade. */
    fun level(from: Float, to: Float, t: Float): Float = SkipFade.level(from, to, t)

    /** The steps of a fade lasting [durationMs]. */
    fun steps(durationMs: Long = MS): Int = (durationMs / STEP_MS).toInt().coerceAtLeast(1)

    /**
     * Whether a pause or a resume fades: only while the sound is really heard (or about to be), never in
     * bit-perfect (the volume is not applied there) and never in the middle of a Ketmix mix, which moves the
     * volumes of its own decks.
     */
    fun applies(audible: Boolean, bitPerfect: Boolean, mixing: Boolean): Boolean = audible && !bitPerfect && !mixing
}
