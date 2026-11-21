package com.ketvoice.desktop.audio

import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.EqualizerProfile

/** The listener's equalizer (`equalizerEnabled`, `equalizerGains`, as on the phone) on the engine's ten bands. */
object DesktopEqualizer {
    @Volatile var engine: AudioEngine? = null

    /** The gains to play, in dB, or null for flat (switched off). */
    fun gains(): FloatArray? =
        if (!AppSettings.equalizerEnabled.value) null
        else EqualizerProfile.normalize(AppSettings.equalizerGains.value).map { it.toFloat() }.toFloatArray()

    fun apply() { engine?.setEqualizer(gains()) }

    fun attach(engine: AudioEngine) { this.engine = engine; apply() }
}
