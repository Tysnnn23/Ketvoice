package com.ketvoice.app.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EqualizerStatus(val sessionId: Int = 0, val available: Boolean = false, val applied: Boolean = false,
                           val centersHz: List<Int> = emptyList(),
                           /** True when Android's multi-band processor runs the ten bands exactly (5.9.0). */
                           val tenBands: Boolean = false)

/**
 * The equalizer of the playing session. Since 5.9.0 it asks Android's DynamicsProcessing for the
 * ten bands of [EqualizerProfile] exactly, with a limiter so that boosted bands never clip; on a
 * phone without it, the classic Equalizer takes over and the ten gains are interpolated onto its bands.
 */
object AudioEqualizer {
    private var effect: Equalizer? = null
    private var processing: DynamicsProcessing? = null
    private val _status = MutableStateFlow(EqualizerStatus())
    val status: StateFlow<EqualizerStatus> = _status

    fun bind(sessionId: Int) {
        if (AppSettings.bitPerfect.value) { release(); return }
        if (sessionId <= 0) { release(); return }
        if (_status.value.sessionId == sessionId && (effect != null || processing != null)) return
        release()
        if (bindProcessing(sessionId)) return
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

    private fun bindProcessing(sessionId: Int): Boolean = try {
        val bands = EqualizerProfile.frequencies.size
        val config = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, bands, false, 0, false, 0, true).build()
        val dp = DynamicsProcessing(0, sessionId, config)
        processing = dp
        _status.value = EqualizerStatus(sessionId, true, centersHz = EqualizerProfile.frequencies, tenBands = true)
        apply()
        true
    } catch (_: Throwable) {
        runCatching { processing?.release() }; processing = null
        false
    }

    fun apply() {
        if (AppSettings.bitPerfect.value) { release(); return }
        processing?.let { dp ->
            try {
                val gains = EqualizerProfile.normalize(AppSettings.equalizerGains.value)
                val eq = DynamicsProcessing.Eq(true, true, gains.size)
                gains.forEachIndexed { i, gain ->
                    eq.setBand(i, DynamicsProcessing.EqBand(true, EqualizerProfile.cutoffHz(i), gain.toFloat()))
                }
                dp.setPreEqAllChannelsTo(eq)
                // A boosted band never clips: the limiter holds the peaks just under full scale.
                dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
                dp.enabled = AppSettings.equalizerEnabled.value
                _status.value = _status.value.copy(available = true, applied = dp.enabled)
            } catch (_: Exception) {
                runCatching { dp.release() }; processing = null
                _status.value = _status.value.copy(available = false, applied = false, tenBands = false)
            }
            return
        }
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
        runCatching { processing?.release() }; processing = null
        _status.value = EqualizerStatus()
    }
}
