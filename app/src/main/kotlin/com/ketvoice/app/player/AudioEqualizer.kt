package com.ketvoice.app.player

import android.media.audiofx.Equalizer
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EqualizerStatus(val sessionId: Int = 0, val available: Boolean = false, val applied: Boolean = false,
                           val centersHz: List<Int> = emptyList())

/** PlaybackService owns a native effect on ExoPlayer's *real* audio session. */
object AudioEqualizer {
    private var effect: Equalizer? = null
    private val _status = MutableStateFlow(EqualizerStatus())
    val status: StateFlow<EqualizerStatus> = _status
    fun bind(sessionId: Int) {
        if (sessionId <= 0) { release(); return }
        if (_status.value.sessionId == sessionId && effect != null) return
        release()
        try {
            val eq = Equalizer(0, sessionId)
            effect = eq
            val count = eq.numberOfBands.toInt().coerceIn(0, 32)
            require(count > 0)
            _status.value = EqualizerStatus(sessionId, true, centersHz = List(count) { eq.getCenterFreq(it.toShort()) / 1000 })
            apply()
        } catch (_: Exception) {
            runCatching { effect?.release() }; effect = null
            _status.value = EqualizerStatus(sessionId)
        }
    }
    fun apply() {
        val eq = effect ?: return
        try {
            val range = eq.bandLevelRange
            _status.value.centersHz.forEachIndexed { i, frequency ->
                val gain = EqualizerProfile.millibels(frequency.toFloat(), AppSettings.equalizerGains.value, range[0].toInt(), range[1].toInt()).toShort()
                if (eq.getBandLevel(i.toShort()) != gain) eq.setBandLevel(i.toShort(), gain)
            }
            eq.enabled = AppSettings.equalizerEnabled.value
            _status.value = _status.value.copy(available = true, applied = eq.enabled)
        } catch (_: Exception) {
            runCatching { eq.release() }; effect = null
            _status.value = _status.value.copy(available = false, applied = false)
        }
    }
    fun release() {
        runCatching { effect?.release() }; effect = null
        _status.value = EqualizerStatus()
    }
}
