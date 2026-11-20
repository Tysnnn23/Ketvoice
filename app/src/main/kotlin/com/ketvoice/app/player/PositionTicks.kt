package com.ketvoice.app.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The position rail's clock (7.6): 200 ms steps while the app is shown, a second behind other apps, and none at
 * all while nothing plays — until 7.5 a paused app woke the main thread five times a second, the screen off included.
 * A paused wait still looks again every [PAUSED_RECHECK_MS], should the controller play without the state saying so.
 */
internal object PositionTicks {
    const val PAUSED_RECHECK_MS = 10_000L

    suspend fun run(state: StateFlow<PlayerUiState>, playing: () -> Boolean, inFront: () -> Boolean, tick: () -> Unit) {
        while (true) {
            if (playing()) tick()
            else if (idle(state.value)) {
                withTimeoutOrNull(PAUSED_RECHECK_MS) { state.first { !idle(it) } }
                continue
            }
            delay(if (inFront()) 200 else 1_000)
        }
    }

    private fun idle(s: PlayerUiState) = !s.isPlaying && !s.playWhenReady
}
