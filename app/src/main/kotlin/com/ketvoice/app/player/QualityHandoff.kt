package com.ketvoice.app.player

import kotlin.math.abs

internal object QualityHandoff {
    /** Do not silently switch to another edit, a live rendition or an ended track. */
    fun compatible(oldDuration: Long, newDuration: Long, position: Long): Boolean =
        oldDuration > 0 && newDuration > 0 && abs(oldDuration - newDuration) <= 1500 &&
            position >= 0 && position < minOf(oldDuration, newDuration) - 2000
    fun aligned(oldPosition: Long, newPosition: Long): Boolean = abs(oldPosition - newPosition) <= 60
}
