package com.ketvoice.app.player

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sleep timer: pauses after a delay, with a short fade so the music does not cut off,
 * or right at the end of the current title.
 */
object SleepTimer {
    val CHOICES get() = SleepCountdown.CHOICES

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var faded = false
    private val _remaining = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remaining
    private val _endOfTrack = MutableStateFlow(false)
    /** True while playback is set to stop when the current title ends. */
    val endOfTrack: StateFlow<Boolean> = _endOfTrack

    fun cancel() = set(0)

    fun set(minutes: Int) {
        require(minutes == 0 || minutes in CHOICES)
        clear()
        _remaining.value = minutes * 60L
        if (minutes == 0) return
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        job = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val left = SleepCountdown.remainingSeconds(deadline, now)
                _remaining.value = left
                if (left == 0L) {
                    Player.pause()
                    restoreGain()
                    break
                }
                val leftMs = deadline - now
                if (leftMs <= SleepCountdown.FADE_MS) {
                    faded = true
                    Player.setFadeGain(SleepCountdown.fadeGain(leftMs))
                    delay(100)
                } else delay(minOf(1000L, leftMs - SleepCountdown.FADE_MS).coerceAtLeast(50L))
            }
        }
    }

    /** Stops after the title that is playing; a title on repeat stops at its end too. */
    fun stopAfterTrack() {
        clear()
        _endOfTrack.value = true
        job = scope.launch {
            while (isActive) {
                val state = Player.state.value
                // A repeated title never reports its end: stop it just before it loops.
                if (state.repeatOne && state.isPlaying && state.durationMs > 0 &&
                    state.durationMs - Player.currentPositionMs() in 0..400) {
                    _endOfTrack.value = false
                    Player.pause()
                    Player.seekTo(0L)
                    break
                }
                delay(200)
            }
        }
    }

    /** Called by the player when a title ends: true once if playback must stop here. */
    internal fun consumeEndOfTrack(): Boolean {
        if (!_endOfTrack.value) return false
        job?.cancel()
        job = null
        _endOfTrack.value = false
        return true
    }

    private fun clear() {
        job?.cancel()
        job = null
        _endOfTrack.value = false
        _remaining.value = 0L
        restoreGain()
    }

    private fun restoreGain() {
        if (faded) Player.setFadeGain(1f)
        faded = false
    }
}
