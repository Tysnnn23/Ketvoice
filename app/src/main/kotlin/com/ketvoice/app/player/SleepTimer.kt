package com.ketvoice.app.player

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _remaining = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remaining

    fun set(minutes: Int) {
        require(minutes in setOf(0, 15, 30, 60))
        job?.cancel()
        _remaining.value = minutes * 60L
        if (minutes == 0) return
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        job = scope.launch {
            while (isActive) {
                val left = SleepCountdown.remainingSeconds(deadline, SystemClock.elapsedRealtime())
                _remaining.value = left
                if (left == 0L) {
                    Player.pause()
                    break
                }
                delay(1000)
            }
        }
    }
}
