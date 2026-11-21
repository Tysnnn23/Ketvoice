package com.ketvoice.app.automix

/**
 * Desktop counterpart of the `TempoReturn` that lives in the Android `Automix.kt` (which also holds the
 * Media3 decks, so it is not shared): the same steps, restated value for value. After a mix in time the
 * incoming title glides back to its own speed in steps no listener hears as a change of speed; the desktop
 * mixer plays the glide ([com.ketvoice.desktop.audio.DesktopMixer]). Keep it equal to the Android one.
 */
internal object TempoReturn {
    const val STEP = .0025f
    const val EVERY_MS = 500L
    /** The blend's last bars ring out on the title's mixed speed before the glide starts. */
    const val WAIT_MS = 2_000L

    fun next(speed: Float): Float = when {
        kotlin.math.abs(speed - 1f) <= STEP -> 1f
        speed > 1f -> speed - STEP
        else -> speed + STEP
    }

    /** How long the glide back from [speed] takes. */
    fun durationMs(speed: Float): Long = (kotlin.math.ceil(kotlin.math.abs(speed - 1f) / STEP - 1e-4).toLong()) * EVERY_MS
}
